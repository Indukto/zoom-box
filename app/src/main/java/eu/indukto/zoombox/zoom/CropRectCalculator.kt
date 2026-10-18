package eu.indukto.zoombox.zoom

/**
 * Pure crop-rectangle math for the post-capture pipeline in
 * `CameraViewModel.gradeAndSavePhoto`.
 *
 * The pipeline computes its crop rectangle once, in *display* space: the
 * orientation-normalized frame the user framed in the viewfinder, at full
 * sensor resolution (`rotW` × `rotH`). Two decode paths then consume it:
 *
 *  1. `BitmapRegionDecoder` wants the rectangle in *source* (unrotated,
 *     EXIF-as-stored) coordinates — [sourceRect] inverts the EXIF orientation
 *     for that.
 *  2. The full-frame `BitmapFactory` path decodes the whole image *scaled* by
 *     `inSampleSize` (the 3 MP / 0.8 MP output-resolution settings) and needs
 *     the rectangle re-mapped into that smaller bitmap — [toDecodedSpace].
 *
 * Both live here, dependency-free, because getting either wrong is silent:
 * `Bitmap.createBitmap` merely throws and the caller falls back to an
 * uncropped photo, so the geometry needs JVM tests pinning it rather than
 * on-device archaeology.
 */
object CropRectCalculator {

    // EXIF orientation tag values (android.media.ExifInterface.ORIENTATION_*).
    // Plain ints so this module stays free of Android dependencies; the values
    // are fixed by the EXIF spec.
    private const val ORIENTATION_FLIP_HORIZONTAL = 2
    private const val ORIENTATION_ROTATE_180 = 3
    private const val ORIENTATION_FLIP_VERTICAL = 4
    private const val ORIENTATION_TRANSPOSE = 5
    private const val ORIENTATION_ROTATE_90 = 6
    private const val ORIENTATION_TRANSVERSE = 7
    private const val ORIENTATION_ROTATE_270 = 8

    /**
     * How an EXIF orientation maps the stored image onto the display frame:
     * rotate clockwise by [rotateCwDegrees], then mirror horizontally when
     * [mirrorXAfterRotate]. "Rotate then mirror" covers all eight tags with
     * one composition (a vertical flip is a 180° rotation plus a horizontal
     * mirror), so the display matrix and the source-rect inversion below can
     * never disagree about which transform they are inverting.
     */
    data class OrientationOps(val rotateCwDegrees: Int, val mirrorXAfterRotate: Boolean)

    /** Decomposes an EXIF orientation tag into its rotation + mirror. */
    fun orientationOps(exifOrientation: Int): OrientationOps = when (exifOrientation) {
        ORIENTATION_FLIP_HORIZONTAL -> OrientationOps(0, true)
        ORIENTATION_ROTATE_180 -> OrientationOps(180, false)
        ORIENTATION_FLIP_VERTICAL -> OrientationOps(180, true)
        ORIENTATION_TRANSPOSE -> OrientationOps(90, true)
        ORIENTATION_ROTATE_90 -> OrientationOps(90, false)
        ORIENTATION_TRANSVERSE -> OrientationOps(270, true)
        ORIENTATION_ROTATE_270 -> OrientationOps(270, false)
        else -> OrientationOps(0, false)
    }

    /**
     * Maps a display-space crop ([cropX], [cropY], [cropW] × [cropH] within the
     * orientation-normalized [sourceWidth] × [sourceHeight] frame) back into
     * source coordinates, i.e. the rect to hand `BitmapRegionDecoder`.
     *
     * Returns `[left, top, right, bottom]`, half-open, clamped into the source
     * frame. Works for all eight EXIF orientations — the previous inline
     * mapping handled only the three rotations, so flip-tagged JPEGs had their
     * crop region mirrored into the wrong corner.
     */
    fun sourceRect(
        exifOrientation: Int,
        cropX: Int,
        cropY: Int,
        cropW: Int,
        cropH: Int,
        sourceWidth: Int,
        sourceHeight: Int
    ): IntArray {
        val w = sourceWidth
        val h = sourceHeight
        val rect = when (exifOrientation) {
            ORIENTATION_ROTATE_90 ->
                intArrayOf(cropY, h - cropX - cropW, cropY + cropH, h - cropX)
            ORIENTATION_ROTATE_180 ->
                intArrayOf(w - cropX - cropW, h - cropY - cropH, w - cropX, h - cropY)
            ORIENTATION_ROTATE_270 ->
                intArrayOf(w - cropY - cropH, cropX, w - cropY, cropX + cropW)
            ORIENTATION_TRANSPOSE ->
                intArrayOf(cropY, cropX, cropY + cropH, cropX + cropW)
            ORIENTATION_TRANSVERSE ->
                intArrayOf(w - cropY - cropH, h - cropX - cropW, w - cropY, h - cropX)
            ORIENTATION_FLIP_HORIZONTAL ->
                intArrayOf(w - cropX - cropW, cropY, w - cropX, cropY + cropH)
            ORIENTATION_FLIP_VERTICAL ->
                intArrayOf(cropX, h - cropY - cropH, cropX + cropW, h - cropY)
            else ->
                intArrayOf(cropX, cropY, cropX + cropW, cropY + cropH)
        }
        // Defensive clamp: a bogus EXIF tag with sane crop input must still
        // hand the decoder a rect it accepts rather than throwing mid-save.
        rect[0] = rect[0].coerceIn(0, w)
        rect[1] = rect[1].coerceIn(0, h)
        rect[2] = rect[2].coerceIn(rect[0] + 1, w)
        rect[3] = rect[3].coerceIn(rect[1] + 1, h)
        return rect
    }

    /**
     * Re-maps a display-space crop from full-resolution coordinates into the
     * coordinate space of a bitmap decoded with `inSampleSize`.
     *
     * Returns `[x, y, width, height]`, guaranteed to lie inside the decoded
     * bitmap with width/height ≥ 1. The scale is derived from the *actual*
     * decoded dimensions rather than `1 / inSampleSize`, because
     * `BitmapFactory` is only contracted to land "close to" the nominal
     * downscaled size and the rounding differs per decoder.
     *
     * Passing unscaled full-resolution coordinates here is what made
     * `Bitmap.createBitmap` throw at the 3 MP / 0.8 MP settings and the
     * caller silently fall back to an uncropped photo.
     */
    fun toDecodedSpace(
        cropX: Int,
        cropY: Int,
        cropW: Int,
        cropH: Int,
        fullWidth: Int,
        fullHeight: Int,
        decodedWidth: Int,
        decodedHeight: Int
    ): IntArray {
        if (fullWidth <= 0 || fullHeight <= 0 || decodedWidth <= 0 || decodedHeight <= 0) {
            return intArrayOf(0, 0, decodedWidth.coerceAtLeast(1), decodedHeight.coerceAtLeast(1))
        }
        var x = (cropX.toLong() * decodedWidth / fullWidth).toInt()
        var y = (cropY.toLong() * decodedHeight / fullHeight).toInt()
        var w = (cropW.toLong() * decodedWidth / fullWidth).toInt()
        var h = (cropH.toLong() * decodedHeight / fullHeight).toInt()
        // A 1-px crop rounds to 0 at high downsample factors; keep it visible.
        w = w.coerceAtLeast(1)
        h = h.coerceAtLeast(1)
        x = x.coerceIn(0, decodedWidth - 1)
        y = y.coerceIn(0, decodedHeight - 1)
        w = w.coerceAtMost(decodedWidth - x)
        h = h.coerceAtMost(decodedHeight - y)
        return intArrayOf(x, y, w, h)
    }
}
