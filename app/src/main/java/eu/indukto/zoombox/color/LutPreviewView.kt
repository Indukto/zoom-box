package eu.indukto.zoombox.color

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [TextureView] that hosts the camera preview rendered through a 3D-LUT
 * fragment shader. Replaces `androidx.camera.view.PreviewView` for setups that
 * need per-frame color grading.
 *
 * Previously a [GLSurfaceView] (separate Android surface, hole-punched below
 * the window). Moved to TextureView so the graded frames are composited
 * in-window and visible to in-window capture — the control bubble's backdrop
 * blur samples the live video through this path. Behavior is otherwise
 * identical: on-demand rendering only (camera frame or param change), same
 * [surfaceProvider] contract for CameraX, same [LutPreviewRenderer] GL code.
 *
 * Use [surfaceProvider] when binding the CameraX [Preview]; it gives CameraX a
 * surface backed by the renderer's internal [SurfaceTexture], which is sampled
 * by [LutPreviewRenderer].
 *
 * All GL work happens on a dedicated render thread; this class only forwards
 * public setters and the SurfaceRequest hook.
 */
class LutPreviewView(
    context: Context
) : TextureView(context), TextureView.SurfaceTextureListener {

    val renderer: LutPreviewRenderer = LutPreviewRenderer(onRequestRender = { requestRender() })

    /** Cached resolution from the last SurfaceRequest, used by the renderer. */
    private var lastSurfaceWidth = 0
    private var lastSurfaceHeight = 0

    /** The Surface handed to CameraX, tracked so we can release on cleanup. */
    private var currentSurface: Surface? = null
    private var currentSurfaceTexture: SurfaceTexture? = null

    // The TextureView's own SurfaceTexture + last window size, kept so a dead
    // EGL context can be rebuilt against the same window surface (see
    // drawFrame's recovery path).
    private var windowSurfaceTexture: SurfaceTexture? = null
    private var windowWidth = 0
    private var windowHeight = 0

    // Consecutive failed draws before recovery gives up. Reset by any
    // successful draw or fresh surface, so a real context loss recovers while
    // a permanently broken EGL cannot spin teardown/init forever.
    private var failedDraws = 0

    /**
     * Invoked after the GL chain is rebuilt from a dead EGL context. CameraX
     * must re-provide its surface then: the rebuild mints a fresh camera
     * SurfaceTexture (see LutPreviewRenderer.onGlContextCreated). The host
     * bumps its rebind trigger so the proven unbindAll → bindToLifecycle path
     * runs again.
     */
    var onContextLost: (() -> Unit)? = null

    private val cameraExecutor = Executors.newSingleThreadExecutor()

    // Manually-managed EGL state. Touched only on [glThread], except for the
    // nullable reads in drawFrame's early-out (safe: writes happen-before via
    // the Handler queue).
    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null

    // Coalesces render requests: at most one draw is ever queued, matching
    // the old RENDERMODE_WHEN_DIRTY semantics (no continuous render loop).
    private val renderPending = AtomicBoolean(false)

    init {
        surfaceTextureListener = this
        // We paint every pixel opaquely (the renderer clears to black), so the
        // view composites as opaque — cheaper, and keeps the video in the
        // in-window layer that backdrop blur captures.
        isOpaque = true
    }

    /**
     * CameraX-compatible [Preview.SurfaceProvider]. Hands CameraX a Surface
     * backed by the renderer's GL-thread SurfaceTexture.
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
        postGl {
            val st = renderer.awaitSurfaceTexture()
            if (st == null) {
                Log.e(TAG, "SurfaceTexture not ready; willNotProvideSurface")
                request.willNotProvideSurface()
                return@postGl
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

    /**
     * Push one immutable render-parameter snapshot (preset look + user WB /
     * exposure) to the GPU renderer. Both the live preview and the CPU
     * capture pipeline build the same [RetroRenderParams], so the viewfinder
     * and the saved JPEG share a single source of truth.
     */
    fun setRenderParams(params: RetroRenderParams, grainSeed: Int = 0) {
        renderer.setRenderParams(params, grainSeed)
    }

    fun setLut(lut: CubeLut?) {
        renderer.setLut(lut)
    }

    /**
     * Crossfade blend between two LUTs: [from] at mix = 0 morphing to [to]
     * at mix = 1. Either side may be null (pass-through side grades nothing).
     */
    fun setLutBlend(from: CubeLut?, to: CubeLut?, mix: Float) {
        renderer.setLutBlend(from, to, mix)
    }

    fun setFlipH(flip: Boolean) {
        renderer.setFlipH(flip)
    }

    /** Tear down everything. Call from the host's onDispose / onDestroy. */
    fun cleanup() {
        runGlBlocking { teardownGlLocked() }
        quitGlThread()
        try { cameraExecutor.shutdown() } catch (_: Exception) {}
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // The window surface is gone with the view; release EGL and camera
        // surfaces now so a re-attach starts clean. The camera executor stays
        // alive on purpose: this remember'ed instance is re-attached on
        // preset switches and the next bind reuses it.
        runGlBlocking { teardownGlLocked() }
        quitGlThread()
    }

    // ------------------------------------------------------------------
    // TextureView.SurfaceTextureListener (UI thread callbacks)

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        windowSurfaceTexture = st
        windowWidth = width
        windowHeight = height
        failedDraws = 0
        ensureGlThread()
        postGl { initGl(st, width, height) }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        // The EGL window surface tracks the TextureView buffer; only the GL
        // viewport / FBO target need to follow the new size.
        postGl {
            if (eglDisplay != null) {
                renderer.onGlSurfaceChanged(width, height)
                requestRender()
            }
        }
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        // Synchronously release our EGL surface before the framework releases
        // the SurfaceTexture underneath it. Returning true lets the framework
        // finish the release.
        runGlBlocking { teardownGlLocked() }
        windowSurfaceTexture = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
        // Intentionally empty: we render on demand (camera frame or param
        // change). Our own eglSwapBuffers triggers this callback, so
        // requesting a render here would self-trigger an infinite loop.
    }

    // ------------------------------------------------------------------
    // Render thread + manual EGL (GL thread only, unless noted)

    @Synchronized
    private fun ensureGlThread() {
        if (glThread == null) {
            val t = HandlerThread("LutPreviewGL")
            t.start()
            glThread = t
            glHandler = Handler(t.looper)
        }
    }

    @Synchronized
    private fun quitGlThread() {
        try { glThread?.quitSafely() } catch (_: Exception) {}
        glThread = null
        glHandler = null
    }

    private fun postGl(block: () -> Unit) {
        val handler = glHandler ?: return
        handler.post {
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "GL task failed", e)
            }
        }
    }

    /**
     * Runs [block] on the GL thread and waits for completion. Only used for
     * teardown paths that must finish before the caller continues (surface
     * destroy, cleanup). Never called from the GL thread itself, and the GL
     * thread never blocks on the UI thread, so this cannot deadlock.
     */
    private fun runGlBlocking(block: () -> Unit) {
        val handler = glHandler
        if (handler == null || Looper.myLooper() === handler.looper) {
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "GL blocking task failed", e)
            }
            return
        }
        val latch = CountDownLatch(1)
        var error: Exception? = null
        handler.post {
            try {
                block()
            } catch (e: Exception) {
                error = e
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await(3, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        error?.let { Log.e(TAG, "GL blocking task failed", it) }
    }

    /** Coalesced render request: at most one draw queued at any time. */
    private fun requestRender() {
        val handler = glHandler ?: return
        if (renderPending.compareAndSet(false, true)) {
            handler.post {
                renderPending.set(false)
                drawFrame()
            }
        }
    }

    private fun initGl(displaySt: SurfaceTexture, width: Int, height: Int) {
        if (eglDisplay != null) {
            Log.w(TAG, "initGl: EGL already initialized; tearing down first")
            teardownGlLocked()
        }
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display === EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "initGl: no EGL display")
            return
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            Log.e(TAG, "initGl: eglInitialize failed")
            return
        }
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, numConfigs, 0) ||
            numConfigs[0] == 0
        ) {
            Log.e(TAG, "initGl: no matching EGL config")
            // No eglTerminate on these error paths either: the display is
            // shared with GpuCaptureProcessor and terminating it would tear
            // down the capture pipeline's live context too. Our own objects
            // are all that this class may destroy.
            return
        }
        val context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        if (context === EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "initGl: context creation failed")
            return
        }
        val surface = EGL14.eglCreateWindowSurface(
            display, configs[0], displaySt, intArrayOf(EGL14.EGL_NONE), 0
        )
        if (surface == null || surface === EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "initGl: window surface creation failed")
            EGL14.eglDestroyContext(display, context)
            return
        }
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            Log.e(TAG, "initGl: eglMakeCurrent failed")
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            return
        }
        eglDisplay = display
        eglContext = context
        eglSurface = surface
        renderer.onGlContextCreated()
        renderer.onGlSurfaceChanged(width, height)
        requestRender()
    }

    private fun drawFrame() {
        val display = eglDisplay ?: return
        val surface = eglSurface ?: return
        val context = eglContext ?: return
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            // A failed makeCurrent means the cached EGL objects are dead (GPU
            // reset, or another component terminated the shared display).
            // Logging and returning left the viewfinder black until the next
            // look switch — the reported "screen goes black and never comes
            // back". Rebuild the EGL chain against the same window surface and
            // tell the host so CameraX re-provides its surface on the fresh
            // camera SurfaceTexture. Bounded: after two failed rebuilds in a
            // row, stop — the EGL stack is permanently broken and looping
            // teardown/init on every frame would be worse than a black feed.
            failedDraws++
            Log.w(TAG, "drawFrame: eglMakeCurrent failed (attempt $failedDraws)")
            if (failedDraws > 2) return
            val st = windowSurfaceTexture
            teardownGlLocked()
            if (st != null) {
                initGl(st, windowWidth, windowHeight)
                onContextLost?.invoke()
            }
            return
        }
        failedDraws = 0
        try {
            renderer.drawFrame()
        } catch (e: Exception) {
            Log.e(TAG, "drawFrame failed", e)
            return
        }
        if (!EGL14.eglSwapBuffers(display, surface)) {
            Log.w(TAG, "drawFrame: eglSwapBuffers failed")
        }
    }

    private fun teardownGlLocked() {
        // Release the camera SurfaceTexture first: CameraX must re-provide a
        // surface after this, which the existing rebind path handles (same
        // contract as the old surface-destroy path).
        try {
            renderer.releaseSurfaceTexture()
        } catch (e: Exception) {
            Log.w(TAG, "teardown: releaseSurfaceTexture failed", e)
        }
        val display = eglDisplay
        if (display != null && display !== EGL14.EGL_NO_DISPLAY) {
            try {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
            } catch (_: Exception) {
            }
            eglSurface?.let {
                try {
                    EGL14.eglDestroySurface(display, it)
                } catch (_: Exception) {
                }
            }
            eglContext?.let {
                try {
                    EGL14.eglDestroyContext(display, it)
                } catch (_: Exception) {
                }
            }
            // Deliberately NO eglTerminate: EGL_DEFAULT_DISPLAY is
            // process-global and shared with GpuCaptureProcessor. On
            // non-refcounted drivers eglTerminate tears down EVERY context on
            // the display, so calling it here would kill the capture
            // pipeline's cached context — the next shutter then renders into a
            // zombie context and saves a black photo. This class owns exactly
            // one surface and one context; destroying those is the full
            // cleanup.
        }
        eglSurface = null
        eglContext = null
        eglDisplay = null
    }

    companion object {
        private const val TAG = "LutPreviewView"
    }
}

