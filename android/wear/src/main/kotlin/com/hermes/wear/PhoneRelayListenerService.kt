package com.hermes.wear

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.hermes.shared.DataLayerPaths
import com.hermes.shared.HermesJson
import com.hermes.shared.HermesResponse
import com.hermes.shared.HermesStatus

/** 폰이 보낸 상태/응답을 받는다. 워치 앱이 꺼져 있어도 시스템이 이 서비스를 깨우므로
 * 렌더처럼 오래 걸리는 작업도 "화면을 닫아둔 채" 기다릴 수 있다 (PLAN.md Stage 5). */
class PhoneRelayListenerService : WearableListenerService() {

    private lateinit var voice: VoiceOutputHelper

    override fun onCreate() {
        super.onCreate()
        voice = VoiceOutputHelper(this)
        createNotificationChannelIfNeeded()
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            DataLayerPaths.STATUS -> handleStatus(event.data)
            DataLayerPaths.RESPONSE -> handleResponse(event.data)
        }
    }

    private fun handleStatus(data: ByteArray) {
        val status = runCatching {
            HermesJson.decodeFromString(HermesStatus.serializer(), String(data, Charsets.UTF_8))
        }.getOrNull() ?: return
        WearResponseBus.updateStatus(status)
    }

    private fun handleResponse(data: ByteArray) {
        val response = runCatching {
            HermesJson.decodeFromString(HermesResponse.serializer(), String(data, Charsets.UTF_8))
        }.getOrNull() ?: return

        WearResponseBus.updateResponse(response)
        voice.vibrateShort()
        if (response.ok) {
            voice.speak(response.text)
        }
        notifyResult(response)
    }

    private fun notifyResult(response: HermesResponse) {
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (response.ok) "Hermes" else "Hermes - 오류")
            .setContentText(response.text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        manager.notify(response.reqId.hashCode(), notification)
    }

    private fun createNotificationChannelIfNeeded() {
        val channel = NotificationChannel(CHANNEL_ID, "Hermes 응답", NotificationManager.IMPORTANCE_DEFAULT)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "hermes-response"
    }
}
