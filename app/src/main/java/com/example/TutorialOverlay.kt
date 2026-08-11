@file:Suppress("unused", "UnusedImports")

package com.example

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

/**
 * Sequential skip-or-do-it gesture tutorial.
 *
 * The overlay is intentionally **minimal and white**:
 *   - No big dimmed "menu" — the live camera stays fully visible behind.
 *   - No card / panel chrome — just a small translucent pill of text
 *     floating near the top of the screen and a tiny pair of animated
 *     white finger-dots in the centre showing what to do.
 *   - The user reads it as a transient coach-mark that lives or dies
 *     by their own finger, not as a modal dialog.
 *
 * Three states, each dismissed differently:
 *
 *   [Zoom]   — fires [onAdvance] the moment the user crosses pinch /
 *              vertical drag touch-slop on the overlay. The camera
 *              underneath then owns the rest of the gesture (the
 *              tutorial disappears mid-gesture, so the zoom itself
 *              still happens naturally).
 *   [Swipe]  — fires [onAdvance] on the first `onDragStart` of a
 *              horizontal sweep past touch-slop. Same mid-gesture
 *              handoff rationale.
 *   (absent) — overlay is not shown at all. The parent (CameraUi)
 *              uses this to know the tutorial is finished.
 *
 * System Back and tapping the small "skip" label both call
 * [onSkipAll] to dismiss every remaining step without performing the
 * gesture. Useful for power users who already know the controls.
 */
enum class TutorialStep { Zoom, Swipe }

/**
 * Per-step tutorial overlay.
 *
 * Three state outputs:
 *   - [onAdvance]         — fires once when the matching gesture is
 *                           detected; advances Zoom → Swipe → done.
 *   - [onZoomAction]      — fires alongside [onAdvance] DURING the
 *                           zoom step so the camera actually zooms
 *                           even if the underlying sibling pointerInput
 *                           doesn't pick up the rest of the motion
 *                           (Compose pointer-pipeline caveats).
 *   - [onSwipeAction]     — fires alongside [onAdvance] DURING the
 *                           swipe step with `+1` for a LEFT swipe
 *                           (next preset) or `-1` for a RIGHT swipe
 *                           (previous preset). Matches the convention
 *                           of the existing `detectHorizontalDragGestures`
 *                           block in the camera UI.
 *   - [onSkipAll]         — power-user escape from the entire tour.
 *
 * @param viewfinderBottomFraction the bottom edge of the live
 *        viewfinder, as a fraction of the total overlay height
 *        (0 = top, 1 = bottom). The zoom arrow's base is anchored to
 *        this edge so the cue reads as one bottom-to-top drag across
 *        the preview instead of running down into the controls deck
 *        below it. Falls back to 0.75 when the parent doesn't supply
 *        it.
 */
