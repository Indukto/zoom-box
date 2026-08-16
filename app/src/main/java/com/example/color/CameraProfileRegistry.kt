package com.example.color

import android.content.Context
import com.example.FilmPreset

/**
 * Loads backend look profiles from the `assets/cameras/` JSON files and falls back to
 * the in-code [FilmPreset.toCameraProfile] adapter when a matching JSON file
 * does not exist. JSON wins when present, so adding or tweaking a look becomes
 * an asset change rather than an enum edit — while every existing preset keeps
 * producing identical parameters because the adapter mirrors the enum.
 */
class CameraProfileRegistry(private val context: Context) {

    private val profiles: Map<String, CameraProfile> by lazy { loadProfiles() }

    /** The profile for [preset], preferring a JSON definition when one exists. */
    fun profileFor(preset: FilmPreset): CameraProfile =
        profiles[preset.profileId] ?: preset.toCameraProfile()

    /**
     * The render snapshot the capture pipeline should use for [preset], with
     * the user's live WB/exposure adjustments layered on top of the profile's
     * default look. WB/exposure always come from the user, never the file.
     */
    fun renderParamsFor(
        preset: FilmPreset,
        temperature: Float,
        tint: Float,
        exposure: Float
    ): RetroRenderParams = profileFor(preset).look.copy(
        temperature = temperature,
        tint = tint,
        exposure = exposure
    )

    /** Ids that were actually loaded from JSON (useful for tests/diagnostics). */
    fun loadedProfileIds(): Set<String> = profiles.keys

    private fun loadProfiles(): Map<String, CameraProfile> {
        val result = mutableMapOf<String, CameraProfile>()
        val names = runCatching { context.assets.list("cameras") ?: emptyArray() }
            .getOrDefault(emptyArray())
        for (name in names) {
            if (!name.endsWith(".json")) continue
            val id = name.removeSuffix(".json")
            val profile = runCatching {
                val json = context.assets.open("cameras/$name")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                CameraProfileLoader.parse(json)
            }.getOrNull() ?: continue
            result[id] = profile
        }
        return result
    }
}
