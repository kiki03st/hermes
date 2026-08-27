package com.hermes.app

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 아래 JSON 문자열은 전부 이 PC에 실제로 띄운 Hermes 게이트웨이(v0.20.6)가
 * `GET /v1/runs/{id}/events`로 보낸 원문을 그대로 붙여넣은 것이다(2026-08-28 실측) —
 * 합성 픽스처가 아니다. `approval.request`는 트러스트 게이트가 실제 MCP 쓰기 도구
 * 호출을 막았을 때 나온 진짜 페이로드다. */
class RunEventTest {

    @Test
    fun `parses a real message delta event`() {
        val json = """{"event": "message.delta", "run_id": "run_0b2b4b80e4854e3190e70559e929687a", "timestamp": 1787871064.8296082, "delta": "I see"}"""

        val event = RunEvent.parse(json)

        check(event is RunEvent.MessageDelta)
        assertEquals("run_0b2b4b80e4854e3190e70559e929687a", event.runId)
        assertEquals("I see", event.delta)
        assertEquals(1787871064.8296082, event.timestamp, 0.0)
    }

    @Test
    fun `parses a real reasoning available event`() {
        val json = """{"event": "reasoning.available", "run_id": "run_x", "timestamp": 1.0, "text": "thinking..."}"""

        val event = RunEvent.parse(json)

        check(event is RunEvent.ReasoningAvailable)
        assertEquals("thinking...", event.text)
    }

    @Test
    fun `parses a real tool started event`() {
        val json = """{"event": "tool.started", "run_id": "run_x", "timestamp": 1787871065.8382373, "tool": "tool_describe", "preview": null}"""

        val event = RunEvent.parse(json)

        check(event is RunEvent.ToolStarted)
        assertEquals("tool_describe", event.tool)
        assertNull(event.preview)
    }

    @Test
    fun `parses a real tool completed event with error true`() {
        val json = """{"event": "tool.completed", "run_id": "run_x", "timestamp": 1.0, "tool": "tool_describe", "duration": 0.093, "error": true}"""

        val event = RunEvent.parse(json)

        check(event is RunEvent.ToolCompleted)
        assertEquals("tool_describe", event.tool)
        assertEquals(0.093, event.durationSeconds!!, 0.0001)
        assertTrue(event.isError)
    }

    @Test
    fun `parses a real tool completed event with error false`() {
        val json = """{"event": "tool.completed", "run_id": "run_x", "timestamp": 1.0, "tool": "tool_describe", "duration": 0.102, "error": false}"""

        val event = RunEvent.parse(json)

        check(event is RunEvent.ToolCompleted)
        assertEquals(false, event.isError)
    }

    @Test
    fun `parses the real approval request that the trust gate produced for a blocked write tool`() {
        // 실측: acad-write-test(trust:untrusted)의 modify 도구를 호출했을 때 나온 원문.
        val json = """{"command": "MCP tool 'modify' on UNTRUSTED server 'acad-write-test' wants to run. This tool is write-capable (no readOnlyHint=true annotation) and may modify external state.", "description": "Server 'acad-write-test' is configured 'trust: untrusted'. Approve to run 'modify' once, or deny to block it.", "pattern_key": "mcp_elicitation", "pattern_keys": ["mcp_elicitation"], "request_id": "e623c08f6cdf405ca7cc3c6e52af7f54", "event": "approval.request", "run_id": "run_933a9e935b67465aa74fa934937b5f3c", "timestamp": 1787871109.9152088, "choices": ["once", "session", "always", "deny"]}"""

        val event = RunEvent.parse(json)

        check(event is RunEvent.ApprovalRequest)
        assertEquals("run_933a9e935b67465aa74fa934937b5f3c", event.runId)
        assertEquals(listOf("once", "session", "always", "deny"), event.choices)
        // 승인 종류마다 달라지는 부가 필드(command/description/pattern_key/request_id)는
        // raw 에 원본 그대로 남아 있어야 한다 — 타입이 못 박지 않은 필드도 잃지 않는다.
        assertEquals("mcp_elicitation", event.raw["pattern_key"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parses a real approval responded event`() {
        // 실측: POST /v1/runs/{id}/approval 처리 후 SSE 로 다시 나온 이벤트 형태
        // (핸들러 소스 gateway/platforms/api_server.py:8134-8145 기준).
        val json = """{"event": "approval.responded", "run_id": "run_x", "timestamp": 1.0, "choice": "once", "resolved": 1}"""

        val event = RunEvent.parse(json)

        check(event is RunEvent.ApprovalResponded)
        assertEquals("once", event.choice)
        assertEquals(1, event.resolved)
    }

    @Test
    fun `parses a real run completed event with usage`() {
        val json = """{"event": "run.completed", "run_id": "run_fd60a2c6f59c43679fbed2147c4344fe", "timestamp": 1787870970.4776132, "output": "hello", "usage": {"input_tokens": 10515, "output_tokens": 288, "total_tokens": 10803}}"""

        val event = RunEvent.parse(json)

        check(event is RunEvent.RunCompleted)
        assertEquals("hello", event.output)
    }

    @Test
    fun `parses a run failed event`() {
        val json = """{"event": "run.failed", "run_id": "run_x", "timestamp": 1.0, "error": "boom"}"""

        val event = RunEvent.parse(json)

        check(event is RunEvent.RunFailed)
        assertEquals("boom", event.error)
    }

    @Test
    fun `parses a run cancelled event with no extra fields`() {
        val json = """{"event": "run.cancelled", "run_id": "run_x", "timestamp": 1.0}"""

        val event = RunEvent.parse(json)

        check(event is RunEvent.RunCancelled)
        assertEquals("run_x", event.runId)
    }

    @Test
    fun `parses a run steered event`() {
        val json = """{"event": "run.steered", "run_id": "run_x", "timestamp": 1.0, "accepted": true}"""

        val event = RunEvent.parse(json)

        check(event is RunEvent.RunSteered)
        assertTrue(event.accepted)
    }

    @Test
    fun `unrecognized event type falls back to Unknown instead of throwing`() {
        val json = """{"event": "subagent.start", "run_id": "run_x", "timestamp": 1.0, "whatever": 123}"""

        val event = RunEvent.parse(json)

        check(event is RunEvent.Unknown)
        assertEquals("subagent.start", event.event)
    }

    @Test
    fun `missing event field falls back to Unknown with null type`() {
        val json = """{"run_id": "run_x", "timestamp": 1.0}"""

        val event = RunEvent.parse(json)

        check(event is RunEvent.Unknown)
        assertNull(event.event)
    }

    @Test
    fun `missing run_id yields null rather than throwing`() {
        assertNull(RunEvent.parse("""{"event": "message.delta", "timestamp": 1.0, "delta": "x"}"""))
    }

    @Test
    fun `malformed json yields null rather than throwing`() {
        assertNull(RunEvent.parse("not json at all"))
        assertNull(RunEvent.parse("{"))
    }

    @Test
    fun `non-object json yields null`() {
        assertNull(RunEvent.parse("""["array", "not", "object"]"""))
        assertNull(RunEvent.parse("""42"""))
    }

    @Test
    fun `missing timestamp defaults to zero rather than failing`() {
        val json = """{"event": "run.cancelled", "run_id": "run_x"}"""

        val event = RunEvent.parse(json)

        check(event is RunEvent.RunCancelled)
        assertEquals(0.0, event.timestamp, 0.0)
    }
}
