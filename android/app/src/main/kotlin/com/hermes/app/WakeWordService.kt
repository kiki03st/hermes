package com.hermes.app

import ai.picovoice.porcupine.PorcupineManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * "제우스" 웨이크워드를 상시 감지하는 포그라운드 서비스. `WatchRelayListenerService`
 * (`WearableListenerService`, 시스템이 Wearable 메시지로 깨움, 포그라운드 아님)와는 성격이
 * 달라 그 구조를 따르지 않는다 — 이건 순수 [Service], 마이크를 계속 들고 있어야 해서
 * `foregroundServiceType="microphone"`로 시작한다(계획 §2).
 *
 * 설정 화면 토글에서만 시작/종료된다 — 그 외 트리거(부팅 등) 없음(계획 §6, 최신 Android의
 * 백그라운드 마이크 포그라운드 서비스 시작 제한 때문에 신뢰 못 함).
 */
class WakeWordService : Service() {
    private var porcupineManager: PorcupineManager? = null
    private var listening = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannelIfNeeded()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        scope.launch {
            WakeWordBus.recognitionFinished.collect { resumeListening() }
        }
        startPorcupine()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        stopPorcupineQuietly()
        scope.cancel()
        super.onDestroy()
    }

    private fun startPorcupine() {
        val accessKey = BuildConfig.PICOVOICE_ACCESS_KEY
        if (accessKey.isBlank()) {
            Log.w(TAG, "PICOVOICE_ACCESS_KEY 없음 — local.properties의 picovoice.accessKey 설정 필요, 웨이크워드 비활성")
            return
        }
        runCatching {
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeywordPath("zeus_ko.ppn")
                .setModelPath("porcupine_params_ko.pv")
                .setSensitivity(0.7f)
                .build(applicationContext) { _ -> onWakeWordDetected() }
            porcupineManager?.start()
            listening = true
        }.onFailure { e ->
            // AccessKey 검증 네트워크 실패, 에셋 누락(zeus_ko.ppn/porcupine_params_ko.pv —
            // docs/wake-word-setup.md 참고), 마이크 점유 충돌 등 — 크래시 대신 조용히 비활성.
            Log.e(TAG, "Porcupine 초기화 실패 — 웨이크워드 비활성 상태로 둠", e)
        }
    }

    /** Porcupine 콜백 스레드에서 호출된다. 마이크는 단일 클라이언트 제약이 있어(Picovoice
     * GitHub issue #331) STT가 잡기 전에 반드시 먼저 놓는다. */
    private fun onWakeWordDetected() {
        pauseListening()
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_AUTO_RECOGNIZE, true)
            },
        )
    }

    private fun pauseListening() {
        runCatching { porcupineManager?.stop() }
        listening = false
    }

    private fun resumeListening() {
        if (listening) return
        runCatching {
            porcupineManager?.start()
            listening = true
        }.onFailure { Log.e(TAG, "Porcupine 재시작 실패", it) }
    }

    private fun stopPorcupineQuietly() {
        runCatching { porcupineManager?.stop() }
        runCatching { porcupineManager?.delete() }
        porcupineManager = null
        listening = false
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Hermes")
            .setContentText("\"제우스\" 웨이크워드 대기 중")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createNotificationChannelIfNeeded() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Hermes 웨이크워드", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "hermes-wakeword"
        private const val NOTIFICATION_ID = 42
    }
}
