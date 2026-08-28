package com.hermes.app.ui.chat

import com.hermes.app.RunEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JSON 픽스처는 `RunEventTest.kt`와 동일 — 이 PC에 실제로 띄운 Hermes 게이트웨이가
 * 보낸 원문(2026-08-28 실측)이다. 합성 이벤트를 새로 지어내지 않고 그대로 재사용한다. */
class ChatReducerTest {

    private fun turnFrom(vararg json: String): ChatMessage.AssistantTurn {
        var messages = ChatReducer.startAssistantTurn(emptyList())
        for (j in json) {
            val event = RunEvent.parse(j) ?: error("fixture failed to parse: $j")
            messages = ChatReducer.applyEvent(messages, event)
        }
        return messages.last() as ChatMessage.AssistantTurn
    }

    @Test
    fun `appendUserMessage adds a User item`() {
        val messages = ChatReducer.appendUserMessage(emptyList(), "3x4m 방 그려줘")

        assertEquals(1, messages.size)
        check(messages[0] is ChatMessage.User)
        assertEquals("3x4m 방 그려줘", (messages[0] as ChatMessage.User).text)
    }

    @Test
    fun `startAssistantTurn appends an empty streaming turn`() {
        val messages = ChatReducer.startAssistantTurn(
            ChatReducer.appendUserMessage(emptyList(), "hi"),
        )

        assertEquals(2, messages.size)
        val turn = messages[1] as ChatMessage.AssistantTurn
        assertEquals("", turn.textSoFar)
        assertTrue(turn.isStreaming)
    }

    @Test
    fun `sequential message deltas accumulate into the same turn, not new items`() {
        val delta1 = """{"event": "message.delta", "run_id": "run_x", "timestamp": 1.0, "delta": "I see"}"""
        val delta2 = """{"event": "message.delta", "run_id": "run_x", "timestamp": 1.1, "delta": " a room"}"""

        var messages = ChatReducer.startAssistantTurn(emptyList())
        messages = ChatReducer.applyEvent(messages, RunEvent.parse(delta1)!!)
        messages = ChatReducer.applyEvent(messages, RunEvent.parse(delta2)!!)

        assertEquals(1, messages.size)
        val turn = messages[0] as ChatMessage.AssistantTurn
        assertEquals("I see a room", turn.textSoFar)
    }

    @Test
    fun `reasoning available populates the turn's reasoning field`() {
        val json = """{"event": "reasoning.available", "run_id": "run_x", "timestamp": 1.0, "text": "thinking..."}"""

        val turn = turnFrom(json)

        assertEquals("thinking...", turn.reasoning)
    }

    @Test
    fun `tool started then completed transitions RUNNING to DONE by matching tool name`() {
        val started = """{"event": "tool.started", "run_id": "run_x", "timestamp": 1.0, "tool": "tool_describe", "preview": null}"""
        val completed = """{"event": "tool.completed", "run_id": "run_x", "timestamp": 1.1, "tool": "tool_describe", "duration": 0.1, "error": false}"""

        val turn = turnFrom(started, completed)

        assertEquals(1, turn.toolActivity.size)
        assertEquals("tool_describe", turn.toolActivity[0].tool)
        assertEquals(ToolState.DONE, turn.toolActivity[0].state)
    }

    @Test
    fun `tool completed with error true marks the activity ERROR`() {
        val started = """{"event": "tool.started", "run_id": "run_x", "timestamp": 1.0, "tool": "modify", "preview": null}"""
        val completed = """{"event": "tool.completed", "run_id": "run_x", "timestamp": 1.1, "tool": "modify", "duration": 0.093, "error": true}"""

        val turn = turnFrom(started, completed)

        assertEquals(ToolState.ERROR, turn.toolActivity[0].state)
    }

    @Test
    fun `approval request attaches PendingApproval with dynamic choices, not hardcoded`() {
        // 실측: acad-write-test(trust:untrusted)의 modify 도구를 호출했을 때 나온 원문.
        val json = """{"command": "MCP tool 'modify' on UNTRUSTED server 'acad-write-test' wants to run. This tool is write-capable (no readOnlyHint=true annotation) and may modify external state.", "description": "Server 'acad-write-test' is configured 'trust: untrusted'. Approve to run 'modify' once, or deny to block it.", "pattern_key": "mcp_elicitation", "pattern_keys": ["mcp_elicitation"], "request_id": "e623c08f6cdf405ca7cc3c6e52af7f54", "event": "approval.request", "run_id": "run_x", "timestamp": 1.0, "choices": ["once", "session", "always", "deny"]}"""

        val turn = turnFrom(json)

        assertEquals(listOf("once", "session", "always", "deny"), turn.approval?.choices)
        assertTrue(turn.approval?.command?.startsWith("MCP tool 'modify'") == true)
    }

    @Test
    fun `approval responded clears the pending approval`() {
        val request = """{"event": "approval.request", "run_id": "run_x", "timestamp": 1.0, "choices": ["once", "deny"]}"""
        val responded = """{"event": "approval.responded", "run_id": "run_x", "timestamp": 1.1, "choice": "once", "resolved": 1}"""

        val turn = turnFrom(request, responded)

        assertNull(turn.approval)
    }

    @Test
    fun `run completed stops streaming and uses the final output text`() {
        val json = """{"event": "run.completed", "run_id": "run_x", "timestamp": 1.0, "output": "hello", "usage": {"input_tokens": 1, "output_tokens": 1, "total_tokens": 2}}"""

        val turn = turnFrom(json)

        assertEquals("hello", turn.textSoFar)
        assertTrue(!turn.isStreaming)
    }

    @Test
    fun `run failed stops streaming and records the error`() {
        val json = """{"event": "run.failed", "run_id": "run_x", "timestamp": 1.0, "error": "boom"}"""

        val turn = turnFrom(json)

        assertEquals("boom", turn.error)
        assertTrue(!turn.isStreaming)
    }

    @Test
    fun `run cancelled stops streaming and appends a system notice`() {
        val json = """{"event": "run.cancelled", "run_id": "run_x", "timestamp": 1.0}"""

        var messages = ChatReducer.startAssistantTurn(emptyList())
        messages = ChatReducer.applyEvent(messages, RunEvent.parse(json)!!)

        assertEquals(2, messages.size)
        val turn = messages[0] as ChatMessage.AssistantTurn
        assertTrue(!turn.isStreaming)
        val notice = messages[1] as ChatMessage.SystemNotice
        assertEquals("취소됨", notice.text)
    }

    @Test
    fun `run steered and unknown events are no-ops on the message list`() {
        val steered = """{"event": "run.steered", "run_id": "run_x", "timestamp": 1.0, "accepted": true}"""
        val unknown = """{"event": "subagent.start", "run_id": "run_x", "timestamp": 1.0, "whatever": 123}"""

        var messages = ChatReducer.startAssistantTurn(emptyList())
        val beforeSteered = messages
        messages = ChatReducer.applyEvent(messages, RunEvent.parse(steered)!!)
        assertEquals(beforeSteered, messages)

        val beforeUnknown = messages
        messages = ChatReducer.applyEvent(messages, RunEvent.parse(unknown)!!)
        assertEquals(beforeUnknown, messages)
    }

    @Test
    fun `appendSystemNotice adds a SystemNotice with the given text`() {
        val result = ChatReducer.appendSystemNotice(emptyList(), "업로드 실패: 네트워크 오류")

        val notice = result.single() as ChatMessage.SystemNotice
        assertEquals("업로드 실패: 네트워크 오류", notice.text)
    }
}
