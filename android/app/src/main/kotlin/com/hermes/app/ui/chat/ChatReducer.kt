package com.hermes.app.ui.chat

import com.hermes.app.RunEvent
import java.util.UUID
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 순수 함수 모음 — Compose/코루틴 의존성 없음. [com.hermes.app.RunsClient]가 주는
 * [RunEvent] 스트림을 [ChatMessage] 목록으로 접는다. `ChatConversationState`가 코루틴
 * 접합부를 맡고, 리스트 모양 변경은 전부 여기로 위임한다 — 그래야 `RunEventTest.kt`와
 * 같은 방식(실측 SSE JSON을 그대로 파싱해서)으로 Compose 없이 유닛테스트할 수 있다.
 */
object ChatReducer {

    fun appendUserMessage(messages: List<ChatMessage>, text: String): List<ChatMessage> =
        messages + ChatMessage.User(id = newId(), text = text)

    fun appendSystemNotice(messages: List<ChatMessage>, text: String): List<ChatMessage> =
        messages + ChatMessage.SystemNotice(id = newId(), text = text)

    fun startAssistantTurn(messages: List<ChatMessage>): List<ChatMessage> =
        messages + ChatMessage.AssistantTurn(id = newId())

    /**
     * 마지막 아이템이 진행 중인 [ChatMessage.AssistantTurn]이라고 가정한다
     * (`appendUserMessage` 다음에 `startAssistantTurn`을 호출하는 게 호출자의 책임).
     * 그렇지 않으면 방어적으로 새 턴을 하나 만들어 이어서 처리한다 — 조용히 이벤트를
     * 버리지 않는다.
     */
    fun applyEvent(messages: List<ChatMessage>, event: RunEvent): List<ChatMessage> {
        val turn = messages.lastOrNull() as? ChatMessage.AssistantTurn
            ?: return applyEvent(startAssistantTurn(messages), event)

        val updated: ChatMessage.AssistantTurn = when (event) {
            is RunEvent.MessageDelta ->
                turn.copy(textSoFar = turn.textSoFar + event.delta)

            is RunEvent.ReasoningAvailable ->
                turn.copy(reasoning = event.text)

            is RunEvent.ToolStarted ->
                turn.copy(toolActivity = turn.toolActivity + ToolActivity(event.tool ?: "?", ToolState.RUNNING))

            is RunEvent.ToolCompleted ->
                turn.copy(toolActivity = applyToolCompleted(turn.toolActivity, event))

            is RunEvent.ApprovalRequest -> turn.copy(
                approval = PendingApproval(
                    choices = event.choices,
                    command = event.raw["command"]?.jsonPrimitive?.contentOrNull,
                    description = event.raw["description"]?.jsonPrimitive?.contentOrNull,
                ),
            )

            is RunEvent.ApprovalResponded ->
                turn.copy(approval = null)

            is RunEvent.RunCompleted -> {
                val (cleaned, media) = extractMedia(event.output.ifBlank { turn.textSoFar })
                turn.copy(textSoFar = cleaned, isStreaming = false, media = media)
            }

            is RunEvent.RunFailed ->
                turn.copy(isStreaming = false, error = event.error)

            is RunEvent.RunCancelled ->
                turn.copy(isStreaming = false)

            is RunEvent.RunSteered, is RunEvent.Unknown -> turn
        }

        val withUpdatedTurn = messages.dropLast(1) + updated
        return if (event is RunEvent.RunCancelled) {
            withUpdatedTurn + ChatMessage.SystemNotice(id = newId(), text = "취소됨")
        } else {
            withUpdatedTurn
        }
    }

    /** [messages]에서 [turnId] 턴의 [mediaId] 미디어 항목만 [status]로 갱신한다 —
     * 다운로드는 IO라 [ChatConversationState]가 코루틴으로 처리하고, 그 결과 반영은
     * 여기(순수 함수)에 위임한다. */
    fun applyMediaStatus(messages: List<ChatMessage>, turnId: String, mediaId: String, status: MediaStatus): List<ChatMessage> =
        messages.map { msg ->
            if (msg is ChatMessage.AssistantTurn && msg.id == turnId) {
                msg.copy(media = msg.media.map { if (it.id == mediaId) it.copy(status = status) else it })
            } else {
                msg
            }
        }

    /** `MEDIA:<path>`에서 `.../generated/<tool>/<filename>` 형태만 파싱한다(구분자
     * `\`/`/` 둘 다, 대소문자 무관) — 화이트리스트 밖 경로는 `null`을 돌려주고 호출자는
     * 원본 텍스트를 그대로 둔다(설계 문서 §에러 처리 — 임의 경로를 다운로드 시도하지
     * 않는다). */
    fun parseGeneratedMediaPath(path: String): Pair<String, String>? {
        val normalized = path.replace('\\', '/')
        val idx = normalized.indexOf("/generated/", ignoreCase = true)
        if (idx == -1) return null
        val rest = normalized.substring(idx + "/generated/".length)
        val parts = rest.split('/', limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        return parts[0] to parts[1]
    }

    private val MEDIA_LINE = Regex("""^MEDIA:(\S+)$""")

    /** [text]를 줄 단위로 스캔해 `MEDIA:<generated 하위 경로>` 줄을 떼어내고(사용자
     * 승인 사항 — 원본 경로 텍스트는 안 보여줌) [ChatMedia] 목록으로 돌려준다.
     * `run.completed`의 최종 텍스트에만 적용한다 — 스트리밍 중간 델타는 태그가 아직
     * 안 끝났을 수 있어 건드리지 않는다(설계 문서 §데이터 흐름). */
    private fun extractMedia(text: String): Pair<String, List<ChatMedia>> {
        val media = mutableListOf<ChatMedia>()
        val keptLines = text.lines().filter { line ->
            val match = MEDIA_LINE.matchEntire(line.trim())
            val parsed = match?.let { parseGeneratedMediaPath(it.groupValues[1]) }
            if (parsed != null) {
                media += ChatMedia(id = newId(), tool = parsed.first, filename = parsed.second)
                false
            } else {
                true
            }
        }
        return keptLines.joinToString("\n").trim() to media
    }

    /** 같은 도구 이름의 RUNNING 중 가장 최근 것을 완료 상태로 바꾼다 — `tool.completed`엔
     * 호출 id가 없어 이름 매칭이 최선이다(동시에 같은 도구가 중복 실행되는 경우는 프로토콜상
     * 구분 불가, 실측 트래픽에서도 관측된 적 없음). 매칭 실패 시 새 항목을 완료 상태로 추가해
     * 이벤트를 잃지 않는다. */
    private fun applyToolCompleted(activity: List<ToolActivity>, event: RunEvent.ToolCompleted): List<ToolActivity> {
        val toolName = event.tool
        val targetIndex = activity.indexOfLast {
            it.state == ToolState.RUNNING && (toolName == null || it.tool == toolName)
        }
        val finalState = if (event.isError) ToolState.ERROR else ToolState.DONE
        if (targetIndex == -1) {
            return activity + ToolActivity(toolName ?: "?", finalState)
        }
        return activity.toMutableList().also { list ->
            list[targetIndex] = list[targetIndex].copy(state = finalState)
        }
    }

    private fun newId(): String = UUID.randomUUID().toString()
}
