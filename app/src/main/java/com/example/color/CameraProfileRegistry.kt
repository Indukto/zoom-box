package com.example.color

import android.content.Context
import com.example.FilmPreset

/**
 * Loads backend look profiles from the `assets/cameras/` JSON files and falls back to
 * the in-code [FilmPreset.toCameraProfile] adapter when a matching JSON file
 * does not exist. JSON wins when present, so adding or tweaking a look becomes
 * an asset change rather than an enum edit — while every existing preset keeps
 * producing identical parameters because the adapter mirrors the enum.
 *
 * Selection is by stable string id, not by enum instance: [catalog] is the
 * picker's data source and it can contain looks that have no [FilmPreset]
 * entry at all. The enum only contributes the picker *order* for the looks
 * that mirror it, plus a fallback profile for a look whose JSON file is
 * missing.
 */
class CameraProfileRegistry(private val context: Context) {

    companion object {
        /**
         * The enum-backed half of the catalog, built without touching assets
         * so it is safe to call from any thread (the UI seeds its copy from
         * here so the picker is never momentarily empty) and free of Android
         * dependencies (so it stays unit-testable).
         */
        fun enumEntries(): List<LookEntry> = FilmPreset.entries.map { preset ->
            val profile = preset.toCameraProfile()
            LookEntry(
                id = profile.id,
                displayName = profile.displayName,
                category = profile.category,
                preset = preset,
                profile = profile
            )
        }
    }

    private val profiles: Map<String, CameraProfile> by lazy { loadProfiles() }

    private val catalogEntries: List<LookEntry> by lazy { buildCatalog() }

    /**
     * Every selectable look, in a deterministic order: enum-backed looks
     * first, in [FilmPreset.entries] order (so the bundled thirteen never
     * move), then any JSON-only looks sorted by id.
     */
    fun catalog(): List<LookEntry> = catalogEntries

    /** The catalog entry for [id], or null when no look carries that id. */
    fun entryFor(id: String): LookEntry? = catalogEntries.firstOrNull { it.id == id }

    /** True when [id] names a look in [catalog]. */
    fun contains(id: String): Boolean = entryFor(id) != null

    /** True when [id] grades nothing (no LUT and no render stage). */
    fun isPassThrough(id: String): Boolean = entryFor(id)?.isPassThrough ?: false

    /** The bundled profile for [id], or null when no JSON file declares it. */
    fun profileFor(id: String): CameraProfile? = profiles[id]

    /** The profile for [preset], preferring a JSON definition when one exists. */
    fun profileFor(preset: FilmPreset): CameraProfile =
        profileFor(preset.profileId) ?: preset.toCameraProfile()

    /**
     * The render snapshot the capture pipeline should use for [id], with the
     * user's live WB/exposure adjustments layered on top of the profile's
     * default look. WB/exposure always come from the user, never the file.
     * Null when [id] names no bundled profile.
     */
    fun renderParamsFor(
        id: String,
        temperature: Float,
        tint: Float,
        exposure: Float
    ): RetroRenderParams? = profileFor(id)?.look?.copy(
        temperature = temperature,
        tint = tint,
        exposure = exposure
    )

    /**
     * Enum-shaped convenience wrapper: same layering as the id overload, but
     * never null because the [FilmPreset] adapter always yields a profile.
     */
    fun renderParamsFor(
        preset: FilmPreset,
        temperature: Float,
        tint: Float,
        exposure: Float
    ): RetroRenderParams = profileFor(preset).look.copy(
        temperature = temperature,
        tint = tint,
        exposure = exposure
    )

    /** Ids that were actually loaded from JSON (useful for tests/diagnostics). */
    fun loadedProfileIds(): Set<String> = profiles.keys

    /**
     * Merges the enum order with the bundled JSON profiles. When a JSON file
     * and the enum both describe a look, the JSON parameters win (see
     * [profileFor]) but the enum decides where the entry sits.
     */
    private fun buildCatalog(): List<LookEntry> {
        val byId = LinkedHashMap<String, LookEntry>()
        for (entry in enumEntries()) {
            // JSON parameters win where a file exists, but the enum keeps
            // ownership of the slot so the bundled order never shifts.
            byId[entry.id] = profileFor(entry.id)?.let { bundled ->
                entry.copy(profile = bundled, displayName = bundled.displayName, category = bundled.category)
            } ?: entry
        }
        for (id in profiles.keys.sorted()) {
            if (byId.containsKey(id)) continue
            val profile = profiles.getValue(id)
            byId[id] = LookEntry(
                id = profile.id,
                displayName = profile.displayName,
                category = profile.category,
                // No enum entry — this look exists purely as an asset.
                preset = null,
                profile = profile
            )
        }
        return byId.values.toList()
    }

    private fun loadProfiles(): Map<String, CameraProfile> {
        val result = mutableMapOf<String, CameraProfile>()
        val names = runCatching { context.assets.list("cameras") ?: emptyArray() }
            .getOrDefault(emptyArray())
        for (name in names) {
            if (!name.endsWith(".json")) continue
            val id = name.removeSuffix(".json")
            val profile = runCatching {
                val json = context.assets.open("cameras/$name")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                CameraProfileLoader.parse(json)
            }.getOrNull() ?: continue
            result[id] = profile
            // A file whose declared `id` disagrees with its file name stays
            // selectable under both keys, so a typo in either place can never
            // make a look disappear from the picker.
            if (profile.id != id) result[profile.id] = profile
        }
        return result
    }
}