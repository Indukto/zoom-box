@file:Suppress("unused", "UnusedImports")

package com.example

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.zoom.AspectRatio
import com.example.zoom.CaptureExtension
import com.example.zoom.LensRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("camera_settings")

/**
 * Saved resolution of JPEG captures. Each value carries the matching
 * `inSampleSize` to feed into `BitmapFactory.Options` on the full-decode
 * path in `processAndSavePhoto`:
 *
 *  - [FULL] (inSampleSize = 1)            — full sensor resolution, ~3-4 s
 *                                            capture
 *  - [THREE_MEGAPIXEL] (inSampleSize = 2) — halved each axis (~3 MP),
 *                                            ~1 s capture, plenty for the
 *                                            retro filter aesthetic
 *
 * The crop-region path (BitmapRegionDecoder.decodeRegion) does NOT honor
 * inSampleSize, so this preference only affects the no-zoom / no-native-
 * focal-crop path. When the cropped area is below 90 % of full-frame,
 * the saved file uses the source pixel dimensions of the cropped rect
 * regardless of this setting.
 */
enum class OutputResolution(val inSampleSize: Int) {
    FULL(1),
    THREE_MEGAPIXEL(2);

    companion object {
        /** Parse a stored enum name with a safe fallback to the default (3 MP). */
        fun fromKey(key: String?): OutputResolution =
            key?.let { name -> runCatching { valueOf(name) }.getOrNull() }
                ?: THREE_MEGAPIXEL
    }
}

class UserPreferencesRepository(private val context: Context) {

    companion object {
        private val RAW_MODE = booleanPreferencesKey("raw_mode")
        private val ASPECT_RATIO = stringPreferencesKey("aspect_ratio")
        private val ACTIVE_PRESET = stringPreferencesKey("active_preset")
        private val FLASH_MODE = intPreferencesKey("flash_mode")
        private val SHOW_GRID_LINES = booleanPreferencesKey("show_grid_lines")
        private val GALLERY_FRAME = booleanPreferencesKey("gallery_frame")
        private val SELF_TIMER_MODE = intPreferencesKey("self_timer_mode")
        private val DOUBLE_EXPOSURE = booleanPreferencesKey("double_exposure")
        private val IS_FRONT_CAMERA = booleanPreferencesKey("is_front_camera")
        private val ACTIVE_EXTENSION = stringPreferencesKey("active_extension")
        private val SELECTED_LENS_ROLE = stringPreferencesKey("selected_lens_role")
        // Preserves the LazyRow's horizontal scroll position inside the
        // "Film Style" bottom-sheet picker across sessions. Without these
        // two keys the picker always resets to the leftmost preset when
        // the sheet is re-opened, even though the active preset itself is
        // already persisted — so the user's *browse* progress is lost
        // even when their *selection* isn't.
        private val FILM_STYLE_SCROLL_INDEX = intPreferencesKey("film_style_scroll_index")
        private val FILM_STYLE_SCROLL_OFFSET = intPreferencesKey("film_style_scroll_offset")
        private val OUTPUT_RESOLUTION = stringPreferencesKey("output_resolution")
    }

    data class Settings(
        val rawModeEnabled: Boolean = false,
        val aspectRatio: AspectRatio = AspectRatio.DEFAULT,
        val activePreset: FilmPreset = FilmPreset.WARM_PORTRAIT,
        val flashMode: Int = 0,
        val showGridLines: Boolean = false,
        val showGalleryFrame: Boolean = false,
        val selfTimerMode: Int = 0,
        val doubleExposureActive: Boolean = false,
        val isFrontCamera: Boolean = false,
        val activeExtension: CaptureExtension = CaptureExtension.NONE,
        val selectedLensRole: LensRole = LensRole.PRIMARY,
        val filmStyleScrollIndex: Int = 0,
        val filmStyleScrollOffset: Int = 0,
        val outputResolution: OutputResolution = OutputResolution.THREE_MEGAPIXEL
    )

