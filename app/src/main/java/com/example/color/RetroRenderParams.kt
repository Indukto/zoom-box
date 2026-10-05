package com.example.color

import com.example.FilmPreset

/**
 * One immutable snapshot of every color/tone parameter that both render
 * back-ends consume:
 *  - the live GPU preview ([LutPreviewRenderer])
 *  - the CPU capture pipeline (`CameraViewModel.applyRetroFilter`)
 *
 * A [FilmPreset] plus the user's white-balance/exposure adjustments is
 * flattened into a single [RetroRenderParams] via [toRetroRenderParams], so
 * preview and capture share the same ordered signal chain instead of each
 * re-deriving the preset's knobs from its own copy of [FilmPreset].
 *
 * This is a pure Kotlin value type (no Android dependencies) so the mapping
 * can be unit-tested on the JVM. It intentionally only carries parameters the
 * two back-ends actually consume today; DAZZ-style fields ZoomBox does not
 * implement yet (lens distortion, skin protection, halation, dust/scratches,
 * light leaks, ...) should be added here as opt-in zero-default fields when
 * their render stages land. [highlightRolloff] and [fade] are the first two
 * of those stages and are already consumed by both the GL shader and the CPU
 * filter.
 */
data class RetroRenderParams(
    // ── User-adjustable white balance + exposure ──
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val exposure: Float = 0f,
    // ── Tone + film curve ──
    val filmCurve: Float = 0f,
    val contrast: Float = 1.0f,
    val saturation: Float = 1.0f,
    val bloom: Float = 0f,
    val fringing: Float = 0f,
    // ── Split toning ──
    val shadowTintR: Float = 0f,
    val shadowTintG: Float = 0f,
    val shadowTintB: Float = 0f,
    val shadowTintStrength: Float = 0f,
    val highlightTintR: Float = 0f,
    val highlightTintG: Float = 0f,
    val highlightTintB: Float = 0f,
    val highlightTintStrength: Float = 0f,
    // ── Dreamcore-style extras ──
    val softFocus: Float = 0f,
    val milkyMix: Float = 0f,
    val milkyTintR: Float = 0f,
    val milkyTintG: Float = 0f,
    val milkyTintB: Float = 0f,
    // ── Capture-only film grain (the GPU preview does not render grain yet) ──
    val grainStrength: Float = 0f,
    val grainChroma: Float = 0f,
    // ── Opt-in artifacts (zero defaults keep existing presets pixel-identical) ──
    /** Filmic highlight roll-off in [0,1]; soft shoulder above a ~0.7 knee. */
    val highlightRolloff: Float = 0f,
    /** Black-point fade in [0,1]; lifts shadows toward mid-gray. */
    val fade: Float = 0f,
    /** Vignette strength multiplier; 1.0 reproduces the original falloff. */
    val vignette: Float = 1f,
    /** Procedural dust specks in [0,1]. */
    val dust: Float = 0f,
    /** Procedural vertical film scratches in [0,1]. */
    val scratch: Float = 0f,
    /** Warm corner light leak in [0,1]. */
    val lightLeak: Float = 0f,
    // ── LUT identity ──
    /** Asset path of the `.cube` LUT; empty string = pass-through (no LUT). */
    val lutPath: String = ""
) {
    /**
     * True when any non-LUT stage actually changes pixels, i.e. the capture
     * pipeline must run its (potentially expensive) filter pass. Mirrors the
     * previous inline condition so behaviour is unchanged. LUT presence is
     * checked separately by callers because a LUT can fail to parse to null.
     */
    val needsProcessing: Boolean
        get() =
            temperature != 0f || tint != 0f || exposure != 0f ||
                filmCurve > 0f ||
                contrast != 1.0f || saturation != 1.0f ||
                bloom > 0f || fringing > 0f ||
                shadowTintStrength > 0f || highlightTintStrength > 0f ||
                softFocus > 0f || milkyMix > 0f ||
                grainStrength > 0f ||
                highlightRolloff > 0f || fade > 0f ||
                vignette != 1f || dust > 0f || scratch > 0f || lightLeak > 0f
}

/**
 * Flattens [FilmPreset]'s default look plus the caller's white-balance/exposure
 * adjustments into one render-parameter snapshot shared by the live preview and
 * the capture pipeline.
 */
/**
 * Duration of the film-look crossfade in the live viewfinder: the outgoing
 * look's [RetroRenderParams] (plus its LUT — see `LutPreviewRenderer`) is
 * interpolated toward the incoming look's snapshot over this window instead
 * of snapping. Captures always use the final target snapshot, never a
 * mid-fade interpolation.
 */
const val LOOK_CROSSFADE_DURATION_MS = 250

