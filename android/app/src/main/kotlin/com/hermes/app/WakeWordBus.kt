package com.hermes.app

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * [WakeWordService](백그라운드)가 STT 액티비티 결과를 기다렸다가 Porcupine 리스닝을 다시
 * 켜야 할 때 쓰는 신호선 — 서비스는 이미 살아서 돌고 있으니 콜드스타트 문제가 없어
 * `WearResponseBus`(`android/wear/.../WearResponseBus.kt`)와 같은 StateFlow/SharedFlow
 * 버스 패턴을 그대로 재사용한다.
 *
 * 반대 방향("웨이크워드 떴다, STT 켜라")은 이 버스를 안 쓴다 — 앱이 완전히 죽어있으면
 * 구독할 Flow 자체가 없어서 Intent extra + `startActivity`만 확실히 동작한다
 * (`WakeWordService.onWakeWordDetected`/`MainActivity.EXTRA_AUTO_RECOGNIZE` 참고).
 */
object WakeWordBus {
    private val _recognitionFinished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val recognitionFinished: SharedFlow<Unit> = _recognitionFinished

    fun notifyRecognitionFinished() {
        _recognitionFinished.tryEmit(Unit)
    }
}
