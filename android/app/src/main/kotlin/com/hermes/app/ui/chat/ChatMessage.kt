package com.hermes.app.ui.chat

/**
 * 화면 표시용 모델 — `com.hermes.app.ChatModels.kt`의 와이어 DTO와는 별개다.
 * `/v1/runs`의 [com.hermes.app.RunEvent] 스트림을 [ChatReducer]가 이 모델로 접어 넣는다.
 */
sealed interface ChatMessage {
    val id: String

    data class User(override val id: String, val text: String) : ChatMessage

    /**
     * 진행 중이거나 끝난 assistant 턴 하나. [reasoning]/[toolActivity]/[approval]은 별도
     * 리스트 아이템이 아니라 이 턴에 붙는 하위 상태다 — 클로드/코덱스처럼 도구 활동과
     * 생각 블록이 같은 턴 안에 중첩되는 형태를 따른다.
     *
     * `tool.completed`는 도구 이름 + 소요시간 + 성공여부만 갖고 있다(인자·결과 없음 —
     * RunEvent.kt 문서, 2026-08-28 실측). 그래서 [toolActivity]는 이름 + 상태 이상을
     * 표현할 수 없다.
     */
    data class AssistantTurn(
        override val id: String,
        val textSoFar: String = "",
        val isStreaming: Boolean = true,
        val reasoning: String? = null,
        val toolActivity: List<ToolActivity> = emptyList(),
        val approval: PendingApproval? = null,
        val error: String? = null,
    ) : ChatMessage

    /** run.cancelled, 시작 실패 등 대화 흐름에 넣을 만한 시스템 메시지. */
    data class SystemNotice(override val id: String, val text: String) : ChatMessage
}

data class ToolActivity(val tool: String, val state: ToolState)

enum class ToolState { RUNNING, DONE, ERROR }

/**
 * [choices]는 트러스트 게이트가 상황별로 좁혀서 주는 값이라 하드코딩 금지 — 그대로
 * 버튼으로 렌더한다(기존 `RunsSection.kt`의 `ApprovalDialog`와 같은 원칙).
 */
data class PendingApproval(
    val choices: List<String>,
    val command: String?,
    val description: String?,
)
