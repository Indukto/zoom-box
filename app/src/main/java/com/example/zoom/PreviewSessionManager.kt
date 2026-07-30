package com.example.zoom

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Log
import android.view.WindowManager
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.Stable
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

/**
 * Manages the live preview session, handling lens transitions.
 *
 * Uses Camera2Interop.Extender.setPhysicalCameraId() to route Preview and
 * ImageCapture use cases to the specific physical camera (ultra-wide, primary,
 * or tele) within the logical multi-camera device.
 *
 * Also pushes hardware ISP quality keys (EDGE_MODE, NOISE_REDUCTION_MODE,
 * HOT_PIXEL_MODE, LENS_OPTICAL_STABILIZATION_MODE, distortion correction) onto
 * the capture requests so every still benefits from the device's high-quality
 * processing pipeline.
 */
@Stable
data class BindResult(
    val camera: Camera,
    val imageCapture: ImageCapture
)

/**
 * Which OEM/vendor extension to apply to the camera session. NONE disables
 * extensions and keeps the app's manual physical-lens routing.
 *
 * Extensions take over sensor selection on the OEM side, so when an extension
 * is active the manual physical-lens routing is intentionally bypassed (the
 * extension owns the sensor). Modes unavailable on the device fall back to NONE.
 */
@Stable
enum class CaptureExtension(val mode: Int) {
    NONE(ExtensionMode.NONE),
    HDR(ExtensionMode.HDR),
    NIGHT(ExtensionMode.NIGHT),
    BOKEH(ExtensionMode.BOKEH),
    FACE_RETOUCH(ExtensionMode.FACE_RETOUCH),
    AUTO(ExtensionMode.AUTO);

    companion object {
        /** Modes surfaced to the user in the UI, in display order. */
        val userSelectable get() = listOf(NONE, HDR, NIGHT, BOKEH, AUTO)
    }
}

class PreviewSessionManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {

    companion object {
        private const val TAG = "PreviewSessionManager"

        /**
         * Retry delays (ms) between unbindAll() and bindToLifecycle() on
         * lens switch. Tuned for Xiaomi/MediaTek V4L2 kernel-device-node
         * teardown latency. Three attempts: 80 ms → 200 ms → 400 ms.
         */
        private val RETRY_DELAYS_MS = longArrayOf(80, 200, 400)

        /**
         * Cached ExtensionsManager. Resolved lazily and reused across binds;
         * one manager instance covers all cameras on the device.
         */
        @Volatile private var extensionsManager: ExtensionsManager? = null
        @Volatile private var extensionsProvider: ProcessCameraProvider? = null
    }

    /**
     * Tracks the currently-bound use cases and the logical camera they belong
     * to. Used to:
     *   1. Avoid tearing down the underlying CameraDevice on every lens swap
     *      (which is what `unbindAll()` does and what causes the split-second
     *      black flash in the PreviewView).
     *   2. Restore the previous binding if a fresh bind fails synchronously
     *      (so the viewfinder doesn't blink to black while we log the error).
     *
     * Marked @Volatile because bindToLifecycle must be invoked from the main
     * thread, but read-paths inside `bindPreview`/`bindDefaultCamera` are
     * single-threaded under normal Compose usage; the annotation is
     * defensive against future off-main-thread callers.
     */
    // Tracking state for the previously-bound use cases. Main-thread only by
    // CameraX contract — bindToLifecycle must be invoked on the main thread
    // and these fields are only read/written from there. No @Volatile needed;
    // the comment is here so future readers don't try to spawn background
    // binds without first un-synchronizing this state.
    private var currentPreview: Preview? = null
    private var currentImageCapture: ImageCapture? = null
    private var currentLogicalCameraId: String? = null
    /**
     * Tracks the front/back orientation of the last successful bind. Used
     * by the recovery branches to pick the right `CameraSelector` when the
     * previous binding came from [bindDefaultCamera] (which doesn't set
     * [currentLogicalCameraId]). NULL = we don't know (e.g. very first
     * bind, or the manager hasn't bound anything yet).
     */
    private var currentIsFrontCamera: Boolean? = null

