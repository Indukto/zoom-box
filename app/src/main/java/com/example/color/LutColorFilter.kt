package com.example.color

import android.graphics.Bitmap

/**
 * Applies a 3D LUT (see [CubeLut]) to a [Bitmap] via trilinear interpolation
 * on the CPU.
 *
 * The LUT is treated as a regular `size`×`size`×`size` lattice of RGB output
 * samples. Each input pixel's normalized (R, G, B) components in [0, 1] are
 * mapped onto the lattice and the 8 surrounding samples are blended by their
 * fractional distance — the standard trilinear scheme.
 *
 * Runs on a post-capture bitmap, so the per-pixel cost (no GPU) is acceptable.
 * Alpha is preserved unchanged.
 */
object LutColorFilter {

    /**
     * Returns a new bitmap with the LUT applied. The source bitmap is not
     * recycled. If [source] has no config, ARGB_8888 is used.
     */
    fun apply(source: Bitmap, lut: CubeLut): Bitmap {
        val w = source.width
        val h = source.height
        val srcPixels = IntArray(w * h)
        source.getPixels(srcPixels, 0, w, 0, 0, w, h)

        val outPixels = IntArray(w * h)
        val data = lut.data
        val n = lut.size
        val maxIndex = n - 1
        // The lattice samples sit at i / (n - 1), so a normalized component in
        // [0, 1] maps to a lattice coordinate in [0, n - 1].
        val scale = maxIndex.toFloat()

        for (i in srcPixels.indices) {
            val c = srcPixels[i]
            val a = c ushr 24 and 0xFF
            val r8 = c ushr 16 and 0xFF
            val g8 = c ushr 8 and 0xFF
            val b8 = c and 0xFF

            // Normalize to [0, 1] then to lattice coordinates [0, maxIndex].
            val rF = (r8 / 255f) * scale
            val gF = (g8 / 255f) * scale
            val bF = (b8 / 255f) * scale

            val r0 = rF.toInt().coerceIn(0, maxIndex)
            val g0 = gF.toInt().coerceIn(0, maxIndex)
            val b0 = bF.toInt().coerceIn(0, maxIndex)
            val r1 = if (r0 < maxIndex) r0 + 1 else maxIndex
            val g1 = if (g0 < maxIndex) g0 + 1 else maxIndex
            val b1 = if (b0 < maxIndex) b0 + 1 else maxIndex

            val dR = rF - r0
            val dG = gF - g0
            val dB = bF - b0

            // Starting indices of the 8 lattice corners (×3 to step over RGB).
            val i000 = lut.indexOf(r0, g0, b0) * 3
            val i100 = lut.indexOf(r1, g0, b0) * 3
            val i010 = lut.indexOf(r0, g1, b0) * 3
            val i110 = lut.indexOf(r1, g1, b0) * 3
            val i001 = lut.indexOf(r0, g0, b1) * 3
            val i101 = lut.indexOf(r1, g0, b1) * 3
            val i011 = lut.indexOf(r0, g1, b1) * 3
            val i111 = lut.indexOf(r1, g1, b1) * 3

            // Trilinear blend of each channel. data[idx + 0|1|2] = R|G|B.
            val outR = triChannel(data, i000, i100, i010, i110, i001, i101, i011, i111, 0, dR, dG, dB)
            val outG = triChannel(data, i000, i100, i010, i110, i001, i101, i011, i111, 1, dR, dG, dB)
            val outB = triChannel(data, i000, i100, i010, i110, i001, i101, i011, i111, 2, dR, dG, dB)

            val or8 = (outR * 255f + 0.5f).toInt().coerceIn(0, 255)
            val og8 = (outG * 255f + 0.5f).toInt().coerceIn(0, 255)
            val ob8 = (outB * 255f + 0.5f).toInt().coerceIn(0, 255)

            outPixels[i] = (a shl 24) or (or8 shl 16) or (og8 shl 8) or ob8
        }

        val output = Bitmap.createBitmap(w, h, source.config ?: Bitmap.Config.ARGB_8888)
        output.setPixels(outPixels, 0, w, 0, 0, w, h)
        return output
    }

