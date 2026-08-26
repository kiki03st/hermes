package com.hermes.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

object PhoneNodeResolver {
    /** 페어링된 폰 노드 ID. PLAN.md 제약: 워치는 항상 폰 테더링을 거치고 서버를 직접 호출하지 않는다. */
    suspend fun findPhoneNodeId(context: Context): String? {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        return nodes.firstOrNull()?.id
    }
}
