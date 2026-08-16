@file:Suppress("unused", "UnusedImport", "UnusedImports")

package com.example.zoom

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures a single RAW bayer frame and writes it to a DNG file.
 *
 * A matching JPEG is captured in the same still request so the caller can run
 * it through the app's normal retro-filter / crop pipeline: a DNG can't carry
 * a LUT or tone curve, so the JPEG is what the gallery displays, while the DNG
 * remains the unprocessed "Pro" output.
 *
 *   1. Open the logical camera and configure a RAW_SENSOR ImageReader plus a
 *      small YUV preview reader (for 3A convergence) and a JPEG reader.
 *   2. For multi-camera devices, target the requested physical lens via
 *      `OutputConfiguration.setPhysicalCameraId` (API 28+).
 *   3. Run a repeating preview until AE/AWB converge, then submit a single
 *      TEMPLATE_STILL_CAPTURE that targets both the RAW and JPEG surfaces.
 *   4. Pair the returned RAW Image + TotalCaptureResult and emit a DNG via
 *      [DngCreator], oriented to the device rotation. The JPEG is written
 *      straight to disk with its EXIF orientation baked in by the HAL.
 *
 * The `.dng`/`.jpg` pair is saved to `Pictures/` in the app's external files
 * dir, mirroring the storage convention of the JPEG pipeline.
 */
object RawCapture {

    private const val TAG = "RawCapture"

    /** Safety cap on how long we wait for 3A to converge before capturing. */
    private const val CONVERGE_TIMEOUT_MS = 2000L

    /**
     * @param context Activity/application context
     * @param logicalCameraId The logical Camera2 id (e.g. "0")
     * @param physicalCameraId The target physical lens id (may equal logical
     *        if the device has no logical multi-camera)
     * @param focalLengthMm Native focal length, used to label the filename
     * @param flashMode 0 = auto, 1 = on, 2 = off
     * @param targetRotation Current physical device rotation (Surface.ROTATION_*),
     *     sensor-tracked by the caller — Display.getRotation() stays ROTATION_0
     *     while the activity is locked to portrait, so it can't be used here
     * @param onCaptured Invoked with the written .dng file and its companion
     *     .jpg (the latter is null when the device offered no JPEG output size)
     * @param onError Invoked on any failure (capability, open, session, capture)
     */
    @SuppressLint("MissingPermission")
    fun captureDng(
        context: Context,
        logicalCameraId: String,
        physicalCameraId: String,
        focalLengthMm: Int,
        flashMode: Int,
        targetRotation: Int,
        onCaptured: (dngFile: File, jpegFile: File?) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val directory = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
            ?: context.cacheDir
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dngFile = File(directory, "RETRO_RAW_${timeStamp}_${focalLengthMm}mm.dng")
        val jpegFile = File(directory, "RETRO_JPEG_${timeStamp}_${focalLengthMm}mm.jpg")

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraThread = HandlerThread("RawCapture").apply { start() }
        val cameraHandler = Handler(cameraThread.looper)

        var imageReader: ImageReader? = null
        var previewReader: ImageReader? = null
        var jpegReader: ImageReader? = null
        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)

        fun cleanup() {
            try { imageReader?.close() } catch (_: Exception) {}
            try { previewReader?.close() } catch (_: Exception) {}
            try { jpegReader?.close() } catch (_: Exception) {}
            try {
                // If the session is still active, closing the device might trigger
                // HAL errors. Closing the session first is safer, but on some
                // devices stopRepeating() fails if no preview was running.
                session?.close()
                camera?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Cleanup exception (ignorable): ${e.message}")
            }
            cameraThread.quitSafely()
        }

        fun failOnce(error: Exception) {
            if (finished.compareAndSet(false, true)) {
                cleanup()
                onError(error)
            }
        }

        fun succeedOnce(dng: File, jpeg: File?) {
            if (finished.compareAndSet(false, true)) {
                cleanup()
                onCaptured(dng, jpeg)
            }
        }

