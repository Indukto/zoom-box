package com.example.color

import com.example.FilmPreset

/**
 * A backend "look" profile: the data that tells both the live GPU preview and
 * the capture pipeline how to grade a photo. This is the parameter-driven part
 * of DAZZ's camera catalog that is worth porting — the rest (frames,
 * watermarks, video) is deliberately kept out of this model.
 *
 * Profiles can come from the `assets/cameras/` JSON files (see
 * [CameraProfileLoader])
 * or from an in-code [FilmPreset] adapter (see [toCameraProfile]). A profile
 * owns a default [look]; user white-balance/exposure adjustments are layered
 * on top at render time, never baked into the profile itself.
 */
data class CameraProfile(
    val id: String,
    val displayName: String,
    val category: String = "",
    val look: RetroRenderParams = RetroRenderParams()
)

/** Stable id used to match a [FilmPreset] to a JSON profile file name. */
val FilmPreset.profileId: String
    get() = name.lowercase()

/**
 * Compatibility adapter so the existing [FilmPreset] enum keeps working
 * unchanged while the JSON profile layer is adopted. Produces the exact same
 * [RetroRenderParams] as the enum, which lets the registry prefer a JSON file
 * when present and fall back to this adapter otherwise.
 */
fun FilmPreset.toCameraProfile(): CameraProfile = CameraProfile(
    id = profileId,
    displayName = displayName,
    look = toRetroRenderParams()
)
