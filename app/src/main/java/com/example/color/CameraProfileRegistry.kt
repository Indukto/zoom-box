package com.example.color

import android.content.Context
import com.example.FilmPreset

/**
 * Loads backend look profiles from the `assets/cameras/` JSON files, merges in
 * any look the user authored (see [UserLookSource]), and falls back to the
 * in-code [FilmPreset.toCameraProfile] adapter when a matching JSON file does
 * not exist. JSON wins when present, so adding or tweaking a bundled look stays
 * an asset change rather than an enum edit — while every existing preset keeps
 * producing identical parameters because the adapter mirrors the enum.
 *
 * Selection is by stable string id, not by enum instance: [catalog] is the
 * picker's data source and it can contain looks that have no [FilmPreset]
 * entry at all. The enum only contributes the picker *order* for the looks
 * that mirror it, plus a fallback profile for a look whose JSON file is
 * missing.
 *
 * The catalog is assembled once and cached, so saving or deleting a user look
 * stays invisible until [refresh] is called. That is deliberate: the picker,
 * the swipe cycle and the capture path all read the same snapshot, and a
 * half-applied edit would let the viewfinder grade one look while a capture
 * grades another.
 *
 * @param userLooks the user-authored layer. Defaults to the real
 *        `filesDir/looks` directory so a plain `CameraProfileRegistry(context)`
 *        sees everything the user created; tests inject a temp directory.
 */
class CameraProfileRegistry(
    private val context: Context,
    private val userLooks: UserLookSource = UserLookStore(UserLookStore.defaultDir(context))
) {

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

    /** One immutable, internally consistent view of every look source. */
    private class Snapshot(
        /** Lookup used by `profileFor`; asset ids win over user ids. */
        val profiles: Map<String, CameraProfile>,
        /** Ids declared by the bundled JSON profiles only. */
        val assetIds: Set<String>,
        /** Ids that are selectable *and* user-authored. */
        val userIds: Set<String>,
        val catalog: List<LookEntry>
    )

    @Volatile
    private var cached: Snapshot? = null

    private fun snapshot(): Snapshot =
        cached ?: synchronized(this) {
            cached ?: build().also { cached = it }
        }

    /**
     * Drops the cached catalog so the next read picks up a look that was just
     * saved, deleted or overwritten on disk. Callers on the UI side must also
     * re-read [catalog] into their own state (see `CameraViewModel`), because
     * this snapshot is not observable.
     */
    fun refresh() {
        synchronized(this) {
            cached = build()
        }
    }

    /**
     * Every selectable look, in a deterministic order: enum-backed looks
     * first, in [FilmPreset.entries] order (so the bundled thirteen never
     * move), then any JSON-only asset look sorted by id, then the user's own
     * looks sorted by id.
     */
    fun catalog(): List<LookEntry> = snapshot().catalog

    /** The catalog entry for [id], or null when no look carries that id. */
    fun entryFor(id: String): LookEntry? = snapshot().catalog.firstOrNull { it.id == id }

    /** True when [id] names a look in [catalog]. */
    fun contains(id: String): Boolean = entryFor(id) != null

    /** True when [id] grades nothing (no LUT and no render stage). */
    fun isPassThrough(id: String): Boolean = entryFor(id)?.isPassThrough ?: false

    /** True when [id] is a look the user authored. */
    fun isUserLook(id: String): Boolean = id in snapshot().userIds

    /** The bundled profile for [id], or null when no JSON file declares it. */
    fun profileFor(id: String): CameraProfile? = snapshot().profiles[id]

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
    fun loadedProfileIds(): Set<String> = snapshot().assetIds

    /** Ids of the looks the user authored and that are selectable. */
    fun userLookIds(): Set<String> = snapshot().userIds

    /**
     * Merges the enum order with every profile source. When a JSON file and
     * the enum both describe a look, the JSON parameters win (see
     * [profileFor]) but the enum decides where the entry sits. A user look can
     * never take an id the shipped catalog already owns: a file claiming one is
     * ignored for that id, because silently re-grading a bundled look from a
     * file the user cannot see is indistinguishable from a bug.
     */
    private fun build(): Snapshot {
        val assetProfiles = loadAssetProfiles()
        val userProfiles = LinkedHashMap<String, CameraProfile>()
        for (profile in runCatching { userLooks.load() }.getOrDefault(emptyList())) {
            userProfiles[profile.id] = profile
        }

        val enumIds = FilmPreset.entries.mapTo(mutableSetOf()) { it.profileId }

        val profiles = LinkedHashMap<String, CameraProfile>(assetProfiles)
        for ((id, profile) in userProfiles) {
            if (profiles.containsKey(id) || id in enumIds) continue
            profiles[id] = profile
        }

        val byId = LinkedHashMap<String, LookEntry>()
        for (entry in enumEntries()) {
            // JSON parameters win where a file exists, but the enum keeps
            // ownership of the slot so the bundled order never shifts.
            // Read the asset map directly: going through `profileFor` here
            // would re-enter `snapshot()` and recurse forever.
            byId[entry.id] = assetProfiles[entry.id]?.let { bundled ->
                entry.copy(profile = bundled, displayName = bundled.displayName, category = bundled.category)
            } ?: entry
        }
        for (id in assetProfiles.keys.sorted()) {
            if (byId.containsKey(id)) continue
            val profile = assetProfiles.getValue(id)
            byId[id] = LookEntry(
                id = profile.id,
                displayName = profile.displayName,
                category = profile.category,
                // No enum entry — this look exists purely as an asset.
                preset = null,
                profile = profile
            )
        }
        val userIds = mutableSetOf<String>()
        for (id in userProfiles.keys.sorted()) {
            if (byId.containsKey(id) || id in enumIds) continue
            val profile = userProfiles.getValue(id)
            byId[id] = LookEntry(
                id = profile.id,
                displayName = profile.displayName,
                category = profile.category,
                // A user look is never enum-backed, even when it was duplicated
                // from one: it is its own document from here on.
                preset = null,
                profile = profile,
                isUser = true
            )
            userIds += id
        }

        return Snapshot(
            profiles = profiles,
            assetIds = assetProfiles.keys.toSet(),
            userIds = userIds,
            catalog = byId.values.toList()
        )
    }

    private fun loadAssetProfiles(): Map<String, CameraProfile> {
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
