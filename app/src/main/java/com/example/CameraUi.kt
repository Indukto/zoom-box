@file:Suppress(
    "unused",
    "UnusedImport",
    "UnusedImports",
    "RedundantQualifierName",
    "RemoveRedundantQualifierName",
    "RedundantSuppression"
)

package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.ExifInterface
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import android.os.Build
import android.util.Log
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.camera.core.ImageCapture
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FlashAuto
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.FlipCameraAndroid
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.GridOff
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextButton
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberAsyncImagePainter
import androidx.compose.ui.graphics.vector.ImageVector
import java.util.Locale
import com.example.zoom.AspectRatio
import com.example.color.CubeLut
import com.example.zoom.LensRole
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import java.io.File

private const val FLASH_BURST_DURATION_MS = 120L

/**
 * Per-pointer state kept by the passive full-screen swipe overlay.
 */
private class TrackedPointer(
    val start: Offset,
    val initialExposure: Float,
    val initialTemp: Float,
    val initialTint: Float,
    var moved: Boolean = false
)

/**
 * Draws a rule-of-thirds grid (4 lines at width/height thirds) inside [rect].
 * Shared between the inner zoom-box grid and the full-viewfinder grid so
 * their styling stays in lock-step.
 */
private fun DrawScope.drawThirdsGrid(
    rect: Rect,
    color: Color,
    strokeWidth: Float
) {
    val w = rect.width
    val h = rect.height
    val thirdW1 = rect.left + w / 3f
    val thirdW2 = rect.left + 2f * w / 3f
    val thirdH1 = rect.top + h / 3f
    val thirdH2 = rect.top + 2f * h / 3f
    drawLine(color = color, start = Offset(thirdW1, rect.top), end = Offset(thirdW1, rect.bottom), strokeWidth = strokeWidth)
    drawLine(color = color, start = Offset(thirdW2, rect.top), end = Offset(thirdW2, rect.bottom), strokeWidth = strokeWidth)
    drawLine(color = color, start = Offset(rect.left, thirdH1), end = Offset(rect.right, thirdH1), strokeWidth = strokeWidth)
    drawLine(color = color, start = Offset(rect.left, thirdH2), end = Offset(rect.right, thirdH2), strokeWidth = strokeWidth)
}

private fun filmPresetColor(preset: FilmPreset): Color = when (preset) {
    FilmPreset.WARM_PORTRAIT       -> Color(0xFFD4A56A)
    FilmPreset.MONOCHROME_400      -> Color(0xFF6B6B6B)
    FilmPreset.INSTANT_CLASSIC     -> Color(0xFF4A90B0)
    FilmPreset.CROSS_PROCESS       -> Color(0xFFC04040)
    FilmPreset.INSTANT_VINTAGE     -> Color(0xFF8B5E8B)
    FilmPreset.MOODY              -> Color(0xFF2C3E50)
    FilmPreset.MUTED_MEADOW       -> Color(0xFF7DCEA0)
    FilmPreset.SUNLIT_SPILL       -> Color(0xFFF39C12)
    // Soft pinkish-lavender — evokes the pastel / hazy mood of the preset
    // without colliding with the warm MUTED_MEADOW green or the saturated
    // SUNLIT_SPILL amber above it on the picker bar.
    FilmPreset.DREAMY              -> Color(0xFFB8A4C9)
    // Slightly darker than the surrounding chrome so the "no grade"
    // chip reads as a deliberate preset on the picker bar instead of
    // visually disappearing into the dim chrome of the rest of the row.
    FilmPreset.NORMAL              -> Color(0xFF9CA3AF)
}

private fun filmPresetEmoji(preset: FilmPreset): String = when (preset) {
    FilmPreset.WARM_PORTRAIT       -> "🌅"
    FilmPreset.MONOCHROME_400      -> "🌑"
    FilmPreset.INSTANT_CLASSIC     -> "📸"
    FilmPreset.CROSS_PROCESS       -> "🎞️"
    FilmPreset.INSTANT_VINTAGE     -> "🌆"
    FilmPreset.MOODY              -> "🌧️"
    FilmPreset.MUTED_MEADOW       -> "🌿"
    FilmPreset.SUNLIT_SPILL       -> "☀️"
    // Cloud — reads instantly as "soft, hazy, dreamy" without needing a
    // label, and stays distinct from the sunset/sun/moon glyphs above.
    FilmPreset.DREAMY              -> "☁️"
    FilmPreset.NORMAL              -> "📷"
}

// ─────────────────────────────────────────────────────────────────────────────
// Color-temperature & exposure controls
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Maps the app's normalized temperature value (-2 = cool .. +2 = warm) to a
 * color temperature in Kelvin. Photographically, warmer light = lower Kelvin,
 * so a positive temp lowers the K value (5600K daylight at neutral).
 */
private fun tempToKelvin(temp: Float): Int {
    val raw = 5600 - (temp * 825f)
    return (raw / 50f).toInt() * 50
}

/**
 * A custom rectangular slider with a multi-color gradient track, a white
 * indicator notch, a live value chip above the thumb, min/center/max ticks,
 * and double-tap-to-reset. Reused for both the white-balance and exposure
 * panels so they share a consistent look and feel.
 */
@Composable
private fun SpectrumSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    gradient: List<Color>,
    valueLabel: String,
    leftTick: String,
    centerTick: String,
    rightTick: String,
    modifier: Modifier = Modifier,
    step: Float? = null,   // when non-null, the value snaps to multiples of `step`
    trackHeight: Dp = 30.dp,
    doubleTapToReset: Boolean = true
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    var trackWidthPx by remember { mutableStateOf(1f) }
    val min = valueRange.start
    val max = valueRange.endInclusive
    val range = (max - min).coerceAtLeast(0.0001f)
    val fraction = ((value - min) / range).coerceIn(0f, 1f)

    val notchOffsetDp = with(density) { (trackWidthPx * fraction).toDp() }

    // Quantize a raw value to the step grid (if any), clamped to the range.
    fun snap(v: Float): Float {
        if (step == null || step <= 0f) return v.coerceIn(min, max)
        val snapped = kotlin.math.round(v / step) * step
        // Drop float drift so 0.1 steps stay clean (0.1, 0.2, ... not 0.30000004).
        return (kotlin.math.round(snapped * 1000f) / 1000f).coerceIn(min, max)
    }

    fun pxToValue(px: Float): Float {
        val f = (px / trackWidthPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
        return snap(min + f * range)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().height(22.dp), contentAlignment = Alignment.BottomStart) {
            // Value chip floating above the thumb
            Box(
                modifier = Modifier
                    .offset { IntOffset(x = (notchOffsetDp - 16.dp).roundToPx(), y = 0) }
                    .background(Color.White, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = valueLabel,
                    color = Color.Black,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Track + thumb + drag/tap handling
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .onGloballyPositioned { trackWidthPx = it.size.width.toFloat() }
        ) {
            // Gradient bar
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.horizontalGradient(gradient),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
            )

            // Center (zero) notch — subtle marker on the track
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(width = 2.dp, height = 14.dp)
                    .background(Color.White.copy(alpha = 0.35f))
            )

            // Thumb indicator notch
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset(x = (notchOffsetDp - 1.5.dp).roundToPx(), y = 0) }
                    .size(width = 3.dp, height = 38.dp)
                    .background(Color.White)
                    .border(1.dp, Color.Black.copy(alpha = 0.25f))
            )

            // Gesture layer covering the whole track: drag to scrub, tap to set,
            // double-tap to reset to neutral (0, clamped into range). Track width
            // is kept current by onGloballyPositioned above, so taps/drags can
            // convert touch x directly into a value.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(valueRange, step) {
                        detectTapGestures(
                            onTap = { offset ->
                                val v = pxToValue(offset.x)
                                onValueChange(v)
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            },
                            onDoubleTap = if (doubleTapToReset) {
                                {
                                    onValueChange(snap(0f))
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            } else null
                        )
                    }
                    .draggable(
                        orientation = androidx.compose.foundation.gestures.Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            // Track the last *snapped* value so we can tick the
                            // haptic exactly when crossing into a new step.
                            val target = snap(value + (delta / trackWidthPx.coerceAtLeast(1f)) * range)
                            // Use snap(value) to compare against the *current* state value.
                            // If they differ, we crossed a step boundary.
                            if (step != null && target != snap(value)) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            onValueChange(target)
                        },
                        onDragStarted = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                        startDragImmediately = true
                    )
            )
        }

        // Tick labels
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = leftTick, color = Color.White.copy(alpha = 0.55f), fontSize = 9.sp)
            Text(text = centerTick, color = Color.White.copy(alpha = 0.75f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(text = rightTick, color = Color.White.copy(alpha = 0.55f), fontSize = 9.sp)
        }
    }
}

