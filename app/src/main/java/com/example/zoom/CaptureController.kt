package com.example.zoom

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.view.WindowManager
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.Stable
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/**
 * Handles the shutter tap.
 *
 * The preview lens and capture lens are always the same — the user selects
 * which physical lens to use (Ultra-wide, Primary, or Tele) and both preview
 * and capture use that lens. No automatic lens swapping.
 *
 * For the Primary lens, digital zoom is applied as a post-capture crop.
 */
class CaptureController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {

    companion object {
        private const val TAG = "CaptureController"
    }

    @Stable
    data class CaptureResult(
        val photoFile: File,
        val captureLensRole: LensRole,
        val cropFactor: Float,
        val outputMegapixels: Float
    )

    /**
     * Captures a photo using the currently bound lens (same as preview).
     *
     * @param imageCapture The currently bound ImageCapture
     * @param targetFocalMm The target equivalent focal length (for EXIF / filename)
     * @param flashMode Flash mode: 0 = Auto, 1 = On, 2 = Off
     * @param executor The executor for async callbacks
     * @param onResult Callback with the CaptureResult on success
     * @param onError Callback with the exception on failure
     */
    fun capture(
        imageCapture: ImageCapture,
        targetFocalMm: Float,
        flashMode: Int,
        executor: Executor,
        onResult: (CaptureResult) -> Unit,
        onError: (Exception) -> Unit
    ) {
        try {
            val photoFile = createOutputFile(targetFocalMm)
            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

            imageCapture.takePicture(
                outputOptions,
                executor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        onResult(
                            CaptureResult(
                                photoFile = photoFile,
                                captureLensRole = LensRole.PRIMARY,
                                cropFactor = 1.0f,
                                outputMegapixels = 0f
                            )
                        )
                    }

                    override fun onError(exception: ImageCaptureException) {
                        onError(exception)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error during capture", e)
            onError(e)
        }
    }

    /**
     * Creates a timestamped output file for the captured photo.
     */
    private fun createOutputFile(targetFocalMm: Float): File {
        val directory = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
            ?: context.cacheDir
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val focalInt = targetFocalMm.toInt()
        return File(directory, "RETRO_IMG_${timeStamp}_${focalInt}mm.jpg")
    }
}