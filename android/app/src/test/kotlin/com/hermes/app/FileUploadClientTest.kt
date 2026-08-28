package com.hermes.app

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FileUploadClientTest {
    private lateinit var server: HttpServer
    private var lastAuthHeader: String? = null
    private var lastBody: ByteArray = ByteArray(0)
    private var responseStatus = 200
    private var responseBody = """{"path":"/tmp/x_a.txt","note":"note-text"}"""

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/upload") { exchange: HttpExchange ->
            lastAuthHeader = exchange.requestHeaders.getFirst("Authorization")
            lastBody = exchange.requestBody.readBytes()
            val bytes = responseBody.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun client() = FileUploadClient(
        uploadServerUrl = { "http://127.0.0.1:${server.address.port}" },
        apiKey = { "test-key" },
    )

    @Test
    fun `upload sends bearer auth and multipart body containing the file bytes`() {
        val outcome = client().upload("a.txt", "text/plain", "hello".toByteArray())

        check(outcome is UploadOutcome.Success)
        assertEquals("/tmp/x_a.txt", outcome.path)
        assertEquals("note-text", outcome.note)
        assertEquals("Bearer test-key", lastAuthHeader)
        val bodyText = String(lastBody, Charsets.UTF_8)
        assertTrue(bodyText.contains("filename=\"a.txt\""))
        assertTrue(bodyText.contains("hello"))
    }

    @Test
    fun `upload surfaces failure on non-2xx status`() {
        responseStatus = 401
        responseBody = """{"error":"인증 실패"}"""

        val outcome = client().upload("a.txt", "text/plain", "hello".toByteArray())

        check(outcome is UploadOutcome.Failure)
        assertEquals(401, outcome.statusCode)
        assertEquals("인증 실패", outcome.message)
    }

    @Test
    fun `upload on unreachable host returns failure instead of throwing`() {
        val client = FileUploadClient(
            uploadServerUrl = { "http://127.0.0.1:1" },
            apiKey = { "k" },
        )

        val outcome = client.upload("a.txt", "text/plain", "hi".toByteArray())

        check(outcome is UploadOutcome.Failure)
        assertEquals(0, outcome.statusCode)
    }
}
