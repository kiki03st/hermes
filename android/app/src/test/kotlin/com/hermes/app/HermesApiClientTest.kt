package com.hermes.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeHttpTransport(
    private val getResult: HttpResult = HttpResult(200, ""),
    private val postResult: HttpResult = HttpResult(200, ""),
) : HttpTransport {
    var lastGetUrl: String? = null
    var lastGetHeaders: Map<String, String> = emptyMap()
    var lastPostUrl: String? = null
    var lastPostHeaders: Map<String, String> = emptyMap()
    var lastPostBody: String? = null

    override fun get(url: String, headers: Map<String, String>): HttpResult {
        lastGetUrl = url
        lastGetHeaders = headers
        return getResult
    }

    override fun postJson(url: String, headers: Map<String, String>, body: String): HttpResult {
        lastPostUrl = url
        lastPostHeaders = headers
        lastPostBody = body
        return postResult
    }
}

class HermesApiClientTest {

    @Test
    fun `checkHealth hits health path with bearer token and trims trailing slash`() {
        val transport = FakeHttpTransport(getResult = HttpResult(200, "ok"))
        val client = HermesApiClient(transport, serverUrl = { "http://192.168.0.10:8642/" }, apiKey = { "secret-key" })

        val healthy = client.checkHealth()

        assertTrue(healthy)
        assertEquals("http://192.168.0.10:8642/health", transport.lastGetUrl)
        assertEquals("Bearer secret-key", transport.lastGetHeaders["Authorization"])
    }

    @Test
    fun `checkHealth returns false on non-2xx status`() {
        val transport = FakeHttpTransport(getResult = HttpResult(401, "unauthorized"))
        val client = HermesApiClient(transport, serverUrl = { "http://host:8642" }, apiKey = { "bad-key" })

        assertEquals(false, client.checkHealth())
    }

    @Test
    fun `sendChat posts OpenAI-compatible body and parses assistant text`() {
        val responseJson = """{"id":"chatcmpl-1","choices":[{"index":0,"message":{"role":"assistant","content":"등록했어요"}}]}"""
        val transport = FakeHttpTransport(postResult = HttpResult(200, responseJson))
        val client = HermesApiClient(transport, serverUrl = { "http://host:8642" }, apiKey = { "k" })

        val outcome = client.sendChat("내일 오후 3시 치과 예약 잡아줘")

        assertEquals("http://host:8642/v1/chat/completions", transport.lastPostUrl)
        assertTrue(transport.lastPostBody!!.contains("\"stream\":false"))
        assertTrue(transport.lastPostBody!!.contains("hermes-agent"))
        check(outcome is ChatOutcome.Success)
        assertEquals("등록했어요", outcome.text)
    }

    @Test
    fun `sendChat attaches session id header only when provided`() {
        val transport = FakeHttpTransport(postResult = HttpResult(200, """{"choices":[]}"""))
        val client = HermesApiClient(transport, serverUrl = { "http://host:8642" }, apiKey = { "k" })

        client.sendChat("hello", sessionId = null)
        assertEquals(false, transport.lastPostHeaders.containsKey("X-Hermes-Session-Id"))

        client.sendChat("hello", sessionId = "transcript-alpha")
        assertEquals("transcript-alpha", transport.lastPostHeaders["X-Hermes-Session-Id"])
    }

    @Test
    fun `sendChat surfaces failure when server responds with error status`() {
        val transport = FakeHttpTransport(postResult = HttpResult(429, "Too many concurrent runs"))
        val client = HermesApiClient(transport, serverUrl = { "http://host:8642" }, apiKey = { "k" })

        val outcome = client.sendChat("hello")

        check(outcome is ChatOutcome.Failure)
        assertEquals(429, outcome.statusCode)
    }

    @Test
    fun `sendChat surfaces failure when response has no choices`() {
        val transport = FakeHttpTransport(postResult = HttpResult(200, """{"choices":[]}"""))
        val client = HermesApiClient(transport, serverUrl = { "http://host:8642" }, apiKey = { "k" })

        val outcome = client.sendChat("hello")

        check(outcome is ChatOutcome.Failure)
    }
}
