@file:Suppress("unused", "UnusedImports")

package com.example

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.color.profileId
import com.example.zoom.AspectRatio
import com.example.zoom.CaptureExtension
import com.example.zoom.LensRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("camera_settings")

/**
 * Saved resolution of JPEG captures. Each value carries the matching
 * `inSampleSize` to feed into `BitmapFactory.Options` on the full-decode
 * path in `processAndSavePhoto` (and into `BitmapRegionDecoder.decodeRegion`
 * on the crop path):
 *
 *  - [FULL] (inSampleSize = 1)            — full sensor resolution, ~3-4 s
 *                                            capture
 *  - [THREE_MEGAPIXEL] (inSampleSize = 2) — halved each axis (~3 MP),
 *                                            ~1 s capture, plenty for the
 *                                            retro filter aesthetic
 *  - [VINTAGE_DIGICAM] (inSampleSize = 4) — quartered each axis (~0.8 MP),
 *                                            soft old-digicam look,
 *                                            instant save
 *
 * When the cropped area is below 90 % of full-frame, the region decoder
 * scales the cropped rect by the same inSampleSize, so this preference
 * affects both paths.
 */
enum class OutputResolution(val inSampleSize: Int) {
    FULL(1),
    THREE_MEGAPIXEL(2),
    VINTAGE_DIGICAM(4);

    companion object {
        /** Parse a stored enum name with a safe fallback to the default (3 MP). */
        fun fromKey(key: String?): OutputResolution =
            key?.let { name -> runCatching { valueOf(name) }.getOrNull() }
                ?: THREE_MEGAPIXEL
    }
}

class UserPreferencesRepository(private val context: Context) {

    companion object {
        /**
         * The look the user last picked, stored as a [CameraProfileRegistry]
         * look id (`"warm_portrait"`) rather than a [FilmPreset] enum name
         * (`"WARM_PORTRAIT"`). The DataStore key string itself is unchanged,
         * so existing installs keep their selection; [resolveLookId] translates
         * the legacy enum names written before the registry-driven picker.
         *
         * An id that no longer resolves is preserved verbatim instead of being
         * rewritten to the default: a look whose asset fails to load on one
         * launch must not silently erase the user's choice.
         */
        val DEFAULT_LOOK_ID: String = FilmPreset.WARM_PORTRAIT.profileId

        /**
         * Upper bound for the legacy Film-Style scroll index. The grid picker
         * that replaced the old LazyRow no longer reads this, so the clamp is
         * a fixed sanity bound rather than the size of a particular catalog.
         */
        private const val MAX_LEGACY_SCROLL_INDEX = 63

        /** Maps a persisted value (look id or legacy enum name) to a look id. */
        fun resolveLookId(stored: String?): String {
            val raw = stored?.trim().orEmpty()
            if (raw.isEmpty()) return DEFAULT_LOOK_ID
            // Legacy installs stored the enum name. Exact match first so a
            // future enum/asset id collision can't be resolved the wrong way.
            FilmPreset.entries.firstOrNull { it.name == raw }?.let { return it.profileId }
            return raw.lowercase()
        }

        private val RAW_MODE = booleanPreferencesKey("raw_mode")
        private val ASPECT_RATIO = stringPreferencesKey("aspect_ratio")
        private val ACTIVE_LOOK = stringPreferencesKey("active_preset")
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
        // File names the user starred in the gallery. Stored by name (not
        // absolute path) so favorites survive reinstalls the same way the
        // public MediaStore mirror does.
        private val FAVORITE_PHOTOS = stringSetPreferencesKey("favorite_photos")
    }

    data class Settings(
        val rawModeEnabled: Boolean = false,
        val aspectRatio: AspectRatio = AspectRatio.DEFAULT,
        /** Active look id — see [ACTIVE_LOOK]. */
        val activeLookId: String = DEFAULT_LOOK_ID,
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
        val outputResolution: OutputResolution = OutputResolution.THREE_MEGAPIXEL,
        val favoritePhotoNames: Set<String> = emptySet()
    )

    val settingsFlow: Flow<Settings> = context.settingsDataStore.data.map { prefs ->
        Settings(
            rawModeEnabled = prefs[RAW_MODE] ?: false,
            aspectRatio = prefs[ASPECT_RATIO]?.let { name ->
                try { AspectRatio.valueOf(name) } catch (_: Exception) { AspectRatio.DEFAULT }
            } ?: AspectRatio.DEFAULT,
            activeLookId = resolveLookId(prefs[ACTIVE_LOOK]),
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
            // Clamp to a sane non-negative index so a previously persisted
            // out-of-range index (saved before a preset was added/removed)
            // can't be handed back to the picker. The grid picker that
            // replaced the LazyRow ignores this entirely; it is kept so the
            // persisted keys stay readable.
            filmStyleScrollIndex = (prefs[FILM_STYLE_SCROLL_INDEX] ?: 0)
                .coerceIn(0, MAX_LEGACY_SCROLL_INDEX),
            filmStyleScrollOffset = prefs[FILM_STYLE_SCROLL_OFFSET] ?: 0,
            outputResolution = OutputResolution.fromKey(prefs[OUTPUT_RESOLUTION]),
            favoritePhotoNames = prefs[FAVORITE_PHOTOS] ?: emptySet()
        )
    }

    suspend fun saveRawMode(enabled: Boolean) {
        context.settingsDataStore.edit { it[RAW_MODE] = enabled }
    }

    suspend fun saveAspectRatio(ratio: AspectRatio) {
        context.settingsDataStore.edit { it[ASPECT_RATIO] = ratio.name }
    }

    suspend fun saveActiveLookId(lookId: String) {
        context.settingsDataStore.edit { it[ACTIVE_LOOK] = lookId }
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

    suspend fun saveFavoritePhotoNames(names: Set<String>) {
        context.settingsDataStore.edit { it[FAVORITE_PHOTOS] = names }
    }
}
