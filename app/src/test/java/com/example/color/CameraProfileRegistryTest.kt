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
import java.io.File
import kotlin.io.path.createTempDirectory

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

    // ── User-authored looks ─────────────────────────────────────────────────

    private fun registryWith(dir: File) =
        CameraProfileRegistry(ApplicationProvider.getApplicationContext(), UserLookStore(dir))

    private fun userProfile(id: String, name: String, look: RetroRenderParams = RetroRenderParams()) =
        CameraProfile(id = id, displayName = name, category = UserLookStore.CATEGORY, look = look)

    @Test
    fun `user looks are appended after the bundled catalog and flagged as the user's`() {
        val dir = createTempDirectory("looks").toFile()
        try {
            val store = UserLookStore(dir)
            store.save(userProfile("my_moody", "My Moody", RetroRenderParams(contrast = 1.3f)))

            val catalog = registryWith(dir).catalog()
            val bundledIds = FilmPreset.entries.map { it.profileId }

            // The shipped order must not shift: the picker grid and the persisted
            // selection index both depend on the first thirteen slots.
            assertEquals(bundledIds, catalog.take(bundledIds.size).map { it.id })
            assertEquals("my_moody", catalog.last().id)
            assertTrue(catalog.last().isUser)
            assertEquals("My Moody", catalog.last().displayName)
            // A user look has no enum entry, exactly like a JSON-only asset look.
            assertNull(catalog.last().preset)
            assertEquals(1.3f, catalog.last().profile.look.contrast, 0f)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a user file can never shadow a bundled look id`() {
        val dir = createTempDirectory("looks").toFile()
        try {
            val store = UserLookStore(dir)
            store.save(userProfile("warm_portrait", "Impostor", RetroRenderParams(contrast = 1.9f)))

            val scoped = registryWith(dir)
            val warm = scoped.entryFor("warm_portrait")

            assertNotNull(warm)
            assertFalse("a bundled id must not become a user look", warm!!.isUser)
            assertEquals("Warm Portrait", warm.displayName)
            assertSame(FilmPreset.WARM_PORTRAIT, warm.preset)
            // And the parameters still come from the asset, not the file.
            assertEquals(
                FilmPreset.WARM_PORTRAIT.toRetroRenderParams(),
                scoped.profileFor("warm_portrait")!!.look
            )
            assertEquals(emptySet<String>(), scoped.userLookIds())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the catalog is cached until refresh is called`() {
        val dir = createTempDirectory("looks").toFile()
        try {
            val store = UserLookStore(dir)
            val scoped = registryWith(dir)

            assertEquals(emptySet<String>(), scoped.userLookIds())

            store.save(userProfile("my_look", "My Look"))
            assertEquals(
                "a plain read must not see a look that was written after it",
                emptySet<String>(),
                scoped.userLookIds()
            )

            scoped.refresh()
            assertEquals(setOf("my_look"), scoped.userLookIds())
            assertEquals("my_look", scoped.catalog().last().id)

            store.delete("my_look")
            scoped.refresh()
            assertEquals(emptySet<String>(), scoped.userLookIds())
            assertFalse(scoped.contains("my_look"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a params-only user look is a pass-through and a graded one is not`() {
        val dir = createTempDirectory("looks").toFile()
        try {
            val store = UserLookStore(dir)
            store.save(userProfile("blank", "Blank"))
            store.save(userProfile("grainy", "Grainy", RetroRenderParams(grainStrength = 0.4f)))

            val scoped = registryWith(dir)

            assertTrue(scoped.isPassThrough("blank"))
            assertTrue(scoped.entryFor("blank")!!.isPassThrough)
            assertFalse(scoped.isPassThrough("grainy"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `asset ids and user ids stay separate`() {
        val dir = createTempDirectory("looks").toFile()
        try {
            UserLookStore(dir).save(userProfile("my_look", "My Look"))

            val scoped = registryWith(dir)

            assertFalse(scoped.loadedProfileIds().contains("my_look"))
            assertEquals(setOf("my_look"), scoped.userLookIds())
            assertTrue(scoped.loadedProfileIds().contains("warm_portrait"))
            assertTrue(scoped.isUserLook("my_look"))
            assertFalse(scoped.isUserLook("warm_portrait"))
        } finally {
            dir.deleteRecursively()
        }
    }
}