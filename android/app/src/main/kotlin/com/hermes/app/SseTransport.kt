package com.hermes.app

import java.net.HttpURLConnection
import java.net.URL

/** SSE(Server-Sent Events) 연결을 여는 얇은 인터페이스 — [RunsClient]의 이벤트 파싱
 * 로직을 실제 소켓 없이 단위테스트하기 위한 경계. [HttpTransport]와 분리한 이유:
 * 그건 "요청 하나 → 응답 하나"만 다루는 계약이라 이름 그대로 얇게 유지하고, 스트리밍은
 * 별도 계약으로 둔다.
 */
interface SseTransport {
    /**
     * 연결을 열고 서버가 보내는 줄마다 [onLine]을 호출한다. 이 함수는 **블로킹**이다 —
     * 연결이 정상 종료되거나(서버가 스트림을 닫음) [onError]로 이어지는 예외가 나기
     * 전까지 리턴하지 않는다. 그래서 호출자는 반드시 별도 스레드/`Dispatchers.IO`에서
     * 불러야 한다.
     *
     * 연결이 열리자마자(HTTP 응답 코드가 200대임을 확인한 직후) [onConnected]를 한 번
     * 호출하며, 그 인자로 "이 연결을 강제로 끊는" 콜백을 준다. 호출자가 스트림을 더 이상
     * 안 볼 때(코루틴 취소 등) 이 콜백을 불러야 [onLine]에서 블로킹 중인 읽기를 풀어줄 수
     * 있다 — `HttpURLConnection.disconnect()`는 진행 중인 읽기를 `IOException`으로
     * 즉시 중단시키는 문서화된 동작이라 이 방식을 썼다.
     */
    fun open(
        url: String,
        headers: Map<String, String>,
        onConnected: (cancel: () -> Unit) -> Unit,
        onLine: (String) -> Unit,
        onError: (Throwable) -> Unit,
    )
}

class UrlConnectionSseTransport(
    private val connectTimeoutMillis: Int = 10_000,
    // 게이트웨이가 30초마다 ": keepalive" 주석을 보낸다(실측) — 그보다 넉넉히 크게 잡아야
    // 살아있는 연결의 정상적인 idle 구간(다음 keepalive를 기다리는 사이)에 오작동으로
    // 안 끊는다. 예전엔 이걸 0(무제한)으로 뒀는데, 그러면 연결이 TCP RST 없이 조용히
    // 죽었을 때(와이파이 범위 이탈 등, 흔한 모바일 시나리오) `readLine()`이 예외 없이
    // 영원히 블로킹해서 [ChatConversationState]의 isRunning이 영구 박제되는 버그가
    // 있었다 — 유한한 타임아웃이 있어야 결국 SocketTimeoutException으로 빠져나온다.
    private val readTimeoutMillis: Int = 90_000,
) : SseTransport {
    override fun open(
        url: String,
        headers: Map<String, String>,
        onConnected: (cancel: () -> Unit) -> Unit,
        onLine: (String) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            connection.setRequestProperty("Accept", "text/event-stream")

            val status = connection.responseCode
            if (status !in 200..299) {
                val errText = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                onError(RuntimeException("SSE 연결 실패: HTTP $status $errText"))
                return
            }

            val conn = connection
            onConnected { conn.disconnect() }

            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    onLine(line)
                    line = reader.readLine()
                }
            }
        } catch (e: Exception) {
            onError(e)
        } finally {
            connection?.disconnect()
        }
    }
}