@Composable
fun TutorialOverlay(
    step: TutorialStep,
    onAdvance: () -> Unit,
    onSkipAll: () -> Unit,
    onZoomAction: () -> Unit = {},
    onSwipeAction: (direction: Int) -> Unit = { _ -> },
    viewfinderBottomFraction: Float = 0.75f
) {
    val haptic = LocalHapticFeedback.current

    // System back = skip-all. Power users shouldn't be forced through
    // the tour if they don't want it.
    BackHandler(onBack = onSkipAll)

    // One pointerInput handler for the whole overlay surface, keyed by
    // [step] so the appropriate detector is launched freshly when the
    // tour advances. We deliberately use a CUSTOM non-consuming
    // detector rather than Compose's `detectVerticalDragGestures` /
    // `detectHorizontalDragGestures` because those built-ins call
    // `change.consume()` at touch-slop — which would silently swallow
    // the same events from the camera's own zoom / swipe handlers
    // (they live in sibling `pointerInput` blocks on the same
    // composable surface, and Compose routes consumed events away
    // from competing detectors).
    //
    // With our non-consuming detector:
    //   1. Overlay detects slop on its own count, fires `onAdvance`.
    //   2. Overlay recomposes with the next `tutorialStep` (or `null`),
    //      its pointerInput is torn down.
    //   3. The camera's pointerInput underneath THIS VERY gesture is
    //      still alive and continues to receive the remaining motion
    //      events, so the same swipe also cycles the film preset and
    //      the same vertical drag also adjusts the zoom — the
    //      tutorial's dismissal does NOT cancel the action it teaches.
    val gestureDetector: Modifier = Modifier.pointerInput(step) {
        val orientation = when (step) {
            TutorialStep.Zoom -> Orientation.Vertical
            TutorialStep.Swipe -> Orientation.Horizontal
        }
        detectGestureWithoutConsuming(orientation) { directionSign ->
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            // Fire the action callback FIRST. ViewModel writes are
            // synchronous so the camera state updates in the same
            // frame the overlay starts its exit fade. Pair with the
            // recomposition triggered by onAdvance() so the user sees
            // the camera react AND the overlay disappear together.
            if (step == TutorialStep.Zoom) onZoomAction() else onSwipeAction(directionSign)
            onAdvance()
        }
    }

    Box(modifier = Modifier.fillMaxSize().then(gestureDetector)) {
        // NO top hint pill. Per the latest redesign: only the arrow
        // and the animating finger dot remain on screen — no text
        // overlay that would compete with the gesture cue for
        // attention. The arrow shape itself (vertical UP / horizontal
        // L+R) is self-documenting.
        //
        // NO bottom skip button / link either. Power users exit via
        // System Back (handled by the `BackHandler` at the top of
        // this composable); novices are expected to follow along
        // with the gesture. Removing the skip affordance removes a
        // mild visual flicker from the bottom of the viewfinder.

        // Animated gesture demo occupies the full canvas so the
        // arrow lands wherever the viewfinder is on the device.
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            when (step) {
                TutorialStep.Zoom -> ZoomDragDemo(viewfinderBottomFraction)
                TutorialStep.Swipe -> SwipeDemo()
            }
        }
    }
}

/**
 * The HintChip composable used to render the top-of-screen title +
 * subtitle pill. Was removed per the "no text overlay" redesign
 * — the arrow itself is now self-documenting. Kept the doc comment
 * as a marker for future maintainers wondering where the pill went.
 */

/**
 * Animated single-finger drag demo for the zoom step.
 *
 * The visual is intentionally MINIMAL — one vertical arrow that runs
 * from the BOTTOM EDGE of the live viewfinder UP to the VERTICAL
 * MIDDLE of the canvas, with one bright "live" white finger dot
 * moving continuously along the shaft. The arrow communicates
 * "THIS IS THE DIRECTION" instantly on first glance, and the dot
 * adds the "this is moving" affordance without obscuring the arrow
 * itself.
 *
 * Rendering layers (bottom → top):
 *   1. Soft white BLOOM — a wide, faint stroke that makes the arrow
 *      glow against dark scenes and stays legible on bright ones.
 *   2. Dark halo — thin black backing so the white core reads on
 *      sunlit walls / skies.
 *   3. White core — rounded (StrokeCap.Round) so the shaft ends in
 *      soft blobs instead of hard stubs.
 *   4. Arrowhead — an OPEN rounded chevron (stroked, round caps and
 *      joins) rather than a hard filled triangle, so the head shares
 *      the shaft's design language.
 *   5. Pulsing tip glow — a soft circle that "breathes" behind the
 *      arrowhead to draw the eye to where the finger should end.
 *   6. Finger dot — white core with a soft halo AND a thin dark ring
 *      so it stays defined even over white sky.
 */
