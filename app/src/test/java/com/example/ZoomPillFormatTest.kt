package com.example

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the zoom readout formatting used by
 * [LiquidGlassZoomSelector]. Pure JVM, no Android dependencies.
 */
class ZoomPillFormatTest {

    @Test
    fun `formats one decimal with a multiplication sign`() {
        assertEquals("2.4\u00d7", formatZoomRatio(2.35f))
        assertEquals("1.0\u00d7", formatZoomRatio(1.0f))
        assertEquals("5.0\u00d7", formatZoomRatio(5.0f))
    }

    @Test
    fun `rounds to the nearest tenth`() {
        assertEquals("1.3\u00d7", formatZoomRatio(1.26f))
        assertEquals("3.1\u00d7", formatZoomRatio(3.14f))
    }

    @Test
    fun `is locale independent - no formatted separators`() {
        // String.format with a comma-decimal locale would yield "2,4×".
        val out = formatZoomRatio(2.4f)
        assertEquals("2.4\u00d7", out)
        assertEquals(4, out.length)  // "2.4×" is 4 chars
    }
}
