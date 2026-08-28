package com.hermes.app.ui.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hermes.app.FileUploadClient
import com.hermes.app.HermesRuntime
import com.hermes.app.UploadOutcome
import com.hermes.app.WakeWordService
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 메인 챗봇 화면 — 입력은 전부 `/v1/runs`(승인 게이트 포함) 하나로만 나간다(계획 결정 #1).
 * 옛 `RunsSection.kt`가 하던 일이 [ChatConversationState]/[ChatReducer]/[ChatMessageList]로
 * 흡수됐다.
 *
 * [state]는 [com.hermes.app.ui.HermesApp]이 소유·전달한다(여기서 안 만듦) — 직접 타이핑
 * 경로와 웨이크워드 핸즈프리 경로가 같은 인스턴스를 공유해야 하기 때문(웨이크워드 계획 §1).
 *
 * 입력창의 마이크 버튼은 "제우스" 웨이크워드(항상 듣기)와 완전히 독립적이다 — 앱이 이미
 * 열려있는 상태에서 버튼을 눌러 직접 트리거하는 것이므로 BAL 제한이 걸릴 이유가 없어
 * `WakeWordService`의 헤드리스 방식이 아니라 `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`
 * 액티비티를 그대로 띄운다(Wear 앱과 같은 방식). 인식되면 웨이크워드 경로와 동일하게
 * 확인 없이 바로 전송한다(계획 확인).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(state: ChatConversationState, onOpenSettings: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val pendingApproval = (state.messages.lastOrNull() as? ChatMessage.AssistantTurn)?.approval
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uploading by remember { mutableStateOf(false) }

    val attachLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        uploading = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    val selected = readSelectedFile(context, uri)
                    if (selected == null) {
                        UploadOutcome.Failure(0, "파일을 읽을 수 없습니다")
                    } else {
                        FileUploadClient(
                            uploadServerUrl = { HermesRuntime.currentSettings.uploadServerUrl },
                            apiKey = { HermesRuntime.currentSettings.apiKey },
                        ).upload(selected.name, selected.mimeType, selected.bytes)
                    }
                } catch (e: Exception) {
                    // ContentResolver 쪽(SecurityException/IOException 등)은 FileUploadClient의
                    // 내부 try-catch 범위 밖이라 여기서 따로 잡는다 — 안 잡으면 scope.launch가
                    // 예외를 삼키지 않고 그대로 앱을 죽인다.
                    UploadOutcome.Failure(0, "파일 읽기 오류: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            uploading = false
            when (outcome) {
                is UploadOutcome.Success -> {
                    val caption = input
                    input = ""
                    state.submit(
                        "$caption\n\n첨부 파일 경로: ${outcome.path}\n${outcome.note}".trim(),
                    )
                }
                is UploadOutcome.Failure ->
                    state.reportSystemNotice("파일 업로드 실패 (${outcome.statusCode}): ${outcome.message}")
            }
        }
    }

    // 웨이크워드가 항상 듣기 중이면 그쪽 AudioRecord랑 이 버튼의 STT가 마이크를 동시에
    // 잡으려고 해서 충돌한다(마이크는 단일 클라이언트 제약) — 켜져있을 때만, 시작 전엔
    // 일시정지, 끝나면(성공/실패/취소 무관) 재개 신호를 보낸다. 꺼져있으면 굳이 서비스를
    // 새로 띄우지 않게 아예 호출을 안 한다.
    fun pauseWakeWordIfRunning() {
        if (HermesRuntime.currentSettings.wakeWordEnabled) {
            context.startService(
                Intent(context, WakeWordService::class.java).setAction(WakeWordService.ACTION_PAUSE_FOR_EXTERNAL_STT),
            )
        }
    }

    fun resumeWakeWordIfRunning() {
        if (HermesRuntime.currentSettings.wakeWordEnabled) {
            context.startService(
                Intent(context, WakeWordService::class.java).setAction(WakeWordService.ACTION_RESUME_AFTER_EXTERNAL_STT),
            )
        }
    }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        resumeWakeWordIfRunning()
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!text.isNullOrBlank()) state.submit(text)
    }

    fun launchSpeechRecognition() {
        pauseWakeWordIfRunning()
        speechLauncher.launch(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREAN)
            },
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) launchSpeechRecognition() }

    fun onMicClick() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            launchSpeechRecognition()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hermes") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "설정")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ChatMessageList(
                messages = state.messages,
                onApprovalChoice = { turnId, choice -> state.resolveApproval(turnId, choice) },
                modifier = Modifier.weight(1f),
                revision = state.revision,
            )
            InputBar(
                value = input,
                onValueChange = { input = it },
                enabled = pendingApproval == null,
                sending = state.isRunning,
                uploading = uploading,
                onMicClick = ::onMicClick,
                onAttachClick = { attachLauncher.launch("*/*") },
                onSend = {
                    val text = input
                    input = ""
                    state.submit(text)
                },
            )
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    sending: Boolean,
    uploading: Boolean,
    onMicClick: () -> Unit,
    onAttachClick: () -> Unit,
    onSend: () -> Unit,
) {
    Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onAttachClick, enabled = enabled && !sending && !uploading) {
                // 클립 이모지 — 마이크 버튼(🎙)과 같은 이유로 material-icons-extended 없이 표시
                Text(if (uploading) "…" else "📎")
            }
            IconButton(onClick = onMicClick, enabled = enabled && !sending) {
                // 마이크 이모지 — material-icons-extended 의존성 없이 표시 (Wear 앱과 같은 방식)
                Text("🎙")
            }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                placeholder = { Text(if (enabled) "메시지 보내기" else "승인 대기 중") },
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = enabled && !sending && value.isNotBlank(),
                onClick = onSend,
            ) {
                Text("전송")
            }
        }
    }
}

private data class SelectedFile(val name: String, val mimeType: String, val bytes: ByteArray)

/** [android.provider.OpenableColumns.DISPLAY_NAME]으로 원본 파일명을 얻는다 —
 * `content://` URI엔 실제 경로가 없어 이 방법이 표준이다. */
private fun readSelectedFile(context: Context, uri: Uri): SelectedFile? {
    val resolver = context.contentResolver
    val mimeType = resolver.getType(uri) ?: "application/octet-stream"
    var name = "file"
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) {
            cursor.getString(nameIndex)?.let { name = it }
        }
    }
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    return SelectedFile(name, mimeType, bytes)
}