@Composable
private fun PresetButton(
    onClick: () -> Unit,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (isSelected) Color(0xFF2C2C2E) else Color.Transparent)
            .border(1.dp, if (isSelected) Color.White else Color.White.copy(alpha = 0.25f), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
private fun ColorPlot(
    temperature: Float,
    tint: Float,
    onValueChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var size by remember { mutableStateOf(IntSize.Zero) }

    val xFraction = ((temperature + 2f) / 4f).coerceIn(0f, 1f)
    val yFraction = (1f - (tint + 2f) / 4f).coerceIn(0f, 1f)

    // pointerInput(Unit) launches its coroutine once and outlives a single
    // composition. Reading xFraction / yFraction directly inside the
    // awaitEachGesture block would freeze them at the values from the first
    // launch and reset the cursor to (0, 0) on every re-press. rememberUpdatedState
    // exposes the latest parameter values to the long-running coroutine without
    // restarting it on each state change (which would kill ongoing gestures).
    val currentXFraction by rememberUpdatedState(xFraction)
    val currentYFraction by rememberUpdatedState(yFraction)
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    // Snap-to-grid step for both axes. Picked so the 9x9 stop grid lines up
    // with the presets below (Auto = 0/0, Daylight = 0.5/0, Tungsten = -1.5/-0.5)
    // and gives 81 discrete positions inside the -2..+2 design space.
    val colorStep = 0.5f
    fun snap(value: Float): Float =
        (kotlin.math.round(value / colorStep) * colorStep).coerceIn(-2f, 2f)

    // Map a box-pixel coordinate to (temperature, tint). Each gesture
    // anchors the cursor at its current box position with a finger-based
    // offset, so consecutive touches don't teleport the selector.
    fun emitAtPosition(position: Offset) {
        if (size.width > 0 && size.height > 0) {
            val currentX = position.x.coerceIn(0f, size.width.toFloat())
            val currentY = position.y.coerceIn(0f, size.height.toFloat())
            val newTemp = snap((currentX / size.width.toFloat()) * 4f - 2f)
            val newTint = snap((1f - currentY / size.height.toFloat()) * 4f - 2f)
            currentOnValueChange(newTemp, newTint)
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                // Unified press-then-drag handler:
                //   1. Touch-down snaps the cursor instantly to the clicked
                //      cell (no touch-slop wait -- fires on awaitFirstDown).
                //   2. Each subsequent pointer event tracks the finger
                //      across cells, emitting a snapped position per step.
                //   3. On release the cursor stays where the finger lifted.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Unconditional consume: prevent sibling gesture detectors
                    // from receiving events even before size has been laid out.
                    down.consume()
                    // Capture the cursor's current box position plus the
                    // offset from where the finger pressed. The selector
                    // never resets between gestures -- only finger movement
                    // walks the cursor, by an amount equal to the finger's
                    // path. Repeat touches just re-anchor the offset.
                    val cursorStartX = currentXFraction * size.width.toFloat()
                    val cursorStartY = currentYFraction * size.height.toFloat()
                    val pressOffsetX = down.position.x - cursorStartX
                    val pressOffsetY = down.position.y - cursorStartY
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        emitAtPosition(Offset(
                            change.position.x - pressOffsetX,
                            change.position.y - pressOffsetY
                        ))
                        change.consume()
                    }
                }
            }
            .clip(RoundedCornerShape(12.dp))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val horizontalBrush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF87CEEB), // cool cyan/blue
                    Color(0xFFF8FAFC), // neutral white
                    Color(0xFFFBBF24)  // warm amber/orange
                ),
                start = Offset(0f, 0f),
                end = Offset(size.width.toFloat(), 0f)
            )

            val verticalBrush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFFEC4899), // magenta/pink
                    Color.Transparent,
                    Color(0xFF22C55E)  // green
                ),
                start = Offset(0f, 0f),
                end = Offset(0f, size.height.toFloat())
            )

            drawRect(brush = horizontalBrush)
            drawRect(brush = verticalBrush, alpha = 0.65f)

            // Snap grid: 8 faint internal lines per axis so the 0.5-step
            // cadence is visually discoverable. Drawn before the crosshair
            // and thumb so the cursor sits on top.
            val cellColor = Color.White.copy(alpha = 0.10f)
            val cellStroke = 0.5f
            val cells = 15 // 9 stops -> 8 internal lines
            for (i in 1 until cells) {
                val x = size.width.toFloat() * i / cells
                drawLine(
                    color = cellColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height.toFloat()),
                    strokeWidth = cellStroke
                )
            }
            for (i in 1 until cells) {
                val y = size.height.toFloat() * i / cells
                drawLine(
                    color = cellColor,
                    start = Offset(0f, y),
                    end = Offset(size.width.toFloat(), y),
                    strokeWidth = cellStroke
                )
            }

            val xPos = xFraction * size.width
            val yPos = yFraction * size.height

            val pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
            // Horizontal dashed line
            drawLine(
                color = Color.Black.copy(alpha = 0.45f),
                start = Offset(0f, yPos),
                end = Offset(size.width.toFloat(), yPos),
                strokeWidth = 1f,
                pathEffect = pathEffect
            )
            // Vertical dashed line
            drawLine(
                color = Color.Black.copy(alpha = 0.45f),
                start = Offset(xPos, 0f),
                end = Offset(xPos, size.height.toFloat()),
                strokeWidth = 1f,
                pathEffect = pathEffect
            )

            // Draw selection thumb (black circle with white border)
            drawCircle(
                color = Color.White,
                radius = 8.dp.toPx(),
                center = Offset(xPos, yPos)
            )
            drawCircle(
                color = Color.Black,
                radius = 6.dp.toPx(),
                center = Offset(xPos, yPos)
            )
        }
    }
}

/**
 * White balance (color temperature & tint) panel. Shows a live Kelvin/tint readout and a
 * 2D color-plot space with preset buttons.
 */
