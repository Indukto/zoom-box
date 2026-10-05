package com.example.color

import android.graphics.Bitmap
import com.example.FilmPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Behavior-locking tests for [applyRetroFilter], the CPU capture pipeline.
 *
 * These run before the hot-loop refactor as a safety net: every case asserts
 * observable pixel behavior (not implementation details), so the upcoming
 * incremental-x/y loop rewrite and the sin-free grain hash swap can land
 * without silently changing what the saved JPEG looks like.
 *
 * Note: [applyRetroFilter] mutates a mutable receiver in place, so each case
 * snapshots its input pixels (or uses a fresh bitmap per run) before asserting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RetroFilterTest {

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun bitmapOf(w: Int, h: Int, pixels: IntArray): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }

    private fun solid(w: Int, h: Int, argb: Int): IntArray = IntArray(w * h) { argb }

    private fun pixelsOf(bitmap: Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }

    /** Mean of the per-pixel (R+G+B)/3 across the image, in 0..255. */
    private fun meanRgb(pixels: IntArray): Float {
        var sum = 0L
        for (c in pixels) {
            sum += ((c ushr 16) and 0xFF) + ((c ushr 8) and 0xFF) + (c and 0xFF)
        }
        return sum / (3f * pixels.size)
    }

    private fun alphaOf(pixel: Int) = (pixel ushr 24) and 0xFF

    /** Identity (pass-through) 2×2×2 3D LUT: every lattice point maps to itself. */
    private fun identityLut(): CubeLut {
        val data = FloatArray(2 * 2 * 2 * 3)
        for (b in 0..1) for (g in 0..1) for (r in 0..1) {
            val base = ((b * 2 + g) * 2 + r) * 3
            data[base] = r.toFloat()
            data[base + 1] = g.toFloat()
            data[base + 2] = b.toFloat()
        }
        return CubeLut(
            size = 2,
            domainMin = floatArrayOf(0f, 0f, 0f),
            domainMax = floatArrayOf(1f, 1f, 1f),
            data = data
        )
    }

    // ── 1. Pass-through ─────────────────────────────────────────────────

    @Test
    fun `pass-through - NORMAL params with no LUT skip the filter and leave pixels unchanged`() {
        val params = FilmPreset.NORMAL.toRetroRenderParams()
        // The capture pipeline's guard (CameraViewModel) runs the filter only
        // when a LUT is present or some stage needs processing. NORMAL has
        // neither, so the raw frame is saved untouched.
        assertFalse("NORMAL must not require processing", params.needsProcessing)
        val lut: CubeLut? = null // NORMAL's blank asset path parses to null
        assertFalse(lut != null || params.needsProcessing)

        val w = 24
        val h = 16
        val src = IntArray(w * h) { i ->
            (0xFF shl 24) or ((i * 7) and 0xFF shl 16) or ((i * 3) and 0xFF shl 8) or ((i * 11) and 0xFF)
        }
        val before = src.copyOf()
        val input = bitmapOf(w, h, src)

        val output = if (lut != null || params.needsProcessing) {
            runBlocking { input.applyRetroFilter(params, lut) }
        } else {
            input
        }
        assertArrayEquals(before, pixelsOf(output))
    }

    @Test
    fun `pass-through - fully inert params run through every stage byte-identical`() {
        // Same chain, but actually executed: all stages neutral and the
        // baseline vignette disabled. Guards the per-pixel round-trips
        // (float → byte → float → byte) against off-by-one drift.
        val params = RetroRenderParams(vignette = 0f)
        val w = 24
        val h = 16
        val src = IntArray(w * h) { i ->
            (0xFF shl 24) or ((i * 7) and 0xFF shl 16) or ((i * 3) and 0xFF shl 8) or ((i * 11) and 0xFF)
        }
        val before = src.copyOf()
        val out = runBlocking { bitmapOf(w, h, src).applyRetroFilter(params, lut = null) }
        assertArrayEquals(before, pixelsOf(out))
    }

    // ── 2. Determinism ──────────────────────────────────────────────────

    @Test
    fun `determinism - same input and params produce identical output`() {
        // Exercises the whole chain — soft-focus pre-pass, WB, exposure,
        // fringing, film curve, bloom, roll-off, vignette, split toning,
        // fade, grain, and the dust/scratch/light-leak overlay sweep.
        val params = RetroRenderParams(
            temperature = 0.4f,
            tint = -0.3f,
            exposure = 0.5f,
            filmCurve = 0.3f,
            contrast = 1.1f,
            saturation = 1.05f,
            bloom = 0.2f,
            fringing = 0.01f,
            shadowTintB = 0.02f,
            shadowTintStrength = 0.1f,
            highlightTintR = 0.02f,
            highlightTintStrength = 0.1f,
            softFocus = 0.3f,
            milkyMix = 0.15f,
            grainStrength = 0.4f,
            grainChroma = 0.3f,
            highlightRolloff = 0.2f,
            fade = 0.1f,
            vignette = 1f,
            dust = 0.3f,
            scratch = 0.2f,
            lightLeak = 0.2f
        )
        val w = 48
        val h = 32
        val src = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            (0xFF shl 24) or
                ((x * 255 / (w - 1)) shl 16) or
                ((255 - y * 255 / (h - 1)) shl 8) or
                ((x + y) and 0xFF)
        }
        val out1 = runBlocking { bitmapOf(w, h, src).applyRetroFilter(params) }
        val out2 = runBlocking { bitmapOf(w, h, src).applyRetroFilter(params) }
        assertArrayEquals(pixelsOf(out1), pixelsOf(out2))
    }

    // ── 3. Exposure monotonicity ────────────────────────────────────────

    @Test
    fun `exposure - plus EV brightens and minus EV darkens, monotonically`() {
        val w = 32
        val h = 32
        val src = solid(w, h, 0xFF808080.toInt())
        fun meanAt(exposure: Float): Float = meanRgb(
            runBlocking {
                bitmapOf(w, h, src).applyRetroFilter(
                    RetroRenderParams(exposure = exposure, vignette = 0f)
                )
            }.let(::pixelsOf)
        )
        val plus = meanAt(1f)
        val base = meanAt(0f)
        val minus = meanAt(-1f)
        assertTrue("+EV ($plus) must exceed base ($base)", plus > base)
        assertTrue("base ($base) must exceed -EV ($minus)", base > minus)
    }

    // ── 4. Vignette ─────────────────────────────────────────────────────

    @Test
    fun `vignette - darkens corners while leaving the center untouched`() {
        val w = 64
        val h = 64
        val src = solid(w, h, 0xFF808080.toInt())
        val withVig = pixelsOf(
            runBlocking {
                bitmapOf(w, h, src).applyRetroFilter(RetroRenderParams(vignette = 1f))
            }
        )
        val noVig = pixelsOf(
            runBlocking {
                bitmapOf(w, h, src).applyRetroFilter(RetroRenderParams(vignette = 0f))
            }
        )
        val cornerIn = 0
        val center = (h / 2) * w + w / 2
        val withVigCorner = withVig[cornerIn] and 0xFF // blue channel
        val noVigCorner = noVig[cornerIn] and 0xFF
        val srcCorner = src[cornerIn] and 0xFF
        assertTrue(
            "corner must darken with vignette ($withVigCorner < $noVigCorner)",
            withVigCorner < noVigCorner
        )
        assertEquals("no-vignette corner stays at source level", srcCorner, noVigCorner)
        assertEquals(
            "center pixel is inside the inner radius: unchanged",
            src[center],
            withVig[center]
        )
    }

    // ── 5. Grain bounds ─────────────────────────────────────────────────

    @Test
    fun `grain - keeps channels in byte range, preserves alpha, and is zero-mean`() {
        val w = 64
        val h = 64
        val src = solid(w, h, 0xFF808080.toInt())
        val out = pixelsOf(
            runBlocking {
                bitmapOf(w, h, src).applyRetroFilter(
                    RetroRenderParams(grainStrength = 0.5f, grainChroma = 0.3f, vignette = 0f)
                )
            }
        )
        for (c in out) {
            assertTrue("alpha preserved", alphaOf(c) == 0xFF)
            assertTrue("R in 0..255", ((c ushr 16) and 0xFF) in 0..255)
            assertTrue("G in 0..255", ((c ushr 8) and 0xFF) in 0..255)
            assertTrue("B in 0..255", (c and 0xFF) in 0..255)
        }
        val drift = kotlin.math.abs(meanRgb(out) - meanRgb(src))
        assertTrue("grain must be zero-mean (drift $drift < 2.0)", drift < 2.0f)
    }

    // ── 6. Soft focus ───────────────────────────────────────────────────

    @Test
    fun `soft focus - diffuses an impulse to its neighbors and preserves alpha`() {
        val w = 5
        val h = 5
        val cx = 2
        val cy = 2
        val src = solid(w, h, 0xFF000000.toInt()).also {
            it[cy * w + cx] = 0xFFFFFFFF.toInt()
        }
        val out = pixelsOf(
            runBlocking {
                bitmapOf(w, h, src).applyRetroFilter(
                    RetroRenderParams(softFocus = 1f, vignette = 0f)
                )
            }
        )
        val center = out[cy * w + cx] and 0xFF
        // softFocus = 1 is a pure 3x3 box average: a lone 255 impulse spreads
        // 255/9 ≈ 28 to the center and each of its 8 neighbors.
        assertTrue("impulse must diffuse away from center ($center < 255)", center < 255)
        assertTrue("center retains its share of the impulse ($center)", center in 24..33)
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val neighbor = out[(cy + dy) * w + (cx + dx)] and 0xFF
            assertTrue(
                "neighbor ($dx,$dy) must receive diffusion, got $neighbor",
                neighbor in 24..33
            )
        }
        // Ring outside the 3x3 kernel window must remain black…
        for (i in out.indices) {
            val x = i % w
            val y = i / w
            val inKernel = kotlin.math.abs(x - cx) <= 1 && kotlin.math.abs(y - cy) <= 1
            if (!inKernel) assertEquals("outside kernel stays black at ($x,$y)", 0, out[i] and 0xFF)
        }
        // …with alpha untouched everywhere.
        for (c in out) assertEquals("alpha preserved", 0xFF, alphaOf(c))
    }

    // ── 8. Chunk-seam position integrity ────────────────────────────────

    @Test
    fun `vignette - output is mirror-symmetric in x and y across chunk seams`() {
        // The vignette is a pure function of squared distance from center, so
        // for any mirror pair (x, y) <-> (w-x, y) / (x, h-y) / (w-x, h-y)
        // the output must be byte-identical. The parallel passes split pixels
        // into chunks that start mid-row, so a chunk that mis-tracks (x, y)
        // breaks symmetry at its seam. Covers even and odd dimensions (the
        // center lands on .0 or .5 either way).
        for ((w, h) in listOf(38 to 26, 37 to 15)) {
            val src = solid(w, h, 0xFF808080.toInt())
            val out = pixelsOf(
                runBlocking {
                    bitmapOf(w, h, src).applyRetroFilter(RetroRenderParams(vignette = 1f))
                }
            )
            for (y in 1 until h) for (x in 1 until w) {
                val px = out[y * w + x]
                assertEquals("x-mirror at ($x,$y), ${w}x$h", px, out[y * w + (w - x)])
                assertEquals("y-mirror at ($x,$y), ${w}x$h", px, out[(h - y) * w + x])
                assertEquals("xy-mirror at ($x,$y), ${w}x$h", px, out[(h - y) * w + (w - x)])
            }
            // The vignette must actually bite on the boundary pixels.
            val corner = out[0] and 0xFF
            val center = out[(h / 2) * w + w / 2] and 0xFF
            assertTrue("corner darkened ($corner < $center) for ${w}x$h", corner < center)
        }
    }

    // ── 9. Degenerate dimensions ─────────────────────────────────────────

    @Test
    fun `soft focus - 1-wide column diffuses an impulse with exact clamped weights`() {
        // Width 1 wraps x on every single pixel: the strongest exercise of the
        // incremental x/y advance. Clamping folds the 3x3 window into three
        // copies of the single column, so a lone impulse at (0,3) spreads
        // 3*255/9 = 85 to rows 2..4 and nothing to rows 1 and 5.
        val w = 1
        val h = 9
        val src = solid(w, h, 0xFF000000.toInt()).also { it[3] = 0xFFFFFFFF.toInt() }
        val out = pixelsOf(
            runBlocking {
                bitmapOf(w, h, src).applyRetroFilter(
                    RetroRenderParams(softFocus = 1f, vignette = 0f)
                )
            }
        )
        val expected = intArrayOf(0, 0, 85, 85, 85, 0, 0, 0, 0)
        for (y in 0 until h) {
            assertEquals("row $y of 1-wide column", expected[y], out[y] and 0xFF)
            assertEquals("alpha at row $y", 0xFF, alphaOf(out[y]))
        }
    }

    @Test
    fun `1x1 bitmap survives the full pipeline with alpha preserved`() {
        val out = runBlocking {
            bitmapOf(1, 1, intArrayOf(0xFF336699.toInt())).applyRetroFilter(
                RetroRenderParams(
                    softFocus = 1f,
                    grainStrength = 0.5f,
                    grainChroma = 0.3f,
                    dust = 0.3f,
                    scratch = 0.2f,
                    lightLeak = 0.2f,
                    vignette = 1f
                )
            )
        }
        assertEquals("alpha preserved on 1x1", 0xFF, alphaOf(out.getPixel(0, 0)))
    }

    // ── 10. Concurrent runs / shared buffer state ─────────────────────────

    @Test
    fun `concurrent runs - parallel captures match their sequential results byte-for-byte`() {
        // The chunked passes share thread-local pixel buffers guarded only by
        // an in-use flag; two overlapping captures must not corrupt either.
        val w = 40
        val h = 30
        val src = IntArray(w * h) { i ->
            (0xFF shl 24) or ((i * 7) and 0xFF shl 16) or ((i * 13) and 0xFF shl 8) or ((i * 29) and 0xFF)
        }
        val paramsA = RetroRenderParams(grainStrength = 0.4f, grainChroma = 0.2f, softFocus = 0.5f, vignette = 1f)
        val paramsB = RetroRenderParams(exposure = -0.5f, fade = 0.15f, dust = 0.25f, vignette = 1f)
        val seqA = pixelsOf(runBlocking { bitmapOf(w, h, src).applyRetroFilter(paramsA) })
        val seqB = pixelsOf(runBlocking { bitmapOf(w, h, src).applyRetroFilter(paramsB) })
        val (parA, parB) = runBlocking {
            coroutineScope {
                val a = async(Dispatchers.Default) {
                    pixelsOf(bitmapOf(w, h, src).applyRetroFilter(paramsA))
                }
                val b = async(Dispatchers.Default) {
                    pixelsOf(bitmapOf(w, h, src).applyRetroFilter(paramsB))
                }
                a.await() to b.await()
            }
        }
        assertArrayEquals("concurrent A != sequential A", seqA, parA)
        assertArrayEquals("concurrent B != sequential B", seqB, parB)
    }

    // ── 11. Identity LUT ─────────────────────────────────────────────────

    @Test
    fun `identity LUT - round-trips every input pixel unchanged`() {
        val lut = identityLut()
        val w = 24
        val h = 16
        val src = IntArray(w * h) { i ->
            (0xFF shl 24) or ((i * 7) and 0xFF shl 16) or ((i * 3) and 0xFF shl 8) or ((i * 11) and 0xFF)
        }
        val before = src.copyOf()
        val out = runBlocking {
            bitmapOf(w, h, src).applyRetroFilter(RetroRenderParams(vignette = 0f), lut = lut)
        }
        val pixels = pixelsOf(out)
        for (i in pixels.indices) {
            assertEquals("pixel $i must round-trip through the identity LUT", before[i], pixels[i])
        }
    }
}
