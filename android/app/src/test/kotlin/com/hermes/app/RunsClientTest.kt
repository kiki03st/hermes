package com.hermes.app

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeRunsHttpTransport(
    private val postResult: HttpResult = HttpResult(200, ""),
) : HttpTransport {
    var lastPostUrl: String? = null
    var lastPostHeaders: Map<String, String> = emptyMap()
    var lastPostBody: String? = null

    override fun get(url: String, headers: Map<String, String>): HttpResult = HttpResult(200, "")

    override fun postJson(url: String, headers: Map<String, String>, body: String): HttpResult {
        lastPostUrl = url
        lastPostHeaders = headers
        lastPostBody = body
        return postResult
    }
}

/** 실제 소켓 없이 [RunsClient.events]를 테스트하기 위한 가짜 — [lines]를 미리 정해두고
 * `onLine`으로 순서대로 흘려보낸다. */
private class FakeSseTransport(private val lines: List<String>) : SseTransport {
    var cancelCalled = false

    override fun open(
        url: String,
        headers: Map<String, String>,
        onConnected: (cancel: () -> Unit) -> Unit,
        onLine: (String) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        onConnected { cancelCalled = true }
        lines.forEach(onLine)
    }
}

class RunsClientTest {

    @Test
    fun `startRun posts input and returns the run id`() {
        val transport = FakeRunsHttpTransport(HttpResult(202, """{"run_id": "run_abc", "status": "started"}"""))
        val client = RunsClient(transport, FakeSseTransport(emptyList()), serverUrl = { "http://host:8642" }, apiKey = { "k" })

        val outcome = client.startRun("draw a wall")

        check(outcome is RunStartOutcome.Started)
        assertEquals("run_abc", outcome.runId)
        assertEquals("http://host:8642/v1/runs", transport.lastPostUrl)
        assertTrue(transport.lastPostBody!!.contains("\"input\":\"draw a wall\""))
        assertEquals("Bearer k", transport.lastPostHeaders["Authorization"])
    }

    @Test
    fun `startRun attaches session key header only when provided`() {
        val transport = FakeRunsHttpTransport(HttpResult(202, """{"run_id": "run_abc"}"""))
        val client = RunsClient(transport, FakeSseTransport(emptyList()), serverUrl = { "http://host:8642" }, apiKey = { "k" })

        client.startRun("hi", sessionKey = null)
        assertEquals(false, transport.lastPostHeaders.containsKey("X-Hermes-Session-Key"))

        client.startRun("hi", sessionKey = "device-abc")
        assertEquals("device-abc", transport.lastPostHeaders["X-Hermes-Session-Key"])
    }

    @Test
    fun `startRun surfaces failure on non-2xx status`() {
        val transport = FakeRunsHttpTransport(HttpResult(429, "Too many concurrent runs"))
        val client = RunsClient(transport, FakeSseTransport(emptyList()), serverUrl = { "http://host:8642" }, apiKey = { "k" })

        val outcome = client.startRun("hi")

        check(outcome is RunStartOutcome.Failure)
        assertEquals(429, outcome.statusCode)
    }

    @Test
    fun `startRun surfaces failure when run_id missing from response`() {
        val transport = FakeRunsHttpTransport(HttpResult(202, """{"status": "started"}"""))
        val client = RunsClient(transport, FakeSseTransport(emptyList()), serverUrl = { "http://host:8642" }, apiKey = { "k" })

        val outcome = client.startRun("hi")

        check(outcome is RunStartOutcome.Failure)
    }

    @Test
    fun `approve posts choice and parses the real response shape`() {
        val transport = FakeRunsHttpTransport(
            HttpResult(200, """{"object": "hermes.run.approval_response", "run_id": "run_abc", "choice": "once", "resolved": 1}"""),
        )
        val client = RunsClient(transport, FakeSseTransport(emptyList()), serverUrl = { "http://host:8642" }, apiKey = { "k" })

        val outcome = client.approve("run_abc", "once")

        check(outcome is ApprovalOutcome.Resolved)
        assertEquals("once", outcome.choice)
        assertEquals(1, outcome.resolved)
        assertEquals("http://host:8642/v1/runs/run_abc/approval", transport.lastPostUrl)
        assertTrue(transport.lastPostBody!!.contains("\"choice\":\"once\""))
    }

