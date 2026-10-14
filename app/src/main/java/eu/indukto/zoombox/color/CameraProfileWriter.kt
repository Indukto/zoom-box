package eu.indukto.zoombox.color

import org.json.JSONObject

/**
 * Writes a [CameraProfile] back out in the exact shape [CameraProfileLoader]
 * reads, so a user-authored look on disk is indistinguishable *in format* from
 * a bundled look in `assets/cameras/`.
 *
 * The key names below are the contract with the loader — keep the two files in
 * step. `CameraProfileWriterTest` locks that with a round-trip over every
 * bundled profile, which is what makes exporting and re-importing a look safe.
 *
 * Values are written through `Number.toDouble()` rather than as boxed floats:
 * the shortest decimal that round-trips a double also round-trips the float it
 * came from, so `parse(write(params)) == params` holds exactly.
 */
object CameraProfileWriter {

    fun toJson(profile: CameraProfile): String {
        val root = JSONObject()
        root.put("id", profile.id)
        root.put("displayName", profile.displayName)
        root.put("category", profile.category)
        root.put("look", toJsonObject(profile.look))
        return root.toString(2)
    }

    /** The `look` object on its own — the shared grading snapshot, nothing else. */
    fun toJsonObject(look: RetroRenderParams): JSONObject = JSONObject().apply {
        put("lutPath", look.lutPath)
        put("temperature", look.temperature.toDouble())
        put("tint", look.tint.toDouble())
        put("exposure", look.exposure.toDouble())
        put("filmCurve", look.filmCurve.toDouble())
        put("contrast", look.contrast.toDouble())
        put("saturation", look.saturation.toDouble())
        put("bloom", look.bloom.toDouble())
        put("fringing", look.fringing.toDouble())
        put("shadowTintR", look.shadowTintR.toDouble())
        put("shadowTintG", look.shadowTintG.toDouble())
        put("shadowTintB", look.shadowTintB.toDouble())
        put("shadowTintStrength", look.shadowTintStrength.toDouble())
        put("highlightTintR", look.highlightTintR.toDouble())
        put("highlightTintG", look.highlightTintG.toDouble())
        put("highlightTintB", look.highlightTintB.toDouble())
        put("highlightTintStrength", look.highlightTintStrength.toDouble())
        put("softFocus", look.softFocus.toDouble())
        put("milkyMix", look.milkyMix.toDouble())
        put("milkyTintR", look.milkyTintR.toDouble())
        put("milkyTintG", look.milkyTintG.toDouble())
        put("milkyTintB", look.milkyTintB.toDouble())
        put("grainStrength", look.grainStrength.toDouble())
        put("grainChroma", look.grainChroma.toDouble())
        put("highlightRolloff", look.highlightRolloff.toDouble())
        put("fade", look.fade.toDouble())
        put("vignette", look.vignette.toDouble())
        put("dust", look.dust.toDouble())
        put("scratch", look.scratch.toDouble())
        put("lightLeak", look.lightLeak.toDouble())
    }
}

