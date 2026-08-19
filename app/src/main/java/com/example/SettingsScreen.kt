package com.example

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.zoom.AspectRatio

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
                        contentDescription = stringResource(R.string.close_settings_desc),
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.settings_label),
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
                    text = stringResource(R.string.capture_section),
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
                    label = stringResource(R.string.raw_format_label),
                    subtitle = stringResource(R.string.raw_format_subtitle),
                    checked = rawModeEnabled,
                    enabled = rawAvailableForCurrentLens && !isFrontCamera,
                    onCheckedChange = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.toggleRawMode()
                    }
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.aspect_ratio_section),
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
                    label = stringResource(R.string.full_resolution_label),
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
                    text = stringResource(R.string.gallery_section),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp)
                )
                SettingsRow(
                    label = stringResource(R.string.photo_frame_label),
                    subtitle = stringResource(R.string.photo_frame_subtitle),
                    checked = showGalleryFrame,
                    enabled = true,
                    onCheckedChange = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.toggleGalleryFrame()
                    }
                )
                Spacer(modifier = Modifier.height(36.dp))

                Text(
                    text = stringResource(R.string.zoom_camera_footer),
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
                            AspectRatio.RATIO_4_3 -> stringResource(R.string.aspect_ratio_standard)
                            AspectRatio.RATIO_3_2 -> stringResource(R.string.aspect_ratio_tall)
                            AspectRatio.RATIO_1_1 -> stringResource(R.string.aspect_ratio_square)
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
