package eu.indukto.zoombox.color

import android.content.Context
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Read side of the user-authored look layer: everything the registry needs to
 * merge user looks into the picker catalog without knowing where they live.
 *
 * A single-method interface (so it is a `fun interface`) because the registry's
 * only question is "what user looks exist right now?" — writes go through
 * [UserLookStore] directly.
 */
fun interface UserLookSource {
    fun load(): List<CameraProfile>
}

/**
 * Owns the on-disk user look layer: one `<id>.json` file per look under
 * `filesDir/looks/`, in exactly the schema the bundled profiles use.
 *
 * Why files rather than DataStore: a look *is* a document (the whole point of
 * the registry is that profiles are data), so a look that is a file can be
 * exported, diffed and re-imported with the code that already exists. It also
 * keeps DataStore holding preferences only, which is what it is good at.
 *
 * Writes go through a temp file plus an atomic move, because the alternative —
 * truncating the real file and then writing — loses a look if the process dies
 * mid-save, and a half-written profile is exactly the kind of thing the loader
 * would then skip on the next launch.
 */
class UserLookStore(private val dir: File) : UserLookSource {

    companion object {
        /** Directory under `filesDir` that owns the user look layer. */
        const val DIR_NAME = "looks"

        private const val JSON_SUFFIX = ".json"

        /**
         * Category stamped on every user look. Also what the manager screen
         * groups on, though `LookEntry.isUser` is the authoritative flag.
         */
        const val CATEGORY = "custom"

        fun defaultDir(context: Context): File = File(context.filesDir, DIR_NAME)
    }

    /**
     * Every readable look in the store, sorted by id so the picker order is
     * deterministic across launches. A corrupt or unparsable file is skipped
     * rather than fatal — one bad look must not empty the whole catalog.
     *
     * The file *name* is the identity: if a hand-edited document declares a
     * different `id`, the file name still wins, because that is the key the
     * catalog and the persisted selection address it by.
     */
    override fun load(): List<CameraProfile> {
        sweepTempFiles()
        val files = dir.listFiles { file -> isLookFile(file) } ?: return emptyList()
        return files.sortedBy { it.name }.mapNotNull { file ->
            val id = file.name.removeSuffix(JSON_SUFFIX)
            val profile = runCatching {
                CameraProfileLoader.parse(file.readText(Charsets.UTF_8))
            }.getOrNull() ?: return@mapNotNull null
            if (profile.id == id) profile else profile.copy(id = id)
        }
    }

    /** Ids present on disk, without parsing their contents. */
    fun ids(): Set<String> {
        sweepTempFiles()
        val files = dir.listFiles { file -> isLookFile(file) } ?: return emptySet()
        return files.mapTo(mutableSetOf()) { it.name.removeSuffix(JSON_SUFFIX) }
    }

    fun fileFor(id: String): File = File(dir, "$id$JSON_SUFFIX")

    /**
     * True when the write landed. Refuses an id that could not be addressed
     * again (see [LookDraftValidator.isValidId]) rather than creating an
     * orphaned file.
     */
    fun save(profile: CameraProfile): Boolean {
        if (!LookDraftValidator.isValidId(profile.id)) return false
        val target = fileFor(profile.id)
        val temp = File(dir, "${profile.id}$JSON_SUFFIX.tmp")
        return runCatching {
            if (!dir.exists()) dir.mkdirs()
            temp.writeText(CameraProfileWriter.toJson(profile), Charsets.UTF_8)
            replace(temp, target)
            true
        }.getOrElse {
            temp.delete()
            false
        }
    }

    /** True when a stored look was removed (false when there was nothing to remove). */
    fun delete(id: String): Boolean = runCatching { fileFor(id).delete() }.getOrDefault(false)

    /**
     * A free id for [displayName]: its slug, or `slug_2`, `slug_3`, … when that
     * is taken. [taken] must include the bundled ids, so a user look can never
     * be minted onto an id the shipped catalog already owns.
     */
    fun uniqueId(displayName: String, taken: Set<String>): String {
        val base = LookDraftValidator.slugFor(displayName)
        if (base !in taken) return base
        var suffix = 2
        while ("${base}_$suffix" in taken) suffix++
        return "${base}_$suffix"
    }

    private fun isLookFile(file: File): Boolean =
        file.isFile && file.name.endsWith(JSON_SUFFIX)

    /**
     * Drops crashed-save leftovers (`<id>.json.tmp`). A kill between the
     * temp write and the atomic move leaves one behind; it is never a
     * look, so remove it lazily on the next read instead of accumulating.
     */
    private fun sweepTempFiles() {
        val temps = dir.listFiles { file ->
            file.isFile && file.name.endsWith("$JSON_SUFFIX.tmp")
        } ?: return
        for (temp in temps) {
            runCatching { temp.delete() }
        }
    }

    /**
     * POSIX `rename` semantics (Android): atomic, replaces the target. Some
     * filesystems refuse `ATOMIC_MOVE`, and a plain JVM on Windows has no
     * atomic replace at all — fall back to the non-atomic move there so unit
     * tests behave like the device instead of failing on the OS.
     */
    private fun replace(temp: File, target: File) {
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: UnsupportedOperationException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

