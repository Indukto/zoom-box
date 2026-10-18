package eu.indukto.zoombox.zoom

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Placement of the on-screen viewfinder box: the frame the user composes in
 * and the region the capture crop is read from. All values are screen-space
 * dp, matching the `Modifier.offset(x = …, y = …)` origin the box draws from.
 */
internal data class ViewfinderBox(
    val left: Dp,
    val top: Dp,
    val width: Dp,
    val height: Dp
) {
    val right: Dp get() = left + width
    val bottom: Dp get() = top + height
}

/**
 * Viewfinder geometry for one screen size + aspect ratio.
 *
 * Single source of truth: [CameraActiveScreen] places the box and
 * [CameraUi]'s tutorial arrow mirrors it, and both read this instead of
 * repeating the arithmetic (they drifted once, which is how 3:2 ended up
 * with its bottom edge under the deck).
 *
 * Fractions come from the ZoomBox Figma spec (393×852
 * `zoombox-camera-main-screen`), relative to the full screen:
 * 92.12% width (3.94% side margins), 13.22% top inset (status area),
 * 30.13% bottom reserve (the control bubble + the two-row bottom deck).
 *
 * Two bands bound the box:
 *  - the **soft band** (top inset → deck line) is where a ratio that fits is
 *    centered, so 4:3 and 1:1 keep the spec's proportions;
 *  - the **hard band** ([MIN_TOP] → deck line) is the most a box that keeps
 *    the full width may grow upward.
 *
 * A ratio too tall even for the hard band is **letterboxed**: the ratio is
 * preserved exactly and the WIDTH is given up instead, anchored to the deck
 * line. That is the only way to keep the whole frame on screen — the bottom
 * deck is opaque and never moves, so a box wider than the band allows has to
 * be cropped by something (its own controls) no matter how it is placed.
 * 3:2 still fits the hard band at full width and needs no bars; 16:9 needs
 * ~644 dp of height for a ~483 dp band, so it letterboxes to ~81% width on a
 * 19.5:9 phone.
 */
internal object ViewfinderLayout {

    const val WIDTH_FRACTION = 0.9212f // 100 - 3.94*2
    const val TOP_FRACTION = 0.1322f
    const val BOTTOM_RESERVE_FRACTION = 0.3013f

    /**
     * Topmost edge any box may reach — the status-bar floor for the upward
     * growth a tall-but-fitting ratio uses, and for the letterbox case.
     */
    val MIN_TOP = 28.dp

    /** Degenerate-screen floor so the bands can't invert on absurd sizes. */
    private val MIN_BAND = 120.dp

    fun box(screenWidth: Dp, screenHeight: Dp, aspectRatio: AspectRatio): ViewfinderBox {
        val topInset = screenHeight * TOP_FRACTION
        val deckLine = screenHeight - screenHeight * BOTTOM_RESERVE_FRACTION
        val band = (deckLine - topInset).coerceAtLeast(MIN_BAND)
        val hardBand = (deckLine - MIN_TOP).coerceAtLeast(MIN_BAND)

        var width = screenWidth * WIDTH_FRACTION
        var height = width * aspectRatio.heightToWidth
        if (height > hardBand) {
            // Too tall for the hard band at full width: keep the ratio, trade
            // width for height so the deck can't crop the frame.
            height = hardBand
            width = height / aspectRatio.heightToWidth
        }

        // Fits the soft band → centered in it. Otherwise the box grows upward
        // off the deck line, which keeps the bottom edge (and the bubble
        // anchored to it) clear of the deck controls. The final cap is a no-op
        // on any real screen — it only stops the MIN_BAND floor from letting
        // the centered branch push past the deck line on a degenerate size.
        val centered = topInset + (band - height) / 2f
        val anchored = deckLine - height
        val top = (if (height <= band) centered else anchored)
            .coerceIn(MIN_TOP, (deckLine - height).coerceAtLeast(MIN_TOP))
        return ViewfinderBox(
            left = (screenWidth - width) / 2f,
            top = top,
            width = width,
            height = height
        )
    }
}