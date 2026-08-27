package com.hermes.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** UrlConnectionHttpTransport는 순수 java.net API만 쓰므로 JVM에서 그대로 실행 가능 —
 * 실기기 없이도 "연결 자체가 실패하면 예외를 던지지 않고 HttpResult로 정규화하는지"를 검증한다.
 * 이 클래스는 원래 테스트가 없었고, 예외를 안 잡아서 실기기에서 앱이 죽는 버그가 있었다. */
class UrlConnectionHttpTransportTest {

    @Test
    fun `get on unreachable host returns status 0 instead of throwing`() {
        val transport = UrlConnectionHttpTransport(connectTimeoutMillis = 2_000, readTimeoutMillis = 2_000)

        // 존재하지 않는 로컬 포트 — 연결 자체가 즉시 거부된다 (ConnectException).
        val result = transport.get("http://127.0.0.1:1/health", emptyMap())

        assertEquals(0, result.statusCode)
        assertTrue(result.body.contains("네트워크 오류"))
    }

    @Test
    fun `postJson on unreachable host returns status 0 instead of throwing`() {
        val transport = UrlConnectionHttpTransport(connectTimeoutMillis = 2_000, readTimeoutMillis = 2_000)

        val result = transport.postJson("http://127.0.0.1:1/v1/chat/completions", emptyMap(), "{}")

        assertEquals(0, result.statusCode)
    }
}