@Composable
private fun ZoomDragDemo(viewfinderBottomFraction: Float = 0.75f) {
    val infinite = rememberInfiniteTransition(label = "zoomdrag")
    // 0f = base of arrow (bottom of stage), 1f = arrow tip (top).
    // Reverse mode so the loop oscillates continuously instead of
    // snapping back to the start each cycle.
    val progress by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1700, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "zoomdrag_progress"
    )
    // Independent slow "breathing" of the arrowhead glow so the eye
    // is pulled to the tip even when the finger dot is at the base.
    val tipGlow by infinite.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "zoomdrag_tipglow"
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val stageWPx = with(density) { maxWidth.toPx() }
        val stageHPx = with(density) { maxHeight.toPx() }
        val centerXPx = stageWPx / 2f

        // Arrow tip lands at the VERTICAL MIDDLE of the canvas (50%
        // down from top); the base sits on the BOTTOM EDGE of the
        // live viewfinder (a small margin keeps the rounded cap and
        // bloom from clipping), so the cue reads as one continuous
        // bottom-to-top drag across the whole viewfinder — NOT down
        // into the controls deck below it.
        val arrowBaseY =
            stageHPx * viewfinderBottomFraction - with(density) { 10.dp.toPx() }
        val arrowTipY = stageHPx * 0.50f
        val tipSizePx = with(density) { 26.dp.toPx() }
        val stemThicknessPx = with(density) { 5.dp.toPx() }
        val haloThicknessPx = stemThicknessPx + with(density) { 5.dp.toPx() }
        val fingerRadiusPx = with(density) { 13.dp.toPx() }
        val fingerHaloPx = with(density) { 24.dp.toPx() }
        // Stem starts a tip-height inside the chevron tip so the
        // arrowhead and shaft appear as one continuous shape.
        val stemTopY = arrowTipY + tipSizePx * 0.75f
        val stemBottomY = arrowBaseY

        // Live finger dot position along the arrow. progress=0 →
        // base; progress=1 → near tip.
        val fingerY = lerp(stemBottomY, arrowTipY + tipSizePx * 0.35f, progress)
        val fingerPos = Offset(centerXPx, fingerY)

        Canvas(modifier = Modifier.fillMaxSize()) {
            // ----- Bloom -----
            // Wide faint stroke behind everything so the arrow reads
            // as softly glowing on any scene.
            drawLine(
                color = Color.White.copy(alpha = 0.14f),
                start = Offset(centerXPx, stemTopY),
                end = Offset(centerXPx, stemBottomY),
                strokeWidth = with(density) { 26.dp.toPx() },
                cap = StrokeCap.Round
            )

            // ----- Arrow shaft: dark halo + white core -----
            // Rounded caps so the shaft ends in soft blobs instead
            // of flat stubs.
            drawLine(
                color = Color.Black.copy(alpha = 0.38f),
                start = Offset(centerXPx, stemTopY),
                end = Offset(centerXPx, stemBottomY),
                strokeWidth = haloThicknessPx,
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color.White,
                start = Offset(centerXPx, stemTopY),
                end = Offset(centerXPx, stemBottomY),
                strokeWidth = stemThicknessPx,
                cap = StrokeCap.Round
            )

            // ----- Arrowhead: open rounded chevron pointing UP -----
            // Stroked (not filled) with round caps and joins so the
            // head matches the shaft and reads as a modern caret.
            val cx = centerXPx
            val tipY = arrowTipY
            val headPath = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx - tipSizePx, tipY + tipSizePx)
                lineTo(cx, tipY)
                lineTo(cx + tipSizePx, tipY + tipSizePx)
            }
            drawPath(
                path = headPath,
                color = Color.Black.copy(alpha = 0.38f),
                style = Stroke(
                    width = haloThicknessPx,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
            drawPath(
                path = headPath,
                color = Color.White,
                style = Stroke(
                    width = stemThicknessPx,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // ----- Pulsing tip glow -----
            drawCircle(
                color = Color.White.copy(alpha = 0.10f + 0.08f * tipGlow),
                radius = with(density) { 24.dp.toPx() } + with(density) { 10.dp.toPx() } * tipGlow,
                center = Offset(cx, tipY)
            )

            // ----- Live finger dot: halo + core + dark ring -----
            // Drawn last so the dot sits on top of the shaft; the
            // thin dark ring keeps it defined even over bright sky.
            drawCircle(
                color = Color.White.copy(alpha = 0.18f),
                radius = fingerHaloPx,
                center = fingerPos
            )
            drawCircle(
                color = Color.White,
                radius = fingerRadiusPx,
                center = fingerPos
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.30f),
                radius = fingerRadiusPx + with(density) { 1.5.dp.toPx() },
                center = fingerPos,
                style = Stroke(width = with(density) { 2.dp.toPx() })
            )
        }
    }
}

/**
 * Animated horizontal-swipe demo for the style-swap step.
 *
 * Same design language as [ZoomDragDemo] but rotated 90°: one
 * horizontal arrow centred vertically, pointing LEFT (the primary
 * direction — LEFT reveals the next preset per the existing CameraUi
 * convention). A second, dimmer arrowhead on the RIGHT telegraphs
 * "and this direction works too". The finger dot oscillates along the
 * shaft so the user reads it as "in motion".
 *
 * Same rendering layers as the zoom demo: soft bloom, dark halo,
 * rounded white core, OPEN rounded chevrons at both ends, a pulsing
 * tip glow behind the PRIMARY (left) head, and a ring-defined finger
 * dot. The LEFT arrow is full opacity while the RIGHT arrow is at
 * 0.70 alpha so the eye lands on LEFT first.
 */
