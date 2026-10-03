package com.example

import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pure helper tests — no Android framework needed. */
class GalleryHelpersTest {

    @Test
    fun `share mime type covers jpeg dng and fallback`() {
        assertEquals("image/jpeg", galleryShareMimeType(File("a.jpg")))
        assertEquals("image/jpeg", galleryShareMimeType(File("A.JPEG")))
        assertEquals("image/x-adobe-dng", galleryShareMimeType(File("b.dng")))
        assertEquals("image/png", galleryShareMimeType(File("c.png")))
        assertEquals("image/*", galleryShareMimeType(File("d.tiff")))
    }

    @Test
    fun `counter label is one-based`() {
        assertEquals("1 / 12", galleryCounterLabel(0, 12))
        assertEquals("3 / 3", galleryCounterLabel(2, 3))
    }

    @Test
    fun `group by day sorts newest day and photo first`() {
        val dir = createTempDir("gallery-test")
        try {
            val day1Old = File(dir, "old.jpg").apply { writeBytes(byteArrayOf(1)); setLastModified(1_700_000_000_000) }
            val day1New = File(dir, "new.jpg").apply { writeBytes(byteArrayOf(2)); setLastModified(1_700_000_100_000) }
            val day2 = File(dir, "next.jpg").apply { writeBytes(byteArrayOf(3)); setLastModified(1_700_086_400_000) }
            val days = groupGalleryByDay(listOf(day1Old, day2, day1New))
            assertEquals(2, days.size)
            // Newest day first, newest photo first within the day.
            assertEquals(listOf(day2), days[0].files)
            assertEquals(listOf(day1New, day1Old), days[1].files)
            assertTrue(days[0].label.isNotBlank())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `group by day is empty for empty input`() {
        assertTrue(groupGalleryByDay(emptyList()).isEmpty())
    }

    @Test
    fun `format date is stable us english`() {
        // Noon local time: formats back to the same calendar day in every
        // timezone (a fixed UTC epoch would straddle midnight elsewhere).
        val noon = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.MARCH, 12, 12, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals("12 Mar 2026", formatGalleryDate(noon))
    }
}

/** ViewModel gallery-state tests (selection, favorites, pending delete). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GalleryViewModelTest {

    private fun viewModel() =
        CameraViewModel(ApplicationProvider.getApplicationContext())

    @Test
    fun `selection toggles and clears`() {
        val vm = viewModel()
        assertTrue(vm.gallerySelection.value.isEmpty())
        vm.toggleGallerySelection("/a.jpg")
        assertEquals(setOf("/a.jpg"), vm.gallerySelection.value)
        vm.toggleGallerySelection("/b.jpg")
        assertEquals(setOf("/a.jpg", "/b.jpg"), vm.gallerySelection.value)
        vm.toggleGallerySelection("/a.jpg")
        assertEquals(setOf("/b.jpg"), vm.gallerySelection.value)
        vm.clearGallerySelection()
        assertTrue(vm.gallerySelection.value.isEmpty())
    }

    @Test
    fun `favorite toggles for file`() {
        val vm = viewModel()
        val file = File("/tmp/IMG_20240101_24mm.jpg")
        val before = vm.isFavorite(file)
        vm.toggleFavorite(file)
        assertEquals(!before, vm.isFavorite(file))
        vm.toggleFavorite(file)
        assertEquals(before, vm.isFavorite(file))
        // Null-safe no-op.
        vm.toggleFavorite(null)
    }

    @Test
    fun `pending delete request and cancel`() {
        val vm = viewModel()
        assertNull(vm.pendingDelete.value)
        val file = File("/tmp/IMG_20240101_24mm.jpg")
        vm.requestDelete(file)
        assertEquals(file, vm.pendingDelete.value)
        vm.cancelDelete()
        assertNull(vm.pendingDelete.value)
    }

    @Test
    fun `undo not available without delete`() {
        val vm = viewModel()
        assertFalse(vm.canUndoDelete.value)
        vm.dismissUndo()
        assertFalse(vm.canUndoDelete.value)
    }
}
