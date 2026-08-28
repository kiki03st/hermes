package com.hermes.app.ui.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hermes.app.HermesRuntime
import com.hermes.app.WakeWordService
import java.util.Locale

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
                onMicClick = ::onMicClick,
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
    onMicClick: () -> Unit,
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
