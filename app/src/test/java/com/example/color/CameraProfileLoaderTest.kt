package com.example.color

import com.example.FilmPreset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for [CameraProfileLoader] and the [FilmPreset.toCameraProfile]
 * adapter. `org.json` is provided by a test-scoped dependency, so no Android
 * framework is involved.
 *
 * The key guarantee is migration parity: the bundled `warm_portrait.json`
 * profile must produce the exact same [RetroRenderParams] as the existing
 * [FilmPreset.WARM_PORTRAIT] enum, so adopting the JSON registry changes no
 * pixel in the current app.
 */
class CameraProfileLoaderTest {

    private val warmPortraitJson = """
        {
          "id": "warm_portrait",
          "displayName": "Warm Portrait",
          "category": "color-negative",
          "look": {
            "lutPath": "luts/kodak_portra_160_vc.cube",
            "filmCurve": 0.20,
            "contrast": 1.05,
            "saturation": 1.0,
            "bloom": 0.18,
            "fringing": 0.0,
            "shadowTintR": 0.0,
            "shadowTintG": 0.0,
            "shadowTintB": 0.025,
            "shadowTintStrength": 0.06,
            "highlightTintR": 0.04,
            "highlightTintG": 0.02,
            "highlightTintB": 0.0,
            "highlightTintStrength": 0.08,
            "softFocus": 0.0,
            "milkyMix": 0.0,
            "grainStrength": 0.05,
            "grainChroma": 0.3,
            "highlightRolloff": 0.0,
            "fade": 0.0
          }
        }
    """.trimIndent()

    @Test
    fun `parses a complete profile document`() {
        val profile = CameraProfileLoader.parse(warmPortraitJson)

        assertEquals("warm_portrait", profile.id)
        assertEquals("Warm Portrait", profile.displayName)
        assertEquals("color-negative", profile.category)
        assertEquals("luts/kodak_portra_160_vc.cube", profile.look.lutPath)
        assertEquals(0.20f, profile.look.filmCurve, 0f)
        assertEquals(1.05f, profile.look.contrast, 0f)
        assertEquals(0.18f, profile.look.bloom, 0f)
        assertEquals(0.025f, profile.look.shadowTintB, 0f)
        assertEquals(0.06f, profile.look.shadowTintStrength, 0f)
        assertEquals(0.05f, profile.look.grainStrength, 0f)
        assertEquals(0.3f, profile.look.grainChroma, 0f)
    }

    @Test
    fun `bundled warm portrait profile matches the FilmPreset adapter`() {
        val fromJson = CameraProfileLoader.parse(warmPortraitJson).look
        val fromEnum = FilmPreset.WARM_PORTRAIT.toRetroRenderParams()
        assertEquals(fromEnum, fromJson)
    }

    @Test
    fun `missing look defaults to a neutral profile`() {
        val profile = CameraProfileLoader.parse(
            """{"id": "normal", "displayName": "Normal"}"""
        )
        assertEquals(RetroRenderParams(), profile.look)
        assertEquals("normal", profile.id)
        assertEquals("Normal", profile.displayName)
        assertEquals("", profile.category)
    }

    @Test
    fun `unknown keys are ignored`() {
        val profile = CameraProfileLoader.parse(
            """
            {
              "id": "future_camera",
              "displayName": "Future Camera",
              "look": { "contrast": 1.2, "futureField": { "nested": true } },
              "frames": [{ "id": "frame_1" }]
            }
            """.trimIndent()
        )
        assertEquals("future_camera", profile.id)
        assertEquals(1.2f, profile.look.contrast, 0f)
    }

    @Test
    fun `adapter derives a stable profile id from the enum`() {
        assertEquals("warm_portrait", FilmPreset.WARM_PORTRAIT.profileId)
        assertEquals("normal", FilmPreset.NORMAL.profileId)
        assertEquals(
            "Warm Portrait",
            FilmPreset.WARM_PORTRAIT.toCameraProfile().displayName
        )
    }

    @Test
    fun `user wb and exposure are layered over the profile look`() {
        val base = FilmPreset.NORMAL.toCameraProfile().look
        val adjusted = base.copy(temperature = 0.5f, tint = -0.25f, exposure = 1f)
        assertEquals(0.5f, adjusted.temperature, 0f)
        assertEquals(-0.25f, adjusted.tint, 0f)
        assertEquals(1f, adjusted.exposure, 0f)
        // Everything else stays neutral.
        assertEquals(1.0f, adjusted.contrast, 0f)
        assertEquals("", adjusted.lutPath)
    }
}
