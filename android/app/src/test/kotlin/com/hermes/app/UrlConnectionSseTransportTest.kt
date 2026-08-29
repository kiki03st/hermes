package com.hermes.app

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/** [UrlConnectionSseTransport.readTimeoutMillis]가 실제로 걸려있는지 검증한다 — 예전엔
 * `readTimeout = 0`(무한대)이라 연결이 TCP RST 없이 조용히 죽으면(흔한 모바일 와이파이
 * 탈출 시나리오) `readLine()`이 예외 없이 영원히 블로킹해서 [ChatConversationState]의
 * `isRunning`이 영구히 true로 박제되는 버그가 있었다(실측 근거: 2026-08-29 대화 —
 * 이 문제 자체는 로그가 아니라 코드 리딩으로 발견). 살아있는 연결(30초 keepalive)은
 * 안 끊기고, 진짜 조용히 죽은 연결만 타임아웃으로 잡아내야 한다. */
class UrlConnectionSseTransportTest {
    private lateinit var server: HttpServer

    @After
    fun stopServer() {
        if (::server.isInitialized) server.stop(0)
    }

    @Test
    fun `open reports a timeout error when the connection goes silent past readTimeout`() {
        val keepHandlerAlive = CountDownLatch(1)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/events") { exchange ->
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0) // 0 = chunked, 길이 모름 — 계속 열어둘 수 있음
            exchange.responseBody.write("data: hello\n\n".toByteArray(Charsets.UTF_8))
            exchange.responseBody.flush()
            // 이후로 아무것도 안 보내고 그냥 연결을 열어둔다 — "조용히 죽은 연결"을 흉내낸다.
            keepHandlerAlive.await(5, TimeUnit.SECONDS)
        }
        server.start()

        val transport = UrlConnectionSseTransport(readTimeoutMillis = 300)
        var capturedError: Throwable? = null

        transport.open(
            url = "http://127.0.0.1:${server.address.port}/events",
            headers = emptyMap(),
            onConnected = {},
            onLine = {},
            onError = { capturedError = it },
        )

        keepHandlerAlive.countDown()
        assertTrue(capturedError is SocketTimeoutException)
    }
}
