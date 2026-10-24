package eu.indukto.zoombox.color

/**
 * Pure-Kotlin rules for a user-authored look: names, ids, LUT limits and the
 * parameter ranges the editor is allowed to reach.
 *
 * Deliberately separate from [CameraProfileLoader]: the loader parses *assets*
 * that ship with the app and must accept whatever the maintainer authored,
 * while everything a user can type is clamped here before it reaches disk.
 * Framework-free so the rules are unit-testable on the JVM.
 */
object LookDraftValidator {

    /** Longest display name the editor accepts, in characters. */
    const val MAX_NAME_LENGTH = 32

    /** Longest id the store will mint or accept. */
    const val MAX_ID_LENGTH = 40

    /** Used when a name slugifies to nothing (e.g. "!!!"). */
    private const val FALLBACK_ID = "look"

    private val WHITESPACE = Regex("\\s+")
    private val ILLEGAL_ID_CHARS = Regex("[^a-z0-9_]+")

    // ── Parameter ranges ────────────────────────────────────────────────────
    // Tuned so every bundled profile clamps to itself: an edited look must
    // start exactly where its source was, with no slider jumping on open.
    private const val MAX_TINT = 0.2f
    private const val MAX_TINT_STRENGTH = 0.5f
    private const val MAX_FRINGING = 0.05f
    private const val MAX_HALATION_RADIUS = 24f
    private const val MAX_VIGNETTE = 2f
    private const val MIN_CONTRAST = 0.5f
    private const val MAX_CONTRAST = 2f
    private const val MAX_SATURATION = 2f
    private const val MAX_TEMPERATURE = 2f
    private const val MAX_EXPOSURE = 3f

    /** Trimmed, whitespace-collapsed and length-capped. */
    fun sanitizeName(raw: String): String =
        raw.trim().replace(WHITESPACE, " ").take(MAX_NAME_LENGTH)

    fun isValidName(name: String): Boolean = name.isNotBlank()

    /** `"My Warm Portrait"` → `"my_warm_portrait"`; never blank. */
    fun slugFor(name: String): String {
        val slug = name
            .trim()
            .lowercase()
            .replace(ILLEGAL_ID_CHARS, "_")
            .trim('_')
            .take(MAX_ID_LENGTH)
        return slug.ifEmpty { FALLBACK_ID }
    }

    /**
     * True when [id] is safe to use as a file name and as a persisted
     * `active_preset` value: lowercase `[a-z0-9_]` only, non-empty, capped.
     * A stray `/` or a Cyrillic look-alike would otherwise create a file the
     * catalog can never address again.
     */
    fun isValidId(id: String): Boolean =
        id.isNotEmpty() &&
            id.length <= MAX_ID_LENGTH &&
            id == id.lowercase() &&
            !ILLEGAL_ID_CHARS.containsMatchIn(id)

    /**
     * Coerces every grading stage of [params] into its documented range.
     *
     * [RetroRenderParams.lutPath] is intentionally untouched: it is an
     * identity, not a value, and an edited look must keep pointing at the
     * LUT it started from. Its [RetroRenderParams.lutStrength] companion is a
     * value, so it is clamped like every other stage.
     */
    fun clampLook(params: RetroRenderParams): RetroRenderParams = params.copy(
        temperature = params.temperature.coerceIn(-MAX_TEMPERATURE, MAX_TEMPERATURE),
        tint = params.tint.coerceIn(-MAX_TEMPERATURE, MAX_TEMPERATURE),
        exposure = params.exposure.coerceIn(-MAX_EXPOSURE, MAX_EXPOSURE),
        filmCurve = params.filmCurve.coerceIn(0f, 1f),
        contrast = params.contrast.coerceIn(MIN_CONTRAST, MAX_CONTRAST),
        saturation = params.saturation.coerceIn(0f, MAX_SATURATION),
        bloom = params.bloom.coerceIn(0f, 1f),
        fringing = params.fringing.coerceIn(0f, MAX_FRINGING),
        halationStrength = params.halationStrength.coerceIn(0f, 1f),
        halationRadius = params.halationRadius.coerceIn(0f, MAX_HALATION_RADIUS),
        shadowTintR = params.shadowTintR.coerceIn(-MAX_TINT, MAX_TINT),
        shadowTintG = params.shadowTintG.coerceIn(-MAX_TINT, MAX_TINT),
        shadowTintB = params.shadowTintB.coerceIn(-MAX_TINT, MAX_TINT),
        shadowTintStrength = params.shadowTintStrength.coerceIn(0f, MAX_TINT_STRENGTH),
        highlightTintR = params.highlightTintR.coerceIn(-MAX_TINT, MAX_TINT),
        highlightTintG = params.highlightTintG.coerceIn(-MAX_TINT, MAX_TINT),
        highlightTintB = params.highlightTintB.coerceIn(-MAX_TINT, MAX_TINT),
        highlightTintStrength = params.highlightTintStrength.coerceIn(0f, MAX_TINT_STRENGTH),
        softFocus = params.softFocus.coerceIn(0f, 1f),
        milkyMix = params.milkyMix.coerceIn(0f, 1f),
        milkyTintR = params.milkyTintR.coerceIn(0f, 1f),
        milkyTintG = params.milkyTintG.coerceIn(0f, 1f),
        milkyTintB = params.milkyTintB.coerceIn(0f, 1f),
        grainStrength = params.grainStrength.coerceIn(0f, 1f),
        grainChroma = params.grainChroma.coerceIn(0f, 1f),
        grainSize = params.grainSize.coerceIn(0f, 1f),
        highlightRolloff = params.highlightRolloff.coerceIn(0f, 1f),
        fade = params.fade.coerceIn(0f, 1f),
        vignette = params.vignette.coerceIn(0f, MAX_VIGNETTE),
        dust = params.dust.coerceIn(0f, 1f),
        scratch = params.scratch.coerceIn(0f, 1f),
        lightLeak = params.lightLeak.coerceIn(0f, 1f),
        lutStrength = params.lutStrength.coerceIn(0f, 1f)
    )
}

