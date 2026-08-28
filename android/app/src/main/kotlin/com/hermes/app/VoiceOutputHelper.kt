package com.hermes.app

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 답변 낭독 + 짧은 진동. `android/wear/.../VoiceOutputHelper.kt`와 같은 패턴을 폰 앱에
 * 이식한 것 — 서로 다른 모듈이라 코드 공유는 안 하지만 동작은 동일하게 맞춘다.
 *
 * 벨소리 모드를 존중한다 — 사용자가 일부러 무음/진동으로 바꿔놨는데 TTS로 소리를 내는 건
 * 그 의도를 정면으로 거스르는 것이기 때문:
 * - `RINGER_MODE_NORMAL`: 낭독 + 진동
 * - `RINGER_MODE_VIBRATE`: 진동만(소리 대신 진동으로 알아채겠다는 의도이므로 진동은 유지)
 * - `RINGER_MODE_SILENT`: 아무 물리적 신호도 안 냄(알림만 조용히 뜸)
 */
class VoiceOutputHelper(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private var tts: TextToSpeech? = TextToSpeech(appContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.KOREAN
        }
    }

    fun speak(text: String) {
        if (audioManager?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hermes-response")
    }

    fun vibrateShort() {
        if (audioManager?.ringerMode == AudioManager.RINGER_MODE_SILENT) return
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
