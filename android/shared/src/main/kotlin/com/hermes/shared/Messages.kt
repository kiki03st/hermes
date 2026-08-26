package com.hermes.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 워치 → 폰. 마이크로 받은 STT 결과.
 * 필드명은 PLAN.md "Data Layer 경로" 표의 페이로드 표기(req_id 등)를 그대로 따른다. */
@Serializable
data class HermesRequest(
    @SerialName("req_id") val reqId: String,
    val text: String,
)

/** 폰 → 워치. 장기 작업 진행 상태 표시용. */
@Serializable
data class HermesStatus(
    @SerialName("req_id") val reqId: String,
    val stage: String,
    val label: String,
)

/** 폰 → 워치. 최종 응답 — 워치는 완성본만 받는다(스트리밍은 폰 전용). */
@Serializable
data class HermesResponse(
    @SerialName("req_id") val reqId: String,
    val ok: Boolean,
    val text: String,
    @SerialName("has_image") val hasImage: Boolean = false,
    @SerialName("run_id") val runId: String? = null,
)

/** 폰 → 워치. 승인 자체는 폰에서만 하고, 워치는 요청이 대기 중이라는 사실만 안다. */
@Serializable
data class HermesApproval(
    @SerialName("req_id") val reqId: String,
    val summary: String,
)
