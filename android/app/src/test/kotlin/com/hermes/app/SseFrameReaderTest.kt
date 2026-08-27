package com.hermes.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseFrameReaderTest {

    @Test
    fun `data line followed by blank line yields the payload`() {
        val reader = SseFrameReader()

        assertNull(reader.feed("data: {\"a\":1}"))
        assertEquals("{\"a\":1}", reader.feed(""))
    }

    @Test
    fun `comment lines are ignored and do not flush`() {
        val reader = SseFrameReader()

        assertNull(reader.feed(": keepalive"))
        assertNull(reader.feed(""))
    }

    @Test
    fun `stream closed comment is also ignored`() {
        val reader = SseFrameReader()

        assertNull(reader.feed(": stream closed"))
    }

    @Test
    fun `blank line with nothing accumulated yields null, not an empty string`() {
        val reader = SseFrameReader()

        assertNull(reader.feed(""))
    }

    @Test
    fun `real gateway frames parse one at a time in sequence`() {
        // 실측(2026-08-28): 이 PC에 뜬 게이트웨이의 GET /v1/runs/{id}/events 원시 바이트.
        val lines = listOf(
            "data: {\"event\": \"message.delta\", \"run_id\": \"run_abc\", \"timestamp\": 1.0, \"delta\": \"hi\"}",
            "",
            "data: {\"event\": \"run.completed\", \"run_id\": \"run_abc\", \"timestamp\": 2.0, \"output\": \"ok\"}",
            "",
        )
        val reader = SseFrameReader()
        val payloads = lines.mapNotNull { reader.feed(it) }

        assertEquals(2, payloads.size)
        assertEquals(
            "{\"event\": \"message.delta\", \"run_id\": \"run_abc\", \"timestamp\": 1.0, \"delta\": \"hi\"}",
            payloads[0],
        )
    }

    @Test
    fun `unrecognized line prefix does not flush and is not accumulated`() {
        val reader = SseFrameReader()

        assertNull(reader.feed("id: 42"))
        // 아무것도 누적 안 됐으니 빈 줄이 와도 flush 할 게 없다.
        assertNull(reader.feed(""))
    }

    @Test
    fun `data prefix without a following space is still stripped correctly`() {
        val reader = SseFrameReader()

        reader.feed("data:{\"a\":1}")
        assertEquals("{\"a\":1}", reader.feed(""))
    }
}
