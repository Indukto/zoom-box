package com.example

import androidx.compose.runtime.Stable

/**
 * EXIF metadata rendered as short display labels for the in-app gallery /
 * photo viewer ("24mm", "1/1000s", "ISO 100", "90°", …).
 */
@Stable
data class ExifData(
    val focalLength: String = "--",
    val shutterSpeed: String = "--",
    val iso: String = "--",
    /**
     * EXIF orientation tag rendered as a short label ("90°", "180°",
     * "270°", "Mirrored H/V", …). "--" when the tag is absent or NORMAL
     * (which is what this app writes: pixels are baked upright at save
     * time, so no rotation is needed for display).
     */
    val orientation: String = "--"
)

/**
 * A film profile backed by a 3D `.cube` LUT in `assets/`.
 *
 * The LUT defines the base color grade; the default slider values are applied
 * on top of it when the preset is selected and remain user-adjustable.
 */
@Stable
enum class FilmPreset(
    val displayName: String,
    val assetPath: String,
    val defaultTemp: Float = 0f,
    val defaultTint: Float = 0f,
    val defaultExposure: Float = 0f,
    /**
     * Film-grain strength added on top of the LUT at save time, in [0, 1].
     * Real film stocks have visible silver-halide / dye-cloud grain that
     * varies with ISO and emulsion type — higher for instant / fast films,
     * lower for slow color negatives. Creative presets get a subtle dose
     * for a cohesive analog feel.
     */
    val defaultGrainStrength: Float = 0f,
    /**
     * Per-channel chromatic grain fraction in [0, 1] (R, G, B independent
     * noise samples mixed into the monochrome delta). 0 = strictly achromatic
     * grain (B&W negatives, where any chroma speckle looks wrong against the
     * paper response). Higher values introduce colored "dye cloud" speckles,
     * useful if a future color stock preset wants a warm-toned organic grain.
     */
    val defaultGrainChroma: Float = 0f,
    // ── New per-preset tonal & creative parameters ──────────────────────
    /**
     * Film S-curve strength in [0, 1]. Applies a characteristic S-shaped
     * tone response: a gentle toe lifts shadows, a smooth shoulder compresses
     * highlights. 0 = linear (no curve). Real films have a pronounced S-curve
     * in their D-log-E response.
     */
    val defaultFilmCurve: Float = 0f,
    /**
     * Per-preset contrast multiplier. 1.0 = neutral, <1.0 reduces contrast,
     * >1.0 increases contrast. Applied after the LUT as a final tonal tweak.
     */
    val defaultContrast: Float = 1.0f,
    /**
     * Per-preset saturation multiplier. 1.0 = neutral, <1.0 desaturates,
     * >1.0 saturates more. Applied after the LUT.
     */
    val defaultSaturation: Float = 1.0f,
    /**
     * Halation / bloom strength in [0, 1]. Simulates light scattering through
     * film emulsion layers, creating a warm glow around bright highlights.
     * Key characteristic of color negative stocks like Portra.
     */
    val defaultBloom: Float = 0f,
    /**
     * Split toning — shadow tint as (R, G, B) additive offset in [0, 1] range.
     * These are added weighted by (1 - luma), so darker regions get more tint.
     */
    val shadowTintR: Float = 0f,
    val shadowTintG: Float = 0f,
    val shadowTintB: Float = 0f,
    /**
     * Shadow tint strength in [0, 1]. Scales the shadow color offset.
     */
    val shadowTintStrength: Float = 0f,
    /**
     * Split toning — highlight tint as (R, G, B) additive offset in [0, 1] range.
     * These are added weighted by luma, so brighter regions get more tint.
     */
    val highlightTintR: Float = 0f,
    val highlightTintG: Float = 0f,
    val highlightTintB: Float = 0f,
    /**
     * Highlight tint strength in [0, 1]. Scales the highlight color offset.
     */
    val highlightTintStrength: Float = 0f,
    /**
     * Chromatic fringing strength in pixels (normalized to texture coords).
     * Shifts the R and B channels relative to G to simulate color channel
     * misregistration in instant film. 0 = no fringing.
     */
    val defaultFringing: Float = 0f,
    // ── Dreamcore-style extras ────────────────────────────────────────────
    /**
     * Soft-focus blur strength in [0, 1]. Blends each pixel with a 3x3 box
     * blur kernel of its (WB+exposed) neighbours so the image reads as
     * slightly out-of-focus / hazy. 0 = sharp (no blur applied); 1 = full
     * 3x3 box blur. Used by the dreamcore-style preset to simulate the
     * characteristic gauzy / "almost-not-there" focus of dreamcore
     * photography without resampling the full sensor.
     */
    val defaultSoftFocus: Float = 0f,
    /**
     * Strength of the milky pastel haze overlay in [0, 1]. Blends the
     * output toward the [milkyTintR]/[milkyTintG]/[milkyTintB] cream color
     * with strength weighted toward darker regions, producing the
     * signature dreamcore "frosted glass" / pastel-wash look. 0 = no
     * overlay.
     */
    val defaultMilkyMix: Float = 0f,
    /**
     * Per-channel color of the milky haze overlay (R, G, B independent),
     * each in [0, 1]. Presets default to 0 (the overlay is skipped
     * entirely when
     * [defaultMilkyMix] = 0 so the zero defaults are inert).
     */
    val milkyTintR: Float = 0f,
    val milkyTintG: Float = 0f,
    val milkyTintB: Float = 0f,
    // ── Opt-in artifacts (zero defaults keep existing presets pixel-identical) ──
    /**
     * Filmic highlight roll-off in [0, 1]. Compresses the highlights above
     * a soft ~0.7 knee into a rounded shoulder; 0 = linear (no roll-off).
     * Consumed identically by the live GL shader and the CPU capture filter.
     */
    val defaultHighlightRolloff: Float = 0f,
    /**
     * Black-point fade in [0, 1]. Lifts shadows toward mid-gray while
     * leaving mid-tones and highlights nearly untouched; 0 = no fade.
     */
    val defaultFade: Float = 0f,
    /**
     * Vignette strength multiplier. 1.0 reproduces the original built-in
     * falloff; 0 disables it, >1 darkens the corners more.
     */
    val defaultVignette: Float = 1f,
    /** Procedural dust-speck strength in [0, 1]. */
    val defaultDust: Float = 0f,
    /** Procedural vertical film-scratch strength in [0, 1]. */
    val defaultScratch: Float = 0f,
    /** Warm corner light-leak strength in [0, 1]. */
    val defaultLightLeak: Float = 0f) {

    WARM_PORTRAIT(
        "Warm Portrait",
        "luts/kodak_portra_160_vc.cube",
        defaultGrainStrength = 0.05f,
        defaultGrainChroma = 0.3f,       // dye-cloud grain from color emulsion layers
        defaultFilmCurve = 0.20f,
        defaultContrast = 1.05f,
        defaultSaturation = 1.0f,
        defaultBloom = 0.18f,
        shadowTintR = 0.0f, shadowTintG = 0.0f, shadowTintB = 0.025f,  // cool blue shadows
        shadowTintStrength = 0.06f,
        highlightTintR = 0.04f, highlightTintG = 0.02f, highlightTintB = 0.0f,  // warm highlights
        highlightTintStrength = 0.08f,
        defaultFringing = 0.0f
    ),
    MONOCHROME_400(
        "Monochrome 400",
        "luts/kodak_bw_400_cn.cube",
        defaultGrainStrength = 0.32f,
        defaultGrainChroma = 0f,          // strict mono — chroma speckles would look wrong on B&W
        defaultFilmCurve = 0.35f,
        defaultContrast = 1.35f,
        defaultSaturation = 0.0f,
        defaultBloom = 0.0f,
        shadowTintR = 0f, shadowTintG = 0f, shadowTintB = 0f,
        shadowTintStrength = 0f,
        highlightTintR = 0f, highlightTintG = 0f, highlightTintB = 0f,
        highlightTintStrength = 0f,
        defaultFringing = 0.0f
    ),
    INSTANT_CLASSIC(
        "Instant Classic",
        "luts/polaroid_px-680.cube",
        defaultGrainStrength = 0.18f,
        defaultGrainChroma = 0.35f,       // high-ISO instant film has chunky dye clouds
        defaultFilmCurve = 0.20f,
        defaultContrast = 1.10f,
        defaultSaturation = 1.15f,
        defaultBloom = 0.25f,
        shadowTintR = 0.0f, shadowTintG = 0.005f, shadowTintB = 0.01f,
        shadowTintStrength = 0.04f,
        highlightTintR = 0.04f, highlightTintG = 0.015f, highlightTintB = 0.0f,
        highlightTintStrength = 0.06f,
        defaultFringing = 0.006f
    ),
    CROSS_PROCESS(
        "Cross Process",
        "luts/kodak_elite_100_xpro.cube",
        defaultGrainStrength = 0.08f,
        defaultGrainChroma = 0.20f,       // cross-processing accentuates color grain
        defaultFilmCurve = 0.25f,
        defaultContrast = 1.20f,
        defaultSaturation = 1.30f,
        defaultBloom = 0.05f,
        shadowTintR = 0.0f, shadowTintG = 0.015f, shadowTintB = 0.02f,  // green/cyan shadows
        shadowTintStrength = 0.08f,
        highlightTintR = 0.03f, highlightTintG = 0.02f, highlightTintB = 0.0f,  // warm highlights
        highlightTintStrength = 0.07f,
        defaultFringing = 0.003f
    ),
    INSTANT_VINTAGE(
        "Instant Vintage",
        "luts/polaroid_669_++.cube",
        defaultGrainStrength = 0.10f,
        defaultGrainChroma = 0.25f,       // peel-apart film with visible dye specks
        defaultFilmCurve = 0.20f,
        defaultContrast = 1.15f,
        defaultSaturation = 1.20f,
        defaultBloom = 0.20f,
        shadowTintR = 0.0f, shadowTintG = 0.0f, shadowTintB = 0.015f,
        shadowTintStrength = 0.05f,
        highlightTintR = 0.03f, highlightTintG = 0.01f, highlightTintB = 0.0f,
        highlightTintStrength = 0.07f,
        defaultFringing = 0.008f
    ),
    MOODY(
        "Moody",
        "luts/moody.cube",
        defaultGrainStrength = 0.12f,
        defaultGrainChroma = 0.15f,       // light grain adds to the moody aesthetic
        defaultFilmCurve = 0.50f,
        defaultContrast = 1.40f,
        defaultSaturation = 0.85f,
        defaultBloom = 0.08f,
        shadowTintR = 0.0f, shadowTintG = 0.0f, shadowTintB = 0.04f,  // strong blue shadows
        shadowTintStrength = 0.15f,
        highlightTintR = 0.06f, highlightTintG = 0.03f, highlightTintB = 0.0f,  // strong warm highlights
        highlightTintStrength = 0.12f,
        defaultFringing = 0.0f
    ),
    MUTED_MEADOW(
        "Muted Meadow",
        "luts/Muted Meadow.cube",
        defaultGrainStrength = 0.06f,
        defaultGrainChroma = 0.20f,
        defaultFilmCurve = 0.15f,
        defaultContrast = 1.05f,
        defaultSaturation = 0.70f,
        defaultBloom = 0.10f,
        shadowTintR = 0.0f, shadowTintG = 0.015f, shadowTintB = 0.02f,  // teal shadows
        shadowTintStrength = 0.08f,
        highlightTintR = 0.02f, highlightTintG = 0.01f, highlightTintB = 0.0f,  // warm highlights
        highlightTintStrength = 0.05f,
        defaultFringing = 0.0f
    ),
    SUNLIT_SPILL(
        "Sunlit Spill",
        "luts/Sunlit Spill.cube",
        defaultGrainStrength = 0.08f,
        defaultGrainChroma = 0.25f,       // warm halation-style chroma grain
        defaultFilmCurve = 0.10f,
        defaultContrast = 1.0f,
        defaultSaturation = 1.10f,
        defaultBloom = 0.30f,
        shadowTintR = 0.0f, shadowTintG = 0.0f, shadowTintB = 0.0f,  // neutral shadows
        shadowTintStrength = 0.0f,
        highlightTintR = 0.04f, highlightTintG = 0.025f, highlightTintB = 0.0f,  // golden highlights
        highlightTintStrength = 0.10f,
        defaultFringing = 0.002f
    ),
    GOLDEN_200(
        "Golden 200",
        "luts/golden_200.cube",
        defaultGrainStrength = 0.10f,
        defaultGrainChroma = 0.30f,
        defaultFilmCurve = 0.25f,
        defaultContrast = 1.15f,
        defaultSaturation = 1.25f,
        defaultBloom = 0.10f,
        shadowTintR = 0.0f, shadowTintG = 0.0f, shadowTintB = 0.02f,  // cool blue shadows
        shadowTintStrength = 0.05f,
        highlightTintR = 0.05f, highlightTintG = 0.02f, highlightTintB = 0.0f,  // warm highlights
        highlightTintStrength = 0.08f,
        defaultFringing = 0.002f,
        defaultVignette = 1.05f
    ),
    STREET_MONO_400(
        "Street Mono 400",
        "luts/street_mono_400.cube",
        defaultGrainStrength = 0.35f,
        defaultGrainChroma = 0f,          // strict mono — chroma speckles look wrong on B&W
        defaultFilmCurve = 0.40f,
        defaultContrast = 1.45f,
        defaultSaturation = 0.0f,
        defaultBloom = 0.0f,
        defaultVignette = 1.10f
    ),
    VIVID_COOL_400(
        "Vivid Cool 400",
        "luts/vivid_cool_400.cube",
        defaultGrainStrength = 0.06f,
        defaultGrainChroma = 0.20f,
        defaultFilmCurve = 0.18f,
        defaultContrast = 1.10f,
        defaultSaturation = 1.25f,
        defaultBloom = 0.05f,
        shadowTintR = 0.0f, shadowTintG = 0.02f, shadowTintB = 0.03f,  // cyan-green shadows
        shadowTintStrength = 0.07f,
        highlightTintR = 0.0f, highlightTintG = 0.03f, highlightTintB = 0.01f,  // yellow-green highlights
        highlightTintStrength = 0.06f,
        defaultFringing = 0.001f,
        defaultVignette = 1.05f
    ),
    CCD_DIGICAM(
        "CCD Digicam",
        "luts/ccd_digicam.cube",
        defaultGrainStrength = 0.04f,
        defaultGrainChroma = 0.35f,       // digital chroma speckle, not film dye cloud
        defaultFilmCurve = 0.30f,
        defaultContrast = 1.10f,
        defaultSaturation = 0.85f,
        defaultBloom = 0.02f,
        shadowTintR = 0.0f, shadowTintG = 0.01f, shadowTintB = 0.02f,  // subtle cool shadows
        shadowTintStrength = 0.06f,
        highlightTintR = 0.02f, highlightTintG = 0.03f, highlightTintB = 0.04f,  // cool highlights
        highlightTintStrength = 0.05f,
        defaultVignette = 1.12f,
        defaultDust = 0.20f,
        defaultLightLeak = 0.08f
    ),
    PASTEL_INSTANT(
        "Pastel Instant",
        "luts/pastel_instant.cube",
        defaultGrainStrength = 0.08f,
        defaultGrainChroma = 0.25f,
        defaultFilmCurve = 0.15f,
        defaultContrast = 0.95f,
        defaultSaturation = 1.05f,
        defaultBloom = 0.20f,
        highlightTintR = 0.05f, highlightTintG = 0.03f, highlightTintB = 0.01f,  // warm highlights
        highlightTintStrength = 0.10f,
        defaultMilkyMix = 0.12f,
        milkyTintR = 0.98f, milkyTintG = 0.93f, milkyTintB = 0.85f,
        defaultFade = 0.06f,
        defaultVignette = 1.15f,
        defaultLightLeak = 0.15f
    ),

    /**
     * Pass-through preset — no LUT, no grain, no film curve, identity
     * contrast/saturation, no bloom, no split toning, no fringing. Picking
     * this profile is equivalent to a stock phone camera: the sensor
     * image is captured with raw white-balance applied and zero
     * film-grade processing on top.
     *
     * Placed LAST in the enum (rather than first) so that the existing
     * DataStore-persisted `filmStyleScrollIndex` / `activePreset` keep
     * their old mapping for upgraded installs: index 0 stays
     * WARM_PORTRAIT instead of silently shifting to NORMAL on relaunch.
     *
     * The blank `assetPath` is the signal to `loadLut()` to skip the
     * CubeLutParser entirely (it short-circuits on `isBlank()` rather
     * than throwing an IOException each time the preset is selected).
     */
    NORMAL(
        "Normal",
        ""
    );
}
