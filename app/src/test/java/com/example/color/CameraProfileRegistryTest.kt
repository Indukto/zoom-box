package com.example.color

import androidx.test.core.app.ApplicationProvider
import com.example.FilmPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reads the real `assets/cameras/` bundle through [CameraProfileRegistry] and
 * locks the two properties the registry-driven picker depends on:
 *
 *  1. every bundled profile still produces exactly the parameters its
 *     [FilmPreset] entry describes (adopting id-based selection must not
 *     change a single pixel of the shipped looks), and
 *  2. the catalog is deterministic — enum order first, no duplicates — so the
 *     picker grid, the swipe cycle and the persisted look id all agree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraProfileRegistryTest {

    private lateinit var registry: CameraProfileRegistry

    @Before
    fun setUp() {
        // Fresh per test so no lazy asset scan is shared between them: the
        // catalog is built from `assets.list()`, which Robolectric serves
        // from the real merged assets, not a stub.
        registry = CameraProfileRegistry(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `bundled catalog is the enum order with no duplicates`() {
        val catalog = registry.catalog()

        assertEquals(
            FilmPreset.entries.map { it.profileId },
            catalog.map { it.id }
        )
        assertEquals(catalog.size, catalog.map { it.id }.toSet().size)
    }

    @Test
    fun `every bundled profile matches its FilmPreset adapter`() {
        for (preset in FilmPreset.entries) {
            assertEquals(
                "look drifted for ${preset.name}",
                preset.toRetroRenderParams(),
                registry.profileFor(preset).look
            )
        }
    }

    @Test
    fun `catalog entries expose the bundled display name and category`() {
        val warm = registry.entryFor(FilmPreset.WARM_PORTRAIT.profileId)

        assertNotNull(warm)
        assertEquals("Warm Portrait", warm!!.displayName)
        assertEquals("color-negative", warm.category)
        assertSame(FilmPreset.WARM_PORTRAIT, warm.preset)
    }

    @Test
    fun `only the pass-through look reports as pass-through`() {
        assertTrue(registry.isPassThrough(FilmPreset.NORMAL.profileId))
        for (preset in FilmPreset.entries) {
            if (preset == FilmPreset.NORMAL) continue
            assertFalse(
                "${preset.name} must grade something",
                registry.isPassThrough(preset.profileId)
            )
        }
    }

    @Test
    fun `an unknown id resolves to nothing rather than the default look`() {
        assertNull(registry.entryFor("no_such_look"))
        assertNull(registry.profileFor("no_such_look"))
        assertNull(registry.renderParamsFor("no_such_look", 0f, 0f, 0f))
        assertFalse(registry.contains("no_such_look"))
        // `isPassThrough` answers false rather than throwing: the caller
        // treats it as "grade it", which is the safe direction for an
        // unresolved id (a graded frame beats a silently unfiltered one).
        assertFalse(registry.isPassThrough("no_such_look"))
    }

    @Test
    fun `render params layer the user's wb over the bundled look`() {
        val bundled = registry.renderParamsFor(
            FilmPreset.WARM_PORTRAIT.profileId,
            temperature = 0.75f,
            tint = -0.5f,
            exposure = 1.25f
        )

        assertNotNull(bundled)
        assertEquals(0.75f, bundled!!.temperature, 0f)
        assertEquals(-0.5f, bundled.tint, 0f)
        assertEquals(1.25f, bundled.exposure, 0f)
        // Untouched by the user layer, so still straight from the file.
        assertEquals(0.20f, bundled.filmCurve, 0f)
        assertEquals(0.05f, bundled.grainStrength, 0f)
        assertEquals("luts/kodak_portra_160_vc.cube", bundled.lutPath)
    }

    @Test
    fun `id and enum overloads agree`() {
        for (preset in FilmPreset.entries) {
            val byId = registry.renderParamsFor(preset.profileId, 0.2f, 0.1f, -0.3f)
            val byEnum = registry.renderParamsFor(preset, 0.2f, 0.1f, -0.3f)
            assertEquals(byEnum, byId)
        }
    }

    @Test
    fun `every bundled profile id is loaded from json`() {
        val loaded = registry.loadedProfileIds()
        for (preset in FilmPreset.entries) {
            assertTrue(
                "${preset.profileId} has no assets/cameras file",
                loaded.contains(preset.profileId)
            )
        }
    }
}