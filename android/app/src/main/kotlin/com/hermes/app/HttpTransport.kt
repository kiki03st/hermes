package com.hermes.app

import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class HttpResult(val statusCode: Int, val body: String)

/** 실제 네트워크 호출을 감싼 얇은 인터페이스 — HermesApiClient의 요청 조립 로직을
 * 실제 소켓 없이 단위테스트하기 위한 경계. */
interface HttpTransport {
    fun get(url: String, headers: Map<String, String>): HttpResult
    fun postJson(url: String, headers: Map<String, String>, body: String): HttpResult
}

class UrlConnectionHttpTransport(private val timeoutMillis: Int = 15_000) : HttpTransport {

    override fun get(url: String, headers: Map<String, String>): HttpResult =
        request(url, "GET", headers, body = null)

    override fun postJson(url: String, headers: Map<String, String>, body: String): HttpResult =
        request(url, "POST", headers + ("Content-Type" to "application/json"), body)

    private fun request(url: String, method: String, headers: Map<String, String>, body: String?): HttpResult {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }

            if (body != null) {
                connection.doOutput = true
                OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            HttpResult(status, text)
        } finally {
            connection.disconnect()
        }
    }
}