    /**
     * Builds a CameraSelector targeting a logical camera by its CameraX ID.
     * Physical sub-cameras live inside the logical camera; they are selected
     * via Camera2Interop.Extender.setPhysicalCameraId() on each use case.
     */
    fun buildSelectorForLogicalCamera(logicalCameraId: String): CameraSelector {
        return CameraSelector.Builder()
            .addCameraFilter { cameras ->
                cameras.filter { camera ->
                    Camera2CameraInfo.from(camera).cameraId == logicalCameraId
                }
            }
            .build()
    }

    /**
     * Forces a clean tear-down of any state this manager tracks and detaches
     * everything from the camera provider. Call this when ownership of the
     * CameraDevice has to be handed off — e.g. entering RAW capture (which
     * opens its own Camera2 device and contends for the same hardware) or
     * on lifecycle STOP. Does NOT rebuild any use cases afterwards.
     */
    fun release(cameraProvider: ProcessCameraProvider) {
        try { cameraProvider.unbindAll() } catch (e: Exception) {
            Log.w(TAG, "release: unbindAll failed", e)
        }
        currentPreview = null
        currentImageCapture = null
        currentLogicalCameraId = null
        currentIsFrontCamera = null
    }

    /**
     * Picks the right `CameraSelector` to rebind the previously-attached
     * use cases on a failed bind. The two bind paths (`bindPreview` /
     * `bindDefaultCamera`) share this so they can't drift back into the
     * "switches back in a split second" symptom that happens when a back
     * bind fails and recovery silently flips to the primary lens.
     *
     * If a logical-camera id is recorded we use it; otherwise we fall
     * back to whichever `DEFAULT_*_CAMERA` was last bound. OEM extension
     * state (HDR/NIGHT/...) is deliberately NOT preserved here; recovery
     * jumps straight to the plain logical selector. We lose a fidelity
     * state but keep the viewfinder alive, which is the better trade —
     * the user can manually re-select HDR from the UI.
     */
    private fun recoverySelector(): CameraSelector {
        val id = currentLogicalCameraId
        if (id != null) {
            return buildSelectorForLogicalCamera(id)
        }
        return when (currentIsFrontCamera) {
            true -> CameraSelector.DEFAULT_FRONT_CAMERA
            false -> CameraSelector.DEFAULT_BACK_CAMERA
            null -> CameraSelector.DEFAULT_BACK_CAMERA
        }
    }

