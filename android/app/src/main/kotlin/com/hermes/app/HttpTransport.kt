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

/** [connectTimeoutMillis]는 TCP 연결 자체(서버가 안 떠 있으면 즉시 실패해야 함)이고,
 * [readTimeoutMillis]는 응답을 기다리는 시간이다 — Hermes는 에이전트라 툴 호출(캘린더,
 * 웹검색 등)을 몇 번씩 거친 뒤에야 답하므로 실측 기준 수십 초는 정상이다. 기존에 둘 다
 * 15초로 묶여있던 게 채팅 응답 대기 중 SocketTimeoutException을 던지던 원인이었다. */
class UrlConnectionHttpTransport(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 120_000,
) : HttpTransport {

    override fun get(url: String, headers: Map<String, String>): HttpResult =
        request(url, "GET", headers, body = null)

    override fun postJson(url: String, headers: Map<String, String>, body: String): HttpResult =
        request(url, "POST", headers + ("Content-Type" to "application/json"), body)

    /** 상태코드 0은 실제 HTTP 응답이 아니라 연결 자체가 실패했다는 뜻(타임아웃, DNS 실패,
     * cleartext 차단 등) — 이 예외들을 여기서 안 잡으면 코루틴 밖으로 튀어서 앱이 그냥
     * 죽는다(#워치/폰 연결 테스트 크래시). 항상 HttpResult로 정규화해서 호출부가
     * ChatOutcome.Failure/checkHealth=false로 다룰 수 있게 한다. */
    private fun request(url: String, method: String, headers: Map<String, String>, body: String?): HttpResult {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }

            if (body != null) {
                connection.doOutput = true
                OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            HttpResult(status, text)
        } catch (e: Exception) {
            HttpResult(0, "네트워크 오류: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }
}
