@file:Suppress(
    "unused",
    "UnusedImport",
    "UnusedImports",
    "ExifInterface",
    "RedundantQualifierName",
    "RemoveRedundantQualifierName",
    "RedundantSuppression"
)

package com.example

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.media.MediaActionSound
import android.media.ExifInterface
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.Stable
import com.example.color.CubeLut
import com.example.color.CubeLutParser
import com.example.color.CameraProfileRegistry
import com.example.color.GpuCaptureProcessor
import com.example.color.RetroRenderParams
import com.example.color.toRetroRenderParams
// LutColorFilter is no longer called here — its trilinear blend is now
// inlined into applyRetroFilter's parallel chunks (one pixel pass total).
import com.example.zoom.AspectRatio
import com.example.zoom.CaptureExtension
import com.example.zoom.FovMapper
import com.example.zoom.LensCatalog
import com.example.zoom.LensRole
import com.example.zoom.PreviewSessionManager
import com.example.zoom.RawCapture
import com.example.zoom.ZoomBoxCalculator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.time.Duration.Companion.milliseconds

@Stable
data class ExifData(
    val focalLength: String = "--",
    val shutterSpeed: String = "--",
    val iso: String = "--",
    /**
     * EXIF orientation tag rendered as a short label ("90°", "180°",
     * "270°", "Mirrored H/V", …). "--" when the tag is absent or NORMAL
     * (which is what this app writes: pixels are baked upright at save
     * time, so no rotation is needed for display).
     */
    val orientation: String = "--"
)

/**
 * A film profile backed by a 3D `.cube` LUT in `assets/`.
 *
 * The LUT defines the base color grade; the default slider values are applied
 * on top of it when the preset is selected and remain user-adjustable.
 */