    val settingsFlow: Flow<Settings> = context.settingsDataStore.data.map { prefs ->
        Settings(
            rawModeEnabled = prefs[RAW_MODE] ?: false,
            aspectRatio = prefs[ASPECT_RATIO]?.let { name ->
                try { AspectRatio.valueOf(name) } catch (_: Exception) { AspectRatio.DEFAULT }
            } ?: AspectRatio.DEFAULT,
            activePreset = prefs[ACTIVE_PRESET]?.let { name ->
                try { FilmPreset.valueOf(name) } catch (_: Exception) { FilmPreset.WARM_PORTRAIT }
            } ?: FilmPreset.WARM_PORTRAIT,
            flashMode = prefs[FLASH_MODE] ?: 0,
            showGridLines = prefs[SHOW_GRID_LINES] ?: false,
            showGalleryFrame = prefs[GALLERY_FRAME] ?: false,
            selfTimerMode = prefs[SELF_TIMER_MODE] ?: 0,
            doubleExposureActive = prefs[DOUBLE_EXPOSURE] ?: false,
            isFrontCamera = prefs[IS_FRONT_CAMERA] ?: false,
            activeExtension = prefs[ACTIVE_EXTENSION]?.let { name ->
                try { CaptureExtension.valueOf(name) } catch (_: Exception) { CaptureExtension.NONE }
            } ?: CaptureExtension.NONE,
            selectedLensRole = prefs[SELECTED_LENS_ROLE]?.let { name ->
                try { LensRole.valueOf(name) } catch (_: Exception) { LensRole.PRIMARY }
            } ?: LensRole.PRIMARY,
            // Clamp to a sane non-negative index, and to the current enum
            // size so a previously persisted out-of-range index (saved
            // before a preset was added/removed — e.g. NORMAL shifting
            // from index 8 → 9 when a new preset is inserted before it)
            // doesn't strand the LazyListState on a non-existent chip.
            // The Compose layer clamps again with the same upper bound.
            filmStyleScrollIndex = (prefs[FILM_STYLE_SCROLL_INDEX] ?: 0)
                .coerceIn(0, FilmPreset.entries.size - 1),
            filmStyleScrollOffset = prefs[FILM_STYLE_SCROLL_OFFSET] ?: 0,
            outputResolution = OutputResolution.fromKey(prefs[OUTPUT_RESOLUTION])
        )
    }

    suspend fun saveRawMode(enabled: Boolean) {
        context.settingsDataStore.edit { it[RAW_MODE] = enabled }
    }

    suspend fun saveAspectRatio(ratio: AspectRatio) {
        context.settingsDataStore.edit { it[ASPECT_RATIO] = ratio.name }
    }

    suspend fun saveActivePreset(preset: FilmPreset) {
        context.settingsDataStore.edit { it[ACTIVE_PRESET] = preset.name }
    }

    suspend fun saveFlashMode(mode: Int) {
        context.settingsDataStore.edit { it[FLASH_MODE] = mode }
    }

    suspend fun saveShowGridLines(enabled: Boolean) {
        context.settingsDataStore.edit { it[SHOW_GRID_LINES] = enabled }
    }

    suspend fun saveGalleryFrame(enabled: Boolean) {
        context.settingsDataStore.edit { it[GALLERY_FRAME] = enabled }
    }

    suspend fun saveSelfTimerMode(mode: Int) {
        context.settingsDataStore.edit { it[SELF_TIMER_MODE] = mode }
    }

    suspend fun saveDoubleExposure(enabled: Boolean) {
        context.settingsDataStore.edit { it[DOUBLE_EXPOSURE] = enabled }
    }

    suspend fun saveIsFrontCamera(isFront: Boolean) {
        context.settingsDataStore.edit { it[IS_FRONT_CAMERA] = isFront }
    }

    suspend fun saveActiveExtension(ext: CaptureExtension) {
        context.settingsDataStore.edit { it[ACTIVE_EXTENSION] = ext.name }
    }

    suspend fun saveSelectedLensRole(role: LensRole) {
        context.settingsDataStore.edit { it[SELECTED_LENS_ROLE] = role.name }
    }

    /**
     * Persist the LazyRow scroll position of the "Film Style" picker so the
     * browse position survives both closing the bottom sheet and fully
     * relaunching the app. Negative offsets (which can come from edge-case
     * overscroll on some OEMs) are clamped to 0 so the next session starts
     * at the saved item without artefacts.
     */
    suspend fun saveFilmStyleScrollPosition(index: Int, offset: Int) {
        context.settingsDataStore.edit {
            it[FILM_STYLE_SCROLL_INDEX] = index.coerceAtLeast(0)
            it[FILM_STYLE_SCROLL_OFFSET] = offset.coerceAtLeast(0)
        }
    }

    suspend fun saveOutputResolution(resolution: OutputResolution) {
        context.settingsDataStore.edit { it[OUTPUT_RESOLUTION] = resolution.name }
    }
}
