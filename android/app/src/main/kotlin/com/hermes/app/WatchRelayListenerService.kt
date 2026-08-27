package com.hermes.app

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.hermes.shared.DataLayerPaths
import com.hermes.shared.HermesJson
import com.hermes.shared.HermesRequest
import com.hermes.shared.HermesResponse
import com.hermes.shared.WatchPreamble
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 워치가 보낸 [HermesRequest]를 받아 Hermes에 물어보고 [HermesResponse]로 돌려준다.
 * 시스템이 이 서비스를 필요할 때 깨우므로 폰 앱이 강제종료된 상태에서도 동작한다
 * (PLAN.md Stage 2 검증 항목). */
class WatchRelayListenerService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != DataLayerPaths.REQUEST) return

        val request = runCatching {
            HermesJson.decodeFromString(HermesRequest.serializer(), String(event.data, Charsets.UTF_8))
        }.getOrNull() ?: return

        scope.launch { handleRequest(event.sourceNodeId, request) }
    }

    private suspend fun handleRequest(nodeId: String, request: HermesRequest) {
        val settingsStore = SettingsStore(applicationContext)
        val settings = settingsStore.snapshot()
        val client = HermesApiClient(
            transport = UrlConnectionHttpTransport(),
            serverUrl = { settings.serverUrl },
            apiKey = { settings.apiKey },
        )

        // 워치 명령은 (PLAN.md 설계상) 매번 독립된 단발성 지시라 session_id는 안 쓰지만,
        // "치과 예약 잡아준 사람이 누구인지" 같은 장기 기억은 폰 앱과 동일한 스코프를 써서
        // 계속 이어지게 한다.
        val longTermMemoryKey = settingsStore.getOrCreateLongTermMemoryKey()
        val outcome = client.sendChat(WatchPreamble.wrap(request.text), sessionKey = longTermMemoryKey)

        val response = when (outcome) {
            is ChatOutcome.Success -> HermesResponse(reqId = request.reqId, ok = true, text = outcome.text)
            is ChatOutcome.Failure -> HermesResponse(
                reqId = request.reqId,
                ok = false,
                text = "요청 처리 중 문제가 발생했어요 (${outcome.statusCode}).",
            )
        }

        sendToNode(nodeId, response)
    }

    private fun sendToNode(nodeId: String, response: HermesResponse) {
        val payload = HermesJson.encodeToString(HermesResponse.serializer(), response)
            .toByteArray(Charsets.UTF_8)
        Wearable.getMessageClient(applicationContext).sendMessage(nodeId, DataLayerPaths.RESPONSE, payload)
    }
}
