package com.example.color

import org.json.JSONObject

/**
 * Parses one camera-profile JSON document into a [CameraProfile].
 *
 * This is a pure function with no Android framework dependencies beyond
 * `org.json`, so it can be unit-tested on the JVM. Unknown keys are ignored
 * (matching the analysis note that we only preserve unknown JSON fields if
 * future extensibility is actually needed).
 *
 * The expected document shape:
 *
 * ```json
 * {
 *   "id": "warm_portrait",
 *   "displayName": "Warm Portrait",
 *   "category": "color-negative",
 *   "look": {
 *     "lutPath": "luts/kodak_portra_160_vc.cube",
 *     "filmCurve": 0.20,
 *     "contrast": 1.05,
 *     "saturation": 1.0,
 *     "bloom": 0.18,
 *     "shadowTintR": 0.0, "shadowTintG": 0.0, "shadowTintB": 0.025,
 *     "shadowTintStrength": 0.06,
 *     "highlightTintR": 0.04, "highlightTintG": 0.02, "highlightTintB": 0.0,
 *     "highlightTintStrength": 0.08,
 *     "grainStrength": 0.05, "grainChroma": 0.3,
 *     "highlightRolloff": 0.0, "fade": 0.0,
 *     "vignette": 1.0, "dust": 0.0, "scratch": 0.0, "lightLeak": 0.0
 *   }
 * }
 * ```
 */
object CameraProfileLoader {

    fun parse(json: String): CameraProfile {
        val root = JSONObject(json)
        val id = root.getString("id")
        val displayName = root.optString("displayName", id)
        val category = root.optString("category", "")
        val look = parseLook(root.optJSONObject("look"))
        return CameraProfile(id = id, displayName = displayName, category = category, look = look)
    }

    private fun parseLook(look: JSONObject?): RetroRenderParams {
        if (look == null) return RetroRenderParams()
        return RetroRenderParams(
            lutPath = look.optString("lutPath", ""),
            temperature = look.optDouble("temperature", 0.0).toFloat(),
            tint = look.optDouble("tint", 0.0).toFloat(),
            exposure = look.optDouble("exposure", 0.0).toFloat(),
            filmCurve = look.optDouble("filmCurve", 0.0).toFloat(),
            contrast = look.optDouble("contrast", 1.0).toFloat(),
            saturation = look.optDouble("saturation", 1.0).toFloat(),
            bloom = look.optDouble("bloom", 0.0).toFloat(),
            fringing = look.optDouble("fringing", 0.0).toFloat(),
            shadowTintR = look.optDouble("shadowTintR", 0.0).toFloat(),
            shadowTintG = look.optDouble("shadowTintG", 0.0).toFloat(),
            shadowTintB = look.optDouble("shadowTintB", 0.0).toFloat(),
            shadowTintStrength = look.optDouble("shadowTintStrength", 0.0).toFloat(),
            highlightTintR = look.optDouble("highlightTintR", 0.0).toFloat(),
            highlightTintG = look.optDouble("highlightTintG", 0.0).toFloat(),
            highlightTintB = look.optDouble("highlightTintB", 0.0).toFloat(),
            highlightTintStrength = look.optDouble("highlightTintStrength", 0.0).toFloat(),
            softFocus = look.optDouble("softFocus", 0.0).toFloat(),
            milkyMix = look.optDouble("milkyMix", 0.0).toFloat(),
            milkyTintR = look.optDouble("milkyTintR", 0.0).toFloat(),
            milkyTintG = look.optDouble("milkyTintG", 0.0).toFloat(),
            milkyTintB = look.optDouble("milkyTintB", 0.0).toFloat(),
            grainStrength = look.optDouble("grainStrength", 0.0).toFloat(),
            grainChroma = look.optDouble("grainChroma", 0.0).toFloat(),
            highlightRolloff = look.optDouble("highlightRolloff", 0.0).toFloat(),
            fade = look.optDouble("fade", 0.0).toFloat(),
            vignette = look.optDouble("vignette", 1.0).toFloat(),
            dust = look.optDouble("dust", 0.0).toFloat(),
            scratch = look.optDouble("scratch", 0.0).toFloat(),
            lightLeak = look.optDouble("lightLeak", 0.0).toFloat()
        )
    }
}
