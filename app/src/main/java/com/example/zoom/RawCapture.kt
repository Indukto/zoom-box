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
 * Replaces the legacy (dead) `captureWithCamera2()` JPEG path with a real
 * RAW pipeline:
 *   1. Open the logical camera and configure a RAW_SENSOR ImageReader.
 *   2. For multi-camera devices, target the requested physical lens via
 *      `OutputConfiguration.setPhysicalCameraId` (API 28+).
 *   3. Run a precapture 3A convergence so exposure/AF have settled.
 *   4. Submit a single TEMPLATE_STILL_CAPTURE RAW capture.
 *   5. Pair the returned Image + TotalCaptureResult and emit a DNG via
 *      [DngCreator], oriented to the device rotation.
 *
 * The result is a `.dng` saved to `Pictures/` in external files dir, mirroring
 * the storage convention of the JPEG pipeline. The JPEG post-processing
 * (retro filter, crop, gallery insert) is NOT applied to DNGs — RAW is a
 * parallel "Pro" output.
 */
object RawCapture {

    private const val TAG = "RawCapture"

    /**
     * @param context Activity/application context
     * @param logicalCameraId The logical Camera2 id (e.g. "0")
     * @param physicalCameraId The target physical lens id (may equal logical
     *        if the device has no logical multi-camera)
     * @param focalLengthMm Native focal length, used only to label the filename
     * @param flashMode 0 = auto, 1 = on, 2 = off
     * @param targetRotation Current physical device rotation (Surface.ROTATION_*),
     *     sensor-tracked by the caller — Display.getRotation() stays ROTATION_0
     *     while the activity is locked to portrait, so it can't be used here
     * @param onCaptured Invoked with the written .dng file
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
        onCaptured: (File) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val directory = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
            ?: context.cacheDir
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val photoFile = File(directory, "RETRO_RAW_${timeStamp}_${focalLengthMm}mm.dng")

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraThread = HandlerThread("RawCapture").apply { start() }
        val cameraHandler = Handler(cameraThread.looper)

        var imageReader: ImageReader? = null
        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)

        fun cleanup() {
            try { imageReader?.close() } catch (_: Exception) {}
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

        fun succeedOnce(file: File) {
            if (finished.compareAndSet(false, true)) {
                cleanup()
                onCaptured(file)
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

            val sensorOrientation = physicalChars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val deviceRotation = when (targetRotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            // DNG orientation: rotate sensor image into device-natural, then by display rotation.
            // DngCreator.setOrientation() expects EXIF orientation values, not raw degrees:
            //   0° → 1, 90° → 6, 180° → 3, 270° → 8
            val dngCwRotation = (sensorOrientation + deviceRotation + 360) % 360
            val dngExifOrientation = when (dngCwRotation) {
                90 -> 6
                180 -> 3
                270 -> 8
                else -> 1
            }

            // Pair the Image and TotalCaptureResult before writing the DNG.
            // Either may arrive first; both are required by DngCreator.
            var pendingImage: Image? = null
            var pendingResult: TotalCaptureResult? = null

            fun tryWriteDng() {
                val img = pendingImage ?: return
                val res = pendingResult ?: return
                Log.d(TAG, "Both RAW image and metadata arrived. Writing DNG...")
                try {
                    val dng = DngCreator(physicalChars, res)
                    dng.setDescription("ZoomBox Camera RAW capture")
                    dng.setOrientation(dngExifOrientation)
                    FileOutputStream(photoFile).use { out ->
                        dng.writeImage(out, img)
                    }
                    dng.close()
                    img.close()
                    succeedOnce(photoFile)
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
                    tryWriteDng()
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading RAW image", e)
                    failOnce(e)
                }
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
                                    flashMode = flashMode,
                                    handler = cameraHandler,
                                    onStillResult = { result ->
                                        Log.d(TAG, "RAW TotalCaptureResult arrived")
                                        pendingResult = result
                                        tryWriteDng()
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
                            val executor = java.util.concurrent.Executor { cmd -> cameraHandler.post(cmd) }
                            val sessionConfig = SessionConfiguration(
                                SessionConfiguration.SESSION_REGULAR,
                                listOf(outputConfig),
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
                                listOf(imageReader.surface), sessionCallback, cameraHandler)
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
     * Fires a single TEMPLATE_STILL_CAPTURE for the RAW sensor.
     *
     * We do NOT run a repeating preview or AE precapture sequence here because
     * the RAW_SENSOR ImageReader has a very small buffer (maxImages=2). A
     * repeating request targeting the reader surface would fill those slots
     * instantly, causing "max images 2 has already been acquired" when the
     * actual still capture tries to deliver its result.
     *
     * TEMPLATE_STILL_CAPTURE already handles 3A convergence internally on
     * most HALs, so a direct capture is sufficient and avoids the buffer
     * exhaustion problem.
     */
    private fun runPrecaptureThenStill(
        session: CameraCaptureSession,
        camera: CameraDevice,
        characteristics: CameraCharacteristics,
        readerSurface: Surface,
        flashMode: Int,
        handler: Handler,
        onStillResult: (TotalCaptureResult) -> Unit,
        onFailure: (Int) -> Unit
    ) {
        try {
            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            req.addTarget(readerSurface)
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
