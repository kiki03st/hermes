package com.hermes.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesApiContractTest {

    @Test
    fun `watch preamble is prepended before the user text`() {
        val wrapped = WatchPreamble.wrap("내일 오후 3시 치과 예약 잡아줘")

        assertTrue(wrapped.startsWith(WatchPreamble.TEXT))
        assertTrue(wrapped.endsWith("내일 오후 3시 치과 예약 잡아줘"))
    }

    @Test
    fun `run sub-paths are built from the run id`() {
        assertEquals("/v1/runs/run_abc123/events", HermesApi.runEventsPath("run_abc123"))
        assertEquals("/v1/runs/run_abc123/stop", HermesApi.runStopPath("run_abc123"))
        assertEquals("/v1/runs/run_abc123/approval", HermesApi.runApprovalPath("run_abc123"))
    }
}
