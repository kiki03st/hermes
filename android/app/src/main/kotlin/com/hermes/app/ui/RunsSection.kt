package com.hermes.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.app.HermesSettings
import com.hermes.app.HttpTransport
import com.hermes.app.RunEvent
import com.hermes.app.RunStartOutcome
import com.hermes.app.RunsClient
import com.hermes.app.SseTransport
import com.hermes.app.UrlConnectionHttpTransport
import com.hermes.app.UrlConnectionSseTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * `/v1/runs`를 통한 실행 — 승인 게이트 2차(MCP 트러스트 게이트)는 이 경로에만 있다
 * (`/v1/chat/completions`엔 승인 세션이 없다). CAD 쓰기 도구가 폰에서 승인을 받으려면
 * 이 화면을 거쳐야 한다.
 *
 * ⚠️ **알려진 제약(실측, 2026-08-28)**: `/v1/runs/{id}/events`는 도구 호출의 인자·반환값을
 * 노출하지 않는다 — `tool.completed` 이벤트는 `{tool, duration, error}`뿐이다(소스+실측
 * 확인). 그래서 이 화면은 acad-assist 도구의 1차 미리보기(`ActionPreview.summary`/
 * `preview_image`)를 승인 다이얼로그에 함께 보여줄 수 **없다** — 원래 계획(§D)이 "직전
 * tool.completed의 1차 미리보기를 함께 표시"라고 했던 것은 실제 프로토콜로는 불가능하다는
 * 뜻이다. 다이얼로그는 트러스트 게이트(2차)가 만드는 generic 텍스트(`command`/
 * `description`)와 `choices` 버튼만 보여준다. 캡처 PNG 같은 도구 결과물을 폰에 보여주려면
 * Hermes 쪽에 별도 통로(`/v1/artifacts/upload`, `/v1/artifacts/download` — 지금은 browser-control 전용으로 보이고
 * MCP 도구 결과에도 쓰이는지 미확인)가 필요하다 — 이미지 뷰어는 그 통로가 확정되기 전까지
 * 보류한다.
 */
@Composable
fun RunsSection(settings: HermesSettings) {
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    var runId by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf("") }
    var statusLine by remember { mutableStateOf("") }
    var finalText by remember { mutableStateOf("") }
    var pendingApproval by remember { mutableStateOf<RunEvent.ApprovalRequest?>(null) }
    var resolvingApproval by remember { mutableStateOf(false) }

    fun client(): RunsClient {
        val transport: HttpTransport = UrlConnectionHttpTransport()
        val sse: SseTransport = UrlConnectionSseTransport()
        return RunsClient(transport, sse, serverUrl = { settings.serverUrl }, apiKey = { settings.apiKey })
    }

    fun collect(id: String) {
        scope.launch {
            client().events(id)
                .flowOn(Dispatchers.IO)
                .collect { event ->
                    when (event) {
                        is RunEvent.MessageDelta -> transcript += event.delta
                        is RunEvent.ReasoningAvailable -> statusLine = "생각 중"
                        is RunEvent.ToolStarted -> statusLine = "도구 실행 중: ${event.tool ?: "?"}"
                        is RunEvent.ToolCompleted -> statusLine =
                            if (event.isError) "도구 실패: ${event.tool ?: "?"}" else "도구 완료: ${event.tool ?: "?"}"
                        is RunEvent.ApprovalRequest -> pendingApproval = event
                        is RunEvent.ApprovalResponded -> pendingApproval = null
                        is RunEvent.RunCompleted -> {
                            finalText = event.output
                            statusLine = ""
                            running = false
                        }
                        is RunEvent.RunFailed -> {
                            finalText = "오류: ${event.error}"
                            statusLine = ""
                            running = false
                        }
                        is RunEvent.RunCancelled -> {
                            finalText = "취소됨"
                            statusLine = ""
                            running = false
                        }
                        is RunEvent.RunSteered, is RunEvent.Unknown -> Unit
                    }
                }
        }
    }

    fun start() {
        val text = input
        if (text.isBlank() || running) return
        running = true
        transcript = ""
        statusLine = ""
        finalText = ""
        pendingApproval = null
        scope.launch {
            when (val outcome = withContext(Dispatchers.IO) { client().startRun(text) }) {
                is RunStartOutcome.Started -> {
                    runId = outcome.runId
                    collect(outcome.runId)
                }
                is RunStartOutcome.Failure -> {
                    finalText = "시작 실패 (${outcome.statusCode}): ${outcome.message}"
                    running = false
                }
            }
        }
    }

    fun resolve(choice: String) {
        val id = runId ?: return
        resolvingApproval = true
        scope.launch {
            withContext(Dispatchers.IO) { client().approve(id, choice) }
            pendingApproval = null
            resolvingApproval = false
        }
    }

    // 이 섹션은 HermesApp의 바깥 Column이 이미 verticalScroll을 갖고 있어서 여기서는
    // 안 붙인다 — 스크롤 가능한 Column을 무한 높이 제약 안에 중첩하면 Compose가
    // "vertically scrollable component was measured with an infinity maximum height
    // constraints" 로 크래시한다.
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("실행 (승인 게이트 포함)", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("요청") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(enabled = !running && input.isNotBlank(), onClick = { start() }) {
            Text(if (running) "실행 중..." else "실행")
        }
        if (statusLine.isNotBlank()) {
            Text(statusLine, style = MaterialTheme.typography.bodySmall)
        }
        if (transcript.isNotBlank()) {
            Text(transcript)
        }
        if (finalText.isNotBlank()) {
            Text(finalText, style = MaterialTheme.typography.bodyMedium)
        }
    }

    pendingApproval?.let { approval ->
        ApprovalDialog(approval = approval, resolving = resolvingApproval, onChoice = ::resolve)
    }
}

/** `choices`를 그대로 버튼으로 렌더한다 — 하드코딩 금지(계획 §B, `_approval_event_choices`가
 * 상황별로 좁혀서 준다: `["once","deny"]`만 올 때도, 4개 다 올 때도 있다). */
@Composable
private fun ApprovalDialog(
    approval: RunEvent.ApprovalRequest,
    resolving: Boolean,
    onChoice: (String) -> Unit,
) {
    val command = approval.raw["command"]?.jsonPrimitive?.contentOrNull
    val description = approval.raw["description"]?.jsonPrimitive?.contentOrNull

    AlertDialog(
        onDismissRequest = {}, // 승인 다이얼로그는 명시적 선택 없이 닫히면 안 된다.
        title = { Text("승인이 필요합니다") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                command?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Column(
                horizontalAlignment = androidx.compose.ui.Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                approval.choices.forEach { choice ->
                    Button(enabled = !resolving, onClick = { onChoice(choice) }) {
                        Text(approvalChoiceLabel(choice))
                    }
                }
            }
        },
    )
}

private fun approvalChoiceLabel(choice: String): String = when (choice) {
    "once" -> "이번만 승인"
    "session" -> "이 대화 동안 계속 승인"
    "always" -> "항상 승인"
    "deny" -> "거부"
    else -> choice
}
