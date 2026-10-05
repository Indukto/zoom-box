package com.example.color

import kotlin.math.abs

/**
 * Picker cosmetics for a [LookEntry]: the swatch color and the glyph shown
 * on the Film-Style chip, in the preset button and in the swipe toast.
 *
 * Hand-tuned values live in a table keyed by look id so the thirteen bundled
 * looks keep the exact colors they shipped with. Any other id — a JSON-only
 * look added later — falls back to a *deterministic* swatch derived from the
 * id itself: the same look always renders the same color on every device and
 * every launch, which matters because the picker grid is keyed by id and a
 * color that changed between runs would make the grid look randomly shuffled.
 *
 * Deliberately framework-free (plain `Int` ARGB, no Compose types) so the
 * mapping is unit-testable on the JVM; the UI layer wraps the result in
 * `Color(...)`.
 */
object LookSwatch {

    /** Glyph for looks with no hand-tuned entry: a palette, not a stock. */
    const val FALLBACK_EMOJI: String = "🎨"

    private const val FALLBACK_SATURATION = 0.42f
    private const val FALLBACK_VALUE = 0.82f

    // FNV-1a 32-bit constants. Used instead of String.hashCode so the
    // fallback hue does not depend on a JDK implementation detail.
    private const val FNV_OFFSET = -0x7ee3623b // 2166136261
    private const val FNV_PRIME = 0x01000193 // 16777619
    private const val AVALANCHE_MUL = 0x2545f491

    private val PRESET_COLORS: Map<String, Int> = mapOf(
        "warm_portrait" to 0xFFD4A56A.toInt(),
        "monochrome_400" to 0xFF6B6B6B.toInt(),
        "instant_classic" to 0xFF4A90B0.toInt(),
        "cross_process" to 0xFFC04040.toInt(),
        "instant_vintage" to 0xFF8B5E8B.toInt(),
        "moody" to 0xFF2C3E50.toInt(),
        "muted_meadow" to 0xFF7DCEA0.toInt(),
        "sunlit_spill" to 0xFFF39C12.toInt(),
        "golden_200" to 0xFFE0A54A.toInt(),
        "street_mono_400" to 0xFF3B3B3B.toInt(),
        "vivid_cool_400" to 0xFF2E9E8F.toInt(),
        "ccd_digicam" to 0xFF5A7D8C.toInt(),
        // Slightly darker than the surrounding chrome so the "no grade"
        // chip reads as a deliberate preset on the picker bar instead of
        // visually disappearing into the dim chrome of the rest of the row.
        "normal" to 0xFF9CA3AF.toInt()
    )

    private val PRESET_EMOJIS: Map<String, String> = mapOf(
        "warm_portrait" to "🌅",
        "monochrome_400" to "🌑",
        "instant_classic" to "📸",
        "cross_process" to "🎞️",
        "instant_vintage" to "🌆",
        "moody" to "🌧️",
        "muted_meadow" to "🌿",
        "sunlit_spill" to "☀️",
        "golden_200" to "🌟",
        "street_mono_400" to "🖤",
        "vivid_cool_400" to "🍃",
        "ccd_digicam" to "📟",
        "normal" to "📷"
    )

    /** Opaque ARGB swatch for [id]; hand-tuned when known, derived otherwise. */
    fun colorFor(id: String): Int {
        val key = id.trim().lowercase()
        PRESET_COLORS[key]?.let { return it }
        return derivedColor(key)
    }

    /** Glyph for [id]; hand-tuned when known, [FALLBACK_EMOJI] otherwise. */
    fun emojiFor(id: String): String =
        PRESET_EMOJIS[id.trim().lowercase()] ?: FALLBACK_EMOJI

    /**
     * Stable hue from the id, then a fixed mid-saturation / high-value HSV
     * so every derived swatch reads at the same weight as the hand-tuned
     * ones on the dark picker chrome (very light swatches wash out the
     * white name label; very dark ones disappear into the card).
     */
    private fun derivedColor(key: String): Int {
        if (key.isEmpty()) return PRESET_COLORS.getValue("normal")
        var h = FNV_OFFSET
        for (ch in key) {
            h = h xor ch.code
            h *= FNV_PRIME
        }
        // Extra avalanche so short, similar ids ("a1", "a2", …) land far
        // apart on the hue wheel instead of adjacent shades of one color.
        h = h xor (h ushr 15)
        h *= AVALANCHE_MUL
        h = h xor (h ushr 13)
        val hue = ((h ushr 8) and 0xFFFF) / 65535f * 360f
        return hsvArgb(hue, FALLBACK_SATURATION, FALLBACK_VALUE)
    }

    private fun hsvArgb(hueDegrees: Float, saturation: Float, value: Float): Int {
        val chroma = value * saturation
        val sector = (hueDegrees % 360f) / 60f
        val second = chroma * (1f - abs(sector % 2f - 1f))
        val rgb = when (sector.toInt()) {
            0 -> Triple(chroma, second, 0f)
            1 -> Triple(second, chroma, 0f)
            2 -> Triple(0f, chroma, second)
            3 -> Triple(0f, second, chroma)
            4 -> Triple(second, 0f, chroma)
            // sector == 5 or 6 (hue >= 300°, i.e. the magenta-to-red wrap)
            else -> Triple(chroma, 0f, second)
        }
        val lift = value - chroma
        val r = to8(rgb.first + lift)
        val g = to8(rgb.second + lift)
        val b = to8(rgb.third + lift)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun to8(channel: Float): Int = (channel.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
}