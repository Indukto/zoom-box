package com.example

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.color.LookDraft
import com.example.color.LookDraftValidator
import com.example.color.RetroRenderParams
import com.example.color.UserLutStore
import com.example.ui.theme.FilmDarkColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The look editor, as a bottom deck that leaves the live viewfinder visible
 * above it.
 *
 * Rendering it over the camera rather than as a full-screen page is the whole
 * design: every slider writes straight into the draft, `CameraViewModel`
 * previews the draft, and the user sees the grade change on the *real* preview
 * pipeline — the same shader the capture uses — instead of on a mock image that
 * could drift from it.
 *
 * Stateless on purpose: the draft and all callbacks come from the caller
 * (`LookEditorPanelHost`), so the deck can be screenshotted from a test with a
 * hand-built draft and no ViewModel.
 */
@Composable
internal fun LookEditorPanel(
    draft: LookDraft,
    onNameChange: (String) -> Unit,
    onLookChange: (RetroRenderParams) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onPickLut: () -> Unit,
    onClearLut: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
    lutError: Boolean = false,
    onReset: () -> Unit = {},
    // Null means "enabled when the draft is dirty". The host passes an
    // explicit value so Reset can stay enabled for a clean draft that still
    // has factory defaults to return to (a saved bundled override): tapping
    // it then routes to the restore confirm instead of a no-op undo.
    resetEnabled: Boolean? = null,
    onRestoreFactory: (() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    val look = draft.look
    val canSave = LookDraftValidator.isValidName(LookDraftValidator.sanitizeName(draft.displayName))
    val canReset = resetEnabled ?: draft.isDirty

    // Pinned to the film chrome: the deck floats over the camera, so it must
    // not flip to a light palette when the system (or dynamic color) is light.
    MaterialTheme(colorScheme = FilmDarkColorScheme) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .fillMaxHeight(0.74f)
                .testTag("look_editor_panel"),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = Color(0xFF141416).copy(alpha = 0.97f),
            contentColor = Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp)
            ) {
                // Header, on one line: close, title, save. Everything in this
                // row shares one baseline, which is the point — the draft's
                // origin used to be a second line inside this row, and that
                // left the close icon and the save label centred between two
                // text lines instead of on either of them.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onCancel()
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("look_editor_cancel")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.cancel),
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = stringResource(
                            if (draft.isNew) R.string.look_editor_title_new
                            else R.string.look_editor_title_edit
                        ),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp)
                    )
                    // Restores the sliders + LUT to what the draft started
                    // from, without touching the typed name or anything on
                    // disk — or, when the draft is clean but a saved override
                    // still shadows the factory grade (see resetEnabled), to
                    // those factory defaults via the restore confirm. Always
                    // laid out (enabled only when there is somewhere to go
                    // back to) so the header never jumps as the first slider
                    // moves.
                    TextButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onReset()
                        },
                        enabled = canReset,
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        modifier = Modifier
                            .height(40.dp)
                            .testTag("look_editor_reset")
                    ) {
                        Text(
                            text = stringResource(R.string.look_editor_reset),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSave()
                        },
                        enabled = canSave,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        // Compact enough to sit on the title's line without
                        // making the row taller than the close button beside it.
                        contentPadding = PaddingValues(horizontal = 18.dp),
                        modifier = Modifier
                            .height(36.dp)
                            .testTag("look_editor_save")
                    ) {
                        Text(
                            text = stringResource(R.string.look_editor_save),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }

                OutlinedTextField(
                    value = draft.displayName,
                    onValueChange = onNameChange,
                    singleLine = true,
                    isError = !canSave,
                    label = { Text(stringResource(R.string.look_editor_name_label)) },
                    placeholder = { Text(stringResource(R.string.look_editor_name_hint)) },
                    // One supporting line carries all three of the field's
                    // messages: a refused name, the neutral-draft warning and
                    // the draft's origin. As separate Texts stacked under the
                    // field they pushed the LUT row down and left it floating
                    // between them.
                    supportingText = {
                        Text(
                            text = when {
                                !canSave -> stringResource(R.string.look_editor_name_required)
                                // Not an error: a blank look is a legitimate
                                // starting point, but it renders identically to
                                // no look at all, and an unexplained unchanged
                                // viewfinder reads as broken.
                                look.isPassThrough ->
                                    stringResource(R.string.look_editor_neutral_warning)
                                else -> draft.basedOnLabel?.let {
                                    stringResource(R.string.look_editor_based_on, it)
                                } ?: stringResource(R.string.look_editor_based_on_none)
                            },
                            color = if (!canSave) MaterialTheme.colorScheme.error
                            else Color.White.copy(alpha = 0.55f),
                            fontSize = 11.sp
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp)
                        .testTag("look_editor_name")
                )

                // The LUT is the coarsest control in the deck — it decides what
                // every slider below is bending — so it sits above the sections
                // rather than inside one of them. Laid out on one line as well:
                // label, file, then the two taps, so all four controls read
                // along a single baseline.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .testTag("look_editor_lut_row"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CaptionText(
                        text = stringResource(R.string.look_editor_lut_label),
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = lutLabel(look.lutPath),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("look_editor_lut_name")
                    )
                    // 40dp is Material's own button minimum: these are text-only
                    // buttons, so trimming them below it would cost tap area
                    // without changing a single pixel of what is drawn.
                    TextButton(
                        onClick = onPickLut,
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        modifier = Modifier
                            .height(40.dp)
                            .testTag("look_editor_lut_choose")
                    ) {
                        Text(
                            text = stringResource(R.string.look_editor_lut_choose),
                            fontSize = 12.sp
                        )
                    }
                    // Only offered when there is something to clear: an empty
                    // "Remove" next to an empty LUT invites a pointless tap.
                    if (look.lutPath.isNotBlank()) {
                        TextButton(
                            onClick = onClearLut,
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            modifier = Modifier
                                .height(40.dp)
                                .testTag("look_editor_lut_clear")
                        ) {
                            Text(
                                text = stringResource(R.string.look_editor_lut_clear),
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
                if (lutError) {
                    Text(
                        text = stringResource(R.string.look_editor_lut_error),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .testTag("look_editor_lut_error")
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    LookSection(
                        title = stringResource(R.string.look_editor_section_tone),
                        testTag = "look_section_tone"
                    ) {
                        LookSliderRow(
                            label = stringResource(R.string.look_slider_film_curve),
                            value = look.filmCurve,
                            range = 0f..1f,
                            testTag = "look_slider_film_curve",
                            onValueChange = { onLookChange(look.copy(filmCurve = it)) }
                        )
                        LookSliderRow(
                            label = stringResource(R.string.look_slider_contrast),
                            value = look.contrast,
                            range = 0.5f..2f,
                            testTag = "look_slider_contrast",
                            onValueChange = { onLookChange(look.copy(contrast = it)) }
                        )
                        LookSliderRow(
                            label = stringResource(R.string.look_slider_saturation),
                            value = look.saturation,
                            range = 0f..2f,
                            testTag = "look_slider_saturation",
                            onValueChange = { onLookChange(look.copy(saturation = it)) }
                        )
                        LookSliderRow(
                            label = stringResource(R.string.look_slider_highlight_rolloff),
                            value = look.highlightRolloff,
                            range = 0f..1f,
                            testTag = "look_slider_rolloff",
                            onValueChange = { onLookChange(look.copy(highlightRolloff = it)) }
                        )
                        LookSliderRow(
                            label = stringResource(R.string.look_slider_fade),
                            value = look.fade,
                            range = 0f..1f,
                            testTag = "look_slider_fade",
                            onValueChange = { onLookChange(look.copy(fade = it)) }
                        )
                        LookSliderRow(
                            label = stringResource(R.string.look_slider_vignette),
                            value = look.vignette,
                            range = 0f..2f,
                            testTag = "look_slider_vignette",
                            onValueChange = { onLookChange(look.copy(vignette = it)) }
                        )
                    }

                    LookSection(
                        title = stringResource(R.string.look_editor_section_color),
                        initiallyExpanded = false,
                        testTag = "look_section_color"
                    ) {
                        LookSliderRow(
                            label = stringResource(R.string.look_slider_bloom),
                            value = look.bloom,
                            range = 0f..1f,
                            testTag = "look_slider_bloom",
                            onValueChange = { onLookChange(look.copy(bloom = it)) }
                        )
                        LookSliderRow(
                            label = stringResource(R.string.look_slider_fringing),
                            value = look.fringing,
                            range = 0f..0.05f,
                            testTag = "look_slider_fringing",
                            onValueChange = { onLookChange(look.copy(fringing = it)) }
                        )
                        LookSliderRow(
                            label = stringResource(R.string.look_slider_soft_focus),
                            value = look.softFocus,
                            range = 0f..1f,
                            testTag = "look_slider_soft_focus",
                            onValueChange = { onLookChange(look.copy(softFocus = it)) }
                        )
                        LookSliderRow(
                            label = stringResource(R.string.look_slider_milky),
                            value = look.milkyMix,
                            range = 0f..1f,
                            testTag = "look_slider_milky",
                            onValueChange = { onLookChange(look.copy(milkyMix = it)) }
                        )
                    }

                    LookSection(
                        title = stringResource(R.string.look_editor_section_split),
                        initiallyExpanded = false,
                        testTag = "look_section_split"
                    ) {
                        LookSliderRow(
                            label = stringResource(R.string.look_slider_shadow_strength),
                            value = look.shadowTintStrength,
                            range = 0f..0.5f,
                            testTag = "look_slider_shadow_strength",
                            onValueChange = { onLookChange(look.copy(shadowTintStrength = it)) }
                        )
                        LookSliderRow(
                            label = stringResource(R.string.look_slider_red),
                            value = look.shadowTintR,
                            range = -0.2f..0.2f,
                            testTag = "look_slider_shadow_red",
                            onValueChange = { onLookChange(look.copy(shadowTintR = it)) }
                        )
                        LookSliderRow(
                            label = stringResource(R.string.look_slider_green),
                            value = look.shadowTintG,
                            range = -0.2f..0.2f,
                            testTag = "look_slider_shadow_green",
                            onValueChange = { onLookChange(look.copy(shadowTintG = it)) }
                        )
                        LookSliderRow(
                            label = stringResource(R.string.look_slider_blue),
                            value = look.shadowTintB,
                            range = -0.2f..0.2f,
                            testTag = "look_slider_shadow_blue",
                            onValueChange = { onLookChange(look.copy(shadowTintB = it)) }
                        )
                        LookSliderRow(
                            label = stringResource(R.string.look_slider_highlight_strength),
                            value = look.highlightTintStrength,
                            range = 0f..0.5f,
                            testTag = "look_slider_highlight_strength",
                            onValueChange = { onLookChange(look.copy(highlightTintStrength = it)) }
                        )
                        LookSliderRow(
                            label = stringResource(R.string.look_slider_red),
                            value = look.highlightTintR,
                            range = -0.2f..0.2f,
                            testTag = "look_slider_highlight_red",
                            onValueChange = { onLookChange(look.copy(highlightTintR = it)) }
                        )
                        LookSliderRow(
                            label = stringResource(R.string.look_slider_green),
                            value = look.highlightTintG,
                            range = -0.2f..0.2f,
                            testTag = "look_slider_highlight_green",
                            onValueChange = { onLookChange(look.copy(highlightTintG = it)) }
                        )
                        LookSliderRow(
                            label = stringResource(R.string.look_slider_blue),
                            value = look.highlightTintB,
                            range = -0.2f..0.2f,
                            testTag = "look_slider_highlight_blue",
                            onValueChange = { onLookChange(look.copy(highlightTintB = it)) }
                        )
                    }

                    LookSection(
                        title = stringResource(R.string.look_editor_section_artifacts),
                        initiallyExpanded = false,
                        testTag = "look_section_artifacts"
                    ) {
                        LookSliderRow(
                            label = stringResource(R.string.look_slider_grain),
                            value = look.grainStrength,
                            range = 0f..1f,
                            testTag = "look_slider_grain",
                            onValueChange = { onLookChange(look.copy(grainStrength = it)) }
                        )
                        LookSliderRow(
                            label = stringResource(R.string.look_slider_grain_chroma),
                            value = look.grainChroma,
                            range = 0f..1f,
                            testTag = "look_slider_grain_chroma",
                            onValueChange = { onLookChange(look.copy(grainChroma = it)) }
                        )
                        LookSliderRow(
                            label = stringResource(R.string.look_slider_dust),
                            value = look.dust,
                            range = 0f..1f,
                            testTag = "look_slider_dust",
                            onValueChange = { onLookChange(look.copy(dust = it)) }
                        )
                        LookSliderRow(
                            label = stringResource(R.string.look_slider_scratch),
                            value = look.scratch,
                            range = 0f..1f,
                            testTag = "look_slider_scratch",
                            onValueChange = { onLookChange(look.copy(scratch = it)) }
                        )
                        LookSliderRow(
                            label = stringResource(R.string.look_slider_light_leak),
                            value = look.lightLeak,
                            range = 0f..1f,
                            testTag = "look_slider_light_leak",
                            onValueChange = { onLookChange(look.copy(lightLeak = it)) }
                        )
                    }

                    if (onDelete != null) {
                        TextButton(
                            onClick = onDelete,
                            modifier = Modifier
                                .padding(top = 4.dp, bottom = 16.dp)
                                .testTag("look_editor_delete")
                        ) {
                            Text(
                                text = stringResource(R.string.look_editor_delete),
                                color = Color(0xFFEF4444),
                                fontSize = 12.sp
                            )
                        }
                    } else if (onRestoreFactory != null) {
                        // Bundled looks are never deleted — their slot stays
                        // in the picker. Restoring deletes the user override
                        // so the shipped grade shows again.
                        TextButton(
                            onClick = onRestoreFactory,
                            modifier = Modifier
                                .padding(top = 4.dp, bottom = 16.dp)
                                .testTag("look_editor_restore_factory")
                        ) {
                            Text(
                                text = stringResource(R.string.look_editor_restore),
                                color = Color(0xFFEF4444),
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.height(20.dp))
                    }
                }
            }
        }
    }
}

/**
 * The one caption style in the deck, so the LUT row's label and the section
 * titles cannot drift apart as the panel is edited.
 */
@Composable
private fun CaptionText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.5f),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        maxLines = 1,
        modifier = modifier
    )
}