@Composable
private fun WhiteBalancePanel(
    temperature: Float,
    tint: Float,
    onValueChange: (Float, Float) -> Unit,
    headerActions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}
) {
    val kValue = tempToKelvin(temperature)
    val tintInt = (tint * 10).toInt()

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "COLOR BALANCE",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            headerActions()
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Color Plot with text label centered above it
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "${kValue}K ${if (tintInt >= 0) " $tintInt" else "$tintInt"}",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                ColorPlot(
                    temperature = temperature,
                    tint = tint,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                )
            }

            // Right: Row of preset buttons
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Auto preset (A)
                PresetButton(
                    onClick = { onValueChange(0f, 0f) },
                    isSelected = temperature == 0f && tint == 0f
                ) {
                    Text(
                        text = "A",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Daylight preset (Sun)
                PresetButton(
                    onClick = { onValueChange(0.5f, 0.5f) },
                    isSelected = temperature == 0.5f && tint == 0.5f
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WbSunny,
                        contentDescription = "Daylight",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Tungsten preset (Bulb)
                PresetButton(
                    onClick = { onValueChange(-1.5f, -0.5f) },
                    isSelected = temperature == -1.5f && tint == -0.5f
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lightbulb,
                        contentDescription = "Incandescent",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * Exposure compensation panel. Shows a live EV readout and a brightness ramp.
 */
@Composable
private fun ExposurePanel(
    exposure: Float,
    onValueChange: (Float) -> Unit,
    headerActions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}
) {
    // Locale-aware so a German locale sees "1,5" rather than "1.5".
    val evLabel = if (exposure >= 0) "+${String.format(Locale.getDefault(), "%.1f", exposure)}" else String.format(Locale.getDefault(), "%.1f", exposure)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "EXPOSURE",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "$evLabel EV",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif
                )
                headerActions()
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        SpectrumSlider(
            value = exposure,
            onValueChange = onValueChange,
            valueRange = -3f..3f,
            gradient = listOf(Color(0xFF52525B), Color(0xFF52525B)),
            valueLabel = "${evLabel}EV",
            leftTick = "-3",
            centerTick = "0",
            rightTick = "+3",
            step = 0.1f,   // snap to 1/10 EV stops like a typical camera
            trackHeight = 18.dp,
            doubleTapToReset = false
        )
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Floating control surface (Morph)
// ────────────────────────────────────────────────────────────────────────────

/**
 * Three discrete visual modes the floating control surface can occupy.
 * [BUBBLE] is the compact 3-button row (temperature + lens + exposure).
 * [COLOR] / [EXPOSURE] replace it with the expanded settings panel; both
 * anchored at the same bottom edge so the bubble doesn't get shoved
 * downward when the panel opens.
 */
private enum class MorphMode { BUBBLE, COLOR, EXPOSURE }

/**
 * Shared chrome (background fill + faint border + padded 300 dp slot)
 * wrapped around both expanded panels. Keeping both panels inside the
 * same wrapper makes their visual weight match exactly so the morph
 * between them stays symmetric.
 */
@Composable
private fun MorphedPanelChrome(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .background(Color(0xF21E1E1E), RoundedCornerShape(18.dp))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .width(300.dp)
    ) {
        content()
    }
}

/**
 * Compact icon button shown in a panel's title row. Smaller than the
 * bubble's tap targets so the title stays legible when two of these sit
 * next to the EV readout in the ExposurePanel header.
 */
@Composable
private fun MorphedPanelHeaderButton(
    icon: ImageVector,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(24.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = "Close",
            tint = Color.White.copy(alpha = 0.75f),
            modifier = Modifier.size(14.dp)
        )
    }
}

/**
 * The compact 3-button bubble row. Identical visual to the inline row that
 * lived at the bottom of the viewfinder before the morph refactor; extracted
 * so it can be swapped in/out of the same composable slot as the expanded
 * settings panels.
 */
@Composable
private fun FloatingBubbleRow(
    effectiveFocalLength: Int,
    temperature: Float,
    tint: Float,
    exposure: Float,
    isFrontCamera: Boolean = false,
    onTemperatureClick: () -> Unit,
    onLensClick: () -> Unit,
    onExposureClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(22.dp))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(22.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        IconButton(
            onClick = onTemperatureClick,
            modifier = Modifier.size(44.dp).testTag("bubble_temperature_button")
        ) {
            Icon(
                imageVector = Icons.Rounded.Thermostat,
                contentDescription = "Temperature",
                tint = if (temperature != 0f || tint != 0f) Color(0xFFFBBF24) else Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
        Box(
            modifier = Modifier
                .height(40.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (isFrontCamera) Color.White.copy(alpha = 0.06f)
                    else Color.White.copy(alpha = 0.15f)
                )
                .clickable(enabled = !isFrontCamera) { onLensClick() }
                .padding(horizontal = 14.dp)
                .testTag("bubble_lens_button"),
            contentAlignment = Alignment.Center
        ) {
            if (isFrontCamera) {
                // Selfie camera has only one lens — show a fixed indicator
                // instead of one of the back-camera focal lengths. If we
                // left `effectiveFocalLength.toString()` here the user
                // would still see the 13/24/116 cycle when tapping a stale
                // recomposition path. cycleLens() also short-circuits
                // when isFrontCamera so the click is a no-op either way.
                Text(
                    text = "FRONT",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            } else {
                Text(
                    text = effectiveFocalLength.toString(),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .clickable { onExposureClick() }
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .testTag("bubble_exposure_button"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.WbSunny,
                contentDescription = "Exposure",
                tint = if (exposure != 0f) Color(0xFFFBBF24) else Color.White,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = exposure.toInt().toString(),
                color = if (exposure != 0f) Color(0xFFFBBF24) else Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraUi(
    viewModel: CameraViewModel = viewModel()
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var showSettingsPage by remember { mutableStateOf(false) }

    // TESTING: two-step gesture tutorial, sequential (ZoomBox first,
    // swipe-to-change-style second), and currently always-on so QA can
    // repeatedly validate the detection thresholds. `null` means "no
    // tutorial visible" (settled state after both gestures are
    // completed or the user taps skip). Initialised to Zoom so the
    // very first launch shows the pinch demo. Flip the initial value
    // to `null` once the gesture detection is approved.
    var tutorialStep by remember { mutableStateOf<TutorialStep?>(TutorialStep.Zoom) }

    // Current zoom ratio, read once per composition. Used to compute
    // the new zoom after the user performs the tutorial's vertical
    // drag: we apply a +20% bump (zoom-in-by-tactile, matching the
    // bottom-to-top gesture direction the user requested) so the
    // camera visibly reacts to the gesture instead of feeling frozen.
    val currentZoomRatio by viewModel.digitalZoomRatio.collectAsState()

    // Aspect ratio drives the viewfinder box geometry (mirrored in
    // the tutorial section below) so the tutorial arrow's base can be
    // pinned to the actual bottom edge of the live viewfinder.
    val aspectRatio by viewModel.aspectRatio.collectAsState()

    val cameraPermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)

    // Picking the right photo-read permission per platform:
    //   - Android 13 (TIRAMISU, API 33) and up: READ_MEDIA_IMAGES
    //   - Android 10..12 (Q..S_V2, API 29..32): READ_EXTERNAL_STORAGE
    // The manifest declares both with the right sdk-version gates, so older
    // devices install cleanly without seeing READ_MEDIA_IMAGES in the
    // Play listing and newer devices don't see the deprecated
    // READ_EXTERNAL_STORAGE. Without this permission granted, the gallery
    // still works for photos inserted by THIS install of the app (we own
    // those MediaStore rows via implicit app-uid ownership); granting it
    // extends the gallery to foreign photos in Pictures/ZoomBoxCamera/
    // and crucially to pre-reinstall rows (the OS disowns pre-reinstall rows
    // when the UID changes, so they need explicit read access).
    val mediaPermissionState = rememberPermissionState(
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
            android.Manifest.permission.READ_MEDIA_IMAGES
        else
            android.Manifest.permission.READ_EXTERNAL_STORAGE
    )

    // Don't gate the camera UI on this permission — camera is the primary
    // feature and works regardless. Key the effect on BOTH media and camera
    // permission state so it re-runs when either changes (otherwise the
    // "wait for camera before prompting media" early-return below would
    // silently lock out the media dialog forever on a cold launch: camera
    // isn't granted at first composition → early-return → camera flips to
    // granted → the keyed status didn't change → effect never re-runs).
    // Always re-scan at the end so granting mid-session takes effect immediately.
    LaunchedEffect(mediaPermissionState.status, cameraPermissionState.status.isGranted) {
        if (cameraPermissionState.status.isGranted &&
            !mediaPermissionState.status.isGranted &&
            !mediaPermissionState.status.shouldShowRationale
        ) {
            // Defer until camera is granted so we don't pre-empt it.
            // The "don't auto-resurface rationale" branch is the same condition
            // (shouldShowRationale == true means the user previously denied),
            // so the user gets exactly one prompt per install — never again.
            mediaPermissionState.launchPermissionRequest()
        }
        viewModel.loadPhotos(context)
    }

    LaunchedEffect(Unit) {
        // Small delay so gallery I/O doesn't compete with camera init
        kotlinx.coroutines.delay(100.milliseconds)
        viewModel.loadPhotos(context)
    }

    // Refresh the gallery whenever the activity returns to the foreground.
    // Most external-gallery changes are already covered by the MediaStore
    // ContentObserver installed in CameraViewModel, but this is a cheap
    // belt-and-braces fallback: photos added to Pictures/ZoomBoxCamera/ by a
    // non-MediaStore path (USB MTP, ADB `cp`, OEM auto-backup restorers, etc.)
    // wouldn't fire a MediaStore notify and the filmstrip would stay stale
    // until the next capture or relaunch.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.loadPhotos(context)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF121212)
    ) {
        if (cameraPermissionState.status.isGranted) {
            // FIX: Black viewfinder after returning from settings. Previously this
            // branch sibling-swapped SettingsScreen ↔ CameraActiveScreen via an
            // `if (showSettingsPage)` conditional. Swapping un-mounted
            // CameraPreviewView, which disposed its LutPreviewView AndroidView,
            // its `remember`-ed PreviewSessionManager, and its CameraX
            // bindings. CameraX `bindToLifecycle` was registered against the
            // activity's LifecycleOwner (still RESUMED across settings nav),
            // so the implicit unbind was not clean — the HAL reported
            // `cancelRepeatingRequest() call failed: ILLEGAL_ARGUMENT`,
            // `ConsumerBase abandoned`, and `CameraService::connect evicting
            // conflicting client for camera ID 1` (logged in the bug report).
            // On re-mount the new CameraPreviewView created a fresh
            // PreviewSessionManager whose `currentPreview/currentImageCapture/
            // currentLogicalCameraId` were null, so the recovery branch inside
            // PreviewSessionManager.bindPreview() had nothing to restore from.
            // The first rebind raced against the HAL's still-tearing-down
            // device nodes, lost, and left the viewfinder black. The lens-switch
            // workaround succeeded because by the next user-triggered bind
            // (~hundreds of ms later) the HAL had finished settling.
            //
            // Keeping CameraActiveScreen perpetually mounted under the
            // overlay means the camera session is never torn down across the
            // settings navigation — there's no disposal/race to recover
            // from, and the viewfinder is live the instant the overlay
            // closes.
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                // Viewfinder-bottom fraction, mirrored from the exact
                // geometry CameraActiveScreen computes internally
                // (92% width, aspect-ratio height, 56.dp top inset,
                // 200.dp bottom-deck reserve, vertically centred in
                // the remaining band). Kept in sync deliberately: the
                // tutorial arrow's base should sit on the bottom edge
                // of the LIVE VIEWFINDER, not on the bottom of the
                // app — the tutorial canvas extends past the
                // viewfinder down into the bottom deck (shutter /
                // filmstrip), so anchoring to the screen edge would
                // put the arrow far below the preview.
                val vfWidthRaw = maxWidth * 0.92f
                val vfHeightRaw = vfWidthRaw * aspectRatio.heightToWidth
                val availableHeight =
                    (maxHeight - 56.dp - 200.dp).coerceAtLeast(120.dp)
                val vfWidth: Dp
                val vfHeight: Dp
                if (vfHeightRaw > availableHeight) {
                    vfHeight = availableHeight
                    vfWidth = availableHeight / aspectRatio.heightToWidth
                } else {
                    vfWidth = vfWidthRaw
                    vfHeight = vfHeightRaw
                }
                val vfTop = 56.dp + (availableHeight - vfHeight) / 2f
                val viewfinderBottomFraction =
                    (vfTop + vfHeight).value / maxHeight.value

                CameraActiveScreen(
                    viewModel = viewModel,
                    onOpenSettings = { showSettingsPage = true }
                )

                // AnimatedVisibility REMOVES SettingsScreen from composition
                // only after the exit animation completes; CameraActiveScreen
                // sits in the Box OUTSIDE AnimatedVisibility so its lifecycle
                // is never tied to `showSettingsPage`. Slide-from-right + a
                // short fade matches the conventional Android "new screen
                // entering" idiom; the camera underneath reads as a steady
                // surface rather than a blink because the overlay is opaque.
                AnimatedVisibility(
                    visible = showSettingsPage,
                    enter = fadeIn(tween(durationMillis = 220)) +
                            slideInHorizontally(
                                animationSpec = tween(durationMillis = 280),
                                initialOffsetX = { it }
                            ),
                    exit = fadeOut(tween(durationMillis = 200)) +
                           slideOutHorizontally(
                               animationSpec = tween(durationMillis = 240),
                               targetOffsetX = { it }
                           )
                ) {
                    SettingsScreen(
                        viewModel = viewModel,
                        onClose = { showSettingsPage = false }
                    )
                }

                // Sequential gesture tutorial. Each step is a thin
                // coach-mark that floats above the live viewfinder
                // WITHOUT dimming it, advances on the matching gesture,
                // and disappears completely (no second step rendered)
                // once both steps have been completed or the user taps
                // skip. TutorialStep.Zoom must be detected before
                // TutorialStep.Swipe becomes visible.
                AnimatedVisibility(
                    visible = tutorialStep != null,
                    enter = fadeIn(tween(durationMillis = 240)),
                    exit = fadeOut(tween(durationMillis = 200))
                ) {
                    val step = tutorialStep ?: return@AnimatedVisibility
                    TutorialOverlay(
                        step = step,
                        // Anchor the demo arrow's base to the bottom
                        // edge of the live viewfinder (see the mirror
                        // computation at the top of this Box).
                        viewfinderBottomFraction = viewfinderBottomFraction,
                        // When a step completes, advance along the
                        // fixed Zoom → Swipe → none sequence. We do
                        // NOT honor the user's preferred "next" — the
                        // order is canonical so the onboarding always
                        // teaches zoom first.
                        onAdvance = {
                            tutorialStep = when (step) {
                                TutorialStep.Zoom -> TutorialStep.Swipe
                                TutorialStep.Swipe -> null
                            }
                        },
                        // Force a +20% zoom-in step on the camera so
                        // the gesture demonstrably does something
                        // even if Compose's pointer-pipeline drops
                        // the rest of the motion after the overlay
                        // unmounts. Bounded against the camera's own
                        // MIN/MAX so we never push past the hardware.
                        // Via the ViewModel rather than direct
                        // CameraControl so the box-scale derivation in
                        // `recalculateState()` (the chain that snaps
                        // the on-screen zoom-box rect) stays in sync.
                        onZoomAction = {
                            viewModel.setZoom(currentZoomRatio * 1.20f)
                        },
                        // Cycle the film preset directly. Direction
                        // convention matches the camera's own
                        // `detectHorizontalDragGestures` block so
                        // LEFT → next, RIGHT → prev.
                        onSwipeAction = { direction ->
                            viewModel.cycleCameraPreset(direction)
                        },
                        // Either skip button drops the user straight
                        // into the camera. Power users shouldn't be
                        // forced through two animations.
                        onSkipAll = { tutorialStep = null }
                    )
                }
            }
        } else {
            CameraPermissionOnboarding(
                onRequestPermission = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    cameraPermissionState.launchPermissionRequest()
                }
            )
        }
    }
}

@Composable
fun CameraPermissionOnboarding(
    onRequestPermission: () -> Unit
) {
    // ── Staggered entrance animation ──────────────────────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "splash_pulse")
    val iconPulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "icon_pulse"
    )
    val buttonPulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, delayMillis = 400, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "button_pulse"
    )

    // Track whether each element has entered for staggered reveal
    var revealIcon by remember { mutableStateOf(false) }
    var revealTitle by remember { mutableStateOf(false) }
    var revealTagline by remember { mutableStateOf(false) }
    var revealDesc by remember { mutableStateOf(false) }
    var revealButton by remember { mutableStateOf(false) }
    var revealFooter by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        revealIcon = true
        kotlinx.coroutines.delay(180.milliseconds)
        revealTitle = true
        kotlinx.coroutines.delay(160.milliseconds)
        revealTagline = true
        kotlinx.coroutines.delay(160.milliseconds)
        revealDesc = true
        kotlinx.coroutines.delay(200.milliseconds)
        revealButton = true
        kotlinx.coroutines.delay(200.milliseconds)
        revealFooter = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
    ) {
        // ── Subtle radial gradient overlay ────────────────────────────────
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFF59E0B).copy(alpha = 0.06f),
                        Color.Transparent
                    ),
                    center = Offset(size.width / 2f, size.height * 0.42f),
                    radius = size.maxDimension * 0.7f
                )
            )
        }

        // ── Decorative film-frame borders ─────────────────────────────────
        // Top frame line
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(3.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            Color(0xFFF59E0B).copy(alpha = 0.3f),
                            Color(0xFFF59E0B).copy(alpha = 0.5f),
                            Color(0xFFF59E0B).copy(alpha = 0.3f),
                            Color.Transparent
                        )
                    )
                )
        )
        // Sprocket holes (top)
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp)
                .fillMaxWidth(0.85f),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            repeat(12) {
                Box(
                    modifier = Modifier
                        .size(6.dp, 4.dp)
                        .background(Color(0xFFF59E0B).copy(alpha = 0.15f), RoundedCornerShape(1.dp))
                )
            }
        }

        // Bottom frame line
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(3.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            Color(0xFFF59E0B).copy(alpha = 0.3f),
                            Color(0xFFF59E0B).copy(alpha = 0.5f),
                            Color(0xFFF59E0B).copy(alpha = 0.3f),
                            Color.Transparent
                        )
                    )
                )
        )
        // Sprocket holes (bottom)
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
                .fillMaxWidth(0.85f),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            repeat(12) {
                Box(
                    modifier = Modifier
                        .size(6.dp, 4.dp)
                        .background(Color(0xFFF59E0B).copy(alpha = 0.15f), RoundedCornerShape(1.dp))
                )
            }
        }

        // ── Main content column ───────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Icon ──────────────────────────────────────────────────────
            AnimatedVisibility(
                visible = revealIcon,
                enter = fadeIn(tween(500, easing = EaseInOutCubic)) +
                        slideInVertically(tween(500, easing = EaseInOutCubic)) { it / 2 }
            ) {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .scale(iconPulse)
                        .background(Color(0xFF232323), CircleShape)
                        .border(2.dp, Color(0xFFF59E0B), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CameraAlt,
                        contentDescription = "Retro Camera Icon",
                        tint = Color(0xFFF59E0B),
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(36.dp))

            // ── Title ─────────────────────────────────────────────────────
            AnimatedVisibility(
                visible = revealTitle,
                enter = fadeIn(tween(500, easing = EaseInOutCubic)) +
                        slideInVertically(tween(500, easing = EaseInOutCubic)) { it / 2 }
            ) {
                Text(
                    text = "ZOOM CAMERA",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 3.sp,
                    fontFamily = FontFamily.Serif
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Tagline ───────────────────────────────────────────────────
            AnimatedVisibility(
                visible = revealTagline,
                enter = fadeIn(tween(500, easing = EaseInOutCubic)) +
                        slideInVertically(tween(500, easing = EaseInOutCubic)) { it / 2 }
            ) {
                Text(
                    text = "RETRO FILM CAMERA",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color(0xFFF59E0B).copy(alpha = 0.7f),
                    letterSpacing = 4.sp,
                    fontFamily = FontFamily.Serif
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Description ───────────────────────────────────────────────
            AnimatedVisibility(
                visible = revealDesc,
                enter = fadeIn(tween(600, easing = EaseInOutCubic)) +
                        slideInVertically(tween(600, easing = EaseInOutCubic)) { it / 2 }
            ) {
                Text(
                    text = "Capture vintage film-styled photos with our signature zoom box and warm retro filters. Grant camera access to begin.",
                    fontSize = 15.sp,
                    color = Color(0xFF9CA3AF),
                    textAlign = TextAlign.Center,
                    lineHeight = 24.sp,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }

            Spacer(modifier = Modifier.height(48.dp))

            // ── Button ────────────────────────────────────────────────────
            AnimatedVisibility(
                visible = revealButton,
                enter = fadeIn(tween(600, easing = EaseInOutCubic)) +
                        slideInVertically(tween(600, easing = EaseInOutCubic)) { it / 2 }
            ) {
                Box(
                    modifier = Modifier
                        .scale(buttonPulse)
                        .fillMaxWidth(0.7f)
                        .height(56.dp)
                        .clip(RoundedCornerShape(30.dp))
                        .background(
                            brush = Brush.horizontalGradient(
                                listOf(
                                    Color(0xFFF59E0B),
                                    Color(0xFFD97706)
                                )
                            )
                        )
                        .clickable { onRequestPermission() }
                        .testTag("enable_camera_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "ENABLE CAMERA",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        letterSpacing = 1.5.sp,
                        color = Color.Black
                    )
                }
            }
        }

        // ── Version footer ────────────────────────────────────────────────
        AnimatedVisibility(
            visible = revealFooter,
            enter = fadeIn(tween(800))
        ) {
            Text(
                // Sourced from `versionName` in app/build.gradle.kts via
                // BuildConfig.VERSION_NAME. Bump the gradle line and the
                // splash footer reacts — see AppVersion.kt for the single
                // source-of-truth story.
                text = "Zoom Cam · ${AppVersion.display}",
                color = Color(0xFFF59E0B).copy(alpha = 0.25f),
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                fontFamily = FontFamily.Serif,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 36.dp)
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun CameraActiveScreen(
    viewModel: CameraViewModel,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val selectedLensRole by viewModel.selectedLensRole.collectAsState()
    val effectiveFocalLength by viewModel.effectiveFocalLength.collectAsState()
    val digitalZoomRatio by viewModel.digitalZoomRatio.collectAsState()
    val exposure by viewModel.exposure.collectAsState()
    val temperature by viewModel.temperature.collectAsState()
    val tint by viewModel.tint.collectAsState()
    val flashMode by viewModel.flashMode.collectAsState()
    val isFrontCamera by viewModel.isFrontCamera.collectAsState()
    val capturedPhotos by viewModel.capturedPhotos.collectAsState()
    val selectedPhoto by viewModel.selectedPhoto.collectAsState()
    val boxScale by viewModel.boxScale.collectAsState()
    val showGridLines by viewModel.showGridLines.collectAsState()
    val aspectRatio by viewModel.aspectRatio.collectAsState()

    // Cross-fade the grid in/out so flipping the aux toggle never reads as a hard snap.
    val gridAlpha by animateFloatAsState(
        targetValue = if (showGridLines) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "grid_alpha"
    )

    val showTempSlider by viewModel.showTemperatureSlider.collectAsState()
    val showExpSlider by viewModel.showExposureSlider.collectAsState()
    val isCapturing by viewModel.isCapturing.collectAsState()
    // Drives ONLY the shutter button's visual scale and click-debounce.
    // Distinct from `isCapturing` which covers the entire
    // bitmap-decode → EXIF → crop → LUT → encode → save window — that's
    // why the previous implementation felt "stuck down for 1–3 seconds
    // after the click". `captureInFlight` flips back to false the moment
    // the camera hardware hands back the image (synchronously, inside
    // `processAndSavePhoto.top` for the JPEG path and inside the
    // Camera2 callbacks for the RAW path), so the button releases the
    // instant the picture is captured while the heavy work continues in
    // the background.
    val captureInFlight by viewModel.captureInFlight.collectAsState()

    val rawModeEnabled by viewModel.rawModeEnabled.collectAsState()
    val activeExtension by viewModel.activeExtension.collectAsState()
    val activePreset by viewModel.activePreset.collectAsState()
    val settingsLoaded by viewModel.settingsLoaded.collectAsState()
    // NOTE: Film-Style picker scroll position is intentionally NOT
    // collected via `collectAsState`. Doing so would subscribe this whole
    // composable to a StateFlow that mutates on every scroll tick, which
    // would re-launch the LazyRow on each frame of an inertia fling.
    // The seed value is captured once per sheet open (see below) and the
    // save path operates through `snapshotFlow`.

    // Load the active preset's LUT for the live viewfinder GL shader.
    var previewLut by remember { mutableStateOf<CubeLut?>(null) }
    LaunchedEffect(activePreset) {
        previewLut = withContext(Dispatchers.IO) {
            viewModel.loadLut(context, activePreset)
        }
    }

    val mainExecutor = ContextCompat.getMainExecutor(context)
    var activeImageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    val coroutineScope = rememberCoroutineScope()
    var timerCountdown by remember { mutableStateOf(-1) }

    // Probe OEM extension availability whenever the lens switches (or on first
    // entry). Extensions are per-logical-camera, so re-query on every rebinding.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(selectedLensRole, isFrontCamera) {
        if (isFrontCamera) return@LaunchedEffect
        // Use the already-cached lens catalog instead of re-enumerating
        val catalog = viewModel.lensCatalogResult ?: return@LaunchedEffect
        val targetProfile = when (selectedLensRole) {
            LensRole.ULTRA_WIDE -> catalog.ultraWide
            LensRole.PRIMARY -> catalog.primary
            LensRole.TELE -> catalog.tele
        } ?: return@LaunchedEffect
        val providerFuture = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context)
        val provider = try { providerFuture.get() } catch (e: Exception) { return@LaunchedEffect }
        viewModel.probeExtensions(context, provider, targetProfile.logicalCameraId, false, lifecycleOwner)
    }

    var flashFlashActive by remember { mutableStateOf(false) }
    LaunchedEffect(flashFlashActive) {
        if (flashFlashActive) {
            delay(FLASH_BURST_DURATION_MS.milliseconds)
            flashFlashActive = false
        }
    }

    // The viewfinder Box was previously BoxWithConstraints here so its children's
    // .width/.height/.offset could read maxWidth/maxHeight synchronously inside
    // the same measure phase. Compose's lint flags BoxWithConstraints with a
    // "scope is not used" warning whenever the scope lives inside a single
    // composition, so instead we cache the measured size in state and read
    // it back as `totalWidth` / `totalHeight`. The first composition pass uses
    // a placeholder size and pops into the real layout on the very next frame,
    // which is imperceptible.
    val parentSize = remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val totalWidth = with(density) { parentSize.value.width.toDp() }
    val totalHeight = with(density) { parentSize.value.height.toDp() }
    Box(
        modifier = Modifier.fillMaxSize().onSizeChanged { parentSize.value = it }
    ) {

        // Viewfinder bounds — width fixed at 92% of screen, height adapts to
        // the selected aspect ratio. With 4:3 (height/width = 1.35) the box is
        // 1.23× its width, with 3:2 (1.5) it's 1.38×, and with 1:1 it's exactly
        // the width. The zoom-box clamp + the Canvas overlay both keep working
        // unchanged because they already size themselves from vfWidth / aspectRatio.
        // The viewfinder + bottom deck + floating bubble stay anchored to these
        // bounds in every device orientation; the activity rotates freely but the
        // camera UI does NOT reposition. Only the SettingsScreen (a sibling of
        // this composable at the top of CameraUi()) handles landscape so the
        // setting icons adapt to a wider screen naturally.
        // Top inset — minimum gap between the screen's top edge and the
        // viewfinder, reserving room for the settings button / status area.
        val topInset = 56.dp

        // Height-aware clamp so the viewfinder + bubble + bottom deck stay
        // within the available screen height in any orientation. There's no
        // `isLandscape` detection: the clamp is purely height-driven and works
        // identically in portrait or landscape. In portrait, the natural vfH
        // (vfW × heightToWidth) is short enough that the original 92%-wide
        // form is selected. In landscape, the natural vfH exceeds the screen
        // height so we re-derive vfW from the clamped vfH and re-centre
        // horizontally.
        val vfWidthRaw = totalWidth * 0.92f
        val vfHeightRaw = vfWidthRaw * aspectRatio.heightToWidth
        // Reserve 200 dp at the bottom for the bubble (slider popup may open
        // upward another ~120 dp) + the two-row bottom deck (~140 dp) so they
        // never overlap the viewfinder in any orientation.
        val reservedBottom = 200.dp
        val availableHeight = (totalHeight - topInset - reservedBottom).coerceAtLeast(120.dp)
        val vfWidth: Dp
        val vfHeight: Dp
        if (vfHeightRaw > availableHeight) {
            vfHeight = availableHeight
            vfWidth = vfHeight / aspectRatio.heightToWidth
        } else {
            vfWidth = vfWidthRaw
            vfHeight = vfHeightRaw
        }
        val vfX = (totalWidth - vfWidth) / 2f

        // Vertically center the viewfinder between the top inset (settings /
        // status area) and the bottom UI deck, instead of pinning it to the
        // top edge of the screen. In landscape the height clamp makes
        // vfHeight == availableHeight, so vfTop naturally collapses back to
        // topInset (the viewfinder already fills the whole available region).
        val vfTop = topInset + (availableHeight - vfHeight) / 2f

        // ─────────────────────────────────────────────────────────────────
        // Preset-change toast state (declared ahead of the viewfinder Box
        // because `CameraPreviewView`'s modifier-chain pointerInput below
        // writes to these on a horizontal-fling fire).
        // ─────────────────────────────────────────────────────────────────
        // Invariant: `toastPresetSnapshot` is NEVER null. Driving
        // visibility from a separate Boolean avoids the AnimatedVisibility-
        // exit NPE a nullable + `!!` design hit earlier. A rapid
        // "next, next, next" sequence bumps `toastEpoch` each time so
        // the LaunchedEffect below restarts its 900 ms delay cleanly.
        var toastPresetSnapshot by remember { mutableStateOf(FilmPreset.WARM_PORTRAIT) }
        var showToast by remember { mutableStateOf(false) }
        var toastEpoch by remember { mutableStateOf(0) }

        // 1. Black background
        Box(modifier = Modifier.fillMaxSize().background(Color.Black))

        // 2. Camera Viewfinder — 4:3 box at top with rounded corners
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = vfTop)
                .width(vfWidth)
                .height(vfHeight)
                .clip(RoundedCornerShape(16.dp))
        ) {
        if (settingsLoaded) {
        CameraPreviewView(
            modifier = Modifier
                .fillMaxSize()
                // Horizontal-fling preset cycler, chained onto the SAME
                // modifier path as `CameraPreviewView`'s internal zoom
                // pointerInput. Hitting on a sibling Box layered over the
                // viewfinder (the previous approach) made the top Box win
                // hit-testing and starved both `CameraPreviewView`'s zoom
                // `pointerInput` AND its underlying native `AndroidView`
                // of touches for the entire viewfinder rect — the user's
                // report was that vertical-swipe zoom stopped working once
                // the cycler landed. Putting the detector on this modifier
                // chain puts both gestures on the same hit path so neither
                // shadows the other, and matches the
                // `awaitFirstDown(requireUnconsumed = false) +
                // awaitPointerEvent(PointerEventPass.Main)` pattern that
                // `CameraPreviewView` uses for its pan/zoom handler — so
                // neither consume-semantic nor pass-order conflicts arise.
                // Keying on the gating flags tears down / restarts the
                // gesture coroutine cleanly when sliders open/close or the
                // photo viewer state flips, matching the prior guard set.
                .pointerInput(
                    selectedPhoto == null && !showExpSlider && !showTempSlider
                ) {
                    // Compose's `detectHorizontalDragGestures` uses
                    // `awaitTouchSlopOrCancellation` internally with
                    // `Orientation.Horizontal`: it only activates when the
                    // user crosses the slop (~24 dp via
                    // `ViewConfiguration.touchSlop`) in a HORIZONTAL
                    // direction FIRST. If vertical motion reaches touch
                    // slop first (i.e. the user intends to zoom), the
                    // gesture cancels and `CameraPreviewView`'s
                    // `awaitFirstDown + calculatePan().y` loop owns the
                    // gesture. Conversely, a clear horizontal swipe
                    // claims here; `CameraPreviewView` then sees
                    // `change.isConsumed = true` on subsequent moves — even
                    // though it does not break on consume, its
                    // `calculatePan().y` returns the Y delta since the
                    // previous event which is near zero during a horizontal
                    // sweep, so the zoom branch short-circuits via
                    // `if (dragPx == 0f) null` and zoom stays put. Net
                    // effect: cleanly separated horizontal vs vertical
                    // swipe intent at the framework level, with no
                    // percentage-based drift or jitter sensitivity bugs.
                    var totalDrag = 0f
                    var firedThisGesture = false
                    detectHorizontalDragGestures(
                        onDragStart = {
                            totalDrag = 0f
                            firedThisGesture = false
                        },
                        onDragEnd = { totalDrag = 0f },
                        onDragCancel = { totalDrag = 0f },
                        onHorizontalDrag = { change, dragAmount ->
                            // Guard: once we fire one cycle on a given
                            // gesture, do not fire another even if the
                            // user keeps swiping. One gesture = one cycle.
                            // Keeps a single finger-flick from jumping two
                            // presets in a row.
                            if (!firedThisGesture) {
                                totalDrag += dragAmount
                                val threshold = size.width.toFloat() * 0.22f
                                // 0.22 instead of 0.18 + a real touch-slop
                                // gate from Compose means the user has to
                                // commit clearly to a horizontal sweep.
                                // Slight bump from 0.18 because the slop
                                // gate already filters short accidental
                                // brushes; the wider threshold makes a
                                // success feel more deliberate.
                                if (kotlin.math.abs(totalDrag) > threshold) {
                                    firedThisGesture = true
                                    // Convention: swipe LEFT reveals the
                                    // next preset; swipe RIGHT returns to
                                    // the previous one.
                                    val direction = if (totalDrag < 0f) 1 else -1
                                    viewModel.cycleCameraPreset(direction)
                                    haptic.performHapticFeedback(
                                        HapticFeedbackType.LongPress
                                    )
                                    toastPresetSnapshot =
                                        viewModel.activePreset.value
                                    showToast = true
                                    toastEpoch++
                                    change.consume()
                                }
                            }
                        }
                    )
                },
            selectedLensRole = selectedLensRole,
            digitalZoomRatio = digitalZoomRatio,
            exposure = exposure,
            flashMode = flashMode,
            isFrontCamera = isFrontCamera,
            activeExtension = activeExtension,
            isRawCapturing = isCapturing && rawModeEnabled,
            zoomEnabled = !(showExpSlider || showTempSlider),
            temperature = temperature,
            tint = tint,
            activeLut = previewLut,
            activePreset = activePreset,
            onZoomChanged = { viewModel.setZoom(it) },
            onZoomTick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            },
            onAvailableFocalLengths = { viewModel.setAvailableFocalLengths(it) },
            imageCaptureProvider = { activeImageCapture = it },
            onLensCatalogReady = { result -> viewModel.setLensCatalogResult(result) }
        )
        }

        // Countdown timer overlay
        if (timerCountdown > 0) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$timerCountdown",
                    color = Color(0xFFFBBF24),
                    fontSize = 80.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif
                )
            }
        }
        } // end viewfinder Box

        // ─────────────────────────────────────────────────────────────────
        // Preset-change toast (display + auto-dismiss timer)
        // ─────────────────────────────────────────────────────────────────
        // State vars are hoisted above the viewfinder Box so the
        // `CameraPreviewView` modifier chain can mutate them on a
        // horizontal-fling fire. The LaunchedEffect drives `showToast`
        // back to false after 900 ms and leaves `toastPresetSnapshot`
        // intact so the AnimatedVisibility exit animation has a stable
        // value to read while fading out.
        LaunchedEffect(toastEpoch) {
            if (toastEpoch == 0) return@LaunchedEffect
            kotlinx.coroutines.delay(900.milliseconds)
            showToast = false
            // Intentionally leaves `toastPresetSnapshot` intact.
        }
        AnimatedVisibility(
            visible = showToast,
            enter = fadeIn(tween(160)) + scaleIn(tween(220, easing = EaseInOutCubic), initialScale = 0.82f),
            exit = fadeOut(tween(260)) + scaleOut(tween(260), targetScale = 0.82f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = vfTop + 14.dp)
        ) {
            // Snapshot is non-null by construction (see invariant above),
            // so a plain read here is safe across both enter and exit
            // recompositions — no `!!` to crash mid-animation.
            val toastPreset = toastPresetSnapshot
            Row(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(24.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(filmPresetColor(toastPreset)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = filmPresetEmoji(toastPreset), fontSize = 16.sp)
                }
                Text(
                    text = toastPreset.displayName,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Serif
                )
            }
        }

        // Box scale animation
        val animatedBoxWidthFraction by animateFloatAsState(
            targetValue = boxScale,
            animationSpec = spring(stiffness = 200f, dampingRatio = 0.75f),
            label = "box_width_fraction"
        )

        val showZoomBox = selectedLensRole == LensRole.PRIMARY && animatedBoxWidthFraction < 0.99f

        // Coarse 3x3 grid over the full viewfinder when no zoom box is active
        // (e.g. ultra-wide / tele lens). Pairs with the inner thirds grid drawn
        // inside `if (showZoomBox)` below.
        if (gridAlpha > 0f && !showZoomBox) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val left = vfX.toPx()
                val top = vfTop.toPx()
                val right = left + vfWidth.toPx()
                val bottom = top + vfHeight.toPx()
                drawThirdsGrid(
                    rect = Rect(left, top, right, bottom),
                    color = Color.White.copy(alpha = 0.40f * gridAlpha),
                    strokeWidth = 1.dp.toPx()
                )
            }
        }

        if (showZoomBox) {
            // Aspect-ratio-aware box dimensions: box height = box width × heightToWidth.
            // When the natural box height exceeds the viewfinder height (e.g. 3:2
            // portrait at full boxFraction), clamp height to vfHeight and re-derive
            // width so the selected ratio is preserved within the available space.
            val ratioFraction = aspectRatio.heightToWidth
            val naturalBoxW = vfWidth * animatedBoxWidthFraction
            val naturalBoxH = naturalBoxW * ratioFraction
            val (boxWf, boxHf) = if (naturalBoxH > vfHeight) {
                (vfHeight / ratioFraction) to vfHeight
            } else {
                naturalBoxW to naturalBoxH
            }
            val zoomBoxTop = vfTop + (vfHeight - boxHf) / 2f
            val boxCenterX = vfX + (vfWidth - boxWf) / 2f

            Canvas(modifier = Modifier.fillMaxSize()) {
                val boxW = boxWf.toPx()
                val boxH = boxHf.toPx()
                val left = vfX.toPx() + (vfWidth.toPx() - boxW) / 2f
                val top = vfTop.toPx() + (vfHeight.toPx() - boxH) / 2f

                val rect = Rect(left, top, left + boxW, top + boxH)
                val path = Path().apply {
                    addRoundRect(RoundRect(rect = rect, cornerRadius = CornerRadius(20.dp.toPx())))
                }
                clipPath(path = path, clipOp = ClipOp.Difference) {
                    drawRect(color = Color.Black.copy(alpha = 0.65f))
                }

                if (gridAlpha > 0f) {
                    // Rule-of-thirds grid lines, clipped to the zoom box rounded rect
                    clipPath(path = path, clipOp = ClipOp.Intersect) {
                        drawThirdsGrid(
                            rect = rect,
                            color = Color.White.copy(alpha = 0.55f * gridAlpha),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                }
            }

            // Focal length above zoom box (rendered above black mask)
            Text(
                text = "${effectiveFocalLength}mm",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = zoomBoxTop - 30.dp)
            )

            // Zoom box outline
            Box(
                modifier = Modifier
                    .offset(
                        x = boxCenterX,
                        y = vfTop + (vfHeight - boxHf) / 2f
                    )
                    .width(boxWf)
                    .height(boxHf)
                    .border(2.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(20.dp))
            )
        }


        // Three-point settings menu button in the top-right corner of the viewfinder
        // Opens a full-screen Settings page; the actual page surface + back navigation
        // lives in SettingsScreen at the top of CameraUi() (sibling swap, not overlay).
        // Anchored to the viewfinder's top-right corner so it stays put inside
        // the corner even as the viewfinder recenters between the top inset
        // and the bottom deck.
        Box(
            modifier = Modifier
                .offset(x = vfX + vfWidth - 48.dp, y = vfTop + 8.dp)
        ) {
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onOpenSettings()
                },
                modifier = Modifier.size(36.dp).testTag("settings_menu_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "Settings",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Transparent overlay for full-screen swipe when a slider panel is
        // open. Placed BEFORE the floating control surface so the panel
        // sits on top and its buttons (presets, close) receive touch events
        // first without interception. The overlay handles taps outside the
        // panel area and full-screen swipes over the viewfinder.
        if (showExpSlider || showTempSlider) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(showExpSlider, showTempSlider) {
                        awaitEachGesture {
                            val first = awaitPointerEvent(PointerEventPass.Main)
                            val down = first.changes.firstOrNull { it.changedToDown() && it.pressed }
                                    ?: return@awaitEachGesture
                            val track = TrackedPointer(
                                start = down.position,
                                initialExposure = exposure,
                                initialTemp = temperature,
                                initialTint = tint
                            )
                            var consumedByChild = false
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                                consumedByChild = consumedByChild || ch.isConsumed
                                if (!ch.isConsumed && ch.pressed) {
                                    val dx = ch.position.x - track.start.x
                                    val dy = ch.position.y - track.start.y

                                    if (showExpSlider && kotlin.math.abs(dx) > 10f) {
                                        track.moved = true
                                        val raw = track.initialExposure + (dx / size.width.toFloat()) * 6f
                                        val s = (kotlin.math.round(raw / 0.1f) * 0.1f).coerceIn(-3f, 3f)
                                        if (s != exposure) {
                                            viewModel.setExposure(s)
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                        ch.consume()
                                    }

                                    if (showTempSlider && (kotlin.math.abs(dx) > 10f || kotlin.math.abs(dy) > 10f)) {
                                        track.moved = true
                                        val rt = track.initialTemp + (dx / size.width.toFloat()) * 4f
                                        val rti = track.initialTint - (dy / size.height.toFloat()) * 4f
                                        val st = (kotlin.math.round(rt / 0.1f) * 0.1f).coerceIn(-2f, 2f)
                                        val sti = (kotlin.math.round(rti / 0.1f) * 0.1f).coerceIn(-2f, 2f)
                                        if (st != temperature || sti != tint) {
                                            viewModel.setTemperature(st)
                                            viewModel.setTint(sti)
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                        ch.consume()
                                    }
                                }
                            } while (ch.pressed)

                            // Close on tap (no value adjusted)
                            if (!consumedByChild &&
                                track.initialExposure == exposure &&
                                track.initialTemp == temperature &&
                                track.initialTint == tint
                            ) {
                                viewModel.closeSliders()
                            }
                        }
                    }
            )
        }

        // Floating Control Surface — the bubble and the color/exposure panels
        // share the same composable slot at the bottom of the viewfinder and
        // morph in place via AnimatedContent. Anchor the bottom edge of the
        // rendered content to (vfTop + vfHeight - 10 dp) so the bubble stays
        // put; the taller panel grows upward into the viewfinder rather than
        // shoving the bubble down into the deck like the previous stacked
        // layout did.
        val morphBottomAnchorPx = with(LocalDensity.current) { (vfTop + vfHeight - 10.dp).roundToPx() }
        Box(
            modifier = Modifier
                // Anchor content at horizontal center + viewfinder bottom so the
                // morph reads as the bubble staying put while the panel grows
                // upward, instead of leaving the bubble stuck on the left edge of
                // the screen. The previous layout block reported placeable.width as
                // its own bounds and placed at x=0, which overrode any outer
                // contentAlignment and visually pinned the bubble to the screen's
                // left edge during the morph. We now return constraints.maxWidth
                // (= boxWidth from BoxWithConstraints) and place the placeable at
                // the layout's horizontal center.
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(constraints.maxWidth, placeable.height) {
                        val centerX = (constraints.maxWidth - placeable.width) / 2
                        placeable.place(
                            x = centerX,
                            y = morphBottomAnchorPx - placeable.height
                        )
                    }
                },
            contentAlignment = Alignment.BottomCenter
        ) {
            val morphMode: MorphMode = when {
                showTempSlider -> MorphMode.COLOR
                showExpSlider  -> MorphMode.EXPOSURE
                else           -> MorphMode.BUBBLE
            }
            AnimatedContent(
                targetState = morphMode,
                // Drop fillMaxWidth so SizeTransform can actually interpolate
                // the slot's width during the morph. With fillMaxWidth on both
                // states, the slot was already screen-wide on both sides and the
                // size animation had nothing to interpolate — the morph just
                // snapped. With wrap-content here the slot grows from bubble width
                // (~180 dp) to panel width (300 dp) and the size animation reads.
                contentAlignment = Alignment.BottomCenter,
                transitionSpec = {
                    // Sharing one tween curve across both fades AND the
                    // SizeTransform keeps alpha and the bounds grow in lock-step.
                    // Otherwise the alpha finishes while the size is still mid-way
                    // and the transition reads as a lurch. EaseInOutCubic matches
                    // the curve already used for the staggered splash reveals.
                    val morphDuration = 260
                    val morphEasing = EaseInOutCubic
                    ContentTransform(
                        targetContentEnter = fadeIn(
                            tween(morphDuration, easing = morphEasing)
                        ),
                        initialContentExit = fadeOut(
                            tween(morphDuration, easing = morphEasing)
                        ),
                        sizeTransform = SizeTransform(
                            clip = false,
                            sizeAnimationSpec = { _, _ ->
                                tween<IntSize>(morphDuration, easing = morphEasing)
                            }
                        )
                    )
                },
                label = "bubble_panel_morph"
            ) { mode ->
                when (mode) {
                    MorphMode.BUBBLE -> FloatingBubbleRow(
                        effectiveFocalLength = effectiveFocalLength,
                        temperature = temperature,
                        tint = tint,
                        exposure = exposure,
                        isFrontCamera = isFrontCamera,
                        onTemperatureClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.toggleTemperatureSlider()
                        },
                        onLensClick = {
                            // Skip both the haptic and the cycle on front
                            // camera. cycleLens() already no-ops internally
                            // (defense-in-depth) but folding both intent and
                            // feedback into the same guard suppresses the
                            // "buzz-and-nothing" feel on the dead click.
                            if (!isFrontCamera) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.cycleLens()
                            }
                        },
                        onExposureClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.toggleExposureSlider()
                        }
                    )
                    MorphMode.COLOR -> MorphedPanelChrome {
                        WhiteBalancePanel(
                            temperature = temperature,
                            tint = tint,
                            onValueChange = { tempVal, tintVal ->
                                viewModel.setTemperature(tempVal)
                                viewModel.setTint(tintVal)
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            },
                            headerActions = {
                                MorphedPanelHeaderButton(
                                    icon = Icons.Rounded.Close,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.closeSliders()
                                    }
                                )
                            }
                        )
                    }
                    MorphMode.EXPOSURE -> MorphedPanelChrome {
                        ExposurePanel(
                            exposure = exposure,
                            onValueChange = { value ->
                                viewModel.setExposure(value)
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            },
                            headerActions = {
                                MorphedPanelHeaderButton(
                                    icon = Icons.Rounded.Close,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.closeSliders()
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }

        // 5. White flash overlay
        AnimatedVisibility(
            visible = flashFlashActive,
            enter = fadeIn(animationSpec = tween(40)),
            exit = fadeOut(animationSpec = tween(150))
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.White))
        }

        // 6. Bottom Deck Controls (two-row Dazz-cam style)
        val activePreset by viewModel.activePreset.collectAsState()
        val selfTimerMode by viewModel.selfTimerMode.collectAsState()
        var showPresetPicker by remember { mutableStateOf(false) }
        var pendingDelete by remember { mutableStateOf<File?>(null) }

        // Lambda that executes the actual capture, extracted so timer can call it.
        // `beginCapture()` is called immediately before the hardware call (NOT
        // at the top of this lambda) so a missing `captureDevice` or null
        // `currentLens` doesn't strand `captureInFlight` in the "pressed" state.
        // `endCapture()` is called in the JPEG `onCaptureError` branch to
        // release the press if CameraX reports a failure — the capture flow
        // for the success path is covered by `processAndSavePhoto`'s top,
        // which calls `endCapture()` the moment OnImageSavedCallback fires.
        val doCapture: () -> Unit = {
            viewModel.playShutterSound()
            flashFlashActive = true
            val currentLens = viewModel.getCurrentLensProfile()
            val nativeFocalForCrop = if (selectedLensRole == LensRole.PRIMARY)
                viewModel.lensCatalogResult?.primary?.equivFocalMm else null
            if (rawModeEnabled && currentLens != null) {
                viewModel.beginCapture()
                viewModel.captureAndSaveRaw(
                    context = context,
                    logicalCameraId = currentLens.logicalCameraId,
                    physicalCameraId = currentLens.physicalCameraId,
                    focalLengthMm = effectiveFocalLength
                )
            } else {
                val captureDevice = activeImageCapture
                if (captureDevice != null) {
                    viewModel.beginCapture()
                    triggerImageCapture(
                        context = context,
                        imageCapture = captureDevice,
                        executor = mainExecutor,
                        // Sensor-tracked physical rotation — under the portrait
                        // lock Display.getRotation() is pinned to ROTATION_0.
                        targetRotation = viewModel.physicalRotation.value,
                        onCaptured = { rawFile ->
                            viewModel.processAndSavePhoto(
                                context = context,
                                rawFile = rawFile,
                                boxWidthFraction = animatedBoxWidthFraction,
                                screenWidth = totalWidth.value,
                                screenHeight = totalHeight.value,
                                captureFocalLength = effectiveFocalLength,
                                captureLensNativeFocalMm = nativeFocalForCrop
                            )
                        },
                        onCaptureError = { exc ->
                            // Without this, a CameraX failure would leave
                            // captureInFlight stuck true forever and lock the
                            // shutter out until the next lens flip. The
                            // success path doesn't need an explicit reset —
                            // `processAndSavePhoto` does it as its first
                            // line so the shutter snaps back the moment the
                            // hardware delivers the image.
                            Log.e("CameraActiveScreen", "Capture failed", exc)
                            viewModel.endCapture()
                        }
                    )
                }
            }
        }

        // Bottom-deck Column anchored to the bottom of the screen, full width,
        // opaque black background + 40 dp bottom padding for the gesture area.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black)
                .padding(bottom = 40.dp, top = 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // ── Row 1: Auxiliary Controls ──────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Grid overlay toggle
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.toggleGridLines()
                    },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (showGridLines) Color(0xFFFBBF24).copy(alpha = 0.18f) else Color(0xFF1C1C1E)
                    ),
                    modifier = Modifier.size(40.dp).testTag("grid_overlay_button")
                ) {
                    Icon(
                        imageVector = if (showGridLines) Icons.Rounded.GridOn else Icons.Rounded.GridOff,
                        contentDescription = "Grid",
                        tint = if (showGridLines) Color(0xFFFBBF24) else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Self-timer cycle button
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            if (selfTimerMode != 0) Color(0xFF1C1C1E) else Color(0xFF1C1C1E),
                            CircleShape
                        )
                        .clip(CircleShape)
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.cycleSelfTimer()
                        }
                        .testTag("self_timer_button"),
                    contentAlignment = Alignment.Center
                ) {
                    if (selfTimerMode == 0) {
                        Icon(
                            imageVector = Icons.Rounded.Timer,
                            contentDescription = "Timer Off",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Text(
                            text = "${selfTimerMode}s",
                            color = Color(0xFFFBBF24),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Flash toggle
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.toggleFlash()
                    },
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF1C1C1E)),
                    modifier = Modifier.size(40.dp).testTag("flash_toggle_button")
                ) {
                    Icon(
                        imageVector = when (flashMode) {
                            0    -> Icons.Rounded.FlashAuto
                            1    -> Icons.Rounded.FlashOn
                            else -> Icons.Rounded.FlashOff
                        },
                        contentDescription = "Flash",
                        tint = if (flashMode == 2) Color.White else Color(0xFFFBBF24),
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Camera flip
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.toggleCamera()
                    },
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF1C1C1E)),
                    modifier = Modifier.size(40.dp).testTag("camera_flip_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FlipCameraAndroid,
                        contentDescription = "Flip Camera",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // ── Row 2: Primary Actions ─────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                // Left: last-captured thumbnail card
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1C1C1E))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (capturedPhotos.isNotEmpty()) viewModel.setSelectedPhoto(capturedPhotos.first())
                        }
                        .testTag("gallery_button"),
                    contentAlignment = Alignment.Center
                ) {
                    if (capturedPhotos.isNotEmpty()) {
                        Image(
                            painter = rememberAsyncImagePainter(model = capturedPhotos.first()),
                            contentDescription = "Last photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.PhotoLibrary,
                            contentDescription = "No photos",
                            tint = Color.Gray,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Center: Shutter button (large, white ring with red fill)
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .background(Color.Transparent, CircleShape)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(5.dp)
                        .testTag("shutter_button")
                        .clickable(enabled = !captureInFlight && timerCountdown < 0) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (selfTimerMode == 0) {
                                doCapture()
                            } else {
                                // Start countdown
                                coroutineScope.launch {
                                    timerCountdown = selfTimerMode
                                    repeat(selfTimerMode) {
                                        kotlinx.coroutines.delay(1000.milliseconds)
                                        timerCountdown--
                                    }
                                    doCapture()
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val s by animateFloatAsState(
                        // drive off the press-not-processing signal so the
                        // button snaps back the instant the camera hands
                        // back the image, not after the bitmap decode /
                        // LUT pipeline finishes. See `captureInFlight` /
                        // `viewModel.beginCapture()` for timing.
                        targetValue = if (captureInFlight) 0.82f else 1.0f,
                        // Snappier than the previous spring(dampingRatio
                        // = 0.55f) which had a long settle that read as
                        // "mushy press". A 120 ms tween (default easing
                        // = FastOutSlowInEasing) feels closer to a real
                        // shutter button: fast down, fast back.
                        animationSpec = tween(durationMillis = 120),
                        label = "shutter_scale"
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .scale(s)
                            .background(Color(0xFFEF4444), CircleShape)
                    )
                }

                // Right: Retro camera preset picker button
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1C1C1E))
                        .border(1.dp, Color(0xFFFBBF24).copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showPresetPicker = true
                        }
                        .testTag("preset_picker_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(filmPresetColor(activePreset)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = filmPresetEmoji(activePreset),
                                fontSize = 18.sp
                            )
                        }
                    }
                }
            }
        }

        // Preset Picker Bottom Sheet
        if (showPresetPicker) {
            ModalBottomSheet(
                onDismissRequest = { showPresetPicker = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Color(0xFF1A1A1E),
                contentColor = Color.White
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Film Style",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    // Seed the LazyRow with the *active* preset's index so
                    // when the picker opens it lands on the current style.
                    // Keying on `showPresetPicker` re-runs this read every
                    // time the sheet toggles on so each open sees the
                    // latest active preset.
                    val presetList = FilmPreset.entries
                    val safeInitialIndex = remember(showPresetPicker) {
                        presetList.indexOf(activePreset).coerceAtLeast(0)
                    }
                    val filmStyleListState = rememberLazyListState(
                        initialFirstVisibleItemIndex = safeInitialIndex,
                        initialFirstVisibleItemScrollOffset = 0
                    )
                    // Persist every visible-item change so the user's
                    // in-picker browse position survives another sheet open
                    // (this state is only used when the seed index match or
                    // when active == preserved; otherwise the LaunchedEffect
                    // below re-centers on active).
                    LaunchedEffect(filmStyleListState) {
                        snapshotFlow {
                            filmStyleListState.firstVisibleItemIndex to
                                filmStyleListState.firstVisibleItemScrollOffset
                        }
                            .distinctUntilChanged()
                            .collect { (idx, off) ->
                                viewModel.saveFilmStyleScrollPosition(idx, off)
                            }
                    }
                    // Re-centre the active preset whenever the picker
                    // opens OR activePreset changes — using a NON-CLAMPED
                    // scroll by wrapping the LazyRow in symmetric
                    // `contentPadding = (maxWidth - itemWidth) / 2`. Without
                    // the symmetric padding, Compose's scroll clamp pinned
                    // the leftmost and rightmost cards to the row edges,
                    // so the user's reported "sometimes the active isn't
                    // in bounds of the menu" behaviour showed the active
                    // card off-axis when active was at index 0 or the
                    // last item. With the symmetric padding, the row has
                    // scroll headroom on both sides, and
                    // `animateScrollToItem(N, 0)` lands the card at the
                    // visual centre for every index.
                    //
                    // Item width is estimated: each preset card is
                    // Box(60.dp) + Column.padding(8.dp) both sides = 76.dp
                    // visual width. We round up to 80.dp + 8.dp safety to
                    // absorb 2-line label widths ("Sunlit Spill", "Cross
                    // Process") without the padding clipping the card.
                    // Same pattern as the viewfinder-anchored BoxWithConstraints
                    // replacement: cache parent width into state and re-derive
                    // `pickerWidth` so the LazyRow's symmetricPadding is correct on
                    // the second composition onward. The one-frame settle is fine
                    // here because the picker itself cross-fades in via the bottom
                    // sheet's own animation, so a single missing-frame wouldn't
                    // ever be visually singled out.
                    val pickerSize = remember { mutableStateOf(IntSize.Zero) }
                    val pickerWidth = with(LocalDensity.current) { pickerSize.value.width.toDp() }
                    Box(
                        modifier = Modifier.fillMaxWidth().onSizeChanged { pickerSize.value = it }
                    ) {
                        val estimatedItemWidth = 88.dp
                        val symmetricPadding =
                            ((pickerWidth - estimatedItemWidth) / 2).coerceAtLeast(0.dp)
                        LazyRow(
                            state = filmStyleListState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(horizontal = symmetricPadding)
                        ) {
                            items(FilmPreset.entries) { preset ->
                                val selected = preset == activePreset
                                Column(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (selected) Color(0xFFFBBF24).copy(alpha = 0.12f)
                                            else Color(0xFF2C2C2E)
                                        )
                                        .border(
                                            1.5.dp,
                                            if (selected) Color(0xFFFBBF24) else Color.White.copy(alpha = 0.06f),
                                            RoundedCornerShape(12.dp)
                                        )
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewModel.setCameraPreset(preset)
                                            showPresetPicker = false
                                        }
                                        .padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(60.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(filmPresetColor(preset)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = filmPresetEmoji(preset),
                                            fontSize = 24.sp
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = preset.displayName,
                                        color = if (selected) Color(0xFFFBBF24) else Color.White.copy(alpha = 0.85f),
                                        fontSize = 10.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 2,
                                        textAlign = TextAlign.Center,
                                        lineHeight = 12.sp
                                    )
                                }
                            }
                        }
                    }
                    // Recentre on every activePreset change OR picker
                    // open. The symmetric contentPadding above makes this
                    // a one-liner: animateScrollToItem(N, 0) is now
                    // equivalent to "put item N horizontally centred in the
                    // viewport." Guard with `isScrollInProgress` so a
                    // background change doesn't hijack an active fling.
                    LaunchedEffect(activePreset, showPresetPicker) {
                        if (!showPresetPicker) return@LaunchedEffect
                        kotlinx.coroutines.delay(50.milliseconds)
                        if (filmStyleListState.isScrollInProgress) return@LaunchedEffect
                        val targetIndex =
                            presetList.indexOf(activePreset).coerceAtLeast(0)
                        filmStyleListState.animateScrollToItem(targetIndex, 0)
                    }
                }
            }
        }

        // Delete confirmation dialog. Intercepted before the PhotoViewerOverlay
        // callback reaches viewModel.deletePhoto() so an accidental tap on the
        // trash icon doesn't nuke the file. The dialog lives at this scope
        // (above the photo viewer) so back-press / scrim tap dismisses the
        // dialog first, not the viewer underneath.
        pendingDelete?.let { fileToDelete ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Delete photo?",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                },
                text = {
                    Text(
                        "This photo will be permanently deleted from this device. " +
                            "This action cannot be undone.",
                        color = Color(0xFF9CA3AF),
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            // Order matters: kick off the (async, IO-bound) delete
                            // first, then close the dialog. Flipping these two
                            // would dismiss the dialog before the file is gone
                            // and leave the user wondering if the tap registered.
                            viewModel.deletePhoto(context, fileToDelete)
                            pendingDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFEF4444),
                            contentColor = Color.White
                        ),
                        modifier = Modifier.testTag("confirm_delete_button")
                    ) {
                        Text("Delete", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) {
                        Text("Cancel", color = Color.White)
                    }
                },
                containerColor = Color(0xFF1E1E1E),
                tonalElevation = 0.dp
            )
        }


        // 7. Photo Viewer Overlay
        AnimatedVisibility(
            visible = selectedPhoto != null,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it })
        ) {
            val activePhoto = selectedPhoto
            if (activePhoto != null) {
                PhotoViewerOverlay(
                    initialPhotoIndex = capturedPhotos.indexOf(activePhoto).coerceAtLeast(0),
                    allPhotos = capturedPhotos,
                    viewModel = viewModel,
                    onClose = { viewModel.setSelectedPhoto(null) },
                    onDelete = { file -> pendingDelete = file },
                    onSelectPhoto = { viewModel.setSelectedPhoto(it) }
                )
            }
        }
    }
}

