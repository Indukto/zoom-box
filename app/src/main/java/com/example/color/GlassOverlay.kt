package com.example.color

/**
 * Immutable description of one rounded-rect "liquid glass" surface drawn over
 * the live camera preview by [LutPreviewRenderer].
 *
 * The renderer's effect pass re-samples the camera texture under the glass with
 * a lens displacement, so the surface genuinely bends the live image instead of
 * painting a translucent panel over it. This class only carries geometry and
 * optical tuning values across the UI → GL boundary; all geometry is in *view
 * pixels* relative to the GL surface (the viewfinder), and the renderer
 * normalizes the rect to `[0, 1]` UV space when it uploads the uniforms.
 *
 * Deliberately pure Kotlin (no android.* types): the coordinate math is
 * unit-tested on the JVM without Robolectric (see GlassOverlayTest).
 */
data class GlassOverlay(
    /** Left edge of the glass surface, in view pixels. */
    val leftPx: Float,
    /** Top edge of the glass surface, in view pixels. */
    val topPx: Float,
    /** Right edge of the glass surface, in view pixels. */
    val rightPx: Float,
    /** Bottom edge of the glass surface, in view pixels. */
    val bottomPx: Float,
    /** Corner radius of the rounded rect, in view pixels. */
    val radiusPx: Float,
    /** Maximum lens displacement at the boundary, in view pixels. */
    val refractionPx: Float,
    /** In-glass blur radius at the calm center, in view pixels. */
    val blurPx: Float,
    /** Per-channel spread of the refraction, 0..1 (chromatic dispersion). */
    val dispersion: Float,
    /** Width of the rim highlight band along the boundary, in view pixels. */
    val rimWidthPx: Float
) {
    /**
     * `(left, top, right, bottom)` normalized to the GL view's UV space,
     * clamped to `[0, 1]` so a surface hanging off the view edge cannot make
     * the shader sample outside the frame.
     */
    fun rectUniform(viewWidthPx: Int, viewHeightPx: Int): FloatArray {
        require(viewWidthPx > 0 && viewHeightPx > 0) {
            "View size must be positive: ${viewWidthPx}x$viewHeightPx"
        }
        return floatArrayOf(
            (leftPx / viewWidthPx).coerceIn(0f, 1f),
            (topPx / viewHeightPx).coerceIn(0f, 1f),
            (rightPx / viewWidthPx).coerceIn(0f, 1f),
            (bottomPx / viewHeightPx).coerceIn(0f, 1f)
        )
    }

    /** `(refractionPx, blurPx, dispersion, rimWidthPx)` for `uGlassParams`. */
    fun paramsUniform(): FloatArray =
        floatArrayOf(refractionPx, blurPx, dispersion, rimWidthPx)

    /** Returns a copy with every optical value inside its sane range. */
    fun clamped(): GlassOverlay {
        val minLeft = minOf(leftPx, rightPx)
        val maxRight = maxOf(leftPx, rightPx)
        val minTop = minOf(topPx, bottomPx)
        val maxBottom = maxOf(topPx, bottomPx)
        val maxRadius = minOf(maxRight - minLeft, maxBottom - minTop) * 0.5f
        return GlassOverlay(
            leftPx = minLeft,
            topPx = minTop,
            rightPx = maxRight,
            bottomPx = maxBottom,
            radiusPx = radiusPx.coerceIn(0f, maxRadius.coerceAtLeast(0f)),
            refractionPx = refractionPx.coerceAtLeast(0f),
            blurPx = blurPx.coerceAtLeast(0f),
            dispersion = dispersion.coerceIn(0f, 1f),
            rimWidthPx = rimWidthPx.coerceAtLeast(0f)
        )
    }

    companion object {
        /**
         * Max boundary displacement as a fraction of the glass height. Tuned
         * so the lens bend reads clearly on a small pill ("noticeable near
         * edges") without warping the center.
         */
        const val REFRACTION_FRACTION = 0.05f

        /** In-glass blur radius as a fraction of the glass height (low/moderate). */
        const val BLUR_FRACTION = 0.03f

        /** Chromatic dispersion: per-channel spread of the displacement (subtle). */
        const val DISPERSION = 0.05f

        /** Rim highlight width in dp — matches the ~1dp translucent border spec. */
        const val RIM_WIDTH_DP = 1.5f

        /**
         * Builds the overlay for a rounded-rect glass surface laid out in dp,
         * deriving the optical tuning from the surface height. Returns a
         * [clamped] instance so callers can pass arbitrary layout values.
         */
        fun fromDp(
            leftDp: Float,
            topDp: Float,
            widthDp: Float,
            heightDp: Float,
            cornerRadiusDp: Float,
            density: Float
        ): GlassOverlay {
            require(density > 0f) { "Density must be positive: $density" }
            val leftPx = leftDp * density
            val topPx = topDp * density
            val heightPx = heightDp * density
            return GlassOverlay(
                leftPx = leftPx,
                topPx = topPx,
                rightPx = leftPx + widthDp * density,
                bottomPx = topPx + heightPx,
                radiusPx = cornerRadiusDp * density,
                refractionPx = heightPx * REFRACTION_FRACTION,
                blurPx = heightPx * BLUR_FRACTION,
                dispersion = DISPERSION,
                rimWidthPx = RIM_WIDTH_DP * density
            ).clamped()
        }
    }
}
