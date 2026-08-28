package com.hermes.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.hermes.app.ui.chat.ChatMessage
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService

/**
 * "제우스" 웨이크워드를 상시 감지하는 포그라운드 서비스. Picovoice Porcupine 대신
 * **Vosk(한국어 소형 모델) + Silero VAD**를 쓴다 — Porcupine 콘솔 가입이 "회사 이메일만"
 * 요구하는 벽에 막혀서(2026-08-28 실측), 계정 자체가 필요 없는 조합으로 교체했다.
 *
 * Vosk는 범용 STT 엔진이라 grammar(`["제우스","[unk]"]`)로 인식 대상을 제한해도 오디오가
 * 들어오는 한 계속 디코딩이 돎 — Porcupine 전용망보다 CPU를 더 쓴다. Silero VAD를 앞에 둬서
 * **말소리가 있을 때만** 그 프레임을 Vosk에 넘기고, 침묵 구간(하루 대부분)엔 디코더 자체를
 * 안 돌려 배터리 부담을 줄인다.
 *
 * `WatchRelayListenerService`(`WearableListenerService`, 시스템이 Wearable 메시지로 깨움,
 * 포그라운드 아님)와는 성격이 달라 그 구조를 안 따른다 — 이건 순수 [Service], 마이크를
 * 계속 들고 있어야 해서 `foregroundServiceType="microphone"`로 시작한다.
 *
 * 설정 화면 토글에서만 시작/종료된다 — 그 외 트리거(부팅 등) 없음(최신 Android의 백그라운드
 * 마이크 포그라운드 서비스 시작 제한 때문에 신뢰 못 함).
 */