@Composable
fun PhotoViewerOverlay(
    initialPhotoIndex: Int,
    allPhotos: List<File>,
    viewModel: CameraViewModel,
    onClose: () -> Unit,
    onDelete: (File) -> Unit,
    onSelectPhoto: (File) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val pagerState = rememberPagerState(
        initialPage = initialPhotoIndex.coerceIn(0, (allPhotos.size - 1).coerceAtLeast(0)),
        pageCount = { allPhotos.size }
    )

    LaunchedEffect(pagerState.currentPage) {
        if (allPhotos.isNotEmpty()) {
            onSelectPhoto(allPhotos[pagerState.currentPage])
        }
    }

    BackHandler(onBack = onClose)

    // Outer Column keeps its original top = 16 dp baseline so non-cutout
    // phones see no visual change. The cutout-safe offset is supplied by
    // the inner Row below.
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(top = 16.dp)
    ) {
        val currentPhoto = allPhotos.getOrNull(pagerState.currentPage)
        // displayCutoutPadding() pads by the device's display-cutout inset
        // only where one exists: center cutouts get top padding (~32 dp on
        // Pixel 6+ / Dynamic Island on iPhone 14 Pro), top-LEFT/TOP-RIGHT
        // corner cutouts get vertical AND horizontal padding (Pixel 6 Pro,
        // OnePlus 7, Galaxy S). Non-cutout phones report a 0 dp inset, so
        // the X + "Gallery" row sits at the original y-offset.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .displayCutoutPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClose() },
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF1C1C1E))
            ) {
                Icon(imageVector = Icons.Rounded.Close, contentDescription = "Close Viewfinder", tint = Color.White)
            }

            Text(
                text = "Gallery",
                fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp, fontFamily = FontFamily.Serif
            )

            if (currentPhoto != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            try {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", currentPhoto)
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "image/jpeg"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share Retro Photo"))
                            } catch (e: Exception) { Log.e("PhotoViewerOverlay", "Error sharing photo", e) }
                        },
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF1C1C1E))
                    ) {
                        Icon(imageVector = Icons.Rounded.Share, contentDescription = "Share retro capture", tint = Color.White, modifier = Modifier.size(18.dp))
                    }

                    IconButton(
                        onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onDelete(currentPhoto) },
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF2A1C1C)),
                        modifier = Modifier.testTag("delete_photo_button")
                    ) {
                        Icon(imageVector = Icons.Rounded.Delete, contentDescription = "Delete captured photo", tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        // Photo Pager
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            beyondViewportPageCount = 1
        ) { page ->
            val photo = allPhotos[page]

            var photoDims by remember(photo) { mutableStateOf<IntSize?>(null) }
            LaunchedEffect(photo) {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(photo.absolutePath, options)
                var w = options.outWidth
                var h = options.outHeight
                if (w > 0 && h > 0) {
                    // BitmapFactory reports the raw pixel dimensions, but Coil
                    // renders the image with the EXIF orientation applied (it
                    // respects the tag by default). Swap the bounds for 90°/
                    // 270° rotations so the card aspect matches the rendered
                    // image — without this, a horizontal (landscape) photo
                    // carrying an EXIF rotation would be framed by a
                    // portrait-shaped card and vice versa.
                    val orientation = try {
                        ExifInterface(photo.absolutePath).getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )
                    } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }
                    if (orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                        orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
                        orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                        orientation == ExifInterface.ORIENTATION_TRANSVERSE) {
                        w = h.also { h = w }
                    }
                    photoDims = IntSize(w, h)
                }
            }
            val photoAspect = photoDims?.let { d -> d.width.toFloat() / d.height.toFloat() } ?: (1f / 1.35f)

            BoxWithConstraints(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                // Show the photo exactly as saved: the film-card frame, when
                // enabled, is baked into the JPEG itself, so the gallery just
                // renders the file as-is (no double frame). Size it to fit the
                // available area while preserving the photo's aspect ratio:
                // width-bound for portrait shots, height-bound for horizontal
                // (landscape) shots so a wide image fills the screen instead
                // of overflowing (or shrinking to a strip) when the device is
                // held sideways.
                val imgWidth = minOf(maxWidth, maxHeight * photoAspect)
                val imgHeight = imgWidth / photoAspect
                Image(
                    painter = rememberAsyncImagePainter(model = photo),
                    contentDescription = "Enlarged capture",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.width(imgWidth).height(imgHeight)
                )
            }
        }

        // Filmstrip
        Spacer(modifier = Modifier.height(16.dp))
        Box(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(items = allPhotos, key = { it.absolutePath }) { item ->
                    val idx = allPhotos.indexOf(item)
                    val isSelected = idx == pagerState.currentPage
                    Box(
                        modifier = Modifier
                            .size(62.dp).clip(RoundedCornerShape(6.dp))
                            .border(width = if (isSelected) 3.dp else 0.dp, color = if (isSelected) Color(0xFFF59E0B) else Color.Transparent, shape = RoundedCornerShape(6.dp))
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                scope.launch { pagerState.animateScrollToPage(idx) }
                            }
                    ) {
                        Image(painter = rememberAsyncImagePainter(model = item), contentDescription = "Filmstrip photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

// =====================================================================================
// Full-screen Settings page
// =====================================================================================
// The three-point MoreVert in CameraActiveScreen no longer pops a DropdownMenu; instead
// it raises the `showSettingsPage` flag at the top of CameraUi(), which sibling-swaps
// the active surface to this SettingsScreen. Back arrow + system back both return to
// the live camera via onClose().
@Composable
fun SettingsScreen(viewModel: CameraViewModel, onClose: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val rawModeEnabled by viewModel.rawModeEnabled.collectAsState()
    val rawAvailableForCurrentLens by viewModel.rawAvailableForCurrentLens.collectAsState()
    val isFrontCamera by viewModel.isFrontCamera.collectAsState()
    val aspectRatio by viewModel.aspectRatio.collectAsState()
    val outputResolution by viewModel.outputResolution.collectAsState()
    val showGalleryFrame by viewModel.showGalleryFrame.collectAsState()

    // Intercept system back to dismiss the settings page back to the camera.
    BackHandler(onBack = onClose)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0E0E0E)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar — displayCutoutPadding() pushes the X + "Settings"
            // title away from the device's display cutout (notch / Dynamic
            // Island / corner hole-punch). On non-cutout phones the inset is
            // 0 dp so the row stays at the original y-offset (24 dp top
            // padding is preserved verbatim); on cutout phones the cutout
            // inset is added on top, so the row sits below the camera
            // hardware on Pixel 6+, iPhone 14 Pro, Galaxy S, etc.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .displayCutoutPadding()
                    .padding(start = 4.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClose() }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close settings",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Settings",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                )
            }

            // Section header chip
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                color = Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "CAPTURE",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            // Scrollable body
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                SettingsRow(
                    label = "RAW Format",
                    subtitle = "Capture unprocessed sensor data for professional editing (DNG)",
                    checked = rawModeEnabled,
                    enabled = rawAvailableForCurrentLens && !isFrontCamera,
                    onCheckedChange = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.toggleRawMode()
                    }
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "ASPECT RATIO",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp)
                )
                AspectRatioChips(
                    selected = aspectRatio,
                    onSelect = { newRatio ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.setAspectRatio(newRatio)
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
                SettingsRow(
                    label = "Save at full resolution",
                    checked = outputResolution == OutputResolution.FULL,
                    enabled = true,
                    onCheckedChange = { wantFullRes ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.setOutputResolution(
                            if (wantFullRes) OutputResolution.FULL
                            else OutputResolution.THREE_MEGAPIXEL
                        )
                    }
                )
                Spacer(modifier = Modifier.height(36.dp))
                Text(
                    text = "GALLERY",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp)
                )
                SettingsRow(
                    label = "Photo Frame",
                    subtitle = "Add the film-card frame in the gallery and bake it into saved photos",
                    checked = showGalleryFrame,
                    enabled = true,
                    onCheckedChange = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.toggleGalleryFrame()
                    }
                )
                Spacer(modifier = Modifier.height(36.dp))

                Text(
                    text = "Zoom \u2022 Camera",
                    color = Color.White.copy(alpha = 0.35f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingsRow(
    label: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val labelAlpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1A1A1A))
            .clickable(enabled = enabled) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onCheckedChange(!checked)
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = Color.White.copy(alpha = labelAlpha),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.55f * labelAlpha),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    lineHeight = 16.sp
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFFFBBF24),
                checkedTrackColor = Color(0xFFFBBF24).copy(alpha = 0.5f),
                uncheckedThumbColor = Color.Gray,
                uncheckedTrackColor = Color.DarkGray
            )
        )
    }
}

