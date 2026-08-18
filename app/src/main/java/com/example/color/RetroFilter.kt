package com.example.color

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * CPU film-processing pipeline: applies a [RetroRenderParams] snapshot (plus
 * an optional `.cube` [CubeLut]) to a [Bitmap].
 *
 * This is the capture-time counterpart to the live GL preview
 * ([LutPreviewRenderer]) and the GPU still processor
 * ([GpuCaptureProcessor]); all three consume the same parameter snapshot so
 * the saved JPEG can never drift from what the viewfinder showed.
 *
 * Extracted from `CameraViewModel` so the riskiest pixel code is a standalone,
 * unit-testable module. It is intentionally pure bitmap math with no ViewModel
 * state: the only shared mutable state is the [filterBuffers] thread-local
 * pixel-buffer cache, which avoids re-allocating multi-megapixel IntArrays on
 * every capture.
 */

// Avoid retaining unusually large buffers forever on a pooled dispatcher
// thread. This is large enough for typical full-resolution phone captures
// while bounding memory after an outlier image.
private const val MAX_RETAINED_FILTER_PIXELS = 16_000_000

/** Reusable ARGB pixel buffers for one worker thread. */
private class FilterBuffers {
    var pixels = IntArray(0)
    var rowDistanceSquared = FloatArray(0)

    /**
     * Pre-capture snapshot of [pixels] used by the optional soft-focus
     * pre-pass so neighbor reads see unmodified input (in-place mutation +
     * parallel chunks otherwise corrupts a same-frame blur). Lazily grown to
     * match the largest [pixelCount] we have ever processed; cuts GC pressure
     * on repeated captures with the same resolution. Null when soft-focus is
     * not active for the current preset — in which case the per-pixel loop's
     * blur read short-circuits.
     */
    var softFocusSnapshot: IntArray? = null
    var inUse = false
}

private val filterBuffers = ThreadLocal<FilterBuffers>()

/**
 * Applies the shared retro render chain (WB, exposure, fringing, film curve,
 * bloom, roll-off, vignette, contrast/sat, split toning, fade, grain, LUT,
 * milky haze, dust/scratches/light-leak overlays) to this bitmap and returns
 * the graded result. Returns the receiver unchanged when it is empty.
 */
