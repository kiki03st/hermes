package com.hermes.shared

/** Hermes API 서버 계약 — PLAN.md/hermes-agent 공식 문서(api-server.md)를 그대로 따른다.
 * 새로 설계하지 않고 여기 한 곳에 모아 앱/워치가 같은 값을 쓰게 한다. */
object HermesApi {
    const val DEFAULT_PORT = 8642

    const val CHAT_COMPLETIONS_PATH = "/v1/chat/completions"
    const val HEALTH_PATH = "/health"
    const val RUNS_PATH = "/v1/runs"

    fun runEventsPath(runId: String) = "/v1/runs/$runId/events"

    fun runStopPath(runId: String) = "/v1/runs/$runId/stop"

    fun runApprovalPath(runId: String) = "/v1/runs/$runId/approval"

    const val SESSION_ID_HEADER = "X-Hermes-Session-Id"
    const val SESSION_KEY_HEADER = "X-Hermes-Session-Key"

    const val MODEL_NAME = "hermes-agent"
}

/** Stage 1: 워치발 요청은 화면이 작으니 짧게 답하라는 프리앰블을 붙인다.
 * 시스템 프롬프트를 바꾸는 대신 요청 메시지 앞에 붙이는 방식 — Hermes의
 * 프론트엔드 instructions 레이어링을 그대로 이용한다(PLAN.md Stage 1 참고). */
object WatchPreamble {
    const val TEXT = "2문장 이내로 답해라."

    fun wrap(userText: String): String = "$TEXT\n\n$userText"
}
