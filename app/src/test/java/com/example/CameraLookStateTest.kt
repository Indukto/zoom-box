package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.color.CameraProfileRegistry
import com.example.color.profileId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ViewModel-level contract for the two pieces of state the camera UI reads
 * most: the active look (now a string id, not an enum instance) and the
 * double-exposure toggle.
 *
 * These are the seams where a future refactor quietly regresses behavior —
 * an id that no longer resolves, a cycle that no longer wraps, a toggle that
 * stays on after a reset — and they are cheap to pin without a camera.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraLookStateTest {

    private fun viewModel() = CameraViewModel(ApplicationProvider.getApplicationContext())

    @Test
    fun `the camera opens on the pass-through look`() {
        val vm = viewModel()

        assertEquals(FilmPreset.NORMAL.profileId, vm.activeLookId.value)
        assertTrue(vm.isPassThroughLook(vm.activeLookId.value))
    }

    @Test
    fun `the picker catalog is populated before any asset scan`() {
        // The UI must never render an empty grid, so the catalog is seeded
        // from the enum at construction time.
        val ids = viewModel().lookCatalog.value.map { it.id }

        assertEquals(FilmPreset.entries.size, ids.size)
        assertEquals("normal", ids.last())
    }

    @Test
    fun `selecting a look resets the sliders to that profile's defaults`() {
        val vm = viewModel()

        vm.setTemperature(1.5f)
        vm.setExposure(2f)
        vm.setLook(FilmPreset.WARM_PORTRAIT.profileId)

        assertEquals(FilmPreset.WARM_PORTRAIT.profileId, vm.activeLookId.value)
        // The bundled profiles carry no WB/exposure of their own, so this is
        // the neutral reset the picker has always done.
        assertEquals(0f, vm.temperature.value, 0f)
        assertEquals(0f, vm.tint.value, 0f)
        assertEquals(0f, vm.exposure.value, 0f)
    }

    @Test
    fun `an unknown look id is rejected without disturbing the selection`() {
        val vm = viewModel()

        vm.setLook(FilmPreset.MOODY.profileId)
        vm.setLook("no_such_look")

        assertEquals(FilmPreset.MOODY.profileId, vm.activeLookId.value)
        assertFalse(vm.isPassThroughLook("no_such_look"))
    }

    @Test
    fun `cycling walks the catalog and wraps at both ends`() {
        val vm = viewModel()
        val ids = vm.lookCatalog.value.map { it.id }
        assertTrue("catalog too small to exercise the wrap", ids.size > 2)

        vm.setLook(ids[1])
        vm.cycleLook(1)
        assertEquals(ids[2], vm.activeLookId.value)

        vm.cycleLook(-1)
        assertEquals(ids[1], vm.activeLookId.value)

        vm.setLook(ids[1])
        vm.cycleLook(-1)
        assertEquals(ids[0], vm.activeLookId.value)

        // Wraps forwards past the end of the catalog...
        vm.setLook(ids.last())
        vm.cycleLook(1)
        assertEquals(ids.first(), vm.activeLookId.value)
        // ...and backwards past the start.
        vm.setLook(ids.first())
        vm.cycleLook(-1)
        assertEquals(ids.last(), vm.activeLookId.value)
    }

    @Test
    fun `double exposure starts off and toggles on`() {
        val vm = viewModel()

        assertFalse(vm.doubleExposureActive.value)
        vm.toggleDoubleExposure()
        assertTrue(vm.doubleExposureActive.value)
        // Nothing has been captured yet, so there is no ghost to blend.
        assertFalse(vm.doubleExposureHasGhost.value)
    }

    @Test
    fun `clearing double exposure drops the retained ghost`() {
        val vm = viewModel()

        vm.setDoubleExposureEnabled(true)
        vm.clearDoubleExposureGhost()

        assertTrue(vm.doubleExposureActive.value)
        assertFalse(vm.doubleExposureHasGhost.value)
    }

    @Test
    fun `reset to defaults turns double exposure off`() {
        val vm = viewModel()

        vm.setDoubleExposureEnabled(true)
        vm.resetSettingsToDefaults()

        assertFalse(vm.doubleExposureActive.value)
    }

    @Test
    fun `look display names resolve through the catalog`() {
        val vm = viewModel()

        assertEquals(
            FilmPreset.CCD_DIGICAM.displayName,
            vm.lookDisplayName(FilmPreset.CCD_DIGICAM.profileId)
        )
        // An id with no profile falls back rather than rendering a blank.
        assertEquals(FilmPreset.NORMAL.displayName, vm.lookDisplayName("no_such_look"))
    }

    @Test
    fun `every catalog entry resolves to a profile`() {
        val vm = viewModel()

        for (entry in vm.lookCatalog.value) {
            assertEquals(
                entry.displayName,
                vm.lookDisplayName(entry.id)
            )
            assertTrue(
                "${entry.id} has no render params",
                vm.previewRenderParams(entry.id, 0f, 0f, 0f).lutPath.isNotBlank() ||
                    entry.isPassThrough
            )
        }
        assertEquals(FilmPreset.entries.size, CameraProfileRegistry.enumEntries().size)
    }
}