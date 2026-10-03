package com.example.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Unit tests for GlassOverlay — the pure geometry/tuning module behind the
 * liquid-glass shader uniforms. No Android dependencies needed, runs on JVM.
 *
 * Covers the UI → GL contract:
 * - fromDp: dp rect + optical tuning → view pixels
 * - rectUniform: view pixels → normalized shader UV, clamped to [0, 1]
 * - paramsUniform: (refraction px, blur px, dispersion, rim px) packing
 * - clamped: sane ranges for degenerate layout input
 */
class GlassOverlayTest {

    // --- fromDp ---

    @Test
    fun `fromDp converts the rect to view pixels using the density`() {
        val overlay = GlassOverlay.fromDp(
            leftDp = 10f, topDp = 20f, widthDp = 96f, heightDp = 44f,
            cornerRadiusDp = 22f, density = 3f
        )
        assertEquals(30f, overlay.leftPx, 0.001f)
        assertEquals(60f, overlay.topPx, 0.001f)
        assertEquals(318f, overlay.rightPx, 0.001f)
        assertEquals(192f, overlay.bottomPx, 0.001f)
        assertEquals(66f, overlay.radiusPx, 0.001f)
    }

    @Test
    fun `fromDp derives the optical tuning from the glass height`() {
        val overlay = GlassOverlay.fromDp(
            leftDp = 0f, topDp = 0f, widthDp = 96f, heightDp = 44f,
            cornerRadiusDp = 22f, density = 3f
        )
        val heightPx = 44f * 3f
        assertEquals(heightPx * GlassOverlay.REFRACTION_FRACTION, overlay.refractionPx, 0.001f)
        assertEquals(heightPx * GlassOverlay.BLUR_FRACTION, overlay.blurPx, 0.001f)
        assertEquals(GlassOverlay.DISPERSION, overlay.dispersion, 0.001f)
        assertEquals(GlassOverlay.RIM_WIDTH_DP * 3f, overlay.rimWidthPx, 0.001f)
    }

    @Test
    fun `fromDp keeps the radius within half the shorter side`() {
        val overlay = GlassOverlay.fromDp(
            leftDp = 0f, topDp = 0f, widthDp = 40f, heightDp = 20f,
            cornerRadiusDp = 200f, density = 1f
        )
        assertEquals(10f, overlay.radiusPx, 0.001f)
    }

    @Test
    fun `fromDp rejects a non-positive density`() {
        assertThrows(IllegalArgumentException::class.java) {
            GlassOverlay.fromDp(
                leftDp = 0f, topDp = 0f, widthDp = 96f, heightDp = 44f,
                cornerRadiusDp = 22f, density = 0f
            )
        }
    }

    // --- rectUniform ---

    @Test
    fun `rectUniform normalizes view pixels to unit UV space`() {
        val overlay = GlassOverlay(
            leftPx = 100f, topPx = 50f, rightPx = 300f, bottomPx = 150f,
            radiusPx = 20f, refractionPx = 2f, blurPx = 1f,
            dispersion = 0.05f, rimWidthPx = 1.5f
        )
        val rect = overlay.rectUniform(viewWidthPx = 400, viewHeightPx = 200)
        assertEquals(0.25f, rect[0], 0.001f)
        assertEquals(0.25f, rect[1], 0.001f)
        assertEquals(0.75f, rect[2], 0.001f)
        assertEquals(0.75f, rect[3], 0.001f)
    }

    @Test
    fun `rectUniform clamps a surface hanging off the view to the unit range`() {
        val overlay = GlassOverlay(
            leftPx = -40f, topPx = -10f, rightPx = 500f, bottomPx = 260f,
            radiusPx = 20f, refractionPx = 2f, blurPx = 1f,
            dispersion = 0.05f, rimWidthPx = 1.5f
        )
        val rect = overlay.rectUniform(viewWidthPx = 400, viewHeightPx = 200)
        assertEquals(0f, rect[0], 0.001f)
        assertEquals(0f, rect[1], 0.001f)
        assertEquals(1f, rect[2], 0.001f)
        assertEquals(1f, rect[3], 0.001f)
    }

    @Test
    fun `rectUniform rejects a non-positive view size`() {
        val overlay = GlassOverlay.fromDp(
            leftDp = 0f, topDp = 0f, widthDp = 96f, heightDp = 44f,
            cornerRadiusDp = 22f, density = 1f
        )
        assertThrows(IllegalArgumentException::class.java) {
            overlay.rectUniform(viewWidthPx = 0, viewHeightPx = 200)
        }
    }

    // --- paramsUniform ---

    @Test
    fun `paramsUniform packs refraction blur dispersion and rim`() {
        val overlay = GlassOverlay(
            leftPx = 0f, topPx = 0f, rightPx = 10f, bottomPx = 10f,
            radiusPx = 5f, refractionPx = 2.5f, blurPx = 1.25f,
            dispersion = 0.05f, rimWidthPx = 1.5f
        )
        val params = overlay.paramsUniform()
        assertEquals(2.5f, params[0], 0.001f)
        assertEquals(1.25f, params[1], 0.001f)
        assertEquals(0.05f, params[2], 0.001f)
        assertEquals(1.5f, params[3], 0.001f)
    }

    // --- clamped ---

    @Test
    fun `clamped orders reversed rect edges`() {
        val overlay = GlassOverlay(
            leftPx = 300f, topPx = 150f, rightPx = 100f, bottomPx = 50f,
            radiusPx = 10f, refractionPx = 2f, blurPx = 1f,
            dispersion = 0.05f, rimWidthPx = 1.5f
        ).clamped()
        assertEquals(100f, overlay.leftPx, 0.001f)
        assertEquals(50f, overlay.topPx, 0.001f)
        assertEquals(300f, overlay.rightPx, 0.001f)
        assertEquals(150f, overlay.bottomPx, 0.001f)
    }

    @Test
    fun `clamped keeps optical values inside sane ranges`() {
        val overlay = GlassOverlay(
            leftPx = 0f, topPx = 0f, rightPx = 10f, bottomPx = 10f,
            radiusPx = 50f, refractionPx = -2f, blurPx = -1f,
            dispersion = 2f, rimWidthPx = -1.5f
        ).clamped()
        assertEquals(5f, overlay.radiusPx, 0.001f)  // half the shorter side
        assertEquals(0f, overlay.refractionPx, 0.001f)
        assertEquals(0f, overlay.blurPx, 0.001f)
        assertEquals(1f, overlay.dispersion, 0.001f)
        assertEquals(0f, overlay.rimWidthPx, 0.001f)
    }
}
