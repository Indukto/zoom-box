package eu.indukto.zoombox.color

import android.content.Context
import eu.indukto.zoombox.FilmPreset

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
        /** Lookup used by `profileFor`: user edits win over bundled grades. */
        val profiles: Map<String, CameraProfile>,
        /** Shipped grades keyed by id: assets first, enum adapter as fallback. */
        val assetProfiles: Map<String, CameraProfile>,
        /** Ids declared by the bundled JSON profiles only. */
        val assetIds: Set<String>,
        /** Every id the shipped catalog owns (enum order + JSON-only assets). */
        val bundledIds: Set<String>,
        /** Ids that are selectable *and* authored from scratch by the user. */
        val userIds: Set<String>,
        /** Bundled ids carrying a user-saved file that shadows the shipped grade. */
        val overriddenIds: Set<String>,
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

    /** True when [id] is a look the user authored from scratch. */
    fun isUserLook(id: String): Boolean = id in snapshot().userIds

    /** True when [id] is a shipped look (enum order or a JSON asset). */
    fun isBundled(id: String): Boolean = id in snapshot().bundledIds

    /** True when [id] is a shipped look carrying user-saved edits. */
    fun hasUserOverride(id: String): Boolean = id in snapshot().overriddenIds

    /** The bundled profile for [id], or null when no JSON file declares it. */
    fun profileFor(id: String): CameraProfile? = snapshot().profiles[id]

    /**
     * The shipped grade for [id]: the JSON asset when one exists, else the
     * enum adapter. Null for looks the user authored from scratch — those
     * have no factory grade to restore.
     */
    fun assetProfileFor(id: String): CameraProfile? = snapshot().assetProfiles[id]

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

    /** Bundled ids currently shadowed by a user-saved file. */
    fun overriddenLookIds(): Set<String> = snapshot().overriddenIds

    /**
     * Merges the enum order with every profile source. When a JSON file and
     * the enum both describe a look, the JSON parameters win (see
     * [profileFor]) but the enum decides where the entry sits. A user file
     * whose id matches a shipped look shadows that look's grade in place:
     * the entry keeps its bundled slot, order and preset so the picker never
     * grows a duplicate — editing a style edits it, and deleting the file
     * (see restore) brings the shipped grade back.
     */
    private fun build(): Snapshot {
        val assetProfiles = loadAssetProfiles()
        val userProfiles = LinkedHashMap<String, CameraProfile>()
        for (profile in runCatching { userLooks.load() }.getOrDefault(emptyList())) {
            userProfiles[profile.id] = profile
        }

        val enumIds = FilmPreset.entries.mapTo(mutableSetOf()) { it.profileId }
        // Shipped grades: JSON wins over the enum adapter, which stays as the
        // fallback for ids with no asset file. Kept as its own map so an
        // edit can always ask "what did this look ship as?" even after a
        // user file shadows it in [profiles].
        val factoryProfiles = LinkedHashMap<String, CameraProfile>()
        for (preset in FilmPreset.entries) {
            factoryProfiles[preset.profileId] =
                assetProfiles[preset.profileId] ?: preset.toCameraProfile()
        }
        for ((id, profile) in assetProfiles) {
            factoryProfiles.putIfAbsent(id, profile)
        }

        // Effective lookup: user edits win, everything else falls through to
        // the shipped grade. User files never delete a shipped look — an id
        // with no shipped grade and no user file simply resolves to null.
        val profiles = LinkedHashMap<String, CameraProfile>(factoryProfiles)
        for ((id, profile) in userProfiles) {
            profiles[id] = profile
        }

        val bundledIds = LinkedHashSet<String>(enumIds)
        bundledIds += assetProfiles.keys

        val byId = LinkedHashMap<String, LookEntry>()
        for (entry in enumEntries()) {
            // JSON parameters win where a file exists, but the enum keeps
            // ownership of the slot so the bundled order never shifts.
            // Read the asset map directly: going through `profileFor` here
            // would re-enter `snapshot()` and recurse forever.
            val shipped = assetProfiles[entry.id]?.let { bundled ->
                entry.copy(profile = bundled, displayName = bundled.displayName, category = bundled.category)
            } ?: entry
            val override = userProfiles[entry.id]
            byId[entry.id] = if (override == null) {
                shipped
            } else {
                shipped.copy(
                    profile = override,
                    displayName = override.displayName,
                    hasUserOverride = true
                )
            }
        }
        for (id in assetProfiles.keys.sorted()) {
            if (byId.containsKey(id)) continue
            val shipped = assetProfiles.getValue(id)
            val override = userProfiles[id]
            byId[id] = if (override == null) {
                LookEntry(
                    id = shipped.id,
                    displayName = shipped.displayName,
                    category = shipped.category,
                    // No enum entry — this look exists purely as an asset.
                    preset = null,
                    profile = shipped
                )
            } else {
                LookEntry(
                    id = shipped.id,
                    displayName = override.displayName,
                    category = shipped.category,
                    preset = null,
                    profile = override,
                    hasUserOverride = true
                )
            }
        }
        val userIds = mutableSetOf<String>()
        val overriddenIds = mutableSetOf<String>()
        for (id in userProfiles.keys.sorted()) {
            if (id in bundledIds) {
                overriddenIds += id
                continue
            }
            val profile = userProfiles.getValue(id)
            byId[id] = LookEntry(
                id = profile.id,
                displayName = profile.displayName,
                category = profile.category,
                // A user look is never enum-backed: it is its own document.
                preset = null,
                profile = profile,
                isUser = true
            )
            userIds += id
        }

        return Snapshot(
            profiles = profiles,
            assetProfiles = factoryProfiles,
            assetIds = assetProfiles.keys.toSet(),
            bundledIds = bundledIds,
            userIds = userIds,
            overriddenIds = overriddenIds,
            catalog = byId.values.toList()
        )
    }

    private fun loadAssetProfiles(): Map<String, CameraProfile> {
        val result = mutableMapOf<String, CameraProfile>()
        val names = runCatching { context.assets.list("cameras") ?: emptyArray() }
            .getOrDefault(emptyArray())
        for (name in names) {
            if (!name.endsWith(".json")) continue
            val fileId = name.removeSuffix(".json")
            val profile = runCatching {
                val json = context.assets.open("cameras/$name")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                CameraProfileLoader.parse(json)
            }.getOrNull() ?: continue
            // The declared id is the identity the catalog addresses. A file
            // whose name disagrees used to be indexed under both keys, which
            // built two picker entries for one look. Keep the canonical id
            // so the catalog stays duplicate-free; the mismatch is logged.
            if (profile.id != fileId) {
                android.util.Log.w(
                    "CameraProfileRegistry",
                    "cameras/$name declares id '${profile.id}', expected '$fileId'; using declared id"
                )
            }
            result[profile.id] = profile
        }
        return result
    }
}

