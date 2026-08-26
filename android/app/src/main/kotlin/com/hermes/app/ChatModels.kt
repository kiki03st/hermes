package com.hermes.app

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `/v1/chat/completions` 요청/응답 최소 형태 — OpenAI 호환. hermes-agent 공식
 * api-server.md에 나온 필드만 담는다 (PLAN.md Stage 1은 비스트리밍만 쓴다). */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false,
)

@Serializable
data class ChatCompletionResponse(
    val id: String? = null,
    val choices: List<ChatChoice> = emptyList(),
) {
    val firstMessageContent: String?
        get() = choices.firstOrNull()?.message?.content
}

@Serializable
data class ChatChoice(
    val index: Int = 0,
    val message: ChatMessage,
    @SerialName("finish_reason") val finishReason: String? = null,
)