        try {
            if (!isRawSupported(cameraManager, logicalCameraId, physicalCameraId)) {
                failOnce(IllegalStateException("RAW not supported on this lens"))
                return
            }

            val characteristics = cameraManager.getCameraCharacteristics(logicalCameraId)
            val physicalChars = try {
                cameraManager.getCameraCharacteristics(physicalCameraId)
            } catch (_: Exception) { characteristics }

            val configMap = physicalChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val rawSizes = configMap?.getOutputSizes(ImageFormat.RAW_SENSOR)
            val size = rawSizes?.maxByOrNull { it.width * it.height }
            if (size == null) {
                failOnce(IllegalStateException("No RAW_SENSOR output size"))
                return
            }

            imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 2)

            // A small YUV preview target lets the HAL run a repeating 3A
            // request before the still frame. Without it, the very first
            // TEMPLATE_STILL_CAPTURE fires before AE/AWB have converged and
            // the resulting image is underexposed ("dark"). The RAW reader
            // can't double as this target — its tiny maxImages buffer would
            // fill instantly and block the still capture.
            val previewSize = configMap?.getOutputSizes(ImageFormat.YUV_420_888)
                ?.minByOrNull { it.width * it.height }
                ?: Size(640, 480)
            previewReader = ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 2)

            // Companion JPEG so the caller can apply the retro filter / crop
            // and hand the gallery a displayable, correctly-oriented photo.
            val jpegSize = configMap?.getOutputSizes(ImageFormat.JPEG)
                ?.maxByOrNull { it.width * it.height }
            jpegReader = jpegSize?.let {
                ImageReader.newInstance(it.width, it.height, ImageFormat.JPEG, 2)
            }

            val sensorOrientation = physicalChars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val deviceRotation = when (targetRotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            // JPEG-standard recipe: (sensorOrientation - deviceRotation) — the
            // same value CameraX's getSensorRotationDegrees() computes — gives
            // the clockwise rotation that makes the image upright. It is used
            // both for CaptureRequest.JPEG_ORIENTATION (degrees) and, mapped
            // to EXIF, for the DNG orientation tag.
            val dngCwRotation = (sensorOrientation - deviceRotation + 360) % 360
            // DngCreator.setOrientation() expects EXIF orientation values, not
            // raw degrees: 0° → 1, 90° → 6, 180° → 3, 270° → 8.
            val dngExifOrientation = when (dngCwRotation) {
                90 -> 6
                180 -> 3
                270 -> 8
                else -> 1
            }

            // Pair the RAW Image, TotalCaptureResult, and companion JPEG before
            // finishing. Any of the three may arrive first; all are required.
            var pendingImage: Image? = null
            var pendingResult: TotalCaptureResult? = null
            var pendingJpeg: File? = null
            var dngDone = false
            var jpegDone = (jpegReader == null)

            fun tryFinish() {
                val img = pendingImage ?: return
                val res = pendingResult ?: return
                if (dngDone || !jpegDone) return
                Log.d(TAG, "RAW image, metadata and JPEG all present. Writing DNG...")
                try {
                    val dng = DngCreator(physicalChars, res)
                    dng.setDescription("ZoomBox Camera RAW capture")
                    dng.setOrientation(dngExifOrientation)
                    FileOutputStream(dngFile).use { out ->
                        dng.writeImage(out, img)
                    }
                    dng.close()
                    img.close()
                    dngDone = true
                    succeedOnce(dngFile, pendingJpeg)
                } catch (e: Exception) {
                    Log.e(TAG, "DNG write failed", e)
                    try { img.close() } catch (_: Exception) {}
                    failOnce(e)
                }
            }

            imageReader.setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    Log.d(TAG, "RAW image arrived: ${image.width}x${image.height}")
                    pendingImage = image
                    tryFinish()
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading RAW image", e)
                    failOnce(e)
                }
            }, cameraHandler)

            jpegReader?.setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        try {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            FileOutputStream(jpegFile).use { out -> out.write(bytes) }
                            pendingJpeg = jpegFile
                            Log.d(TAG, "JPEG companion saved: ${jpegFile.name}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error writing JPEG companion", e)
                        } finally {
                            image.close()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading JPEG companion", e)
                }
                jpegDone = true
                tryFinish()
            }, cameraHandler)

            cameraManager.openCamera(logicalCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    try {
                        val sessionCallback = object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                runPrecaptureThenStill(
                                    session = s,
                                    camera = device,
                                    characteristics = characteristics,
                                    readerSurface = imageReader.surface,
                                    previewSurface = previewReader.surface,
                                    previewReader = previewReader,
                                    jpegSurface = jpegReader?.surface,
                                    jpegOrientation = dngCwRotation,
                                    flashMode = flashMode,
                                    handler = cameraHandler,
                                    onStillResult = { result ->
                                        Log.d(TAG, "RAW TotalCaptureResult arrived")
                                        pendingResult = result
                                        tryFinish()
                                    },
                                    onFailure = { reason ->
                                        failOnce(RuntimeException("RAW capture failed: $reason"))
                                    }
                                )
                            }

                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                failOnce(RuntimeException("RAW session configure failed"))
                            }
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                            logicalCameraId != physicalCameraId
                        ) {
                            val outputConfig = OutputConfiguration(imageReader.surface)
                            outputConfig.setPhysicalCameraId(physicalCameraId)
                            // RAW targets the requested physical lens; the
                            // preview/3A and JPEG surfaces stay on the logical camera.
                            val outputs = mutableListOf(outputConfig)
                            outputs.add(OutputConfiguration(previewReader.surface))
                            jpegReader?.let { outputs.add(OutputConfiguration(it.surface)) }
                            val executor = java.util.concurrent.Executor { cmd -> cameraHandler.post(cmd) }
                            val sessionConfig = SessionConfiguration(
                                SessionConfiguration.SESSION_REGULAR,
                                outputs,
                                executor,
                                sessionCallback
                            )
                            device.createCaptureSession(sessionConfig)
                        } else {
                            // API < 28 fallback. The 3-arg createCaptureSession(...) was
                            // deprecated in CameraX 1.3 but SessionConfiguration requires
                            // API 28+; there is no equivalent on Android 7/8. The project's
                            // minSdk is 24, so we cannot route this branch through the
                            // modern API without a minSdk bump to 28.
                            @Suppress("DEPRECATION")
                            device.createCaptureSession(
                                listOfNotNull(
                                    imageReader.surface,
                                    previewReader.surface,
                                    jpegReader?.surface
                                ),
                                sessionCallback,
                                cameraHandler
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "RAW session setup failed", e)
                        failOnce(e)
                    }
                }

                override fun onDisconnected(d: CameraDevice) {
                    failOnce(RuntimeException("Camera disconnected"))
                }

                override fun onError(d: CameraDevice, error: Int) {
                    failOnce(RuntimeException("Camera open error: $error"))
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "RAW capture threw", e)
            failOnce(e)
        }
    }

    /**
     * Reports whether the lens can actually emit RAW frames. Capability check
     * is the cheap path; the real gate is whether a RAW_SENSOR output size
     * exists for the *physical* camera when the device is a logical multi-cam.
     */
    private fun isRawSupported(
        cameraManager: CameraManager,
        logicalCameraId: String,
        physicalCameraId: String
    ): Boolean {
        return try {
            val physicalChars = cameraManager.getCameraCharacteristics(physicalCameraId)
            val logicalChars = cameraManager.getCameraCharacteristics(logicalCameraId)

            val physicalCapabilities = physicalChars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val logicalCapabilities = logicalChars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()

            // At least one of physical or logical must advertise RAW capability.
            val hasRawCapability = physicalCapabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) ||
                    logicalCapabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
            if (!hasRawCapability) return false

            // Check for RAW_SENSOR output sizes: prefer physical camera, fall back to logical.
            val candidateChars = if (physicalChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.RAW_SENSOR)
                    ?.isNotEmpty() == true
            ) physicalChars else logicalChars

            val configMap = candidateChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return false
            val sizes = configMap.getOutputSizes(ImageFormat.RAW_SENSOR)
            !sizes.isNullOrEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "isRawSupported probe failed", e)
            false
        }
    }

    /**
     * Converges 3A on a repeating preview request, then fires the RAW still.
     *
     * The previous version fired a single TEMPLATE_STILL_CAPTURE immediately
     * after opening the camera. On many HALs that first frame arrives before
     * auto-exposure / auto-white-balance have settled, so the DNG records an
     * underexposed, badly-balanced frame ("dark" output). Fix:
     *
     *   1. Run a repeating TEMPLATE_PREVIEW request against a small YUV
     *      [previewReader] surface so the HAL converges AE + AWB. The RAW
     *      reader is deliberately NOT a preview target — its maxImages=2
     *      buffer would fill instantly and block the still capture.
     *   2. Release each preview frame straight back to the reader so the
     *      producer never stalls.
     *   3. Once AE and AWB report converged (or the safety timeout elapses),
     *      stop the repeating request and submit the RAW TEMPLATE_STILL_CAPTURE
     *      against [readerSurface] (plus [jpegSurface] when present).
     */
    private fun runPrecaptureThenStill(
        session: CameraCaptureSession,
        camera: CameraDevice,
        characteristics: CameraCharacteristics,
        readerSurface: Surface,
        previewSurface: Surface,
        previewReader: ImageReader,
        jpegSurface: Surface?,
        jpegOrientation: Int,
        flashMode: Int,
        handler: Handler,
        onStillResult: (TotalCaptureResult) -> Unit,
        onFailure: (Int) -> Unit
    ) {
        val converged = java.util.concurrent.atomic.AtomicBoolean(false)
        val stillSubmitted = java.util.concurrent.atomic.AtomicBoolean(false)

        fun submitStillCapture() {
            if (!stillSubmitted.compareAndSet(false, true)) return
            try { session.stopRepeating() } catch (_: Exception) {}
            try {
                val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                req.addTarget(readerSurface)
                jpegSurface?.let { req.addTarget(it) }
                // Bake the upright rotation into the JPEG's EXIF so the
                // filter pipeline can decode and rotate it like a CameraX shot.
                req.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                req.set(CaptureRequest.CONTROL_AE_LOCK, false)
                req.set(CaptureRequest.CONTROL_AWB_LOCK, false)
                when (flashMode) {
                    0 -> req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                    1 -> req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                    else -> {
                        req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        req.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                    }
                }
                applyRawQualityKeys(req, characteristics)

                session.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        onStillResult(result)
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest, failure: CaptureFailure
                    ) {
                        onFailure(failure.reason)
                    }
                }, handler)
            } catch (e: Exception) {
                Log.e(TAG, "Still capture failed", e)
                onFailure(-1)
            }
        }

        try {
            val preview = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            preview.addTarget(previewSurface)
            preview.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            preview.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            preview.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            preview.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            preview.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)

            session.setRepeatingRequest(preview.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    // Hand the preview buffer back to the reader so the HAL
                    // never stalls on a full queue.
                    try { previewReader.acquireLatestImage()?.close() } catch (_: Exception) {}

                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    val awbState = result.get(CaptureResult.CONTROL_AWB_STATE)
                    val aeConverged = aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                            aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                            aeState == CaptureResult.CONTROL_AE_STATE_LOCKED
                    val awbConverged = awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED ||
                            awbState == CaptureResult.CONTROL_AWB_STATE_LOCKED ||
                            awbState == CaptureResult.CONTROL_AWB_STATE_INACTIVE
                    if (aeConverged && awbConverged && converged.compareAndSet(false, true)) {
                        submitStillCapture()
                    }
                }
            }, handler)

            // Safety net for HALs that never report a converged 3A state, so
            // RAW capture can't hang indefinitely on quirky devices.
            handler.postDelayed({
                if (converged.compareAndSet(false, true)) {
                    Log.w(TAG, "3A convergence timed out; capturing RAW anyway")
                    submitStillCapture()
                }
            }, CONVERGE_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e(TAG, "RAW 3A preview failed", e)
            onFailure(-1)
        }
    }

    /**
     * Best-effort: push HIGH_QUALITY noise/edge/hot-pixel keys onto the still
     * request when the RAW pipeline can use them. RAW bypasses most ISP steps
     * by design, but the keys are harmless on HALs that ignore them for RAW.
     */
    private fun applyRawQualityKeys(
        request: CaptureRequest.Builder,
        characteristics: CameraCharacteristics
    ) {
        try {
            val hpModes = characteristics.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)
            if (hpModes != null && hpModes.contains(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)) {
                request.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
            }
            val oisModes = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            if (oisModes != null && oisModes.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
                request.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
            }
        } catch (_: Exception) {
            // Best-effort; ignore.
        }
    }

    @Suppress("unused")
    private fun ByteBuffer.toBytes(): ByteArray {
        val out = ByteArray(remaining())
        get(out)
        return out
    }
}
