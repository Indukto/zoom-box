@file:Suppress(
    "unused",
    "UnusedImport",
    "UnusedImports",
    "RedundantQualifierName",
    "RemoveRedundantQualifierName",
    "missingPermission",
    "MissingPermission",
    "RedundantSuppression"
)

package com.example

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import com.example.color.CubeLut
import com.example.color.CubeLutParser
import com.example.color.LutPreviewView
import com.example.color.RetroRenderParams
import com.example.FilmPreset
import com.example.zoom.CaptureExtension
import com.example.zoom.LensCatalog
import com.example.zoom.LensRole
import com.example.zoom.PreviewSessionManager
import com.example.zoom.ZoomBoxCalculator
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

fun captureWithCamera2(
    context: Context,
    targetLogicalId: String,
    targetPhysicalId: String,
    targetFocalLength: Int,
    flashMode: Int,
    onCaptured: (File) -> Unit,
    onError: (Exception) -> Unit
) {
    val directory = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES) ?: context.cacheDir
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val photoFile = File(directory, "RETRO_IMG_${timeStamp}_${targetFocalLength}mm.jpg")

    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val cameraThread = HandlerThread("Camera2Capture").apply { start() }
    val cameraHandler = Handler(cameraThread.looper)

    fun cleanup(reader: ImageReader? = null, device: CameraDevice? = null) {
        try { reader?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        cameraThread.quitSafely()
    }

    try {
        val characteristics = cameraManager.getCameraCharacteristics(targetLogicalId)

        val physicalChars = try {
            cameraManager.getCameraCharacteristics(targetPhysicalId)
        } catch (_: Exception) { characteristics }
        val sensorOrientation = physicalChars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        val configMap = physicalChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val outputSizes = configMap?.getOutputSizes(ImageFormat.JPEG)
        val size = outputSizes?.maxByOrNull { it.width * it.height }
        if (size == null) { cleanup(); onError(RuntimeException("No JPEG output size")); return }

        val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)

        val deviceRotation = when (context.displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val jpegOrientation = (sensorOrientation + deviceRotation) % 360

        cameraManager.openCamera(targetLogicalId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                try {
                    val sessionCallback = object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                            requestBuilder.addTarget(imageReader.surface)
                            requestBuilder.set(CaptureRequest.JPEG_QUALITY, 97.toByte())
                            requestBuilder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                            when (flashMode) {
                                0 -> requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                                1 -> requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                                else -> {
                                    requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                                    requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                }
                            }
                            val request = requestBuilder.build()

                            imageReader.setOnImageAvailableListener({ reader ->
                                try {
                                    val image = reader.acquireLatestImage()
                                    if (image != null) {
                                        val buffer = image.planes[0].buffer
                                        val bytes = ByteArray(buffer.remaining())
                                        buffer.get(bytes)
                                        FileOutputStream(photoFile).use { it.write(bytes) }
                                        image.close()
                                    }
                                } catch (e: Exception) {
                                    Log.e("CameraPreviewView", "Error reading Camera2 image", e)
                                } finally {
                                    cleanup(imageReader, camera)
                                    onCaptured(photoFile)
                                }
                            }, cameraHandler)

                            session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                                    Log.e("CameraPreviewView", "Camera2 capture failed: ${failure.reason}")
                                    cleanup(imageReader, camera)
                                    onError(RuntimeException("Capture failed"))
                                }
                            }, cameraHandler)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e("CameraPreviewView", "Camera2 session configure failed")
                            cleanup(imageReader, camera)
                            onError(RuntimeException("Session configure failed"))
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val outputConfig = OutputConfiguration(imageReader.surface)
                        outputConfig.setPhysicalCameraId(targetPhysicalId)
                        val executor = java.util.concurrent.Executor { command -> cameraHandler.post(command) }
                        val sessionConfig = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(outputConfig), executor, sessionCallback)
                        camera.createCaptureSession(sessionConfig)
                    } else {
                        // API < 28 fallback. The 3-arg createCaptureSession(...) was
                        // deprecated in CameraX 1.3 but SessionConfiguration requires
                        // API 28+; there is no equivalent on Android 7/8. The project's
                        // minSdk is 24, so we cannot route this branch through the
                        // modern API without a minSdk bump to 28.
                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(listOf(imageReader.surface), sessionCallback, cameraHandler)
                    }
                } catch (e: Exception) {
                    Log.e("CameraPreviewView", "Error creating Camera2 session", e)
                    cleanup(imageReader, camera)
                    onError(e)
                }
            }

            override fun onDisconnected(camera: CameraDevice) {
                Log.e("CameraPreviewView", "Camera2 disconnected")
                cleanup(imageReader, camera)
                onError(RuntimeException("Camera disconnected"))
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Log.e("CameraPreviewView", "Camera2 error: $error")
                cleanup(imageReader, camera)
                onError(RuntimeException("Camera error: $error"))
            }
        }, cameraHandler)
    } catch (e: Exception) {
        Log.e("CameraPreviewView", "Error opening Camera2 device", e)
        cleanup()
        onError(e)
    }
}

