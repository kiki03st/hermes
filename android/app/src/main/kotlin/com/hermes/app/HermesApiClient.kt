package com.hermes.app

import com.hermes.shared.HermesApi
import com.hermes.shared.HermesJson

sealed interface ChatOutcome {
    data class Success(val text: String) : ChatOutcome
    data class Failure(val statusCode: Int, val message: String) : ChatOutcome
}

/** `serverUrl`/`apiKey`는 호출 시점에 다시 읽는다 — 설정 화면에서 바뀐 값을 매번 반영하기 위함. */
class HermesApiClient(
    private val transport: HttpTransport,
    private val serverUrl: () -> String,
    private val apiKey: () -> String,
) {
    fun checkHealth(): Boolean {
        val result = transport.get(baseUrl() + HermesApi.HEALTH_PATH, authHeaders())
        return result.statusCode in 200..299
    }

    /** [sessionId]는 지금 이 대화창(짧고 리셋 가능, 비용 통제용) — 서버가 매 호출마다
     * 통째로 재전송받는 원문 트랜스크립트를 이어붙일 범위다.
     * [sessionKey]는 기기/사용자 단위로 한 번 생성해 절대 안 바뀌는 장기 기억 스코프
     * (Hermes의 memory 도구가 쓰는 저장소) — session_id를 리셋해도 별개로 유지된다.
     * 서로 독립적이라 하나만 보내거나 둘 다 보낼 수 있다 (api_server.py 문서 확인). */
    fun sendChat(userText: String, sessionId: String? = null, sessionKey: String? = null): ChatOutcome {
        val request = ChatCompletionRequest(
            model = HermesApi.MODEL_NAME,
            messages = listOf(ChatMessage(role = "user", content = userText)),
            stream = false,
        )
        val body = HermesJson.encodeToString(ChatCompletionRequest.serializer(), request)

        val headers = authHeaders() + sessionHeaders(sessionId) + sessionKeyHeaders(sessionKey)
        val result = transport.postJson(baseUrl() + HermesApi.CHAT_COMPLETIONS_PATH, headers, body)

        if (result.statusCode !in 200..299) {
            return ChatOutcome.Failure(result.statusCode, result.body)
        }

        val parsed = runCatching {
            HermesJson.decodeFromString(ChatCompletionResponse.serializer(), result.body)
        }.getOrNull()

        val text = parsed?.firstMessageContent
        return if (text != null) {
            ChatOutcome.Success(text)
        } else {
            ChatOutcome.Failure(result.statusCode, "응답을 해석할 수 없습니다: ${result.body}")
        }
    }

    private fun baseUrl(): String = serverUrl().trimEnd('/')

    private fun authHeaders(): Map<String, String> =
        mapOf("Authorization" to "Bearer ${apiKey()}")

    private fun sessionHeaders(sessionId: String?): Map<String, String> =
        if (sessionId != null) mapOf(HermesApi.SESSION_ID_HEADER to sessionId) else emptyMap()

    private fun sessionKeyHeaders(sessionKey: String?): Map<String, String> =
        if (sessionKey != null) mapOf(HermesApi.SESSION_KEY_HEADER to sessionKey) else emptyMap()
}