/**
 * Three-pill chip row for selecting the photo aspect ratio.
 *
 * - 4:3 (Standard) -- the sensor's native portrait ratio, default for backward
 *   compatibility with photos taken before this setting existed.
 * - 3:2 (Tall) -- a slightly taller portrait crop that yields more aggressive
 *   vertical framing (handy for portraits and street photography).
 * - 1:1 (Square) -- Instagram-style square crop, centred on the viewfinder.
 *
 * Each pill shows its ratio label and a short descriptor. The selected pill is
 * amber-tinted with an amber border; the rest sit on the neutral dark surface.
 * Tapping a different pill fires `onSelect(newRatio)` (the ViewModel update
 * triggers a recomposition that updates both the chip selection and the
 * on-screen zoom-box rect).
 */
@Composable
private fun AspectRatioChips(
    selected: AspectRatio,
    onSelect: (AspectRatio) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AspectRatio.entries.forEach { ratio ->
            val isSelected = ratio == selected
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(58.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .testTag("aspect_ratio_chip_${ratio.label}")
                    .border(
                        width = 1.dp,
                        color = if (isSelected) Color(0xFFFBBF24) else Color.Transparent,
                        shape = RoundedCornerShape(10.dp)
                    )
                    .clickable {
                        if (ratio != selected) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSelect(ratio)
                        }
                    },
                color = if (isSelected) Color(0xFFFBBF24).copy(alpha = 0.18f) else Color(0xFF242424),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = ratio.label,
                        color = if (isSelected) Color(0xFFFBBF24) else Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = when (ratio) {
                            AspectRatio.RATIO_4_3 -> "Standard"
                            AspectRatio.RATIO_3_2 -> "Tall"
                            AspectRatio.RATIO_1_1 -> "Square"
                        },
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
            }
        }
    }
}