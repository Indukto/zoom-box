package com.example.color

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import java.util.concurrent.Executors

/**
 * [GLSurfaceView] that hosts the camera preview rendered through a 3D-LUT
 * fragment shader. Replaces `androidx.camera.view.PreviewView` for setups that
 * need per-frame color grading.
 *
 * Use [surfaceProvider] when binding the CameraX [Preview]; it gives CameraX a
 * surface backed by this view's internal [SurfaceTexture], which is sampled by
 * [LutPreviewRenderer].
 *
 * All GL work happens on the GLSurfaceView render thread; this class only
 * forwards public setters and the SurfaceRequest hook.
 */
class LutPreviewView(
    context: Context
) : GLSurfaceView(context) {

    val renderer: LutPreviewRenderer = LutPreviewRenderer(this)

    /** Cached resolution from the last SurfaceRequest, used by the renderer. */
    private var lastSurfaceWidth = 0
    private var lastSurfaceHeight = 0

    /** The Surface handed to CameraX, tracked so we can release on cleanup. */
    private var currentSurface: Surface? = null
    private var currentSurfaceTexture: SurfaceTexture? = null

    private val cameraExecutor = Executors.newSingleThreadExecutor()

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        preserveEGLContextOnPause = true
    }

    /**
     * CameraX-compatible [Preview.SurfaceProvider]. Hands CameraX a Surface
     * backed by this view's GL-thread SurfaceTexture.
     */
    val surfaceProvider: Preview.SurfaceProvider = Preview.SurfaceProvider { request ->
        handleSurfaceRequest(request)
    }

    private fun handleSurfaceRequest(request: SurfaceRequest) {
        val resolution = request.resolution
        lastSurfaceWidth = resolution.width
        lastSurfaceHeight = resolution.height
        renderer.setSurfaceBufferSize(lastSurfaceWidth, lastSurfaceHeight)

        // Wait for the renderer to have created its SurfaceTexture on the GL
        // thread, then build a Surface from it and hand it to CameraX. All
        // SurfaceTexture/Surface construction must happen on the GL thread.
        queueEvent {
            val st = renderer.awaitSurfaceTexture()
            if (st == null) {
                Log.e(TAG, "SurfaceTexture not ready; willSafeToAbort")
                request.willNotProvideSurface()
                return@queueEvent
            }

            try {
                // Tell SurfaceTexture what CameraX asked for BEFORE wrapping
                // it in a Surface. Without this, the SurfaceTexture keeps its
                // default (0x0) buffer size and CameraX falls back to its
                // lowest-res preview size — the live viewfinder renders as a
                // blurry upscaled thumbnail. Setting the buffer size to the
                // same resolution CameraX requested matches both sides and
                // unlocks a sharp preview at the sensor-native frame size.
                st.setDefaultBufferSize(resolution.width, resolution.height)

                // The renderer owns its own SurfaceTexture; we wrap it as a
                // Surface for CameraX. We must track this wrapper so we can
                // release it when CameraX signals session teardown.
                val surface = Surface(st)
                currentSurface = surface
                currentSurfaceTexture = st

                request.provideSurface(surface, cameraExecutor) { result ->
                    // CameraX is done with the surface (rebind / lifecycle).
                    // Tear down the wrapper; the underlying SurfaceTexture is
                    // owned by the renderer and reused across rebinds.
                    try { surface.release() } catch (_: Exception) {}
                    if (currentSurface === surface) currentSurface = null
                    Log.d(TAG, "Surface released: ${result.resultCode}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "provideSurface failed", e)
                request.willNotProvideSurface()
            }
        }
    }

    // ------------------------------------------------------------------
    // Public pass-through setters (UI thread → renderer)

    fun setWhiteBalance(temp: Float, tint: Float, exposure: Float) {
        renderer.setWhiteBalance(temp, tint, exposure)
    }

    fun setFilmEffects(
        filmCurve: Float,
        contrast: Float,
        saturation: Float,
        bloomStrength: Float,
        fringing: Float,
        shadowTintR: Float,
        shadowTintG: Float,
        shadowTintB: Float,
        shadowTintStrength: Float,
        highlightTintR: Float,
        highlightTintG: Float,
        highlightTintB: Float,
        highlightTintStrength: Float
    ) {
        renderer.setFilmEffects(
            filmCurve, contrast, saturation, bloomStrength, fringing,
            shadowTintR, shadowTintG, shadowTintB, shadowTintStrength,
            highlightTintR, highlightTintG, highlightTintB, highlightTintStrength
        )
    }

    fun setLut(lut: CubeLut?) {
        renderer.setLut(lut)
    }

    fun setFlipH(flip: Boolean) {
        renderer.setFlipH(flip)
    }

    /** Tear down everything. Call from the host's onDispose / onDestroy. */
    fun cleanup() {
        try { cameraExecutor.shutdown() } catch (_: Exception) {}
        queueEvent { renderer.releaseSurfaceTexture() }
    }

    companion object {
        private const val TAG = "LutPreviewView"
    }
}
