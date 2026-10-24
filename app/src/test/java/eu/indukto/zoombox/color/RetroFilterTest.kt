package eu.indukto.zoombox.color

import android.graphics.Bitmap
import eu.indukto.zoombox.FilmPreset
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

    // ── 12b. Procedural fBm grain (size, clumping) ──────────────────

    @Test
    fun `grain - a coarser grainSize clumps neighbours together`() {
        // At a coarse cell (grainSize 1.0 on a >=181px frame) the fBm base
        // octave spans multiple pixels, so adjacent pixels correlate — the
        // smooth clumps that distinguish value noise from per-pixel white.
        // At the fine end the cell clamps to ~1px, which is per-pixel white.
        // So the coarse field must vary LESS pixel-to-pixel than the fine
        // one; a relative check avoids depending on the exact amplitude.
        val w = 256
        val h = 256
        val src = solid(w, h, 0xFF808080.toInt())
        fun meanNeighborDiff(grainSize: Float): Float {
            val params = RetroRenderParams(grainStrength = 0.5f, grainChroma = 0f, vignette = 0f, grainSize = grainSize)
            val out = pixelsOf(runBlocking { bitmapOf(w, h, src).applyRetroFilter(params, grainSeed = 5) })
            var sum = 0L
            var count = 0
            for (y in 0 until h) for (x in 0 until w - 1) {
                val a = out[y * w + x] and 0xFF
                val b = out[y * w + x + 1] and 0xFF
                sum += kotlin.math.abs(a - b)
                count++
            }
            return sum / count.toFloat()
        }
        val fine = meanNeighborDiff(0f)
        val coarse = meanNeighborDiff(1f)
        assertTrue("coarse grain must clump (fine $fine, coarse $coarse)", coarse < fine)
    }

    @Test
    fun `grain - grainSize changes the rendered field`() {
        // Two cell sizes over the same frame must produce different speckle;
        // locks the grainSize knob is actually wired into the field.
        val w = 256
        val h = 256
        val src = solid(w, h, 0xFF808080.toInt())
        val fine = RetroRenderParams(grainStrength = 0.5f, grainChroma = 0f, vignette = 0f, grainSize = 0.0f)
        val coarse = RetroRenderParams(grainStrength = 0.5f, grainChroma = 0f, vignette = 0f, grainSize = 1.0f)
        val a = pixelsOf(runBlocking { bitmapOf(w, h, src).applyRetroFilter(fine, grainSeed = 9) })
        val b = pixelsOf(runBlocking { bitmapOf(w, h, src).applyRetroFilter(coarse, grainSeed = 9) })
        assertFalse("different grainSize must move the cell", a.contentEquals(b))
    }

    // ── 11b. Spatial halation ────────────────────────────────────────────

    @Test
    fun `halation - bright regions bleed warm light into dark neighbours`() {
        val w = 64
        val h = 64
        val src = solid(w, h, 0xFF000000.toInt())
        // 16x16 white block on cell boundaries (SCALE = 4), so the quarter-res
        // gather sees an unambiguous bright step to spread from.
        for (y in 24 until 40) for (x in 24 until 40) src[y * w + x] = 0xFFFFFFFF.toInt()

        val plain = runBlocking {
            bitmapOf(w, h, src).applyRetroFilter(RetroRenderParams(vignette = 0f))
        }
        val haloed = runBlocking {
            bitmapOf(w, h, src).applyRetroFilter(
                RetroRenderParams(vignette = 0f, halationStrength = 1f, halationRadius = 24f)
            )
        }

        // Two pixels outside the block: nothing but halation reads neighbours,
        // so light here can only have come from the blur.
        val plainPixel = plain.getPixel(22, 32)
        val haloPixel = haloed.getPixel(22, 32)
        assertEquals(0xFF000000.toInt(), plainPixel)
        val r = (haloPixel ushr 16) and 0xFF
        val g = (haloPixel ushr 8) and 0xFF
        val b = haloPixel and 0xFF
        assertTrue("the glow must reach past the highlight's edge", r > 10)
        assertTrue("halation is warm: red leads green ($r/$g)", r > g)
        assertTrue("halation is warm: green leads blue ($g/$b)", g > b)
    }

    @Test
    fun `halation - every back-end carries the stage and thresholds before blurring`() {
        // The preview and the GPU still share this source verbatim; the CPU
        // composite is matched to it, and the gather's threshold-before-blur
        // order is what makes the glow bleed outward instead of only
        // brightening pixels that were already bright.
        assertTrue(LutPreviewRenderer.FRAG_SHADER.contains("uHalationStrength"))
        assertTrue(LutPreviewRenderer.FRAG_SHADER_NO_3D.contains("uHalationStrength"))
        assertTrue(
            LutPreviewRenderer.HALATION_FRAGMENT_SHADER.contains("smoothstep(0.45, 0.95, luma)")
        )
    }

    @Test
    fun `halation - zero strength leaves the image byte-identical`() {
        val w = 48
        val h = 48
        val src = solid(w, h, 0xFF804020.toInt())

        val off = pixelsOf(runBlocking { bitmapOf(w, h, src).applyRetroFilter(RetroRenderParams()) })
        val zero = pixelsOf(
            runBlocking {
                bitmapOf(w, h, src).applyRetroFilter(
                    RetroRenderParams(halationStrength = 0f, halationRadius = 24f)
                )
            }
        )

        assertArrayEquals(off, zero)
    }

    @Test
    fun `halation - a dark frame stays dark`() {
        val w = 32
        val h = 32
        val src = solid(w, h, 0xFF101010.toInt())

        val out = pixelsOf(
            runBlocking {
                bitmapOf(w, h, src).applyRetroFilter(
                    RetroRenderParams(vignette = 0f, halationStrength = 1f, halationRadius = 24f)
                )
            }
        )

        // Below the highlight knee there is nothing to scatter, so the stage
        // must not lift shadows into a grey wash.
        assertArrayEquals(src, out)
    }

    // ── 12. Organic grain (white + clump, seeded) ───────────────────────

    @Test
    fun `grain - different seeds produce different output`() {
        val w = 48
        val h = 32
        val src = solid(w, h, 0xFF808080.toInt())
        val params = RetroRenderParams(grainStrength = 0.5f, grainChroma = 0.3f, vignette = 0f)
        val out1 = pixelsOf(runBlocking { bitmapOf(w, h, src).applyRetroFilter(params, grainSeed = 1) })
        val out2 = pixelsOf(runBlocking { bitmapOf(w, h, src).applyRetroFilter(params, grainSeed = 2) })
        assertFalse("different seeds must re-key the grain field", out1.contentEquals(out2))
    }

    @Test
    fun `grain - same seed is deterministic`() {
        val w = 48
        val h = 32
        val src = solid(w, h, 0xFF808080.toInt())
        val params = RetroRenderParams(grainStrength = 0.5f, grainChroma = 0.3f, vignette = 0f)
        val out1 = pixelsOf(runBlocking { bitmapOf(w, h, src).applyRetroFilter(params, grainSeed = 7) })
        val out2 = pixelsOf(runBlocking { bitmapOf(w, h, src).applyRetroFilter(params, grainSeed = 7) })
        assertArrayEquals(out1, out2)
    }

    @Test
    fun `grain - neighboring pixels are decorrelated, not smooth blotches`() {
        // The old value-noise-only field interpolated smoothly, so adjacent
        // pixels barely differed. Per-pixel white grain must show strong
        // neighbor-to-neighbor variation on a flat field.
        val w = 64
        val h = 64
        val src = solid(w, h, 0xFF808080.toInt())
        val out = pixelsOf(
            runBlocking {
                bitmapOf(w, h, src).applyRetroFilter(
                    RetroRenderParams(grainStrength = 0.5f, grainChroma = 0f, vignette = 0f)
                )
            }
        )
        var sum = 0L
        var count = 0
        for (y in 0 until h) for (x in 0 until w - 1) {
            val a = out[y * w + x] and 0xFF
            val b = out[y * w + x + 1] and 0xFF
            sum += kotlin.math.abs(a - b)
            count++
        }
        val meanNeighborDiff = sum / count.toFloat()
        assertTrue("grain must vary per pixel (mean neighbor diff $meanNeighborDiff > 2.0)", meanNeighborDiff > 2.0f)
    }

    @Test
    fun `grain - distant tiles do not repeat the same pattern`() {
        // A repeating lattice would make two far-apart tiles near-identical.
        // White grain must make them differ well above rounding noise.
        val w = 128
        val h = 32
        val src = solid(w, h, 0xFF808080.toInt())
        val out = pixelsOf(
            runBlocking {
                bitmapOf(w, h, src).applyRetroFilter(
                    RetroRenderParams(grainStrength = 0.5f, grainChroma = 0f, vignette = 0f)
                )
            }
        )
        var sum = 0L
        for (y in 0 until h) for (x in 0 until 32) {
            val a = out[y * w + x] and 0xFF
            val b = out[y * w + x + 64] and 0xFF
            sum += kotlin.math.abs(a - b)
        }
        val meanTileDiff = sum / (32f * h)
        assertTrue("distant tiles must differ (mean diff $meanTileDiff > 1.5)", meanTileDiff > 1.5f)
    }

    @Test
    fun `grain - GPU shaders carry the seeded fBm stage`() {
        for (shader in listOf(LutPreviewRenderer.FRAG_SHADER, LutPreviewRenderer.FRAG_SHADER_NO_3D)) {
            assertTrue("shader must declare uGrainSeed", shader.contains("uGrainSeed"))
            assertTrue("shader must declare uGrainCell", shader.contains("uGrainCell"))
            // The grain field is a 3-octave fBm value-noise stack, salted as
            // 4*seed+channel; the cell hash must wrap into mediump-clean range.
            assertTrue("shader must build the fBm field", shader.contains("fbmGrain(grainPos"))
            assertTrue("shader must derive grain pos from a highp varying", shader.contains("vTexCoord * uViewSize"))
            assertTrue("shader must declare the lattice-cell hash", shader.contains("float cellHash(vec2"))
            assertTrue("shader must wrap the seed into float-safe range", shader.contains("mod(seed, 256.0)"))
            assertTrue("shader must hash in mediump-clean range", shader.contains("mod(i + s, 256.0)"))
            // Regression for the visible large-scale blocking: sub-pixel
            // octaves must be faded out near the pixel Nyquist limit, and the
            // grain math must run in highp so gl_FragCoord keeps its fraction.
            assertTrue("shader must fade aliasing octaves", shader.contains("octaveFade"))
            assertTrue("shader must ask for fragment highp", shader.contains("GL_FRAGMENT_PRECISION_HIGH"))
            // Regression for the visible grid: the hash must not take the old
            // single-tap white path or an unseeded interpolated octave name.
            assertFalse("shader must not use the old white hash name", shader.contains("grainHash("))
        }
    }

    @Test
    fun `grain - even huge seeds stay per-pixel white on the CPU`() {
        // Integer hash math is magnitude-immune; only the GPU float path
        // needs the MAX_GRAIN_SEED bound. Locks that a wild seed can never
        // collapse the CPU field into blocks either.
        val w = 64
        val h = 64
        val src = solid(w, h, 0xFF808080.toInt())
        val out = pixelsOf(
            runBlocking {
                bitmapOf(w, h, src).applyRetroFilter(
                    RetroRenderParams(grainStrength = 0.5f, grainChroma = 0f, vignette = 0f),
                    grainSeed = Int.MAX_VALUE
                )
            }
        )
        var sum = 0L
        var count = 0
        for (y in 0 until h) for (x in 0 until w - 1) {
            val a = out[y * w + x] and 0xFF
            val b = out[y * w + x + 1] and 0xFF
            sum += kotlin.math.abs(a - b)
            count++
        }
        assertTrue("huge seed must stay white (diff ${sum / count.toFloat()} > 2.0)", sum / count.toFloat() > 2.0f)
    }

    @Test
    fun `grain - capture seed bound fits float precision`() {
        assertTrue("MAX_GRAIN_SEED must stay exactly representable", MAX_GRAIN_SEED <= 256)
    }

    @Test
    fun `grain - no lattice structure at any short lag`() {
        // Regression for the visible grid: the old interpolated octave
        // correlated neighbors and terraced on its lattice. Pure white
        // grain must be uncorrelated at every lag 1..8 in x and y.
        val w = 128
        val h = 128
        val src = solid(w, h, 0xFF808080.toInt())
        val out = pixelsOf(
            runBlocking {
                bitmapOf(w, h, src).applyRetroFilter(
                    RetroRenderParams(grainStrength = 0.5f, grainChroma = 0f, vignette = 0f)
                )
            }
        )
        val dev = DoubleArray(w * h) { (out[it] and 0xFF) - 128.0 }
        val mean = dev.average()
        val variance = dev.sumOf { (it - mean) * (it - mean) } / dev.size
        assertTrue("grain must actually vary", variance > 1.0)
        fun corr(dx: Int, dy: Int): Double {
            var s = 0.0
            var c = 0
            for (y in 0 until h - dy) for (x in 0 until w - dx) {
                s += (dev[y * w + x] - mean) * (dev[(y + dy) * w + x + dx] - mean)
                c++
            }
            return s / c / variance
        }
        for (lag in 1..8) {
            assertTrue("lag-$lag x corr ${corr(lag, 0)} must be ~0", kotlin.math.abs(corr(lag, 0)) < 0.12)
            assertTrue("lag-$lag y corr ${corr(0, lag)} must be ~0", kotlin.math.abs(corr(0, lag)) < 0.12)
        }
    }
}

