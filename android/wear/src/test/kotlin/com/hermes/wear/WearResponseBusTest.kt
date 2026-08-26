package com.hermes.wear

import com.hermes.shared.HermesResponse
import com.hermes.shared.HermesStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class WearResponseBusTest {

    @Test
    fun `updateStatus and updateResponse publish to their respective flows independently`() {
        WearResponseBus.updateStatus(HermesStatus(reqId = "r1", stage = "rendering", label = "렌더 거는 중"))
        WearResponseBus.updateResponse(HermesResponse(reqId = "r1", ok = true, text = "완료했어요"))

        assertEquals("렌더 거는 중", WearResponseBus.status.value?.label)
        assertEquals("완료했어요", WearResponseBus.response.value?.text)
    }
}
