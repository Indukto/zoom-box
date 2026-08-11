package com.example.zoom

/**
 * Pure math module for the zoom-box overlay.
 *
 * Computes the exact pixel rect of the zoom box based on the viewfinder lens
 * focal length and the current target focal length.
 *
 * The zoom box represents what portion of the viewfinder scene will be captured.
 * Formula: boxScale = viewfinderFocalLength / targetFocalLength
 * The scale is ≤ 1.0 because the viewfinder always shows a wider FOV than the target.
 */
object ZoomBoxCalculator {

    // Pixel 7 Pro reference 35mm-equivalent focal lengths
    val ULTRAWIDE_FOCAL_LENGTH = 13f
    val NORMAL_FOCAL_LENGTH = 25f
    val TELE_FOCAL_LENGTH = 69f

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
     * Calculates the zoom-box rect in pixel coordinates.
     *
     * @param viewfinderFocalLength Focal length of the viewfinder lens (e.g., 13mm UW)
     * @param currentZoomFocalLength Current target focal length
     * @param viewfinderWidth Width of the viewfinder container in pixels
     * @param viewfinderHeight Height of the viewfinder container in pixels
     * @return Centered [android.graphics.Rect] with the zoom-box position and size
     */
    fun calculateZoomBox(
        viewfinderFocalLength: Float,
        currentZoomFocalLength: Float,
        viewfinderWidth: Int,
        viewfinderHeight: Int
    ): android.graphics.Rect {
        val scale = (viewfinderFocalLength / currentZoomFocalLength).coerceIn(0f, 1f)

        val boxWidth = (viewfinderWidth * scale).toInt()
        val boxHeight = (viewfinderHeight * scale).toInt()

        val left = (viewfinderWidth - boxWidth) / 2
        val top = (viewfinderHeight - boxHeight) / 2
        val right = left + boxWidth
        val bottom = top + boxHeight

        return android.graphics.Rect(left, top, right, bottom)
    }

    /**
     * Converts a zoom ratio (relative to 25mm normal) to focal length.
     * 1.0× = 25mm, 0.5× = 13mm, 2.8× = 69mm, 5.0× = 125mm
     */
    fun zoomRatioToFocalLength(zoomRatio: Float): Float {
        return NORMAL_FOCAL_LENGTH * zoomRatio
    }

    /**
     * Converts a focal length to a zoom ratio (relative to 25mm normal).
     */
    fun focalLengthToZoomRatio(focalLength: Float): Float {
        return focalLength / NORMAL_FOCAL_LENGTH
    }
}
