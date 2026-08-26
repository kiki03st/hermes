package com.hermes.shared

/** Wearable Data Layer 메시지 경로. PLAN.md "Data Layer 경로" 표 그대로. */
object DataLayerPaths {
    /** 워치 → 폰: [HermesRequest] */
    const val REQUEST = "/hermes/request"

    /** 폰 → 워치: [HermesStatus] */
    const val STATUS = "/hermes/status"

    /** 폰 → 워치: [HermesResponse] */
    const val RESPONSE = "/hermes/response"

    /** 폰 → 워치: [HermesApproval] — 승인은 폰에서만 이뤄지므로 워치는 알림만 받는다 */
    const val APPROVAL = "/hermes/approval"

    /** 폰 → 워치: Asset (축소 썸네일) */
    const val IMAGE = "/hermes/image"
}
