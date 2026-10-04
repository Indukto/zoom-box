package com.example

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Hd
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ui.theme.FilmDarkColorScheme
import com.example.zoom.AspectRatio
import com.example.zoom.CaptureExtension

// =====================================================================================
// Full-screen Settings page — Material You components on the film-chrome dark palette
// =====================================================================================
// Built from Material 3 components (expressive ListItems, SegmentedButton, Switch)
// but pinned to the film-chrome dark palette: the settings page is part of the app's
// camera identity and stays dark even when the system is in light mode. Amber accents
// come from the palette's primary color.
//
// Layout: grouped tonal cards per section (Capture / Viewfinder / Gallery / About),
// each row with a leading icon so the page scans like system settings. Every
// persisted ViewModel preference that affects capture is surfaced here — not just
// the original four rows — so the viewfinder quick-toggles and this page can never
// drift apart.
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
    val showGridLines by viewModel.showGridLines.collectAsState()
    val doubleExposureActive by viewModel.doubleExposureActive.collectAsState()
    // No ghost yet means the effect is armed but has nothing to blend with:
    // the first shot after switching it on is a normal one. Say so rather
    // than letting the user conclude the toggle did nothing.
    val doubleExposureHasGhost by viewModel.doubleExposureHasGhost.collectAsState()
    val selfTimerMode by viewModel.selfTimerMode.collectAsState()
    val flashMode by viewModel.flashMode.collectAsState()
    val activeExtension by viewModel.activeExtension.collectAsState()
    val availableExtensions by viewModel.availableExtensions.collectAsState()
    val extensionsProbeDone by viewModel.extensionsProbeDone.collectAsState()

    // RAW can be off for two distinct reasons — surface the right one so the
    // disabled row explains itself instead of just greying out.
    val rawEnabled = rawAvailableForCurrentLens && !isFrontCamera
    val rawSubtitle = when {
        isFrontCamera -> stringResource(R.string.raw_unavailable_front)
        !rawAvailableForCurrentLens -> stringResource(R.string.raw_unavailable_lens)
        else -> stringResource(R.string.raw_format_subtitle)
    }

    // OEM extensions: only worth a section when the device actually offers a
    // choice beyond "Off". Otherwise the card would be a single disabled chip.
    val extensionOptions = CaptureExtension.userSelectable
        .filter { it == CaptureExtension.NONE || it in availableExtensions }
    val showExtensionSection = extensionsProbeDone && extensionOptions.size > 1

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Top bar ─────────────────────────────────────────────────────
            // Tonal surface layer (Material You elevation) instead of a flat
            // black strip. statusBarsPadding + displayCutoutPadding keep the
            // X + title clear of the notch on edge-to-edge devices.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = colorScheme.surfaceContainerLow
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .displayCutoutPadding()
                        .statusBarsPadding()
                        .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_label),
                            color = colorScheme.onSurface,
                            style = typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.settings_subtitle),
                            color = colorScheme.onSurfaceVariant,
                            style = typography.bodySmall
                        )
                    }
                    // Version pill — mirrors the splash footer + About row so
                    // the build is identifiable without scrolling.
                    Surface(
                        color = colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text(
                            text = AppVersion.display,
                            color = colorScheme.onSecondaryContainer,
                            style = typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            // ── Scrollable body ─────────────────────────────────────────────
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // ── CAPTURE ───────────────────────────────────────────────
                item {
                    SectionHeader(text = stringResource(R.string.capture_section))
                    SettingsCard {
                        SettingsSwitchRow(
                            icon = Icons.Rounded.CameraAlt,
                            label = stringResource(R.string.raw_format_label),
                            subtitle = rawSubtitle,
                            checked = rawModeEnabled,
                            enabled = rawEnabled,
                            testTag = "raw_format_switch",
                            onCheckedChange = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.toggleRawMode()
                            }
                        )
                        CardDivider()
                        ResolutionBlock(
                            selected = outputResolution,
                            onSelect = { res ->
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.setOutputResolution(res)
                            }
                        )
                        CardDivider()
                        AspectRatioBlock(
                            selected = aspectRatio,
                            onSelect = { newRatio ->
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.setAspectRatio(newRatio)
                            }
                        )
                        CardDivider()
                        SettingsSwitchRow(
                            icon = Icons.Rounded.Layers,
                            label = stringResource(R.string.double_exposure_label),
                            subtitle = if (doubleExposureActive && !doubleExposureHasGhost) {
                                stringResource(R.string.double_exposure_subtitle_warming)
                            } else {
                                stringResource(R.string.double_exposure_subtitle)
                            },
                            checked = doubleExposureActive,
                            enabled = true,
                            testTag = "double_exposure_switch",
                            onCheckedChange = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.setDoubleExposureEnabled(!doubleExposureActive)
                            }
                        )
                    }
                }

                // ── ENHANCE (OEM extensions, only when there is a choice) ──
                if (showExtensionSection) {
                    item {
                        SectionHeader(text = stringResource(R.string.enhance_section))
                        SettingsCard {
                            ExtensionBlock(
                                options = extensionOptions,
                                selected = activeExtension,
                                onSelect = { ext ->
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.setExtension(ext)
                                }
                            )
                        }
                    }
                }

                // ── VIEWFINDER ────────────────────────────────────────────
                item {
                    SectionHeader(text = stringResource(R.string.viewfinder_section))
                    SettingsCard {
                        SettingsSwitchRow(
                            icon = Icons.Rounded.GridOn,
                            label = stringResource(R.string.grid_lines_label),
                            subtitle = stringResource(R.string.grid_lines_subtitle),
                            checked = showGridLines,
                            enabled = true,
                            testTag = "grid_lines_switch",
                            onCheckedChange = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.toggleGridLines()
                            }
                        )
                        CardDivider()
                        SelfTimerBlock(
                            selected = selfTimerMode,
                            onSelect = { mode ->
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.setSelfTimerMode(mode)
                            }
                        )
                        CardDivider()
                        FlashBlock(
                            selected = flashMode,
                            onSelect = { mode ->
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.setFlashMode(mode)
                            }
                        )
                    }
                }

                // ── GALLERY ───────────────────────────────────────────────
                item {
                    SectionHeader(text = stringResource(R.string.gallery_section))
                    SettingsCard {
                        SettingsSwitchRow(
                            icon = Icons.Rounded.PhotoLibrary,
                            label = stringResource(R.string.photo_frame_label),
                            subtitle = stringResource(R.string.photo_frame_subtitle),
                            checked = showGalleryFrame,
                            enabled = true,
                            testTag = "gallery_frame_switch",
                            onCheckedChange = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.toggleGalleryFrame()
                            }
                        )
                    }
                }

                // ── ABOUT ─────────────────────────────────────────────────
                item {
                    SectionHeader(text = stringResource(R.string.about_section))
                    SettingsCard {
                        SettingsInfoRow(
                            icon = Icons.Rounded.Info,
                            label = stringResource(R.string.app_version_label),
                            value = AppVersion.display,
                            testTag = "about_version_row"
                        )
                        CardDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                tint = colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.reset_settings_label),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.reset_settings_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.resetSettingsToDefaults()
                                },
                                modifier = Modifier.testTag("reset_settings_button")
                            ) {
                                Text(text = stringResource(R.string.reset_settings_label))
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.zoom_camera_footer),
                        color = colorScheme.onSurfaceVariant,
                        style = typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                    )
                }
            }
        }
    }
}

