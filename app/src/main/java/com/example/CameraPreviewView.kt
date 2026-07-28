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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.color.CubeLut
import com.example.color.CubeLutParser
import com.example.color.LutPreviewView
import com.example.FilmPreset
import com.example.zoom.CaptureExtension
import com.example.zoom.LensCatalog
import com.example.zoom.LensRole
import com.example.zoom.PreviewSessionManager
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

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val deviceRotation = when (windowManager.defaultDisplay.rotation) {
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
    digitalZoomRatio: Float = 1.0f,
    exposure: Float,
    flashMode: Int,
    isFrontCamera: Boolean,
    activeExtension: CaptureExtension = CaptureExtension.NONE,
    isRawCapturing: Boolean = false,
    zoomEnabled: Boolean = true,
    temperature: Float = 0f,
    tint: Float = 0f,
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

    // OpenGL-backed preview surface. Renders camera frames through the
    // WB + 3D-LUT fragment shader (see LutPreviewRenderer). Replaces the
    // stock androidx.camera.view.PreviewView, which only supports flat
    // color overlays.
    val previewView = remember { LutPreviewView(context) }

    val imageCapture = remember {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetRotation(windowManager.defaultDisplay.rotation)
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
    // below would silently no-op the user's first lens tap.
    LaunchedEffect(
        selectedLensRole,
        isFrontCamera,
        activeExtension,
        isRawCapturing,
        catalogHolder.value
    ) {
        val cp = try { cameraProviderFuture.get() } catch (e: Exception) { null } ?: return@LaunchedEffect

        if (isRawCapturing) {
            // RELEASE the camera so RawCapture (Camera2) can take over
            // exclusively. Contention for the same camera device usually
            // leads to CAMERA_ERROR(3). Use PreviewSessionManager.release()
            // so it forgets its tracked use cases too — otherwise the
            // post-RAW recovery bind risks re-attaching stale use cases.
            previewManager.release(cp)
            camera = null
            return@LaunchedEffect
        }

        try {
            if (isFrontCamera) {
                val boundCam = previewManager.bindDefaultCamera(
                    cameraProvider = cp,
                    surfaceProvider = previewView.surfaceProvider,
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
                        surfaceProvider = previewView.surfaceProvider,
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
                        surfaceProvider = previewView.surfaceProvider,
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
        camera?.let { c -> try { c.cameraControl.enableTorch(flashMode == 1) } catch (e: Exception) {} }
        activeImageCapture.flashMode = when (flashMode) {
            0 -> ImageCapture.FLASH_MODE_AUTO
            1 -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
    }

    // Push white-balance + exposure into the GL renderer every time they
    // change. The renderer marshals the values onto the GL thread.
    LaunchedEffect(temperature, tint, exposure) {
        previewView.setWhiteBalance(temperature, tint, exposure)
    }

    // Push the active LUT into the GL renderer. Loads (and caches) the LUT
    // from assets on first use.
    LaunchedEffect(activeLut) {
        previewView.setLut(activeLut)
    }

    // Push per-preset film effect parameters to the GPU renderer whenever
    // the preset changes. These include film curve, contrast, saturation,
    // bloom/halation, chromatic fringing, and split toning values.
    LaunchedEffect(activePreset) {
        previewView.setFilmEffects(
            filmCurve = activePreset.defaultFilmCurve,
            contrast = activePreset.defaultContrast,
            saturation = activePreset.defaultSaturation,
            bloomStrength = activePreset.defaultBloom,
            fringing = activePreset.defaultFringing,
            shadowTintR = activePreset.shadowTintR,
            shadowTintG = activePreset.shadowTintG,
            shadowTintB = activePreset.shadowTintB,
            shadowTintStrength = activePreset.shadowTintStrength,
            highlightTintR = activePreset.highlightTintR,
            highlightTintG = activePreset.highlightTintG,
            highlightTintB = activePreset.highlightTintB,
            highlightTintStrength = activePreset.highlightTintStrength
        )
    }

    // Mirror the front-camera preview horizontally to match the stock
    // PreviewView behavior (selfie mirror).
    LaunchedEffect(isFrontCamera) {
        previewView.setFlipH(isFrontCamera)
    }

    // Zoom gesture — seed from current digitalZoomRatio
    val currentDigitalZoom by rememberUpdatedState(digitalZoomRatio)
    val currentOnZoomChanged by rememberUpdatedState(onZoomChanged)
    val currentOnZoomTick by rememberUpdatedState(onZoomTick)
    val currentZoomEnabled by rememberUpdatedState(zoomEnabled)

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize().pointerInput(zoomEnabled) {
            val heightPx = size.height.toFloat().coerceAtLeast(1f)
            awaitEachGesture {
                if (!currentZoomEnabled) return@awaitEachGesture
                awaitFirstDown(requireUnconsumed = false)
                // Seed from the current VM value so the gesture doesn't jump
                var runningZoom = currentDigitalZoom
                var lastTick = tickIndexOf(runningZoom)
                do {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val pointers = event.changes.filter { it.pressed }
                    if (pointers.isEmpty()) break

                    val newZoom = if (pointers.size >= 2) {
                        val pinchFactor = event.calculateZoom()
                        if (pinchFactor == 1.0f) null
                        else (runningZoom * pinchFactor).coerceIn(1.0f, 3.0f)
                    } else {
                        val dragPx = -event.calculatePan().y
                        if (dragPx == 0f) null
                        else {
                            val fractionalDrag = dragPx / heightPx
                            (runningZoom * kotlin.math.exp(fractionalDrag / 0.7f)).coerceIn(1.0f, 3.0f)
                        }
                    }

                    if (newZoom != null) {
                        runningZoom = newZoom
                        currentOnZoomChanged(newZoom)
                        val tick = tickIndexOf(newZoom)
                        if (tick != lastTick) {
                            lastTick = tick
                            currentOnZoomTick()
                        }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
    )
}

private fun tickIndexOf(zoom: Float): Int {
    if (zoom <= 0f) return 0
    return (kotlin.math.ln(zoom) / kotlin.math.ln(1.08f)).toInt()
}

/**
 * Tiny mutable holder for the cached LensCatalog result. We need an object
 * (not by-value state) so the rebind `LaunchedEffect` reads the same
 * instance the enumeration effect wrote to without re-running when Compose
 * state changes — Compose treats `mutableStateOf` updates as recompositions,
 * which we explicitly want to avoid on the rebind path.
 */
private class CatalogHolder {
    var value: LensCatalog.CatalogResult? = null
}

fun triggerImageCapture(
    context: Context,
    imageCapture: ImageCapture,
    executor: Executor,
    onCaptured: (File) -> Unit,
    onCaptureError: (ImageCaptureException) -> Unit
) {
    val directory = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES) ?: context.cacheDir
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val photoFile = File(directory, "RETRO_IMG_$timeStamp.jpg")
    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
    imageCapture.takePicture(outputOptions, executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) { onCaptured(photoFile) }
            override fun onError(exception: ImageCaptureException) { onCaptureError(exception) }
        })
}