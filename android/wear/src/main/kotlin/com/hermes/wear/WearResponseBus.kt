package com.hermes.wear

import com.hermes.shared.HermesResponse
import com.hermes.shared.HermesStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** [PhoneRelayListenerService](백그라운드)와 화면(포그라운드)이 공유하는 최신 상태.
 * 화면이 닫혀 있어도 서비스는 독립적으로 TTS/진동/알림을 낸다 — 이 버스는 화면이
 * 열려 있을 때 보여줄 텍스트만 담당한다 (PLAN.md: "화면 닫아도 됨. 완료 시 알림으로 재호출"). */
object WearResponseBus {
    private val _status = MutableStateFlow<HermesStatus?>(null)
    val status: StateFlow<HermesStatus?> = _status

    private val _response = MutableStateFlow<HermesResponse?>(null)
    val response: StateFlow<HermesResponse?> = _response

    fun updateStatus(status: HermesStatus) {
        _status.value = status
    }

    fun updateResponse(response: HermesResponse) {
        _response.value = response
    }
}