// ── Building blocks ─────────────────────────────────────────────────────────

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
 * Tonal card grouping related rows. One card per section keeps the page
 * scannable — the previous flat list of identical ListItems blurred the
 * Capture / Gallery boundary.
 */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            content = { content() }
        )
    }
}

@Composable
private fun CardDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
    )
}

/**
 * A Material You settings row: leading icon + expressive [ListItem] on the
 * card's tonal container with a trailing [Switch]. The row itself is tappable
 * to toggle, and the whole row dims automatically when [enabled] is false.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    label: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val colorScheme = MaterialTheme.colorScheme

    ListItem(
        // Toggle rows: the trailing Switch carries the state (system settings
        // pattern), so the row uses the plain onClick variant.
        onClick = {
            if (!enabled) return@ListItem
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onCheckedChange(!checked)
        },
        enabled = enabled,
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) colorScheme.primary else colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        },
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
                enabled = enabled,
                modifier = Modifier.testTag(testTag)
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
 * Non-interactive info row (e.g. app version): same icon + ListItem chrome as
 * the switch rows, but a value label as trailing content instead of a Switch
 * so the About section stays visually aligned with the rest of the page.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsInfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    testTag: String
) {
    val colorScheme = MaterialTheme.colorScheme
    ListItem(
        headlineContent = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
        },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        },
        trailingContent = {
            Surface(
                color = colorScheme.secondaryContainer,
                shape = RoundedCornerShape(999.dp)
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = colorScheme.surfaceContainerLow),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
    )
}

/**
 * Segmented block header: icon + label + helper line, shared by the
 * resolution / aspect / timer / flash / extension pickers so every
 * single-choice control reads as one family.
 */