@Composable
fun CameraPreviewView(
    modifier: Modifier = Modifier,
    selectedLensRole: LensRole = LensRole.PRIMARY,
    digitalZoomRatioFlow: StateFlow<Float>,
    exposure: Float,
    flashMode: Int,
    isFrontCamera: Boolean,
    activeExtension: CaptureExtension = CaptureExtension.NONE,
    isRawCapturing: Boolean = false,
    previewPaused: Boolean = false,
    zoomEnabled: Boolean = true,
    renderParams: RetroRenderParams = RetroRenderParams(),
    activeLut: CubeLut? = null,
    activePreset: FilmPreset = FilmPreset.WARM_PORTRAIT,
    onZoomChanged: (Float) -> Unit,
    onZoomTick: () -> Unit = {},
    onAvailableFocalLengths: (List<Float>) -> Unit,
    imageCaptureProvider: (ImageCapture) -> Unit,
    onLensCatalogReady: ((LensCatalog.CatalogResult) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    // Filtered styles use the OpenGL preview so their LUT/effects are rendered
    // live. NORMAL deliberately uses CameraX's stock PreviewView instead of
    // the custom GL view: the latter owns an extra EGL context whose buffer
    // can be resized during the first edge-to-edge Compose layout pass (the
    // Pixel logcat shows that as a BLASTBufferQueue size mismatch and an
    // abandoned consumer). Normal has no GPU effects to justify that extra
    // surface, so keeping it on the stable CameraX path avoids the startup
    // race entirely. Both views are TextureView-based (in-window), which is
    // also what lets the control bubble's backdrop blur sample the video.
    val lutPreviewView = remember { LutPreviewView(context) }
    val normalPreviewView = remember {
        PreviewView(context).apply {
            // Force TextureView rather than PreviewView's default SurfaceView.
            // The affected Pixel logcat showed a SurfaceView BLAST buffer-size
            // mismatch during startup; compatible mode avoids that separate
            // SurfaceView buffer while preserving the normal camera preview.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val useFilteredPreview = activePreset != FilmPreset.NORMAL
    val activePreviewView = if (useFilteredPreview) lutPreviewView else normalPreviewView
    val activeSurfaceProvider = if (useFilteredPreview) {
        lutPreviewView.surfaceProvider
    } else {
        normalPreviewView.surfaceProvider
    }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetRotation(context.displayRotation)
            .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
            .build()
    }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var activeImageCapture by remember { mutableStateOf(imageCapture) }

    // Cache the lens catalog across recompositions. LensCatalog.enumerate()
    // blocks while reading Camera2 characteristics for every camera on the
    // device (50-200ms on most phones) — running it inside the rebind
    // LaunchedEffect meant every lens tap paid that cost on the main
    // thread, contributing to the visible black-flash gap. We do the
    // enumeration exactly once here and stash the result in a holder so
    // the rebind path can read it synchronously without triggering the
    // SystemCamera stall again.
    val catalogHolder = remember { CatalogHolder() }

    // Hoist PreviewSessionManager to a `remember`-ed instance. Previous
    // versions constructed one inside the LaunchedEffect body, which meant
    // a fresh manager (and a nulled-out currentPreview / currentImageCapture
    // / currentLogicalCameraId) every time the effect re-keyed. That broke
    // the in-place-replace + recovery strategy entirely — the recovery
    // branch could never find `previousPreview` because each effect had a
    // brand-new manager. Keeping the instance alive across all composition
    // passes lets the bind path see the use cases we previously attached
    // and properly rollback / recover on failure.
    val previewManager = remember { PreviewSessionManager(context, lifecycleOwner) }

    LaunchedEffect(activeImageCapture) { imageCaptureProvider(activeImageCapture) }

    // ── Standby recovery: black viewfinder after screen-off / background ──
    // After standby the activity goes STOP → START and the app used to rely
    // entirely on CameraX's *implicit* camera reopen. That reopen fails
    // intermittently (same flaky-HAL family the manual bind path already
    // works around with unbindAll + retry delays), and nothing ever re-ran
    // the bind effect below — its keys (lens, front, extension, RAW,
    // preset, catalog) don't change across standby — so the viewfinder
    // stayed black until a lens switch forced a fresh bind. That is exactly
    // the reported symptom and its workaround.
    //
    // Fix: make the transition explicit. On STOP we proactively
    // PreviewSessionManager.release() (its own doc comment already names
    // lifecycle STOP as a caller — it just was never wired) and drop the
    // stale Camera handle so the zoom/exposure/flash effects stop driving a
    // closed camera. On the next START we bump resumeRebindTick, which is
    // part of the bind effect's keys, so resume performs the exact same
    // proven unbindAll → bindToLifecycle path (with MTK retry delays and
    // the recovery branch) as a lens switch.
    //
    // The sawStopSinceBind guard keeps cold start cheap: the first ON_START
    // arrives with no preceding STOP, so it no-ops instead of paying a
    // redundant unbindAll + rebind flash on every launch.
    var resumeRebindTick by remember { mutableStateOf(0) }
    var sawStopSinceBind by remember { mutableStateOf(false) }
    // Last provider instance seen by the bind effect. Used for the
    // non-blocking ON_STOP release below: cameraProviderFuture.get() would
    // block the main thread if the provider weren't ready yet, while this
    // ref is only ever non-null after a bind pass actually ran — i.e.
    // exactly when there can be something bound worth releasing.
    var boundProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    sawStopSinceBind = true
                    camera = null
                    try {
                        boundProvider?.let { previewManager.release(it) }
                    } catch (_: Exception) {
                    }
                }
                Lifecycle.Event.ON_START -> {
                    if (sawStopSinceBind) {
                        sawStopSinceBind = false
                        resumeRebindTick++
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            try {
                lifecycleOwner.lifecycle.removeObserver(observer)
            } catch (_: Exception) {
            }
        }
    }

    // Defensive teardown: while CameraUi keeps CameraActiveScreen
    // perpetually mounted, the preview session itself is released whenever
    // a fullscreen opaque overlay covers it (previewPaused) or the activity
    // stops. If anything ever re-introduces a sibling-swap (a Navigation
    // compose graph, a quick-settings tab, a capture-review screen that
    // swaps CameraActiveScreen out), make sure
    // PreviewSessionManager.release() runs synchronously with the
    // composable's leave-composition event so we don't leave an orphaned
    // ProcessCameraProvider binding on the HAL — which is exactly what
    // produced the original bug's `evicting conflicting client` logcat.
    //
    // CameraX binds against the activity LifecycleOwner, so when the
    // activity is RESUMED (settings nav case) the implicit unbind is the
    //   weak/half-state path. Calling release() forces a real `unbindAll()`
    //   and clears our cached use cases, so the next remount sees the
    //   provider in a clean state.
    //
    // SCOPE EXPLANATION: onDispose runs when this composable LEAVES the
    // composition, not on every recomposition. While CameraUi keeps
    // CameraActiveScreen perpetually mounted, the only realistic leave
    // events are activity destruction or a future refactor that re-swaps
    // screens — both of which benefit from this explicit cleanup.
    DisposableEffect(Unit) {
        onDispose {
            try {
                val cp = cameraProviderFuture.get()
                previewManager.release(cp)
            } catch (e: Exception) {
                Log.w(
                    "CameraPreviewView",
                    "onDispose: previewManager.release failed (camera provider not yet available)",
                    e
                )
            }
        }
    }

    // Enumerate cameras once. The catalog result is also surfaced to the
    // ViewModel via onLensCatalogReady so it can drive the focal-bubble
    // row and the auto-correct-initial-lens logic.
    LaunchedEffect(Unit) {
        if (catalogHolder.value == null) {
            val result = withContext(Dispatchers.IO) {
                LensCatalog(context).enumerate()
            }
            catalogHolder.value = result
            onAvailableFocalLengths(result.allLenses.map { it.equivFocalMm }.sorted())
            onLensCatalogReady?.invoke(result)
        }
    }

    // Bind camera — re-keys on the inputs that actually require a new
    // CameraX session. We keep catalogHolder.value in the keys so a
    // rebind that fires BEFORE the enumeration completes will re-fire
    // once the catalog lands; otherwise the early `?: return@LaunchedEffect`
    // below would silently no-op the user's first lens tap. resumeRebindTick
    // is bumped by the standby lifecycle observer above so returning from
    // screen-off / background re-binds through this same path.
    // previewPaused releases the session while a fullscreen opaque overlay
    // (settings, photo viewer) covers the viewfinder and re-binds through
    // this same path when the overlay closes.
    LaunchedEffect(
        selectedLensRole,
        isFrontCamera,
        activeExtension,
        isRawCapturing,
        previewPaused,
        useFilteredPreview,
        catalogHolder.value,
        resumeRebindTick
    ) {
        val cp = try { cameraProviderFuture.get() } catch (e: Exception) { null } ?: return@LaunchedEffect
        // Remember the provider for the non-blocking ON_STOP release (see
        // the standby observer above). Not part of the effect keys — it's
        // the process singleton, so this never triggers a restart loop.
        if (boundProvider !== cp) boundProvider = cp

        if (isRawCapturing || previewPaused) {
            // RELEASE the camera so RawCapture (Camera2) can take over
            // exclusively. Contention for the same camera device usually
            // leads to CAMERA_ERROR(3). Use PreviewSessionManager.release()
            // so it forgets its tracked use cases too — otherwise the
            // post-RAW recovery bind risks re-attaching stale use cases.
            // previewPaused reuses the same release: the live preview is
            // invisible behind a fullscreen opaque overlay, so keeping the
            // HAL stream + GL thread + TextureView uploads running only
            // burns GPU and stutters the overlay UI. Clearing the flag
            // re-keys this effect and re-binds through the normal path.
            previewManager.release(cp)
            camera = null
            return@LaunchedEffect
        }

        try {
            if (isFrontCamera) {
                val boundCam = previewManager.bindDefaultCamera(
                    cameraProvider = cp,
                    surfaceProvider = activeSurfaceProvider,
                    imageCapture = imageCapture,
                    isFrontCamera = true,
                    flashMode = flashMode
                )
                camera = boundCam
                activeImageCapture = imageCapture
            } else {
                val catalog = catalogHolder.value ?: return@LaunchedEffect
                val targetProfile = when (selectedLensRole) {
                    LensRole.ULTRA_WIDE -> catalog.ultraWide
                    LensRole.PRIMARY -> catalog.primary
                    LensRole.TELE -> catalog.tele
                }

                val bound = if (targetProfile != null) {
                    previewManager.bindPreview(
                        cameraProvider = cp,
                        logicalCameraId = targetProfile.logicalCameraId,
                        physicalCameraId = targetProfile.physicalCameraId,
                        surfaceProvider = activeSurfaceProvider,
                        flashMode = flashMode,
                        extension = activeExtension
                    )
                } else null

                if (bound == null) {
                    // No LensProfile for the requested role, OR the bind
                    // failed and recovery didn't apply (see
                    // PreviewSessionManager). Fall back to
                    // DEFAULT_BACK_CAMERA so the viewfinder is never
                    // empty.
                    val boundCam = previewManager.bindDefaultCamera(
                        cameraProvider = cp,
                        surfaceProvider = activeSurfaceProvider,
                        imageCapture = imageCapture,
                        isFrontCamera = false,
                        flashMode = flashMode
                    )
                    camera = boundCam
                    activeImageCapture = imageCapture
                } else {
                    camera = bound.camera
                    activeImageCapture = bound.imageCapture
                }
            }
        } catch (e: CancellationException) {
            // A route change cancels this effect while CameraX is still
            // releasing the previous surface. Never turn that cancellation
            // into a successful-looking bind continuation.
            throw e
        } catch (e: Exception) {
            Log.e("CameraPreviewView", "Failed to bind camera lifecycle", e)
        }
    }

    // Keep preview at 1.0x zoom
    LaunchedEffect(camera) {
        camera?.let { c ->
            try { c.cameraControl.setZoomRatio(1.0f) }
            catch (e: Exception) { Log.e("CameraPreviewView", "Error resetting zoom", e) }
        }
    }

    LaunchedEffect(exposure, camera) {
        camera?.let { c ->
            val es = c.cameraInfo.exposureState
            if (es.isExposureCompensationSupported) {
                val min = es.exposureCompensationRange.lower
                val max = es.exposureCompensationRange.upper
                val ratio = (exposure + 3f) / 6f
                val index = (min + ratio * (max - min)).toInt().coerceIn(min, max)
                try { c.cameraControl.setExposureCompensationIndex(index) }
                catch (e: Exception) { Log.e("CameraPreviewView", "Error adjusting exposure", e) }
            }
        }
    }

    LaunchedEffect(flashMode, camera) {
        // Still-photo flash only: never leave the torch (continuous LED) on.
        // FLASH_MODE_ON fires the LED at capture time; enableTorch(true)
        // would keep it lit like a flashlight the whole time mode ON is set.
        camera?.let { c -> try { c.cameraControl.enableTorch(false) } catch (e: Exception) {} }
        activeImageCapture.flashMode = when (flashMode) {
            0 -> ImageCapture.FLASH_MODE_AUTO
            1 -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
    }

    // Push one immutable render-parameter snapshot (preset look + user WB /
    // exposure) into the GL renderer. The snapshot is built by the caller
    // from the same CameraProfileRegistry the capture pipeline uses, so the
    // live viewfinder and the saved JPEG always agree — including for JSON
    // look profiles.
    LaunchedEffect(renderParams) {
        lutPreviewView.setRenderParams(renderParams)
    }

    // Push the active LUT into the GL renderer. Loads (and caches) the LUT
    // from assets on first use.
    LaunchedEffect(activeLut) {
        lutPreviewView.setLut(activeLut)
    }

    // Mirror the front-camera preview horizontally to match the stock
    // PreviewView behavior (selfie mirror).
    LaunchedEffect(isFrontCamera) {
        lutPreviewView.setFlipH(isFrontCamera)
    }

    // Zoom gesture — the ratio is collected HERE (not by the caller) so
    // per-gesture StateFlow writes recompose only this preview wrapper, not
    // the whole camera screen. `rememberUpdatedState` keeps the gesture
    // coroutine reading the latest value without restarting pointerInput.
    val digitalZoomRatio by digitalZoomRatioFlow.collectAsState()
    val currentDigitalZoom by rememberUpdatedState(digitalZoomRatio)
    val currentOnZoomChanged by rememberUpdatedState(onZoomChanged)
    val currentOnZoomTick by rememberUpdatedState(onZoomTick)
    val currentZoomEnabled by rememberUpdatedState(zoomEnabled)

    // The native child must change together with the CameraX surface provider.
    // key() disposes the old preview child before the manager binds the new
    // provider, preventing an abandoned consumer during preset changes.
    key(useFilteredPreview) {
        AndroidView(
            factory = { activePreviewView },
            modifier = modifier.fillMaxSize().pointerInput(zoomEnabled) {
            val heightPx = size.height.toFloat().coerceAtLeast(1f)
            awaitEachGesture {
                if (!currentZoomEnabled) return@awaitEachGesture
                awaitFirstDown(requireUnconsumed = false)
                // Seed from the current VM value so the gesture doesn't jump
                var runningZoom = currentDigitalZoom
                var lastTick = tickIndexOf(runningZoom)
                // Coalesce ViewModel writes: pointer events arrive faster than
                // the overlay spring can settle, and retargeting the spring +
                // rewriting StateFlow on every sub-pixel move keeps the UI at
                // a constant 60fps recompose. Deltas under EPS change the box
                // by <1px (d(boxScale)/d(zoom) = -1/zoom^2; 0.002 * ~400px VF
                // width ~= 0.8px), so holding them back is visually identical
                // while roughly halving StateFlow churn. The exact final value
                // is always flushed when the gesture ends below.
                var lastSentZoom = runningZoom
                do {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val pointers = event.changes.filter { it.pressed }
                    if (pointers.isEmpty()) break

                    val newZoom = if (pointers.size >= 2) {
                        val pinchFactor = event.calculateZoom()
                        if (pinchFactor == 1.0f) null
                        else (runningZoom * pinchFactor).coerceIn(
                            ZoomBoxCalculator.MIN_ZOOM_RATIO,
                            ZoomBoxCalculator.MAX_ZOOM_RATIO
                        )
                    } else {
                        val dragPx = -event.calculatePan().y
                        if (dragPx == 0f) null
                        else {
                            val fractionalDrag = dragPx / heightPx
                            (runningZoom * kotlin.math.exp(fractionalDrag / 0.7f)).coerceIn(
                                ZoomBoxCalculator.MIN_ZOOM_RATIO,
                                ZoomBoxCalculator.MAX_ZOOM_RATIO
                            )
                        }
                    }

                    if (newZoom != null) {
                        runningZoom = newZoom
                        if (kotlin.math.abs(newZoom - lastSentZoom) > 0.002f) {
                            lastSentZoom = newZoom
                            currentOnZoomChanged(newZoom)
                        }
                        val tick = tickIndexOf(newZoom)
                        if (tick != lastTick) {
                            lastTick = tick
                            currentOnZoomTick()
                        }
                    }
                } while (event.changes.any { it.pressed })
                // Flush the exact resting value so the committed zoom always
                // matches the gesture, even when the tail sat within EPS.
                if (runningZoom != lastSentZoom) {
                    currentOnZoomChanged(runningZoom)
                }
            }
            }
        )
    }
}

private fun tickIndexOf(zoom: Float): Int {
    if (zoom <= 0f) return 0
    return (kotlin.math.ln(zoom) / kotlin.math.ln(1.08f)).toInt()
}

/**
 * Small Compose-aware holder for the cached LensCatalog result. The value must
 * be observable: the initial bind effect can run before camera enumeration
 * finishes, and updating this holder must re-run that effect. LUT-backed
 * presets happen to trigger another recomposition when their LUT finishes
 * loading; NORMAL returns null, so a plain mutable field would leave NORMAL
 * permanently unbound with a black viewfinder.
 */
private class CatalogHolder {
    var value: LensCatalog.CatalogResult? by mutableStateOf(null)
}

fun triggerImageCapture(
    context: Context,
    imageCapture: ImageCapture,
    executor: Executor,
    targetRotation: Int,
    onCaptured: (File) -> Unit,
    onCaptureError: (ImageCaptureException) -> Unit
) {
    val directory = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES) ?: context.cacheDir
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val photoFile = File(directory, "RETRO_IMG_$timeStamp.jpg")
    // The ImageCapture's target rotation was set at bind time (possibly long
    // ago), so refresh it to the CURRENT physical rotation right before
    // capturing. The rotation comes from an OrientationEventListener (the
    // activity is locked to portrait, where Display.getRotation() keeps
    // reporting ROTATION_0) — this is what makes a photo taken with the
    // phone held sideways come out landscape instead of always portrait.
    // CameraX handles the sensor-side rotation math itself once the target
    // rotation is correct.
    imageCapture.targetRotation = targetRotation
    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
    imageCapture.takePicture(outputOptions, executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) { onCaptured(photoFile) }
            override fun onError(exception: ImageCaptureException) { onCaptureError(exception) }
        })
}

/**
 * Returns the current display rotation in surface-rotation constants.
 *
 * `WindowManager.getDefaultDisplay()` was deprecated in API 30 in favour of
 * the per-Context `Display` (`Context.getDisplay()`); we use the modern
 * accessor where available and fall back to the deprecated one on older
 * devices (project minSdk is 24). The fallback warning is scoped to the
 * `else` branch and suppressed so it does not surface in the build log.
 */
private val Context.displayRotation: Int
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display.rotation
    } else {
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    }