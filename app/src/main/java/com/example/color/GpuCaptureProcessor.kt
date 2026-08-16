package com.example.color

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs the same film-effect fragment shader as the live preview over a decoded
 * bitmap, using an offscreen EGL PBuffer context, and returns the graded
 * result as a new [Bitmap].
 *
 * This is the GPU still-capture counterpart to [LutPreviewRenderer]. It reuses
 * the exact shader sources ([LutPreviewRenderer.FRAG_SHADER] /
 * [LutPreviewRenderer.FRAG_SHADER_NO_3D] / [LutPreviewRenderer.EFFECT_VERT_SHADER])
 * so preview and capture can never drift apart, and it applies film grain (a
 * capture-time finish the live viewfinder intentionally omits).
 *
 * Safety contract: [process] returns `null` on *any* EGL/shader/readback
 * failure instead of throwing, so the caller always has the CPU pipeline as a
 * fallback. It is still device-dependent code and should be validated on
 * real Adreno/Mali hardware before being enabled in production.
 */
class GpuCaptureProcessor {

    fun process(
        source: Bitmap,
        params: RetroRenderParams,
        lut: CubeLut? = null
    ): Bitmap? {
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) return null

        var display: EGLDisplay? = null
        var context: EGLContext? = null
        var surface: EGLSurface? = null
        return try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY) ?: return null
            if (display == EGL14.EGL_NO_DISPLAY) return null
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null

            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
                numConfigs[0] <= 0
            ) return null
            val config = configs[0] ?: return null

            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (context == null || context == EGL14.EGL_NO_CONTEXT) return null

            val surfaceAttribs = intArrayOf(
                EGL14.EGL_WIDTH, w,
                EGL14.EGL_HEIGHT, h,
                EGL14.EGL_NONE
            )
            surface = EGL14.eglCreatePbufferSurface(display, config, surfaceAttribs, 0)
            if (surface == null || surface == EGL14.EGL_NO_SURFACE) return null

            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null
            render(source, params, lut)
        } catch (e: Exception) {
            Log.e(TAG, "GPU capture failed; CPU fallback will be used", e)
            null
        } finally {
            try {
                if (display != null) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (surface != null) EGL14.eglDestroySurface(display, surface)
                    if (context != null) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
            } catch (_: Exception) {
                // Best-effort teardown; never mask the original result.
            }
        }
    }

    private fun render(source: Bitmap, params: RetroRenderParams, lut: CubeLut?): Bitmap? {
        val w = source.width
        val h = source.height

        var program = compileProgram(LutPreviewRenderer.EFFECT_VERT_SHADER, LutPreviewRenderer.FRAG_SHADER)
        val has3d = program != 0
        if (!has3d) {
            Log.w(TAG, "GL_OES_texture_3D unsupported; GPU capture uses the no-LUT shader")
            program = compileProgram(LutPreviewRenderer.EFFECT_VERT_SHADER, LutPreviewRenderer.FRAG_SHADER_NO_3D)
        }
        if (program == 0) return null

        val aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        val aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        if (aPosition < 0 || aTexCoord < 0) return null

        val uTexture = GLES20.glGetUniformLocation(program, "uTexture")
        val uViewSize = GLES20.glGetUniformLocation(program, "uViewSize")
        val uLut = GLES20.glGetUniformLocation(program, "uLut")
        val uLutEnabled = GLES20.glGetUniformLocation(program, "uLutEnabled")

        // ── Input texture (manual upload so orientation is deterministic) ──
        // The bitmap is converted top-down into RGBA and uploaded so buffer
        // row 0 becomes texture row 0 (the GL bottom-left). Drawing the quad
        // with identity texcoords then puts the image upright in the FBO, and
        // glReadPixels returns rows top-down — no extra flip is required.
        val inputTex = IntArray(1)
        GLES20.glGenTextures(1, inputTex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTex[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)

        val srcPixels = IntArray(w * h)
        source.getPixels(srcPixels, 0, w, 0, 0, w, h)
        val rgba = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        for (p in srcPixels) {
            rgba.put(((p ushr 16) and 0xFF).toByte())
            rgba.put(((p ushr 8) and 0xFF).toByte())
            rgba.put((p and 0xFF).toByte())
            rgba.put(((p ushr 24) and 0xFF).toByte())
        }
        rgba.position(0)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgba
        )

        // ── Optional 3D LUT ──
        var lutTexture = 0
        if (has3d && lut != null) {
            lutTexture = uploadLut(lut)
        }

        // ── Output FBO ──
        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        val outputTex = IntArray(1)
        GLES20.glGenTextures(1, outputTex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, outputTex[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, outputTex[0], 0
        )
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "GPU capture FBO incomplete")
            return null
        }

        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTex[0])
        if (uTexture >= 0) GLES20.glUniform1i(uTexture, 0)
        if (uViewSize >= 0) GLES20.glUniform2f(uViewSize, w.toFloat(), h.toFloat())

        setFloatUniform(program, "uTemperature", params.temperature)
        setFloatUniform(program, "uTint", params.tint)
        setFloatUniform(program, "uExposure", params.exposure)
        setFloatUniform(program, "uFilmCurve", params.filmCurve)
        setFloatUniform(program, "uContrast", params.contrast)
        setFloatUniform(program, "uSaturation", params.saturation)
        setFloatUniform(program, "uBloomStrength", params.bloom)
        setFloatUniform(program, "uFringing", params.fringing)
        setFloatUniform(program, "uShadowTintR", params.shadowTintR)
        setFloatUniform(program, "uShadowTintG", params.shadowTintG)
        setFloatUniform(program, "uShadowTintB", params.shadowTintB)
        setFloatUniform(program, "uShadowTintStrength", params.shadowTintStrength)
        setFloatUniform(program, "uHighlightTintR", params.highlightTintR)
        setFloatUniform(program, "uHighlightTintG", params.highlightTintG)
        setFloatUniform(program, "uHighlightTintB", params.highlightTintB)
        setFloatUniform(program, "uHighlightTintStrength", params.highlightTintStrength)
        setFloatUniform(program, "uSoftFocus", params.softFocus)
        setFloatUniform(program, "uMilkyMix", params.milkyMix)
        setFloatUniform(program, "uMilkyTintR", params.milkyTintR)
        setFloatUniform(program, "uMilkyTintG", params.milkyTintG)
        setFloatUniform(program, "uMilkyTintB", params.milkyTintB)
        setFloatUniform(program, "uHighlightRolloff", params.highlightRolloff)
        setFloatUniform(program, "uFade", params.fade)
        // Grain is a capture-time finish; the live preview leaves it at 0.
        setFloatUniform(program, "uGrainStrength", params.grainStrength)
        setFloatUniform(program, "uGrainChroma", params.grainChroma)

        if (has3d && lutTexture != 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
            if (uLut >= 0) GLES20.glUniform1i(uLut, 1)
            if (uLutEnabled >= 0) GLES20.glUniform1i(uLutEnabled, 1)
        } else if (uLutEnabled >= 0) {
            GLES20.glUniform1i(uLutEnabled, 0)
        }

        drawQuad(aPosition, aTexCoord)

        // ── Readback ──
        GLES20.glFinish()
        val readback = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readback)
        readback.position(0)
        val bytes = ByteArray(w * h * 4)
        readback.get(bytes)

        val out = IntArray(w * h)
        for (i in 0 until w * h) {
            val r = bytes[i * 4].toInt() and 0xFF
            val g = bytes[i * 4 + 1].toInt() and 0xFF
            val b = bytes[i * 4 + 2].toInt() and 0xFF
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        // Cleanup GL resources (the EGL surface/context are torn down by the
        // caller's finally block).
        GLES20.glDeleteFramebuffers(1, fbo, 0)
        GLES20.glDeleteTextures(1, outputTex, 0)
        GLES20.glDeleteTextures(1, inputTex, 0)
        if (lutTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(lutTexture), 0)
        GLES20.glDeleteProgram(program)

        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Uploads a [CubeLut] as an 8-bit RGB 3D texture (mirrors the preview upload). */
    private fun uploadLut(lut: CubeLut): Int {
        val n = lut.size
        val buf = ByteBuffer.allocateDirect(n * n * n * 3).order(ByteOrder.nativeOrder())
        for (v in lut.data) {
            buf.put((v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
        }
        buf.position(0)

        val id = IntArray(1)
        GLES20.glGenTextures(1, id, 0)
        GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, id[0])
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES20.GL_RGB,
            n, n, n, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, buf
        )
        return id[0]
    }

    private fun setFloatUniform(program: Int, name: String, value: Float) {
        val loc = GLES20.glGetUniformLocation(program, name)
        if (loc >= 0) GLES20.glUniform1f(loc, value)
    }

    private fun drawQuad(aPosition: Int, aTexCoord: Int) {
        val positions = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )
        // Identity texcoords: bottom-left = (0,0), top-right = (1,1).
        val texCoords = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )
        val posBuf = ByteBuffer.allocateDirect(positions.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(positions)
        val texBuf = ByteBuffer.allocateDirect(texCoords.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(texCoords)
        posBuf.position(0)
        texBuf.position(0)

        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, posBuf)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun compileProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        if (vs == 0) return 0
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        if (fs == 0) {
            GLES20.glDeleteShader(vs)
            return 0
        }
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            Log.e(TAG, "Program link failed: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    companion object {
        private const val TAG = "GpuCaptureProcessor"
    }
}
