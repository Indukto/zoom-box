package com.example

import com.example.color.CameraProfileRegistry
import com.example.color.profileId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the DataStore migration for the registry-driven look picker.
 *
 * Installs that shipped before the picker became registry-driven stored a
 * `FilmPreset` enum *name* under the same DataStore key that now holds a look
 * *id*. `resolveLookId` is the only thing standing between those users and a
 * silently reset Film Style, so its behavior is pinned here rather than
 * discovered in the field.
 */
class LookIdResolutionTest {

    @Test
    fun `a legacy enum name resolves to its look id`() {
        assertEquals(
            "warm_portrait",
            UserPreferencesRepository.resolveLookId("WARM_PORTRAIT")
        )
        assertEquals(
            "street_mono_400",
            UserPreferencesRepository.resolveLookId("STREET_MONO_400")
        )
    }

    @Test
    fun `an already-migrated id round-trips unchanged`() {
        for (preset in FilmPreset.entries) {
            assertEquals(
                preset.profileId,
                UserPreferencesRepository.resolveLookId(preset.profileId)
            )
        }
    }

    @Test
    fun `every enum name migrates to a distinct catalog id`() {
        val ids = FilmPreset.entries.map {
            UserPreferencesRepository.resolveLookId(it.name)
        }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(
            CameraProfileRegistry.enumEntries().map { it.id }.toSet(),
            ids.toSet()
        )
    }

    @Test
    fun `a missing value falls back to the default look`() {
        assertEquals(
            UserPreferencesRepository.DEFAULT_LOOK_ID,
            UserPreferencesRepository.resolveLookId(null)
        )
        assertEquals(
            UserPreferencesRepository.DEFAULT_LOOK_ID,
            UserPreferencesRepository.resolveLookId("")
        )
        assertEquals(
            UserPreferencesRepository.DEFAULT_LOOK_ID,
            UserPreferencesRepository.resolveLookId("   ")
        )
        assertEquals("warm_portrait", UserPreferencesRepository.DEFAULT_LOOK_ID)
    }

    @Test
    fun `an unknown id is preserved for forward compatibility`() {
        // A JSON-only look (or one whose asset failed to load this launch)
        // must keep its selection instead of being silently rewritten to the
        // default; the ViewModel resolves it or falls back at render time.
        assertEquals(
            "halation_soft_1984",
            UserPreferencesRepository.resolveLookId("halation_soft_1984")
        )
        assertEquals(
            "halation_soft_1984",
            UserPreferencesRepository.resolveLookId("  Halation_Soft_1984 ")
        )
    }
}