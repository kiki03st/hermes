package com.hermes.app.ui.chat

import com.hermes.app.DownloadOutcome
import com.hermes.app.HttpResult
import com.hermes.app.HttpTransport
import com.hermes.app.MediaDownloadClient
import com.hermes.app.RunsClient
import com.hermes.app.SseTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeStartRunHttpTransport : HttpTransport {
    override fun get(url: String, headers: Map<String, String>): HttpResult = HttpResult(200, "")
    override fun postJson(url: String, headers: Map<String, String>, body: String): HttpResult =
        HttpResult(202, """{"run_id": "run_x"}""")
}

private class FakeMediaDownloadClient(private val outcome: DownloadOutcome) : MediaDownloadClient {
    var lastTool: String? = null
    var lastFilename: String? = null

    override fun downloadGenerated(tool: String, filename: String): DownloadOutcome {
        lastTool = tool
        lastFilename = filename
        return outcome
    }
}

/** [RunsClient.events]가 `run.completed`를 `MEDIA:<generated 경로>` 태그가 든 텍스트로
 * 딱 한 번 흘려보내고 끝나는 상황을 흉내낸다. */
private class SingleEventSseTransport(private val json: String) : SseTransport {
    override fun open(
        url: String,
        headers: Map<String, String>,
        onConnected: (cancel: () -> Unit) -> Unit,
        onLine: (String) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        onConnected {}
        onLine("data: $json")
        onLine("")
    }
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
            mediaClient = { FakeMediaDownloadClient(DownloadOutcome.Success(ByteArray(0))) },
        )

        state.submit("hi")

        val deadline = System.currentTimeMillis() + 2_000
        while (state.isRunning && System.currentTimeMillis() < deadline) Thread.sleep(10)

        assertFalse(state.isRunning)
        val turn = state.messages.last() as ChatMessage.AssistantTurn
        assertTrue(turn.error != null)
    }

    @Test
    fun `run completed with a MEDIA tag downloads the image and marks it Loaded`() {
        val json = """{"event": "run.completed", "run_id": "run_x", "timestamp": 1.0, "output": "짜잔\n\nMEDIA:C:\\hermes\\upload-server\\generated\\comfyui\\a.png", "usage": {"input_tokens": 1, "output_tokens": 1, "total_tokens": 2}}"""
        val fakeMedia = FakeMediaDownloadClient(DownloadOutcome.Success(byteArrayOf(1, 2, 3)))
        val state = ChatConversationState(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            sessionKey = { null },
            client = {
                RunsClient(
                    transport = FakeStartRunHttpTransport(),
                    sse = SingleEventSseTransport(json),
                    serverUrl = { "http://host" },
                    apiKey = { "k" },
                )
            },
            mediaClient = { fakeMedia },
        )

        state.submit("그려줘")

        val deadline = System.currentTimeMillis() + 2_000
        fun currentMediaStatus() = (state.messages.last() as ChatMessage.AssistantTurn).media.firstOrNull()?.status
        while (currentMediaStatus() is MediaStatus.Loading || currentMediaStatus() == null) {
            if (System.currentTimeMillis() > deadline) break
            Thread.sleep(10)
        }

        val turn = state.messages.last() as ChatMessage.AssistantTurn
        assertEquals("comfyui", fakeMedia.lastTool)
        assertEquals("a.png", fakeMedia.lastFilename)
        assertEquals(1, turn.media.size)
        assertTrue(turn.media[0].status is MediaStatus.Loaded)
    }
}