@Composable
private fun SegmentedBlockHeader(
    icon: ImageVector,
    label: String,
    helper: String
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colorScheme.primary,
            modifier = Modifier
                .size(22.dp)
                .padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = helper,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Photo-quality picker: fast 3 MP vs full sensor resolution. Segmented
 * (not the old Switch) so both options and their trade-off are visible at
 * a glance — the previous "Save at full resolution" toggle hid what "off"
 * meant.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ResolutionBlock(
    selected: OutputResolution,
    onSelect: (OutputResolution) -> Unit
) {
    val options = listOf(OutputResolution.THREE_MEGAPIXEL, OutputResolution.FULL)
    val helper = when (selected) {
        OutputResolution.FULL -> stringResource(R.string.resolution_full_desc)
        OutputResolution.THREE_MEGAPIXEL -> stringResource(R.string.resolution_fast_desc)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        SegmentedBlockHeader(
            icon = Icons.Rounded.Hd,
            label = stringResource(R.string.resolution_section),
            helper = helper
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp)
        ) {
            options.forEachIndexed { index, res ->
                val tag = if (res == OutputResolution.FULL) "resolution_chip_FULL" else "resolution_chip_FAST"
                SegmentedButton(
                    selected = res == selected,
                    onClick = { if (res != selected) onSelect(res) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    label = {
                        Text(
                            text = if (res == OutputResolution.FULL)
                                stringResource(R.string.resolution_full_label)
                            else
                                stringResource(R.string.resolution_fast_label),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    modifier = Modifier.testTag(tag)
                )
            }
        }
    }
}

/**
 * Material You single-choice segmented row for the photo aspect ratio
 * (4:3 Standard, 3:2 Tall, 1:1 Square). The selected segment gets the
 * secondary-container tint; a helper line below describes the chosen ratio.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AspectRatioBlock(
    selected: AspectRatio,
    onSelect: (AspectRatio) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        SegmentedBlockHeader(
            icon = Icons.Rounded.Crop,
            label = stringResource(R.string.aspect_ratio_section),
            helper = when (selected) {
                AspectRatio.RATIO_4_3 -> stringResource(R.string.aspect_ratio_standard)
                AspectRatio.RATIO_3_2 -> stringResource(R.string.aspect_ratio_tall)
                AspectRatio.RATIO_1_1 -> stringResource(R.string.aspect_ratio_square)
            }
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp)
        ) {
            AspectRatio.entries.forEachIndexed { index, ratio ->
                SegmentedButton(
                    selected = ratio == selected,
                    onClick = {
                        if (ratio != selected) onSelect(ratio)
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
        // Keep the tinted-helper affordance of the previous design: a quiet
        // caption confirming what the chosen crop is for. Color comes from
        // onSurfaceVariant so it never competes with the section header.
        Text(
            text = when (selected) {
                AspectRatio.RATIO_4_3 -> stringResource(R.string.aspect_ratio_standard)
                AspectRatio.RATIO_3_2 -> stringResource(R.string.aspect_ratio_tall)
                AspectRatio.RATIO_1_1 -> stringResource(R.string.aspect_ratio_square)
            },
            style = MaterialTheme.typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, bottom = 8.dp)
        )
    }
}

/**
 * Self-timer picker (Off / 3s / 10s). Lives in Settings as well as on the
 * viewfinder aux row so users who never discover the bottom-deck button can
 * still find the timer — both controls write the same StateFlow.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SelfTimerBlock(
    selected: Int,
    onSelect: (Int) -> Unit
) {
    val options = listOf(0, 3, 10)
    val helper = if (selected == 0) {
        stringResource(R.string.self_timer_desc_off)
    } else {
        stringResource(R.string.self_timer_desc_on, selected)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        SegmentedBlockHeader(
            icon = Icons.Rounded.Timer,
            label = stringResource(R.string.self_timer_label),
            helper = helper
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp)
        ) {
            options.forEachIndexed { index, mode ->
                val label = if (mode == 0) {
                    stringResource(R.string.self_timer_off)
                } else {
                    stringResource(R.string.self_timer_seconds, mode)
                }
                SegmentedButton(
                    selected = mode == selected,
                    onClick = { if (mode != selected) onSelect(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    label = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    modifier = Modifier.testTag("self_timer_chip_$mode")
                )
            }
        }
    }
}

/**
 * Flash-mode picker (Auto / On / Off) mirroring the viewfinder flash button.
 * Segmented here because the aux button's cycle order (Auto → On → Off) is
 * undiscoverable — this spells the three states out.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FlashBlock(
    selected: Int,
    onSelect: (Int) -> Unit
) {
    val options = listOf(0, 1, 2)
    val helper = when (selected) {
        1 -> stringResource(R.string.flash_desc_on)
        2 -> stringResource(R.string.flash_desc_off)
        else -> stringResource(R.string.flash_desc_auto)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        SegmentedBlockHeader(
            icon = Icons.Rounded.FlashOn,
            label = stringResource(R.string.flash_label_settings),
            helper = helper
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp)
        ) {
            options.forEachIndexed { index, mode ->
                val label = when (mode) {
                    1 -> stringResource(R.string.flash_on)
                    2 -> stringResource(R.string.flash_off)
                    else -> stringResource(R.string.flash_auto)
                }
                SegmentedButton(
                    selected = mode == selected,
                    onClick = { if (mode != selected) onSelect(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    label = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    modifier = Modifier.testTag("flash_chip_$mode")
                )
            }
        }
    }
}

/**
 * OEM extension picker (Off / HDR / Blur / Auto). Only composed when the
 * device probes more than NONE, so Pixel-style devices without extensions
 * never see a dead single-chip row.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExtensionBlock(
    options: List<CaptureExtension>,
    selected: CaptureExtension,
    onSelect: (CaptureExtension) -> Unit
) {
    val helper = when (selected) {
        CaptureExtension.HDR -> stringResource(R.string.extension_subtitle_hdr)
        CaptureExtension.BOKEH -> stringResource(R.string.extension_subtitle_bokeh)
        CaptureExtension.AUTO -> stringResource(R.string.extension_subtitle_auto)
        CaptureExtension.FACE_RETOUCH -> stringResource(R.string.extension_subtitle_auto)
        CaptureExtension.NONE -> stringResource(R.string.extension_subtitle_none)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        SegmentedBlockHeader(
            icon = Icons.Rounded.Tune,
            label = stringResource(R.string.extension_label),
            helper = helper
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp)
        ) {
            options.forEachIndexed { index, ext ->
                val label = when (ext) {
                    CaptureExtension.HDR -> stringResource(R.string.extension_hdr)
                    CaptureExtension.BOKEH -> stringResource(R.string.extension_bokeh)
                    CaptureExtension.AUTO -> stringResource(R.string.extension_auto)
                    CaptureExtension.FACE_RETOUCH -> stringResource(R.string.extension_auto)
                    CaptureExtension.NONE -> stringResource(R.string.extension_none)
                }
                SegmentedButton(
                    selected = ext == selected,
                    onClick = { if (ext != selected) onSelect(ext) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    label = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    modifier = Modifier.testTag("extension_chip_${ext.name}")
                )
            }
        }
    }
}
