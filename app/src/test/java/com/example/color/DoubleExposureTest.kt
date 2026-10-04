package com.example.color

import android.graphics.Bitmap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Locks the double-exposure compositor.
 *
 * The blend has to be *additive in light* (screen, not average or additive
 * `a + b`, which clips) and it has to be integer-exact: the CPU capture path
 * and the GPU path both consume this output, so a float rounding difference
 * between two runs of the same shot would show up as a different saved JPEG.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DoubleExposureTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun solid(width: Int, height: Int, color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height) { color }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun pixelsOf(bitmap: Bitmap): IntArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels
    }

    @Test
    fun `no ghost returns the base frame untouched`() {
        val base = solid(4, 4, argb(255, 10, 20, 30))

        val merged = DoubleExposure.blend(base, null)

        assertSame(base, merged)
    }

    @Test
    fun `zero strength returns the base frame untouched`() {
        val base = solid(4, 4, argb(255, 10, 20, 30))
        val ghost = solid(4, 4, argb(255, 240, 240, 240))

        assertSame(base, DoubleExposure.blend(base, ghost, strength = 0f))
        // Out-of-range strengths clamp rather than throw or invert the mix.
        assertSame(base, DoubleExposure.blend(base, ghost, strength = -3f))
    }

    @Test
    fun `a full-strength exposure adds light without clipping`() {
        // Black frame over a white ghost must come out white (screen of 0 and
        // 1 is 1), and must not exceed 255 anywhere.
        val base = solid(2, 2, argb(255, 0, 0, 0))
        val ghost = solid(2, 2, argb(255, 255, 255, 255))

        val merged = DoubleExposure.blend(base, ghost, strength = 1f)

        assertEquals(argb(255, 255, 255, 255), merged.getPixel(0, 0))
        assertEquals(argb(255, 255, 255, 255), merged.getPixel(1, 1))
    }

    @Test
    fun `the blend only ever adds light`() {
        val cases = listOf(
            Triple(0, 0, 1f),
            Triple(255, 255, 1f),
            Triple(128, 128, 0.5f),
            Triple(17, 200, 0.3f),
            Triple(240, 13, 0.9f)
        )

        for ((base, ghost, strength) in cases) {
            val frame = solid(1, 1, argb(255, base, base, base))
            val previous = solid(1, 1, argb(255, ghost, ghost, ghost))
            val value = DoubleExposure.blend(frame, previous, strength)
                .getPixel(0, 0) and 0xFF
            // At any strength the ghost can only lift the frame towards the
            // full screen result, and never past it.
            val full = DoubleExposure.blend(frame, previous, strength = 1f).getPixel(0, 0) and 0xFF

            assertTrue(
                "screen($base, $ghost) at $strength% went dark: $value",
                value >= base
            )
            assertTrue(
                "screen($base, $ghost) at $strength% overshot: $value > $full",
                value <= full
            )
            assertTrue("screen($base, $ghost) clipped: $value", value <= 255)
        }
    }

    @Test
    fun `a full exposure is commutative, a partial one is not`() {
        val dark = solid(3, 3, argb(255, 20, 60, 200))
        val light = solid(3, 3, argb(255, 200, 120, 40))

        // Screen is commutative, so two *full* exposures land on the same
        // pixels no matter which frame is treated as the ghost.
        assertArrayEquals(
            pixelsOf(DoubleExposure.blend(dark, light, strength = 1f)),
            pixelsOf(DoubleExposure.blend(light, dark, strength = 1f))
        )
        // Below full strength the mix anchors on the new frame, which is the
        // point of the effect: the shot you are taking stays dominant.
        val anchoredOnNew = DoubleExposure.blend(dark, light, strength = 0.75f).getPixel(0, 0)
        val anchoredOnGhost = DoubleExposure.blend(light, dark, strength = 0.75f).getPixel(0, 0)
        assertNotEquals(anchoredOnNew, anchoredOnGhost)
    }

    @Test
    fun `strength scales the ghost in monotonically`() {
        val base = solid(1, 1, argb(255, 40, 40, 40))
        val ghost = solid(1, 1, argb(255, 200, 200, 200))

        var previous = -1
        for (strength in listOf(0.1f, 0.25f, 0.5f, 0.75f, 1f)) {
            val value = DoubleExposure.blend(base, ghost, strength).getPixel(0, 0) and 0xFF
            assertTrue("strength $strength did not brighten: $value", value > previous)
            previous = value
        }
    }

    @Test
    fun `default strength lands on the documented mix`() {
        // 50% gray over 50% gray: screen = 1 - 0.5*0.5 = 0.75, mixed back
        // toward the base at 55% => 0.5 + 0.25*0.55 = 0.6375 -> 163.
        val base = solid(1, 1, argb(255, 128, 128, 128))
        val ghost = solid(1, 1, argb(255, 128, 128, 128))

        val merged = DoubleExposure.blend(base, ghost)

        assertEquals(163, merged.getPixel(0, 0) and 0xFF)
    }

    @Test
    fun `alpha comes from the new frame only`() {
        // A half-transparent ghost still contributes its light...
        val opaque = solid(1, 1, argb(255, 100, 100, 100))
        val translucentGhost = solid(1, 1, argb(128, 255, 255, 255))
        val mergedOpaque = DoubleExposure.blend(opaque, translucentGhost)

        // ...but must not punch a hole in an opaque capture.
        assertEquals(255, (mergedOpaque.getPixel(0, 0) shr 24) and 0xFF)
        assertTrue(
            "ghost light was dropped",
            (mergedOpaque.getPixel(0, 0) and 0xFF) > 100
        )

        // And the reverse: an opaque ghost must not fill in a transparent
        // frame's alpha, or a padded capture would save as opaque black.
        val seeThrough = solid(1, 1, argb(64, 40, 40, 40))
        val mergedSeeThrough = DoubleExposure.blend(seeThrough, opaque)

        assertEquals(64, (mergedSeeThrough.getPixel(0, 0) shr 24) and 0xFF)
    }

    @Test
    fun `a differently sized ghost is resampled to the frame`() {
        val base = solid(8, 4, argb(255, 90, 90, 90))
        val ghost = solid(2, 1, argb(255, 210, 210, 210))

        val merged = DoubleExposure.blend(base, ghost)

        assertEquals(8, merged.width)
        assertEquals(4, merged.height)
        assertEquals(merged.getPixel(0, 0), merged.getPixel(7, 3))
        // The ghost is the caller's memory: it must survive the blend.
        assertEquals(2, ghost.width)
        assertTrue(!ghost.isRecycled)
    }

    @Test
    fun `a recycled ghost is ignored instead of crashing`() {
        val base = solid(2, 2, argb(255, 50, 50, 50))
        val ghost = solid(2, 2, argb(255, 200, 200, 200))
        ghost.recycle()

        assertSame(base, DoubleExposure.blend(base, ghost))
    }

    @Test
    fun `blending is deterministic`() {
        val base = solid(6, 5, argb(255, 33, 199, 87))
        val ghost = solid(6, 5, argb(255, 141, 17, 233))

        val first = pixelsOf(DoubleExposure.blend(base, ghost, strength = 0.6f))
        val second = pixelsOf(DoubleExposure.blend(base, ghost, strength = 0.6f))

        assertArrayEquals(first, second)
    }

    @Test
    fun `a prepared ghost is bounded and keeps the aspect ratio`() {
        val source = solid(4000, 3000, argb(255, 12, 34, 56))

        val ghost = DoubleExposure.prepareGhost(source)!!

        assertEquals(DoubleExposure.GHOST_MAX_EDGE_PX, maxOf(ghost.width, ghost.height))
        assertEquals(1080, ghost.width)
        assertEquals(810, ghost.height)
        // A copy, never the capture frame itself: recycling the capture must
        // not pull the ghost out from under the next shot.
        assertNotEquals(source, ghost)
        assertTrue(!source.isRecycled)
    }

    @Test
    fun `a small frame is copied without resampling`() {
        val source = solid(320, 240, argb(255, 1, 2, 3))

        val ghost = DoubleExposure.prepareGhost(source)!!

        assertEquals(320, ghost.width)
        assertEquals(240, ghost.height)
        assertEquals(source.getPixel(5, 5), ghost.getPixel(5, 5))
    }

    @Test
    fun `preparing a ghost from an unusable frame yields null`() {
        // A zero-sized bitmap cannot be constructed at all, so the recycled
        // case is the only way "no pixels" reaches this code in practice --
        // the capture path can hold a recycled frame after a failed decode.
        val recycled = solid(2, 2, argb(255, 0, 0, 0))
        recycled.recycle()
        assertNull(DoubleExposure.prepareGhost(recycled))
    }
}