package com.hermes.wear

import com.google.android.gms.wearable.MessageClient
import com.hermes.shared.DataLayerPaths
import com.hermes.shared.HermesJson
import com.hermes.shared.HermesRequest
import kotlinx.coroutines.tasks.await

class DataLayerSender(private val messageClient: MessageClient) {
    suspend fun sendRequest(nodeId: String, request: HermesRequest) {
        val payload = HermesJson.encodeToString(HermesRequest.serializer(), request)
            .toByteArray(Charsets.UTF_8)
        messageClient.sendMessage(nodeId, DataLayerPaths.REQUEST, payload).await()
    }
}