class WakeWordService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var model: Model? = null
    private var vad: VadSilero? = null
    private var listenJob: Job? = null
    private var paused = false
    private val overlay by lazy { WakeWordOverlay(applicationContext) }
    private val voiceOutput by lazy { VoiceOutputHelper(applicationContext) }
    private var speechRecognizer: SpeechRecognizer? = null

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
        loadModelAndStart()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        listenJob?.cancel()
        runCatching { speechRecognizer?.destroy() }
        overlay.hide()
        runCatching { voiceOutput.shutdown() }
        runCatching { vad?.close() }
        runCatching { model?.close() }
        scope.cancel()
        super.onDestroy()
    }

    /** `assets/model-ko`(docs/wake-word-setup.md 참고)를 내부 저장소로 복사한 뒤 [Model]을
     * 만든다 — 에셋이 없으면(수동 준비 안 끝남) 실패 콜백만 로그로 남고 조용히 비활성. */
    private fun loadModelAndStart() {
        StorageService.unpack(
            this,
            "model-ko",
            "model-ko",
            { loadedModel ->
                model = loadedModel
                vad = VadSilero(
                    context = applicationContext,
                    sampleRate = SampleRate.SAMPLE_RATE_16K,
                    frameSize = FrameSize.FRAME_SIZE_512,
                    mode = Mode.NORMAL,
                    speechDurationMs = 50,
                    silenceDurationMs = 300,
                )
                startListening()
            },
            { exception ->
                Log.e(TAG, "Vosk 모델 로드 실패 — assets/model-ko 확인 필요(docs/wake-word-setup.md)", exception)
            },
        )
    }

    private fun startListening() {
        if (model == null || vad == null || paused) return
        listenJob?.cancel()
        listenJob = scope.launch { runListenLoop() }
    }

    private fun resumeListening() {
        paused = false
        startListening()
    }

    @Suppress("MissingPermission") // RECORD_AUDIO — 설정 화면 토글에서만 이 서비스가 시작되므로 이미 승인된 상태
    private suspend fun CoroutineScope.runListenLoop() {
        val currentModel = model ?: return
        val currentVad = vad ?: return
        val sampleRate = SampleRate.SAMPLE_RATE_16K.value
        val frameSamples = FrameSize.FRAME_SIZE_512.value

        val recognizer = runCatching { Recognizer(currentModel, sampleRate.toFloat(), GRAMMAR) }
            .getOrElse {
                Log.e(TAG, "Recognizer 생성 실패", it)
                return
            }

        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, frameSamples * 2) * 4,
        )

        val frame = ShortArray(frameSamples)
        // Silero VAD는 오탐을 피하려고 "확신 들 때까지 트리거를 늦게" 잡는 게 알려진 특성이라
        // "제우스"처럼 짧은 단어는 어두 자음이 잘려나갈 수 있다 — 발화 시작 전 마지막 몇 프레임을
        // 미리 담아뒀다가(프리롤) 발화가 확정되는 순간 먼저 흘려보내 앞부분 손실을 줄인다.
        val preRoll = ArrayDeque<ShortArray>()
        val preRollFrames = 4 // 512샘플/16kHz = 32ms 프레임 4개 ≈ 128ms
        try {
            recorder.startRecording()
            var inSpeechSegment = false
            while (isActive && !paused) {
                val read = recorder.read(frame, 0, frameSamples)
                if (read <= 0) continue

                if (currentVad.isSpeech(frame)) {
                    if (!inSpeechSegment) {
                        preRoll.forEach { buffered -> recognizer.acceptWaveForm(buffered, buffered.size) }
                        preRoll.clear()
                    }
                    inSpeechSegment = true
                    if (recognizer.acceptWaveForm(frame, read)) {
                        handleUtterance(recognizer.result)
                        recognizer.reset()
                    }
                } else if (inSpeechSegment) {
                    inSpeechSegment = false
                    handleUtterance(recognizer.finalResult)
                    recognizer.reset()
                } else {
                    preRoll.addLast(frame.copyOf(read))
                    if (preRoll.size > preRollFrames) preRoll.removeFirst()
                }
            }
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            runCatching { recognizer.close() }
        }
    }

    /** 매치 여부와 무관하게 인식된 텍스트를 전부 로그로 남긴다 — "제우스"가 실제로 뭐로
     * 들리는지 확인해야 원인이 발음/볼륨(빈 결과)인지, grammar 사전에 이 단어 자체가 없어서
     * 못 잡는 건지(엉뚱한 텍스트/항상 빈 값) 구분할 수 있다. */
    private fun handleUtterance(resultJson: String) {
        val text = runCatching { JSONObject(resultJson).optString("text") }.getOrDefault("")
        Log.d(TAG, "인식 결과: \"$text\" (원문: $resultJson)")
        if (text.contains(WAKE_WORD)) {
            paused = true
            onWakeWordDetected()
        }
    }

    /** 마이크는 단일 클라이언트 제약이 있어(AudioRecord 한 번에 하나) STT가 잡기 전에
     * [runListenLoop]가 자기 [AudioRecord]를 반드시 먼저 놓는다(위 finally 블록).
     *
     * 앱을 여는 대신(Android 10+ 백그라운드 액티비티 실행 제한 때문에 앱이 닫혀있으면
     * `startActivity()`가 조용히 씹힘 — 실측 확인) [WakeWordOverlay]로 작은 알림만 띄우고
     * 헤드리스 [SpeechRecognizer]로 바로 다음 문장을 받는다. [handleUtterance]는
     * `runListenLoop`의 `Dispatchers.Default` 코루틴에서 불리는데, `SpeechRecognizer`/
     * `WindowManager`는 둘 다 Looper 있는 스레드(메인 스레드)에서만 써야 해서 여기서 바로
     * 갈아탄다. */
    private fun onWakeWordDetected() {
        scope.launch(Dispatchers.Main) { startHeadlessRecognition() }
    }

    private fun startHeadlessRecognition() {
        overlay.show()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "SpeechRecognizer 사용 불가 — 리스닝 재개")
            finishHeadlessRecognition()
            return
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                finishHeadlessRecognition()
                if (!text.isNullOrBlank()) {
                    HermesRuntime.chatState.submit(text) { turn -> deliverAnswer(turn) }
                }
            }
            override fun onError(error: Int) {
                Log.w(TAG, "헤드리스 STT 오류 코드: $error")
                finishHeadlessRecognition()
            }
            override fun onEndOfSpeech() {}
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREAN)
            },
        )
    }

    private fun finishHeadlessRecognition() {
        overlay.hide()
        speechRecognizer?.destroy()
        speechRecognizer = null
        resumeListening()
    }

    /** 오버레이는 STT 캡처가 끝나자마자 사라지고 답변은 그 뒤에 비동기로 도착한다 — 답이
     * 왔다는 걸 앱을 열지 않고도 알 수 있어야 해서 낭독+진동+알림 세 채널로 전달한다.
     * 벨소리 모드 존중은 [VoiceOutputHelper]가 알아서 한다(무음/진동 모드에선 낭독 생략 등). */
    private fun deliverAnswer(turn: ChatMessage.AssistantTurn) {
        val answer = turn.error ?: turn.textSoFar.ifBlank { "응답이 없습니다" }
        voiceOutput.speak(answer)
        voiceOutput.vibrateShort()
        postAnswerNotification(answer)
    }

    private fun postAnswerNotification(answer: String) {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Hermes")
            .setContentText(answer)
            .setStyle(NotificationCompat.BigTextStyle().bigText(answer))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        getSystemService(NotificationManager::class.java).notify(ANSWER_NOTIFICATION_ID, notification)
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
        private const val ANSWER_NOTIFICATION_ID = 43
        private const val WAKE_WORD = "제우스"
        private val GRAMMAR = "[\"$WAKE_WORD\", \"[unk]\"]"
    }
}
