package com.example

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Fixed pill metrics. Shared between this composable (which draws the labels)
 * and the glass geometry CameraActiveScreen hands to the GL renderer, so the
 * shader's rounded rect and the Compose content stay pixel-aligned.
 */
internal val LiquidGlassPillWidth = 96.dp
internal val LiquidGlassPillHeight = 44.dp
internal val LiquidGlassPillCornerRadius = 22.dp
internal val LiquidGlassPillBottomMargin = 12.dp

/**
 * Display-only liquid-glass zoom readout pinned to the bottom of the
 * viewfinder: current zoom ratio (e.g. "2.4×") plus the 35mm-equivalent focal
 * length. Pinch/drag on the viewfinder stays the only zoom *input* — this pill
 * never consumes gestures.
 *
 * When [glassActive] is true the live preview runs through the GLES effect
 * pass, whose glass stage refracts the camera image inside this exact rect
 * (edge-weighted lens distortion, subtle dispersion, blur, rim highlight); the
 * GL surface lives below the window, so this composable then draws only the
 * labels on top of the shader-drawn glass. On the Normal preset there is no
 * shader (stock TextureView preview) and the pill falls back to the same
 * frosted chrome as [FloatingBubbleRow]: translucent fill + 1dp highlight
 * border, no refraction.
 */
@Composable
fun LiquidGlassZoomSelector(
    zoomRatio: Float,
    focalLengthMm: Int,
    glassActive: Boolean,
    modifier: Modifier = Modifier,
    controlAngle: Float = 0f
) {
    val shape = RoundedCornerShape(LiquidGlassPillCornerRadius)

    // Zoom changes lift the readout to full strength; after a short idle it
    // settles back so the pill reads as chrome rather than a notification.
    var emphasized by remember { mutableStateOf(true) }
    LaunchedEffect(zoomRatio) {
        emphasized = true
        delay(2_000)
        emphasized = false
    }
    val labelAlpha by animateFloatAsState(
        targetValue = if (emphasized) 1f else 0.8f,
        label = "zoom_pill_label_alpha"
    )

    Box(
        modifier = modifier
            .size(width = LiquidGlassPillWidth, height = LiquidGlassPillHeight)
            .then(
                if (glassActive) {
                    // The GL effect pass draws the glass body under this rect;
                    // any Compose fill would cover the refracted camera image.
                    Modifier
                } else {
                    Modifier
                        .background(Color.Black.copy(alpha = 0.55f), shape)
                        .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
                }
            )
            .testTag("liquid_glass_zoom_selector"),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.rotate(controlAngle)
        ) {
            Text(
                text = formatZoomRatio(zoomRatio),
                color = Color.White.copy(alpha = labelAlpha),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = stringResource(R.string.focal_length_mm, focalLengthMm),
                color = Color.White.copy(alpha = labelAlpha * 0.55f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/** "2.4×" — one decimal, locale-independent (no String.format). */
internal fun formatZoomRatio(zoomRatio: Float): String {
    val tenths = kotlin.math.round(zoomRatio * 10f).toInt()
    return "${tenths / 10}.${tenths % 10}\u00d7"
}
