package com.hermes.app

/** SSE 프레임 파서 — 원시 텍스트 줄을 받아 완성된 `data:` 페이로드가 나올 때마다
 * 돌려준다. 실제 소켓 스트림에 의존하지 않아 순수 단위테스트가 가능하다.
 *
 * 규칙은 Hermes 게이트웨이(v0.20.6)를 상대로 **실측**했다 —
 * `gateway/platforms/api_server.py`의 `_sse_frame`을 읽고, 이 PC에서 실제로 뜬
 * 게이트웨이에 `POST /v1/runs`로 run을 하나 만들어 `GET /v1/runs/{id}/events`의
 * 원시 바이트를 직접 받아 아래 규칙과 정확히 일치함을 확인했다(2026-08-28):
 * - 각 프레임은 `data: <json>\n\n` 뿐이다 — `event:` 줄은 절대 안 온다
 *   (`_handle_run_events`가 `_sse_frame(event)`를 `event=` 인자 없이 부른다).
 * - `:`로 시작하는 줄은 주석이다(`: keepalive`, `: stream closed`) — 무시한다.
 * - 빈 줄이 프레임 경계다.
 */
class SseFrameReader {
    private val dataLines = mutableListOf<String>()

    /** 줄 하나를 먹인다. 이 줄로 프레임이 완성됐으면(빈 줄을 만났고 누적된 `data:`가
     * 있으면) 그 JSON 페이로드를 돌려주고, 아니면 null. */
    fun feed(line: String): String? = when {
        line.isEmpty() -> flush()
        line.startsWith(":") -> null // 주석(keepalive 등) — 무시
        line.startsWith("data:") -> {
            dataLines += line.removePrefix("data:").removePrefix(" ")
            null
        }
        else -> null // 이 파이프라인이 안 보내는 필드(id: 등) — 무시
    }

    private fun flush(): String? {
        if (dataLines.isEmpty()) return null
        val payload = dataLines.joinToString("\n")
        dataLines.clear()
        return payload
    }
}