suspend fun Bitmap.applyRetroFilter(
    params: RetroRenderParams,
    lut: CubeLut? = null
): Bitmap {
    // Destructure the shared snapshot into locals so the existing
    // per-pixel body stays untouched (and stays in the same order as the
    // GL shader's stages).
    val tempVal = params.temperature
    val tintVal = params.tint
    val expVal = params.exposure
    val grainStrength = params.grainStrength
    val grainChroma = params.grainChroma
    val filmCurve = params.filmCurve
    val contrast = params.contrast
    val saturation = params.saturation
    val bloomStrength = params.bloom
    val shadowTintStrength = params.shadowTintStrength
    val shadowTintR = params.shadowTintR
    val shadowTintG = params.shadowTintG
    val shadowTintB = params.shadowTintB
    val highlightTintStrength = params.highlightTintStrength
    val highlightTintR = params.highlightTintR
    val highlightTintG = params.highlightTintG
    val highlightTintB = params.highlightTintB
    val fringing = params.fringing
    val softFocus = params.softFocus
    val milkyMix = params.milkyMix
    val milkyTintR = params.milkyTintR
    val milkyTintG = params.milkyTintG
    val milkyTintB = params.milkyTintB
    val highlightRolloff = params.highlightRolloff
    val fade = params.fade
    val vignette = params.vignette
    val dust = params.dust
    val scratch = params.scratch
    val lightLeak = params.lightLeak

    val w = this.width
    val h = this.height
    if (w <= 0 || h <= 0) return this

    val target = if (this.isMutable) this else this.copy(this.config ?: Bitmap.Config.ARGB_8888, true)

    val hasExp = expVal != 0f
    val hasTemp = tempVal != 0f
    val hasTint = tintVal != 0f

    // GPU-matched WB formulas (matching FRAG_SHADER in LutPreviewRenderer):
    //   temp:  c.r += temp*0.04; c.b -= temp*0.04
    //   tint:  c.g -= tint*0.04; c.r += tint*0.02; c.b += tint*0.02
    // Precompute combined per-channel deltas in normalised [0,1] space
    // so the per-pixel loop only does float add + multiply.
    val wbActive = hasTemp || hasTint
    val wbDeltaR = tempVal * 0.04f + tintVal * 0.02f
    val wbDeltaG = -tintVal * 0.04f
    val wbDeltaB = -tempVal * 0.04f + tintVal * 0.02f

    val expScale = if (hasExp) java.lang.Math.pow(2.0, expVal * 0.4).toFloat() else 1f

    // ── Precompute bloom look-up table ──
    // For the CPU path we use a simplified bloom that works on the
    // quantized 255 values: a luminance-based warm glow added at the end.
    val bloomActive = bloomStrength > 0f

    // ── Vignette precomputations ──
    val cx = w * 0.5f
    val cy = h * 0.5f
    val maxRadius = kotlin.math.max(w, h).toFloat() * 0.72f
    val maxRadiusInv = 1f / maxRadius
    val vigInner = 0.55f
    val vigRange = 0.45f
    // Keep the film-frame falloff subtle so corners stay readable.
    // This mirrors the live GLSurface shader's reduced vignette strength.
    val vigFadeMax = 95f / 255f
    val cornerRgb = 8f
    val innerRadiusSq = (vigInner * maxRadius) * (vigInner * maxRadius)

    val cachedBuffers = filterBuffers.get() ?: FilterBuffers().also { filterBuffers.set(it) }
    // A coroutine can suspend while its child chunks run. If another
    // capture resumes on this same dispatcher thread in the meantime,
    // give it private arrays rather than corrupting the first result.
    val buffers = if (cachedBuffers.inUse) FilterBuffers() else cachedBuffers
    buffers.inUse = true

    try {
        val rowDy2 = buffers.rowDistanceSquared.let { buffer ->
            if (buffer.size < h) {
                FloatArray(h).also { buffers.rowDistanceSquared = it }
            } else {
                buffer
            }
        }
        for (y in 0 until h) {
            val dy = y - cy
            rowDy2[y] = dy * dy
        }

        val pixelCount = w * h
        val pixels = buffers.pixels.let { buffer ->
            if (buffer.size < pixelCount) {
                IntArray(pixelCount).also { buffers.pixels = it }
            } else {
                buffer
            }
        }
        target.getPixels(pixels, 0, w, 0, 0, w, h)

        // Precompute LUT params so each parallel chunk can do its
        // trilinear blend inline (one pixel pass total) instead of relying
        // on the previous separate LutColorFilter.applyInPlace call. That
        // call did a SECOND full-bitmap getPixels + per-pixel trilinear
        // blend + setPixels which was responsible for ~3–4 s of capture
        // latency on full sensor resolution JPEGs (single-threaded, even when the retro
        // chunks above finished quickly on quad-core devices).
        val lutActive = lut != null
        val lutData: FloatArray? = lut?.data
        val lutN: Int = lut?.size ?: 0
        val lutMaxIdx: Int = if (lutN > 1) lutN - 1 else 0
        val lutMaxIdxF: Float = lutMaxIdx.toFloat()
        val lutScaleF: Float = if (lutN > 1) (1f / 255f) * (lutN - 1).toFloat() else 0f
        val lutSz: Int = if (lutN > 1) lutN * lutN else 0

        // ── Precompute Film S-Curve table ──
        // Before this table existed, every per-pixel film-curve call ran
        // StrictMath.exp twice with no hardware acceleration (~50–100 ns
        // each). At three channels per pixel that was 6 exp() per pixel —
        // ~3 s of post-processing on a 12 MP bitmap. Quantising the input
        // to 256 buckets and keying a FloatArray lookup replaces the
        // per-pixel transcendental with one array read. The quantisation
        // step (1/255 ≈ 0.0039 in the unit interval) is well below one
        // 8-bit LSB after the downstream `(value * 255f + 0.5f).toInt()`
        // rounding, so JPEG output bytes are identical for every
        // practical input.
        val filmCurveLut: FloatArray? = if (filmCurve > 0f) {
            FloatArray(256).also { tbl ->
                val s = filmCurve * 0.5f
                for (i in 0..255) {
                    val x = i / 255f
                    val toe = (1f - kotlin.math.exp(-x * 5.0f)) * s * 0.12f
                    val shoulder = (1f - kotlin.math.exp(-(1f - x) * 5.0f)) * s * 0.20f
                    var r = x + toe - shoulder
                    r += (x - 0.5f) * s * 0.15f
                    tbl[i] = r.coerceIn(0f, 1f)
                }
            }
        } else null

        val numChunks = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val total = w * h
        val chunkSize = (total + numChunks - 1) / numChunks

        // ── Soft-focus pre-pass (dreamcore) ──
        // Reads a frozen copy of the input bitmap (snapshot), averages a
        // 3x3 box neighbourhood for each pixel, and writes the blurred
        // mix BACK INTO `pixels` (in-place). After this pass the rest of
        // the pipeline sees blurred pixels as its input — the WB, exposure,
        // S-curve and toning stages then operate on the soft signal so
        // the entire downstream image has the dreamy, smoothed look.
        //
        // Why a snapshot rather than reading directly from `pixels`:
        // the parallel chunks below all access the same `pixels[]`
        // array, and a blur sample at index p+X reads 8 neighbours that
        // may already have been rewritten to the post-blur value by
        // another chunk or by an earlier sweep through this chunk. A
        // pre-capture snapshot freezes neighbours at their input values,
        // so each pixel's blur sees a true box-3x3 window of the
        // pre-blur bitmap. Memory cost is one extra IntArray (≈12 MB
        // for a 3 MP capture); only allocated when softFocus > 0 so
        // other presets pay nothing.
        val softFocusActive = softFocus > 0f
        if (softFocusActive) {
            val snap = buffers.softFocusSnapshot?.let { if (it.size >= pixelCount) it else null }
                ?: IntArray(pixelCount).also { buffers.softFocusSnapshot = it }
            System.arraycopy(pixels, 0, snap, 0, pixelCount)

            val blursChunks = numChunks
            val blurChunkSize = (total + blursChunks - 1) / blursChunks
            coroutineScope {
                (0 until blursChunks).map { chunk ->
                    async(Dispatchers.Default) {
                        val a = chunk * blurChunkSize
                        val b = (a + blurChunkSize).coerceAtMost(total)
                        var p = a
                        while (p < b) {
                            val x = p % w
                            val y = p / w
                            var sumR = 0
                            var sumG = 0
                            var sumB = 0
                            // 3x3 box read with edge-clamping so the
                            // blur doesn't produce darker outer pixels
                            // (the blur kernel spreads the missing
                            // contributions into the boundary).
                            val yTop = if (y > 0) y - 1 else 0
                            val yBot = if (y < h - 1) y + 1 else h - 1
                            val xLft = if (x > 0) x - 1 else 0
                            val xRgt = if (x < w - 1) x + 1 else w - 1
                            val rowT = yTop * w
                            val rowM = y * w
                            val rowB = yBot * w
                            val s_tl = snap[rowT + xLft]
                            val s_tc = snap[rowT + x]
                            val s_tr = snap[rowT + xRgt]
                            val s_ml = snap[rowM + xLft]
                            val s_mc = snap[rowM + x]
                            val s_mr = snap[rowM + xRgt]
                            val s_bl = snap[rowB + xLft]
                            val s_bc = snap[rowB + x]
                            val s_br = snap[rowB + xRgt]
                            sumR = (s_tl ushr 16 and 0xFF) +
                                   (s_tc ushr 16 and 0xFF) +
                                   (s_tr ushr 16 and 0xFF) +
                                   (s_ml ushr 16 and 0xFF) +
                                   (s_mc ushr 16 and 0xFF) +
                                   (s_mr ushr 16 and 0xFF) +
                                   (s_bl ushr 16 and 0xFF) +
                                   (s_bc ushr 16 and 0xFF) +
                                   (s_br ushr 16 and 0xFF)
                            sumG = (s_tl ushr 8 and 0xFF) +
                                   (s_tc ushr 8 and 0xFF) +
                                   (s_tr ushr 8 and 0xFF) +
                                   (s_ml ushr 8 and 0xFF) +
                                   (s_mc ushr 8 and 0xFF) +
                                   (s_mr ushr 8 and 0xFF) +
                                   (s_bl ushr 8 and 0xFF) +
                                   (s_bc ushr 8 and 0xFF) +
                                   (s_br ushr 8 and 0xFF)
                            sumB = (s_tl and 0xFF) +
                                   (s_tc and 0xFF) +
                                   (s_tr and 0xFF) +
                                   (s_ml and 0xFF) +
                                   (s_mc and 0xFF) +
                                   (s_mr and 0xFF) +
                                   (s_bl and 0xFF) +
                                   (s_bc and 0xFF) +
                                   (s_br and 0xFF)
                            val bR = sumR / 9
                            val bG = sumG / 9
                            val bB = sumB / 9

                            val orig = pixels[p]
                            val alphaMask = orig and 0xFF000000.toInt()
                            val origR = (orig ushr 16) and 0xFF
                            val origG = (orig ushr 8) and 0xFF
                            val origB = orig and 0xFF
                            // mix(original, blurred, softFocus) — preserves
                            // original sharpness when softFocus approaches 0
                            // and is a pure 3x3 average when softFocus=1.
                            val mixR = (origR + (bR - origR) * softFocus).toInt().coerceIn(0, 255)
                            val mixG = (origG + (bG - origG) * softFocus).toInt().coerceIn(0, 255)
                            val mixB = (origB + (bB - origB) * softFocus).toInt().coerceIn(0, 255)
                            pixels[p] = alphaMask or (mixR shl 16) or (mixG shl 8) or mixB
                            p++
                        }
                    }
                }
            }
        }

        // Precompute the milky haze parameters (constants for this run,
        // computed once instead of per-pixel). Inert unless the preset
        // asked for any milky amount.
        val milkyActive = milkyMix > 0f
        val milkyStrengthShade = milkyMix * 1.2f  // weight on shadow side
        val milkyStrengthBase = milkyMix * 0.25f // baseline wash even at luma=1
        val milkyR = milkyTintR.coerceIn(0f, 1f)
        val milkyG = milkyTintG.coerceIn(0f, 1f)
        val milkyB = milkyTintB.coerceIn(0f, 1f)

        coroutineScope {
            (0 until numChunks).map { chunk ->
                val start = chunk * chunkSize
                val end = (start + chunkSize).coerceAtMost(total)
                async(Dispatchers.Default) {
                    var p = start
                    while (p < end) {
                        val x = p % w
                        val y = p / w
                        val dx = x - cx

                        val c = pixels[p]
                        val a = (c ushr 24) and 0xFF
                        var r8 = (c ushr 16) and 0xFF
                        var g8 = (c ushr 8) and 0xFF
                        var b8 = c and 0xFF

                        // Convert to float for processing
                        var rf = r8 / 255f
                        var gf = g8 / 255f
                        var bf = b8 / 255f

                        // ── 1. White Balance ──
                        if (wbActive) {
                            rf += wbDeltaR
                            gf += wbDeltaG
                            bf += wbDeltaB
                            rf = rf.coerceIn(0f, 1f)
                            gf = gf.coerceIn(0f, 1f)
                            bf = bf.coerceIn(0f, 1f)
                        }

                        // ── 2. Exposure ──
                        if (hasExp) {
                            rf = (rf * expScale).coerceIn(0f, 1f)
                            gf = (gf * expScale).coerceIn(0f, 1f)
                            bf = (bf * expScale).coerceIn(0f, 1f)
                        }

                        // ── 3. Chromatic Fringing ──
                        // Note: On CPU we simulate fringing by skewing R vs B
                        // relative to G on bright edges. A simplified approach:
                        // shift R and B oppositely based on horizontal gradient
                        // approximation. For the CPU path we keep it lightweight:
                        // we sample neighboring pixels through the array.
                        // Actually, true fringing would need neighbor access which
                        // is expensive in a per-pixel parallel loop. We approximate
                        // it as a per-pixel color misregistration offset based on
                        // local brightness gradient. For simplicity and performance,
                        // we apply a slight R/B separation proportional to
                        // (rf - bf) so that high-frequency color edges get a subtle
                        // split — a cheap stand-in for optical misregistration.
                        if (fringing > 0f) {
                            val rOffset = (rf - bf) * fringing * 0.5f
                            val bOffset = (bf - rf) * fringing * 0.5f
                            rf = (rf + rOffset).coerceIn(0f, 1f)
                            bf = (bf + bOffset).coerceIn(0f, 1f)
                        }

                        // ── 4. Film S-Curve ──
                        // Replaces the hard clip with a smooth shoulder/toe.
                        // Math is precomputed once per capture in
                        // `filmCurveLut` (above); the per-pixel work is now
                        // one FloatArray lookup per channel instead of 2
                        // StrictMath.exp + 4 multiply+adds each.
                        if (filmCurveLut != null) {
                            rf = filmCurveLut[(rf * 255f + 0.5f).toInt()]
                            gf = filmCurveLut[(gf * 255f + 0.5f).toInt()]
                            bf = filmCurveLut[(bf * 255f + 0.5f).toInt()]
                        }

                        // ── 5. Halation / Bloom (luma-based additive glow) ──
                        // Simplified: compute luma, extract brights, tint warm, add.
                        // Uses the same 3rd-order smoothstep as the GL shader so
                        // the CPU and GPU bloom match instead of diverging on a
                        // linear vs. smooth ramp.
                        if (bloomActive) {
                            val luma = rf * 0.299f + gf * 0.587f + bf * 0.114f
                            val brightMask = smoothstep3(0.3f, 0.8f, luma)
                            val warmGlowR = brightMask * bloomStrength * luma * 1.0f
                            val warmGlowG = brightMask * bloomStrength * luma * 0.7f
                            val warmGlowB = brightMask * bloomStrength * luma * 0.3f
                            rf = (rf + warmGlowR).coerceIn(0f, 1f)
                            gf = (gf + warmGlowG).coerceIn(0f, 1f)
                            bf = (bf + warmGlowB).coerceIn(0f, 1f)
                        }

                        // ── 5.5. Highlight roll-off (filmic shoulder) ──
                        // Mirrors the GL shader's rolloffChannel() exactly:
                        // identity below the 0.7 knee, soft shoulder above.
                        if (highlightRolloff > 0f) {
                            if (rf > 0.7f) {
                                val t = (rf - 0.7f) / 0.3f
                                rf = 0.7f + (t - highlightRolloff * t * (1f - t)) * 0.3f
                            }
                            if (gf > 0.7f) {
                                val t = (gf - 0.7f) / 0.3f
                                gf = 0.7f + (t - highlightRolloff * t * (1f - t)) * 0.3f
                            }
                            if (bf > 0.7f) {
                                val t = (bf - 0.7f) / 0.3f
                                bf = 0.7f + (t - highlightRolloff * t * (1f - t)) * 0.3f
                            }
                        }

                        // ── 6. Vignette ──
                        val distSq = dx * dx + rowDy2[y]
                        if (distSq > innerRadiusSq) {
                            val dist = kotlin.math.sqrt(distSq)
                            val radialT = ((dist * maxRadiusInv - vigInner) / vigRange) * vignette
                            if (radialT > 0f) {
                                val clampedT = if (radialT > 1f) 1f else radialT
                                val shaderA = clampedT * vigFadeMax
                                val shaderC = clampedT * cornerRgb
                                val invA = 1f - shaderA
                                val cornerContrib = shaderC * shaderA
                                r8 = (rf * 255f).toInt()
                                g8 = (gf * 255f).toInt()
                                b8 = (bf * 255f).toInt()
                                r8 = (r8 * invA + cornerContrib).toInt().coerceIn(0, 255)
                                g8 = (g8 * invA + cornerContrib).toInt().coerceIn(0, 255)
                                b8 = (b8 * invA + cornerContrib).toInt().coerceIn(0, 255)
                                rf = r8 / 255f
                                gf = g8 / 255f
                                bf = b8 / 255f
                            }
                        }

                        // ── 7. Contrast & Saturation (applied post-LUT) ──
                        // These are normally applied after the LUT. Since the LUT
                        // is applied later as a separate step, we do contrast/sat
                        // here in the pixel loop but they conceptually come after
                        // the LUT in the signal chain. To keep the effect, we
                        // apply them now and the LUT step will map the result.
                        if (contrast != 1.0f || saturation != 1.0f) {
                            val luma = rf * 0.299f + gf * 0.587f + bf * 0.114f
                            // Contrast — pivot around 0.5
                            if (contrast != 1.0f) {
                                rf = ((rf - 0.5f) * contrast + 0.5f).coerceIn(0f, 1f)
                                gf = ((gf - 0.5f) * contrast + 0.5f).coerceIn(0f, 1f)
                                bf = ((bf - 0.5f) * contrast + 0.5f).coerceIn(0f, 1f)
                            }
                            // Saturation — blend toward luma
                            if (saturation != 1.0f) {
                                rf = (luma + (rf - luma) * saturation).coerceIn(0f, 1f)
                                gf = (luma + (gf - luma) * saturation).coerceIn(0f, 1f)
                                bf = (luma + (bf - luma) * saturation).coerceIn(0f, 1f)
                            }
                        }

                        // ── 8. Split Toning ──
                        if (shadowTintStrength > 0f || highlightTintStrength > 0f) {
                            val luma = rf * 0.299f + gf * 0.587f + bf * 0.114f
                            val shadowW = (1f - luma).coerceIn(0f, 1f)
                            val highlightW = luma.coerceIn(0f, 1f)
                            if (shadowTintStrength > 0f) {
                                rf += shadowTintR * shadowW * shadowTintStrength
                                gf += shadowTintG * shadowW * shadowTintStrength
                                bf += shadowTintB * shadowW * shadowTintStrength
                            }
                            if (highlightTintStrength > 0f) {
                                rf += highlightTintR * highlightW * highlightTintStrength
                                gf += highlightTintG * highlightW * highlightTintStrength
                                bf += highlightTintB * highlightW * highlightTintStrength
                            }
                            rf = rf.coerceIn(0f, 1f)
                            gf = gf.coerceIn(0f, 1f)
                            bf = bf.coerceIn(0f, 1f)
                        }

                        // ── 8.5. Fade (black-point lift) ──
                        // Mirrors the GL shader: lift shadows toward mid-gray,
                        // leaving mid-tones and highlights nearly untouched.
                        if (fade > 0f) {
                            rf += fade * (0.5f - rf) * (1f - rf)
                            gf += fade * (0.5f - gf) * (1f - gf)
                            bf += fade * (0.5f - bf) * (1f - bf)
                            rf = rf.coerceIn(0f, 1f)
                            gf = gf.coerceIn(0f, 1f)
                            bf = bf.coerceIn(0f, 1f)
                        }

                        // Convert back to 8-bit
                        r8 = (rf * 255f + 0.5f).toInt().coerceIn(0, 255)
                        g8 = (gf * 255f + 0.5f).toInt().coerceIn(0, 255)
                        b8 = (bf * 255f + 0.5f).toInt().coerceIn(0, 255)

                        // ── 9. Film Grain ──
                        if (grainStrength > 0f) {
                            // ── Realistic silver-halide film grain ────────────
                            val lum = (r8 * 0.299f + g8 * 0.587f + b8 * 0.114f) / 255f
                            val midMask = 1f - 4f * (lum - 0.5f) * (lum - 0.5f) // [0, 1]
                            val midMaskClamped = midMask.coerceAtLeast(0.4f)

                            val fine = valueNoise2D(x * 1.05f, y * 1.05f)
                            val medium = valueNoise2D(x * 0.32f + 31.7f, y * 0.32f + 17.3f)
                            val monoCentered = (fine + medium) - 1f

                            val amp = grainStrength * (1.2f + 2.0f * midMaskClamped) * 11f
                            val monoDelta = (monoCentered * amp).toInt()

                            var chromaR = 0
                            var chromaG = 0
                            var chromaB = 0
                            if (grainChroma > 0f) {
                                val cr = valueNoise2D(x * 1.13f + 7.1f, y * 1.13f + 3.7f)
                                val cg = valueNoise2D(x * 0.97f + 91.3f, y * 0.97f + 47.2f)
                                val cb = valueNoise2D(x * 1.21f + 13.4f, y * 1.21f + 71.9f)
                                chromaR = ((cr - 0.5f) * amp * grainChroma).toInt()
                                chromaG = ((cg - 0.5f) * amp * grainChroma).toInt()
                                chromaB = ((cb - 0.5f) * amp * grainChroma).toInt()
                            }

                            r8 = (r8 + monoDelta + chromaR).coerceIn(0, 255)
                            g8 = (g8 + monoDelta + chromaG).coerceIn(0, 255)
                            b8 = (b8 + monoDelta + chromaB).coerceIn(0, 255)
                        }

                        if (lutActive) {
                            // Trilinear LUT blend — folded from the
                            // previous standalone LutColorFilter.applyInPlace
                            // pass. Outputs the byte-quantized ARGB pixel in
                            // one go, no extra getPixels/setPixels round-trip.
                            val rF = r8.toFloat() * lutScaleF
                            val gF = g8.toFloat() * lutScaleF
                            val bF = b8.toFloat() * lutScaleF
                            val r0 = if (rF < 0f) 0 else if (rF > lutMaxIdxF) lutMaxIdx else rF.toInt()
                            val g0 = if (gF < 0f) 0 else if (gF > lutMaxIdxF) lutMaxIdx else gF.toInt()
                            val b0 = if (bF < 0f) 0 else if (bF > lutMaxIdxF) lutMaxIdx else bF.toInt()
                            val r1 = if (r0 < lutMaxIdx) r0 + 1 else lutMaxIdx
                            val g1 = if (g0 < lutMaxIdx) g0 + 1 else lutMaxIdx
                            val b1 = if (b0 < lutMaxIdx) b0 + 1 else lutMaxIdx
                            val dR = rF - r0
                            val dG = gF - g0
                            val dB = bF - b0
                            val dR1 = 1f - dR
                            val dG1 = 1f - dG
                            val dB1 = 1f - dB

                            val dataArr = lutData!!
                            val i000 = (b0 * lutSz + g0 * lutN + r0) * 3
                            val i100 = (b0 * lutSz + g0 * lutN + r1) * 3
                            val i010 = (b0 * lutSz + g1 * lutN + r0) * 3
                            val i110 = (b0 * lutSz + g1 * lutN + r1) * 3
                            val i001 = (b1 * lutSz + g0 * lutN + r0) * 3
                            val i101 = (b1 * lutSz + g0 * lutN + r1) * 3
                            val i011 = (b1 * lutSz + g1 * lutN + r0) * 3
                            val i111 = (b1 * lutSz + g1 * lutN + r1) * 3

                            val c000r = dataArr[i000];     val c100r = dataArr[i100]
                            val c010r = dataArr[i010];     val c110r = dataArr[i110]
                            val c001r = dataArr[i001];     val c101r = dataArr[i101]
                            val c011r = dataArr[i011];     val c111r = dataArr[i111]
                            val rLow = (c000r * dR1 + c100r * dR) * dG1 + (c010r * dR1 + c110r * dR) * dG
                            val rUp  = (c001r * dR1 + c101r * dR) * dG1 + (c011r * dR1 + c111r * dR) * dG
                            val outR = rLow * dB1 + rUp * dB

                            val c000g = dataArr[i000 + 1]; val c100g = dataArr[i100 + 1]
                            val c010g = dataArr[i010 + 1]; val c110g = dataArr[i110 + 1]
                            val c001g = dataArr[i001 + 1]; val c101g = dataArr[i101 + 1]
                            val c011g = dataArr[i011 + 1]; val c111g = dataArr[i111 + 1]
                            val gLow = (c000g * dR1 + c100g * dR) * dG1 + (c010g * dR1 + c110g * dR) * dG
                            val gUp  = (c001g * dR1 + c101g * dR) * dG1 + (c011g * dR1 + c111g * dR) * dG
                            val outG = gLow * dB1 + gUp * dB

                            val c000b = dataArr[i000 + 2]; val c100b = dataArr[i100 + 2]
                            val c010b = dataArr[i010 + 2]; val c110b = dataArr[i110 + 2]
                            val c001b = dataArr[i001 + 2]; val c101b = dataArr[i101 + 2]
                            val c011b = dataArr[i011 + 2]; val c111b = dataArr[i111 + 2]
                            val bLow = (c000b * dR1 + c100b * dR) * dG1 + (c010b * dR1 + c110b * dR) * dG
                            val bUp  = (c001b * dR1 + c101b * dR) * dG1 + (c011b * dR1 + c111b * dR) * dG
                            val outB = bLow * dB1 + bUp * dB

                            val or8 = (outR * 255f + 0.5f).toInt().coerceIn(0, 255)
                            val og8 = (outG * 255f + 0.5f).toInt().coerceIn(0, 255)
                            val ob8 = (outB * 255f + 0.5f).toInt().coerceIn(0, 255)

                            // ── 10. Milky pastel haze overlay (dreamcore) ──
                            // Final stage (after LUT): blend toward the cream
                            // tint, weighted toward shadows (heavy wash on
                            // dark pixels, gentle wash on highlights). Mirrors
                            // the GL shader so the JPEG and the live viewfinder
                            // agree. Falls through to the regular pixel write
                            // below after this conditional modifies or8/og8/ob8.
                            if (milkyActive) {
                                val ms =
                                    (1f - (or8 / 255f * 0.299f + og8 / 255f * 0.587f + ob8 / 255f * 0.114f))
                                        .coerceIn(0f, 1f) * milkyStrengthShade + milkyStrengthBase
                                val clampedMs = ms.coerceIn(0f, 1f)
                                val invMs = 1f - clampedMs
                                val mR = (or8 / 255f * invMs + milkyR * clampedMs) * 255f + 0.5f
                                val mG = (og8 / 255f * invMs + milkyG * clampedMs) * 255f + 0.5f
                                val mB = (ob8 / 255f * invMs + milkyB * clampedMs) * 255f + 0.5f
                                val mr8 = mR.toInt().coerceIn(0, 255)
                                val mg8 = mG.toInt().coerceIn(0, 255)
                                val mb8 = mB.toInt().coerceIn(0, 255)
                                pixels[p] = (a shl 24) or (mr8 shl 16) or (mg8 shl 8) or mb8
                            } else {
                                pixels[p] = (a shl 24) or (or8 shl 16) or (og8 shl 8) or ob8
                            }
                        } else {
                            // No LUT path — apply milky haze to the
                            // post-split-toning / post-grain / etc. RGB.
                            if (milkyActive) {
                                val lumF =
                                    (r8 / 255f * 0.299f + g8 / 255f * 0.587f + b8 / 255f * 0.114f)
                                val ms =
                                    ((1f - lumF).coerceIn(0f, 1f)) * milkyStrengthShade + milkyStrengthBase
                                val clampedMs = ms.coerceIn(0f, 1f)
                                val invMs = 1f - clampedMs
                                val mR = (r8 / 255f * invMs + milkyR * clampedMs) * 255f + 0.5f
                                val mG = (g8 / 255f * invMs + milkyG * clampedMs) * 255f + 0.5f
                                val mB = (b8 / 255f * invMs + milkyB * clampedMs) * 255f + 0.5f
                                val mr8 = mR.toInt().coerceIn(0, 255)
                                val mg8 = mG.toInt().coerceIn(0, 255)
                                val mb8 = mB.toInt().coerceIn(0, 255)
                                pixels[p] = (a shl 24) or (mr8 shl 16) or (mg8 shl 8) or mb8
                            } else {
                                pixels[p] = (a shl 24) or (r8 shl 16) or (g8 shl 8) or b8
                            }
                        }
                        p++
                    }
                }
            }.awaitAll()
        }

        // ── Procedural overlay pass (dust / scratches / light leak) ──
        // Applied in a second parallel sweep after the graded pixels
        // (including the LUT and milky haze) are final, so the main
        // per-pixel loop stays single-purpose. These overlays are
        // position-only: they don't read neighbours, so an in-place
        // pass over `pixels` is safe.
        if (dust > 0f || scratch > 0f || lightLeak > 0f) {
            coroutineScope {
                (0 until numChunks).map { chunk ->
                    val start = chunk * chunkSize
                    val end = (start + chunkSize).coerceAtMost(total)
                    async(Dispatchers.Default) {
                        var p = start
                        while (p < end) {
                            val x = p % w
                            val y = p / w
                            val c = pixels[p]
                            val a = (c ushr 24) and 0xFF
                            var rr = ((c ushr 16) and 0xFF) / 255f
                            var gg = ((c ushr 8) and 0xFF) / 255f
                            var bb = (c and 0xFF) / 255f

                            if (dust > 0f) {
                                val cell = 64
                                val idX = x / cell
                                val idY = y / cell
                                val uvX = (x - idX * cell).toFloat() / cell
                                val uvY = (y - idY * cell).toFloat() / cell
                                val rnd = hashF(idX.toFloat(), idY.toFloat())
                                if (rnd >= 0.62f) {
                                    val dcx = hashF(idX + 13.7f, idY.toFloat())
                                    val dcy = hashF(idX.toFloat(), idY + 57.1f)
                                    val rad = 0.05f + hashF(idX + 23.3f, idY + 91.7f) * 0.14f
                                    val ddx = uvX - dcx
                                    val ddy = uvY - dcy
                                    val d = kotlin.math.sqrt(ddx * ddx + ddy * ddy)
                                    val mask = 1f - smoothstep3(rad * 0.4f, rad, d)
                                    val amount = mask * (0.6f + hashF(idX + 71.9f, idY + 3.1f) * 0.4f)
                                    rr -= amount * dust * 0.45f
                                    gg -= amount * dust * 0.45f
                                    bb -= amount * dust * 0.45f
                                }
                            }
                            if (scratch > 0f) {
                                var best = 0f
                                for (i in 0 until 5) {
                                    val band = i.toFloat()
                                    val sx = hashF(band, 3.3f)
                                    val present = if (hashF(band, 9.1f) >= 0.5f) 1f else 0f
                                    val dxp = kotlin.math.abs(x - sx * w)
                                    val widthPx = 1f + hashF(band, 7.7f) * 4f
                                    val line = 1f - smoothstep3(widthPx * 0.3f, widthPx, dxp)
                                    val flicker = 0.6f + 0.4f * hashF(kotlin.math.floor(y / 10f), band)
                                    val s = present * line * flicker
                                    if (s > best) best = s
                                }
                                rr -= best * scratch * 0.55f
                                gg -= best * scratch * 0.55f
                                bb -= best * scratch * 0.55f
                            }
                            if (lightLeak > 0f) {
                                val ndcX = (x - cx) / (w * 0.5f)
                                val ndcY = (y - cy) / (h * 0.5f)
                                val tlx = ndcX + 1f
                                val tly = ndcY + 1f
                                val tlLen = kotlin.math.sqrt(tlx * tlx + tly * tly)
                                val brx = ndcX - 1f
                                val bry = ndcY - 1f
                                val brLen = kotlin.math.sqrt(brx * brx + bry * bry)
                                val tl = 1f - smoothstep3(0.15f, 1.5f, tlLen)
                                val br = 1f - smoothstep3(0.15f, 1.5f, brLen)
                                val m = (tl * 0.75f + br * 0.4f).coerceIn(0f, 1f)
                                rr += 1.0f * m * lightLeak * 0.35f
                                gg += 0.55f * m * lightLeak * 0.35f
                                bb += 0.22f * m * lightLeak * 0.35f
                            }

                            val fr8 = (rr * 255f + 0.5f).toInt().coerceIn(0, 255)
                            val fg8 = (gg * 255f + 0.5f).toInt().coerceIn(0, 255)
                            val fb8 = (bb * 255f + 0.5f).toInt().coerceIn(0, 255)
                            pixels[p] = (a shl 24) or (fr8 shl 16) or (fg8 shl 8) or fb8
                            p++
                        }
                    }
                }.awaitAll()
            }
        }

        target.setPixels(pixels, 0, w, 0, 0, w, h)

        // The LUT trilinear blend is now folded into the parallel chunks
        // above; no separate second pixel pass is needed.
        return target
    } finally {
        buffers.inUse = false
        if (buffers.pixels.size > MAX_RETAINED_FILTER_PIXELS) {
            buffers.pixels = IntArray(0)
            buffers.rowDistanceSquared = FloatArray(0)
        }
    }
}

