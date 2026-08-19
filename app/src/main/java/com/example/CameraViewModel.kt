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
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.media.MediaActionSound
import android.media.ExifInterface
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
import com.example.color.applyRetroFilter
// The former standalone LutColorFilter class was removed when its trilinear
// blend got inlined into applyRetroFilter's parallel chunks (one pixel pass total).
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
import kotlin.math.PI
import kotlin.time.Duration.Companion.milliseconds

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val prefsRepo = UserPreferencesRepository(application)

    // All gallery / MediaStore file mechanics (scan, save, delete, EXIF)
    // live in PhotoStore; the ViewModel keeps only the state-flow
    // choreography around them.
    private val photoStore = PhotoStore(application)

    // One GPU still processor for the ViewModel's lifetime: its EGL display /
    // context are created once and reused across captures on a dedicated
    // thread (see GpuCaptureProcessor), instead of paying EGL setup per shot.
    private val gpuCaptureProcessor = GpuCaptureProcessor()

    private companion object {
        /**
         * When true, captures try the GPU still pipeline
         * ([GpuCaptureProcessor]) first and fall back to the CPU filter on
         * any EGL/shader failure. Enabled by default with automatic CPU
         * fallback; the known caveat is that GPU grain (hash noise) and CPU
         * grain (value noise) still differ slightly — see DAZZ_PORT_ROUND2.md
         * — so a golden-image parity pass is the remaining work before this
         * path is considered byte-stable against the CPU result.
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
                        _capturedPhotos.value = photoStore.listPhotos(skipOrphanCleanup = _isCapturing.value)
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
            _capturedPhotos.value = photoStore.listPhotos(skipOrphanCleanup = _isCapturing.value)
        }
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
    fun loadLut(context: Context, preset: FilmPreset): CubeLut? =
        loadLutByPath(context, preset.assetPath)

    /**
     * Loads the LUT the *live preview* should use for [preset]. Differs from
     * [loadLut] in that it honours the JSON look profile: the registry's
     * `lutPath` wins when the profile is bundled, so a JSON profile that
     * points at a different `.cube` grades the viewfinder exactly like the
     * capture pipeline instead of silently using the enum's LUT.
     */
    fun loadPreviewLut(context: Context, preset: FilmPreset): CubeLut? {
        val profile = cameraProfileRegistry.profileFor(preset)
        val path = profile.look.lutPath.ifBlank { preset.assetPath }
        return loadLutByPath(context, path)
    }

    private fun loadLutByPath(context: Context, assetPath: String): CubeLut? {
        // Pass-through / no-grade preset (e.g. NORMAL): skip the parser
        // and the asset I/O entirely. Returning null here is what tells
        // both the live GL preview (`LutPreviewView.setLut(null)`) and
        // the post-capture `applyRetroFilter` (the `currentLut != null`
        // OR-chain guard) to skip the LUT step.
        if (assetPath.isBlank()) return null
        cachedLuts[assetPath]?.let { return it }
        return try {
            val lut = CubeLutParser.parse(assetPath, context)
            cachedLuts[assetPath] = lut
            lut
        } catch (e: Exception) {
            Log.e("CameraViewModel", "Failed to load LUT $assetPath", e)
            null
        }
    }

    /**
     * The render snapshot the *live viewfinder* should show for [preset].
     * Goes through the same [CameraProfileRegistry] as the capture pipeline
     * ([renderParamsFor]), so JSON look profiles drive preview and capture
     * from one source of truth — the viewfinder can no longer drift from the
     * saved JPEG when a profile is tweaked in `assets/cameras/`.
     */
    fun previewRenderParams(
        preset: FilmPreset,
        temperature: Float,
        tint: Float,
        exposure: Float
    ): RetroRenderParams = cameraProfileRegistry.renderParamsFor(preset, temperature, tint, exposure)
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
                photoStore.saveDng(dngFile)
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
                    val originalBitmap = BitmapFactory.decodeFile(rawFile.absolutePath,
                        BitmapFactory.Options().apply {
                            inMutable = true
                            inSampleSize = _outputResolution.value.inSampleSize
                        }) ?: return@launch

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
                            gpuCaptureProcessor.process(finalBitmap, renderParams, lut = currentLut)
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

                photoStore.saveJpeg(renamedFile)
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

    fun readExifData(file: File): ExifData = photoStore.readExif(file)

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

                // File deletion (public copy + app-private mirror) plus
                // MediaStore row removal plus the gallery re-scan all live in
                // PhotoStore; the ViewModel keeps only the selection
                // choreography below. The re-scan is synchronous here (not
                // loadPhotos()) so _capturedPhotos is fresh by the time we
                // make the auto-advance decision.
                val refreshed = photoStore.deleteAndRefresh(
                    file,
                    skipOrphanCleanup = _isCapturing.value
                )
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

    override fun onCleared() {
        super.onCleared()
        gpuCaptureProcessor.release()
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