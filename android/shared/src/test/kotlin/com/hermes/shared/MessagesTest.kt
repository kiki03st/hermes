package com.hermes.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class MessagesTest {

    @Test
    fun `HermesRequest serializes with PLAN md wire field names`() {
        val request = HermesRequest(reqId = "req-1", text = "내일 오후 3시 치과 예약")

        val json = HermesJson.encodeToString(HermesRequest.serializer(), request)

        assertEquals(true, json.contains("\"req_id\":\"req-1\""))
        val decoded = HermesJson.decodeFromString(HermesRequest.serializer(), json)
        assertEquals(request, decoded)
    }

    @Test
    fun `HermesResponse defaults has_image to false and run_id to null`() {
        val json = """{"req_id":"req-1","ok":true,"text":"등록했어요"}"""

        val decoded = HermesJson.decodeFromString(HermesResponse.serializer(), json)

        assertEquals(false, decoded.hasImage)
        assertEquals(null, decoded.runId)
    }

    @Test
    fun `unknown fields from a newer server do not break decoding`() {
        val json = """{"req_id":"req-1","text":"hello","future_field":"ignored"}"""

        val decoded = HermesJson.decodeFromString(HermesRequest.serializer(), json)

        assertEquals("req-1", decoded.reqId)
        assertEquals("hello", decoded.text)
    }
}