private fun lerpFloat(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/**
 * Linearly interpolates every continuous grade parameter toward [to] by [t].
 *
 * Used once per animation frame by the viewfinder crossfade driver, so this
 * stays allocation-free apart from the returned snapshot and clamps [t] into
 * [0, 1] — callers pass raw animation values that can briefly overshoot.
 *
 * [lutPath] is discrete (a LUT can't be half-parsed): the outgoing path is
 * kept for t < 1 and the target wins exactly at t = 1. The live preview
 * blends the two uploaded LUT textures separately via its `uLutMix` uniform;
 * the capture pipeline never calls this and always grades with the final
 * target snapshot.
 */
fun RetroRenderParams.lerp(to: RetroRenderParams, t: Float): RetroRenderParams {
    val k = t.coerceIn(0f, 1f)
    return RetroRenderParams(
        temperature = lerpFloat(temperature, to.temperature, k),
        tint = lerpFloat(tint, to.tint, k),
        exposure = lerpFloat(exposure, to.exposure, k),
        filmCurve = lerpFloat(filmCurve, to.filmCurve, k),
        contrast = lerpFloat(contrast, to.contrast, k),
        saturation = lerpFloat(saturation, to.saturation, k),
        bloom = lerpFloat(bloom, to.bloom, k),
        fringing = lerpFloat(fringing, to.fringing, k),
        shadowTintR = lerpFloat(shadowTintR, to.shadowTintR, k),
        shadowTintG = lerpFloat(shadowTintG, to.shadowTintG, k),
        shadowTintB = lerpFloat(shadowTintB, to.shadowTintB, k),
        shadowTintStrength = lerpFloat(shadowTintStrength, to.shadowTintStrength, k),
        highlightTintR = lerpFloat(highlightTintR, to.highlightTintR, k),
        highlightTintG = lerpFloat(highlightTintG, to.highlightTintG, k),
        highlightTintB = lerpFloat(highlightTintB, to.highlightTintB, k),
        highlightTintStrength = lerpFloat(highlightTintStrength, to.highlightTintStrength, k),
        softFocus = lerpFloat(softFocus, to.softFocus, k),
        milkyMix = lerpFloat(milkyMix, to.milkyMix, k),
        milkyTintR = lerpFloat(milkyTintR, to.milkyTintR, k),
        milkyTintG = lerpFloat(milkyTintG, to.milkyTintG, k),
        milkyTintB = lerpFloat(milkyTintB, to.milkyTintB, k),
        grainStrength = lerpFloat(grainStrength, to.grainStrength, k),
        grainChroma = lerpFloat(grainChroma, to.grainChroma, k),
        highlightRolloff = lerpFloat(highlightRolloff, to.highlightRolloff, k),
        fade = lerpFloat(fade, to.fade, k),
        vignette = lerpFloat(vignette, to.vignette, k),
        dust = lerpFloat(dust, to.dust, k),
        scratch = lerpFloat(scratch, to.scratch, k),
        lightLeak = lerpFloat(lightLeak, to.lightLeak, k),
        lutPath = if (k >= 1f) to.lutPath else lutPath
    )
}

/**
 * Flattens [FilmPreset]'s default look plus the caller's white-balance/exposure
 * adjustments into one render-parameter snapshot shared by the live preview and
 * the capture pipeline.
 */
fun FilmPreset.toRetroRenderParams(
    temperature: Float = 0f,
    tint: Float = 0f,
    exposure: Float = 0f
): RetroRenderParams = RetroRenderParams(
    temperature = temperature,
    tint = tint,
    exposure = exposure,
    filmCurve = defaultFilmCurve,
    contrast = defaultContrast,
    saturation = defaultSaturation,
    bloom = defaultBloom,
    fringing = defaultFringing,
    shadowTintR = this.shadowTintR,
    shadowTintG = this.shadowTintG,
    shadowTintB = this.shadowTintB,
    shadowTintStrength = this.shadowTintStrength,
    highlightTintR = this.highlightTintR,
    highlightTintG = this.highlightTintG,
    highlightTintB = this.highlightTintB,
    highlightTintStrength = this.highlightTintStrength,
    softFocus = defaultSoftFocus,
    milkyMix = defaultMilkyMix,
    milkyTintR = this.milkyTintR,
    milkyTintG = this.milkyTintG,
    milkyTintB = this.milkyTintB,
    grainStrength = defaultGrainStrength,
    grainChroma = defaultGrainChroma,
    highlightRolloff = defaultHighlightRolloff,
    fade = defaultFade,
    vignette = defaultVignette,
    dust = defaultDust,
    scratch = defaultScratch,
    lightLeak = defaultLightLeak,
    lutPath = assetPath
)