/**
 * Names the `.cube` the draft currently grades through, and says where it came
 * from: a bundled asset the user cannot edit reads differently from a file they
 * imported themselves.
 */
@Composable
private fun lutLabel(path: String): String = when {
    path.isBlank() -> stringResource(R.string.look_editor_lut_none)
    UserLutStore.isUserPath(path) ->
        stringResource(R.string.look_editor_lut_imported, UserLutStore.displayName(path))
    else -> stringResource(R.string.look_editor_lut_bundled, UserLutStore.displayName(path))
}

/**
 * One collapsible group of sliders. Collapsed groups keep the deck short enough
 * that the live preview above it stays a useful size on a phone.
 */
@Composable
private fun LookSection(
    title: String,
    testTag: String,
    initiallyExpanded: Boolean = true,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(modifier = Modifier.fillMaxWidth()) {
        // A hairline above each group: without it the collapsed headers ran
        // into the previous slider's track and read as one more slider.
        HorizontalDivider(
            color = Color.White.copy(alpha = 0.09f),
            modifier = Modifier.padding(vertical = 2.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .testTag(testTag)
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                modifier = Modifier.weight(1f)
            )
            // Fixed-size slot: `+` and `−` are different widths, and letting
            // each sit hard against the right edge made the glyph jump as a
            // group opened and closed.
            Box(
                modifier = Modifier.size(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (expanded) "−" else "+",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        if (expanded) content()
    }
}

/**
 * A labeled slider with a numeric readout.
 *
 * The readout is rounded and rendered with `Float.toString` deliberately: a
 * `String.format` with the default locale inside a composable is exactly the
 * pattern Compose warns about (`NonObservableLocale`), and this value is a
 * neutral coefficient, not a localized quantity.
 */
@Composable
private fun LookSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    testTag: String,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 11.sp
            )
            Text(
                text = formatLookValue(value),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { raw ->
                // 1/100 steps: fine enough for every stage, coarse enough that
                // a drag does not write a different float on every frame.
                val stepped = (raw * 100f).roundToInt() / 100f
                if (stepped != value) onValueChange(stepped)
            },
            valueRange = range,
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .testTag(testTag)
        )
    }
}

