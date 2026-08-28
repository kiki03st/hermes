package com.hermes.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun ChatMessageList(
    messages: List<ChatMessage>,
    onApprovalChoice: (turnId: String, choice: String) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    revision: Int = messages.size,
) {
    // MessageDelta는 기존 아이템(AssistantTurn)을 갱신하지 새 아이템을 추가하지 않는다 —
    // messages.size만 보고 스크롤하면 스트리밍 중엔 안 따라간다. 호출자가 리듀서 호출마다
    // 증가시키는 revision을 키로 써야 매 델타마다 최신 위치로 스크롤된다.
    LaunchedEffect(revision) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp, horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(messages, key = { it.id }) { message ->
            when (message) {
                is ChatMessage.User -> UserBubble(message)
                is ChatMessage.AssistantTurn -> AssistantBubble(message, onApprovalChoice)
                is ChatMessage.SystemNotice -> NoticeRow(message)
            }
        }
    }
}

@Composable
private fun UserBubble(message: ChatMessage.User) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Card(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun AssistantBubble(turn: ChatMessage.AssistantTurn, onApprovalChoice: (String, String) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Card(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                turn.reasoning?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = "생각 중: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (turn.toolActivity.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        turn.toolActivity.forEach { activity -> ToolActivityRow(activity) }
                    }
                }

                if (turn.textSoFar.isNotBlank()) {
                    Text(
                        text = turn.textSoFar,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else if (turn.isStreaming && turn.toolActivity.isEmpty() && turn.reasoning == null) {
                    TypingIndicator()
                }

                turn.error?.let {
                    Text(
                        text = "오류: $it",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                turn.approval?.let { approval ->
                    ApprovalCard(approval, onChoice = { choice -> onApprovalChoice(turn.id, choice) })
                }
            }
        }
    }
}

@Composable
private fun ToolActivityRow(activity: ToolActivity) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        when (activity.state) {
            ToolState.RUNNING -> CircularProgressIndicator(
                modifier = Modifier.padding(2.dp),
                strokeWidth = 2.dp,
            )
            ToolState.DONE -> StatusDot(color = MaterialTheme.colorScheme.primary)
            ToolState.ERROR -> StatusDot(color = MaterialTheme.colorScheme.error)
        }
        Text(
            text = activity.tool,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusDot(color: androidx.compose.ui.graphics.Color) {
    Box(modifier = Modifier.padding(2.dp)) {
        Box(
            modifier = Modifier
                .padding(2.dp)
                .background(color = color, shape = androidx.compose.foundation.shape.CircleShape)
                .widthIn(min = 8.dp),
        )
    }
}

@Composable
private fun TypingIndicator() {
    Text(
        text = "...",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 승인 프롬프트를 모달이 아니라 대화 흐름 안 카드로 렌더한다(계획 §2) — "무시 불가" 보장은
 * 입력창을 막는 쪽(`ChatScreen`)에서 담당하고, 여기선 [PendingApproval.choices]를 그대로
 * 버튼으로 그린다(하드코딩 금지, `RunsSection.kt`의 옛 `ApprovalDialog`와 같은 원칙).
 */
@Composable
private fun ApprovalCard(approval: PendingApproval, onChoice: (String) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("승인이 필요합니다", style = MaterialTheme.typography.titleSmall)
            approval.command?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            approval.description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                approval.choices.forEach { choice ->
                    Button(onClick = { onChoice(choice) }) {
                        Text(approvalChoiceLabel(choice))
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeRow(message: ChatMessage.SystemNotice) {
    Text(
        text = message.text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun approvalChoiceLabel(choice: String): String = when (choice) {
    "once" -> "이번만 승인"
    "session" -> "이 대화 동안 계속 승인"
    "always" -> "항상 승인"
    "deny" -> "거부"
    else -> choice
}
