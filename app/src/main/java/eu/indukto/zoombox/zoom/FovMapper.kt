package eu.indukto.zoombox.zoom

/**
 * Core math module for the zoom-box camera.
 *
 * All functions are pure — no Android dependencies, fully unit-testable.
 *
 * The zoom-box works by showing a "framing box" on the preview that indicates
 * what portion of the scene will be captured at the target focal length.
 *
 * Box size formula:
 *   boxScale = previewFocalMm / targetFocalMm
 *
 * This is derived from the rectilinear projection assumption:
 *   HFOV(f) = 2 * atan(18 / f)   (18 = half 36mm sensor width)
 *   boxScale = tan(HFOV(target)/2) / tan(HFOV(preview)/2) = preview/target
 *
 * The atan/tan cancel exactly, so boxScale is purely the focal length ratio.
 *
 * Lens selection is now manual — the user picks which physical lens to use
 * (Ultra-wide, Primary, or Tele). The zoom box overlay only appears when
 * the Primary lens is selected, showing the digital crop area.
 */
object FovMapper {

    /**
     * Computes the box scale factor for the zoom-box overlay.
     * Only meaningful when the Primary lens is active.
     *
     * @param previewFocalMm The equivalent focal length of the current preview lens
     * @param targetFocalMm The target equivalent focal length the user has selected
     * @return A value in (0, 1] representing the fraction of the frame the box covers.
     *         Returns 0f if targetFocalMm is 0 (should not happen in practice).
     */
    fun boxScale(previewFocalMm: Float, targetFocalMm: Float): Float {
        if (targetFocalMm <= 0f || previewFocalMm <= 0f) return 1f
        return (previewFocalMm / targetFocalMm).coerceIn(0f, 1f)
    }

    /**
     * Computes the digital crop factor for the capture.
     *
     * When the target focal length exceeds the native focal length of the
     * capture lens, we need to digitally crop the output.
     *
     * @param captureLensFocalMm The native equivalent focal length of the capture lens
     * @param targetFocalMm The target equivalent focal length
     * @return The crop factor (≥ 1.0). 1.0 means no digital crop.
     */
    fun captureCropFactor(captureLensFocalMm: Float, targetFocalMm: Float): Float {
        if (targetFocalMm <= 0f || captureLensFocalMm <= 0f) return 1f
        return (targetFocalMm / captureLensFocalMm).coerceAtLeast(1f)
    }

    /**
     * Estimates the output megapixels after crop.
     *
     * @param nativeMp The native megapixels of the capture lens
     * @param cropFactor The digital crop factor (≥ 1.0)
     * @return Approximate output megapixels after cropping
     */
    fun outputMegapixels(nativeMp: Int, cropFactor: Float): Float {
        return nativeMp / (cropFactor * cropFactor)
    }
}
