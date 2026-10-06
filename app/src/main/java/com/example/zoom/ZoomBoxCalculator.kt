package com.example.zoom

/**
 * Pure math module for the zoom-box overlay.
 */
object ZoomBoxCalculator {

    /**
     * Digital zoom limits (ratios relative to the primary lens). Shared by the
     * zoom gesture in CameraPreviewView and the clamp in CameraViewModel.setZoom
     * so both stay in lock-step. 5.0x on a 24 mm primary reaches ~120 mm,
     * roughly matching the tele lens — the box-shrink ceiling the UI was
     * designed around.
     */
    const val MIN_ZOOM_RATIO = 1.0f
    const val MAX_ZOOM_RATIO = 5.0f

    /**
     * Rubber-band resistance applied per unit of overshoot past a zoom limit.
     * The mapping is `limit ± excess * FACTOR / (1 + excess * 0.5)`: stiff
     * near the limit, increasingly resistant further out, so the finger
     * feels the band tighten instead of hitting a wall.
     */
    const val RUBBER_BAND_FACTOR = 0.35f

    /** Hard cap on how far (in zoom-ratio units) the visual zoom may overshoot. */
    const val MAX_OVERSHOOT = 0.35f

    /** Committed zoom for capture/focal math: hard-clamped to [MIN, MAX]. */
    fun clampZoom(zoom: Float): Float =
        zoom.coerceIn(MIN_ZOOM_RATIO, MAX_ZOOM_RATIO)

    /**
     * Visual zoom with rubber-band resistance past [MIN_ZOOM_RATIO] /
     * [MAX_ZOOM_RATIO]. Identity inside the range; outside, the excess is
     * compressed with diminishing returns and capped at [MAX_OVERSHOOT].
     * The caller commits [clampZoom] (capture truth, focal label) and shows
     * `rubberBand(raw) - clampZoom(raw)` as a sprung overshoot that snaps
     * back to 0 on release.
     */
    fun rubberBand(zoom: Float): Float {
        if (zoom >= MIN_ZOOM_RATIO && zoom <= MAX_ZOOM_RATIO) return zoom
        val (limit, excess) = if (zoom < MIN_ZOOM_RATIO) {
            MIN_ZOOM_RATIO to (MIN_ZOOM_RATIO - zoom)
        } else {
            MAX_ZOOM_RATIO to (zoom - MAX_ZOOM_RATIO)
        }
        val rubber = excess * RUBBER_BAND_FACTOR / (1f + excess * 0.5f)
        val capped = rubber.coerceIn(0f, MAX_OVERSHOOT)
        return if (zoom < MIN_ZOOM_RATIO) limit - capped else limit + capped
    }
}
