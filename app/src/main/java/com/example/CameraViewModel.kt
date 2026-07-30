package com.example

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.media.MediaActionSound
import android.media.ExifInterface
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.Stable
import com.example.color.CubeLut
import com.example.color.CubeLutParser
import com.example.color.LutColorFilter
import com.example.zoom.AspectRatio
import com.example.zoom.CaptureExtension
import com.example.zoom.FovMapper
import com.example.zoom.LensCatalog
import com.example.zoom.LensRole
import com.example.zoom.PreviewSessionManager
import com.example.zoom.RawCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

@Stable
data class ExifData(
    val focalLength: String = "--",
    val shutterSpeed: String = "--",
    val iso: String = "--"
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
    val defaultFringing: Float = 0f
) {
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
    );
}

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val prefsRepo = UserPreferencesRepository(application)

    private val _selectedLensRole = MutableStateFlow(LensRole.PRIMARY)
    val selectedLensRole: StateFlow<LensRole> = _selectedLensRole.asStateFlow()

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

    private val _activePreset = MutableStateFlow(FilmPreset.WARM_PORTRAIT)
    val activePreset: StateFlow<FilmPreset> = _activePreset.asStateFlow()

    // Lazily-parsed LUTs keyed by asset path. Parsed once on first use and
    // reused for every subsequent capture that selects the same film.
    private val cachedLuts = mutableMapOf<String, CubeLut>()

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

    private val _lensSwitchTrigger = MutableStateFlow(0)
    val lensSwitchTrigger: StateFlow<Int> = _lensSwitchTrigger.asStateFlow()

    private val _showGridLines = MutableStateFlow(false)
    val showGridLines: StateFlow<Boolean> = _showGridLines.asStateFlow()

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

    init {
        viewModelScope.launch {
            prefsRepo.settingsFlow.first().let { saved ->
                _rawModeEnabled.value = saved.rawModeEnabled
                _aspectRatio.value = saved.aspectRatio
                _activePreset.value = saved.activePreset
                _flashMode.value = saved.flashMode
                _showGridLines.value = saved.showGridLines
                _selfTimerMode.value = saved.selfTimerMode
                _doubleExposureActive.value = saved.doubleExposureActive
                _isFrontCamera.value = saved.isFrontCamera
                _activeExtension.value = saved.activeExtension
                _selectedLensRole.value = saved.selectedLensRole
                _filmStyleScrollIndex.value = saved.filmStyleScrollIndex
                _filmStyleScrollOffset.value = saved.filmStyleScrollOffset
            }
        }
    }

    fun loadPhotos(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            _capturedPhotos.value = listPhotoFiles(context)
        }
    }

    /**
     * Synchronous directory scan shared by [loadPhotos] (async wrapper) and
     * [deletePhoto] (which needs the post-delete list *now* to auto-advance
     * the photo viewer's selection). Sorted newest-first to match the
     * gallery / filmstrip where index 0 is the most recent capture.
     */
    private fun listPhotoFiles(context: Context): List<File> {
        // Two locations hold our captures:
        //   1. App-private: getExternalFilesDir(DIRECTORY_PICTURES) — working
        //      copies written straight from the capture pipeline.
        //   2. Public-shared MediaStore mirror: Pictures/ZoomBoxCamera/ (plus
        //      its RAW subfolder). After an app reinstall the private copy
        //      is wiped but the MediaStore entries survive — scanning the
        //      public tree is what surfaces the user's old photos at startup.
        val privateDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val publicRoot = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "ZoomBoxCamera"
        )

        fun isOurPhoto(file: File): Boolean =
            file.isFile && file.extension.lowercase() in listOf("jpg", "jpeg", "dng")

        val privateFiles = privateDir?.listFiles(::isOurPhoto)?.toList() ?: emptyList()
        // Targeted scan: only check the root and known subdirectories
        // (RAW/) instead of walkTopDown() which traverses the entire tree.
        // runCatching guards against scoped-storage edge cases where the
        // public tree exists but listFiles() refuses to descend it.
        val publicFiles = runCatching {
            val rootFiles = publicRoot.listFiles(::isOurPhoto)?.toList() ?: emptyList()
            val rawDir = File(publicRoot, "RAW")
            val rawFiles = if (rawDir.isDirectory) {
                rawDir.listFiles(::isOurPhoto)?.toList() ?: emptyList()
            } else emptyList()
            rootFiles + rawFiles
        }.getOrDefault(emptyList())

        // Public first, then private — distinctBy { it.name } keeps the
        // public entry when both copies exist, so the file we hand to
        // deletePhoto() is the canonical (reinstall-survived) path.
        return (publicFiles + privateFiles)
            .distinctBy { it.name }
            .sortedByDescending { it.lastModified() }
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
        val clampedRatio = ratio.coerceIn(1.0f, 3.0f)
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
        val ordered = FilmPreset.values()
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
     * RAW capture entry point. Routes the shutter through [RawCapture.captureDng]
     * and inserts the resulting .dng into the gallery as image/x-adobe-dng.
     * Skips the JPEG post-processing pipeline (no retro filter / crop).
     */
    fun captureAndSaveRaw(
        context: Context,
        logicalCameraId: String,
        physicalCameraId: String,
        focalLengthMm: Int
    ) {
        _isCapturing.value = true
        RawCapture.captureDng(
            context = context,
            logicalCameraId = logicalCameraId,
            physicalCameraId = physicalCameraId,
            focalLengthMm = focalLengthMm,
            flashMode = _flashMode.value,
            onCaptured = { dngFile ->
                saveDngToGallery(context, dngFile)
                loadPhotos(context)
                android.widget.Toast.makeText(context, "RAW saved: ${dngFile.name}", android.widget.Toast.LENGTH_SHORT).show()
                _isCapturing.value = false
            },
            onError = { e ->
                Log.e("CameraViewModel", "RAW capture failed", e)
                android.widget.Toast.makeText(
                    context,
                    "RAW capture failed: ${e.localizedMessage ?: "unknown error"}",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ZoomBoxCamera/RAW")
                } else {
                    @Suppress("DEPRECATION")
                    put(MediaStore.Images.Media.DATA, file.absolutePath)
                }
            }
            val resolver = context.contentResolver
            val contentUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val uri = resolver.insert(contentUri, values) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { `in` -> `in`.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
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
        _isCapturing.value = true
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

                val arTargetRatio = 1f / currentAspectRatioMultiplier
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
                    val decoder = BitmapRegionDecoder.newInstance(rawFile.absolutePath, false)
                    val regionBitmap = decoder.decodeRegion(Rect(srcL, srcT, srcR, srcB), null)
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
                    val originalBitmap = BitmapFactory.decodeFile(rawFile.absolutePath,
                        BitmapFactory.Options().apply { inMutable = true }) ?: return@launch

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

                val currentLut = loadLut(context, _activePreset.value)
                val preset = _activePreset.value
                val hasAdjustments = _temperature.value != 0f || _tint.value != 0f || _exposure.value != 0f
                if (currentLut != null || hasAdjustments || preset.defaultGrainStrength > 0f ||
                    preset.defaultFilmCurve > 0f || preset.defaultContrast != 1.0f ||
                    preset.defaultSaturation != 1.0f || preset.defaultBloom > 0f ||
                    preset.shadowTintStrength > 0f || preset.highlightTintStrength > 0f ||
                    preset.defaultFringing > 0f) {
                    val filtered = finalBitmap.applyRetroFilter(
                        _temperature.value,
                        _tint.value,
                        _exposure.value,
                        lut = currentLut,
                        grainStrength = preset.defaultGrainStrength,
                        grainChroma = preset.defaultGrainChroma,
                        filmCurve = preset.defaultFilmCurve,
                        contrast = preset.defaultContrast,
                        saturation = preset.defaultSaturation,
                        bloomStrength = preset.defaultBloom,
                        shadowTintStrength = preset.shadowTintStrength,
                        shadowTintR = preset.shadowTintR,
                        shadowTintG = preset.shadowTintG,
                        shadowTintB = preset.shadowTintB,
                        highlightTintStrength = preset.highlightTintStrength,
                        highlightTintR = preset.highlightTintR,
                        highlightTintG = preset.highlightTintG,
                        highlightTintB = preset.highlightTintB,
                        fringing = preset.defaultFringing
                    )
                    if (filtered !== finalBitmap) {
                        finalBitmap.recycle()
                        finalBitmap = filtered
                    }
                }

                FileOutputStream(rawFile).use { out -> finalBitmap.compress(Bitmap.CompressFormat.JPEG, 97, out) }
                finalBitmap.recycle()

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
                    exifOut.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                    exifOut.saveAttributes()
                } catch (e: Exception) { Log.e("CameraViewModel", "Error writing EXIF", e) }

                savePhotoToGallery(context, renamedFile)
                loadPhotos(context)
            } catch (e: Exception) { Log.e("CameraViewModel", "Error processing photo", e) }
            finally { _isCapturing.value = false }
        }
    }

    private fun savePhotoToGallery(context: Context, file: File) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ZoomBoxCamera")
                } else { @Suppress("DEPRECATION") put(MediaStore.Images.Media.DATA, file.absolutePath) }
            }
            val resolver = context.contentResolver
            val contentUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(contentUri, values)
            if (uri != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { `in` -> `in`.copyTo(out) } }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }

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
            val name = file.nameWithoutExtension
            val focalMatch = Regex("""_(\d+)mm$""").find(name)
            val focalLength = focalMatch?.groupValues?.get(1)?.let { "${it}mm" } ?: "--"
            ExifData(focalLength = focalLength, shutterSpeed = shutterSpeed, iso = iso)
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
    private suspend fun Bitmap.applyRetroFilter(
        tempVal: Float,
        tintVal: Float,
        expVal: Float,
        lut: CubeLut? = null,
        grainStrength: Float = 0f,
        grainChroma: Float = 0f,
        // ── New film effect parameters ──
        filmCurve: Float = 0f,
        contrast: Float = 1.0f,
        saturation: Float = 1.0f,
        bloomStrength: Float = 0f,
        shadowTintStrength: Float = 0f,
        shadowTintR: Float = 0f,
        shadowTintG: Float = 0f,
        shadowTintB: Float = 0f,
        highlightTintStrength: Float = 0f,
        highlightTintR: Float = 0f,
        highlightTintG: Float = 0f,
        highlightTintB: Float = 0f,
        fringing: Float = 0f
    ): Bitmap {
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

        val expScale = if (hasExp) java.lang.Math.pow(2.0, (expVal * 0.4).toDouble()).toFloat() else 1f

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
        val vigFadeMax = 135f / 255f
        val cornerRgb = 12f
        val innerRadiusSq = (vigInner * maxRadius) * (vigInner * maxRadius)

        val rowDy2 = FloatArray(h) { y -> (y - cy).let { it * it } }

        val pixels = IntArray(w * h)
        target.getPixels(pixels, 0, w, 0, 0, w, h)

        val numChunks = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val total = w * h
        val chunkSize = (total + numChunks - 1) / numChunks

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
                        // Uses a simple parametric curve: toe lifts shadows,
                        // shoulder compresses highlights.
                        if (filmCurve > 0f) {
                            rf = filmScurve(rf, filmCurve)
                            gf = filmScurve(gf, filmCurve)
                            bf = filmScurve(bf, filmCurve)
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

                            r8 = (r8 + monoDelta + chromaR).toInt().coerceIn(0, 255)
                            g8 = (g8 + monoDelta + chromaG).toInt().coerceIn(0, 255)
                            b8 = (b8 + monoDelta + chromaB).toInt().coerceIn(0, 255)
                        }

                        pixels[p] = (a shl 24) or (r8 shl 16) or (g8 shl 8) or b8
                        p++
                    }
                }
            }.awaitAll()
        }

        target.setPixels(pixels, 0, w, 0, 0, w, h)

        if (lut != null) LutColorFilter.applyInPlace(target, lut)
        return target
    }

    /**
     * Film S-curve transfer function.
     * Implements a smooth toe (shadows lift) and shoulder (highlights compress)
     * using a simple parametric curve. At strength=0 it's identity (linear).
     * At strength=1 it's a pronounced filmic curve.
     *
     * The curve: c → curve_mid + (c - mid) adjusted by a sigmoid-like shaping
     * that compresses both extremes.
     */
    private fun filmScurve(x: Float, strength: Float): Float {
        // Simple and cheap: a contrast S-curve using smoothstep-like math
        // toe: soft lift of shadows
        // shoulder: soft compression of highlights
        val s = strength * 0.5f
        val toe = (1.0f - kotlin.math.exp(-x * 5.0f)) * s * 0.12f
        val shoulder = (1.0f - kotlin.math.exp(-(1.0f - x) * 5.0f)) * s * 0.20f
        var result = x + toe - shoulder
        // The curve also has a gentle S-shape: push midtones slightly
        val midPush = (x - 0.5f) * s * 0.15f
        result += midPush
        return result.coerceIn(0f, 1f)
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
        var h = x * 0x27d4eb2d.toInt() xor y * 0x165667b1.toInt()
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
        try { shutterSound?.release() } catch (_: Exception) {}
    }
}