@Composable
private fun SwipeDemo() {
    val infinite = rememberInfiniteTransition(label = "swipe")
    // progress oscillates 0 → 1, mapping to just past the LEFT tip
    // → just before the RIGHT tip so the dot sweeps the full length.
    val progress by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "swipe_progress"
    )
    // Same breathing tip glow as the zoom demo, keyed to the PRIMARY
    // (left) head.
    val tipGlow by infinite.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "swipe_tipglow"
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val stageWPx = with(density) { maxWidth.toPx() }
        val stageHPx = with(density) { maxHeight.toPx() }
        val centerY = stageHPx / 2f

        // Symmetric horizontal margins from the canvas edges so the
        // arrow has equal visual weight left/right.
        val sideMarginPx = with(density) { 32.dp.toPx() }
        val leftTipX = sideMarginPx
        val rightTipX = stageWPx - sideMarginPx
        val tipHeightPx = with(density) { 22.dp.toPx() }
        val stemThicknessPx = with(density) { 5.dp.toPx() }
        val haloThicknessPx = stemThicknessPx + with(density) { 5.dp.toPx() }
        val fingerRadiusPx = with(density) { 13.dp.toPx() }
        val fingerHaloPx = with(density) { 24.dp.toPx() }
        // Stem starts a tip-height inside each tip so the arrowhead
        // joins the shaft cleanly.
        val stemLeftX = leftTipX + tipHeightPx * 0.75f
        val stemRightX = rightTipX - tipHeightPx * 0.75f

        val fingerX = lerp(stemLeftX, stemRightX, progress)
        val fingerPos = Offset(fingerX, centerY)

        Canvas(modifier = Modifier.fillMaxSize()) {
            // ----- Bloom -----
            drawLine(
                color = Color.White.copy(alpha = 0.14f),
                start = Offset(stemLeftX, centerY),
                end = Offset(stemRightX, centerY),
                strokeWidth = with(density) { 26.dp.toPx() },
                cap = StrokeCap.Round
            )

            // ----- Horizontal shaft: dark halo + white core -----
            drawLine(
                color = Color.Black.copy(alpha = 0.38f),
                start = Offset(stemLeftX, centerY),
                end = Offset(stemRightX, centerY),
                strokeWidth = haloThicknessPx,
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color.White,
                start = Offset(stemLeftX, centerY),
                end = Offset(stemRightX, centerY),
                strokeWidth = stemThicknessPx,
                cap = StrokeCap.Round
            )

            // ----- LEFT arrowhead (PRIMARY): open chevron, LEFT -----
            val leftHead = androidx.compose.ui.graphics.Path().apply {
                moveTo(leftTipX + tipHeightPx, centerY - tipHeightPx)
                lineTo(leftTipX, centerY)
                lineTo(leftTipX + tipHeightPx, centerY + tipHeightPx)
            }
            drawPath(
                path = leftHead,
                color = Color.Black.copy(alpha = 0.38f),
                style = Stroke(
                    width = haloThicknessPx,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
            drawPath(
                path = leftHead,
                color = Color.White,
                style = Stroke(
                    width = stemThicknessPx,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // ----- RIGHT arrowhead (SECONDARY): open chevron, RIGHT -----
            // Slightly dimmer so the convention-leading arrow (LEFT)
            // lands first.
            val rightHead = androidx.compose.ui.graphics.Path().apply {
                moveTo(rightTipX - tipHeightPx, centerY - tipHeightPx)
                lineTo(rightTipX, centerY)
                lineTo(rightTipX - tipHeightPx, centerY + tipHeightPx)
            }
            drawPath(
                path = rightHead,
                color = Color.Black.copy(alpha = 0.32f),
                style = Stroke(
                    width = haloThicknessPx,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
            drawPath(
                path = rightHead,
                color = Color.White.copy(alpha = 0.78f),
                style = Stroke(
                    width = stemThicknessPx,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // ----- Pulsing tip glow behind the PRIMARY head -----
            drawCircle(
                color = Color.White.copy(alpha = 0.10f + 0.08f * tipGlow),
                radius = with(density) { 24.dp.toPx() } + with(density) { 10.dp.toPx() } * tipGlow,
                center = Offset(leftTipX, centerY)
            )

            // ----- Live finger dot: halo + core + dark ring -----
            drawCircle(
                color = Color.White.copy(alpha = 0.18f),
                radius = fingerHaloPx,
                center = fingerPos
            )
            drawCircle(
                color = Color.White,
                radius = fingerRadiusPx,
                center = fingerPos
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.30f),
                radius = fingerRadiusPx + with(density) { 1.5.dp.toPx() },
                center = fingerPos,
                style = Stroke(width = with(density) { 2.dp.toPx() })
            )
        }
    }
}

/**
 * Linear interpolation helper used by [SwipeDemo] to map the loop
 * position `[0..1]` onto physical pixel X inside the stage.
 */
private fun lerp(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction

/**
 * Non-consuming touch-slop detector.
 *
 * Why this exists:
 *   Compose's `detectHorizontalDragGestures` /
 *   `detectVerticalDragGestures` call `change.consume()` the moment
 *   the touched pointer crosses touch-slop along the requested
 *   orientation. That consumption blocks the camera's own zoom and
 *   swipe handlers (sibling `pointerInput` blocks on the same
 *   composable surface) from seeing the very same events — so an
 *   overlay that fires its own dismissal via those built-ins would
 *   silently cancel the very gesture it teaches.
 *
 * This helper mirrors the slop-checking behaviour of those built-ins
 * but NEVER calls `change.consume()` on any event. We compute the
 * delta with `positionChangeIgnoreConsumed()` so sibling handlers
 * that DO consume don't double-count or hide motion from us, and we
 * fire [onTrigger] synchronously the instant our own cumulative drag
 * exceeds `viewConfiguration.touchSlop`. After that, we keep draining
 * the gesture (waiting for lift) so the overlay doesn't shade out
 * early — important because the parent composable dismisses on
 * `onTrigger` and the pointerInput block is then torn down cleanly.
 *
 * @param orientation axis to monitor (Horizontal or Vertical)
 * @param onTrigger   called exactly once per gesture, on the first
 *                    event whose cumulative position delta crosses
 *                    touch-slop. Receives the SIGN of that
 *                    accumulated delta as `+1` (right / down) or
 *                    `-1` (left / up). Callers use the sign to
 *                    decide whether the gesture cycles forward or
 *                    backward, zooms in or out, etc.
 */
private suspend fun PointerInputScope.detectGestureWithoutConsuming(
    orientation: Orientation,
    onTrigger: (directionSign: Int) -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val touchSlop = viewConfiguration.touchSlop
        var accumulated = 0f
        var fired = false
        var pointerId = down.id

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            // Find our specific pointer in this event batch so we
            // ignore other fingers landing simultaneously (multi-touch
            // should not contribute to the tutorial's slop budget).
            val change: PointerInputChange =
                event.changes.firstOrNull { it.id == pointerId } ?: break
            if (change.changedToUpIgnoreConsumed()) break

            // Use the *raw* position delta, not the consumed-aware
            // pan helper, so a sibling handler's `consume()` on the
            // same change can't trick us into thinking the user
            // hasn't moved yet.
            val delta = change.positionChangeIgnoreConsumed()
            val component = if (orientation == Orientation.Horizontal) delta.x else delta.y
            accumulated += component

            if (!fired && kotlin.math.abs(accumulated) >= touchSlop) {
                fired = true
                // Sign convention matches Compose's `positionChange`:
                //   horizontal +1 = right, -1 = left
                //   vertical   +1 = down,  -1 = up
                val directionSign = if (accumulated >= 0f) +1 else -1
                onTrigger(directionSign)
            }

            if (!change.pressed) break
        }
    }
}

/**
 * `awaitEachGesture` and `awaitFirstDown` are imported at the top of
 * the file (`androidx.compose.foundation.gestures.awaitEachGesture`
 * and `awaitFirstDown`) — they aren't re-exported locally because
 * they're public APIs and the file already pulls them in via the
 * standard import.
 */
