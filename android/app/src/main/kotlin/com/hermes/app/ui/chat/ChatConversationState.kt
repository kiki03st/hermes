package com.hermes.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hermes.app.RunEvent
import com.hermes.app.RunStartOutcome
import com.hermes.app.RunsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 코루틴 접합부만 담당하는 얇은 상태 홀더 — 리스트 모양 변경 로직은 전부 [ChatReducer]에
 * 위임한다(프레임워크 독립적이라 유닛테스트 가능, `ChatReducerTest.kt` 참고). `ViewModel`을
 * 쓰지 않은 이유는 계획 §4 — 이 리포에 선례가 없고 화면 1개 회전 생존 이득 대비 배선 비용이
 * 안 맞는다. 이 클래스 자체는 프레임워크 독립적이라 나중에 `ViewModel`로 감싸는 건 기계적
 * 후속 작업으로 남는다.
 */
class ChatConversationState(
    private val scope: CoroutineScope,
    private val sessionKey: () -> String?,
    private val client: () -> RunsClient,
) {
    var messages: List<ChatMessage> by mutableStateOf(emptyList())
        private set

    var isRunning: Boolean by mutableStateOf(false)
        private set

    /** [ChatMessageList]의 자동 스크롤 트리거 — `messages.size`만으로는 스트리밍 중
     * 기존 아이템 갱신(MessageDelta)을 못 잡아내서 별도로 둔다. */
    var revision: Int by mutableStateOf(0)
        private set

    private var currentRunId: String? = null

    fun submit(text: String) {
        if (text.isBlank() || isRunning) return

        messages = ChatReducer.startAssistantTurn(ChatReducer.appendUserMessage(messages, text))
        revision++
        isRunning = true

        scope.launch {
            when (val outcome = withContext(Dispatchers.IO) { client().startRun(text, sessionKey()) }) {
                is RunStartOutcome.Started -> {
                    currentRunId = outcome.runId
                    collect(outcome.runId)
                }
                is RunStartOutcome.Failure -> {
                    failCurrentTurn("시작 실패 (${outcome.statusCode}): ${outcome.message}")
                }
            }
        }
    }

    fun resolveApproval(turnId: String, choice: String) {
        val id = currentRunId ?: return
        scope.launch {
            withContext(Dispatchers.IO) { client().approve(id, choice) }
        }
    }

    fun stop() {
        val id = currentRunId ?: return
        scope.launch {
            withContext(Dispatchers.IO) { client().stop(id) }
        }
    }

    private fun collect(runId: String) {
        scope.launch {
            client().events(runId)
                .flowOn(Dispatchers.IO)
                .collect { event ->
                    messages = ChatReducer.applyEvent(messages, event)
                    revision++
                    if (event is RunEvent.RunCompleted || event is RunEvent.RunFailed || event is RunEvent.RunCancelled) {
                        isRunning = false
                    }
                }
        }
    }

    private fun failCurrentTurn(message: String) {
        val turn = messages.lastOrNull() as? ChatMessage.AssistantTurn
        if (turn != null) {
            messages = messages.dropLast(1) + turn.copy(isStreaming = false, error = message)
        }
        revision++
        isRunning = false
    }
}
