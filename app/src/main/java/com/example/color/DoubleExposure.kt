package com.example.color

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Double exposure: the film trick of letting light land on the same frame
 * twice. The second "exposure" here is the previous shot, composited onto the
 * new one with a **screen** blend.
 *
 * Screen is the right operator because it is literally "add light without
 * clipping": `1 - (1 - a)(1 - b)`. Every result is >= both inputs and <= 1,
 * so a silhouette lit against a bright background stays readable instead of
 * blowing out the way additive (`a + b`) blending would.
 *
 * All the math is integer, channel by channel, with explicit round-half-up:
 * the CPU filter and the GPU shader both need repeatable results, and a
 * float `lerp` here would make the saved JPEG depend on JIT rounding.
 *
 * - [blend] never mutates or recycles either input. It returns `base` itself
 *   when there is nothing to do (no ghost, zero strength), which the capture
 *   pipeline relies on for its `if (merged !== base) base.recycle()` guard.
 * - [prepareGhost] produces the bounded copy the pipeline keeps around between
 *   shots, so holding a "previous frame" costs a few MB instead of a full
 *   12 MP frame.
 */
object DoubleExposure {

    /** Default ghost opacity: strong enough to read as two exposures. */
    const val DEFAULT_STRENGTH: Float = 0.55f

    /** Long edge of the retained ghost. ~4.7 MB at ARGB_8888. */
    const val GHOST_MAX_EDGE_PX: Int = 1080

    /**
     * Composites [ghost] onto [base] and returns a new bitmap, or [base]
     * unchanged when there is nothing to blend.
     *
     * @param strength ghost opacity in [0, 1]; 0 disables the effect.
     *   1 is a full second exposure; the default mixes far enough in that a
     *   single ghost frame stays legible.
     */
    fun blend(
        base: Bitmap,
        ghost: Bitmap?,
        strength: Float = DEFAULT_STRENGTH
    ): Bitmap {
        val percent = strengthPercent(strength)
        if (ghost == null || percent == 0) return base
        if (ghost.isRecycled || base.width <= 0 || base.height <= 0) return base

        val width = base.width
        val height = base.height
        // A ghost captured at a different resolution (or from a different
        // crop) is resampled rather than rejected: the effect is a soft
        // overlay, so nearest-neighbour seams would be the only visible
        // artifact and bilinear resampling avoids them.
        val aligned = if (ghost.width == width && ghost.height == height) {
            ghost
        } else {
            Bitmap.createScaledBitmap(ghost, width, height, true)
        }

        val basePixels = IntArray(width * height)
        base.getPixels(basePixels, 0, width, 0, 0, width, height)
        val ghostPixels = IntArray(width * height)
        aligned.getPixels(ghostPixels, 0, width, 0, 0, width, height)
        if (aligned !== ghost) aligned.recycle()

        val out = IntArray(width * height)
        for (i in out.indices) {
            val b = basePixels[i]
            val g = ghostPixels[i]
            val r = mixChannel(channel(b, 16), channel(g, 16), percent)
            val gr = mixChannel(channel(b, 8), channel(g, 8), percent)
            val bl = mixChannel(channel(b, 0), channel(g, 0), percent)
            // Alpha comes from the new frame alone: the ghost is a record of
            // an earlier *exposure*, not a second layer of the same canvas,
            // so letting it punch holes in the current frame would show up as
            // unexplained transparency in the saved file.
            out[i] = (b and 0xFF000000.toInt()) or (r shl 16) or (gr shl 8) or bl
        }
        return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Bounded ARGB_8888 copy of [source] for retention between shots: scaled
     * down so its long edge is at most [maxEdge]. Returns null for a source
     * with no pixels (a recycled bitmap, a failed decode) so callers can
     * treat "no ghost yet" as one state instead of two.
     */
    fun prepareGhost(source: Bitmap, maxEdge: Int = GHOST_MAX_EDGE_PX): Bitmap? {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
        val limit = max(1, maxEdge)
        val longEdge = max(source.width, source.height)
        if (longEdge <= limit) return source.copy(Bitmap.Config.ARGB_8888, false)
        val scale = limit.toFloat() / longEdge
        val width = max(1, (source.width * scale).roundToInt())
        val height = max(1, (source.height * scale).roundToInt())
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    /** [strength] as a rounded 0..100 percentage of the second exposure. */
    private fun strengthPercent(strength: Float): Int =
        (strength.coerceIn(0f, 1f) * 100f).roundToInt()

    /**
     * `screen(base, ghost)` mixed back toward [base] by [percent]:
     * `base + (screen - base) * percent / 100`.
     */
    private fun mixChannel(base: Int, ghost: Int, percent: Int): Int {
        val screen = 255 - ((255 - base) * (255 - ghost) + 127) / 255
        return base + ((screen - base) * percent + 50) / 100
    }

    private fun channel(pixel: Int, shift: Int): Int = (pixel shr shift) and 0xFF
}