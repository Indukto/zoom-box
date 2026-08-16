package com.example.color

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GLSurfaceView.Renderer that samples the camera SurfaceTexture, applies
 * white-balance + exposure + 3D-LUT color grading in a fragment shader, and
 * blits the result to the screen. This is the live viewfinder counterpart of
 * [LutColorFilter]; the fragment shader performs the same color grade as the
 * CPU path but at full preview rate on the GPU.
 *
 * Lifecycle / threading notes:
 * - All GL calls happen on the GLSurfaceView render thread.
 * - The [SurfaceTexture] fed to CameraX is created on the render thread inside
 *   [onSurfaceCreated] and exposed via [surfaceTextureFuture]; the camera
 *   plumbing reads it back from there.
 * - [onFrameAvailable] is invoked on CameraX's thread; it only pokes the view
 *   to request a render.
 */
class LutPreviewRenderer(
    private val glSurfaceView: GLSurfaceView
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    @Volatile private var surfaceTexture: SurfaceTexture? = null
    /** Read by the SurfaceProvider after [onSurfaceCreated] runs. */
    @Volatile var surfaceTextureReady: Boolean = false
        private set

    // --- GL program state ---
    // DAZZ's renderer uses two passes: copy the external camera texture into
    // a stable 2D texture first, then run all multi-sample effects from that
    // snapshot. Sampling the OES texture repeatedly in one fragment shader can
    // produce unstable frames on tile-based GPUs (Mali/Adreno).
    private var copyProgram = 0
    private var copyAPositionLoc = 0
    private var copyATexCoordLoc = 0
    private var copyUTextureLoc = 0
    private var copyUStMatrixLoc = 0
    private var copyUTexCropLoc = 0

    private var program = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTextureLoc = 0
    private var uTemperatureLoc = 0
    private var uTintLoc = 0
    private var uExposureLoc = 0
    private var uLutEnabledLoc = 0
    private var uLutLoc = 0
    private var uViewSizeLoc = 0

    // ── New effect uniforms ──
    private var uFilmCurveLoc = 0
    private var uContrastLoc = 0
    private var uSaturationLoc = 0
    private var uBloomStrengthLoc = 0
    private var uFringingLoc = 0
    private var uShadowTintRLoc = 0
    private var uShadowTintGLoc = 0
    private var uShadowTintBLoc = 0
    private var uShadowTintStrengthLoc = 0
    private var uHighlightTintRLoc = 0
    private var uHighlightTintGLoc = 0
    private var uHighlightTintBLoc = 0
    private var uHighlightTintStrengthLoc = 0

    // ── Dreamcore-style extras (uniform locations) ──
    private var uSoftFocusLoc = 0
    private var uMilkyMixLoc = 0
    private var uMilkyTintRLoc = 0
    private var uMilkyTintGLoc = 0
    private var uMilkyTintBLoc = 0

    // ── Opt-in artifact uniforms (highlight roll-off, fade, film grain) ──
    private var uHighlightRolloffLoc = 0
    private var uFadeLoc = 0
    private var uGrainStrengthLoc = 0
    private var uGrainChromaLoc = 0

    private var inputTexture = 0
    private var lutTexture = 0
    private var lutWidth = 0  // 0 == no LUT uploaded yet
    private var has3dTextures = false  // false when GPU lacks GL_OES_texture_3D

    // Intermediate render target for the OES -> 2D copy pass. It is sized to
    // the GLSurfaceView viewport and recreated whenever the EGL surface size
    // changes. All access happens on the GL thread.
    private var intermediateFbo = 0
    private var intermediateTexture = 0
    private var intermediateWidth = 0
    private var intermediateHeight = 0

    // --- Per-frame inputs ---
    private val stMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    @Volatile private var temperature = 0f
    @Volatile private var tint = 0f
    @Volatile private var exposure = 0f
    @Volatile private var flipH = false
    @Volatile private var lutEnabled = false

    // ── New per-frame effect inputs ──
    @Volatile private var filmCurve = 0f
    @Volatile private var contrast = 1.0f
    @Volatile private var saturation = 1.0f
    @Volatile private var bloomStrength = 0f
    @Volatile private var fringing = 0f
    @Volatile private var shadowTintR = 0f
    @Volatile private var shadowTintG = 0f
    @Volatile private var shadowTintB = 0f
    @Volatile private var shadowTintStrength = 0f
    @Volatile private var highlightTintR = 0f
    @Volatile private var highlightTintG = 0f
    @Volatile private var highlightTintB = 0f
    @Volatile private var highlightTintStrength = 0f

    // ── Dreamcore-style extras (per-frame inputs) ──
    @Volatile private var softFocus = 0f
    @Volatile private var milkyMix = 0f
    @Volatile private var milkyTintR = 0f
    @Volatile private var milkyTintG = 0f
    @Volatile private var milkyTintB = 0f

    // ── Opt-in artifact inputs. Grain stays 0 in the live preview (only the
    //    GPU capture processor feeds non-zero grain), so the viewfinder keeps
    //    its grain-free look even for grainy film presets. ──
    @Volatile private var highlightRolloff = 0f
    @Volatile private var fade = 0f

    // The active LUT is retained so it can be re-uploaded after an EGL
    // context recreation. The pending value is consumed only on the GL thread.
    @Volatile private var activeLut: CubeLut? = null
    @Volatile private var pendingLut: CubeLut? = null

    // Surface-buffer aspect (set by SurfaceProvider), used for FILL_CENTER crop.
    @Volatile private var surfaceBufferWidth = 0
    @Volatile private var surfaceBufferHeight = 0
    private var viewWidth = 0
    private var viewHeight = 0

    // ------------------------------------------------------------------
    // Public API (called from the UI/compose thread)

    /**
     * Set every color/tone parameter for the current preset in one batch.
     * This is the single entry point shared with the CPU capture pipeline:
     * both paths consume the same [RetroRenderParams] snapshot so the live
     * viewfinder and the saved JPEG stay in sync. WB/exposure, film effects,
     * and dreamcore extras previously arrived through three separate setters.
     */
    fun setRenderParams(params: RetroRenderParams) {
        temperature = params.temperature
        tint = params.tint
        exposure = params.exposure
        filmCurve = params.filmCurve
        contrast = params.contrast
        saturation = params.saturation
        bloomStrength = params.bloom
        fringing = params.fringing
        shadowTintR = params.shadowTintR
        shadowTintG = params.shadowTintG
        shadowTintB = params.shadowTintB
        shadowTintStrength = params.shadowTintStrength
        highlightTintR = params.highlightTintR
        highlightTintG = params.highlightTintG
        highlightTintB = params.highlightTintB
        highlightTintStrength = params.highlightTintStrength
        softFocus = params.softFocus
        milkyMix = params.milkyMix
        milkyTintR = params.milkyTintR
        milkyTintG = params.milkyTintG
        milkyTintB = params.milkyTintB
        highlightRolloff = params.highlightRolloff
        fade = params.fade
        glSurfaceView.requestRender()
    }

    fun setFlipH(value: Boolean) {
        flipH = value
        glSurfaceView.requestRender()
    }

    /**
     * Set the LUT to apply. Pass null to disable LUT grading (WB/exposure only).
     * The upload happens on the GL thread on the next frame.
     */
    fun setLut(lut: CubeLut?) {
        activeLut = lut
        pendingLut = lut
        glSurfaceView.requestRender()
    }

    fun setSurfaceBufferSize(width: Int, height: Int) {
        surfaceBufferWidth = width
        surfaceBufferHeight = height
    }

    /** Blocks until [onSurfaceCreated] has produced the SurfaceTexture. */
    fun awaitSurfaceTexture(): SurfaceTexture? {
        var tries = 0
        while (!surfaceTextureReady && tries < 200) {
            try { Thread.sleep(10) } catch (_: InterruptedException) {}
            tries++
        }
        return surfaceTexture
    }

    // ------------------------------------------------------------------
    // SurfaceTexture.OnFrameAvailableListener

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        glSurfaceView.requestRender()
    }

    // ------------------------------------------------------------------
    // GLSurfaceView.Renderer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A GLSurfaceView may recreate its EGL context after pause/resume.
        // Release the old SurfaceTexture first, then delete any resources that
        // still belong to the current context. Deletion is harmless after a
        // context loss, while doing it here also avoids leaks if the same EGL
        // context survives a surface recreation.
        surfaceTexture?.let { old ->
            try { old.setOnFrameAvailableListener(null) } catch (_: Exception) {}
            try { old.release() } catch (_: Exception) {}
        }
        surfaceTexture = null
        surfaceTextureReady = false
        destroyGlResources()

        // A context loss invalidates the uploaded 3D texture, but not the
        // immutable CubeLut value held by the UI. Re-upload it on the first
        // frame of the new context unless a newer request is already pending.
        if (pendingLut == null) pendingLut = activeLut

        // Pass 1: external OES camera frame -> stable 2D texture.
        copyProgram = createProgram(VERT_SHADER, COPY_FRAGMENT_SHADER)
        require(copyProgram != 0) { "Failed to compile camera copy program" }

        // Pass 2: effect shader samples sampler2D only. If the device cannot
        // compile the 3D-LUT variant, keep the same two-pass architecture and
        // fall back to the no-LUT effect shader.
        program = createProgram(EFFECT_VERT_SHADER, FRAG_SHADER)
        has3dTextures = program != 0
        if (!has3dTextures) {
            Log.w(TAG, "GL_OES_texture_3D not supported; LUT preview disabled")
            program = createProgram(EFFECT_VERT_SHADER, FRAG_SHADER_NO_3D)
        }
        require(program != 0) { "Failed to compile LUT preview program" }

        copyAPositionLoc = GLES20.glGetAttribLocation(copyProgram, "aPosition")
        copyATexCoordLoc = GLES20.glGetAttribLocation(copyProgram, "aTexCoord")
        copyUTextureLoc = GLES20.glGetUniformLocation(copyProgram, "uTexture")
        copyUStMatrixLoc = GLES20.glGetUniformLocation(copyProgram, "uStMatrix")
        copyUTexCropLoc = GLES20.glGetUniformLocation(copyProgram, "uTexCrop")

        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")
        uTemperatureLoc = GLES20.glGetUniformLocation(program, "uTemperature")
        uTintLoc = GLES20.glGetUniformLocation(program, "uTint")
        uExposureLoc = GLES20.glGetUniformLocation(program, "uExposure")
        uLutEnabledLoc = GLES20.glGetUniformLocation(program, "uLutEnabled")
        uLutLoc = GLES20.glGetUniformLocation(program, "uLut")
        uViewSizeLoc = GLES20.glGetUniformLocation(program, "uViewSize")

        // ── New effect uniform locations ──
        uFilmCurveLoc = GLES20.glGetUniformLocation(program, "uFilmCurve")
        uContrastLoc = GLES20.glGetUniformLocation(program, "uContrast")
        uSaturationLoc = GLES20.glGetUniformLocation(program, "uSaturation")
        uBloomStrengthLoc = GLES20.glGetUniformLocation(program, "uBloomStrength")
        uFringingLoc = GLES20.glGetUniformLocation(program, "uFringing")
        uShadowTintRLoc = GLES20.glGetUniformLocation(program, "uShadowTintR")
        uShadowTintGLoc = GLES20.glGetUniformLocation(program, "uShadowTintG")
        uShadowTintBLoc = GLES20.glGetUniformLocation(program, "uShadowTintB")
        uShadowTintStrengthLoc = GLES20.glGetUniformLocation(program, "uShadowTintStrength")
        uHighlightTintRLoc = GLES20.glGetUniformLocation(program, "uHighlightTintR")
        uHighlightTintGLoc = GLES20.glGetUniformLocation(program, "uHighlightTintG")
        uHighlightTintBLoc = GLES20.glGetUniformLocation(program, "uHighlightTintB")
        uHighlightTintStrengthLoc = GLES20.glGetUniformLocation(program, "uHighlightTintStrength")

        // ── Dreamcore-style extras (uniform locations) ──
        uSoftFocusLoc = GLES20.glGetUniformLocation(program, "uSoftFocus")
        uMilkyMixLoc = GLES20.glGetUniformLocation(program, "uMilkyMix")
        uMilkyTintRLoc = GLES20.glGetUniformLocation(program, "uMilkyTintR")
        uMilkyTintGLoc = GLES20.glGetUniformLocation(program, "uMilkyTintG")
        uMilkyTintBLoc = GLES20.glGetUniformLocation(program, "uMilkyTintB")

        // ── Opt-in artifact uniform locations ──
        uHighlightRolloffLoc = GLES20.glGetUniformLocation(program, "uHighlightRolloff")
        uFadeLoc = GLES20.glGetUniformLocation(program, "uFade")
        uGrainStrengthLoc = GLES20.glGetUniformLocation(program, "uGrainStrength")
        uGrainChromaLoc = GLES20.glGetUniformLocation(program, "uGrainChroma")

        // Drain any stale GL errors from EGL-context creation or the program
        // link above. GL errors are sticky flags that survive across bind
        // calls, so calling glGetError here leaves our subsequent
        // glBindTexture + glTexParameteri sequence starting from a known-clean
        // state. This is a defensive no-op on healthy contexts: glGetError
        // returns GL_NO_ERROR immediately and the loop exits. On drivers
        // that surface multiple errors per frame (Adreno, some PowerVR) each
        // one is logged with its hex code so a real bug is still visible.
        while (true) {
            val err = GLES20.glGetError()
            if (err == GLES20.GL_NO_ERROR) break
            Log.w(TAG, "Drained stale GL error 0x${Integer.toHexString(err)} at onSurfaceCreated")
        }

        // Input (camera) texture.
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        inputTexture = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTexture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // The SurfaceTexture owns the camera-to-GL handoff. Attach a detaching
        // GL texture so we can recreate cleanly on context loss.
        val st = SurfaceTexture(inputTexture)
        st.setOnFrameAvailableListener(this)
        surfaceTexture = st
        surfaceTextureReady = true

        // LUT texture (3D). Created lazily when setLut() provides one.
        val lut = IntArray(1)
        GLES20.glGenTextures(1, lut, 0)
        lutTexture = lut[0]

        // Default GL_UNPACK_ALIGNMENT is 4, which only works when rows are a
        // multiple of 4 bytes. A 3D LUT stored as GL_RGB has 3 bytes per texel
        // — for any LUT size n where n * 3 is NOT a multiple of 4 (e.g. the
        // bundled 13-cube LUTs at 39 bytes/row) the driver inserts phantom
        // pad bytes and the next row's channels phase-shift, producing the
        // classic psychedelic rainbow banding. Pin alignment to 1 (no padding)
        // here so subsequent glTexImage3D uploads are always tightly packed.
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        GLES20.glViewport(0, 0, width, height)
        ensureIntermediateTarget(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        // Upload any pending LUT on the GL thread.
        pendingLut?.let { uploadLut(it); pendingLut = null }

        val st = surfaceTexture ?: return
        try {
            // The external texture must be consumed once per frame before it is
            // copied. This also prevents the SurfaceTexture queue from backing
            // up while the effect pass is doing its extra samples.
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)
        } catch (e: Exception) {
            // SurfaceTexture may be released during a rebind; just skip this frame.
            return
        }

        if (viewWidth <= 0 || viewHeight <= 0) return
        if (intermediateFbo == 0 ||
            intermediateWidth != viewWidth || intermediateHeight != viewHeight
        ) {
            ensureIntermediateTarget(viewWidth, viewHeight)
        }

        if (intermediateFbo == 0 || intermediateTexture == 0) {
            // FBO creation is expected to work on every GLES implementation,
            // but keep a live ungraded preview rather than showing a permanent
            // black screen if a vendor driver rejects the target.
            drawCameraFallback()
            return
        }

        drawCopyPass()
        drawEffectPass()
    }

    /** Copies one camera frame from the external OES texture into the stable 2D FBO texture. */
    private fun drawCopyPass() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, intermediateFbo)
        GLES20.glViewport(0, 0, intermediateWidth, intermediateHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(copyProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTexture)
        GLES20.glUniform1i(copyUTextureLoc, 0)
        GLES20.glUniformMatrix4fv(copyUStMatrixLoc, 1, false, stMatrix, 0)

        val crop = computeFillCenterCrop()
        GLES20.glUniform4f(copyUTexCropLoc, crop[0], crop[1], crop[2], crop[3])
        drawQuad(copyAPositionLoc, copyATexCoordLoc, quadTexCoordBuf(flipH))
    }

    /** Runs the existing effect shader over the copied 2D frame and presents it. */
    private fun drawEffectPass() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewWidth, viewHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, intermediateTexture)
        GLES20.glUniform1i(uTextureLoc, 0)
        GLES20.glUniform1f(uTemperatureLoc, temperature)
        GLES20.glUniform1f(uTintLoc, tint)
        GLES20.glUniform1f(uExposureLoc, exposure)
        GLES20.glUniform2f(uViewSizeLoc, viewWidth.toFloat(), viewHeight.toFloat())

        // ── Upload film effect uniforms ──
        GLES20.glUniform1f(uFilmCurveLoc, filmCurve)
        GLES20.glUniform1f(uContrastLoc, contrast)
        GLES20.glUniform1f(uSaturationLoc, saturation)
        GLES20.glUniform1f(uBloomStrengthLoc, bloomStrength)
        GLES20.glUniform1f(uFringingLoc, fringing)
        GLES20.glUniform1f(uShadowTintRLoc, shadowTintR)
        GLES20.glUniform1f(uShadowTintGLoc, shadowTintG)
        GLES20.glUniform1f(uShadowTintBLoc, shadowTintB)
        GLES20.glUniform1f(uShadowTintStrengthLoc, shadowTintStrength)
        GLES20.glUniform1f(uHighlightTintRLoc, highlightTintR)
        GLES20.glUniform1f(uHighlightTintGLoc, highlightTintG)
        GLES20.glUniform1f(uHighlightTintBLoc, highlightTintB)
        GLES20.glUniform1f(uHighlightTintStrengthLoc, highlightTintStrength)

        // ── Upload dreamcore uniforms ──
        GLES20.glUniform1f(uSoftFocusLoc, softFocus)
        GLES20.glUniform1f(uMilkyMixLoc, milkyMix)
        GLES20.glUniform1f(uMilkyTintRLoc, milkyTintR)
        GLES20.glUniform1f(uMilkyTintGLoc, milkyTintG)
        GLES20.glUniform1f(uMilkyTintBLoc, milkyTintB)

        // ── Upload opt-in artifact uniforms. Grain is forced to 0 here: the
        //    live viewfinder intentionally stays grain-free (grain is a
        //    capture-time finish applied to the saved JPEG). ──
        GLES20.glUniform1f(uHighlightRolloffLoc, highlightRolloff)
        GLES20.glUniform1f(uFadeLoc, fade)
        GLES20.glUniform1f(uGrainStrengthLoc, 0f)
        GLES20.glUniform1f(uGrainChromaLoc, 0f)

        if (has3dTextures && lutEnabled && lutWidth > 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
            GLES20.glUniform1i(uLutLoc, 1)
            GLES20.glUniform1i(uLutEnabledLoc, 1)
        } else {
            GLES20.glUniform1i(uLutEnabledLoc, 0)
        }

        // The copy pass owns the camera transform, crop, and front-camera
        // mirror. The effect pass reads a full, already-oriented 2D texture.
        drawQuad(aPositionLoc, aTexCoordLoc, quadTexCoordBuf(false))
    }

    /** Ungraded but correctly oriented fallback used only if an FBO is unavailable. */
    private fun drawCameraFallback() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewWidth, viewHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(copyProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTexture)
        GLES20.glUniform1i(copyUTextureLoc, 0)
        GLES20.glUniformMatrix4fv(copyUStMatrixLoc, 1, false, stMatrix, 0)
        val crop = computeFillCenterCrop()
        GLES20.glUniform4f(copyUTexCropLoc, crop[0], crop[1], crop[2], crop[3])
        drawQuad(copyAPositionLoc, copyATexCoordLoc, quadTexCoordBuf(flipH))
    }

    private fun drawQuad(positionLoc: Int, texCoordLoc: Int, texCoords: FloatBuffer) {
        if (positionLoc < 0 || texCoordLoc < 0) return
        val positions = quadPositionBuf()
        positions.position(0)
        texCoords.position(0)

        GLES20.glEnableVertexAttribArray(positionLoc)
        GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, 0, positions)
        GLES20.glEnableVertexAttribArray(texCoordLoc)
        GLES20.glVertexAttribPointer(texCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoords)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionLoc)
        GLES20.glDisableVertexAttribArray(texCoordLoc)
    }

    /** Releases the SurfaceTexture and all GL resources when the camera session is torn down. */
    fun releaseSurfaceTexture() {
        val st = surfaceTexture
        if (st != null) {
            try { st.setOnFrameAvailableListener(null) } catch (_: Exception) {}
            try { st.release() } catch (_: Exception) {}
        }
        surfaceTexture = null
        surfaceTextureReady = false
        destroyGlResources()
    }

    // ------------------------------------------------------------------
    // Internals

    /** Creates or resizes the intermediate RGBA texture used by the copy pass. */
    private fun ensureIntermediateTarget(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (intermediateFbo != 0 &&
            intermediateWidth == width && intermediateHeight == height
        ) return

        destroyIntermediateTarget()

        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        intermediateTexture = textureIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, intermediateTexture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            width,
            height,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null
        )

        val fboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        intermediateFbo = fboIds[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, intermediateFbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            intermediateTexture,
            0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "Intermediate preview FBO is incomplete: 0x${Integer.toHexString(status)}")
            destroyIntermediateTarget()
            return
        }

        intermediateWidth = width
        intermediateHeight = height
        Log.d(TAG, "Intermediate preview FBO ready: ${width}x${height}")
    }

    private fun destroyIntermediateTarget() {
        if (intermediateFbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(intermediateFbo), 0)
        }
        if (intermediateTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(intermediateTexture), 0)
        }
        intermediateFbo = 0
        intermediateTexture = 0
        intermediateWidth = 0
        intermediateHeight = 0
    }

    /** Deletes resources owned by the current GL context. Must run on the GL thread. */
    private fun destroyGlResources() {
        destroyIntermediateTarget()
        if (inputTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(inputTexture), 0)
        }
        if (lutTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(lutTexture), 0)
        }
        if (copyProgram != 0) GLES20.glDeleteProgram(copyProgram)
        if (program != 0) GLES20.glDeleteProgram(program)
        inputTexture = 0
        lutTexture = 0
        lutWidth = 0
        lutEnabled = false
        copyProgram = 0
        program = 0
        has3dTextures = false
    }

    private fun uploadLut(lut: CubeLut) {
        if (!has3dTextures) return  // GPU doesn't support 3D textures
        // Convert float RGB samples to 8-bit (the camera input is 8-bit anyway).
        val n = lut.size
        val buf = ByteBuffer.allocateDirect(n * n * n * 3).order(ByteOrder.nativeOrder())
        for (v in lut.data) {
            buf.put((v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
        }
        buf.position(0)

        GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES20.GL_CLAMP_TO_EDGE)
        // Belt-and-suspenders: also set UNPACK_ALIGNMENT on every upload so a
        // foreign driver that resets it between contexts can't reintroduce
        // the rainbow banding. See onSurfaceCreated for context.
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES20.GL_RGB,
            n, n, n, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, buf
        )
        lutWidth = n
        lutEnabled = true
    }

    /**
     * Returns (u0, v0, u1, v1) — the sub-rect of the camera buffer that should
     * be sampled to fill the view with FILL_CENTER semantics (no letterbox).
     * Returns full extent (0,0,1,1) when the surface size isn't known yet.
     */
    private fun computeFillCenterCrop(): FloatArray {
        var bw = surfaceBufferWidth
        var bh = surfaceBufferHeight
        if (bw <= 0 || bh <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return floatArrayOf(0f, 0f, 1f, 1f)
        }
        // CameraX writes frames to the SurfaceTexture in sensor-natural
        // orientation (almost always landscape on phones) and sets the
        // stMatrix to rotate them to display-natural during sampling.
        // uTexCrop is applied BEFORE uStMatrix in the vertex shader, so
        // it operates in pre-rotation buffer coords. For 90°/270°
        // rotations the buffer's post-rotation aspect is W:H inverted,
        // so the buffer-side crop math has to use the swapped dims to
        // produce a fill that's correct in display space. We detect the
        // swap by checking the off-diagonal entries that are non-zero on
        // 90°/270° rotation matrices (identity and 180° leave them at 0,
        // so the crop math runs unchanged there).
        if (kotlin.math.abs(stMatrix[1]) > 0.5f || kotlin.math.abs(stMatrix[4]) > 0.5f) {
            val tmp = bw
            bw = bh
            bh = tmp
        }
        val bufferAspect = bw.toFloat() / bh.toFloat()
        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
        return if (bufferAspect > viewAspect) {
            // Buffer is wider — crop left/right.
            val crop = viewAspect / bufferAspect
            val off = (1f - crop) * 0.5f
            floatArrayOf(off, 0f, off + crop, 1f)
        } else {
            // Buffer is taller — crop top/bottom.
            val crop = bufferAspect / viewAspect
            val off = (1f - crop) * 0.5f
            floatArrayOf(0f, off, 1f, off + crop)
        }
    }

    private fun quadPositionBuf(): FloatBuffer {
        // Same vertex array each frame; cached statically.
        if (::posBuf.isInitialized) return posBuf
        val verts = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )
        val b = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(verts)
        b.position(0)
        posBuf = b
        return posBuf
    }

    private lateinit var posBuf: FloatBuffer

    private fun quadTexCoordBuf(flip: Boolean): FloatBuffer {
        if (::tcsBufNoFlip.isInitialized && ::tcsBufFlip.isInitialized) {
            return if (flip) tcsBufFlip else tcsBufNoFlip
        }
        val noFlip = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )
        val flipArr = floatArrayOf(
            1f, 0f,
            0f, 0f,
            1f, 1f,
            0f, 1f
        )
        tcsBufNoFlip = ByteBuffer.allocateDirect(noFlip.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(noFlip)
        tcsBufNoFlip.position(0)
        tcsBufFlip = ByteBuffer.allocateDirect(flipArr.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(flipArr)
        tcsBufFlip.position(0)
        return if (flip) tcsBufFlip else tcsBufNoFlip
    }

    private lateinit var tcsBufNoFlip: FloatBuffer
    private lateinit var tcsBufFlip: FloatBuffer

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            Log.e(TAG, "Shader compile failed: $log")
            return 0
        }
        return shader
    }

    private fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        if (vs == 0) return 0
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        if (fs == 0) {
            GLES20.glDeleteShader(vs)
            return 0
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            Log.e(TAG, "Program link failed: ${GLES20.glGetProgramInfoLog(p)}")
            GLES20.glDeleteProgram(p)
            return 0
        }
        return p
    }

    companion object {
        private const val TAG = "LutPreviewRenderer"

        // Pass 1 fragment shader: sample the camera's external OES texture
        // exactly once and write a stable RGBA frame into the intermediate FBO.
        private const val COPY_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        // Pass 2 vertex shader: the copy pass already applied the camera
        // transform, crop, and mirror, so effects sample the 2D texture as-is.
        internal const val EFFECT_VERT_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        // Quad vertices (clip space) → fullscreen triangle strip.
        private const val VERT_SHADER = """
            uniform mat4 uStMatrix;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform vec4 uTexCrop;   // (u0, v0, u1, v1) sub-rect of camera tex
            varying vec2 vTexCoord;
            void main() {
                // Map the base 0..1 texcoord into the cropped sub-rect.
                vec2 cropped = vec2(
                    mix(uTexCrop.x, uTexCrop.z, aTexCoord.x),
                    mix(uTexCrop.y, uTexCrop.w, aTexCoord.y)
                );
                vTexCoord = (uStMatrix * vec4(cropped, 0.0, 1.0)).xy;
                gl_Position = aPosition;
            }
        """

        // Fragment shader: sample camera → apply WB + exposure + film effects
        // + vignette + optional LUT.
        // Uses GL_OES_EGL_image_external for the camera texture and
        // GL_OES_texture_3D for the LUT. Vignette math matches the CPU
        // applyRetroFilter RadialGradient.
        internal const val FRAG_SHADER = """
            #extension GL_OES_texture_3D : enable
            precision mediump float;
            precision mediump sampler3D;
            uniform sampler2D uTexture;
            uniform mediump sampler3D uLut;
            uniform float uTemperature;
            uniform float uTint;
            uniform float uExposure;
            uniform int uLutEnabled;
            uniform vec2 uViewSize;

            // ── New effect uniforms ──
            uniform float uFilmCurve;
            uniform float uContrast;
            uniform float uSaturation;
            uniform float uBloomStrength;
            uniform float uFringing;
            uniform float uShadowTintR;
            uniform float uShadowTintG;
            uniform float uShadowTintB;
            uniform float uShadowTintStrength;
            uniform float uHighlightTintR;
            uniform float uHighlightTintG;
            uniform float uHighlightTintB;
            uniform float uHighlightTintStrength;

            // ── Dreamcore-style extras (soft-focus blur + milky haze) ──
            uniform float uSoftFocus;
            uniform float uMilkyMix;
            uniform float uMilkyTintR;
            uniform float uMilkyTintG;
            uniform float uMilkyTintB;

            // ── Opt-in artifact uniforms (highlight roll-off, fade, grain) ──
            uniform float uHighlightRolloff;
            uniform float uFade;
            uniform float uGrainStrength;
            uniform float uGrainChroma;

            varying vec2 vTexCoord;

            // ── Filmic S-curve ──
            float filmScurve(float x, float strength) {
                float s = strength * 0.5;
                float toe = (1.0 - exp(-x * 5.0)) * s * 0.12;
                float shoulder = (1.0 - exp(-(1.0 - x) * 5.0)) * s * 0.20;
                float result = x + toe - shoulder;
                float midPush = (x - 0.5) * s * 0.15;
                result += midPush;
                return clamp(result, 0.0, 1.0);
            }

            // ── Filmic highlight roll-off ──
            // Compresses values above a soft ~0.7 knee into a rounded
            // shoulder. At roll-off = 0 the knee is identity (no-op).
            float rolloffChannel(float x) {
                if (x <= 0.7) return x;
                float t = (x - 0.7) / 0.3;
                float shoulder = t - uHighlightRolloff * t * (1.0 - t);
                return 0.7 + shoulder * 0.3;
            }

            // ── Per-pixel hash for film grain ──
            float hash(vec2 p) {
                return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
            }

            void main() {
                vec3 c = texture2D(uTexture, vTexCoord).rgb;

                // ── 0.5. Soft-focus 3x3 box blur (dreamcore) ──
                // Optional first stage for the gauzy/out-of-focus look.
                // 8 extra texture2D samples (only when uSoftFocus>0) blended
                // with the center pixel via a 3x3 box filter. Doing the
                // blur BEFORE WB/exposure/s-curve/lut so the lifted blacks
                // and the per-pixel toning feel the blurred signal — that
                // is what makes the result really feel soft, instead of
                // reading as a sharp image with a slightly blurry
                // standalone filter applied on top.
                if (uSoftFocus > 0.0) {
                    vec2 pxSize = 1.0 / uViewSize;
                    vec3 n_tl = texture2D(uTexture, vTexCoord + vec2(-pxSize.x,  pxSize.y)).rgb;
                    vec3 n_t  = texture2D(uTexture, vTexCoord + vec2(       0.0,  pxSize.y)).rgb;
                    vec3 n_tr = texture2D(uTexture, vTexCoord + vec2( pxSize.x,  pxSize.y)).rgb;
                    vec3 n_l  = texture2D(uTexture, vTexCoord + vec2(-pxSize.x,       0.0)).rgb;
                    vec3 n_r  = texture2D(uTexture, vTexCoord + vec2( pxSize.x,       0.0)).rgb;
                    vec3 n_bl = texture2D(uTexture, vTexCoord + vec2(-pxSize.x, -pxSize.y)).rgb;
                    vec3 n_b  = texture2D(uTexture, vTexCoord + vec2(       0.0, -pxSize.y)).rgb;
                    vec3 n_br = texture2D(uTexture, vTexCoord + vec2( pxSize.x, -pxSize.y)).rgb;
                    vec3 blurred = (n_tl + n_t + n_tr + n_l + c + n_r + n_bl + n_b + n_br) / 9.0;
                    c = mix(c, blurred, uSoftFocus);
                }

                // ── 1. White balance ──
                c.r += uTemperature * 0.04;
                c.b -= uTemperature * 0.04;
                c.g -= uTint * 0.04;
                c.r += uTint * 0.02;
                c.b += uTint * 0.02;
                c = clamp(c, 0.0, 1.0);

                // ── 2. Exposure ──
                c *= pow(2.0, uExposure * 0.4);
                c = clamp(c, 0.0, 1.0);

                // ── 3. Chromatic Fringing ──
                // Shift R and B channels relative to G to simulate color
                // channel misregistration in instant/Polaroid films.
                if (uFringing > 0.0) {
                    vec2 fringingOff = vec2(uFringing * 0.004, 0.0);
                    float rFringe = texture2D(uTexture, vTexCoord + fringingOff).r;
                    float bFringe = texture2D(uTexture, vTexCoord - fringingOff).b;
                    c.r = mix(c.r, rFringe, uFringing * 5.0);
                    c.b = mix(c.b, bFringe, uFringing * 5.0);
                }

                // ── 4. Film S-Curve ──
                // Smooth shoulder/toe replaces hard clipping on highlights/shadows.
                if (uFilmCurve > 0.0) {
                    c.r = filmScurve(c.r, uFilmCurve);
                    c.g = filmScurve(c.g, uFilmCurve);
                    c.b = filmScurve(c.b, uFilmCurve);
                }

                // ── 5. Halation / Bloom ──
                // Warm glow around bright highlights (Portra glow, etc.)
                if (uBloomStrength > 0.0) {
                    float luma = dot(c.rgb, vec3(0.299, 0.587, 0.114));
                    float brightMask = smoothstep(0.3, 0.8, luma);
                    vec3 warmGlow = brightMask * uBloomStrength * luma * vec3(1.0, 0.7, 0.3);
                    c.rgb += warmGlow;
                    c = clamp(c, 0.0, 1.0);
                }

                // ── 5.5. Highlight roll-off (filmic shoulder) ──
                // Rounds off the brightest stops so they never clip hard.
                if (uHighlightRolloff > 0.0) {
                    c.r = rolloffChannel(c.r);
                    c.g = rolloffChannel(c.g);
                    c.b = rolloffChannel(c.b);
                }

                // ── 6. Vignette ──
                vec2 center = uViewSize * 0.5;
                float dist = distance(gl_FragCoord.xy, center);
                float maxRadius = 0.72 * max(uViewSize.x, uViewSize.y);
                float t = (dist / maxRadius - 0.55) / 0.45;
                t = clamp(t, 0.0, 1.0);
                // Subtle film falloff: keep corners readable without removing
                // the analog frame character.
                float shaderA = t * (95.0 / 255.0);
                float invA = 1.0 - shaderA;
                float cornerContrib = t * (8.0 / 255.0) * shaderA;
                c.rgb = c.rgb * invA + vec3(cornerContrib);

                // ── 7. Contrast & Saturation ──
                float lumaCS = dot(c.rgb, vec3(0.299, 0.587, 0.114));
                if (uContrast != 1.0) {
                    c.r = (c.r - 0.5) * uContrast + 0.5;
                    c.g = (c.g - 0.5) * uContrast + 0.5;
                    c.b = (c.b - 0.5) * uContrast + 0.5;
                }
                if (uSaturation != 1.0) {
                    c.r = lumaCS + (c.r - lumaCS) * uSaturation;
                    c.g = lumaCS + (c.g - lumaCS) * uSaturation;
                    c.b = lumaCS + (c.b - lumaCS) * uSaturation;
                }
                c = clamp(c, 0.0, 1.0);

                // ── 8. Split Toning ──
                float lumaST = dot(c.rgb, vec3(0.299, 0.587, 0.114));
                float shadowW = 1.0 - lumaST;
                float highlightW = lumaST;
                if (uShadowTintStrength > 0.0) {
                    c.r += uShadowTintR * shadowW * uShadowTintStrength;
                    c.g += uShadowTintG * shadowW * uShadowTintStrength;
                    c.b += uShadowTintB * shadowW * uShadowTintStrength;
                }
                if (uHighlightTintStrength > 0.0) {
                    c.r += uHighlightTintR * highlightW * uHighlightTintStrength;
                    c.g += uHighlightTintG * highlightW * uHighlightTintStrength;
                    c.b += uHighlightTintB * highlightW * uHighlightTintStrength;
                }
                c = clamp(c, 0.0, 1.0);

                // ── 8.5. Fade (black-point lift) ──
                // Lifts shadows toward mid-gray while mid-tones and
                // highlights are left nearly untouched. Identical formula
                // on CPU and GPU so preview and capture stay in sync.
                if (uFade > 0.0) {
                    c.r += uFade * (0.5 - c.r) * (1.0 - c.r);
                    c.g += uFade * (0.5 - c.g) * (1.0 - c.g);
                    c.b += uFade * (0.5 - c.b) * (1.0 - c.b);
                    c = clamp(c, 0.0, 1.0);
                }

                // ── 8.7. Film grain (capture finish; 0 in live preview) ──
                if (uGrainStrength > 0.0) {
                    float n = hash(gl_FragCoord.xy + vec2(0.13, 0.71));
                    float mono = (n - 0.5) * 2.0 * uGrainStrength * 0.15;
                    c.rgb += vec3(mono);
                    if (uGrainChroma > 0.0) {
                        float nr = hash(gl_FragCoord.xy + vec2(7.1, 3.7));
                        float ng = hash(gl_FragCoord.xy + vec2(91.3, 47.2));
                        float nb = hash(gl_FragCoord.xy + vec2(13.4, 71.9));
                        c.r += (nr - 0.5) * 2.0 * uGrainStrength * uGrainChroma * 0.1;
                        c.g += (ng - 0.5) * 2.0 * uGrainStrength * uGrainChroma * 0.1;
                        c.b += (nb - 0.5) * 2.0 * uGrainStrength * uGrainChroma * 0.1;
                    }
                    c = clamp(c, 0.0, 1.0);
                }

                // ── 9. 3D LUT ──
                if (uLutEnabled == 1) {
                    c = texture3D(uLut, c).rgb;
                }
                // ── 10. Milky pastel haze overlay (dreamcore) ──
                // Last stage: blend toward the milky tint, weighted toward
                // shadows so the cream wash pools into dark areas while
                // highlights barely budge. The (1-luma) term dominates so
                // even a 0.30 mix casts a visible pastel glow over the
                // dark side of the frame while the highlight side gets
                // only ~0.075 of wash — exactly the asymmetry of a
                // semi-translucent cream overlay.
                if (uMilkyMix > 0.0) {
                    vec3 milkyColor = vec3(uMilkyTintR, uMilkyTintG, uMilkyTintB);
                    float milkyLuma = dot(c, vec3(0.299, 0.587, 0.114));
                    float milkyStrength = (1.0 - milkyLuma) * uMilkyMix * 1.2 + uMilkyMix * 0.25;
                    c = mix(c, milkyColor, clamp(milkyStrength, 0.0, 1.0));
                }

                gl_FragColor = vec4(c, 1.0);
            }
        """

        // Fallback fragment shader for GPUs that lack GL_OES_texture_3D
        // (e.g. some Android Emulator GPU profiles). Identical to
        // FRAG_SHADER except the 3D LUT stage is removed entirely —
        // only WB + exposure + film effects + vignette are applied. The
        // camera frame is still sampled from the copied 2D texture because
        // the OES-to-2D copy remains active on this fallback path.
        internal const val FRAG_SHADER_NO_3D = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform float uTemperature;
            uniform float uTint;
            uniform float uExposure;
            uniform vec2 uViewSize;

            // ── New effect uniforms ──
            uniform float uFilmCurve;
            uniform float uContrast;
            uniform float uSaturation;
            uniform float uBloomStrength;
            uniform float uFringing;
            uniform float uShadowTintR;
            uniform float uShadowTintG;
            uniform float uShadowTintB;
            uniform float uShadowTintStrength;
            uniform float uHighlightTintR;
            uniform float uHighlightTintG;
            uniform float uHighlightTintB;
            uniform float uHighlightTintStrength;

            // ── Dreamcore-style extras (soft-focus blur + milky haze) ──
            uniform float uSoftFocus;
            uniform float uMilkyMix;
            uniform float uMilkyTintR;
            uniform float uMilkyTintG;
            uniform float uMilkyTintB;

            // ── Opt-in artifact uniforms (highlight roll-off, fade, grain) ──
            uniform float uHighlightRolloff;
            uniform float uFade;
            uniform float uGrainStrength;
            uniform float uGrainChroma;

            varying vec2 vTexCoord;

            // ── Filmic S-curve ──
            float filmScurve(float x, float strength) {
                float s = strength * 0.5;
                float toe = (1.0 - exp(-x * 5.0)) * s * 0.12;
                float shoulder = (1.0 - exp(-(1.0 - x) * 5.0)) * s * 0.20;
                float result = x + toe - shoulder;
                float midPush = (x - 0.5) * s * 0.15;
                result += midPush;
                return clamp(result, 0.0, 1.0);
            }

            // ── Filmic highlight roll-off ──
            // Compresses values above a soft ~0.7 knee into a rounded
            // shoulder. At roll-off = 0 the knee is identity (no-op).
            float rolloffChannel(float x) {
                if (x <= 0.7) return x;
                float t = (x - 0.7) / 0.3;
                float shoulder = t - uHighlightRolloff * t * (1.0 - t);
                return 0.7 + shoulder * 0.3;
            }

            // ── Per-pixel hash for film grain ──
            float hash(vec2 p) {
                return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
            }

            void main() {
                vec3 c = texture2D(uTexture, vTexCoord).rgb;

                // ── 0.5. Soft-focus 3x3 box blur (dreamcore) ──
                // Optional first stage for the gauzy/out-of-focus look.
                // 8 extra texture2D samples (only when uSoftFocus>0) blended
                // with the center pixel via a 3x3 box filter. Doing the
                // blur BEFORE WB/exposure/s-curve/lut so the lifted blacks
                // and the per-pixel toning feel the blurred signal — that
                // is what makes the result really feel soft, instead of
                // reading as a sharp image with a slightly blurry
                // standalone filter applied on top.
                if (uSoftFocus > 0.0) {
                    vec2 pxSize = 1.0 / uViewSize;
                    vec3 n_tl = texture2D(uTexture, vTexCoord + vec2(-pxSize.x,  pxSize.y)).rgb;
                    vec3 n_t  = texture2D(uTexture, vTexCoord + vec2(       0.0,  pxSize.y)).rgb;
                    vec3 n_tr = texture2D(uTexture, vTexCoord + vec2( pxSize.x,  pxSize.y)).rgb;
                    vec3 n_l  = texture2D(uTexture, vTexCoord + vec2(-pxSize.x,       0.0)).rgb;
                    vec3 n_r  = texture2D(uTexture, vTexCoord + vec2( pxSize.x,       0.0)).rgb;
                    vec3 n_bl = texture2D(uTexture, vTexCoord + vec2(-pxSize.x, -pxSize.y)).rgb;
                    vec3 n_b  = texture2D(uTexture, vTexCoord + vec2(       0.0, -pxSize.y)).rgb;
                    vec3 n_br = texture2D(uTexture, vTexCoord + vec2( pxSize.x, -pxSize.y)).rgb;
                    vec3 blurred = (n_tl + n_t + n_tr + n_l + c + n_r + n_bl + n_b + n_br) / 9.0;
                    c = mix(c, blurred, uSoftFocus);
                }

                // ── 1. White balance ──
                c.r += uTemperature * 0.04;
                c.b -= uTemperature * 0.04;
                c.g -= uTint * 0.04;
                c.r += uTint * 0.02;
                c.b += uTint * 0.02;
                c = clamp(c, 0.0, 1.0);

                // ── 2. Exposure ──
                c *= pow(2.0, uExposure * 0.4);
                c = clamp(c, 0.0, 1.0);

                // ── 3. Chromatic Fringing ──
                if (uFringing > 0.0) {
                    vec2 fringingOff = vec2(uFringing * 0.004, 0.0);
                    float rFringe = texture2D(uTexture, vTexCoord + fringingOff).r;
                    float bFringe = texture2D(uTexture, vTexCoord - fringingOff).b;
                    c.r = mix(c.r, rFringe, uFringing * 5.0);
                    c.b = mix(c.b, bFringe, uFringing * 5.0);
                }

                // ── 4. Film S-Curve ──
                if (uFilmCurve > 0.0) {
                    c.r = filmScurve(c.r, uFilmCurve);
                    c.g = filmScurve(c.g, uFilmCurve);
                    c.b = filmScurve(c.b, uFilmCurve);
                }

                // ── 5. Halation / Bloom ──
                if (uBloomStrength > 0.0) {
                    float luma = dot(c.rgb, vec3(0.299, 0.587, 0.114));
                    float brightMask = smoothstep(0.3, 0.8, luma);
                    vec3 warmGlow = brightMask * uBloomStrength * luma * vec3(1.0, 0.7, 0.3);
                    c.rgb += warmGlow;
                    c = clamp(c, 0.0, 1.0);
                }

                // ── 5.5. Highlight roll-off (filmic shoulder) ──
                // Rounds off the brightest stops so they never clip hard.
                if (uHighlightRolloff > 0.0) {
                    c.r = rolloffChannel(c.r);
                    c.g = rolloffChannel(c.g);
                    c.b = rolloffChannel(c.b);
                }

                // ── 6. Vignette ──
                vec2 center = uViewSize * 0.5;
                float dist = distance(gl_FragCoord.xy, center);
                float maxRadius = 0.72 * max(uViewSize.x, uViewSize.y);
                float t = (dist / maxRadius - 0.55) / 0.45;
                t = clamp(t, 0.0, 1.0);
                // Subtle film falloff: keep corners readable without removing
                // the analog frame character.
                float shaderA = t * (95.0 / 255.0);
                float invA = 1.0 - shaderA;
                float cornerContrib = t * (8.0 / 255.0) * shaderA;
                c.rgb = c.rgb * invA + vec3(cornerContrib);

                // ── 7. Contrast & Saturation ──
                float lumaCS = dot(c.rgb, vec3(0.299, 0.587, 0.114));
                if (uContrast != 1.0) {
                    c.r = (c.r - 0.5) * uContrast + 0.5;
                    c.g = (c.g - 0.5) * uContrast + 0.5;
                    c.b = (c.b - 0.5) * uContrast + 0.5;
                }
                if (uSaturation != 1.0) {
                    c.r = lumaCS + (c.r - lumaCS) * uSaturation;
                    c.g = lumaCS + (c.g - lumaCS) * uSaturation;
                    c.b = lumaCS + (c.b - lumaCS) * uSaturation;
                }
                c = clamp(c, 0.0, 1.0);

                // ── 8. Split Toning ──
                float lumaST = dot(c.rgb, vec3(0.299, 0.587, 0.114));
                float shadowW = 1.0 - lumaST;
                float highlightW = lumaST;
                if (uShadowTintStrength > 0.0) {
                    c.r += uShadowTintR * shadowW * uShadowTintStrength;
                    c.g += uShadowTintG * shadowW * uShadowTintStrength;
                    c.b += uShadowTintB * shadowW * uShadowTintStrength;
                }
                if (uHighlightTintStrength > 0.0) {
                    c.r += uHighlightTintR * highlightW * uHighlightTintStrength;
                    c.g += uHighlightTintG * highlightW * uHighlightTintStrength;
                    c.b += uHighlightTintB * highlightW * uHighlightTintStrength;
                }
                c = clamp(c, 0.0, 1.0);

                // ── 8.5. Fade (black-point lift) ──
                // Lifts shadows toward mid-gray while mid-tones and
                // highlights are left nearly untouched. Identical formula
                // on CPU and GPU so preview and capture stay in sync.
                if (uFade > 0.0) {
                    c.r += uFade * (0.5 - c.r) * (1.0 - c.r);
                    c.g += uFade * (0.5 - c.g) * (1.0 - c.g);
                    c.b += uFade * (0.5 - c.b) * (1.0 - c.b);
                    c = clamp(c, 0.0, 1.0);
                }

                // ── 8.7. Film grain (capture finish; 0 in live preview) ──
                if (uGrainStrength > 0.0) {
                    float n = hash(gl_FragCoord.xy + vec2(0.13, 0.71));
                    float mono = (n - 0.5) * 2.0 * uGrainStrength * 0.15;
                    c.rgb += vec3(mono);
                    if (uGrainChroma > 0.0) {
                        float nr = hash(gl_FragCoord.xy + vec2(7.1, 3.7));
                        float ng = hash(gl_FragCoord.xy + vec2(91.3, 47.2));
                        float nb = hash(gl_FragCoord.xy + vec2(13.4, 71.9));
                        c.r += (nr - 0.5) * 2.0 * uGrainStrength * uGrainChroma * 0.1;
                        c.g += (ng - 0.5) * 2.0 * uGrainStrength * uGrainChroma * 0.1;
                        c.b += (nb - 0.5) * 2.0 * uGrainStrength * uGrainChroma * 0.1;
                    }
                    c = clamp(c, 0.0, 1.0);
                }

                // ── 10. Milky pastel haze overlay (dreamcore) ──
                // Last stage: blend toward the milky tint, weighted toward
                // shadows so the cream wash pools into dark areas while
                // highlights barely budge. The (1-luma) term dominates so
                // even a 0.30 mix casts a visible pastel glow over the
                // dark side of the frame while the highlight side gets
                // only ~0.075 of wash — exactly the asymmetry of a
                // semi-translucent cream overlay.
                if (uMilkyMix > 0.0) {
                    vec3 milkyColor = vec3(uMilkyTintR, uMilkyTintG, uMilkyTintB);
                    float milkyLuma = dot(c, vec3(0.299, 0.587, 0.114));
                    float milkyStrength = (1.0 - milkyLuma) * uMilkyMix * 1.2 + uMilkyMix * 0.25;
                    c = mix(c, milkyColor, clamp(milkyStrength, 0.0, 1.0));
                }

                gl_FragColor = vec4(c, 1.0);
            }
        """
    }
}