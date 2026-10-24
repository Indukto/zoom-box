package eu.indukto.zoombox.color

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GL renderer that samples the camera SurfaceTexture, applies white-balance
 * + exposure + 3D-LUT color grading in a fragment shader, and blits the
 * result to the screen. This is the live viewfinder counterpart of the CPU
 * capture filter (`CameraViewModel.applyRetroFilter`) and the GPU capture
 * processor ([GpuCaptureProcessor]); the fragment shader performs the same
 * color grade as those paths but at full preview rate on the GPU.
 *
 * The renderer owns no thread or EGL state: [LutPreviewView] (a TextureView
 * with a manually-managed EGL context) drives [onGlContextCreated],
 * [onGlSurfaceChanged] and [drawFrame] on its render thread and forwards
 * render requests through [onRequestRender].
 *
 * Lifecycle / threading notes:
 * - All GL calls happen on the view's render thread.
 * - The [SurfaceTexture] fed to CameraX is created on the render thread inside
 *   [onGlContextCreated] and exposed via [surfaceTextureFuture]; the camera
 *   plumbing reads it back from there.
 * - [onFrameAvailable] is invoked on CameraX's thread; it only pokes the view
 *   to request a render.
 */
class LutPreviewRenderer(
    private val onRequestRender: () -> Unit
) : SurfaceTexture.OnFrameAvailableListener {

    @Volatile private var surfaceTexture: SurfaceTexture? = null
    /** Read by the SurfaceProvider after [onSurfaceCreated] runs. */
    @Volatile var surfaceTextureReady: Boolean = false
        private set

    // --- GL program state ---
    // The renderer uses two passes: copy the external camera texture into
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
    // ── Crossfade blend (second LUT slot + mix factor) ──
    private var uLut2Loc = 0
    private var uLut2EnabledLoc = 0
    private var uLutMixLoc = 0
    private var uLutStrengthLoc = 0
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
    private var uGrainSeedLoc = 0
    private var uGrainTimeLoc = 0
    private var uGrainCellLoc = 0
    private var uVignetteLoc = 0
    private var uDustLoc = 0
    private var uScratchLoc = 0
    private var uLightLeakLoc = 0

    // ── Spatial halation (quarter-res blur target + composite) ──
    private var uHalationTexLoc = 0
    private var uHalationOnLoc = 0
    private var uHalationStrengthLoc = 0
    private var halationProgram = 0
    private var halationAPositionLoc = 0
    private var halationATexCoordLoc = 0
    private var halationUTextureLoc = 0
    private var halationUTexelLoc = 0
    private var halationURadiusLoc = 0
    private var halationFbo = 0
    private var halationTexture = 0
    private var halationWidth = 0
    private var halationHeight = 0
    @Volatile private var halationStrength = 0f
    @Volatile private var halationRadius = 8f

    private var inputTexture = 0
    private var lutTexture = 0
    private var lutWidth = 0  // 0 == no LUT uploaded yet
    // Second 3D-LUT slot for the look crossfade: the outgoing look stays in
    // slot A while the incoming look uploads into slot B, and the shader
    // mixes the two graded colors by uLutMix. Either slot may be absent
    // (pass-through side), in which case that side contributes ungraded c.
    private var lutTextureB = 0
    private var lutWidthB = 0
    private var lutBEnabled = false
    private var has3dTextures = false  // false when GPU lacks GL_OES_texture_3D

    // Intermediate render target for the OES -> 2D copy pass. It is sized to
    // the view viewport and recreated whenever the EGL surface size
    // changes. All access happens on the GL thread.
    private var intermediateFbo = 0
    private var intermediateTexture = 0
    private var intermediateWidth = 0
    private var intermediateHeight = 0

    // --- Per-frame inputs ---
    private val stMatrix = FloatArray(16)

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

    // ── Opt-in artifact inputs, all rendered live so the viewfinder matches
    //    the saved JPEG — grain, dust, scratches and light leaks included.
    //    Zeroing any of these here re-introduces the WYSIWYG gap where a look
    //    (ccd_digicam ships dust + a light leak) previewed clean but captured
    //    with visible artifacts. ──
    @Volatile private var highlightRolloff = 0f
    @Volatile private var fade = 0f
    @Volatile private var vignette = 1f
    @Volatile private var dust = 0f
    @Volatile private var scratch = 0f
    @Volatile private var lightLeak = 0f
    @Volatile private var grainStrength = 0f
    @Volatile private var grainChroma = 0f
    @Volatile private var grainSize = 0f
    @Volatile private var grainSeed = 0f

    // The active LUT is retained so it can be re-uploaded after an EGL
    // context recreation. The pending value is consumed only on the GL thread.
    @Volatile private var activeLut: CubeLut? = null
    @Volatile private var pendingLut: CubeLut? = null
    // Crossfade blend state (slot B + mix). [lutRevision] bumps on every
    // setLut/setLutBlend so a null assignment (fade to pass-through) is an
    // observable change too — the GL thread applies the slots when the
    // revision differs from [lastAppliedRevision], or when [lutDirty] forces
    // a re-upload after context recreation. [lutMix] 0 = fully slot A.
    @Volatile private var activeLutB: CubeLut? = null
    @Volatile private var pendingLutB: CubeLut? = null
    @Volatile private var lutMix = 0f
    // Per-look LUT strength: how much of the table's grade reaches the pixel.
    // Lives beside the mix rather than in the effect uniforms because it only
    // means anything while a LUT slot is bound.
    @Volatile private var lutStrength = 1f
    @Volatile private var lutRevision = 0
    private var lastAppliedRevision = -1
    private var lutDirty = true

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
    fun setRenderParams(params: RetroRenderParams, grainSeed: Int = 0) {
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
        vignette = params.vignette
        dust = params.dust
        scratch = params.scratch
        lightLeak = params.lightLeak
        grainStrength = params.grainStrength
        grainChroma = params.grainChroma
        grainSize = params.grainSize
        halationStrength = params.halationStrength
        halationRadius = params.halationRadius
        lutStrength = params.lutStrength
        this.grainSeed = grainSeed.toFloat()
        onRequestRender()
    }

    fun setFlipH(value: Boolean) {
        flipH = value
        onRequestRender()
    }

    /**
     * Set the LUT to apply. Pass null to disable LUT grading (WB/exposure only).
     * The upload happens on the GL thread on the next frame.
     */
    fun setLut(lut: CubeLut?) {
        setLutBlend(from = lut, to = null, mix = 0f)
    }

    /**
     * Crossfade entry point: grades with [from] at mix = 0 morphing to [to]
     * at mix = 1. Either side may be null (pass-through), in which case that
     * side contributes the ungraded color. Slot uploads happen on the GL
     * thread on the next frame; a null slot actively disables its side
     * (unlike the old single-slot path, which left a stale LUT bound).
     */
    fun setLutBlend(from: CubeLut?, to: CubeLut?, mix: Float) {
        activeLut = from
        pendingLut = from
        activeLutB = to
        pendingLutB = to
        lutMix = mix.coerceIn(0f, 1f)
        lutRevision++
        onRequestRender()
    }

    fun setSurfaceBufferSize(width: Int, height: Int) {
        surfaceBufferWidth = width
        surfaceBufferHeight = height
    }

    /**
     * Non-blocking accessor for the camera [SurfaceTexture]: null until
     * [onSurfaceCreated] has minted one. Deliberately does NOT wait — the old
     * polling wait ran on the GL thread, the very thread that must run
     * onSurfaceCreated, so when a camera surface request outran the queued
     * init the poll stalled that queue for 2 s and then failed against its
     * own initializer (black viewfinder until the next rebind). Callers that
     * can arrive before init must DEFER instead of block.
     */
    fun peekSurfaceTexture(): SurfaceTexture? = if (surfaceTextureReady) surfaceTexture else null

    // ------------------------------------------------------------------
    // SurfaceTexture.OnFrameAvailableListener

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        onRequestRender()
    }

    // ------------------------------------------------------------------
    // GL lifecycle — driven by LutPreviewView's render thread with its
    // manually-managed EGL context current.

    /**
     * (Re)initializes all GL state. Called whenever the view creates a fresh
     * EGL context (TextureView surface available). Mirrors the old
     * GLSurfaceView onSurfaceCreated contract: the previous camera
     * SurfaceTexture is released and CameraX must re-provide a surface,
     * which the existing rebind path already handles.
     */
    fun onGlContextCreated() {
        // A fresh EGL context invalidates everything from the previous one.
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

        // A context loss invalidates the uploaded 3D textures, but not the
        // immutable CubeLut values held by the UI. Re-upload both blend slots
        // on the first frame of the new context unless a newer request is
        // already pending.
        if (pendingLut == null) pendingLut = activeLut
        if (pendingLutB == null) pendingLutB = activeLutB
        lutDirty = true

        // Pass 1: external OES camera frame -> stable 2D texture.
        copyProgram = createProgram(VERT_SHADER, COPY_FRAGMENT_SHADER)
        require(copyProgram != 0) { "Failed to compile camera copy program" }

        // Optional, so a driver that rejects the shader only loses halation.
        // EFFECT_VERT_SHADER, not VERT_SHADER: the gather reads an already-
        // oriented 2D texture, so it must not go through the OES transform
        // and crop uniforms the copy pass owns.
        halationProgram = createProgram(EFFECT_VERT_SHADER, HALATION_FRAGMENT_SHADER)
        if (halationProgram != 0) {
            halationAPositionLoc = GLES20.glGetAttribLocation(halationProgram, "aPosition")
            halationATexCoordLoc = GLES20.glGetAttribLocation(halationProgram, "aTexCoord")
            halationUTextureLoc = GLES20.glGetUniformLocation(halationProgram, "uTexture")
            halationUTexelLoc = GLES20.glGetUniformLocation(halationProgram, "uTexel")
            halationURadiusLoc = GLES20.glGetUniformLocation(halationProgram, "uRadius")
        } else {
            Log.w(TAG, "Halation blur program failed to compile; halation stays off")
        }

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
        uLut2Loc = GLES20.glGetUniformLocation(program, "uLut2")
        uLut2EnabledLoc = GLES20.glGetUniformLocation(program, "uLut2Enabled")
        uLutMixLoc = GLES20.glGetUniformLocation(program, "uLutMix")
        uLutStrengthLoc = GLES20.glGetUniformLocation(program, "uLutStrength")
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
        uGrainSeedLoc = GLES20.glGetUniformLocation(program, "uGrainSeed")
        uGrainTimeLoc = GLES20.glGetUniformLocation(program, "uGrainTime")
        uGrainCellLoc = GLES20.glGetUniformLocation(program, "uGrainCell")
        uVignetteLoc = GLES20.glGetUniformLocation(program, "uVignette")
        uDustLoc = GLES20.glGetUniformLocation(program, "uDust")
        uScratchLoc = GLES20.glGetUniformLocation(program, "uScratch")
        uLightLeakLoc = GLES20.glGetUniformLocation(program, "uLightLeak")
        uHalationTexLoc = GLES20.glGetUniformLocation(program, "uHalationTex")
        uHalationOnLoc = GLES20.glGetUniformLocation(program, "uHalationOn")
        uHalationStrengthLoc = GLES20.glGetUniformLocation(program, "uHalationStrength")

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

        // LUT textures (3D slots A + B for the crossfade blend). Created up
        // front; uploads happen lazily when setLut/setLutBlend provides one.
        val lutIds = IntArray(2)
        GLES20.glGenTextures(2, lutIds, 0)
        lutTexture = lutIds[0]
        lutTextureB = lutIds[1]

        // Default GL_UNPACK_ALIGNMENT is 4, which only works when rows are a
        // multiple of 4 bytes. A 3D LUT stored as GL_RGB has 3 bytes per texel
        // — for any LUT size n where n * 3 is NOT a multiple of 4 (e.g. the
        // bundled 13-cube LUTs at 39 bytes/row) the driver inserts phantom
        // pad bytes and the next row's channels phase-shift, producing the
        // classic psychedelic rainbow banding. Pin alignment to 1 (no padding)
        // here so subsequent glTexImage3D uploads are always tightly packed.
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
    }

    fun onGlSurfaceChanged(width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        GLES20.glViewport(0, 0, width, height)
        ensureIntermediateTarget(width, height)
        destroyHalationTarget()
    }

    fun drawFrame() {
        // Upload any pending LUT slots on the GL thread. The revision check
        // (not nullness) is what observes a fade-to-null: clearing a slot is
        // a real state change that must unbind that side of the blend.
        if (lutDirty || lutRevision != lastAppliedRevision) {
            applyPendingLuts()
        }

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
        drawHalationPass()
        drawEffectPass()
    }

    /**
     * Renders the quarter-resolution halation gather from the copied frame.
     *
     * No-ops unless a look actually asks for halation: the composite stage is
     * the only consumer, and an always-on pass would burn bandwidth on every
     * look that leaves the knob at 0.
     */
    private fun drawHalationPass() {
        if (halationProgram == 0 || halationStrength <= 0f || halationRadius <= 0f) return
        if (intermediateTexture == 0) return
        val w = (viewWidth / 4).coerceAtLeast(1)
        val h = (viewHeight / 4).coerceAtLeast(1)
        ensureHalationTarget(w, h)
        if (halationFbo == 0) return

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, halationFbo)
        GLES20.glViewport(0, 0, halationWidth, halationHeight)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(halationProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, intermediateTexture)
        GLES20.glUniform1i(halationUTextureLoc, 0)
        GLES20.glUniform2f(
            halationUTexelLoc,
            1f / halationWidth.toFloat(),
            1f / halationHeight.toFloat()
        )
        // Radius is authored in 1080p pixels; convert to target texels so the
        // glow keeps its physical size on a 1440p or 4K frame.
        val radiusTexels = halationRadius * (viewHeight.toFloat() / 1080f) / 4f
        GLES20.glUniform1f(halationURadiusLoc, radiusTexels.coerceAtLeast(0.5f))
        drawQuad(halationAPositionLoc, halationATexCoordLoc, quadTexCoordBuf(false))
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

        // ── Upload opt-in artifact uniforms. Every stage renders live so the
        //    viewfinder matches the saved JPEG — the one RetroRenderParams
        //    snapshot drives all back-ends (see invariant #3 in AGENTS.md).
        //    Dust/scratch/light-leak used to be forced to 0 here as
        //    "capture-only" overlays, which made ccd_digicam's saved photos
        //    visibly differ from the viewfinder.
        //    Grain is the one deliberate exception, and only in how it is
        //    *presented*: the field is re-keyed on a 24fps cadence and run at
        //    PREVIEW_GRAIN_GAIN. A frozen field on a live image reads as a
        //    dirty lens, not as film, and once it moves a reshuffle draws far
        //    more attention than the same density held still — so the
        //    viewfinder runs slightly lighter than the capture, which keeps
        //    the authored strength. Capture keeps both the seed and the
        //    authored amount. ──
        GLES20.glUniform1f(uHighlightRolloffLoc, highlightRolloff)
        GLES20.glUniform1f(uFadeLoc, fade)
        // Re-key the field on a film's cadence rather than once per draw: a
        // 60fps viewfinder would otherwise reshuffle at 60Hz and strobe. Clock
        // time instead of a draw counter keeps the grain speed identical on
        // 24/30/60fps devices and while a slider drag forces extra redraws.
        // The phase is scaled by the golden ratio and wrapped to [0,1) so each
        // step lands far from the last one and the salt always stays
        // fractional (the shader adds it inside fract(); a whole number there
        // would do nothing). Captures never see this — a still is one instant
        // of grain and keeps the deterministic seed from the params.
        val grainStep = (System.nanoTime() / GRAIN_STEP_NANOS) % 251L
        val grainTime = (grainStep * 0.6180339887f) % 1f
        GLES20.glUniform1f(uGrainTimeLoc, grainTime)
        GLES20.glUniform1f(uGrainStrengthLoc, grainStrength * PREVIEW_GRAIN_GAIN)
        GLES20.glUniform1f(uGrainChromaLoc, grainChroma)
        GLES20.glUniform1f(uGrainSeedLoc, grainSeed)
        // Base lattice cell, identical formula to RetroFilter's CPU path so
        // the live viewfinder and saved capture agree on grain size. Scales
        // with min(viewport) so grain keeps a constant apparent size; coerced
        // to ≥1px like imageonline.
        val grainCell = if (viewWidth > 0 && viewHeight > 0) {
            ((1f + grainSize * 9f) * minOf(viewWidth, viewHeight) / 1800f).coerceAtLeast(1f)
        } else {
            1f
        }
        GLES20.glUniform1f(uGrainCellLoc, grainCell)

        // ── Halation ──
        // The blur pass ran earlier this frame; bind its target when the look
        // asked for it and the target came up. Unit 4 keeps the LUT slots (1,
        // 2) and the grain plate (3) untouched.
        val halationOn = halationStrength > 0f && halationFbo != 0 && halationTexture != 0
        GLES20.glUniform1i(uHalationOnLoc, if (halationOn) 1 else 0)
        GLES20.glUniform1f(uHalationStrengthLoc, halationStrength)
        if (halationOn) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE4)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, halationTexture)
            GLES20.glUniform1i(uHalationTexLoc, 4)
        }
        GLES20.glUniform1f(uVignetteLoc, vignette)
        GLES20.glUniform1f(uDustLoc, dust)
        GLES20.glUniform1f(uScratchLoc, scratch)
        GLES20.glUniform1f(uLightLeakLoc, lightLeak)

        // Dual-slot LUT blend for the look crossfade. Each present slot is
        // bound to its own texture unit; absent sides fall back to ungraded
        // c inside the shader. mix = 0 reproduces the old single-LUT path
        // exactly, so still captures (which never set slot B) are unchanged.
        val aOn = has3dTextures && lutEnabled && lutWidth > 0
        val bOn = has3dTextures && lutBEnabled && lutWidthB > 0
        if (aOn) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
            GLES20.glUniform1i(uLutLoc, 1)
        }
        if (bOn) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureB)
            GLES20.glUniform1i(uLut2Loc, 2)
        }
        GLES20.glUniform1i(uLutEnabledLoc, if (aOn) 1 else 0)
        GLES20.glUniform1i(uLut2EnabledLoc, if (bOn) 1 else 0)
        GLES20.glUniform1f(uLutMixLoc, lutMix)
        GLES20.glUniform1f(uLutStrengthLoc, lutStrength)

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

    private fun ensureHalationTarget(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (halationFbo != 0 && halationWidth == width && halationHeight == height) return
        destroyHalationTarget()

        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        halationTexture = textureIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, halationTexture)
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
        halationFbo = fboIds[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, halationFbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            halationTexture,
            0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "Halation FBO is incomplete: 0x${Integer.toHexString(status)}")
            destroyHalationTarget()
            return
        }
        halationWidth = width
        halationHeight = height
    }

    private fun destroyHalationTarget() {
        if (halationFbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(halationFbo), 0)
        }
        if (halationTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(halationTexture), 0)
        }
        halationFbo = 0
        halationTexture = 0
        halationWidth = 0
        halationHeight = 0
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
        destroyHalationTarget()
        if (inputTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(inputTexture), 0)
        }
        if (lutTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(lutTexture), 0)
        }
        if (lutTextureB != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(lutTextureB), 0)
        }
        if (copyProgram != 0) GLES20.glDeleteProgram(copyProgram)
        if (halationProgram != 0) GLES20.glDeleteProgram(halationProgram)
        if (program != 0) GLES20.glDeleteProgram(program)
        halationProgram = 0
        copyProgram = 0
        inputTexture = 0
        lutTexture = 0
        lutTextureB = 0
        lutWidth = 0
        lutWidthB = 0
        lutEnabled = false
        lutBEnabled = false
        copyProgram = 0
        program = 0
        has3dTextures = false
    }

    /** Applies both pending blend slots; consumes pendingLut/pendingLutB. */
    private fun applyPendingLuts() {
        val a = pendingLut
        pendingLut = null
        if (a != null) {
            lutWidth = uploadLutToSlot(a, lutTexture)
            lutEnabled = lutWidth > 0
        } else {
            lutWidth = 0
            lutEnabled = false
        }
        val b = pendingLutB
        pendingLutB = null
        if (b != null) {
            lutWidthB = uploadLutToSlot(b, lutTextureB)
            lutBEnabled = lutWidthB > 0
        } else {
            lutWidthB = 0
            lutBEnabled = false
        }
        lastAppliedRevision = lutRevision
        lutDirty = false
    }

    /**
     * Uploads [lut] into [textureId] as an 8-bit RGB 3D texture and returns
     * the uploaded lattice size (0 when 3D textures are unsupported). Slots
     * may hold different LUT sizes — each texture filters independently and
     * the shader mixes the two graded colors, never the lattices.
     */
    private fun uploadLutToSlot(lut: CubeLut, textureId: Int): Int {
        if (!has3dTextures) return 0  // GPU doesn't support 3D textures
        // Convert float RGB samples to 8-bit (the camera input is 8-bit anyway).
        val n = lut.size
        val buf = ByteBuffer.allocateDirect(n * n * n * 3).order(ByteOrder.nativeOrder())
        for (v in lut.data) {
            buf.put((v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
        }
        buf.position(0)

        GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, textureId)
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
        return n
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

        // Viewfinder grain runs slightly lighter than the capture: the field
        // is re-keyed continuously (see drawEffectPass), and a moving field of
        // the same amplitude reads much stronger than a still one.
        private const val PREVIEW_GRAIN_GAIN = 0.6f

        // One grain step per 24fps film frame. Nanoseconds rather than a draw
        // counter so the cadence does not ride the viewfinder's frame rate.
        private const val GRAIN_STEP_NANOS = 41_666_667L

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

        /**
         * Quarter-resolution halation gather: a 17-tap two-ring blur over the
         * copied frame. One pass is enough because the target is already a
         * quarter of the frame — a separable pair would cost more draws for a
         * glow that is deliberately soft.
         *
         * Each tap is highlight-thresholded *before* it enters the sum. That
         * order is the whole effect: masking the blurred result would only
         * brighten pixels that were already bright, while thresholding first
         * lets a bright region's light bleed out into its dark neighbours —
         * the orange fringe a bright edge gets inside the film base. Weights
         * sum to 1, so a uniformly bright frame keeps its own level.
         */
        internal const val HALATION_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec2 uTexel;
            uniform float uRadius;
            varying vec2 vTexCoord;

            vec3 highlight(vec2 uv) {
                vec3 c = texture2D(uTexture, uv).rgb;
                float luma = dot(c, vec3(0.299, 0.587, 0.114));
                return c * smoothstep(0.45, 0.95, luma);
            }

            void main() {
                vec3 sum = highlight(vTexCoord) * 0.18;
                for (int i = 0; i < 8; i++) {
                    float a = float(i) * 0.7853981634;
                    vec2 dir = vec2(cos(a), sin(a));
                    sum += highlight(vTexCoord + dir * uRadius * uTexel) * 0.07;
                    sum += highlight(vTexCoord + dir * uRadius * 0.55 * uTexel) * 0.0325;
                }
                gl_FragColor = vec4(sum, 1.0);
            }
        """

        // Fragment shader: sample camera → apply WB + exposure + film effects
        // + vignette + optional LUT.
        // Uses GL_OES_EGL_image_external for the camera texture and
        // GL_OES_texture_3D for the LUT. Vignette math matches the CPU
        // applyRetroFilter RadialGradient.
        internal const val FRAG_SHADER = """
            #extension GL_OES_texture_3D : enable
            // highp matters for the grain stage: gl_FragCoord up to ~1080 at
            // mediump loses its fractional part (10-bit mantissa steps by 1
            // at 1024), quantizing the noise lattice phase into visible
            // blocks. highp keeps sub-pixel positions exact. Fall back only
            // where the GPU truly lacks fragment highp.
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            precision mediump sampler3D;
            uniform sampler2D uTexture;
            uniform mediump sampler3D uLut;
            uniform mediump sampler3D uLut2;
            uniform float uTemperature;
            uniform float uTint;
            uniform float uExposure;
            uniform int uLutEnabled;
            uniform int uLut2Enabled;
            uniform float uLutMix;
            uniform float uLutStrength;
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
            uniform float uGrainSeed;
            uniform float uGrainTime;
            uniform float uGrainCell;
            uniform sampler2D uHalationTex;
            uniform int uHalationOn;
            uniform float uHalationStrength;
            uniform float uVignette;
            uniform float uDust;
            uniform float uScratch;
            uniform float uLightLeak;

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

            // ── Portable float hash + value noise ──
            // Sin-free fract-multiply hash (hash12); stays in lock-step
            // with hashF() in RetroFilter.kt for dust and scratches. Grain
            // uses the seeded cellHash/fbmGrain below so each capture re-keys
            // its field; the CPU pairs an integer cellHash with the same
            // octaves and weights, giving statistical (not bit-exact) parity.
            float hash(vec2 p) {
                vec3 p3 = fract(vec3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }
            float smootherstepNoise(float t) {
                return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
            }
            float valueNoise(vec2 p) {
                vec2 i = floor(p);
                vec2 f = fract(p);
                vec2 s = vec2(smootherstepNoise(f.x), smootherstepNoise(f.y));
                float n00 = hash(i);
                float n10 = hash(i + vec2(1.0, 0.0));
                float n01 = hash(i + vec2(0.0, 1.0));
                float n11 = hash(i + vec2(1.0, 1.0));
                float nx0 = mix(n00, n10, s.x);
                float nx1 = mix(n01, n11, s.x);
                return mix(nx0, nx1, s.y);
            }
            // ── Seeded lattice-cell hash for the fBm grain ──
            // GPU cousin of RetroFilter's integer cellHash: same signed
            // [-1,1] range and per-lattice-point sampling, but a float
            // fract-multiply mix (integer hashing is not portable/reliable
            // in GLES2). Back-ends are statistically, not bit-exact, parity
            // — matching the established grain contract. Under the highp
            // precision declared above the hash keeps full resolution, so
            // the interpolated lattice never quantizes into visible
            // terraces; the modulo bounds the lattice and the wrapped seed
            // re-keys the field each capture.
            float cellHash(vec2 i, float seed) {
                float s = mod(seed, 256.0);
                vec2 q = mod(i + s, 256.0);
                // uGrainTime re-keys every lattice value for the live preview
                // without touching the authored seed, which keeps meaning
                // "which look/capture" while time rides its own uniform. It
                // must stay fractional: fract(x + n) == fract(x), so a whole
                // number added here would do nothing at all. Captures pass 0
                // and stay deterministic.
                vec3 p3 = fract(vec3(q.xyx) * vec3(0.1031, 0.1030, 0.0973) + s * 0.000091 + uGrainTime);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z) * 2.0 - 1.0;
            }
            // One octave of smoothstep-faded bilinear value noise in
            // [-1,1]. The 3rd-order fade t*t*(3-2t) matches the CPU's
            // valueOctave exactly so both back-ends ease cells into their
            // neighbours the same way (no seams / byte terracing).
            float valueOctave(vec2 p, float cell, float seed) {
                vec2 f = p / cell;
                vec2 i = floor(f);
                vec2 u = f - i;
                vec2 s = vec2(u.x * u.x * (3.0 - 2.0 * u.x),
                             u.y * u.y * (3.0 - 2.0 * u.y));
                float n00 = cellHash(i, seed);
                float n10 = cellHash(i + vec2(1.0, 0.0), seed);
                float n01 = cellHash(i + vec2(0.0, 1.0), seed);
                float n11 = cellHash(i + vec2(1.0, 1.0), seed);
                float nx0 = mix(n00, n10, s.x);
                float nx1 = mix(n01, n11, s.x);
                return mix(nx0, nx1, s.y);
            }
            // 3-octave fBm grain field at one fragment (matches the CPU's
            // fbmGrain): lattice cells cell, cell/2, cell/4 weighted 1.0,
            // 0.5, 0.3 (imageonline's grainField). Per-octave seed offsets
            // (+97, +193) decorrelate the octaves so they don't stack into
            // a lattice; the seed already folds in 4*grainSeed + channel.
            // Value noise aliases once its lattice drops below ~2px: a 0.5px
            // lattice sampled at 1px beats at ~24px, which is the large
            // low-frequency Moire blocking this stage showed on-screen.
            // Fading each octave out as it nears the pixel Nyquist limit
            // keeps only resolvable detail — the procedural equivalent of
            // mip-filtering. The base octave is always >=1px (grainCell is
            // clamped), so it stays at full weight.
            float octaveFade(float cell) {
                return smoothstep(1.0, 2.0, cell);
            }
            float fbmGrain(vec2 p, float cell, float seed) {
                float total = valueOctave(p, cell, seed);
                // Skip octaves that are fully faded out: on a low-res live
                // preview their cells are sub-pixel, so this drops the three
                // taps to one and cuts the per-fragment hash cost ~3x. cell
                // is a uniform, so the branch is coherent across the draw.
                float f2 = octaveFade(cell * 0.5);
                if (f2 > 0.0) total += 0.5 * f2 * valueOctave(p, cell * 0.5, seed + 97.0);
                float f3 = octaveFade(cell * 0.25);
                if (f3 > 0.0) total += 0.3 * f3 * valueOctave(p, cell * 0.25, seed + 193.0);
                return total;
            }
            // ── Procedural overlays: dust, scratches, light leak ──
            float dustAmount(vec2 p) {
                float cell = 64.0;
                vec2 id = floor(p / cell);
                vec2 uv = fract(p / cell);
                if (hash(id) < 0.62) return 0.0;
                float cx = hash(id + vec2(13.7, 0.0));
                float cy = hash(id + vec2(0.0, 57.1));
                float rad = 0.05 + hash(id + vec2(23.3, 91.7)) * 0.14;
                float d = length(uv - vec2(cx, cy));
                float mask = 1.0 - smoothstep(rad * 0.4, rad, d);
                return mask * (0.6 + hash(id + vec2(71.9, 3.1)) * 0.4);
            }
            float scratchAmount(vec2 p) {
                float result = 0.0;
                for (int i = 0; i < 5; i++) {
                    float band = float(i);
                    float sx = hash(vec2(band, 3.3));
                    float present = step(0.5, hash(vec2(band, 9.1)));
                    float dx = abs(p.x - sx * uViewSize.x);
                    float widthPx = 1.0 + hash(vec2(band, 7.7)) * 4.0;
                    float line = 1.0 - smoothstep(widthPx * 0.3, widthPx, dx);
                    float flicker = 0.6 + 0.4 * hash(vec2(floor(p.y / 10.0), band));
                    result = max(result, present * line * flicker);
                }
                return result;
            }
            vec3 lightLeakColor(vec2 p) {
                vec2 ndc = (p - uViewSize * 0.5) / (uViewSize * 0.5);
                float tl = 1.0 - smoothstep(0.15, 1.5, length(ndc + vec2(1.0, 1.0)));
                float br = 1.0 - smoothstep(0.15, 1.5, length(ndc - vec2(1.0, 1.0)));
                float m = clamp(tl * 0.75 + br * 0.4, 0.0, 1.0);
                return vec3(1.0, 0.55, 0.22) * m;
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
                // channel misregistration in instant films.
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
                // Warm glow around bright highlights (warm negative glow, etc.)
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
                t = clamp(t * uVignette, 0.0, 1.0);
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

                // ── 8.7. Film grain (procedural 3-octave fBm) ──
                // Tent mid-tone weighting + imageonline amplitude, matching
                // RetroFilter's CPU path byte-for-byte in formulation. The
                // field is interpolated value noise (smooth clumps) rather
                // than per-pixel white, and rounds to bytes like the CPU so
                // faint grain keeps an even density without terracing.
                if (uGrainStrength > 0.0) {
                    float lum = dot(c.rgb, vec3(0.299, 0.587, 0.114));
                    float weight = 0.35 + 0.65 * (1.0 - abs(2.0 * lum - 1.0));
                    // Normalised amplitude (CPU works in bytes: amount/100·255·0.3,
                    // so /255 here).
                    float amp = uGrainStrength * 0.3 * weight;
                    float seed0 = uGrainSeed * 4.0;
                    // Derive the position from the highp vTexCoord varying, not
                    // gl_FragCoord (which is mediump in GLSL ES 1.00 and loses
                    // its fraction past ~1024 — that quantized the noise lattice
                    // into visible blocks). vTexCoord*uViewSize is the fragment's
                    // pixel position in the copied frame.
                    vec2 grainPos = vTexCoord * uViewSize;
                    float mono = fbmGrain(grainPos, uGrainCell, seed0);
                    float dr = mono * amp;
                    float dg = mono * amp;
                    float db = mono * amp;
                    if (uGrainChroma > 0.0) {
                        // Independent per-channel fBm fields (dye-cloud / sensor
                        // speckle), salted to match the CPU so both back-ends
                        // decorrelate R/G/B identically.
                        dr += fbmGrain(grainPos, uGrainCell, seed0 + 1.0) * amp * uGrainChroma;
                        dg += fbmGrain(grainPos, uGrainCell, seed0 + 2.0) * amp * uGrainChroma;
                        db += fbmGrain(grainPos, uGrainCell, seed0 + 3.0) * amp * uGrainChroma;
                    }
                    c.r += floor(dr * 255.0 + 0.5) / 255.0;
                    c.g += floor(dg * 255.0 + 0.5) / 255.0;
                    c.b += floor(db * 255.0 + 0.5) / 255.0;
                    c = clamp(c, 0.0, 1.0);
                }

                // ── 9. 3D LUT (dual-slot crossfade blend) ──
                // Slot A holds the outgoing look, slot B the incoming one;
                // uLutMix 0→1 morphs between the two graded colors. An absent
                // side contributes the ungraded color, so fades to/from the
                // pass-through look dissolve the grade instead of popping.
                // mix = 0 with only slot A present is the legacy path, which
                // is also what still captures use (they never bind slot B).
                // uLutStrength then blends the whole graded result back toward
                // the ungraded color, which is the knob Dazz exposes as the
                // lookup's `intensity`: 1 = full grade, 0 = table bound but
                // inert. Applied after the crossfade so a fade never doubles
                // as a strength ramp.
                if (uLutEnabled == 1 || uLut2Enabled == 1) {
                    vec3 gradedA = (uLutEnabled == 1) ? texture3D(uLut, c).rgb : c;
                    vec3 gradedB = (uLut2Enabled == 1) ? texture3D(uLut2, c).rgb : c;
                    vec3 graded = mix(gradedA, gradedB, clamp(uLutMix, 0.0, 1.0));
                    c = mix(c, graded, clamp(uLutStrength, 0.0, 1.0));
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

                // ── 10.5. Spatial halation ──
                // A blurred copy of the frame's highlights, warm-tinted and
                // screen-blended back: light scattered in the film base
                // spreads into *neighbours*, which no per-pixel stage can do.
                // Runs after the wash rather than before it so the glow keeps
                // its own colour instead of being tinted by the milky haze,
                // and so the CPU filter (which composites it at its pixel
                // write) lands on the same result.
                if (uHalationOn == 1 && uHalationStrength > 0.0) {
                    vec3 halo = texture2D(uHalationTex, vTexCoord).rgb;
                    vec3 glow = halo * vec3(1.0, 0.52, 0.38) * (uHalationStrength * 0.9);
                    c += glow * (1.0 - c);
                    c = clamp(c, 0.0, 1.0);
                }

                // ── 11. Procedural overlays (dust, scratches, light leak) ──
                // Subtractive, on top of the graded image, in both preview and
                // capture — a look that ships dust must show it while framing.
                // The mask is sparse (most lattice cells carry no speck), so the
                // strength knob is what makes the dirt read heavier or lighter.
                if (uDust > 0.0) {
                    c.rgb -= vec3(dustAmount(gl_FragCoord.xy) * uDust * 0.45);
                }
                if (uScratch > 0.0) {
                    c.rgb -= vec3(scratchAmount(gl_FragCoord.xy) * uScratch * 0.55);
                }
                if (uLightLeak > 0.0) {
                    c.rgb += lightLeakColor(gl_FragCoord.xy) * uLightLeak * 0.35;
                }
                c = clamp(c, 0.0, 1.0);

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
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
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
            uniform float uGrainSeed;
            uniform float uGrainTime;
            uniform float uGrainCell;
            uniform sampler2D uHalationTex;
            uniform int uHalationOn;
            uniform float uHalationStrength;
            uniform float uVignette;
            uniform float uDust;
            uniform float uScratch;
            uniform float uLightLeak;

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

            // ── Portable float hash + value noise ──
            // Sin-free fract-multiply hash (hash12); stays in lock-step
            // with hashF() in RetroFilter.kt for dust and scratches. Grain
            // uses the seeded cellHash/fbmGrain below so each capture re-keys
            // its field; the CPU pairs an integer cellHash with the same
            // octaves and weights, giving statistical (not bit-exact) parity.
            float hash(vec2 p) {
                vec3 p3 = fract(vec3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z);
            }
            float smootherstepNoise(float t) {
                return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
            }
            float valueNoise(vec2 p) {
                vec2 i = floor(p);
                vec2 f = fract(p);
                vec2 s = vec2(smootherstepNoise(f.x), smootherstepNoise(f.y));
                float n00 = hash(i);
                float n10 = hash(i + vec2(1.0, 0.0));
                float n01 = hash(i + vec2(0.0, 1.0));
                float n11 = hash(i + vec2(1.0, 1.0));
                float nx0 = mix(n00, n10, s.x);
                float nx1 = mix(n01, n11, s.x);
                return mix(nx0, nx1, s.y);
            }
            // ── Seeded lattice-cell hash for the fBm grain ──
            // GPU cousin of RetroFilter's integer cellHash: same signed
            // [-1,1] range and per-lattice-point sampling, but a float
            // fract-multiply mix (integer hashing is not portable/reliable
            // in GLES2). Back-ends are statistically, not bit-exact, parity
            // — matching the established grain contract. Under the highp
            // precision declared above the hash keeps full resolution, so
            // the interpolated lattice never quantizes into visible
            // terraces; the modulo bounds the lattice and the wrapped seed
            // re-keys the field each capture.
            float cellHash(vec2 i, float seed) {
                float s = mod(seed, 256.0);
                vec2 q = mod(i + s, 256.0);
                // uGrainTime re-keys every lattice value for the live preview
                // without touching the authored seed, which keeps meaning
                // "which look/capture" while time rides its own uniform. It
                // must stay fractional: fract(x + n) == fract(x), so a whole
                // number added here would do nothing at all. Captures pass 0
                // and stay deterministic.
                vec3 p3 = fract(vec3(q.xyx) * vec3(0.1031, 0.1030, 0.0973) + s * 0.000091 + uGrainTime);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z) * 2.0 - 1.0;
            }
            // One octave of smoothstep-faded bilinear value noise in
            // [-1,1]. The 3rd-order fade t*t*(3-2t) matches the CPU's
            // valueOctave exactly so both back-ends ease cells into their
            // neighbours the same way (no seams / byte terracing).
            float valueOctave(vec2 p, float cell, float seed) {
                vec2 f = p / cell;
                vec2 i = floor(f);
                vec2 u = f - i;
                vec2 s = vec2(u.x * u.x * (3.0 - 2.0 * u.x),
                             u.y * u.y * (3.0 - 2.0 * u.y));
                float n00 = cellHash(i, seed);
                float n10 = cellHash(i + vec2(1.0, 0.0), seed);
                float n01 = cellHash(i + vec2(0.0, 1.0), seed);
                float n11 = cellHash(i + vec2(1.0, 1.0), seed);
                float nx0 = mix(n00, n10, s.x);
                float nx1 = mix(n01, n11, s.x);
                return mix(nx0, nx1, s.y);
            }
            // 3-octave fBm grain field at one fragment (matches the CPU's
            // fbmGrain): lattice cells cell, cell/2, cell/4 weighted 1.0,
            // 0.5, 0.3 (imageonline's grainField). Per-octave seed offsets
            // (+97, +193) decorrelate the octaves so they don't stack into
            // a lattice; the seed already folds in 4*grainSeed + channel.
            // Value noise aliases once its lattice drops below ~2px: a 0.5px
            // lattice sampled at 1px beats at ~24px, which is the large
            // low-frequency Moire blocking this stage showed on-screen.
            // Fading each octave out as it nears the pixel Nyquist limit
            // keeps only resolvable detail — the procedural equivalent of
            // mip-filtering. The base octave is always >=1px (grainCell is
            // clamped), so it stays at full weight.
            float octaveFade(float cell) {
                return smoothstep(1.0, 2.0, cell);
            }
            float fbmGrain(vec2 p, float cell, float seed) {
                float total = valueOctave(p, cell, seed);
                // Skip octaves that are fully faded out: on a low-res live
                // preview their cells are sub-pixel, so this drops the three
                // taps to one and cuts the per-fragment hash cost ~3x. cell
                // is a uniform, so the branch is coherent across the draw.
                float f2 = octaveFade(cell * 0.5);
                if (f2 > 0.0) total += 0.5 * f2 * valueOctave(p, cell * 0.5, seed + 97.0);
                float f3 = octaveFade(cell * 0.25);
                if (f3 > 0.0) total += 0.3 * f3 * valueOctave(p, cell * 0.25, seed + 193.0);
                return total;
            }
            // ── Procedural overlays: dust, scratches, light leak ──
            float dustAmount(vec2 p) {
                float cell = 64.0;
                vec2 id = floor(p / cell);
                vec2 uv = fract(p / cell);
                if (hash(id) < 0.62) return 0.0;
                float cx = hash(id + vec2(13.7, 0.0));
                float cy = hash(id + vec2(0.0, 57.1));
                float rad = 0.05 + hash(id + vec2(23.3, 91.7)) * 0.14;
                float d = length(uv - vec2(cx, cy));
                float mask = 1.0 - smoothstep(rad * 0.4, rad, d);
                return mask * (0.6 + hash(id + vec2(71.9, 3.1)) * 0.4);
            }
            float scratchAmount(vec2 p) {
                float result = 0.0;
                for (int i = 0; i < 5; i++) {
                    float band = float(i);
                    float sx = hash(vec2(band, 3.3));
                    float present = step(0.5, hash(vec2(band, 9.1)));
                    float dx = abs(p.x - sx * uViewSize.x);
                    float widthPx = 1.0 + hash(vec2(band, 7.7)) * 4.0;
                    float line = 1.0 - smoothstep(widthPx * 0.3, widthPx, dx);
                    float flicker = 0.6 + 0.4 * hash(vec2(floor(p.y / 10.0), band));
                    result = max(result, present * line * flicker);
                }
                return result;
            }
            vec3 lightLeakColor(vec2 p) {
                vec2 ndc = (p - uViewSize * 0.5) / (uViewSize * 0.5);
                float tl = 1.0 - smoothstep(0.15, 1.5, length(ndc + vec2(1.0, 1.0)));
                float br = 1.0 - smoothstep(0.15, 1.5, length(ndc - vec2(1.0, 1.0)));
                float m = clamp(tl * 0.75 + br * 0.4, 0.0, 1.0);
                return vec3(1.0, 0.55, 0.22) * m;
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
                t = clamp(t * uVignette, 0.0, 1.0);
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

                // ── 8.7. Film grain (procedural 3-octave fBm) ──
                // Tent mid-tone weighting + imageonline amplitude, matching
                // RetroFilter's CPU path byte-for-byte in formulation. The
                // field is interpolated value noise (smooth clumps) rather
                // than per-pixel white, and rounds to bytes like the CPU so
                // faint grain keeps an even density without terracing.
                if (uGrainStrength > 0.0) {
                    float lum = dot(c.rgb, vec3(0.299, 0.587, 0.114));
                    float weight = 0.35 + 0.65 * (1.0 - abs(2.0 * lum - 1.0));
                    // Normalised amplitude (CPU works in bytes: amount/100·255·0.3,
                    // so /255 here).
                    float amp = uGrainStrength * 0.3 * weight;
                    float seed0 = uGrainSeed * 4.0;
                    // Derive the position from the highp vTexCoord varying, not
                    // gl_FragCoord (which is mediump in GLSL ES 1.00 and loses
                    // its fraction past ~1024 — that quantized the noise lattice
                    // into visible blocks). vTexCoord*uViewSize is the fragment's
                    // pixel position in the copied frame.
                    vec2 grainPos = vTexCoord * uViewSize;
                    float mono = fbmGrain(grainPos, uGrainCell, seed0);
                    float dr = mono * amp;
                    float dg = mono * amp;
                    float db = mono * amp;
                    if (uGrainChroma > 0.0) {
                        // Independent per-channel fBm fields (dye-cloud / sensor
                        // speckle), salted to match the CPU so both back-ends
                        // decorrelate R/G/B identically.
                        dr += fbmGrain(grainPos, uGrainCell, seed0 + 1.0) * amp * uGrainChroma;
                        dg += fbmGrain(grainPos, uGrainCell, seed0 + 2.0) * amp * uGrainChroma;
                        db += fbmGrain(grainPos, uGrainCell, seed0 + 3.0) * amp * uGrainChroma;
                    }
                    c.r += floor(dr * 255.0 + 0.5) / 255.0;
                    c.g += floor(dg * 255.0 + 0.5) / 255.0;
                    c.b += floor(db * 255.0 + 0.5) / 255.0;
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

                // ── 10.5. Spatial halation ──
                // A blurred copy of the frame's highlights, warm-tinted and
                // screen-blended back: light scattered in the film base
                // spreads into *neighbours*, which no per-pixel stage can do.
                // Runs after the wash rather than before it so the glow keeps
                // its own colour instead of being tinted by the milky haze,
                // and so the CPU filter (which composites it at its pixel
                // write) lands on the same result.
                if (uHalationOn == 1 && uHalationStrength > 0.0) {
                    vec3 halo = texture2D(uHalationTex, vTexCoord).rgb;
                    vec3 glow = halo * vec3(1.0, 0.52, 0.38) * (uHalationStrength * 0.9);
                    c += glow * (1.0 - c);
                    c = clamp(c, 0.0, 1.0);
                }

                // ── 11. Procedural overlays (dust, scratches, light leak) ──
                // Subtractive, on top of the graded image, in both preview and
                // capture — a look that ships dust must show it while framing.
                // The mask is sparse (most lattice cells carry no speck), so the
                // strength knob is what makes the dirt read heavier or lighter.
                if (uDust > 0.0) {
                    c.rgb -= vec3(dustAmount(gl_FragCoord.xy) * uDust * 0.45);
                }
                if (uScratch > 0.0) {
                    c.rgb -= vec3(scratchAmount(gl_FragCoord.xy) * uScratch * 0.55);
                }
                if (uLightLeak > 0.0) {
                    c.rgb += lightLeakColor(gl_FragCoord.xy) * uLightLeak * 0.35;
                }
                c = clamp(c, 0.0, 1.0);

                gl_FragColor = vec4(c, 1.0);
            }
        """
    }
}
