package com.example.zoom

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Screen position (dp) of the focal-length label's centre. */
internal data class FocalLabelAnchor(val x: Dp, val y: Dp)

/**
 * Centre point for the focal-length label floating just outside the zoom box.
 *
 * [boxLeft]/[boxTop] are the zoom box's TOP-LEFT corner and [boxWidth] /
 * [boxHeight] its size — the same `offset(x = boxLeft, y = boxTop)` origin the
 * outline Box draws from, so the two can never disagree about where the box
 * is.
 *
 * The activity is portrait-locked, so a sideways-held phone shows the whole
 * UI sideways: the zoom box stays axis-aligned in portrait/screen coordinates
 * while the viewer's "up" points along `(sinθ, -cosθ)` (the portrait RIGHT
 * edge at +90°, the portrait LEFT edge at -90°). The label rides
 * [controlAngle] so it reads upright for the viewer, and its anchor is
 * expressed relative to the box in that frame: start at the box centre and
 * push out along the viewer's up vector by the box's projected half-extent
 * along that direction, plus the portrait spec's gap (measured from the
 * label's outer edge to the box).
 *
 * Degenerates exactly to the portrait spec at θ = 0 (box top edge + [gap])
 * and lands on the box's portrait-right/left face at ±90°, so the number
 * always hugs the box instead of parking on the far edge of the viewfinder.
 * Because the up direction points at a screen edge once the device is
 * sideways (the viewfinder's side margin is thin), the reach is also capped
 * so the label's outer edge never leaves the screen.
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
): FocalLabelAnchor {
    val boxCenterX = boxLeft + boxWidth / 2f
    val boxCenterY = boxTop + boxHeight / 2f

    val radians = Math.toRadians(controlAngle.toDouble())
    val upX = sin(radians).toFloat()
    val upY = -cos(radians).toFloat()

    // Distance from the box centre to its boundary along the viewer's up
    // vector: for an axis-aligned rect that is the nearer of
    // halfWidth/|ux| and halfHeight/|uy| (a zero component gives Infinity,
    // so the other axis wins).
    val boxEdgeReach = minOf(
        (boxWidth / 2f) / abs(upX),
        (boxHeight / 2f) / abs(upY)
    )

    // Distance from the box centre to the screen edge along the same vector.
    val screenReachX =
        if (upX > 0f) (screenWidth - screenPad - boxCenterX) / upX
        else if (upX < 0f) (screenPad - boxCenterX) / upX
        else Float.POSITIVE_INFINITY.dp
    val screenReachY =
        if (upY > 0f) (screenHeight - screenPad - boxCenterY) / upY
        else if (upY < 0f) (screenPad - boxCenterY) / upY
        else Float.POSITIVE_INFINITY.dp

    // Ideal: the label's outer edge `gap` beyond the box's up-facing edge,
    // capped so the label stays on-screen when the box nearly fills the
    // viewfinder sideways.
    val reach = minOf(
        boxEdgeReach + gap - labelHalfHeight,
        minOf(screenReachX, screenReachY) - labelHalfHeight
    )

    return FocalLabelAnchor(
        x = boxCenterX + reach * upX,
        y = boxCenterY + reach * upY
    )
}
