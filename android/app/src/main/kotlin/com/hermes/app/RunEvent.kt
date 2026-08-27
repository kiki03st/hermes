package com.hermes.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** `/v1/runs/{id}/events`가 SSE로 보내는 이벤트 — Hermes v0.20.6 게이트웨이 소스
 * (`gateway/platforms/api_server.py`의 `_sse_frame`/`_make_run_event_callback`/
 * `_handle_run_approval` 등)를 읽어 확인하고, 이 PC에 실제로 뜬 게이트웨이에
 * `POST /v1/runs`로 run을 만들어 `message.delta`/`tool.started`/`tool.completed`/
 * `reasoning.available`/`approval.request`/`run.completed` 이벤트를 직접 받아
 * 필드를 대조 확인했다(2026-08-28). `approval.request`는 트러스트 게이트를 실제로
 * 통과시켜서(쓰기 도구 → 승인 대기 → `POST /approval` → 도구 실행 재개 → COM 부재로
 * 우아하게 실패) 왕복 전체를 확인했다 — 승인 게이트 2차가 AutoCAD 없이 검증
 * 가능하다는 계획 §E의 핵심 주장이 여기서 실측으로 증명됐다.
 *
 * 확인 안 된 필드를 지어내지 않기 위해, 여기 없는 이벤트 타입이나 필드는 [Unknown]으로
 * 원본 [JsonObject]를 그대로 들고 넘긴다 — 화면은 최소한 raw 텍스트로라도 보여줄 수 있다.
 *
 * ⚠️ SSE 프레임에 `event:` 줄은 절대 안 온다 — `_handle_run_events`가
 * `_sse_frame(event)`를 `event=` 키워드 인자 없이 부른다(소스 확인 + 실측 둘 다 일치).
 * 그래서 파서는 `data:` 줄의 JSON 페이로드 안에 있는 `"event"` 키로 타입을 가른다.
 * [SseFrameReader]가 SSE 프레임 자체(`data:`/빈 줄/`:` 주석)를 파싱해 이 함수에
 * 순수 JSON 텍스트만 넘긴다.
 */
sealed interface RunEvent {
    val runId: String
    val timestamp: Double

    data class MessageDelta(
        override val runId: String,
        override val timestamp: Double,
        val delta: String,
    ) : RunEvent

    data class ReasoningAvailable(
        override val runId: String,
        override val timestamp: Double,
        val text: String,
    ) : RunEvent

    data class ToolStarted(
        override val runId: String,
        override val timestamp: Double,
        val tool: String?,
        val preview: String?,
    ) : RunEvent

    data class ToolCompleted(
        override val runId: String,
        override val timestamp: Double,
        val tool: String?,
        val durationSeconds: Double?,
        val isError: Boolean,
    ) : RunEvent

    /** 승인 대기. [choices]를 폰 다이얼로그의 버튼으로 그대로 렌더한다 — 하드코딩
     * 금지(계획 §B, `_approval_event_choices`가 상황별로 좁혀서 준다). [raw]에는
     * `command`/`description`/`pattern_key`/`pattern_keys`/`request_id` 같은, 승인
     * 종류마다 달라지는 부가 필드가 원본 그대로 들어있다(실측 예시, 트러스트 게이트가
     * MCP 쓰기 도구를 막았을 때: `command`="MCP tool 'modify' on UNTRUSTED server ...
     * wants to run.", `choices`=["once","session","always","deny"]) — 트러스트
     * 게이트가 만드는 값이라 이 클라이언트가 스키마를 못 박을 수 없다. */
    data class ApprovalRequest(
        override val runId: String,
        override val timestamp: Double,
        val choices: List<String>,
        val raw: JsonObject,
    ) : RunEvent

    data class ApprovalResponded(
        override val runId: String,
        override val timestamp: Double,
        val choice: String,
        val resolved: Int,
    ) : RunEvent

    data class RunCompleted(
        override val runId: String,
        override val timestamp: Double,
        val output: String,
    ) : RunEvent

    data class RunFailed(
        override val runId: String,
        override val timestamp: Double,
        val error: String,
    ) : RunEvent

    data class RunCancelled(override val runId: String, override val timestamp: Double) : RunEvent

    data class RunSteered(
        override val runId: String,
        override val timestamp: Double,
        val accepted: Boolean,
    ) : RunEvent

    /** 위 목록에 없는 이벤트 타입(`subagent.start` 등 — 이 파이프라인이 안 쓰는 것)이거나
     * 필드가 기대와 다를 때. [event]는 원본 "event" 값 그대로(없으면 null). */
    data class Unknown(
        override val runId: String,
        override val timestamp: Double,
        val event: String?,
        val raw: JsonObject,
    ) : RunEvent

    companion object {
        /** SSE `data:` 줄 하나의 JSON 텍스트를 파싱한다. `run_id`가 없으면(형식이 완전히
         * 어긋난 텍스트) null — 그 외에는 최소한 [Unknown]으로라도 살려서 돌려준다. */
        fun parse(jsonText: String): RunEvent? {
            val obj = runCatching { Json.parseToJsonElement(jsonText) }.getOrNull()
                as? JsonObject ?: return null
            val runId = obj.str("run_id") ?: return null
            val timestamp = obj["timestamp"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val type = obj.str("event")

            return when (type) {
                "message.delta" -> MessageDelta(runId, timestamp, obj.str("delta") ?: "")
                "reasoning.available" -> ReasoningAvailable(runId, timestamp, obj.str("text") ?: "")
                "tool.started" -> ToolStarted(runId, timestamp, obj.str("tool"), obj.str("preview"))
                "tool.completed" -> ToolCompleted(
                    runId,
                    timestamp,
                    obj.str("tool"),
                    obj["duration"]?.jsonPrimitive?.doubleOrNull,
                    obj["error"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
                "approval.request" -> ApprovalRequest(
                    runId,
                    timestamp,
                    choices = obj["choices"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?: emptyList(),
                    raw = obj,
                )
                "approval.responded" -> ApprovalResponded(
                    runId,
                    timestamp,
                    choice = obj.str("choice") ?: "",
                    resolved = obj["resolved"]?.jsonPrimitive?.intOrNull ?: 0,
                )
                "run.completed" -> RunCompleted(runId, timestamp, obj.str("output") ?: "")
                "run.failed" -> RunFailed(runId, timestamp, obj.str("error") ?: "")
                "run.cancelled" -> RunCancelled(runId, timestamp)
                "run.steered" -> RunSteered(
                    runId,
                    timestamp,
                    obj["accepted"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
                else -> Unknown(runId, timestamp, type, obj)
            }
        }

        private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    }
}
