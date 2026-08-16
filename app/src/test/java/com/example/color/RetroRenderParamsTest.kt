package com.example.color

import com.example.FilmPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [RetroRenderParams] and the [FilmPreset.toRetroRenderParams]
 * mapping. No Android dependencies are needed: the model is a pure Kotlin value
 * type and FilmPreset is just an enum of primitives.
 *
 * The goal is to guarantee that the live GL preview and the CPU capture
 * pipeline receive the exact same parameter snapshot for the same preset +
 * user adjustments, so the viewfinder and the saved JPEG can never drift.
 */
class RetroRenderParamsTest {

    @Test
    fun `normal preset maps to a fully neutral snapshot`() {
        val p = FilmPreset.NORMAL.toRetroRenderParams()

        assertEquals("", p.lutPath)
        assertEquals(0f, p.temperature, 0f)
        assertEquals(0f, p.tint, 0f)
        assertEquals(0f, p.exposure, 0f)
        assertEquals(0f, p.filmCurve, 0f)
        assertEquals(1.0f, p.contrast, 0f)
        assertEquals(1.0f, p.saturation, 0f)
        assertEquals(0f, p.bloom, 0f)
        assertEquals(0f, p.fringing, 0f)
        assertEquals(0f, p.shadowTintStrength, 0f)
        assertEquals(0f, p.highlightTintStrength, 0f)
        assertEquals(0f, p.softFocus, 0f)
        assertEquals(0f, p.milkyMix, 0f)
        assertEquals(0f, p.grainStrength, 0f)
        assertEquals(0f, p.grainChroma, 0f)
        assertFalse(p.needsProcessing)
    }

    @Test
    fun `warm portrait maps every preset knob`() {
        val preset = FilmPreset.WARM_PORTRAIT
        val p = preset.toRetroRenderParams()

        assertEquals(preset.assetPath, p.lutPath)
        assertEquals(preset.defaultFilmCurve, p.filmCurve, 0f)
        assertEquals(preset.defaultContrast, p.contrast, 0f)
        assertEquals(preset.defaultSaturation, p.saturation, 0f)
        assertEquals(preset.defaultBloom, p.bloom, 0f)
        assertEquals(preset.defaultFringing, p.fringing, 0f)
        assertEquals(preset.shadowTintR, p.shadowTintR, 0f)
        assertEquals(preset.shadowTintG, p.shadowTintG, 0f)
        assertEquals(preset.shadowTintB, p.shadowTintB, 0f)
        assertEquals(preset.shadowTintStrength, p.shadowTintStrength, 0f)
        assertEquals(preset.highlightTintR, p.highlightTintR, 0f)
        assertEquals(preset.highlightTintG, p.highlightTintG, 0f)
        assertEquals(preset.highlightTintB, p.highlightTintB, 0f)
        assertEquals(preset.highlightTintStrength, p.highlightTintStrength, 0f)
        assertEquals(preset.defaultSoftFocus, p.softFocus, 0f)
        assertEquals(preset.defaultMilkyMix, p.milkyMix, 0f)
        assertEquals(preset.milkyTintR, p.milkyTintR, 0f)
        assertEquals(preset.milkyTintG, p.milkyTintG, 0f)
        assertEquals(preset.milkyTintB, p.milkyTintB, 0f)
        assertEquals(preset.defaultGrainStrength, p.grainStrength, 0f)
        assertEquals(preset.defaultGrainChroma, p.grainChroma, 0f)
        assertTrue(p.needsProcessing)
    }

    @Test
    fun `user white balance and exposure are merged into the snapshot`() {
        val p = FilmPreset.NORMAL.toRetroRenderParams(
            temperature = 0.5f,
            tint = -0.25f,
            exposure = 1.0f
        )

        assertEquals(0.5f, p.temperature, 0f)
        assertEquals(-0.25f, p.tint, 0f)
        assertEquals(1.0f, p.exposure, 0f)
        // WB/exposure alone must force the filter pass on an otherwise
        // neutral (NORMAL) preset.
        assertTrue(p.needsProcessing)
    }

    @Test
    fun `needsProcessing is true only when a stage actually changes pixels`() {
        assertFalse(RetroRenderParams().needsProcessing)
        assertTrue(RetroRenderParams(contrast = 1.01f).needsProcessing)
        assertTrue(RetroRenderParams(saturation = 0.9f).needsProcessing)
        assertTrue(RetroRenderParams(grainStrength = 0.01f).needsProcessing)
        assertTrue(RetroRenderParams(softFocus = 0.01f).needsProcessing)
        assertTrue(RetroRenderParams(shadowTintStrength = 0.01f).needsProcessing)
        assertTrue(RetroRenderParams(highlightTintStrength = 0.01f).needsProcessing)
        assertTrue(RetroRenderParams(fringing = 0.01f).needsProcessing)
        assertTrue(RetroRenderParams(milkyMix = 0.01f).needsProcessing)
        assertTrue(RetroRenderParams(filmCurve = 0.01f).needsProcessing)
        assertTrue(RetroRenderParams(bloom = 0.01f).needsProcessing)
        assertTrue(RetroRenderParams(temperature = 0.01f).needsProcessing)
        assertTrue(RetroRenderParams(tint = 0.01f).needsProcessing)
        assertTrue(RetroRenderParams(exposure = 0.01f).needsProcessing)
        assertTrue(RetroRenderParams(highlightRolloff = 0.01f).needsProcessing)
        assertTrue(RetroRenderParams(fade = 0.01f).needsProcessing)
        // The LUT is checked separately by callers (a parse can return null),
        // so a lone LUT path must not flip needsProcessing.
        assertFalse(RetroRenderParams(lutPath = "luts/moody.cube").needsProcessing)
    }

    @Test
    fun `every film preset maps without dropping fields`() {
        for (preset in FilmPreset.entries) {
            val p = preset.toRetroRenderParams(
                temperature = 0.1f,
                tint = 0.2f,
                exposure = 0.3f
            )
            assertEquals("${preset.name} lutPath", preset.assetPath, p.lutPath)
            assertEquals("${preset.name} filmCurve", preset.defaultFilmCurve, p.filmCurve, 0f)
            assertEquals("${preset.name} contrast", preset.defaultContrast, p.contrast, 0f)
            assertEquals("${preset.name} saturation", preset.defaultSaturation, p.saturation, 0f)
            assertEquals("${preset.name} grain", preset.defaultGrainStrength, p.grainStrength, 0f)
            assertEquals("${preset.name} grainChroma", preset.defaultGrainChroma, p.grainChroma, 0f)
            assertEquals("${preset.name} temperature", 0.1f, p.temperature, 0f)
            assertEquals("${preset.name} tint", 0.2f, p.tint, 0f)
            assertEquals("${preset.name} exposure", 0.3f, p.exposure, 0f)
        }
    }
}
