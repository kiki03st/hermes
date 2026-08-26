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

    fun sendChat(userText: String, sessionId: String? = null): ChatOutcome {
        val request = ChatCompletionRequest(
            model = HermesApi.MODEL_NAME,
            messages = listOf(ChatMessage(role = "user", content = userText)),
            stream = false,
        )
        val body = HermesJson.encodeToString(ChatCompletionRequest.serializer(), request)

        val headers = authHeaders() + sessionHeaders(sessionId)
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
}
