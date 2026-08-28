package com.hermes.app.ui.chat

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
import androidx.compose.ui.unit.dp

/**
 * 메인 챗봇 화면 — 입력은 전부 `/v1/runs`(승인 게이트 포함) 하나로만 나간다(계획 결정 #1).
 * 옛 `RunsSection.kt`가 하던 일이 [ChatConversationState]/[ChatReducer]/[ChatMessageList]로
 * 흡수됐다.
 *
 * [state]는 [com.hermes.app.ui.HermesApp]이 소유·전달한다(여기서 안 만듦) — 직접 타이핑
 * 경로와 웨이크워드 핸즈프리 경로가 같은 인스턴스를 공유해야 하기 때문(웨이크워드 계획 §1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(state: ChatConversationState, onOpenSettings: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val pendingApproval = (state.messages.lastOrNull() as? ChatMessage.AssistantTurn)?.approval

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
