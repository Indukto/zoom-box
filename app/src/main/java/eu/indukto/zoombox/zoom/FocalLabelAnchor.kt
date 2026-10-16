package eu.indukto.zoombox.zoom

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Screen position (dp) of a floating label's centre. */
internal data class EdgeLabelAnchor(val x: Dp, val y: Dp)

/**
 * Centre point for a label that rides just outside an axis-aligned [rect], in
 * the viewer's frame at any device angle.
 *
 * [rectLeft]/[rectTop] are the rect's TOP-LEFT corner and [rectWidth] /
 * [rectHeight] its size, so callers can pass the same `offset(x = …, y = …)`
 * origin the rect itself draws from and the two can never disagree about where
 * it is. [gap] is measured from the label's outer edge to the rect's
 * up-facing edge, so a negative [gap] deliberately overlaps the rect by that
 * much (the preset toast sits just inside the viewfinder's boundary).
 *
 * The activity is portrait-locked, so a sideways-held phone shows the whole UI
 * sideways: the rect stays axis-aligned in portrait/screen coordinates while
 * the viewer's "up" points along `(sinθ, -cosθ)` (the portrait RIGHT edge at
 * +90°, the portrait LEFT edge at -90°). Callers are expected to draw the
 * label with `.rotate(θ)` so it reads upright for the viewer; this function
 * only picks the position. The anchor starts at the rect centre and pushes
 * out along the viewer's up vector by the rect's projected half-extent along
 * that direction, plus [gap].
 *
 * Degenerates exactly to "rect top edge ± gap" at θ = 0 and lands on the
 * rect's portrait-right/left face at ±90°, so the label always hugs the rect
 * instead of parking on the far edge of the viewfinder. Because the up
 * direction points at a screen edge once the device is sideways (the
 * viewfinder's side margin is thin), the reach is also capped so the label's
 * outer edge never leaves the screen.
 */
internal fun edgeLabelAnchor(
    rectLeft: Dp,
    rectTop: Dp,
    rectWidth: Dp,
    rectHeight: Dp,
    labelHalfHeight: Dp,
    controlAngle: Float,
    screenWidth: Dp,
    screenHeight: Dp,
    gap: Dp,
    screenPad: Dp = 4.dp
): EdgeLabelAnchor {
    val centerX = rectLeft + rectWidth / 2f
    val centerY = rectTop + rectHeight / 2f

    val radians = Math.toRadians(controlAngle.toDouble())
    val upX = sin(radians).toFloat()
    val upY = -cos(radians).toFloat()

    // Distance from the rect centre to its boundary along the viewer's up
    // vector: for an axis-aligned rect that is the nearer of
    // halfWidth/|ux| and halfHeight/|uy| (a zero component gives Infinity,
    // so the other axis wins).
    val rectEdgeReach = minOf(
        (rectWidth / 2f) / abs(upX),
        (rectHeight / 2f) / abs(upY)
    )

    // Distance from the rect centre to the screen edge along the same vector.
    val screenReachX =
        if (upX > 0f) (screenWidth - screenPad - centerX) / upX
        else if (upX < 0f) (screenPad - centerX) / upX
        else Float.POSITIVE_INFINITY.dp
    val screenReachY =
        if (upY > 0f) (screenHeight - screenPad - centerY) / upY
        else if (upY < 0f) (screenPad - centerY) / upY
        else Float.POSITIVE_INFINITY.dp

    // Ideal: the label's outer edge `gap` beyond the rect's up-facing edge
    // (negative gap = tucked inside it), capped so the label stays on-screen
    // when the rect nearly fills the viewfinder sideways.
    val reach = minOf(
        rectEdgeReach + gap - labelHalfHeight,
        minOf(screenReachX, screenReachY) - labelHalfHeight
    )

    return EdgeLabelAnchor(
        x = centerX + reach * upX,
        y = centerY + reach * upY
    )
}

/**
 * [edgeLabelAnchor] specialised to the zoom box, with the portrait spec's
 * 30dp gap between the focal-length label's outer edge and the box.
 */
internal fun focalLabelAnchor(
    boxLeft: Dp,
    boxTop: Dp,
    boxWidth: Dp,
    boxHeight: Dp,
    labelHalfHeight: Dp,
    controlAngle: Float,
    screenWidth: Dp,
    screenHeight: Dp,
    gap: Dp = 30.dp,
    screenPad: Dp = 4.dp
): EdgeLabelAnchor = edgeLabelAnchor(
    rectLeft = boxLeft,
    rectTop = boxTop,
    rectWidth = boxWidth,
    rectHeight = boxHeight,
    labelHalfHeight = labelHalfHeight,
    controlAngle = controlAngle,
    screenWidth = screenWidth,
    screenHeight = screenHeight,
    gap = gap,
    screenPad = screenPad
)