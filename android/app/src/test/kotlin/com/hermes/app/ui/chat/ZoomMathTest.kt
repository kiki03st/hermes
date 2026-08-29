package com.hermes.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomMathTest {

    @Test
    fun `clampZoom keeps values inside the default 1x to 5x range unchanged`() {
        assertEquals(2.5f, clampZoom(2.5f), 0.001f)
    }

    @Test
    fun `clampZoom floors below the minimum`() {
        assertEquals(1f, clampZoom(0.3f), 0.001f)
    }

    @Test
    fun `clampZoom ceilings above the maximum`() {
        assertEquals(5f, clampZoom(12f), 0.001f)
    }

    @Test
    fun `clampZoom respects custom min and max`() {
        assertEquals(2f, clampZoom(0.5f, min = 2f, max = 8f), 0.001f)
        assertEquals(8f, clampZoom(20f, min = 2f, max = 8f), 0.001f)
    }
}