    /**
     * Binds a preview + ImageCapture use case to a specific physical camera
     * within a logical multi-camera device.
     *
     * @param cameraProvider The ProcessCameraProvider
     * @param logicalCameraId The parent logical camera ID
     * @param physicalCameraId The target physical camera ID
     * @param surfaceProvider The Preview.SurfaceProvider from the PreviewView
     * @param flashMode Flash mode: 0 = Auto, 1 = On, 2 = Off
     * @param extension OEM extension to apply (NONE = manual physical-lens path)
     * @return A BindResult containing the bound Camera and ImageCapture, or null on failure
     */
    suspend fun bindPreview(
        cameraProvider: ProcessCameraProvider,
        logicalCameraId: String,
        physicalCameraId: String,
        surfaceProvider: Preview.SurfaceProvider,
        flashMode: Int = 0,
        extension: CaptureExtension = CaptureExtension.NONE
    ): BindResult? {
        val rotation = context.displayRotation

        val characteristics = getCharacteristics(physicalCameraId)
            ?: getCharacteristics(logicalCameraId)

        // Extensions own the sensor path; only the non-extension path routes
        // to a specific physical lens (the app's signature behavior).
        //
        // Also skip the physical-lens routing when the IDs are equal: that
        // happens on single-lens devices/emulators (e.g. Pixel 5 AVD with
        // physicalCameraIds == [cameraId]) where `setPhysicalCameraId()` is a
        // documented no-op but in practice the HAL still has to undo the
        // surface config, which on virtualised stacks can intermittently
        // lead to an async `onConfigureFailed`. Letting the logical-camera
        // path bind clean here is strictly safer.
        val usePhysicalLens = extension == CaptureExtension.NONE &&
            physicalCameraId != logicalCameraId

        val preview = Preview.Builder()
            .apply {
                if (usePhysicalLens) {
                    Camera2Interop.Extender(this).setPhysicalCameraId(physicalCameraId)
                }
                applyQualityKeys(this, characteristics)
            }
            .build()
            .apply { setSurfaceProvider(surfaceProvider) }

        val newImageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(rotation)
            .apply {
                if (usePhysicalLens) {
                    Camera2Interop.Extender(this).setPhysicalCameraId(physicalCameraId)
                }
                applyQualityKeys(this, characteristics)
                when (flashMode) {
                    0 -> setFlashMode(ImageCapture.FLASH_MODE_AUTO)
                    1 -> setFlashMode(ImageCapture.FLASH_MODE_ON)
                    else -> setFlashMode(ImageCapture.FLASH_MODE_OFF)
                }
            }
            .build()

        val selector = if (usePhysicalLens) {
            buildSelectorForLogicalCamera(logicalCameraId)
        } else {
            // Build the base logical selector, then layer the extension on top.
            buildExtensionSelector(cameraProvider, logicalCameraId, extension)
                ?: buildSelectorForLogicalCamera(logicalCameraId)
        }

        // Remember the previously bound use cases so we can put them back if
        // the new bind fails synchronously. Without this, a single throw from
        // bindToLifecycle leaves the camera provider in an empty state and
        // the viewfinder blinks to black until the next ticker. Recovery
        // keeps the prior lens visible to the user even on the failed
        // switch — better to show "you can't switch here" than "the whole
        // preview is gone".
        val previousPreview = currentPreview
        val previousImageCapture = currentImageCapture
        val previousLogicalCameraId = currentLogicalCameraId

        // ───── unbindAll + retry path ─────────────────────────────────
        // CameraX's `bindToLifecycle(selector, new...)` is NOT an atomic
        // replace on devices that constrain the surface combination
        // count (Xiaomi/MTK only advertises a single PRIV/PREVIEW slot
        // and a single JPEG/STILL_CAPTURE slot). Selective `unbind()`
        // is asynchronous on these HALs — the old surfaces aren't torn
        // down before `bindToLifecycle` validates the combined set,
        // causing "No supported surface combination" with 4 surfaces
        // (2 existing + 2 new) where only 2 are supported.
        //
        // `unbindAll()` guarantees all surfaces are released before the
        // new bind, at the cost of a brief black flash on lens switch.
        //
        // On Xiaomi/MediaTek devices the V4L2 kernel device nodes are
        // torn down asynchronously after `unbindAll()` — without a delay
        // the next `bindToLifecycle()` hits a half-closed device and the
        // HAL logs "invalid device state 3". We therefore inject a short
        // delay and retry with progressively longer waits after failures.

        // Retry delays in milliseconds, tuned for MTK V4L2 node teardown.
        val retryDelays = RETRY_DELAYS_MS
        Log.i("LensSwitch", "bind START | logical=$logicalCameraId physical=$physicalCameraId usePhysical=$usePhysicalLens")

        for ((index, delayMs) in retryDelays.withIndex()) {
            try {
                cameraProvider.unbindAll()
                delay(delayMs)
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    newImageCapture
                )
                currentPreview = preview
                currentImageCapture = newImageCapture
                currentLogicalCameraId = logicalCameraId
                // bindPreview always targets a back-facing logical camera via
                // its specific id. currentIsFrontCamera is left untouched
                // here because recoverySelector() short-circuits on the
                // non-null logical id; the field is preserved across the
                // next bindDefaultCamera call in case the user toggles
                // to/from front and we lose the id.
                Log.i("LensSwitch", "bind OK | attempt=${index + 1}/${retryDelays.size} delay=${delayMs}ms")
                return BindResult(camera, newImageCapture)
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "bindPreview attempt ${index + 1}/${retryDelays.size} " +
                        "failed (delay=${delayMs}ms, physical=$physicalCameraId, " +
                        "logical=$logicalCameraId)",
                    e
                )
            }
        }

        // All retries exhausted. Try to restore the previous binding.
        Log.e(
            TAG,
            "All bindPreview attempts failed (physical=$physicalCameraId, " +
                "logical=$logicalCameraId); attempting recovery"
        )

        if (previousPreview != null && previousImageCapture != null) {
            try {
                cameraProvider.unbindAll()
                delay(80)
                val restored = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    recoverySelector(),
                    previousPreview,
                    previousImageCapture
                )
                Log.w(
                    TAG,
                    "Recovered after failed bind; previous use cases restored " +
                        "(logical=$previousLogicalCameraId, front=${currentIsFrontCamera})"
                )
                currentPreview = previousPreview
                currentImageCapture = previousImageCapture
                currentLogicalCameraId = previousLogicalCameraId
                Log.i("LensSwitch", "bind RECOVERY | restored previous lens (logical=$previousLogicalCameraId)")
                return BindResult(restored, previousImageCapture)
            } catch (e2: Exception) {
                Log.e(TAG, "Recovery bind failed; falling back to clean unbind", e2)
            }
        }

        // Last-resort: tear down completely.
        release(cameraProvider)
        return null
    }

    /**
     * Reports which extensions are actually available for a given logical
     * camera + lens-facing on this device. Use this to grey out unavailable
     * modes in the UI before calling [bindPreview].
     */
    fun availableExtensions(
        cameraProvider: ProcessCameraProvider,
        logicalCameraId: String,
        isFrontCamera: Boolean
    ): Set<CaptureExtension> {
        val supported = mutableSetOf<CaptureExtension>()
        try {
            val manager = getOrInitExtensionsManager(cameraProvider) ?: return emptySet()
            val baseSelector = CameraSelector.Builder()
                .addCameraFilter { cameras ->
                    cameras.filter { Camera2CameraInfo.from(it).cameraId == logicalCameraId }
                }
                .build()
            val facingSelector = if (isFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            CaptureExtension.userSelectable.forEach { ext ->
                if (ext == CaptureExtension.NONE) {
                    supported.add(ext)
                    return@forEach
                }
                try {
                    if (manager.isExtensionAvailable(facingSelector, ext.mode)) {
                        // Also require availability against the specific logical id;
                        // some devices only expose extensions on the main rear cam.
                        if (manager.isExtensionAvailable(baseSelector, ext.mode)) {
                            supported.add(ext)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Extension ${ext.name} probe failed", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "availableExtensions failed", e)
        }
        return supported
    }

    /**
     * Lazily initializes the shared ExtensionsManager. Returns null if the
     * Extensions API isn't usable on this device.
     */
    private fun getOrInitExtensionsManager(provider: ProcessCameraProvider): ExtensionsManager? {
        extensionsManager?.let { return it }
        return try {
            val future = ExtensionsManager.getInstanceAsync(context, provider)
            // Bounded wait — ExtensionsManager init should be near-instant
            // once the provider is up, which it already is by this point.
            val manager = future[5, TimeUnit.SECONDS]
            extensionsManager = manager
            extensionsProvider = provider
            manager
        } catch (e: Exception) {
            Log.w(TAG, "ExtensionsManager unavailable; extension modes disabled", e)
            null
        }
    }

    private fun buildExtensionSelector(
        provider: ProcessCameraProvider,
        logicalCameraId: String,
        extension: CaptureExtension
    ): CameraSelector? {
        val manager = getOrInitExtensionsManager(provider) ?: return null
        val base = CameraSelector.Builder()
            .addCameraFilter { cameras ->
                cameras.filter { Camera2CameraInfo.from(it).cameraId == logicalCameraId }
            }
            .build()
        return try {
            manager.getExtensionEnabledCameraSelector(base, extension.mode)
        } catch (e: Exception) {
            Log.w(TAG, "getExtensionEnabledCameraSelector(${extension.name}) failed", e)
            null
        }
    }

    /**
     * Applies the ISP quality CaptureRequest keys supported by this sensor to
     * the given Preview builder via Camera2-Interop. Each key is gated by the
     * corresponding AVAILABLE_*_MODES characteristic so unsupported devices
     * are left untouched (no-op).
     */
    private fun applyQualityKeys(
        builder: Preview.Builder,
        characteristics: CameraCharacteristics?
    ) {
        if (characteristics == null) return
        val extender = Camera2Interop.Extender(builder)
        applyEdgeMode(extender, characteristics)
        applyNoiseReductionMode(extender, characteristics)
        applyHotPixelMode(extender, characteristics)
        applyOisMode(extender, characteristics)
        applyDistortionCorrection(extender, characteristics)
    }

    /**
     * Same as [applyQualityKeys] but for the still-capture ImageCapture builder.
     */
    private fun applyQualityKeys(
        builder: ImageCapture.Builder,
        characteristics: CameraCharacteristics?
    ) {
        if (characteristics == null) return
        val extender = Camera2Interop.Extender(builder)
        applyEdgeMode(extender, characteristics)
        applyNoiseReductionMode(extender, characteristics)
        applyHotPixelMode(extender, characteristics)
        applyOisMode(extender, characteristics)
        applyDistortionCorrection(extender, characteristics)
    }

    // ---- Per-key helpers. Each is gated by the device's AVAILABLE_*_MODES ----
    // so we never request a mode the HAL can't deliver. `Extender<T : Builder<T>>`
    // is shared between Preview.Builder and ImageCapture.Builder.

    private fun applyEdgeMode(
        extender: Camera2Interop.Extender<*>,
        characteristics: CameraCharacteristics
    ) {
        @Suppress("UNCHECKED_CAST")
        val modes = characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: return
        // We intentionally do NOT push HIGH_QUALITY on the still-capture
        // path. On Xiaomi/MTK the "MTK_HAL_REQUEST_HIGH_QUALITY_CAP"
        // metadata tag is paired with our HIGH_QUALITY request and the
        // corresponding `bad pFeatureSettingQuery` line in the
        // mtkcam-capturesession log during processInstantResult (the
        // zero-shutter-latency preview pipeline). Capping both preview
        // and capture at FAST keeps edge sharpening consistent across
        // sessions and avoids the feature-pipeline reject.
        val chosen = when {
            modes.contains(CaptureRequest.EDGE_MODE_FAST) ->
                CaptureRequest.EDGE_MODE_FAST
            else -> return
        }
        setOptionSafe(extender, CaptureRequest.EDGE_MODE, chosen)
    }

    private fun applyNoiseReductionMode(
        extender: Camera2Interop.Extender<*>,
        characteristics: CameraCharacteristics
    ) {
        val modes = characteristics.get(
            CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: return
        // Same rationale as applyEdgeMode: cap at FAST on both preview
        // and capture. HIGH_QUALITY noise reduction feeds the same
        // `bad pFeatureSettingQuery` failure mode on Xiaomi/MTK during
        // a lens switch.
        val chosen = when {
            modes.contains(CaptureRequest.NOISE_REDUCTION_MODE_FAST) ->
                CaptureRequest.NOISE_REDUCTION_MODE_FAST
            else -> return
        }
        setOptionSafe(extender, CaptureRequest.NOISE_REDUCTION_MODE, chosen)
    }

    private fun applyHotPixelMode(
        extender: Camera2Interop.Extender<*>,
        characteristics: CameraCharacteristics
    ) {
        // Capped at FAST for symmetry with applyEdgeMode /
        // applyNoiseReductionMode / applyDistortionCorrection. Pushing
        // HOT_PIXEL_HIGH is well within the HAL feature combination that
        // triggers `bad pFeatureSettingQuery` on Xiaomi/MTK during a
        // lens switch.
        val modes = characteristics.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES) ?: return
        val chosen = when {
            modes.contains(CaptureRequest.HOT_PIXEL_MODE_FAST) ->
                CaptureRequest.HOT_PIXEL_MODE_FAST
            modes.contains(CaptureRequest.HOT_PIXEL_MODE_OFF) ->
                CaptureRequest.HOT_PIXEL_MODE_OFF
            else -> return
        }
        setOptionSafe(extender, CaptureRequest.HOT_PIXEL_MODE, chosen)
    }

    private fun applyOisMode(
        extender: Camera2Interop.Extender<*>,
        characteristics: CameraCharacteristics
    ) {
        val modes = characteristics.get(
            CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: return
        // Optical image stabilization (sensor-level). Improves sharpness
        // especially at tele focal lengths and in low light.
        if (modes.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
            setOptionSafe(extender, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }
    }

    private fun applyDistortionCorrection(
        extender: Camera2Interop.Extender<*>,
        characteristics: CameraCharacteristics
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        // Distortion correction (API 28+). Useful on ultra-wide lenses
        // (uncorrected barrel distortion is severe there).
        //
        // We intentionally do NOT push HIGH_QUALITY. The Xiaomi/MTK HAL
        // rejects a HIGH_QUALITY distortion-correction CaptureRequest on
        // many physical sub-cameras mid-session with
        // "bad pFeatureSettingQuery" in the mtkcam-capturesession log and
        // concurrently emits "getMipiError:64" on the sensor/CameraDevice
        // pair. Capping the request to FAST mode (when advertised) keeps
        // distortion correction on without triggering the feature-pipeline
        // reject that interrupts the preview surface. If the lens doesn't
        // advertise FAST at all we let the HAL fall back to its default,
        // which is OFF on most Xiaomi UW lenses.
        try {
            val modes = characteristics.get(
                CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES) ?: return
            val chosen = when {
                modes.contains(CaptureRequest.DISTORTION_CORRECTION_MODE_FAST) ->
                    CaptureRequest.DISTORTION_CORRECTION_MODE_FAST
                modes.contains(CaptureRequest.DISTORTION_CORRECTION_MODE_OFF) ->
                    CaptureRequest.DISTORTION_CORRECTION_MODE_OFF
                else -> return
            }
            setOptionSafe(extender, CaptureRequest.DISTORTION_CORRECTION_MODE, chosen)
        } catch (_: Throwable) {
            // Key not present on this device/API — safe to ignore.
        }
    }

    /**
     * setCaptureRequestOption is invariant on the builder's type parameter; on a
     * star-projected Extender the type system can't prove the assignment. We cast
     * once here rather than duplicating every call site.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <V> setOptionSafe(
        extender: Camera2Interop.Extender<*>,
        key: CaptureRequest.Key<V>,
        value: V
    ) {
        try {
            (extender as Camera2Interop.Extender<Any>).setCaptureRequestOption(
                key as CaptureRequest.Key<Any>, value as Any)
        } catch (e: Exception) {
            Log.w(TAG, "setCaptureRequestOption(${key.name}) failed", e)
        }
    }

    /**
     * Binds the default front or back camera (fallback when no multi-camera setup).
     */
    suspend fun bindDefaultCamera(
        cameraProvider: ProcessCameraProvider,
        surfaceProvider: Preview.SurfaceProvider,
        imageCapture: ImageCapture,
        isFrontCamera: Boolean,
        flashMode: Int = 0
    ): Camera? {
        val rotation = context.displayRotation

        val preview = Preview.Builder()
            .build()
            .apply { setSurfaceProvider(surfaceProvider) }

        val updatedImageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(rotation)
            .apply {
                when (flashMode) {
                    0 -> setFlashMode(ImageCapture.FLASH_MODE_AUTO)
                    1 -> setFlashMode(ImageCapture.FLASH_MODE_ON)
                    else -> setFlashMode(ImageCapture.FLASH_MODE_OFF)
                }
            }
            .build()

        val selector = if (isFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        // Same unbindAll strategy as bindPreview: selective unbind is
        // asynchronous on Xiaomi/MTK HALs and causes "No supported surface
        // combination" because old surfaces linger during the new bind.
        // unbindAll guarantees a clean slate, at the cost of a brief black
        // flash on switch.
        val previousPreview = currentPreview
        val previousImageCapture = currentImageCapture

        // Retry with increasing delays — same rationale as bindPreview:
        // on Xiaomi/MTK the V4L2 device nodes need time to fully release.
        val retryDelays = RETRY_DELAYS_MS
        Log.i("LensSwitch", "bindDefault START | isFront=$isFrontCamera")

        for ((index, delayMs) in retryDelays.withIndex()) {
            try {
                cameraProvider.unbindAll()
                delay(delayMs)
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    updatedImageCapture
                )
                currentPreview = preview
                currentImageCapture = updatedImageCapture
                // DEFAULT_*_CAMERA doesn't correspond to a known logical id;
                // keep currentLogicalCameraId as null so the recovery path in
                // bindPreview also resolves to DEFAULT_*_CAMERA if the next
                // call fails.
                currentLogicalCameraId = null
                currentIsFrontCamera = isFrontCamera
                Log.i("LensSwitch", "bindDefault OK | attempt=${index + 1}/${retryDelays.size} delay=${delayMs}ms")
                return camera
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "bindDefaultCamera attempt ${index + 1}/${retryDelays.size} " +
                        "failed (delay=${delayMs}ms, isFront=$isFrontCamera)",
                    e
                )
            }
        }

        // All retries exhausted. Attempt recovery.
        Log.e(TAG, "All bindDefaultCamera attempts failed (isFront=$isFrontCamera)")

        if (previousPreview != null && previousImageCapture != null) {
            try {
                cameraProvider.unbindAll()
                delay(80)
                val restored = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    recoverySelector(),
                    previousPreview,
                    previousImageCapture
                )
                Log.w(TAG, "Recovered default-camera bind; previous use cases restored")
                currentPreview = previousPreview
                currentImageCapture = previousImageCapture
                return restored
            } catch (e2: Exception) {
                Log.e(TAG, "Recovery bind failed; falling back to clean unbind", e2)
            }
        }
        release(cameraProvider)
        return null
    }

    /**
     * Checks if concurrent camera streaming (e.g. Primary + Tele simultaneously)
     * is supported on this device.
     */
    fun isConcurrentStreamingSupported(): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val concurrentIds = cameraManager.concurrentCameraIds
                concurrentIds.isNotEmpty()
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns the CameraCharacteristics for a given physical camera ID.
     */
    fun getCharacteristics(physicalCameraId: String): CameraCharacteristics? {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraManager.getCameraCharacteristics(physicalCameraId)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting characteristics for $physicalCameraId", e)
            null
        }
    }
}

/**
 * Returns the current display rotation in surface-rotation constants.
 * Modern API (30+) on Context.display; deprecated WindowManager.defaultDisplay
 * fallback for older devices (project minSdk is 24).
 */
private val Context.displayRotation: Int
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display?.rotation ?: 0
    } else {
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    }