@Stable
enum class FilmPreset(
    val displayName: String,
    val assetPath: String,
    val defaultTemp: Float = 0f,
    val defaultTint: Float = 0f,
    val defaultExposure: Float = 0f,
    /**
     * Film-grain strength added on top of the LUT at save time, in [0, 1].
     * Real film stocks have visible silver-halide / dye-cloud grain that
     * varies with ISO and emulsion type — higher for instant / fast films,
     * lower for slow color negatives. Creative presets get a subtle dose
     * for a cohesive analog feel.
     */
    val defaultGrainStrength: Float = 0f,
    /**
     * Per-channel chromatic grain fraction in [0, 1] (R, G, B independent
     * noise samples mixed into the monochrome delta). 0 = strictly achromatic
     * grain (B&W negatives, where any chroma speckle looks wrong against the
     * paper response). Higher values introduce colored "dye cloud" speckles,
     * useful if a future color stock preset wants a warm-toned organic grain.
     */
    val defaultGrainChroma: Float = 0f,
    // ── New per-preset tonal & creative parameters ──────────────────────
    /**
     * Film S-curve strength in [0, 1]. Applies a characteristic S-shaped
     * tone response: a gentle toe lifts shadows, a smooth shoulder compresses
     * highlights. 0 = linear (no curve). Real films have a pronounced S-curve
     * in their D-log-E response.
     */
    val defaultFilmCurve: Float = 0f,
    /**
     * Per-preset contrast multiplier. 1.0 = neutral, <1.0 reduces contrast,
     * >1.0 increases contrast. Applied after the LUT as a final tonal tweak.
     */
    val defaultContrast: Float = 1.0f,
    /**
     * Per-preset saturation multiplier. 1.0 = neutral, <1.0 desaturates,
     * >1.0 saturates more. Applied after the LUT.
     */
    val defaultSaturation: Float = 1.0f,
    /**
     * Halation / bloom strength in [0, 1]. Simulates light scattering through
     * film emulsion layers, creating a warm glow around bright highlights.
     * Key characteristic of color negative stocks like Portra.
     */
    val defaultBloom: Float = 0f,
    /**
     * Split toning — shadow tint as (R, G, B) additive offset in [0, 1] range.
     * These are added weighted by (1 - luma), so darker regions get more tint.
     */
    val shadowTintR: Float = 0f,
    val shadowTintG: Float = 0f,
    val shadowTintB: Float = 0f,
    /**
     * Shadow tint strength in [0, 1]. Scales the shadow color offset.
     */
    val shadowTintStrength: Float = 0f,
    /**
     * Split toning — highlight tint as (R, G, B) additive offset in [0, 1] range.
     * These are added weighted by luma, so brighter regions get more tint.
     */
    val highlightTintR: Float = 0f,
    val highlightTintG: Float = 0f,
    val highlightTintB: Float = 0f,
    /**
     * Highlight tint strength in [0, 1]. Scales the highlight color offset.
     */
    val highlightTintStrength: Float = 0f,
    /**
     * Chromatic fringing strength in pixels (normalized to texture coords).
     * Shifts the R and B channels relative to G to simulate color channel
     * misregistration in instant film. 0 = no fringing.
     */
    val defaultFringing: Float = 0f,
    // ── Dreamcore-style extras ────────────────────────────────────────────
    /**
     * Soft-focus blur strength in [0, 1]. Blends each pixel with a 3x3 box
     * blur kernel of its (WB+exposed) neighbours so the image reads as
     * slightly out-of-focus / hazy. 0 = sharp (no blur applied); 1 = full
     * 3x3 box blur. Used by the dreamcore-style preset to simulate the
     * characteristic gauzy / "almost-not-there" focus of dreamcore
     * photography without resampling the full sensor.
     */
    val defaultSoftFocus: Float = 0f,
    /**
     * Strength of the milky pastel haze overlay in [0, 1]. Blends the
     * output toward the [milkyTintR]/[milkyTintG]/[milkyTintB] cream color
     * with strength weighted toward darker regions, producing the
     * signature dreamcore "frosted glass" / pastel-wash look. 0 = no
     * overlay.
     */
    val defaultMilkyMix: Float = 0f,
    /**
     * Per-channel color of the milky haze overlay (R, G, B independent),
     * each in [0, 1]. Presets default to 0 (the overlay is skipped
     * entirely when
     * [defaultMilkyMix] = 0 so the zero defaults are inert).
     */
    val milkyTintR: Float = 0f,
    val milkyTintG: Float = 0f,
    val milkyTintB: Float = 0f,
    // ── Opt-in artifacts (zero defaults keep existing presets pixel-identical) ──
    /**
     * Filmic highlight roll-off in [0, 1]. Compresses the highlights above
     * a soft ~0.7 knee into a rounded shoulder; 0 = linear (no roll-off).
     * Consumed identically by the live GL shader and the CPU capture filter.
     */
    val defaultHighlightRolloff: Float = 0f,
    /**
     * Black-point fade in [0, 1]. Lifts shadows toward mid-gray while
     * leaving mid-tones and highlights nearly untouched; 0 = no fade.
     */
    val defaultFade: Float = 0f) {

    WARM_PORTRAIT(
        "Warm Portrait",
        "luts/kodak_portra_160_vc.cube",
        defaultGrainStrength = 0.05f,
        defaultGrainChroma = 0.3f,       // dye-cloud grain from color emulsion layers
        defaultFilmCurve = 0.20f,
        defaultContrast = 1.05f,
        defaultSaturation = 1.0f,
        defaultBloom = 0.18f,
        shadowTintR = 0.0f, shadowTintG = 0.0f, shadowTintB = 0.025f,  // cool blue shadows
        shadowTintStrength = 0.06f,
        highlightTintR = 0.04f, highlightTintG = 0.02f, highlightTintB = 0.0f,  // warm highlights
        highlightTintStrength = 0.08f,
        defaultFringing = 0.0f
    ),
    MONOCHROME_400(
        "Monochrome 400",
        "luts/kodak_bw_400_cn.cube",
        defaultGrainStrength = 0.32f,
        defaultGrainChroma = 0f,          // strict mono — chroma speckles would look wrong on B&W
        defaultFilmCurve = 0.35f,
        defaultContrast = 1.35f,
        defaultSaturation = 0.0f,
        defaultBloom = 0.0f,
        shadowTintR = 0f, shadowTintG = 0f, shadowTintB = 0f,
        shadowTintStrength = 0f,
        highlightTintR = 0f, highlightTintG = 0f, highlightTintB = 0f,
        highlightTintStrength = 0f,
        defaultFringing = 0.0f
    ),
    INSTANT_CLASSIC(
        "Instant Classic",
        "luts/polaroid_px-680.cube",
        defaultGrainStrength = 0.18f,
        defaultGrainChroma = 0.35f,       // high-ISO instant film has chunky dye clouds
        defaultFilmCurve = 0.20f,
        defaultContrast = 1.10f,
        defaultSaturation = 1.15f,
        defaultBloom = 0.25f,
        shadowTintR = 0.0f, shadowTintG = 0.005f, shadowTintB = 0.01f,
        shadowTintStrength = 0.04f,
        highlightTintR = 0.04f, highlightTintG = 0.015f, highlightTintB = 0.0f,
        highlightTintStrength = 0.06f,
        defaultFringing = 0.006f
    ),
    CROSS_PROCESS(
        "Cross Process",
        "luts/kodak_elite_100_xpro.cube",
        defaultGrainStrength = 0.08f,
        defaultGrainChroma = 0.20f,       // cross-processing accentuates color grain
        defaultFilmCurve = 0.25f,
        defaultContrast = 1.20f,
        defaultSaturation = 1.30f,
        defaultBloom = 0.05f,
        shadowTintR = 0.0f, shadowTintG = 0.015f, shadowTintB = 0.02f,  // green/cyan shadows
        shadowTintStrength = 0.08f,
        highlightTintR = 0.03f, highlightTintG = 0.02f, highlightTintB = 0.0f,  // warm highlights
        highlightTintStrength = 0.07f,
        defaultFringing = 0.003f
    ),
    INSTANT_VINTAGE(
        "Instant Vintage",
        "luts/polaroid_669_++.cube",
        defaultGrainStrength = 0.10f,
        defaultGrainChroma = 0.25f,       // peel-apart film with visible dye specks
        defaultFilmCurve = 0.20f,
        defaultContrast = 1.15f,
        defaultSaturation = 1.20f,
        defaultBloom = 0.20f,
        shadowTintR = 0.0f, shadowTintG = 0.0f, shadowTintB = 0.015f,
        shadowTintStrength = 0.05f,
        highlightTintR = 0.03f, highlightTintG = 0.01f, highlightTintB = 0.0f,
        highlightTintStrength = 0.07f,
        defaultFringing = 0.008f
    ),
    MOODY(
        "Moody",
        "luts/moody.cube",
        defaultGrainStrength = 0.12f,
        defaultGrainChroma = 0.15f,       // light grain adds to the moody aesthetic
        defaultFilmCurve = 0.50f,
        defaultContrast = 1.40f,
        defaultSaturation = 0.85f,
        defaultBloom = 0.08f,
        shadowTintR = 0.0f, shadowTintG = 0.0f, shadowTintB = 0.04f,  // strong blue shadows
        shadowTintStrength = 0.15f,
        highlightTintR = 0.06f, highlightTintG = 0.03f, highlightTintB = 0.0f,  // strong warm highlights
        highlightTintStrength = 0.12f,
        defaultFringing = 0.0f
    ),
    MUTED_MEADOW(
        "Muted Meadow",
        "luts/Muted Meadow.cube",
        defaultGrainStrength = 0.06f,
        defaultGrainChroma = 0.20f,
        defaultFilmCurve = 0.15f,
        defaultContrast = 1.05f,
        defaultSaturation = 0.70f,
        defaultBloom = 0.10f,
        shadowTintR = 0.0f, shadowTintG = 0.015f, shadowTintB = 0.02f,  // teal shadows
        shadowTintStrength = 0.08f,
        highlightTintR = 0.02f, highlightTintG = 0.01f, highlightTintB = 0.0f,  // warm highlights
        highlightTintStrength = 0.05f,
        defaultFringing = 0.0f
    ),
    SUNLIT_SPILL(
        "Sunlit Spill",
        "luts/Sunlit Spill.cube",
        defaultGrainStrength = 0.08f,
        defaultGrainChroma = 0.25f,       // warm halation-style chroma grain
        defaultFilmCurve = 0.10f,
        defaultContrast = 1.0f,
        defaultSaturation = 1.10f,
        defaultBloom = 0.30f,
        shadowTintR = 0.0f, shadowTintG = 0.0f, shadowTintB = 0.0f,  // neutral shadows
        shadowTintStrength = 0.0f,
        highlightTintR = 0.04f, highlightTintG = 0.025f, highlightTintB = 0.0f,  // golden highlights
        highlightTintStrength = 0.10f,
        defaultFringing = 0.002f
    ),

    /**
     * Pass-through preset — no LUT, no grain, no film curve, identity
     * contrast/saturation, no bloom, no split toning, no fringing. Picking
     * this profile is equivalent to a stock phone camera: the sensor
     * image is captured with raw white-balance applied and zero
     * film-grade processing on top.
     *
     * Placed LAST in the enum (rather than first) so that the existing
     * DataStore-persisted `filmStyleScrollIndex` / `activePreset` keep
     * their old mapping for upgraded installs: index 0 stays
     * WARM_PORTRAIT instead of silently shifting to NORMAL on relaunch.
     *
     * The blank `assetPath` is the signal to `loadLut()` to skip the
     * CubeLutParser entirely (it short-circuits on `isBlank()` rather
     * than throwing an IOException each time the preset is selected).
     */
    NORMAL(
        "Normal",
        ""
    );
}

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val prefsRepo = UserPreferencesRepository(application)

    // Reuse the large ARGB pixel buffer on the worker thread. The filter still
    // reads and writes the exact same pixels; this only avoids allocating and
    // collecting a multi-megapixel IntArray for every capture. A holder-level
    // inUse flag prevents overlapping processing coroutines on the same worker
    // from sharing the mutable arrays.
    private class FilterBuffers {
        var pixels = IntArray(0)
        var rowDistanceSquared = FloatArray(0)
        /**
         * Pre-capture snapshot of [pixels] used by the optional
         * soft-focus pre-pass so neighbor reads see unmodified input
         * (in-place mutation + parallel chunks otherwise corrupts a
         * same-frame blur). Lazily grown to match the largest
         * [pixelCount] we have ever processed; cuts GC pressure on
         * repeated captures with the same resolution. Null when
         * soft-focus is not active for the current preset — in which
         * case the per-pixel loop's blur read short-circuits.
         */
        var softFocusSnapshot: IntArray? = null
        var inUse = false
    }

    private val filterBuffers = ThreadLocal<FilterBuffers>()

    // Avoid retaining unusually large buffers forever on a pooled dispatcher
    // thread. This is large enough for typical full-resolution phone captures
    // while bounding memory after an outlier image.
    private companion object {
        const val MAX_RETAINED_FILTER_PIXELS = 16_000_000

        /**
         * When true, captures try the GPU still pipeline
         * ([GpuCaptureProcessor]) first and fall back to the CPU filter on
         * any EGL/shader failure. Kept off by default until the EGL path is
         * validated against the CPU output on real Adreno/Mali hardware.
         */
        const val USE_GPU_CAPTURE = true
    }

    private val _selectedLensRole = MutableStateFlow(LensRole.PRIMARY)
    val selectedLensRole: StateFlow<LensRole> = _selectedLensRole.asStateFlow()

    // User-facing JPEG resolution preference (3 MP / full sensor resolution).
    // Both branches inside processAndSavePhoto read
    // `_outputResolution.value.inSampleSize` and pass it through to the
    // decoder — the full-decode BitmapFactory branch via Options.inSampleSize,
    // and the crop-region BitmapRegionDecoder branch via the Options object
    // given to decodeRegion.
    private val _outputResolution = MutableStateFlow(OutputResolution.THREE_MEGAPIXEL)
    val outputResolution: StateFlow<OutputResolution> = _outputResolution.asStateFlow()

    // ── Film-style picker scroll position ─────────────────────────────────
    // Cached snapshot of the LazyListState's first-visible cell, exposed to
    // the UI so `rememberLazyListState(...)` can seed its initial scroll on
    // open. These mirror the DataStore copy but live in memory so the
    // first composition can read them without an async hop.

    private val _filmStyleScrollIndex = MutableStateFlow(0)
    val filmStyleScrollIndex: StateFlow<Int> = _filmStyleScrollIndex.asStateFlow()

    private val _filmStyleScrollOffset = MutableStateFlow(0)
    val filmStyleScrollOffset: StateFlow<Int> = _filmStyleScrollOffset.asStateFlow()

    private val _previewLensRole = MutableStateFlow(LensRole.PRIMARY)
    val previewLensRole: StateFlow<LensRole> = _previewLensRole.asStateFlow()

    private val _captureLensRole = MutableStateFlow(LensRole.PRIMARY)
    val captureLensRole: StateFlow<LensRole> = _captureLensRole.asStateFlow()

    private val _digitalZoomRatio = MutableStateFlow(1.0f)
    val digitalZoomRatio: StateFlow<Float> = _digitalZoomRatio.asStateFlow()

    private val _effectiveFocalLength = MutableStateFlow(24)
    val effectiveFocalLength: StateFlow<Int> = _effectiveFocalLength.asStateFlow()

    private val _boxScale = MutableStateFlow(1f)
    val boxScale: StateFlow<Float> = _boxScale.asStateFlow()

    private val _availableFocalLengths = MutableStateFlow<List<Float>>(listOf(24f, 77f))
    val availableFocalLengths: StateFlow<List<Float>> = _availableFocalLengths.asStateFlow()

    var lensCatalogResult: LensCatalog.CatalogResult? = null
        private set

    private val _exposure = MutableStateFlow(0f)
    val exposure: StateFlow<Float> = _exposure.asStateFlow()

    private val _temperature = MutableStateFlow(0f)
    val temperature: StateFlow<Float> = _temperature.asStateFlow()

    private val _tint = MutableStateFlow(0f)
    val tint: StateFlow<Float> = _tint.asStateFlow()

    // Start in the pass-through route while persisted settings are loading.
    // CameraUi waits for settingsLoaded before creating any preview surface, so
    // the startup route never flips from a LUT GLSurfaceView to PreviewView.
    private val _activePreset = MutableStateFlow(FilmPreset.NORMAL)
    val activePreset: StateFlow<FilmPreset> = _activePreset.asStateFlow()

    private val _settingsLoaded = MutableStateFlow(false)
    val settingsLoaded: StateFlow<Boolean> = _settingsLoaded.asStateFlow()

    // ── Physical device rotation (sensor-tracked) ─────────────────────────
    // The activity is locked to portrait, so Display.getRotation() keeps
    // reporting ROTATION_0 even when the phone is physically held sideways.
    // CameraX's docs prescribe an OrientationEventListener for exactly this
    // case: it tracks the sensor-derived device orientation so the capture
    // target rotation can follow how the phone is actually held — that's
    // what makes a photo taken in landscape come out landscape.
    private val _physicalRotation = MutableStateFlow(Surface.ROTATION_0)
    val physicalRotation: StateFlow<Int> = _physicalRotation.asStateFlow()

    private var orientationListener: OrientationEventListener? = null

    // Lazily-parsed LUTs keyed by asset path. Parsed once on first use and
    // reused for every subsequent capture that selects the same film.
    private val cachedLuts = mutableMapOf<String, CubeLut>()

    // Backend look profiles (assets/cameras/*.json). JSON wins over the
    // in-code FilmPreset adapter when a matching id exists, so adding a look
    // no longer requires an enum edit. Loaded lazily on the capture thread.
    private val cameraProfileRegistry by lazy {
        CameraProfileRegistry(getApplication<Application>())
    }

    private val _flashMode = MutableStateFlow(0)
    val flashMode: StateFlow<Int> = _flashMode.asStateFlow()

    private val _isFrontCamera = MutableStateFlow(false)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    private val _capturedPhotos = MutableStateFlow<List<File>>(emptyList())
    val capturedPhotos: StateFlow<List<File>> = _capturedPhotos.asStateFlow()

    private val _selectedPhoto = MutableStateFlow<File?>(null)
    val selectedPhoto: StateFlow<File?> = _selectedPhoto.asStateFlow()

    private val _showTemperatureSlider = MutableStateFlow(false)
    val showTemperatureSlider: StateFlow<Boolean> = _showTemperatureSlider.asStateFlow()

    private val _showExposureSlider = MutableStateFlow(false)
    val showExposureSlider: StateFlow<Boolean> = _showExposureSlider.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    // ── Shutter-press state (decoupled from post-processing) ─────────────
    // `_isCapturing` flips true at the start of `processAndSavePhoto`, which
    // means it stays high for the entire bitmap-decode → EXIF → crop → LUT →
    // JPEG-encode → save pipeline (~0.5–3 s on a Pixel). Driving the shutter
    // button's visual scale off `_isCapturing` makes it look stuck-down for
    // the whole pipeline ("press feels heavy"), which is exactly what the
    // user reported. `_captureInFlight` is the narrower signal: it goes true
    // when the capture is fired and false the moment the camera hardware
    // hands back the image (BEFORE `processAndSavePhoto` even starts its
    // background work). The shutter scale + click-debounce both read this,
    // so the button visually snaps back the instant the picture is taken
    // and the user can fire the next shot immediately. `_isCapturing` stays
    // as an internal "post-processing busy" signal used by anything that
    // needs to gate on the entire pipeline (e.g. concurrent probes).
    private val _captureInFlight = MutableStateFlow(false)
    val captureInFlight: StateFlow<Boolean> = _captureInFlight.asStateFlow()

    /**
     * Mark the start of a capture. Call this from the UI the moment the
     * user clicks the shutter (just before the camera hardware is asked
     * to expose) so the button visual flips to its pressed state without
     * waiting on a recomposition round-trip through the view-model.
     *
     * Self-timer mode is the one exception — the click here starts a
     * countdown, not a capture, so the caller is expected to invoke
     * `beginCapture()` immediately before the real capture fires, not
     * at click time (keeps the shutter from being un-clickable during
     * the visible countdown).
     */
    fun beginCapture() { _captureInFlight.value = true }

    /**
     * Mark the end of a capture. Called inside the post-capture
     * callbacks (onImageSaved for the JPEG path, `onCaptured` / `onError`
     * for the Camera2 RAW path). Resetting synchronously inside the
     * callback — not deferred to the post-processing pipeline — is
     * what guarantees the shutter visually releases before the bitmap
     * decode / LUT apply / JPEG encode / MediaStore save runs.
     */
    fun endCapture() { _captureInFlight.value = false }

    private val _lensSwitchTrigger = MutableStateFlow(0)
    val lensSwitchTrigger: StateFlow<Int> = _lensSwitchTrigger.asStateFlow()

    private val _showGridLines = MutableStateFlow(false)
    val showGridLines: StateFlow<Boolean> = _showGridLines.asStateFlow()

    // Whether the in-app gallery wraps each photo in the white film-card
    // frame (phone name + EXIF strip). Defaults to off; the user opts in
    // from the Settings page.
    private val _showGalleryFrame = MutableStateFlow(false)
    val showGalleryFrame: StateFlow<Boolean> = _showGalleryFrame.asStateFlow()

    private val _aspectRatio = MutableStateFlow(AspectRatio.RATIO_4_3)
    val aspectRatio: StateFlow<AspectRatio> = _aspectRatio.asStateFlow()

    // 0 = Off, 3 = 3 s, 10 = 10 s
    private val _selfTimerMode = MutableStateFlow(0)
    val selfTimerMode: StateFlow<Int> = _selfTimerMode.asStateFlow()

    private val _doubleExposureActive = MutableStateFlow(false)
    val doubleExposureActive: StateFlow<Boolean> = _doubleExposureActive.asStateFlow()

    // ── RAW capture mode ──────────────────────────────────────────────────
    // When true, the shutter routes through RawCapture.captureDng() instead of
    // the JPEG ImageCapture path. Capability-checked per lens via the catalog:
    // RAW is only offered when the currently-selected lens advertises RAW_SENSOR.

    private val _rawModeEnabled = MutableStateFlow(false)
    val rawModeEnabled: StateFlow<Boolean> = _rawModeEnabled.asStateFlow()

    // True when the currently selected lens can actually emit RAW frames.
    private val _rawAvailableForCurrentLens = MutableStateFlow(false)
    val rawAvailableForCurrentLens: StateFlow<Boolean> = _rawAvailableForCurrentLens.asStateFlow()

    // ── OEM extension mode (HDR / Night / Bokeh / Auto) ───────────────────
    // NONE keeps the manual physical-lens routing. Any other value lets the
    // OEM extension own sensor selection. Availability is device-specific and
    // cached after the first probe.

    private val _activeExtension = MutableStateFlow(CaptureExtension.NONE)
    val activeExtension: StateFlow<CaptureExtension> = _activeExtension.asStateFlow()

    private val _availableExtensions = MutableStateFlow<Set<CaptureExtension>>(setOf(CaptureExtension.NONE))
    val availableExtensions: StateFlow<Set<CaptureExtension>> = _availableExtensions.asStateFlow()

    private val _extensionsProbeDone = MutableStateFlow(false)
    val extensionsProbeDone: StateFlow<Boolean> = _extensionsProbeDone.asStateFlow()

    private var shutterSound: MediaActionSound? = null

    // Held so onCleared() can unregister the MediaStore observer we install in
    // init. Nullable so a registration failure in init leaves the slot null and
    // onCleared() can no-op cleanly without an NPE.
    private var mediaStoreObserver: ContentObserver? = null

    // Held while a debounced MediaStore-driven refresh is in flight so a burst
    // of notifications (e.g. Google Photos doing a bulk insert, the system
    // MediaScanner noticing an unrelated Pictures/ change) collapses into one
    // directory rescan instead of N concurrent ones. Reset by the debounced
    // coroutine when it completes.
    private var pendingGalleryRefresh: Job? = null

    init {
        viewModelScope.launch {
            try {
                prefsRepo.settingsFlow.first().let { saved ->
                    _rawModeEnabled.value = saved.rawModeEnabled
                    _aspectRatio.value = saved.aspectRatio
                    _activePreset.value = saved.activePreset
                    _flashMode.value = saved.flashMode
                    _showGridLines.value = saved.showGridLines
                    _showGalleryFrame.value = saved.showGalleryFrame
                    _selfTimerMode.value = saved.selfTimerMode
                    _doubleExposureActive.value = saved.doubleExposureActive
                    _isFrontCamera.value = saved.isFrontCamera
                    _activeExtension.value = saved.activeExtension
                    _selectedLensRole.value = saved.selectedLensRole
                    _outputResolution.value = saved.outputResolution
                    _filmStyleScrollIndex.value = saved.filmStyleScrollIndex
                    _filmStyleScrollOffset.value = saved.filmStyleScrollOffset
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep the safe defaults if the preference store is corrupt or
                // temporarily unavailable. The camera should still start rather
                // than fail its ViewModel coroutine silently.
                Log.e("CameraViewModel", "Failed to load saved camera settings", e)
            } finally {
                // Do this even after a preference-store failure: the safe
                // NORMAL defaults still allow the camera to start, and the
                // preview is never created mid-route switch.
                _settingsLoaded.value = true
            }
        }

        // Preload every FilmPreset's LUT off the main thread so the first
        // capture of any film preset doesn't pay the synchronous CubeLutParser
        // cost inside the capture coroutine. Each `.cube` asset parse is
        // ~50–150 ms of asset I/O + per-element normalisation; without this
        // warm-up the user-visible penalty lands on whichever preset they
        // capture first after opening the app. The coroutine runs in parallel
        // with the settings load above — both share viewModelScope and are
        // cancellable if the ViewModel is cleared before they finish.
        // loadLut already wraps CubeLutParser.parse in its own try/catch and
        // returns null on a per-preset failure, so a single bad .cube asset
        // can't poison the whole warm-up without us adding any extra guards.
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            for (preset in FilmPreset.entries) loadLut(app, preset)
        }

        // ── MediaStore sync ─────────────────────────────────────────────────
        // Register a ContentObserver on the shared image collection so that
        // deletions or insertions made in OTHER gallery apps (Google Photos,
        // the system Files app, OEM gallery, etc.) propagate into our in-app
        // gallery *immediately* instead of waiting for the next launch or the
        // next in-app capture. Without this observer the in-app filmstrip
        // silently diverges from the device's Pictures/ZoomBoxCamera folder
        // — a delete in Google Photos leaves a stale thumbnail in this app
        // until relaunch.
        //
        // notifyForDescendants = true so notifications for the public tree
        // (including subfolders like Pictures/ZoomBoxCamera/RAW/) bubble up.
        // We pass a main-Looper Handler so onChange runs on the main thread
        // and we dispatch the IO re-scan via viewModelScope — `getApplication`
        // is safe to call from init because AndroidViewModel caches the
        // application reference at construction time.
        try {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    // Cancel any refresh that's still waiting out its debounce
                    // window so a burst of notifications (Google Photos bulk
                    // insert, system MediaScanner noticing other Pictures/…
                    // changes) collapses into a single rescan instead of N
                    // concurrent reads racing on the same disk.
                    pendingGalleryRefresh?.cancel()
                    pendingGalleryRefresh = viewModelScope.launch(Dispatchers.IO) {
                        kotlinx.coroutines.delay(150.milliseconds)
                        _capturedPhotos.value = listPhotoFiles(getApplication())
                    }
                }
            }
            getApplication<Application>().contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                /* notifyForDescendants = */ true,
                observer
            )
            mediaStoreObserver = observer
        } catch (e: Exception) {
            // Some test harnesses / vendor content providers can refuse observer
            // registration — log and continue. The gallery still works, it just
            // falls back to the existing capture/delete/launch refresh path.
            Log.e("CameraViewModel", "Failed to register MediaStore observer", e)
        }

        // ── Physical rotation tracking ────────────────────────────────────
        // Display.getRotation() stays ROTATION_0 while the activity is locked
        // to portrait, so the sensor-based OrientationEventListener is the
        // only reliable source of "which way is down" for the capture
        // pipeline. The degree → Surface-rotation mapping below matches what
        // ImageCapture.setTargetRotation documents: 0° → ROTATION_0,
        // 45–135° → ROTATION_270, 135–225° → ROTATION_180, 225–315° →
        // ROTATION_90.
        try {
            orientationListener = object : OrientationEventListener(getApplication()) {
                override fun onOrientationChanged(orientation: Int) {
                    if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return
                    val rotation = when (orientation) {
                        in 45 until 135 -> Surface.ROTATION_270
                        in 135 until 225 -> Surface.ROTATION_180
                        in 225 until 315 -> Surface.ROTATION_90
                        else -> Surface.ROTATION_0
                    }
                    if (rotation != _physicalRotation.value) _physicalRotation.value = rotation
                }
            }.apply { enable() }
        } catch (e: Exception) {
            // A failed sensor registration shouldn't kill the camera; captures
            // simply fall back to ROTATION_0 (portrait).
            Log.e("CameraViewModel", "Failed to start orientation listener", e)
        }
    }

    fun loadPhotos(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            _capturedPhotos.value = listPhotoFiles(context)
        }
    }

    /**
     * Synchronous gallery scan shared by [loadPhotos] (async wrapper) and
     * [deletePhoto] (which needs the post-delete list *now* to auto-advance
     * the photo viewer's selection). Sorted newest-first to match the
     * gallery / filmstrip where index 0 is the most recent capture.
     */
    private fun listPhotoFiles(context: Context): List<File> {
        // Two locations hold our captures:
        //   1. App-private: getExternalFilesDir(DIRECTORY_PICTURES) — working
        //      copies written straight from the capture pipeline. File.listFiles
        //      works fine here because we own the directory and it lives
        //      inside our scope (`/sdcard/Android/data/<package>/files/...`).
        //   2. Public-shared MediaStore mirror: Pictures/ZoomBoxCamera/ (plus
        //      any subfolders, including the RAW/ tree). After an app reinstall
        //      the private copy is wiped but the MediaStore entries survive —
        //      and only MediaStore can see them on Android 10+ scoped storage
        //      without READ_MEDIA_IMAGES.
        val privateDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)

        // Local helper kept inside this method so its scope is obviously
        // tied to the gallery scan; lift it back to the class only if some
        // other method needs the same predicate.
        fun isOurPhoto(file: File): Boolean =
            file.isFile && file.extension.lowercase() in listOf("jpg", "jpeg", "dng")

        val publicFiles = listPublicPhotosViaMediaStore(context)
        // Drop orphan app-private cache mirrors whose MediaStore row has gone
        // away (external delete via file manager, sideload via ADB, etc.).
        // Without this pass, distinctBy-{name} below would resurrect those
        // names from the private mirror after the corresponding public file
        // disappears — the gallery would keep showing photos the user just
        // removed from /sdcard/Pictures/ZoomBoxCamera/. Skip during an
        // in-flight capture: the working copy is on disk before its
        // MediaStore row exists, and we'd otherwise race-delete the photo
        // the user is currently taking (see [_isCapturing]).
        if (!_isCapturing.value) {
            cleanupOrphanPrivateFiles(context, publicFiles.map { it.name }.toSet())
        }
        val privateFiles = privateDir?.listFiles(::isOurPhoto)?.toList() ?: emptyList()

        // Public first, then private — distinctBy { it.name } keeps the
        // public entry when both copies exist, so the file we hand to
        // deletePhoto() is the canonical (reinstall-survived) path.
        return (publicFiles + privateFiles)
            .distinctBy { it.name }
            .sortedByDescending { it.lastModified() }
    }

    /**
     * Delete app-private cache mirrors whose MediaStore row is no longer
     * present, so external deletes (file manager, MTP, Google Photos) are
     * reflected in the in-app gallery even though the app-private copy is
     * technically still on disk.
     *
     * The capture pipeline writes each photo to BOTH places
     * (`getExternalFilesDir(DIRECTORY_PICTURES)/...` for the working copy and
     * `Pictures/ZoomBoxCamera/...` via `MediaStore.insert` for the public
     * mirror). If the user removes the public side, the working copy
     * lingers and `listPhotoFiles`'s distinctBy-{name} pass falls back to
     * it, re-surfacing the supposedly-deleted photo. This pass makes the
     * external delete two-sided by also trashing the cache mirror.
     *
     * Done as a single bulk MediaStore query rather than one round-trip per
     * private file: even on a heavily-used device a few hundred files would
     * mean hundreds of binder calls, vs. one. We also scope the bulk probe to
     * our own folder so a name collision with an unrelated MediaStore row
     * (some other app's `IMG_20240301_120000.jpg` for instance) can't fool
     * us into keeping a true orphan.
     *
     * Mid-capture safety: callers should skip this pass while
     * [_isCapturing].value is true; between the working-copy rename and the
     * MediaStore.insert call a file legitimately has no row yet, and we'd
     * race-delete it.
     */
    private fun cleanupOrphanPrivateFiles(context: Context, publicFileNames: Set<String>) {
        val privateDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return
        val candidateFiles = privateDir.listFiles()?.filter { f ->
            f.isFile && f.extension.lowercase() in listOf("jpg", "jpeg", "dng")
        } ?: return

        // Candidates that aren't already covered by the public set need a
        // MediaStore probe. Same slash-anchored scope as listPublicPhotos…
        // so we don't accidentally accept a name that already exists
        // somewhere else on the device.
        val orphanCandidates = candidateFiles.filter { it.name !in publicFileNames }
        if (orphanCandidates.isEmpty()) return

        val resolver = context.contentResolver
        val imageUri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val folderBase = Environment.DIRECTORY_PICTURES.lowercase()
        // Slash-anchored: = (bare or trailing-slash root) + LIKE "ZoomBoxCamera/%"
        // Mirrors listPublicPhotosViaMediaStore so probe-cached rows match the
        // same population the public query uses.
        val selectionArgs = mutableListOf<String>()
        val selection = buildString {
            append(MediaStore.Images.Media.DISPLAY_NAME).append(" IN (")
            repeat(orphanCandidates.size) { idx ->
                if (idx > 0) append(", ")
                append("?")
                selectionArgs += orphanCandidates[idx].name
            }
            append(") AND (")
            // 'Pictures/zoomboxcamera'
            append("LOWER(").append(MediaStore.Images.Media.RELATIVE_PATH).append(") = ?")
            selectionArgs += "$folderBase/zoomboxcamera"
            append(" OR ")
            // 'Pictures/zoomboxcamera/'
            append("LOWER(").append(MediaStore.Images.Media.RELATIVE_PATH).append(") = ?")
            selectionArgs += "$folderBase/zoomboxcamera/"
            append(" OR ")
            // 'Pictures/zoomboxcamera/...'
            append("LOWER(").append(MediaStore.Images.Media.RELATIVE_PATH).append(") LIKE ?")
            selectionArgs += "$folderBase/zoomboxcamera/%"
            append(")")
        }

        // Probe once: collect all display names that have a row under our
        // folder. Anything not in this set is an orphan.
        val protectedNames: Set<String> = runCatching {
            resolver.query(
                imageUri,
                arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
                selection,
                selectionArgs.toTypedArray(),
                null
            )?.use { cursor ->
                val idx = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                buildSet {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) add(name)
                    }
                }
            }
        }.getOrNull() ?: emptySet()

        var deletedCount = 0
        for (f in orphanCandidates) {
            if (f.name in protectedNames) continue
            val deleted = runCatching { f.delete() }.getOrDefault(false)
            if (deleted) deletedCount++
        }
        if (deletedCount > 0) {
            Log.i("CameraViewModel", "Cleaned up $deletedCount orphan private file(s)")
        }
    }

    /**
     * Query MediaStore for every image in (and under) `Pictures/ZoomBoxCamera/`
     * and project the result back into `File` objects so the rest of the
     * capture/delete pipeline keeps working with `File` references unchanged.
     *
     * Why MediaStore instead of `File.listFiles()` on the public tree:
     *   - On Android 10+ scoped storage, `File.listFiles()` returns null/empty
     *     on `/sdcard/Pictures/...` unless the app has MANAGE_EXTERNAL_STORAGE
     *     or the corresponding READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE
     *     permission. Our app previously assumed the public tree was readable
     *     and would silently show an empty gallery whenever the user reinstalled
     *     (UID changes → OS no longer treats us as the owner of pre-reinstall
     *     rows, and File API falls back to "no access").
     *   - MediaStore queries work for our own rows even without the runtime
     *     permission (we implicitly own them) and naturally return both our
     *     own files and any other-app file in the same folder once the
     *     permission is granted.
     *
     * RELATIVE_PATH matching uses slash-anchored equality + LIKE so we cover
     * every vendor normalizer without over-matching sibling folders:
     *   - "Pictures/ZoomBoxCamera"        (= exact match for non-slash form)
     *   - "Pictures/ZoomBoxCamera/"       (= exact match for AOSP normalised form)
     *   - "Pictures/ZoomBoxCamera/RAW/"   (LIKE prefix for subfolders)
     *   - any future subfolder the user might create (LIKE prefix)
     * Importantly we reject similarly-named sibling folders like
     * "Pictures/ZoomBoxCameraBackup/" or "Pictures/ZoomBoxCamera2/" because
     * the LIKE pattern is anchored with the trailing slash instead of a bare
     * `%` — otherwise those folders would also appear in the gallery.
     * IS_PENDING = 0 filters out half-written rows that the capture pipeline
     * hasn't finished copying yet — without this the gallery can briefly show
     * a thumbnail pointing at a file that's still being flushed to disk.
     */
    private fun listPublicPhotosViaMediaStore(context: Context): List<File> {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            @Suppress("DEPRECATION") MediaStore.Images.Media.DATA
        )
        // IS_PENDING = 0 keeps in-flight writes out of the gallery.
        // MIME_TYPE filter narrows to JPEG/DNG so we don't accidentally pick up
        // any other image format a vendor MediaProvider stuffs under our folder.
        // RELATIVE_PATH matching is slash-anchored: '=' fixes the bare root
        // (some vendors keep "Pictures/ZoomBoxCamera" with no trailing slash;
        // AOSP inserts "Pictures/ZoomBoxCamera/"); the two LIKE patterns only
        // match true descendants ("ZoomBoxCamera/RAW/…", or any future
        // subfolder like "ZoomBoxCamera/2024/…"). Without the slash anchor a
        // sibling folder such as "Pictures/ZoomBoxCameraBackup/" or
        // "Pictures/ZoomBoxCamera2/" would also satisfy the prefix match and
        // we'd leak foreign images into the gallery.
        val selection = "${MediaStore.Images.Media.IS_PENDING} = 0 " +
            "AND (${MediaStore.Images.Media.MIME_TYPE} = ? " +
                 "OR ${MediaStore.Images.Media.MIME_TYPE} = ?) " +
            "AND (LOWER(${MediaStore.Images.Media.RELATIVE_PATH}) = ? " +
                 "OR LOWER(${MediaStore.Images.Media.RELATIVE_PATH}) LIKE ? " +
                 "OR LOWER(${MediaStore.Images.Media.RELATIVE_PATH}) LIKE ?)"
        val args = arrayOf(
            "image/jpeg", "image/x-adobe-dng",
            "${Environment.DIRECTORY_PICTURES}/zoomboxcamera/".lowercase(),
            "${Environment.DIRECTORY_PICTURES}/zoomboxcamera".lowercase(),
            "${Environment.DIRECTORY_PICTURES}/zoomboxcamera/%".lowercase()
        )
        // DATE_ADDED DESC matches the newest-first gallery ordering
        // (lastModified on File can drift if a user copies files in via MTP).
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        return runCatching {
            resolver.query(collection, projection, selection, args, sortOrder)?.use { cursor ->
                @Suppress("DEPRECATION")
                val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                val result = mutableListOf<File>()
                while (cursor.moveToNext()) {
                    @Suppress("DEPRECATION")
                    val dataPath = cursor.getString(dataIdx)
                    if (dataPath.isNullOrBlank()) continue
                    val f = File(dataPath)
                    // Skip rows whose on-disk file is gone — MediaStore rows
                    // can outlive the actual file during a half-completed delete;
                    // listing them in the gallery would dereference to nothing.
                    if (f.exists()) result.add(f)
                }
                result
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun getCurrentLensProfile(): com.example.zoom.LensProfile? {
        val catalog = lensCatalogResult ?: return null
        return when (_selectedLensRole.value) {
            com.example.zoom.LensRole.ULTRA_WIDE -> catalog.ultraWide
            com.example.zoom.LensRole.PRIMARY -> catalog.primary
            com.example.zoom.LensRole.TELE -> catalog.tele
        }
    }

    fun setAvailableFocalLengths(lengths: List<Float>) {
        if (lengths.isNotEmpty() && lengths != _availableFocalLengths.value) {
            _availableFocalLengths.value = lengths
            recalculateState()
        }
    }

    fun setLensCatalogResult(result: LensCatalog.CatalogResult) {
        lensCatalogResult = result
        Log.i("LensSwitch", "Catalog loaded | UW=${result.ultraWide?.equivFocalMm}mm(${result.ultraWide?.physicalCameraId}) " +
            "PRI=${result.primary?.equivFocalMm}mm(${result.primary?.physicalCameraId}) " +
            "TELE=${result.tele?.equivFocalMm}mm(${result.tele?.physicalCameraId})")
        // The default _selectedLensRole is PRIMARY (24mm-ish). On devices
        // without a primary-class lens the initial preview stays black
        // because the binding fails silently. Auto-correct the initial
        // selection to the first available back-facing lens so the
        // viewfinder lights up at app start even without a 24mm hardware
        // camera. Mirrors the user's bug: "Ich habe keine Kamera '24' —
        // die App switcht beim Start zu dieser Kamera".
        ensureSelectedLensAvailable()
        recalculateState()
        refreshRawAvailabilityForCurrentLens()
    }

    /**
     * Auto-correction for the initial lens selection: if the currently
     * selected role isn't backed by a physical lens on this device, step
     * down the priority ladder PRIMARY → ULTRA_WIDE → TELE and pick the
     * first role the catalog actually has. If the device has no back-facing
     * lenses at all, leave the selection unchanged and let the preview's
     * DEFAULT_BACK_CAMERA fallback path handle the binding.
     */
    private fun ensureSelectedLensAvailable() {
        val catalog = lensCatalogResult ?: return
        val current = _selectedLensRole.value
        val currentAvailable = when (current) {
            LensRole.ULTRA_WIDE -> catalog.ultraWide != null
            LensRole.PRIMARY -> catalog.primary != null
            LensRole.TELE -> catalog.tele != null
        }
        if (currentAvailable) return

        val fallback = when {
            catalog.primary != null -> LensRole.PRIMARY
            catalog.ultraWide != null -> LensRole.ULTRA_WIDE
            catalog.tele != null -> LensRole.TELE
            else -> return  // no back cameras; leave selection for the
                             // preview's DEFAULT_BACK_CAMERA path
        }
        Log.i("LensSwitch", "Auto-correct: ${current.name} not available → fallback to ${fallback.name}")
        _selectedLensRole.value = fallback
        viewModelScope.launch { prefsRepo.saveSelectedLensRole(fallback) }
        // Mirror cycleLens(): bump the switch trigger so the CameraPreviewView
        // re-keys and the binding re-fires against the corrected role.
        _lensSwitchTrigger.value = _lensSwitchTrigger.value + 1
        _digitalZoomRatio.value = 1.0f
    }

    fun setZoom(ratio: Float) {
        if (_selectedLensRole.value != LensRole.PRIMARY) return
        val clampedRatio = ratio.coerceIn(
            ZoomBoxCalculator.MIN_ZOOM_RATIO,
            ZoomBoxCalculator.MAX_ZOOM_RATIO
        )
        _digitalZoomRatio.value = clampedRatio
        recalculateState()
    }

    /**
     * Cycle through physical lenses regardless of catalog availability.
     * The preview binding in CameraPreviewView will fallback gracefully if a lens isn't found.
     */
    fun cycleLens() {
        // Front camera has only one lens — there is nothing to cycle to.
        // Bump out before the state machine runs so the BubbleRow in
        // CameraUi doesn't flicker through 13/24/116 mm while the user is
        // on selfie mode. The UI also gates the click, but guarding here
        // is defense-in-depth in case a future screen subscribes
        // directly to _selectedLensRole.
        if (_isFrontCamera.value) return
        val catalog = lensCatalogResult
        val availableRoles = mutableListOf<LensRole>()
        if (catalog?.primary != null) availableRoles.add(LensRole.PRIMARY)
        if (catalog?.ultraWide != null) availableRoles.add(LensRole.ULTRA_WIDE)
        if (catalog?.tele != null) availableRoles.add(LensRole.TELE)
        if (availableRoles.isEmpty()) return
        val currentIndex = availableRoles.indexOf(_selectedLensRole.value)
        val nextRole = availableRoles[(currentIndex + 1) % availableRoles.size]
        Log.i("LensSwitch", "User switch: ${_selectedLensRole.value.name} → ${nextRole.name}")
        _selectedLensRole.value = nextRole
        viewModelScope.launch { prefsRepo.saveSelectedLensRole(nextRole) }
        _lensSwitchTrigger.value = _lensSwitchTrigger.value + 1
        _digitalZoomRatio.value = 1.0f
        recalculateState()
        refreshRawAvailabilityForCurrentLens()
        // Switching lens invalidates the per-lens extension availability; reset
        // the probe so the UI re-queries against the new logical camera.
        _extensionsProbeDone.value = false
        _availableExtensions.value = setOf(CaptureExtension.NONE)
        _activeExtension.value = CaptureExtension.NONE
        viewModelScope.launch { prefsRepo.saveActiveExtension(CaptureExtension.NONE) }
    }

    private fun recalculateState() {
        // Front camera is single-lens; back-camera focal / box-scale / role
        // state is meaningless there. Short-circuit prevents an earlier
        // rear-camera zoom from leaking into the front preview as the
        // zoom-box overlay + "24mm" label at line ~1421 of CameraUi.kt.
        if (_isFrontCamera.value) return
        val catalog = lensCatalogResult
        val primaryFocalMm = catalog?.primary?.equivFocalMm ?: 24f
        val ultraWideFocalMm = catalog?.ultraWide?.equivFocalMm ?: 13.4f
        val teleFocalMm = catalog?.tele?.equivFocalMm ?: 116.2f

        val selectedRole = _selectedLensRole.value

        _previewLensRole.value = selectedRole
        _captureLensRole.value = selectedRole

        when (selectedRole) {
            LensRole.PRIMARY -> {
                val nativeFocal = primaryFocalMm
                val digitalZoom = _digitalZoomRatio.value
                val effectiveFocal = (nativeFocal * digitalZoom).toInt()
                _effectiveFocalLength.value = effectiveFocal
                val scale = FovMapper.boxScale(nativeFocal, effectiveFocal.toFloat())
                _boxScale.value = scale
            }
            LensRole.ULTRA_WIDE -> {
                val nativeFocal = ultraWideFocalMm.toInt()
                _effectiveFocalLength.value = nativeFocal
                _boxScale.value = 1f
            }
            LensRole.TELE -> {
                val nativeFocal = teleFocalMm.toInt()
                _effectiveFocalLength.value = nativeFocal
                _boxScale.value = 1f
            }
        }
    }

    fun setExposure(value: Float) { _exposure.value = value.coerceIn(-3.0f, 3.0f) }
    fun setTemperature(value: Float) { _temperature.value = value.coerceIn(-2.0f, 2.0f) }
    fun setTint(value: Float) { _tint.value = value.coerceIn(-2.0f, 2.0f) }
    fun setCameraPreset(preset: FilmPreset) {
        _activePreset.value = preset
        viewModelScope.launch { prefsRepo.saveActivePreset(preset) }
        setTemperature(preset.defaultTemp)
        setTint(preset.defaultTint)
        setExposure(preset.defaultExposure)
    }

    /**
     * Step the active film preset by [direction] slots in enum order,
     * wrapping at the ends. Used by the viewfinder horizontal-swipe
     * gesture so consecutive swipes ("next, next, next") walk through
     * the full preset gallery then loop back to the start.
     *
     * @param direction +1 advances to the next preset (swipe left);
     *                 -1 advances to the previous preset (swipe right).
     * Delegates to [setCameraPreset] so the new preset's default
     * exposure / temperature / tint are applied exactly like a tap in
     * the bottom-sheet picker, and the choice is persisted.
     */
    fun cycleCameraPreset(direction: Int) {
        val ordered = FilmPreset.entries
        if (ordered.size <= 1) return
        val currentIndex = ordered.indexOf(_activePreset.value).let { if (it < 0) 0 else it }
        val step = if (direction >= 0) 1 else -1
        val nextIndex = ((currentIndex + step) % ordered.size + ordered.size) % ordered.size
        val nextPreset = ordered[nextIndex]
        if (nextPreset == _activePreset.value) return
        setCameraPreset(nextPreset)
    }

    /**
     * Returns the parsed LUT for [preset], loading and caching it on first use.
     * Returns null if the asset cannot be read (the pipeline then skips the
     * LUT step and falls back to the manual color filters only).
     */
    fun loadLut(context: Context, preset: FilmPreset): CubeLut? {
        // Pass-through / no-grade preset (e.g. NORMAL): skip the parser
        // and the asset I/O entirely. Returning null here is what tells
        // both the live GL preview (`LutPreviewView.setLut(null)`) and
        // the post-capture `applyRetroFilter` (the `currentLut != null`
        // OR-chain guard) to skip the LUT step.
        if (preset.assetPath.isBlank()) return null
        cachedLuts[preset.assetPath]?.let { return it }
        return try {
            val lut = CubeLutParser.parse(preset.assetPath, context)
            cachedLuts[preset.assetPath] = lut
            lut
        } catch (e: Exception) {
            Log.e("CameraViewModel", "Failed to load LUT ${preset.assetPath}", e)
            null
        }
    }
    fun toggleFlash() {
        _flashMode.value = (_flashMode.value + 1) % 3
        viewModelScope.launch { prefsRepo.saveFlashMode(_flashMode.value) }
    }
    fun toggleCamera() {
        val nowFront = !_isFrontCamera.value
        _isFrontCamera.value = nowFront
        viewModelScope.launch { prefsRepo.saveIsFrontCamera(nowFront) }
        if (nowFront) {
            // The front camera is single-lens. Reset zoom-related state so
            // a back-camera zoom (boxScale < 0.99) doesn't carry into the
            // selfie preview as a stale zoom-box overlay. _selectedLensRole
            // is left alone — the user typically flips back-and-forth and
            // we want them to land where they were last on the rear side.
            _digitalZoomRatio.value = 1.0f
            _boxScale.value = 1f
        }
        // Always re-run recalculateState: short-circuits on front (so the
        // reset values above stick), recomputes on rear transition.
        recalculateState()
    }
    fun toggleGridLines() {
        _showGridLines.value = !_showGridLines.value
        viewModelScope.launch { prefsRepo.saveShowGridLines(_showGridLines.value) }
    }
    fun toggleGalleryFrame() {
        _showGalleryFrame.value = !_showGalleryFrame.value
        viewModelScope.launch { prefsRepo.saveGalleryFrame(_showGalleryFrame.value) }
    }
    fun cycleSelfTimer() {
        _selfTimerMode.value = when (_selfTimerMode.value) { 0 -> 3; 3 -> 10; else -> 0 }
        viewModelScope.launch { prefsRepo.saveSelfTimerMode(_selfTimerMode.value) }
    }
    fun toggleDoubleExposure() {
        _doubleExposureActive.value = !_doubleExposureActive.value
        viewModelScope.launch { prefsRepo.saveDoubleExposure(_doubleExposureActive.value) }
    }
    fun setAspectRatio(ratio: AspectRatio) {
        _aspectRatio.value = ratio
        viewModelScope.launch { prefsRepo.saveAspectRatio(ratio) }
    }

    /**
     * Switch between 3 MP (fast) and full sensor resolution (archival) JPEG
     * output. The new value is committed to DataStore immediately so it
     * survives a relaunch.
     */
    fun setOutputResolution(resolution: OutputResolution) {
        _outputResolution.value = resolution
        viewModelScope.launch { prefsRepo.saveOutputResolution(resolution) }
    }
    fun setSelectedPhoto(file: File?) { _selectedPhoto.value = file }

    /**
     * Called from a `snapshotFlow` collector in CameraUi every time the
     * user's scroll inside the Film-Style picker LazyRow changes. Writes
     * both to the in-memory cache (so a re-open before DataStore has
     * finished its async write still sees the latest position) and to
     * disk. Idempotent — no-op when the values haven't moved so we don't
     * spam DataStore with redundant edits on touchpad inertia scroll.
     */
    fun saveFilmStyleScrollPosition(index: Int, offset: Int) {
        val safeIndex = index.coerceAtLeast(0)
        val safeOffset = offset.coerceAtLeast(0)
        if (_filmStyleScrollIndex.value == safeIndex &&
            _filmStyleScrollOffset.value == safeOffset
        ) return
        _filmStyleScrollIndex.value = safeIndex
        _filmStyleScrollOffset.value = safeOffset
        viewModelScope.launch { prefsRepo.saveFilmStyleScrollPosition(safeIndex, safeOffset) }
    }

    fun toggleTemperatureSlider() {
        _showTemperatureSlider.value = !_showTemperatureSlider.value
        if (_showTemperatureSlider.value) _showExposureSlider.value = false
    }

    fun toggleExposureSlider() {
        _showExposureSlider.value = !_showExposureSlider.value
        if (_showExposureSlider.value) _showTemperatureSlider.value = false
    }

    fun closeSliders() {
        _showTemperatureSlider.value = false
        _showExposureSlider.value = false
    }

    // ── RAW / Extension toggles ───────────────────────────────────────────

    /**
     * Toggle RAW capture mode. Refuses to enable RAW when the current lens
     * doesn't support it (the caller can also check [rawAvailableForCurrentLens]
     * to grey out the control).
     */
    fun toggleRawMode() {
        if (!_rawModeEnabled.value && !_rawAvailableForCurrentLens.value) return
        _rawModeEnabled.value = !_rawModeEnabled.value
        viewModelScope.launch { prefsRepo.saveRawMode(_rawModeEnabled.value) }
        // RAW bypasses OEM extensions by design (extensions produce processed
        // JPEGs); force NONE while RAW is on so the two don't conflict.
        if (_rawModeEnabled.value) {
            _activeExtension.value = CaptureExtension.NONE
            viewModelScope.launch { prefsRepo.saveActiveExtension(CaptureExtension.NONE) }
        }
    }

    fun setRawModeEnabled(enabled: Boolean) {
        if (enabled && !_rawAvailableForCurrentLens.value) return
        _rawModeEnabled.value = enabled
        viewModelScope.launch { prefsRepo.saveRawMode(enabled) }
        if (enabled) {
            _activeExtension.value = CaptureExtension.NONE
            viewModelScope.launch { prefsRepo.saveActiveExtension(CaptureExtension.NONE) }
        }
    }

    /**
     * Select an OEM extension mode. Falls back to NONE if the mode isn't in
     * [availableExtensions] (probed at runtime).
     */
    fun setExtension(ext: CaptureExtension) {
        if (ext != CaptureExtension.NONE && ext !in _availableExtensions.value) return
        _activeExtension.value = ext
        viewModelScope.launch { prefsRepo.saveActiveExtension(ext) }
        // Extensions produce processed output, so RAW is mutually exclusive.
        if (ext != CaptureExtension.NONE) {
            _rawModeEnabled.value = false
            viewModelScope.launch { prefsRepo.saveRawMode(false) }
        }
    }

    fun cycleExtension() {
        val available = CaptureExtension.userSelectable.filter { it in _availableExtensions.value }
        if (available.size <= 1) return
        val idx = available.indexOf(_activeExtension.value)
        _activeExtension.value = available[(idx + 1).mod(available.size)]
        viewModelScope.launch { prefsRepo.saveActiveExtension(_activeExtension.value) }
        if (_activeExtension.value != CaptureExtension.NONE) {
            _rawModeEnabled.value = false
            viewModelScope.launch { prefsRepo.saveRawMode(false) }
        }
    }

    /**
     * Refreshes the RAW availability flag based on the currently selected
     * lens. Called whenever the lens role changes or the catalog is refreshed.
     */
    fun refreshRawAvailabilityForCurrentLens() {
        val catalog = lensCatalogResult ?: return
        val currentLens = when (_selectedLensRole.value) {
            LensRole.ULTRA_WIDE -> catalog.ultraWide
            LensRole.PRIMARY -> catalog.primary
            LensRole.TELE -> catalog.tele
        }
        val supported = currentLens?.supportsRaw == true
        _rawAvailableForCurrentLens.value = supported
        // Auto-disable RAW if the user switches to a lens that can't do it.
        if (!supported && _rawModeEnabled.value) _rawModeEnabled.value = false
    }

    /**
     * Probes which OEM extensions the device advertises for the given camera.
     * Result is cached in [availableExtensions] and surfaced via [extensionsProbeDone]
     * so the UI can stop showing the loading affordance.
     */
    fun probeExtensions(
        context: Context,
        cameraProvider: androidx.camera.lifecycle.ProcessCameraProvider,
        logicalCameraId: String,
        isFrontCamera: Boolean,
        lifecycleOwner: androidx.lifecycle.LifecycleOwner
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val manager = PreviewSessionManager(context, lifecycleOwner)
                val available = manager.availableExtensions(cameraProvider, logicalCameraId, isFrontCamera)
                _availableExtensions.value = available
                if (_activeExtension.value !in available) _activeExtension.value = CaptureExtension.NONE
            } catch (e: Exception) {
                Log.e("CameraViewModel", "probeExtensions failed", e)
                _availableExtensions.value = setOf(CaptureExtension.NONE)
            } finally {
                _extensionsProbeDone.value = true
            }
        }
    }

    fun playShutterSound() {
        val sound = shutterSound ?: MediaActionSound().also {
            try { it.load(MediaActionSound.SHUTTER_CLICK) } catch (e: Exception) { Log.e("CameraViewModel", "Error loading shutter sound", e) }
            shutterSound = it
        }
        try { sound.play(MediaActionSound.SHUTTER_CLICK) } catch (e: Exception) { Log.e("CameraViewModel", "Error playing shutter sound", e) }
    }

    /**
     * RAW capture entry point. Routes the shutter through [RawCapture.captureDng],
     * inserts the resulting .dng into the gallery as image/x-adobe-dng, and —
     * because a DNG can't carry the retro filter — runs the companion JPEG
     * through the same [processAndSavePhoto] post-processing pipeline as a
     * normal shot so the gallery shows a correctly-oriented, filtered photo.
     */
    fun captureAndSaveRaw(
        context: Context,
        logicalCameraId: String,
        physicalCameraId: String,
        focalLengthMm: Int,
        boxWidthFraction: Float,
        screenWidth: Float,
        screenHeight: Float,
        captureLensNativeFocalMm: Float?
    ) {
        _isCapturing.value = true
        // Mirror the timer onto the shutter-press state so the button visuals
        // (and `isRawCapturing` in CameraPreviewView) see "actively capturing"
        // until the Camera2 hardware callback fires. `_isCapturing` covers the
        // full pipeline including MediaStore save; `_captureInFlight` releases
        // earlier so the button doesn't feel stuck after the DNG lands.
        _captureInFlight.value = true
        RawCapture.captureDng(
            context = context,
            logicalCameraId = logicalCameraId,
            physicalCameraId = physicalCameraId,
            focalLengthMm = focalLengthMm,
            flashMode = _flashMode.value,
            // Sensor-tracked physical rotation (Display.getRotation() is
            // pinned to ROTATION_0 under the portrait lock).
            targetRotation = _physicalRotation.value,
            onCaptured = { dngFile, jpegFile ->
                // Release the shutter visual the instant the DNG lands on
                // disk. The DNG goes to the RAW/ gallery subfolder; the JPEG
                // companion then runs through the standard filter pipeline so
                // RAW capture produces the same retro/LUT look, zoom crop and
                // orientation as a normal shot.
                // No toast here: the JPEG path is silent on success, and the
                // RAW popup was inconsistent with the rest of the app.
                _captureInFlight.value = false
                saveDngToGallery(context, dngFile)
                if (jpegFile != null) {
                    // processAndSavePhoto manages `_isCapturing` and the
                    // gallery refresh itself (mirrors the JPEG path).
                    processAndSavePhoto(
                        context = context,
                        rawFile = jpegFile,
                        boxWidthFraction = boxWidthFraction,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        captureFocalLength = focalLengthMm,
                        captureLensNativeFocalMm = captureLensNativeFocalMm
                    )
                } else {
                    loadPhotos(context)
                    _isCapturing.value = false
                }
            },
            onError = { e ->
                // Mirror: release the press on first line so a failure
                // doesn't leave the shutter disabled. Log-only, matching the
                // JPEG path which reports capture errors to logcat rather
                // than surfacing a popup.
                Log.e("CameraViewModel", "RAW capture failed", e)
                _captureInFlight.value = false
                _isCapturing.value = false
            }
        )
    }

    /**
     * Inserts a .dng into MediaStore under Pictures/ZoomBoxCamera/RAW. RAW files
     * are kept separate from JPEGs both by extension and by subfolder so the
     * retro-roll filmstrip (which decodes JPEGs) isn't polluted.
     */
    private fun saveDngToGallery(context: Context, file: File) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                put(MediaStore.Images.Media.IS_PENDING, 1)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ZoomBoxCamera/RAW")
            }
            val resolver = context.contentResolver
            val contentUri =
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(contentUri, values) ?: return
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { `in` -> `in`.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            Log.e("CameraViewModel", "Error saving DNG to gallery", e)
        }
    }

    fun processAndSavePhoto(
        context: Context,
        rawFile: File,
        boxWidthFraction: Float,
        screenWidth: Float,
        screenHeight: Float,
        captureFocalLength: Int,
        captureLensNativeFocalMm: Float? = null
    ) {
        // Hardware has handed back the image (the caller was invoke from
        // `ImageCapture.OnImageSavedCallback`). Release the shutter-visual
        // state synchronously BEFORE flipping `_isCapturing` so the user
        // sees the button snap back even though the bitmap decode → EXIF
        // → crop → LUT → encode → save pipeline is still running in the
        // IO dispatcher below.
        endCapture()
        _isCapturing.value = true
        val tStart = System.currentTimeMillis()
        val currentAspectRatioMultiplier = _aspectRatio.value.heightToWidth
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var originalExposureTime = 0.0
                var originalIso = 0
                var exifOrientation = ExifInterface.ORIENTATION_NORMAL
                try {
                    val e = ExifInterface(rawFile.absolutePath)
                    originalExposureTime = e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
                    originalIso = e.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0)
                    exifOrientation = e.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } catch (e: Exception) { Log.e("CameraViewModel", "Error reading original EXIF", e) }

                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(rawFile.absolutePath, opts)
                val origW = opts.outWidth
                val origH = opts.outHeight
                if (origW <= 0 || origH <= 0) return@launch

                val (rotW, rotH) = when (exifOrientation) {
                    ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270 -> origH to origW
                    else -> origW to origH
                }

                var curX = 0; var curY = 0; var curW = rotW; var curH = rotH

                if (captureLensNativeFocalMm != null) {
                    val cropFactor = (captureLensNativeFocalMm / captureFocalLength).coerceIn(0f, 1f)
                    if (cropFactor < 0.99f) {
                        val cropW = (curW * cropFactor).toInt().coerceAtLeast(1)
                        val cropH = (curH * cropFactor).toInt().coerceAtLeast(1)
                        curX = (curW - cropW) / 2; curY = (curH - cropH) / 2
                        curW = cropW; curH = cropH
                    }
                }

                // The aspect-ratio setting is expressed in portrait terms
                // (heightToWidth > 1, e.g. 4:3 portrait). A landscape capture
                // (rotated dims wider than tall) must be cropped toward the
                // LANDSCAPE equivalent of the chosen ratio — otherwise a photo
                // taken with the phone held sideways gets force-cropped into a
                // portrait frame and the saved picture is never landscape.
                val arTargetRatio = if (rotW > rotH) {
                    currentAspectRatioMultiplier
                } else {
                    1f / currentAspectRatioMultiplier
                }
                val arActualRatio = curW.toFloat() / curH.toFloat()
                if (kotlin.math.abs(arActualRatio - arTargetRatio) >= 0.02f) {
                    if (arActualRatio > arTargetRatio) {
                        val targetW = (curH * arTargetRatio).toInt().coerceIn(1, curW)
                        curX += (curW - targetW) / 2; curW = targetW
                    } else {
                        val targetH = (curW / arTargetRatio).toInt().coerceIn(1, curH)
                        curY += (curH - targetH) / 2; curH = targetH
                    }
                }

                if (captureLensNativeFocalMm == null && boxWidthFraction < 0.99f) {
                    val wScreen = screenWidth; val hScreen = screenHeight
                    val arW = curW.toFloat(); val arH = curH.toFloat()
                    val scale = kotlin.math.max(wScreen / arW, hScreen / arH)
                    val wVisible = wScreen / scale; val hVisible = hScreen / scale
                    val xVisibleStart = (arW - wVisible) / 2f; val yVisibleStart = (arH - hVisible) / 2f
                    val wBox = wScreen * boxWidthFraction; val hBox = wBox * currentAspectRatioMultiplier
                    val xBox = (wScreen - wBox) / 2f; val yBox = (hScreen - hBox) / 2f
                    val xCrop = (xVisibleStart + (xBox / wScreen) * wVisible).toInt().coerceIn(0, curW - 1)
                    val yCrop = (yVisibleStart + (yBox / hScreen) * hVisible).toInt().coerceIn(0, curH - 1)
                    val wCrop = ((wBox / wScreen) * wVisible).toInt().coerceIn(1, curW - xCrop)
                    val hCrop = ((hBox / hScreen) * hVisible).toInt().coerceIn(1, curH - yCrop)
                    curX += xCrop; curY += yCrop; curW = wCrop; curH = hCrop
                }

                val cropArea = curW.toLong() * curH.toLong()
                val fullArea = rotW.toLong() * rotH.toLong()

                var finalBitmap: Bitmap
                if (cropArea < fullArea * 9 / 10) {
                    val (srcL, srcT, srcR, srcB) = when (exifOrientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 ->
                            intArrayOf(curY, origH - curX - curW, curY + curH, origH - curX)
                        ExifInterface.ORIENTATION_ROTATE_180 ->
                            intArrayOf(origW - curX - curW, origH - curY - curH, origW - curX, origH - curY)
                        ExifInterface.ORIENTATION_ROTATE_270 ->
                            intArrayOf(origW - curY - curH, curX, origW - curY, curX + curW)
                        else ->
                            intArrayOf(curX, curY, curX + curW, curY + curH)
                    }
                    // BitmapRegionDecoder.newInstance(String) is API 31+, but the
                    // project's minSdk is 24. The 2-arg `newInstance(String, Boolean)`
                    // overload is deprecated but available on all API levels, so we
                    // use it with `inInputShareable = false` (non-shared / safer) and
                    // suppress the deprecation lint. The modern 1-arg form is otherwise
                    // byte-for-byte equivalent (it internally calls this same path).
                    @Suppress("DEPRECATION")
                    val decoder = BitmapRegionDecoder.newInstance(rawFile.absolutePath, false)
                    // Pass the user's output-resolution preference into the
                    // region decoder so this crop-region branch honours the
                    // 3 MP / full sensor resolution choice just like the
                    // BitmapFactory path below does. The decoder scales the
                    // cropped rect on decode (no need to allocate and then
                    // downscale a full sensor resolution bitmap) so the saved
                    // JPEG matches the requested size. Per
                    // BitmapRegionDecoder's contract, `Rect` stays in original
                    // unscaled coordinates, which matches the `origW`/`origH`
                    // math above.
                    val regionOpts = BitmapFactory.Options().apply {
                        inSampleSize = _outputResolution.value.inSampleSize
                    }
                    val regionBitmap = decoder.decodeRegion(Rect(srcL, srcT, srcR, srcB), regionOpts)
                    decoder.recycle()

                    val matrix = Matrix()
                    when (exifOrientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                    }
                    if (_isFrontCamera.value) matrix.postScale(-1f, 1f)

                    finalBitmap = if (matrix.isIdentity) regionBitmap
                    else {
                        val rotated = Bitmap.createBitmap(regionBitmap, 0, 0, regionBitmap.width, regionBitmap.height, matrix, true)
                        if (rotated !== regionBitmap) regionBitmap.recycle()
                        rotated
                    }
                } else {
                    // Honour the user's output resolution preference so this
                    // no-crop branch decodes at either full sensor resolution
                    // (inSampleSize = 1) or at half resolution on each axis
                    // (inSampleSize = 2 → ~3 MP). The retro
                    // film-style output forgives the small loss of fine
                    // detail (the filter character — grain, tonal
                    // compression, colour tint — already hides it) but this
                    // single change cuts applyRetroFilter's pixel-loop work
                    // ~4× and the JPEG encode + save by a similar factor,
                    // dropping the full-quality post-capture path (the only
                    // path without explicit zoom-box cropping) from ~3-4 s
                    // to < 1 s on mid-range hardware.
                    // Honour the user's output resolution preference: inSampleSize = 2
                    // (3 MP) is the snappy default that pairs well with the retro filter
                    // aesthetic; inSampleSize = 1 keeps full source resolution for
                    // archival-quality shots at the cost of much slower capture.
                    val originalBitmap = BitmapFactory.decodeFile(rawFile.absolutePath,
                        BitmapFactory.Options().apply {
                            inMutable = true
                            inSampleSize = _outputResolution.value.inSampleSize
                        }) ?: return@launch
                    @Suppress("UNUSED_VARIABLE")

                    val matrix = Matrix()
                    when (exifOrientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
                        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
                    }
                    if (_isFrontCamera.value) matrix.postScale(-1f, 1f)

                    val normalizedBitmap = try {
                        if (matrix.isIdentity) originalBitmap
                        else Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, matrix, true)
                    } catch (e: Exception) { originalBitmap }
                    if (normalizedBitmap !== originalBitmap) originalBitmap.recycle()

                    finalBitmap = try {
                        Bitmap.createBitmap(normalizedBitmap, curX, curY, curW, curH)
                    } catch (e: Exception) {
                        Log.e("CameraViewModel", "Error in final crop", e)
                        normalizedBitmap
                    }
                    if (finalBitmap !== normalizedBitmap) normalizedBitmap.recycle()
                }

                val preset = _activePreset.value
                // One shared snapshot drives both the live preview and the
                // post-capture filter, so the saved JPEG can never drift from
                // what the viewfinder showed. The registry prefers a JSON
                // profile over the enum when one is bundled; the bundled
                // profile mirrors the enum so output is unchanged today.
                val renderParams = cameraProfileRegistry.renderParamsFor(
                    preset,
                    temperature = _temperature.value,
                    tint = _tint.value,
                    exposure = _exposure.value
                )
                val currentLut = loadLut(context, preset)
                if (currentLut != null || renderParams.needsProcessing) {
                    val filtered = if (USE_GPU_CAPTURE) {
                        // GPU still capture with a CPU fallback.
                        // GpuCaptureProcessor returns null on any EGL/shader
                        // failure, in which case the well-tested CPU pipeline
                        // below still produces the saved JPEG.
                        val gpu = try {
                            GpuCaptureProcessor().process(finalBitmap, renderParams, lut = currentLut)
                        } catch (e: Exception) {
                            Log.e("CameraViewModel", "GPU capture threw; using CPU filter", e)
                            null
                        }
                        gpu ?: finalBitmap.applyRetroFilter(renderParams, lut = currentLut)
                    } else {
                        finalBitmap.applyRetroFilter(renderParams, lut = currentLut)
                    }
                    if (filtered !== finalBitmap) {
                        finalBitmap.recycle()
                        finalBitmap = filtered
                    }
                }

                // When the Photo Frame setting is on, bake the film-card frame
                // into the saved JPEG itself so the frame travels with the file
                // (visible in every gallery app), not just the in-app viewer.
                val framedBitmap = if (_showGalleryFrame.value) {
                    bakeGalleryFrame(
                        photo = finalBitmap,
                        focalLength = captureFocalLength,
                        exposureTime = originalExposureTime,
                        iso = originalIso
                    )
                } else {
                    finalBitmap
                }
                if (framedBitmap !== finalBitmap) finalBitmap.recycle()

                FileOutputStream(rawFile).use { out -> framedBitmap.compress(Bitmap.CompressFormat.JPEG, 97, out) }
                framedBitmap.recycle()

                val focalDir = rawFile.parentFile
                val focalName = rawFile.nameWithoutExtension
                val focalExt = rawFile.extension
                val newName = "${focalName}_${captureFocalLength}mm.$focalExt"
                val renamedFile = File(focalDir, newName)
                rawFile.renameTo(renamedFile)

                try {
                    val exifOut = ExifInterface(renamedFile.absolutePath)
                    if (originalExposureTime > 0.0) { exifOut.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, originalExposureTime.toString()) }
                    if (originalIso > 0) { exifOut.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, originalIso.toString()) }
                    exifOut.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "${captureFocalLength}.0")
                    // The pixels were already rotated upright by the matrix pass
                    // above (EXIF rotation applied to the decoded bitmap before
                    // re-encoding), so the saved file is tagged NORMAL. Baking
                    // the rotation into the pixels + tagging NORMAL is what keeps
                    // the photo upright in external gallery apps and viewers.
                    exifOut.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                    exifOut.saveAttributes()
                } catch (e: Exception) { Log.e("CameraViewModel", "Error writing EXIF", e) }

                savePhotoToGallery(context, renamedFile)
                loadPhotos(context)
            } catch (e: Exception) {
                Log.e("CameraViewModel", "Error processing photo", e)
            }
            finally {
                _isCapturing.value = false
                Log.i("CaptureTime", "processAndSavePhoto total=${System.currentTimeMillis() - tStart} ms")
            }
        }
    }

    /**
     * Draw the film-card frame onto a photo so it ships inside the saved JPEG
     * (visible in every gallery app), matching the white card the in-app
     * gallery draws when the Photo Frame setting is enabled: a cream
     * background, a padded photo, and a footer with the device name (left)
     * and an EXIF summary — focal length, shutter speed, ISO (right).
     *
     * Sizes are scaled from the photo width using a 360 dp reference width (a
     * typical phone layout), so a 3 MP and a full-resolution capture both get
     * proportionally identical frames. Orientation is intentionally omitted
     * from the footer — saved photos are always tagged NORMAL.
     */
    internal fun bakeGalleryFrame(
        photo: Bitmap,
        focalLength: Int,
        exposureTime: Double,
        iso: Int
    ): Bitmap {
        val scale = photo.width / 360f
        val pad = (12f * scale).toInt().coerceAtLeast(1)
        val spacer = (14f * scale).toInt().coerceAtLeast(1)
        val textSize = (12f * scale).toInt().coerceAtLeast(1)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize.toFloat()
            // Black at 55 % alpha — matches the gallery footer text.
            color = 0x8C000000.toInt()
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }
        val fm = textPaint.fontMetrics
        val textHeight = (fm.descent - fm.ascent).toInt()

        val frameW = photo.width + 2 * pad
        val frameH = pad + photo.height + spacer + textHeight + pad

        val frame = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        // Cream card background, same colour as the gallery card.
        canvas.drawColor(0xFFF9FAF9.toInt())
        canvas.drawBitmap(photo, pad.toFloat(), pad.toFloat(), null)

        val baseline = pad + photo.height + spacer - fm.ascent

        // Device name, left-aligned.
        canvas.drawText(Build.MODEL, pad.toFloat(), baseline, textPaint)

        // EXIF summary, right-aligned (formatted exactly like the gallery
        // footer: "24mm  1/1000s  ISO 100").
        val shutterSpeed = if (exposureTime > 0.0) {
            if (exposureTime < 1.0) { val denom = kotlin.math.round(1.0 / exposureTime).toInt(); "1/${denom}s" }
            else { "${kotlin.math.round(exposureTime).toInt()}s" }
        } else "--"
        val isoText = if (iso > 0) "ISO $iso" else "--"
        val metaText = "${focalLength}mm  $shutterSpeed  $isoText"
        val metaWidth = textPaint.measureText(metaText)
        canvas.drawText(metaText, frameW - pad - metaWidth, baseline, textPaint)

        return frame
    }

    private fun savePhotoToGallery(context: Context, file: File) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.IS_PENDING, 1)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ZoomBoxCamera")
            }
            val resolver = context.contentResolver
            val contentUri =
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(contentUri, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { `in` -> `in`.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        } catch (e: Exception) { Log.e("CameraViewModel", "Error saving to gallery", e) }
    }

    fun readExifData(file: File): ExifData {
        return try {
            val exif = ExifInterface(file.absolutePath)
            val exposureTime = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
            val shutterSpeed = if (exposureTime > 0.0) {
                if (exposureTime < 1.0) { val denom = kotlin.math.round(1.0 / exposureTime).toInt(); "1/${denom}s" }
                else { "${kotlin.math.round(exposureTime).toInt()}s" }
            } else "--"
            val isoRaw = exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0)
            val iso = if (isoRaw > 0) "ISO $isoRaw" else "--"
            val orientationTag = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val orientation = when (orientationTag) {
                ExifInterface.ORIENTATION_ROTATE_90 -> "90°"
                ExifInterface.ORIENTATION_ROTATE_180 -> "180°"
                ExifInterface.ORIENTATION_ROTATE_270 -> "270°"
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> "Mirrored H"
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> "Mirrored V"
                ExifInterface.ORIENTATION_TRANSPOSE -> "90° Mirrored"
                ExifInterface.ORIENTATION_TRANSVERSE -> "270° Mirrored"
                else -> "--"
            }
            val name = file.nameWithoutExtension
            val focalMatch = Regex("""_(\d+)mm$""").find(name)
            val focalLength = focalMatch?.groupValues?.get(1)?.let { "${it}mm" } ?: "--"
            ExifData(focalLength = focalLength, shutterSpeed = shutterSpeed, iso = iso, orientation = orientation)
        } catch (e: Exception) { Log.e("CameraViewModel", "Error reading EXIF", e); ExifData() }
    }

    fun deletePhoto(context: Context, file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val wasSelected = _selectedPhoto.value == file
                // Position of the deleted photo in the (newest-first) gallery,
                // captured BEFORE the file disappears so the auto-advance below
                // can land on the photo that fills the deleted slot — the same
                // one the user would have reached by scrolling farther down the
                // filmstrip. -1 is a sentinel meaning "file isn't currently
                // tracked" (e.g. out-of-band deletion); we handle that branch
                // explicitly below instead of coercing it to 0 (which would
                // silently jump the user to the newest photo).
                val insertionIndex = if (wasSelected) {
                    _capturedPhotos.value.indexOf(file)
                } else -1

                // The file passed in is whichever copy listPhotoFiles
                // surfaced (public takes precedence). There may still be a
                // same-name mirror in the app-private dir — delete that too
                // so a subsequent startup scan doesn't re-surface it as a
                // duplicate after the public copy was removed.
                val privateMirror = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                    ?.let { File(it, file.name) }
                listOfNotNull(file, privateMirror).distinct().forEach { candidate ->
                    if (candidate.exists()) candidate.delete()
                }

                // Remove the MediaStore row we created in savePhotoToGallery /
                // saveDngToGallery so the deletion propagates to OTHER gallery
                // apps (Google Photos, Files app, OEM gallery, etc.) instead
                // of only cleaning up our own file system copy. Without this,
                // the vice-versa half of the gallery-sync contract — "delete
                // from in-app gallery" → "delete from system gallery" — is
                // broken; other apps keep showing the photo until their next
                // background scan re-detects the missing file on disk (often
                // hours later).
                deleteMediaStoreRow(context, file)

                // Re-scan synchronously inside this coroutine instead of calling
                // loadPhotos() — loadPhotos fires its own viewModelScope.launch
                // and _capturedPhotos wouldn't be updated by the time we read it
                // for the advance decision. Note: rapid double deletes may
                // interleave (viewModelScope.launch is not serialized), but
                // MutableStateFlow guarantees ordered, conflated emissions, so
                // the eventual UI state is still the desired one.
                val refreshed = listPhotoFiles(context)
                _capturedPhotos.value = refreshed

                // Stay-in-gallery: deleting shouldn't kick the user out of the
                // photo viewer. Same-slot — next photo in filmstrip order
                // (chronologically older since the list is newest-first);
                // tail-stepping — when the deleted photo was the very last
                // entry; sentinel fallback — pick any photo to keep the
                // gallery open if the deleted file wasn't tracked anymore;
                // only close the viewer when there is literally nothing
                // left to show.
                if (wasSelected) {
                    _selectedPhoto.value = when {
                        insertionIndex in refreshed.indices -> refreshed[insertionIndex]
                        refreshed.isNotEmpty() -> refreshed.last()
                        else -> null
                    }
                }
            } catch (e: Exception) { Log.e("CameraViewModel", "Error deleting photo", e) }
        }
    }

    /**
     * Removes the MediaStore row(s) that correspond to [file] so the deletion
     * propagates to other gallery apps. Min SDK is 29 so we always deal in
     * scoped-storage semantics: the row was inserted by us via
     * MediaStore.Images.Media with a known absolute DATA path.
     *
     * The WHERE clause matches DISPLAY_NAME + DATA for two reasons:
     *   - DISPLAY_NAME alone risks colliding with a same-named JPEG from
     *     another app (e.g. user copies IMG_1234.jpg into another folder);
     *   - RELATIVE_PATH is unreliable as a selection key: savePhotoToGallery
     *     writes it without a trailing slash while saveDngToGallery writes
     *     "Pictures/ZoomBoxCamera/RAW/", and the platform MediaProvider
     *     doesn't always normalise the trailing slash on insert, so a
     *     RELATIVE_PATH comparison can fail to match our own freshly-written
     *     rows. DATA is the canonical "this file lives at this absolute
     *     path" column and works for both the JPEG root and the RAW subfolder.
     *
     * Deletion is best-effort: zero rows deleted is fine (file may have been
     * only in the app-private directory and never inserted into the public
     * tree). We log the count so it's traceable but don't surface as an error.
     */
    private fun deleteMediaStoreRow(context: Context, file: File) {
        try {
            val resolver = context.contentResolver
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            @Suppress("DEPRECATION")
            val rows = resolver.delete(
                collection,
                "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.DATA} = ?",
                arrayOf(file.name, file.absolutePath)
            )
            Log.i("CameraViewModel", "Deleted $rows MediaStore row(s) for ${file.name}")
        } catch (e: SecurityException) {
            // Vendors have been known to throw SecurityException when revoking
            // a delete on a row owned by a different package; log + swallow
            // rather than failing the in-app delete.
            Log.e("CameraViewModel", "Permission error deleting MediaStore row for ${file.name}", e)
        } catch (e: Exception) {
            Log.e("CameraViewModel", "Error deleting MediaStore row for ${file.name}", e)
        }
    }

    private suspend fun Bitmap.applyRetroFilter(
        params: RetroRenderParams,
        lut: CubeLut? = null
    ): Bitmap {
        // Destructure the shared snapshot into locals so the existing
        // per-pixel body stays untouched (and stays in the same order as the
        // GL shader's stages).
        val tempVal = params.temperature
        val tintVal = params.tint
        val expVal = params.exposure
        val grainStrength = params.grainStrength
        val grainChroma = params.grainChroma
        val filmCurve = params.filmCurve
        val contrast = params.contrast
        val saturation = params.saturation
        val bloomStrength = params.bloom
        val shadowTintStrength = params.shadowTintStrength
        val shadowTintR = params.shadowTintR
        val shadowTintG = params.shadowTintG
        val shadowTintB = params.shadowTintB
        val highlightTintStrength = params.highlightTintStrength
        val highlightTintR = params.highlightTintR
        val highlightTintG = params.highlightTintG
        val highlightTintB = params.highlightTintB
        val fringing = params.fringing
        val softFocus = params.softFocus
        val milkyMix = params.milkyMix
        val milkyTintR = params.milkyTintR
        val milkyTintG = params.milkyTintG
        val milkyTintB = params.milkyTintB
        val highlightRolloff = params.highlightRolloff
        val fade = params.fade

        val w = this.width
        val h = this.height
        if (w <= 0 || h <= 0) return this

        val target = if (this.isMutable) this else this.copy(this.config ?: Bitmap.Config.ARGB_8888, true)

        val hasExp = expVal != 0f
        val hasTemp = tempVal != 0f
        val hasTint = tintVal != 0f

        // GPU-matched WB formulas (matching FRAG_SHADER in LutPreviewRenderer):
        //   temp:  c.r += temp*0.04; c.b -= temp*0.04
        //   tint:  c.g -= tint*0.04; c.r += tint*0.02; c.b += tint*0.02
        // Precompute combined per-channel deltas in normalised [0,1] space
        // so the per-pixel loop only does float add + multiply.
        val wbActive = hasTemp || hasTint
        val wbDeltaR = tempVal * 0.04f + tintVal * 0.02f
        val wbDeltaG = -tintVal * 0.04f
        val wbDeltaB = -tempVal * 0.04f + tintVal * 0.02f

        val expScale = if (hasExp) java.lang.Math.pow(2.0, expVal * 0.4).toFloat() else 1f

        // ── Precompute bloom look-up table ──
        // For the CPU path we use a simplified bloom that works on the
        // quantized 255 values: a luminance-based warm glow added at the end.
        val bloomActive = bloomStrength > 0f

        // ── Vignette precomputations ──
        val cx = w * 0.5f
        val cy = h * 0.5f
        val maxRadius = kotlin.math.max(w, h).toFloat() * 0.72f
        val maxRadiusInv = 1f / maxRadius
        val vigInner = 0.55f
        val vigRange = 0.45f
        // Keep the film-frame falloff subtle so corners stay readable.
        // This mirrors the live GLSurface shader's reduced vignette strength.
        val vigFadeMax = 95f / 255f
        val cornerRgb = 8f
        val innerRadiusSq = (vigInner * maxRadius) * (vigInner * maxRadius)

        val cachedBuffers = filterBuffers.get() ?: FilterBuffers().also { filterBuffers.set(it) }
        // A coroutine can suspend while its child chunks run. If another
        // capture resumes on this same dispatcher thread in the meantime,
        // give it private arrays rather than corrupting the first result.
        val buffers = if (cachedBuffers.inUse) FilterBuffers() else cachedBuffers
        buffers.inUse = true

        try {
            val rowDy2 = buffers.rowDistanceSquared.let { buffer ->
            if (buffer.size < h) {
                FloatArray(h).also { buffers.rowDistanceSquared = it }
            } else {
                buffer
            }
        }
        for (y in 0 until h) {
            val dy = y - cy
            rowDy2[y] = dy * dy
        }

        val pixelCount = w * h
        val pixels = buffers.pixels.let { buffer ->
            if (buffer.size < pixelCount) {
                IntArray(pixelCount).also { buffers.pixels = it }
            } else {
                buffer
            }
        }
        target.getPixels(pixels, 0, w, 0, 0, w, h)

        // Precompute LUT params so each parallel chunk can do its
        // trilinear blend inline (one pixel pass total) instead of relying
        // on the previous separate LutColorFilter.applyInPlace call. That
        // call did a SECOND full-bitmap getPixels + per-pixel trilinear
        // blend + setPixels which was responsible for ~3–4 s of capture
        // latency on full sensor resolution JPEGs (single-threaded, even when the retro
        // chunks above finished quickly on quad-core devices).
        val lutActive = lut != null
        val lutData: FloatArray? = lut?.data
        val lutN: Int = lut?.size ?: 0
        val lutMaxIdx: Int = if (lutN > 1) lutN - 1 else 0
        val lutMaxIdxF: Float = lutMaxIdx.toFloat()
        val lutScaleF: Float = if (lutN > 1) (1f / 255f) * (lutN - 1).toFloat() else 0f
        val lutSz: Int = if (lutN > 1) lutN * lutN else 0

        // ── Precompute Film S-Curve table ──
        // Before this table existed, every per-pixel film-curve call ran
        // StrictMath.exp twice with no hardware acceleration (~50–100 ns
        // each). At three channels per pixel that was 6 exp() per pixel —
        // ~3 s of post-processing on a 12 MP bitmap. Quantising the input
        // to 256 buckets and keying a FloatArray lookup replaces the
        // per-pixel transcendental with one array read. The quantisation
        // step (1/255 ≈ 0.0039 in the unit interval) is well below one
        // 8-bit LSB after the downstream `(value * 255f + 0.5f).toInt()`
        // rounding, so JPEG output bytes are identical for every
        // practical input.
        val filmCurveLut: FloatArray? = if (filmCurve > 0f) {
            FloatArray(256).also { tbl ->
                val s = filmCurve * 0.5f
                for (i in 0..255) {
                    val x = i / 255f
                    val toe = (1f - kotlin.math.exp(-x * 5.0f)) * s * 0.12f
                    val shoulder = (1f - kotlin.math.exp(-(1f - x) * 5.0f)) * s * 0.20f
                    var r = x + toe - shoulder
                    r += (x - 0.5f) * s * 0.15f
                    tbl[i] = r.coerceIn(0f, 1f)
                }
            }
        } else null

        val numChunks = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val total = w * h
        val chunkSize = (total + numChunks - 1) / numChunks

        // ── Soft-focus pre-pass (dreamcore) ──
        // Reads a frozen copy of the input bitmap (snapshot), averages a
        // 3x3 box neighbourhood for each pixel, and writes the blurred
        // mix BACK INTO `pixels` (in-place). After this pass the rest of
        // the pipeline sees blurred pixels as its input — the WB, exposure,
        // S-curve and toning stages then operate on the soft signal so
        // the entire downstream image has the dreamy, smoothed look.
        //
        // Why a snapshot rather than reading directly from `pixels`:
        // the parallel chunks below all access the same `pixels[]`
        // array, and a blur sample at index p+X reads 8 neighbours that
        // may already have been rewritten to the post-blur value by
        // another chunk or by an earlier sweep through this chunk. A
        // pre-capture snapshot freezes neighbours at their input values,
        // so each pixel's blur sees a true box-3x3 window of the
        // pre-blur bitmap. Memory cost is one extra IntArray (≈12 MB
        // for a 3 MP capture); only allocated when softFocus > 0 so
        // other presets pay nothing.
        val softFocusActive = softFocus > 0f
        if (softFocusActive) {
            val snap = buffers.softFocusSnapshot?.let { if (it.size >= pixelCount) it else null }
                ?: IntArray(pixelCount).also { buffers.softFocusSnapshot = it }
            System.arraycopy(pixels, 0, snap, 0, pixelCount)

            val blursChunks = numChunks
            val blurChunkSize = (total + blursChunks - 1) / blursChunks
            coroutineScope {
                (0 until blursChunks).map { chunk ->
                    async(Dispatchers.Default) {
                        val a = chunk * blurChunkSize
                        val b = (a + blurChunkSize).coerceAtMost(total)
                        var p = a
                        while (p < b) {
                            val x = p % w
                            val y = p / w
                            var sumR = 0
                            var sumG = 0
                            var sumB = 0
                            // 3x3 box read with edge-clamping so the
                            // blur doesn't produce darker outer pixels
                            // (the blur kernel spreads the missing
                            // contributions into the boundary).
                            val yTop = if (y > 0) y - 1 else 0
                            val yBot = if (y < h - 1) y + 1 else h - 1
                            val xLft = if (x > 0) x - 1 else 0
                            val xRgt = if (x < w - 1) x + 1 else w - 1
                            val rowT = yTop * w
                            val rowM = y * w
                            val rowB = yBot * w
                            val s_tl = snap[rowT + xLft]
                            val s_tc = snap[rowT + x]
                            val s_tr = snap[rowT + xRgt]
                            val s_ml = snap[rowM + xLft]
                            val s_mc = snap[rowM + x]
                            val s_mr = snap[rowM + xRgt]
                            val s_bl = snap[rowB + xLft]
                            val s_bc = snap[rowB + x]
                            val s_br = snap[rowB + xRgt]
                            sumR = (s_tl ushr 16 and 0xFF) +
                                   (s_tc ushr 16 and 0xFF) +
                                   (s_tr ushr 16 and 0xFF) +
                                   (s_ml ushr 16 and 0xFF) +
                                   (s_mc ushr 16 and 0xFF) +
                                   (s_mr ushr 16 and 0xFF) +
                                   (s_bl ushr 16 and 0xFF) +
                                   (s_bc ushr 16 and 0xFF) +
                                   (s_br ushr 16 and 0xFF)
                            sumG = (s_tl ushr 8 and 0xFF) +
                                   (s_tc ushr 8 and 0xFF) +
                                   (s_tr ushr 8 and 0xFF) +
                                   (s_ml ushr 8 and 0xFF) +
                                   (s_mc ushr 8 and 0xFF) +
                                   (s_mr ushr 8 and 0xFF) +
                                   (s_bl ushr 8 and 0xFF) +
                                   (s_bc ushr 8 and 0xFF) +
                                   (s_br ushr 8 and 0xFF)
                            sumB = (s_tl and 0xFF) +
                                   (s_tc and 0xFF) +
                                   (s_tr and 0xFF) +
                                   (s_ml and 0xFF) +
                                   (s_mc and 0xFF) +
                                   (s_mr and 0xFF) +
                                   (s_bl and 0xFF) +
                                   (s_bc and 0xFF) +
                                   (s_br and 0xFF)
                            val bR = sumR / 9
                            val bG = sumG / 9
                            val bB = sumB / 9

                            val orig = pixels[p]
                            val alphaMask = orig and 0xFF000000.toInt()
                            val origR = (orig ushr 16) and 0xFF
                            val origG = (orig ushr 8) and 0xFF
                            val origB = orig and 0xFF
                            // mix(original, blurred, softFocus) — preserves
                            // original sharpness when softFocus approaches 0
                            // and is a pure 3x3 average when softFocus=1.
                            val mixR = (origR + (bR - origR) * softFocus).toInt().coerceIn(0, 255)
                            val mixG = (origG + (bG - origG) * softFocus).toInt().coerceIn(0, 255)
                            val mixB = (origB + (bB - origB) * softFocus).toInt().coerceIn(0, 255)
                            pixels[p] = alphaMask or (mixR shl 16) or (mixG shl 8) or mixB
                            p++
                        }
                    }
                }
            }
        }

        // Precompute the milky haze parameters (constants for this run,
        // computed once instead of per-pixel). Inert unless the preset
        // asked for any milky amount.
        val milkyActive = milkyMix > 0f
        val milkyStrengthShade = milkyMix * 1.2f  // weight on shadow side
        val milkyStrengthBase = milkyMix * 0.25f // baseline wash even at luma=1
        val milkyR = milkyTintR.coerceIn(0f, 1f)
        val milkyG = milkyTintG.coerceIn(0f, 1f)
        val milkyB = milkyTintB.coerceIn(0f, 1f)

        coroutineScope {
            (0 until numChunks).map { chunk ->
                val start = chunk * chunkSize
                val end = (start + chunkSize).coerceAtMost(total)
                async(Dispatchers.Default) {
                    var p = start
                    while (p < end) {
                        val x = p % w
                        val y = p / w
                        val dx = x - cx

                        val c = pixels[p]
                        val a = (c ushr 24) and 0xFF
                        var r8 = (c ushr 16) and 0xFF
                        var g8 = (c ushr 8) and 0xFF
                        var b8 = c and 0xFF

                        // Convert to float for processing
                        var rf = r8 / 255f
                        var gf = g8 / 255f
                        var bf = b8 / 255f

                        // ── 1. White Balance ──
                        if (wbActive) {
                            rf += wbDeltaR
                            gf += wbDeltaG
                            bf += wbDeltaB
                            rf = rf.coerceIn(0f, 1f)
                            gf = gf.coerceIn(0f, 1f)
                            bf = bf.coerceIn(0f, 1f)
                        }

                        // ── 2. Exposure ──
                        if (hasExp) {
                            rf = (rf * expScale).coerceIn(0f, 1f)
                            gf = (gf * expScale).coerceIn(0f, 1f)
                            bf = (bf * expScale).coerceIn(0f, 1f)
                        }

                        // ── 3. Chromatic Fringing ──
                        // Note: On CPU we simulate fringing by skewing R vs B
                        // relative to G on bright edges. A simplified approach:
                        // shift R and B oppositely based on horizontal gradient
                        // approximation. For the CPU path we keep it lightweight:
                        // we sample neighboring pixels through the array.
                        // Actually, true fringing would need neighbor access which
                        // is expensive in a per-pixel parallel loop. We approximate
                        // it as a per-pixel color misregistration offset based on
                        // local brightness gradient. For simplicity and performance,
                        // we apply a slight R/B separation proportional to
                        // (rf - bf) so that high-frequency color edges get a subtle
                        // split — a cheap stand-in for optical misregistration.
                        if (fringing > 0f) {
                            val rOffset = (rf - bf) * fringing * 0.5f
                            val bOffset = (bf - rf) * fringing * 0.5f
                            rf = (rf + rOffset).coerceIn(0f, 1f)
                            bf = (bf + bOffset).coerceIn(0f, 1f)
                        }

                        // ── 4. Film S-Curve ──
                        // Replaces the hard clip with a smooth shoulder/toe.
                        // Math is precomputed once per capture in
                        // `filmCurveLut` (above); the per-pixel work is now
                        // one FloatArray lookup per channel instead of 2
                        // StrictMath.exp + 4 multiply+adds each.
                        if (filmCurveLut != null) {
                            rf = filmCurveLut[(rf * 255f + 0.5f).toInt()]
                            gf = filmCurveLut[(gf * 255f + 0.5f).toInt()]
                            bf = filmCurveLut[(bf * 255f + 0.5f).toInt()]
                        }

                        // ── 5. Halation / Bloom (luma-based additive glow) ──
                        // Simplified: compute luma, extract brights, tint warm, add.
                        if (bloomActive) {
                            val luma = rf * 0.299f + gf * 0.587f + bf * 0.114f
                            val brightMask = ((luma - 0.3f) / 0.5f).coerceIn(0f, 1f)
                            val warmGlowR = brightMask * bloomStrength * luma * 1.0f
                            val warmGlowG = brightMask * bloomStrength * luma * 0.7f
                            val warmGlowB = brightMask * bloomStrength * luma * 0.3f
                            rf = (rf + warmGlowR).coerceIn(0f, 1f)
                            gf = (gf + warmGlowG).coerceIn(0f, 1f)
                            bf = (bf + warmGlowB).coerceIn(0f, 1f)
                        }

                        // ── 5.5. Highlight roll-off (filmic shoulder) ──
                        // Mirrors the GL shader's rolloffChannel() exactly:
                        // identity below the 0.7 knee, soft shoulder above.
                        if (highlightRolloff > 0f) {
                            if (rf > 0.7f) {
                                val t = (rf - 0.7f) / 0.3f
                                rf = 0.7f + (t - highlightRolloff * t * (1f - t)) * 0.3f
                            }
                            if (gf > 0.7f) {
                                val t = (gf - 0.7f) / 0.3f
                                gf = 0.7f + (t - highlightRolloff * t * (1f - t)) * 0.3f
                            }
                            if (bf > 0.7f) {
                                val t = (bf - 0.7f) / 0.3f
                                bf = 0.7f + (t - highlightRolloff * t * (1f - t)) * 0.3f
                            }
                        }

                        // ── 6. Vignette ──
                        val distSq = dx * dx + rowDy2[y]
                        if (distSq > innerRadiusSq) {
                            val dist = kotlin.math.sqrt(distSq)
                            val radialT = (dist * maxRadiusInv - vigInner) / vigRange
                            if (radialT > 0f) {
                                val clampedT = if (radialT > 1f) 1f else radialT
                                val shaderA = clampedT * vigFadeMax
                                val shaderC = clampedT * cornerRgb
                                val invA = 1f - shaderA
                                val cornerContrib = shaderC * shaderA
                                r8 = (rf * 255f).toInt()
                                g8 = (gf * 255f).toInt()
                                b8 = (bf * 255f).toInt()
                                r8 = (r8 * invA + cornerContrib).toInt().coerceIn(0, 255)
                                g8 = (g8 * invA + cornerContrib).toInt().coerceIn(0, 255)
                                b8 = (b8 * invA + cornerContrib).toInt().coerceIn(0, 255)
                                rf = r8 / 255f
                                gf = g8 / 255f
                                bf = b8 / 255f
                            }
                        }

                        // ── 7. Contrast & Saturation (applied post-LUT) ──
                        // These are normally applied after the LUT. Since the LUT
                        // is applied later as a separate step, we do contrast/sat
                        // here in the pixel loop but they conceptually come after
                        // the LUT in the signal chain. To keep the effect, we
                        // apply them now and the LUT step will map the result.
                        if (contrast != 1.0f || saturation != 1.0f) {
                            val luma = rf * 0.299f + gf * 0.587f + bf * 0.114f
                            // Contrast — pivot around 0.5
                            if (contrast != 1.0f) {
                                rf = ((rf - 0.5f) * contrast + 0.5f).coerceIn(0f, 1f)
                                gf = ((gf - 0.5f) * contrast + 0.5f).coerceIn(0f, 1f)
                                bf = ((bf - 0.5f) * contrast + 0.5f).coerceIn(0f, 1f)
                            }
                            // Saturation — blend toward luma
                            if (saturation != 1.0f) {
                                rf = (luma + (rf - luma) * saturation).coerceIn(0f, 1f)
                                gf = (luma + (gf - luma) * saturation).coerceIn(0f, 1f)
                                bf = (luma + (bf - luma) * saturation).coerceIn(0f, 1f)
                            }
                        }

                        // ── 8. Split Toning ──
                        if (shadowTintStrength > 0f || highlightTintStrength > 0f) {
                            val luma = rf * 0.299f + gf * 0.587f + bf * 0.114f
                            val shadowW = (1f - luma).coerceIn(0f, 1f)
                            val highlightW = luma.coerceIn(0f, 1f)
                            if (shadowTintStrength > 0f) {
                                rf += shadowTintR * shadowW * shadowTintStrength
                                gf += shadowTintG * shadowW * shadowTintStrength
                                bf += shadowTintB * shadowW * shadowTintStrength
                            }
                            if (highlightTintStrength > 0f) {
                                rf += highlightTintR * highlightW * highlightTintStrength
                                gf += highlightTintG * highlightW * highlightTintStrength
                                bf += highlightTintB * highlightW * highlightTintStrength
                            }
                            rf = rf.coerceIn(0f, 1f)
                            gf = gf.coerceIn(0f, 1f)
                            bf = bf.coerceIn(0f, 1f)
                        }

                        // ── 8.5. Fade (black-point lift) ──
                        // Mirrors the GL shader: lift shadows toward mid-gray,
                        // leaving mid-tones and highlights nearly untouched.
                        if (fade > 0f) {
                            rf += fade * (0.5f - rf) * (1f - rf)
                            gf += fade * (0.5f - gf) * (1f - gf)
                            bf += fade * (0.5f - bf) * (1f - bf)
                            rf = rf.coerceIn(0f, 1f)
                            gf = gf.coerceIn(0f, 1f)
                            bf = bf.coerceIn(0f, 1f)
                        }

                        // Convert back to 8-bit
                        r8 = (rf * 255f + 0.5f).toInt().coerceIn(0, 255)
                        g8 = (gf * 255f + 0.5f).toInt().coerceIn(0, 255)
                        b8 = (bf * 255f + 0.5f).toInt().coerceIn(0, 255)

                        // ── 9. Film Grain ──
                        if (grainStrength > 0f) {
                            // ── Realistic silver-halide film grain ────────────
                            val lum = (r8 + g8 + b8) / 765f
                            val midMask = 1f - 4f * (lum - 0.5f) * (lum - 0.5f) // [0, 1]
                            val midMaskClamped = midMask.coerceAtLeast(0.4f)

                            val fine = valueNoise2D(x * 1.05f, y * 1.05f)
                            val medium = valueNoise2D(x * 0.32f + 31.7f, y * 0.32f + 17.3f)
                            val monoCentered = (fine + medium) - 1f

                            val amp = grainStrength * (1.2f + 2.0f * midMaskClamped) * 11f
                            val monoDelta = (monoCentered * amp).toInt()

                            var chromaR = 0
                            var chromaG = 0
                            var chromaB = 0
                            if (grainChroma > 0f) {
                                val cr = valueNoise2D(x * 1.13f + 7.1f, y * 1.13f + 3.7f)
                                val cg = valueNoise2D(x * 0.97f + 91.3f, y * 0.97f + 47.2f)
                                val cb = valueNoise2D(x * 1.21f + 13.4f, y * 1.21f + 71.9f)
                                chromaR = ((cr - 0.5f) * amp * grainChroma).toInt()
                                chromaG = ((cg - 0.5f) * amp * grainChroma).toInt()
                                chromaB = ((cb - 0.5f) * amp * grainChroma).toInt()
                            }

                            r8 = (r8 + monoDelta + chromaR).coerceIn(0, 255)
                            g8 = (g8 + monoDelta + chromaG).coerceIn(0, 255)
                            b8 = (b8 + monoDelta + chromaB).coerceIn(0, 255)
                        }

                        if (lutActive) {
                            // Trilinear LUT blend — folded from the
                            // previous standalone LutColorFilter.applyInPlace
                            // pass. Outputs the byte-quantized ARGB pixel in
                            // one go, no extra getPixels/setPixels round-trip.
                            val rF = r8.toFloat() * lutScaleF
                            val gF = g8.toFloat() * lutScaleF
                            val bF = b8.toFloat() * lutScaleF
                            val r0 = if (rF < 0f) 0 else if (rF > lutMaxIdxF) lutMaxIdx else rF.toInt()
                            val g0 = if (gF < 0f) 0 else if (gF > lutMaxIdxF) lutMaxIdx else gF.toInt()
                            val b0 = if (bF < 0f) 0 else if (bF > lutMaxIdxF) lutMaxIdx else bF.toInt()
                            val r1 = if (r0 < lutMaxIdx) r0 + 1 else lutMaxIdx
                            val g1 = if (g0 < lutMaxIdx) g0 + 1 else lutMaxIdx
                            val b1 = if (b0 < lutMaxIdx) b0 + 1 else lutMaxIdx
                            val dR = rF - r0
                            val dG = gF - g0
                            val dB = bF - b0
                            val dR1 = 1f - dR
                            val dG1 = 1f - dG
                            val dB1 = 1f - dB

                            val dataArr = lutData!!
                            val i000 = (b0 * lutSz + g0 * lutN + r0) * 3
                            val i100 = (b0 * lutSz + g0 * lutN + r1) * 3
                            val i010 = (b0 * lutSz + g1 * lutN + r0) * 3
                            val i110 = (b0 * lutSz + g1 * lutN + r1) * 3
                            val i001 = (b1 * lutSz + g0 * lutN + r0) * 3
                            val i101 = (b1 * lutSz + g0 * lutN + r1) * 3
                            val i011 = (b1 * lutSz + g1 * lutN + r0) * 3
                            val i111 = (b1 * lutSz + g1 * lutN + r1) * 3

                            val c000r = dataArr[i000];     val c100r = dataArr[i100]
                            val c010r = dataArr[i010];     val c110r = dataArr[i110]
                            val c001r = dataArr[i001];     val c101r = dataArr[i101]
                            val c011r = dataArr[i011];     val c111r = dataArr[i111]
                            val rLow = (c000r * dR1 + c100r * dR) * dG1 + (c010r * dR1 + c110r * dR) * dG
                            val rUp  = (c001r * dR1 + c101r * dR) * dG1 + (c011r * dR1 + c111r * dR) * dG
                            val outR = rLow * dB1 + rUp * dB

                            val c000g = dataArr[i000 + 1]; val c100g = dataArr[i100 + 1]
                            val c010g = dataArr[i010 + 1]; val c110g = dataArr[i110 + 1]
                            val c001g = dataArr[i001 + 1]; val c101g = dataArr[i101 + 1]
                            val c011g = dataArr[i011 + 1]; val c111g = dataArr[i111 + 1]
                            val gLow = (c000g * dR1 + c100g * dR) * dG1 + (c010g * dR1 + c110g * dR) * dG
                            val gUp  = (c001g * dR1 + c101g * dR) * dG1 + (c011g * dR1 + c111g * dR) * dG
                            val outG = gLow * dB1 + gUp * dB

                            val c000b = dataArr[i000 + 2]; val c100b = dataArr[i100 + 2]
                            val c010b = dataArr[i010 + 2]; val c110b = dataArr[i110 + 2]
                            val c001b = dataArr[i001 + 2]; val c101b = dataArr[i101 + 2]
                            val c011b = dataArr[i011 + 2]; val c111b = dataArr[i111 + 2]
                            val bLow = (c000b * dR1 + c100b * dR) * dG1 + (c010b * dR1 + c110b * dR) * dG
                            val bUp  = (c001b * dR1 + c101b * dR) * dG1 + (c011b * dR1 + c111b * dR) * dG
                            val outB = bLow * dB1 + bUp * dB

                            val or8 = (outR * 255f + 0.5f).toInt().coerceIn(0, 255)
                            val og8 = (outG * 255f + 0.5f).toInt().coerceIn(0, 255)
                            val ob8 = (outB * 255f + 0.5f).toInt().coerceIn(0, 255)

                            // ── 10. Milky pastel haze overlay (dreamcore) ──
                            // Final stage (after LUT): blend toward the cream
                            // tint, weighted toward shadows (heavy wash on
                            // dark pixels, gentle wash on highlights). Mirrors
                            // the GL shader so the JPEG and the live viewfinder
                            // agree. Falls through to the regular pixel write
                            // below after this conditional modifies or8/og8/ob8.
                            if (milkyActive) {
                                val ms =
                                    (1f - (or8 / 255f * 0.299f + og8 / 255f * 0.587f + ob8 / 255f * 0.114f))
                                        .coerceIn(0f, 1f) * milkyStrengthShade + milkyStrengthBase
                                val clampedMs = ms.coerceIn(0f, 1f)
                                val invMs = 1f - clampedMs
                                val mR = (or8 / 255f * invMs + milkyR * clampedMs) * 255f + 0.5f
                                val mG = (og8 / 255f * invMs + milkyG * clampedMs) * 255f + 0.5f
                                val mB = (ob8 / 255f * invMs + milkyB * clampedMs) * 255f + 0.5f
                                val mr8 = mR.toInt().coerceIn(0, 255)
                                val mg8 = mG.toInt().coerceIn(0, 255)
                                val mb8 = mB.toInt().coerceIn(0, 255)
                                pixels[p] = (a shl 24) or (mr8 shl 16) or (mg8 shl 8) or mb8
                            } else {
                                pixels[p] = (a shl 24) or (or8 shl 16) or (og8 shl 8) or ob8
                            }
                        } else {
                            // No LUT path — apply milky haze to the
                            // post-split-toning / post-grain / etc. RGB.
                            if (milkyActive) {
                                val lumF =
                                    (r8 / 255f * 0.299f + g8 / 255f * 0.587f + b8 / 255f * 0.114f)
                                val ms =
                                    ((1f - lumF).coerceIn(0f, 1f)) * milkyStrengthShade + milkyStrengthBase
                                val clampedMs = ms.coerceIn(0f, 1f)
                                val invMs = 1f - clampedMs
                                val mR = (r8 / 255f * invMs + milkyR * clampedMs) * 255f + 0.5f
                                val mG = (g8 / 255f * invMs + milkyG * clampedMs) * 255f + 0.5f
                                val mB = (b8 / 255f * invMs + milkyB * clampedMs) * 255f + 0.5f
                                val mr8 = mR.toInt().coerceIn(0, 255)
                                val mg8 = mG.toInt().coerceIn(0, 255)
                                val mb8 = mB.toInt().coerceIn(0, 255)
                                pixels[p] = (a shl 24) or (mr8 shl 16) or (mg8 shl 8) or mb8
                            } else {
                                pixels[p] = (a shl 24) or (r8 shl 16) or (g8 shl 8) or b8
                            }
                        }
                        p++
                    }
                }
            }.awaitAll()
        }

            target.setPixels(pixels, 0, w, 0, 0, w, h)

            // The LUT trilinear blend is now folded into the parallel chunks
            // above; no separate second pixel pass is needed.
            return target
        } finally {
            buffers.inUse = false
            if (buffers.pixels.size > MAX_RETAINED_FILTER_PIXELS) {
                buffers.pixels = IntArray(0)
                buffers.rowDistanceSquared = FloatArray(0)
            }
        }
    }

    /**
     * MurmurHash-3 style 32-bit integer hash mixing two independent large
     * primes (one per axis) into a single uniform random in [0, 1).
     *
     * Why two primes (and not `iy * 137` like the previous scheme): using a
     * small prime for Y produces visible horizontal banding in the noise
     * output because neighbouring rows hash to almost-the-same buckets.
     * Two unrelated large primes decorrelate the axes so the grain looks
     * like 2-D independent noise.
     *
     * Bit-width safety: Kotlin/JVM `Int` is signed and silently wraps on
     * overflow — that's exactly what we want from a hash. Only the final
     * `ushr` is critical (vs `shr`) so the sign bit never sneaks into the
     * normalized float, otherwise half the pixels would receive negative
     * "grain" deltas only.
     */
    private fun hash2D(x: Int, y: Int): Float {
        var h = x * 0x27d4eb2d xor y * 0x165667b1
        h = h xor (h ushr 13)
        h *= 0x85ebca6b.toInt()
        h = h xor (h ushr 16)
        // 24-bit mantissa: enough resolution per pixel and never negative.
        return ((h ushr 8) and 0xFFFFFF) / 16777216f
    }

    /**
     * 2-D value (Perlin-like) noise. Returns a uniform random in [0, 1) with
     * smooth interpolation between integer-lattice hash samples.
     *
     * Uses 5th-order smootherstep (`6x^5 - 15x^4 + 10x^3`) instead of the
     * usual 3rd-order smoothstep: the smootherstep has zero 1st and 2nd
     * derivatives at the lattice boundary, which kills the faint grid
     * cells visible in cheaper interpolators (you can usually see them
     * when zooming into generated Perlin noise — they read as soft
     * checkerboard instead of pure noise).
     *
     * Per-call cost: 4 hash lookups + ~6 multiplies + ~6 adds. Called twice
     * per grain pixel (fine + medium octaves) inside the parallel chunk
     * loop, which already amortises the chunk start/coroutine overhead.
     */
    private fun valueNoise2D(x: Float, y: Float): Float {
        val ix = x.toInt()
        val iy = y.toInt()
        val fx = x - ix
        val fy = y - iy
        val sx = fx * fx * fx * (fx * (fx * 6f - 15f) + 10f)
        val sy = fy * fy * fy * (fy * (fy * 6f - 15f) + 10f)

        val n00 = hash2D(ix, iy)
        val n10 = hash2D(ix + 1, iy)
        val n01 = hash2D(ix, iy + 1)
        val n11 = hash2D(ix + 1, iy + 1)

        val nx0 = n00 + (n10 - n00) * sx
        val nx1 = n01 + (n11 - n01) * sx
        return nx0 + (nx1 - nx0) * sy
    }

    override fun onCleared() {
        super.onCleared()
        try { orientationListener?.disable() } catch (_: Exception) {}
        try { shutterSound?.release() } catch (_: Exception) {}
        // Unregister the MediaStore observer we set up in init. Without this
        // the observer (which captures `viewModelScope` via the onChange
        // lambda) would leak past the ViewModel lifetime and the
        // ContentResolver would hold a phantom reference until process death.
        // ContentResolver.unregisterContentObserver throws
        // IllegalArgumentException if the observer isn't currently registered
        // (e.g. registration failed in init), so wrap defensively.
        mediaStoreObserver?.let {
            try { getApplication<Application>().contentResolver.unregisterContentObserver(it) }
            catch (_: Exception) {}
            mediaStoreObserver = null
        }
    }
}