package com.hermes.app.ui.chat

import com.hermes.app.HttpResult
import com.hermes.app.HttpTransport
import com.hermes.app.RunsClient
import com.hermes.app.SseTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeStartRunHttpTransport : HttpTransport {
    override fun get(url: String, headers: Map<String, String>): HttpResult = HttpResult(200, "")
    override fun postJson(url: String, headers: Map<String, String>, body: String): HttpResult =
        HttpResult(202, """{"run_id": "run_x"}""")
}

/** [RunsClient.events]가 SSE 연결 도중 에러로 끝나는 상황(네트워크 끊김, 게이트웨이
 * 재시작 등)을 흉내낸다 — [com.hermes.app.UrlConnectionSseTransport]의 실제 예외 발생
 * 조건과 무관하게, `onError`가 불리는 그 자체만 재현한다. */
private class ErroringSseTransport(private val error: Throwable) : SseTransport {
    override fun open(
        url: String,
        headers: Map<String, String>,
        onConnected: (cancel: () -> Unit) -> Unit,
        onLine: (String) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        onConnected {}
        onError(error)
    }
}

/** 실측(2026-08-29)으로 확인된 버그: SSE 스트림이 `run.completed`/`run.failed`/
 * `run.cancelled` 없이 예외로 끝나면(연결 끊김 등) [ChatConversationState.collect]가
 * 그 예외를 안 잡아서 [ChatConversationState.isRunning]이 영구히 true로 박제됐다 —
 * 전송/마이크/첨부 버튼이 다시는 안 풀렸다. */
class ChatConversationStateTest {

    @Test
    fun `stream error unlocks isRunning and marks the turn failed instead of hanging forever`() {
        val state = ChatConversationState(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            sessionKey = { null },
            client = {
                RunsClient(
                    transport = FakeStartRunHttpTransport(),
                    sse = ErroringSseTransport(RuntimeException("network drop")),
                    serverUrl = { "http://host" },
                    apiKey = { "k" },
                )
            },
        )

        state.submit("hi")

        val deadline = System.currentTimeMillis() + 2_000
        while (state.isRunning && System.currentTimeMillis() < deadline) Thread.sleep(10)

        assertFalse(state.isRunning)
        val turn = state.messages.last() as ChatMessage.AssistantTurn
        assertTrue(turn.error != null)
    }
}