/** `0.05` stays `0.05`, `1.0` reads `1` — no locale, no trailing noise. */
private fun formatLookValue(value: Float): String {
    val rounded = (value * 100f).roundToInt() / 100f
    return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString() else rounded.toString()
}

/**
 * Binds [LookEditorPanel] to the ViewModel. Kept separate from the panel so the
 * deck itself stays pure UI (and screenshot-testable without a ViewModel), and
 * so the camera screen only has to host this one leaf — the draft is collected
 * *here*, which keeps slider ticks from recomposing the screen behind it.
 */
@Composable
internal fun LookEditorPanelHost(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier
) {
    val draft by viewModel.lookDraft.collectAsState()
    val current = draft ?: return
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    var lutError by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Storage Access Framework: the user picks a document, the app gets a read
    // grant for it, and no storage permission is involved at any point — which
    // is the only reason importing a LUT is allowed to exist in this app. The
    // grant only has to survive the copy that follows, because the LUT then
    // lives in our own directory (see UserLutStore.import).
    val lutPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // Copying and parsing up to 4 MB of text is not a frame's work.
            lutError = !withContext(Dispatchers.IO) {
                viewModel.importDraftLut(context, uri)
            }
        }
    }

    // The shipped grade when the draft edits a bundled look in place; null
    // for scratch-authored looks, which have no factory defaults to return
    // to. Read once per composition so the Reset/restore wiring below agrees
    // on what "defaults" means for this draft.
    val factory = current.factoryLook

    LookEditorPanel(
        draft = current,
        onNameChange = viewModel::updateLookDraftName,
        onLookChange = viewModel::updateLookDraft,
        onSave = { viewModel.saveLookDraft() },
        onCancel = viewModel::cancelLookDraft,
        onPickLut = {
            lutError = false
            // "*/*" deliberately: `.cube` files are routinely typed
            // application/octet-stream, or left untyped by file managers and
            // cloud providers, so any narrower filter hides the very file the
            // user tapped "Load .cube" for.
            lutPicker.launch(arrayOf("*/*"))
        },
        onClearLut = {
            lutError = false
            viewModel.clearDraftLut()
        },
        onDelete = if (current.id != null && factory == null) {
            { confirmDelete = true }
        } else null,
        lutError = lutError,
        onReset = {
            // A reset restores the source LUT, so a stale import error must
            // not linger under the restored row.
            lutError = false
            // Dirty means unsaved slider moves: undo those back to the saved
            // grade. Clean with a factory restore pending means the draft
            // already shows the saved edit, so Reset is the way back to the
            // factory defaults — same confirm as the row at the deck's foot.
            if (current.isDirty) viewModel.resetLookDraft()
            else if (factory != null && current.look != factory) {
                confirmRestore = true
            }
        },
        // Enabled while slider moves can be undone, and while a clean draft
        // still has factory defaults to return to. Without the second half
        // a saved edit could never be reset again: reopening lands clean on
        // the saved grade, so dirty-only Reset would sit disabled forever.
        resetEnabled = current.isDirty || (factory != null && current.look != factory),
        onRestoreFactory = if (current.id != null && factory != null) {
            { confirmRestore = true }
        } else null,
        modifier = modifier
    )

    if (confirmDelete) {
        val id = current.id
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = {
                Text(
                    text = stringResource(R.string.look_editor_delete_title),
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(
                        R.string.look_editor_delete_message,
                        current.displayName
                    ),
                    color = Color.White.copy(alpha = 0.75f)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (id != null) viewModel.deleteUserLook(id)
                        confirmDelete = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEF4444),
                        contentColor = Color.White
                    ),
                    modifier = Modifier.testTag("look_editor_delete_confirm")
                ) {
                    Text(stringResource(R.string.delete), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.cancel), color = Color.White)
                }
            },
            containerColor = Color(0xFF1E1E1E),
            tonalElevation = 0.dp
        )
    }

    if (confirmRestore) {
        val id = current.id
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = {
                Text(
                    text = stringResource(R.string.look_editor_restore_title),
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(
                        R.string.look_editor_restore_message,
                        current.displayName
                    ),
                    color = Color.White.copy(alpha = 0.75f)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (id != null) {
                            lutError = false
                            viewModel.restoreBundledLook(id)
                        }
                        confirmRestore = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEF4444),
                        contentColor = Color.White
                    ),
                    modifier = Modifier.testTag("look_editor_restore_confirm")
                ) {
                    Text(stringResource(R.string.look_editor_restore), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = false }) {
                    Text(stringResource(R.string.cancel), color = Color.White)
                }
            },
            containerColor = Color(0xFF1E1E1E),
            tonalElevation = 0.dp
        )
    }
}
