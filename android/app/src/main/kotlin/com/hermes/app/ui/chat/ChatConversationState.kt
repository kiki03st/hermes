package com.hermes.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hermes.app.RunEvent
import com.hermes.app.RunStartOutcome
import com.hermes.app.RunsClient
import java.util.UUID
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
    private val now: () -> Long = System::currentTimeMillis,
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

    // /v1/runs가 대화를 이어가는 진짜 키 — 같은 값을 계속 보내야 서버가 이전 턴을 이어서
    // 안다(Hermes 게이트웨이 소스 `_handle_runs` 확인, 2026-08-29). 처음엔 run_id를
    // previous_response_id로 체이닝하는 방식을 썼었는데, 그건 /v1/responses 전용 별도
    // 저장소를 가리키는 필드라 /v1/runs에선 안 먹혀서(게이트웨이 로그에 history=0으로
    // 계속 찍힘) 이 방식으로 정정했다.
    private var sessionId: String = UUID.randomUUID().toString()

    // IDLE_RESET_MS 이상 조용했으면 sessionId를 새로 발급해서 새 대화로 취급한다. 화면
    // 기록은 안 지우고(스크롤해서 계속 보임) 서버로 나가는 맥락 연결만 조용히 새로 시작한다.
    // 알렉사/구글 어시스턴트 같은 실제 음성비서들이 쓰는 "대화 세션 자연 소멸" 방식 —
    // 사용자가 수동으로 지울 필요 없이 오래된 화제가 최근 질문에 섞여드는 걸 막는다.
    private var lastInteractionAtMs: Long = 0L

    private var pendingOnComplete: ((ChatMessage.AssistantTurn) -> Unit)? = null

    /** [onComplete]는 이 run이 끝날 때(성공/실패/취소로 [isRunning]이 false가 될 때) 마지막
     * [ChatMessage.AssistantTurn]과 함께 한 번 불린다 — 타이핑 채팅([ui.chat.ChatScreen])은
     * 안 넘기고, 헤드리스 음성 경로([com.hermes.app.WakeWordService])가 답변을 낭독/알림으로
     * 전달할 때 씀. 승인 대기로 run이 안 끝난 상태로 남아있으면 콜백도 안 불리는데, 나중에
     * 앱에서 직접 승인해서 그 run이 마저 끝나면 그때 불린다 — 별도 분기 없이 자연스럽게
     * 처리된다. */
    fun submit(text: String, onComplete: ((ChatMessage.AssistantTurn) -> Unit)? = null) {
        if (text.isBlank() || isRunning) return

        pendingOnComplete = onComplete
        messages = ChatReducer.startAssistantTurn(ChatReducer.appendUserMessage(messages, text))
        revision++
        isRunning = true

        val nowMs = now()
        if (nowMs - lastInteractionAtMs > IDLE_RESET_MS) {
            sessionId = UUID.randomUUID().toString()
        }
        lastInteractionAtMs = nowMs

        scope.launch {
            when (
                val outcome = withContext(Dispatchers.IO) { client().startRun(text, sessionKey(), sessionId) }
            ) {
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
                        notifyComplete()
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
        notifyComplete()
    }

    private fun notifyComplete() {
        val turn = messages.lastOrNull() as? ChatMessage.AssistantTurn ?: return
        pendingOnComplete?.invoke(turn)
        pendingOnComplete = null
    }

    companion object {
        const val IDLE_RESET_MS = 30 * 60 * 1000L
    }
}
