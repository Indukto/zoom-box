package eu.indukto.zoombox.color

import kotlin.math.roundToInt

/**
 * Pre-blurred highlight field backing the CPU halation stage.
 *
 * The GL back-ends get halation for free: they render a quarter-resolution
 * blur into an FBO and sample it from the effect shader. The CPU filter has no
 * such target, so it builds the same thing once per capture — a quarter-res
 * box-downsample of the *ungraded* input, blurred with a separable box — and
 * samples it bilinearly at composite time. Sampling the input rather than the
 * graded pixels matches the shader, which gathers from the copied camera frame
 * before any stage runs.
 *
 * Channels are stored as 8-bit levels: the field only ever feeds a soft,
 * masked glow, so the quantisation is far below the effect's own contrast.
 */
internal class HalationField(
    val width: Int,
    val height: Int,
    private val red: ByteArray,
    private val green: ByteArray,
    private val blue: ByteArray
) {
    /**
     * Bilinear sample at normalised frame coordinates, writing r, g, b in
     * `0..1` into [out]. Clamped at the edges, matching the GL target's
     * `CLAMP_TO_EDGE` wrapping.
     */
    fun sample(u: Float, v: Float, out: FloatArray) {
        val gx = (u * width - 0.5f).coerceIn(0f, (width - 1).toFloat())
        val gy = (v * height - 0.5f).coerceIn(0f, (height - 1).toFloat())
        val x0 = gx.toInt()
        val y0 = gy.toInt()
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val fx = gx - x0
        val fy = gy - y0
        val fx1 = 1f - fx
        val fy1 = 1f - fy
        val rowTop = y0 * width
        val rowBot = y1 * width
        out[0] = bilinear(red, rowTop + x0, rowTop + x1, rowBot + x0, rowBot + x1, fx, fx1, fy, fy1)
        out[1] = bilinear(green, rowTop + x0, rowTop + x1, rowBot + x0, rowBot + x1, fx, fx1, fy, fy1)
        out[2] = bilinear(blue, rowTop + x0, rowTop + x1, rowBot + x0, rowBot + x1, fx, fx1, fy, fy1)
    }

    private fun bilinear(
        channel: ByteArray,
        i00: Int,
        i10: Int,
        i01: Int,
        i11: Int,
        fx: Float,
        fx1: Float,
        fy: Float,
        fy1: Float
    ): Float {
        val top = (channel[i00].toInt() and 0xFF) * fx1 + (channel[i10].toInt() and 0xFF) * fx
        val bottom = (channel[i01].toInt() and 0xFF) * fx1 + (channel[i11].toInt() and 0xFF) * fx
        return (top * fy1 + bottom * fy) / 255f
    }

    companion object {
        /** Downsample factor; the same quarter-res split the GL paths use. */
        const val SCALE = 4

        /**
         * Upper bound on the blur radius in *small-grid* texels. The gather
         * gets softer than film halation needs long before this, and the box
         * blur's cost is linear in the radius.
         */
        private const val MAX_BLUR_RADIUS = 12

        /**
         * Builds the field from [pixels] (ARGB, `width * height` long).
         *
         * [radiusPx] is the halation radius in 1080p pixels, converted to
         * small-grid texels so the glow keeps a constant physical size on a
         * full-sensor capture instead of shrinking.
         */
        fun from(pixels: IntArray, width: Int, height: Int, radiusPx: Float): HalationField {
            val gw = (width / SCALE).coerceAtLeast(1)
            val gh = (height / SCALE).coerceAtLeast(1)
            val count = gw * gh
            val red = ByteArray(count)
            val green = ByteArray(count)
            val blue = ByteArray(count)

            // Box-downsample: one cell per SCALE x SCALE block so a single
            // hot pixel cannot dominate its cell the way nearest sampling
            // would, and so the low-pass that precedes the blur is free.
            //
            // Highlights are thresholded here, per source pixel, before
            // anything is blurred — the shader's gather does the same tap by
            // tap. Masking the blurred field instead would only brighten
            // pixels that were already bright; thresholding first is what
            // lets a bright region bleed out into its dark neighbours.
            for (gy in 0 until gh) {
                val y0 = gy * SCALE
                val y1 = (y0 + SCALE).coerceAtMost(height)
                for (gx in 0 until gw) {
                    val x0 = gx * SCALE
                    val x1 = (x0 + SCALE).coerceAtMost(width)
                    var sumR = 0f
                    var sumG = 0f
                    var sumB = 0f
                    var samples = 0
                    for (y in y0 until y1) {
                        var index = y * width + x0
                        for (x in x0 until x1) {
                            val c = pixels[index++]
                            val r = (c ushr 16) and 0xFF
                            val g = (c ushr 8) and 0xFF
                            val b = c and 0xFF
                            val luma = (r * 0.299f + g * 0.587f + b * 0.114f) / 255f
                            val mask = smoothstep(0.45f, 0.95f, luma)
                            sumR += r * mask
                            sumG += g * mask
                            sumB += b * mask
                            samples++
                        }
                    }
                    if (samples == 0) samples = 1
                    val cell = gy * gw + gx
                    red[cell] = (sumR / samples).roundToInt().coerceIn(0, 255).toByte()
                    green[cell] = (sumG / samples).roundToInt().coerceIn(0, 255).toByte()
                    blue[cell] = (sumB / samples).roundToInt().coerceIn(0, 255).toByte()
                }
            }

            val radius = (radiusPx * (height / 1080f) / SCALE)
                .roundToInt()
                .coerceIn(1, MAX_BLUR_RADIUS)
            val scratch = FloatArray(count)
            return HalationField(
                gw,
                gh,
                boxBlur(red, scratch, gw, gh, radius),
                boxBlur(green, scratch, gw, gh, radius),
                boxBlur(blue, scratch, gw, gh, radius)
            )
        }

        /** GLSL `smoothstep`, in the same 0..1 domain the shader uses. */
        private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        /**
         * Separable box blur with clamped edges. Two one-dimensional sweeps
         * approximate the disc gather the shader's two-ring kernel performs;
         * at this downsample the difference is invisible in the finished glow.
         */
        private fun boxBlur(src: ByteArray, scratch: FloatArray, gw: Int, gh: Int, radius: Int): ByteArray {
            val invWindow = 1f / (radius * 2 + 1)
            for (y in 0 until gh) {
                val row = y * gw
                var sum = 0f
                for (i in -radius..radius) sum += src[row + i.coerceIn(0, gw - 1)].toInt() and 0xFF
                for (x in 0 until gw) {
                    scratch[row + x] = sum * invWindow
                    val leaving = (x - radius).coerceIn(0, gw - 1)
                    val entering = (x + radius + 1).coerceIn(0, gw - 1)
                    sum += ((src[row + entering].toInt() and 0xFF) - (src[row + leaving].toInt() and 0xFF)).toFloat()
                }
            }

            val out = ByteArray(gw * gh)
            for (x in 0 until gw) {
                var sum = 0f
                for (i in -radius..radius) sum += scratch[i.coerceIn(0, gh - 1) * gw + x]
                for (y in 0 until gh) {
                    out[y * gw + x] = (sum * invWindow).roundToInt().coerceIn(0, 255).toByte()
                    val leaving = (y - radius).coerceIn(0, gh - 1)
                    val entering = (y + radius + 1).coerceIn(0, gh - 1)
                    sum += scratch[entering * gw + x] - scratch[leaving * gw + x]
                }
            }
            return out
        }
    }
}