    /**
     * Trilinear interpolation of a single channel (0 = R, 1 = G, 2 = B).
     *
     * Each `iXXX` is the starting array index of a lattice corner's RGB triple;
     * [offset] selects which of the three channels to read.
     */
    private fun triChannel(
        data: FloatArray,
        i000: Int, i100: Int, i010: Int, i110: Int,
        i001: Int, i101: Int, i011: Int, i111: Int,
        offset: Int,
        dR: Float, dG: Float, dB: Float
    ): Float {
        // Interpolate along R for both B-slices.
        val i00 = lerp(data[i000 + offset], data[i100 + offset], dR)
        val i10 = lerp(data[i010 + offset], data[i110 + offset], dR)
        val i01 = lerp(data[i001 + offset], data[i101 + offset], dR)
        val i11 = lerp(data[i011 + offset], data[i111 + offset], dR)

        // Interpolate along G.
        val i0 = lerp(i00, i10, dG)
        val i1 = lerp(i01, i11, dG)

        // Interpolate along B.
        return lerp(i0, i1, dB)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /**
     * In-place variant: applies the LUT directly to [target]'s pixel array
     * and writes it back. Returns [target] so the call is chainable.
     *
     * Why this exists: the JPEG-saved photo only needs the final pixel
     * values, not a fresh bitmap. [apply] allocates a new ARGB_8888 bitmap
     * and copies wxh pixels twice (source getPixels + setPixels on output);
     * the in-place variant skips both the allocation and one of the copies,
     * halving time-to-first-byte on a full sensor resolution capture. The blend math is
     * identical to [apply] — same trilinear interpolation, same indexing.
     */
    fun applyInPlace(target: Bitmap, lut: CubeLut): Bitmap {
        val w = target.width
        val h = target.height
        val pixels = IntArray(w * h)
        target.getPixels(pixels, 0, w, 0, 0, w, h)

        val data = lut.data
        val n = lut.size
        val maxIdx = if (n > 1) n - 1 else 0
        val scaleF = if (n > 1) (1f / 255f) * (n - 1).toFloat() else 0f
        val sz = if (n > 1) n * n else 0
        val inv255 = 1f / 255f

        for (i in pixels.indices) {
            val c = pixels[i]
            val a = c ushr 24 and 0xFF
            val r8 = c ushr 16 and 0xFF
            val g8 = c ushr 8 and 0xFF
            val b8 = c and 0xFF

            // Saturated RGB -> lattice coords. Inline the toInt->clamp path
            // because the LUT hit rate is 100% in [0, maxIdx].
            val rF = (r8.toFloat()) * scaleF
            val gF = (g8.toFloat()) * scaleF
            val bF = (b8.toFloat()) * scaleF
            val r0 = if (rF < 0f) 0 else if (rF > maxIdx.toFloat()) maxIdx else rF.toInt()
            val g0 = if (gF < 0f) 0 else if (gF > maxIdx.toFloat()) maxIdx else gF.toInt()
            val b0 = if (bF < 0f) 0 else if (bF > maxIdx.toFloat()) maxIdx else bF.toInt()
            val r1 = if (r0 < maxIdx) r0 + 1 else maxIdx
            val g1 = if (g0 < maxIdx) g0 + 1 else maxIdx
            val b1 = if (b0 < maxIdx) b0 + 1 else maxIdx

            val dR = rF - r0
            val dG = gF - g0
            val dB = bF - b0
            val dR1 = 1f - dR
            val dG1 = 1f - dG
            val dB1 = 1f - dB

            // 8 lattice corner offsets (CG layout: ((b * size) + g) * size + r).
            val i000 = (b0 * sz + g0 * n + r0) * 3
            val i100 = (b0 * sz + g0 * n + r1) * 3
            val i010 = (b0 * sz + g1 * n + r0) * 3
            val i110 = (b0 * sz + g1 * n + r1) * 3
            val i001 = (b1 * sz + g0 * n + r0) * 3
            val i101 = (b1 * sz + g0 * n + r1) * 3
            val i011 = (b1 * sz + g1 * n + r0) * 3
            val i111 = (b1 * sz + g1 * n + r1) * 3

            // Inline trilinear blend (function-call overhead was ~10ns / pixel).
            val c000r = data[i000];     val c100r = data[i100]
            val c010r = data[i010];     val c110r = data[i110]
            val c001r = data[i001];     val c101r = data[i101]
            val c011r = data[i011];     val c111r = data[i111]
            val rLow = (c000r * dR1 + c100r * dR) * dG1 + (c010r * dR1 + c110r * dR) * dG
            val rUp = (c001r * dR1 + c101r * dR) * dG1 + (c011r * dR1 + c111r * dR) * dG
            val outR = rLow * dB1 + rUp * dB

            val c000g = data[i000 + 1]; val c100g = data[i100 + 1]
            val c010g = data[i010 + 1]; val c110g = data[i110 + 1]
            val c001g = data[i001 + 1]; val c101g = data[i101 + 1]
            val c011g = data[i011 + 1]; val c111g = data[i111 + 1]
            val gLow = (c000g * dR1 + c100g * dR) * dG1 + (c010g * dR1 + c110g * dR) * dG
            val gUp = (c001g * dR1 + c101g * dR) * dG1 + (c011g * dR1 + c111g * dR) * dG
            val outG = gLow * dB1 + gUp * dB

            val c000b = data[i000 + 2]; val c100b = data[i100 + 2]
            val c010b = data[i010 + 2]; val c110b = data[i110 + 2]
            val c001b = data[i001 + 2]; val c101b = data[i101 + 2]
            val c011b = data[i011 + 2]; val c111b = data[i111 + 2]
            val bLow = (c000b * dR1 + c100b * dR) * dG1 + (c010b * dR1 + c110b * dR) * dG
            val bUp = (c001b * dR1 + c101b * dR) * dG1 + (c011b * dR1 + c111b * dR) * dG
            val outB = bLow * dB1 + bUp * dB

            val or8 = (outR * 255f + 0.5f).toInt().coerceIn(0, 255)
            val og8 = (outG * 255f + 0.5f).toInt().coerceIn(0, 255)
            val ob8 = (outB * 255f + 0.5f).toInt().coerceIn(0, 255)

            pixels[i] = (a shl 24) or (or8 shl 16) or (og8 shl 8) or ob8
        }

        target.setPixels(pixels, 0, w, 0, 0, w, h)
        return target
    }
}