    @Test
    fun `approve sends resolveAll as the all field`() {
        val transport = FakeRunsHttpTransport(HttpResult(200, """{"choice": "always", "resolved": 3}"""))
        val client = RunsClient(transport, FakeSseTransport(emptyList()), serverUrl = { "http://host:8642" }, apiKey = { "k" })

        client.approve("run_abc", "always", resolveAll = true)

        assertTrue(transport.lastPostBody!!.contains("\"all\":true"))
    }

    @Test
    fun `approve surfaces failure on non-2xx status (e g approval_not_pending)`() {
        val transport = FakeRunsHttpTransport(HttpResult(409, "Run has no pending approval"))
        val client = RunsClient(transport, FakeSseTransport(emptyList()), serverUrl = { "http://host:8642" }, apiKey = { "k" })

        val outcome = client.approve("run_abc", "once")

        check(outcome is ApprovalOutcome.Failure)
        assertEquals(409, outcome.statusCode)
    }

    @Test
    fun `stop posts to the stop path and reports success by status code`() {
        val transport = FakeRunsHttpTransport(HttpResult(200, """{"object": "hermes.run.stop"}"""))
        val client = RunsClient(transport, FakeSseTransport(emptyList()), serverUrl = { "http://host:8642" }, apiKey = { "k" })

        val stopped = client.stop("run_abc")

        assertTrue(stopped)
        assertEquals("http://host:8642/v1/runs/run_abc/stop", transport.lastPostUrl)
    }

    @Test
    fun `events emits parsed RunEvents from real captured SSE lines`() = runTest {
        // 실측 원문(2026-08-28) — 메시지 델타 두 개 + 완료.
        val sse = FakeSseTransport(
            listOf(
                """data: {"event": "message.delta", "run_id": "run_x", "timestamp": 1.0, "delta": "Hel"}""",
                "",
                """data: {"event": "message.delta", "run_id": "run_x", "timestamp": 1.1, "delta": "lo"}""",
                "",
                """data: {"event": "run.completed", "run_id": "run_x", "timestamp": 1.2, "output": "Hello"}""",
                "",
            ),
        )
        val client = RunsClient(FakeRunsHttpTransport(), sse, serverUrl = { "http://host:8642" }, apiKey = { "k" })

        val events = withTimeout(5_000) { client.events("run_x").toList() }

        assertEquals(3, events.size)
        assertTrue(events[0] is RunEvent.MessageDelta)
        assertTrue(events[2] is RunEvent.RunCompleted)
    }

    @Test
    fun `events skips keepalive comment lines`() = runTest {
        val sse = FakeSseTransport(
            listOf(
                ": keepalive",
                """data: {"event": "run.completed", "run_id": "run_x", "timestamp": 1.0, "output": "ok"}""",
                "",
            ),
        )
        val client = RunsClient(FakeRunsHttpTransport(), sse, serverUrl = { "http://host:8642" }, apiKey = { "k" })

        val events = withTimeout(5_000) { client.events("run_x").toList() }

        assertEquals(1, events.size)
    }

    @Test
    fun `events requests the correct url with auth header`() = runTest {
        val transport = FakeRunsHttpTransport()
        var capturedUrl: String? = null
        var capturedHeaders: Map<String, String> = emptyMap()
        val sse = object : SseTransport {
            override fun open(
                url: String,
                headers: Map<String, String>,
                onConnected: (cancel: () -> Unit) -> Unit,
                onLine: (String) -> Unit,
                onError: (Throwable) -> Unit,
            ) {
                capturedUrl = url
                capturedHeaders = headers
                onConnected {}
            }
        }
        val client = RunsClient(transport, sse, serverUrl = { "http://host:8642" }, apiKey = { "secret" })

        withTimeout(5_000) { client.events("run_abc").toList() }

        assertEquals("http://host:8642/v1/runs/run_abc/events", capturedUrl)
        assertEquals("Bearer secret", capturedHeaders["Authorization"])
    }
}
