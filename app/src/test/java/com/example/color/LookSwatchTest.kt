package com.example.color

import com.example.FilmPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the picker cosmetics in [LookSwatch]: the thirteen bundled looks keep
 * their hand-tuned colors, and any look id that is added later without a
 * hand-tuned entry still gets an opaque, stable, non-degenerate swatch.
 *
 * Determinism is the actual contract here — the picker grid is keyed by look
 * id, so a swatch that changed between launches would read as the grid
 * reshuffling itself.
 */
class LookSwatchTest {

    private val bundledIds = FilmPreset.entries.map { it.profileId }

    @Test
    fun `every bundled look has a hand-tuned color and glyph`() {
        for (id in bundledIds) {
            val color = LookSwatch.colorFor(id)
            assertEquals("${id} swatch must be opaque", 0xFF, (color ushr 24) and 0xFF)
            assertTrue(
                "${id} swatch must not be the fallback glyph",
                LookSwatch.emojiFor(id) != LookSwatch.FALLBACK_EMOJI
            )
        }
    }

    @Test
    fun `hand-tuned colors stay put`() {
        // Spot-checks rather than a table copy: a palette tweak should be a
        // conscious edit here, not an incidental refactor.
        assertEquals(0xFFD4A56A.toInt(), LookSwatch.colorFor("warm_portrait"))
        assertEquals(0xFF6B6B6B.toInt(), LookSwatch.colorFor("monochrome_400"))
        assertEquals(0xFF9CA3AF.toInt(), LookSwatch.colorFor("normal"))
        assertEquals("📸", LookSwatch.emojiFor("instant_classic"))
    }

    @Test
    fun `look ids are matched case-insensitively`() {
        assertEquals(LookSwatch.colorFor("warm_portrait"), LookSwatch.colorFor("WARM_PORTRAIT"))
        assertEquals(LookSwatch.emojiFor("moody"), LookSwatch.emojiFor("  Moody  "))
    }

    @Test
    fun `an unknown look gets an opaque deterministic swatch`() {
        val first = LookSwatch.colorFor("halation_soft_1984")
        val second = LookSwatch.colorFor("halation_soft_1984")

        assertEquals(first, second)
        assertEquals(0xFF, (first ushr 24) and 0xFF)
        // A derived swatch must not accidentally shadow a bundled one.
        assertNotEquals(LookSwatch.colorFor("moody"), first)
    }

    @Test
    fun `an unknown look gets the fallback glyph`() {
        assertEquals(LookSwatch.FALLBACK_EMOJI, LookSwatch.emojiFor("halation_soft_1984"))
        assertEquals(LookSwatch.FALLBACK_EMOJI, LookSwatch.emojiFor(""))
    }

    @Test
    fun `derived swatches spread across the hue wheel`() {
        // Enough ids that a weak hash would show up as near-duplicates.
        val ids = (1..24).map { "future_look_$it" }
        val colors = ids.map { LookSwatch.colorFor(it) }.toSet()

        // Not a distribution claim — just "adjacent ids do not collapse onto
        // one or two swatches", which is what a missing avalanche step did.
        assertTrue(
            "derived swatches collapsed to ${colors.size} distinct values",
            colors.size >= 12
        )
    }

    @Test
    fun `an empty id falls back to the pass-through swatch`() {
        assertEquals(LookSwatch.colorFor("normal"), LookSwatch.colorFor(""))
        assertEquals(LookSwatch.colorFor("normal"), LookSwatch.colorFor("   "))
    }
}