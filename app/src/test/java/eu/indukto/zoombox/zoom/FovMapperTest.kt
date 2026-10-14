package eu.indukto.zoombox.zoom

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for FovMapper — the pure math module.
 * No Android dependencies needed, runs on JVM.
 *
 * Tests the remaining functions after the manual lens selection refactor:
 * - boxScale: zoom box overlay size (only meaningful on Primary lens)
 * - captureCropFactor: digital crop factor for post-capture
 * - outputMegapixels: estimated output resolution after crop
 */
class FovMapperTest {

    // Pixel 7 Pro reference focal lengths
    private val UW_FOCAL_MM = 13.4f
    private val PRIMARY_FOCAL_MM = 24f
    private val TELE_FOCAL_MM = 116.2f

    // --- boxScale tests ---

    @Test
    fun `boxScale at primary focal length returns 1`() {
        // At 24mm with Primary preview (24mm), box should fill the frame
        val scale = FovMapper.boxScale(PRIMARY_FOCAL_MM, PRIMARY_FOCAL_MM)
        assertEquals("At native focal length, boxScale should be 1.0", 1.0f, scale, 0.001f)
    }

    @Test
    fun `boxScale at 85mm with Primary preview returns approx 0dot282`() {
        // At 85mm target with Primary preview (24mm): boxScale = 24/85 ≈ 0.282
        val scale = FovMapper.boxScale(PRIMARY_FOCAL_MM, 85f)
        val expected = 24f / 85f
        assertEquals("Box scale at 85mm should be 24/85", expected, scale, 0.001f)
    }

    @Test
    fun `boxScale at 50mm with Primary preview returns 0dot48`() {
        val scale = FovMapper.boxScale(PRIMARY_FOCAL_MM, 50f)
        assertEquals("Box scale at 50mm should be 24/50 = 0.48", 0.48f, scale, 0.001f)
    }

    @Test
    fun `boxScale at 116dot2mm with Primary preview returns 24 over 116dot2`() {
        val scale = FovMapper.boxScale(PRIMARY_FOCAL_MM, TELE_FOCAL_MM)
        val expected = PRIMARY_FOCAL_MM / TELE_FOCAL_MM
        assertEquals("Box scale at 116.2mm should be 24/116.2", expected, scale, 0.001f)
    }

    @Test
    fun `boxScale with ultraWide preview at 13dot4mm returns 1`() {
        val scale = FovMapper.boxScale(UW_FOCAL_MM, UW_FOCAL_MM)
        assertEquals("At UW native focal length, boxScale should be 1.0", 1.0f, scale, 0.001f)
    }

    @Test
    fun `boxScale with ultraWide preview at 18mm returns correctly`() {
        val scale = FovMapper.boxScale(UW_FOCAL_MM, 18f)
        val expected = UW_FOCAL_MM / 18f
        assertEquals("Box scale at 18mm with UW preview", expected, scale, 0.001f)
    }

    @Test
    fun `boxScale clamps values above 1 to 1`() {
        val scale = FovMapper.boxScale(50f, 30f)
        assertEquals("When target < preview focal, boxScale should clamp to 1.0", 1.0f, scale, 0.001f)
    }

    @Test
    fun `boxScale with zero target returns 1`() {
        val scale = FovMapper.boxScale(24f, 0f)
        assertEquals("Zero target should return 1.0", 1.0f, scale, 0.001f)
    }

    @Test
    fun `boxScale with negative values returns 1`() {
        val scale = FovMapper.boxScale(-24f, 50f)
        assertEquals("Negative preview focal should return 1.0", 1.0f, scale, 0.001f)
    }

    // --- captureCropFactor tests ---

    @Test
    fun `captureCropFactor at native focal length returns 1`() {
        val crop = FovMapper.captureCropFactor(24f, 24f)
        assertEquals("Crop factor at native focal should be 1.0", 1.0f, crop, 0.001f)
    }

    @Test
    fun `captureCropFactor at 50mm with Primary capture returns 50 over 24`() {
        val crop = FovMapper.captureCropFactor(24f, 50f)
        val expected = 50f / 24f
        assertEquals("Crop factor at 50mm with Primary", expected, crop, 0.001f)
    }

    @Test
    fun `captureCropFactor at 85mm with Primary capture returns 85 over 24`() {
        val crop = FovMapper.captureCropFactor(24f, 85f)
        val expected = 85f / 24f
        assertEquals("Crop factor at 85mm with Primary", expected, crop, 0.001f)
    }

    @Test
    fun `captureCropFactor at 116dot2mm with Tele capture returns 1`() {
        val crop = FovMapper.captureCropFactor(TELE_FOCAL_MM, TELE_FOCAL_MM)
        assertEquals("Crop factor at native Tele should be 1.0", 1.0f, crop, 0.001f)
    }

    @Test
    fun `captureCropFactor at 120mm with Tele capture returns 120 over 116dot2`() {
        val crop = FovMapper.captureCropFactor(TELE_FOCAL_MM, 120f)
        val expected = 120f / TELE_FOCAL_MM
        assertEquals("Crop factor at 120mm with Tele", expected, crop, 0.001f)
    }

    // --- outputMegapixels tests ---

    @Test
    fun `outputMegapixels at native focal length equals native MP`() {
        val mp = FovMapper.outputMegapixels(50, 1.0f)
        assertEquals("At 1x crop, output should be 50MP", 50.0f, mp, 0.5f)
    }

    @Test
    fun `outputMegapixels at 2x crop reduces by 4x`() {
        val mp = FovMapper.outputMegapixels(50, 2.0f)
        assertEquals("At 2x crop, output should be ~12.5MP", 12.5f, mp, 0.5f)
    }

    @Test
    fun `outputMegapixels at tele native 48MP with 1x crop`() {
        val mp = FovMapper.outputMegapixels(48, 1.0f)
        assertEquals("Tele native 48MP at 1x", 48.0f, mp, 0.5f)
    }

    // --- Integration: Acceptance Criteria ---

    @Test
    fun `acceptance criteria 1 - at 24mm box scale is 1`() {
        val scale = FovMapper.boxScale(PRIMARY_FOCAL_MM, PRIMARY_FOCAL_MM)
        assertEquals("AC1: At 24mm, box scale = 1.0", 1.0f, scale, 0.001f)
    }

    @Test
    fun `acceptance criteria 2 - at 85mm box scale is approx 0dot282`() {
        val scale = FovMapper.boxScale(PRIMARY_FOCAL_MM, 85f)
        val expected = PRIMARY_FOCAL_MM / 85f
        assertEquals("AC2: At 85mm, box scale ≈ 0.282", expected, scale, 0.001f)
    }
}
