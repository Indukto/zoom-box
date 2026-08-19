package com.example

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ui.theme.FilmDarkColorScheme
import com.example.zoom.AspectRatio

// =====================================================================================
// Full-screen Settings page — Material You components on the film-chrome dark palette
// =====================================================================================
// Built from Material 3 components (expressive ListItems, SegmentedButton, Switch)
// but pinned to the film-chrome dark palette: the settings page is part of the app's
// camera identity and stays dark even when the system is in light mode. Amber accents
// come from the palette's primary color.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(viewModel: CameraViewModel, onClose: () -> Unit) {
    // Intercept system back to dismiss the settings page back to the camera.
    BackHandler(onBack = onClose)

    // Pin this subtree to the film-chrome dark palette regardless of the system
    // light/dark setting. Shapes and typography pass through from the outer
    // expressive theme so the Material You look is preserved.
    MaterialTheme(
        colorScheme = FilmDarkColorScheme,
        shapes = MaterialTheme.shapes,
        typography = MaterialTheme.typography
    ) {
        SettingsContent(viewModel = viewModel, onClose = onClose)
    }
}

/**
 * The settings page body. Rendered inside the film-chrome [MaterialTheme] pin
 * in [SettingsScreen], so [MaterialTheme.colorScheme] and the Material
 * components below always resolve to the dark film palette.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsContent(viewModel: CameraViewModel, onClose: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val colorScheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    val rawModeEnabled by viewModel.rawModeEnabled.collectAsState()
    val rawAvailableForCurrentLens by viewModel.rawAvailableForCurrentLens.collectAsState()
    val isFrontCamera by viewModel.isFrontCamera.collectAsState()
    val aspectRatio by viewModel.aspectRatio.collectAsState()
    val outputResolution by viewModel.outputResolution.collectAsState()
    val showGalleryFrame by viewModel.showGalleryFrame.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Top bar ─────────────────────────────────────────────────────────
            // Tonal surface layer (Material You elevation) instead of a flat black
            // strip. displayCutoutPadding() keeps the X + title clear of the notch.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = colorScheme.surfaceContainerLow
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .displayCutoutPadding()
                        .padding(start = 4.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onClose()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.close_settings_desc),
                            tint = colorScheme.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.settings_label),
                        color = colorScheme.onSurface,
                        style = typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // ── Scrollable body ─────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                SectionHeader(text = stringResource(R.string.capture_section))
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

                SectionHeader(text = stringResource(R.string.aspect_ratio_section))
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

                SectionHeader(text = stringResource(R.string.gallery_section))
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

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.zoom_camera_footer),
                    color = colorScheme.onSurfaceVariant,
                    style = typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
                )
            }
        }
    }
}

/**
 * Material You section header: small primary-colored label, the same pattern
 * system settings use to group related rows.
 */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp)
    )
}

/**
 * A Material You settings row: expressive [ListItem] on a tonal container with a
 * trailing [Switch]. The row itself is tappable to toggle, and the whole row
 * dims automatically when [enabled] is false.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsRow(
    label: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val colorScheme = MaterialTheme.colorScheme

    ListItem(
        // Toggle rows: the trailing Switch carries the state (system settings
        // pattern), so the row uses the plain onClick variant.
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onCheckedChange(!checked)
        },
        enabled = enabled,
        supportingContent = subtitle?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
            }
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled
            )
        },
        colors = ListItemDefaults.colors(containerColor = colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        // The content slot is the row's headline.
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Material You single-choice segmented row for the photo aspect ratio
 * (4:3 Standard, 3:2 Tall, 1:1 Square). The selected segment gets the
 * secondary-container tint; a helper line below describes the chosen ratio.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AspectRatioChips(
    selected: AspectRatio,
    onSelect: (AspectRatio) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val colorScheme = MaterialTheme.colorScheme

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            AspectRatio.entries.forEachIndexed { index, ratio ->
                SegmentedButton(
                    selected = ratio == selected,
                    onClick = {
                        if (ratio != selected) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSelect(ratio)
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = AspectRatio.entries.size
                    ),
                    label = {
                        Text(
                            text = ratio.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    modifier = Modifier.testTag("aspect_ratio_chip_${ratio.label}")
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = when (selected) {
                AspectRatio.RATIO_4_3 -> stringResource(R.string.aspect_ratio_standard)
                AspectRatio.RATIO_3_2 -> stringResource(R.string.aspect_ratio_tall)
                AspectRatio.RATIO_1_1 -> stringResource(R.string.aspect_ratio_square)
            },
            style = MaterialTheme.typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}