/**
 * Portable float hash matching the GLSL `hash(vec2)` used by the live
 * preview and GPU capture shaders:
 * `fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453)`.
 *
 * Replaced the previous 32-bit integer Murmur-style hash so the CPU and
 * GPU film-grain key off the same noise field and therefore match within
 * float precision. `sin`/`floor` run in double precision on the JVM and
 * are narrowed back to Float, which mirrors the shader's mediump path
 * closely enough for grain.
 */
private fun hashF(x: Float, y: Float): Float {
    val dot = x * 127.1f + y * 311.7f
    val s = kotlin.math.sin(dot.toDouble()).toFloat() * 43758.5453f
    return s - kotlin.math.floor(s.toDouble()).toFloat()
}

/** 5th-order smootherstep, matching the shader's `smootherstepNoise`. */
private fun smootherstep(t: Float): Float = t * t * t * (t * (t * 6f - 15f) + 10f)

/** GLSL `smoothstep(edge0, edge1, x)` equivalent (3rd-order). */
private fun smoothstep3(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * 2-D value (Perlin-like) noise. Returns a uniform random in [0, 1) with
 * smooth interpolation between integer-lattice hash samples.
 *
 * Uses 5th-order smootherstep (`6x^5 - 15x^4 + 10x^3`) instead of the
 * usual 3rd-order smoothstep: the smootherstep has zero 1st and 2nd
 * derivatives at the lattice boundary, which kills the faint grid
 * cells visible in cheaper interpolators (you can usually see them
 * when zooming into generated Perlin noise — they read as soft
 * checkerboard instead of pure noise).
 *
 * Per-call cost: 4 hash lookups + ~6 multiplies + ~6 adds. Called twice
 * per grain pixel (fine + medium octaves) inside the parallel chunk
 * loop, which already amortises the chunk start/coroutine overhead.
 */
private fun valueNoise2D(x: Float, y: Float): Float {
    val ix = x.toInt()
    val iy = y.toInt()
    val fx = x - ix
    val fy = y - iy
    val sx = smootherstep(fx)
    val sy = smootherstep(fy)

    val n00 = hashF(ix.toFloat(), iy.toFloat())
    val n10 = hashF((ix + 1).toFloat(), iy.toFloat())
    val n01 = hashF(ix.toFloat(), (iy + 1).toFloat())
    val n11 = hashF((ix + 1).toFloat(), (iy + 1).toFloat())

    val nx0 = n00 + (n10 - n00) * sx
    val nx1 = n01 + (n11 - n01) * sx
    return nx0 + (nx1 - nx0) * sy
}
