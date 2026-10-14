package eu.indukto.zoombox.color

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

/**
 * Owns the imported-LUT layer: the `.cube` files a user brought in from the
 * device, under `filesDir/luts/`.
 *
 * Why copy at all: a `content://` document is only readable while its grant
 * lasts, and a look is a document that outlives the picker that produced it.
 * Copying gives the look a file it owns, so a look saved today still grades
 * after the next reboot.
 *
 * A look records `user:<file>.cube` rather than an absolute path: `filesDir`
 * moves between installs and OS versions, and an absolute path would be a
 * dangling pointer after a restore.
 */
object UserLutStore {

    /** Directory under `filesDir` that holds imported LUTs. */
    const val DIR_NAME = "luts"

    /** Marker that makes a `lutPath` resolve here instead of under `assets/`. */
    private const val PREFIX = "user:"

    private const val SUFFIX = ".cube"

    /**
     * Refuse anything larger. Parsing splits the whole document line by line,
     * so this cap is what turns "the user picked a video by mistake" into an
     * error message instead of an OOM.
     */
    const val MAX_BYTES = 4L * 1024 * 1024

    fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    /**
     * True when [path] is one of ours.
     *
     * A look document is user-editable, so this is "the prefix plus one bare
     * file name" rather than a prefix test: a hand-written
     * `user:../../databases/foo` must not resolve outside the directory.
     */
    fun isUserPath(path: String): Boolean {
        if (!path.startsWith(PREFIX)) return false
        val name = path.removePrefix(PREFIX)
        return name.isNotEmpty() && name == File(name).name && name != "." && name != ".."
    }

    fun pathFor(fileName: String): String = PREFIX + fileName

    /** The file behind a user path; null for any other path. */
    fun fileFor(context: Context, path: String): File? =
        if (isUserPath(path)) File(dir(context), path.removePrefix(PREFIX)) else null

    /** Short label for the editor: the `.cube`'s own file name. */
    fun displayName(path: String): String = when {
        path.isBlank() -> ""
        isUserPath(path) -> path.removePrefix(PREFIX)
        else -> path.substringAfterLast('/')
    }

    /**
     * Copies the document behind [uri] into [dir] and returns the `user:` path a
     * look should record, or null when the bytes are not a readable `.cube`, are
     * larger than [MAX_BYTES], or cannot be opened at all.
     *
     * Validate before keeping: the copy is parsed while it is still a throwaway,
     * so a look can never be saved pointing at a LUT the grading pipeline would
     * fail to read. The name is minted fresh on every import — a `.cube` can be
     * shared by several looks, and overwriting one in place would silently
     * re-grade all of them.
     *
     * Reads and parses the whole document, so call it off the main thread.
     */
    fun import(context: Context, uri: Uri): String? {
        val target = dir(context)
        // Unique per call: two imports in flight must not share a scratch file.
        val temp = File(target, ".import-${System.nanoTime()}.tmp")
        return try {
            if (!target.exists() && !target.mkdirs()) return null
            val read = context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> copy(input, output) }
            } ?: return null
            if (read > MAX_BYTES) return null
            // Throws on anything malformed — the reason the copy is still
            // allowed to be discarded at this point.
            CubeLutParser.parse(temp)
            val name = uniqueName(baseName(context, uri), existingNames(context))
            // No replace: the name was just minted free, and a move that refuses
            // to clobber is the cheaper guarantee than one that overwrites.
            Files.move(temp.toPath(), File(target, name).toPath())
            pathFor(name)
        } catch (e: Exception) {
            Log.e("UserLutStore", "Failed to import LUT from $uri", e)
            null
        } finally {
            // A no-op after a successful move, and the whole point otherwise.
            temp.delete()
        }
    }

    private fun existingNames(context: Context): Set<String> =
        dir(context).listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) }
            ?.mapTo(mutableSetOf()) { it.name }
            ?: emptySet()

    /**
     * `my_lut.cube`, then `my_lut_2.cube`, … so importing a second LUT can never
     * land on a name another look already points at.
     */
    private fun uniqueName(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        val stem = base.removeSuffix(SUFFIX)
        var suffix = 2
        while ("${stem}_$suffix$SUFFIX" in taken) suffix++
        return "${stem}_$suffix$SUFFIX"
    }

    /**
     * The name to store a picked document under. Slugs the display name through
     * [LookDraftValidator.slugFor] — the same lowercase `[a-z0-9_]` rule look ids
     * use — so whatever the file manager reported cannot walk out of the
     * directory or collide with a scratch file.
     */
    private fun baseName(context: Context, uri: Uri): String {
        val display = runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment
        val stem = display.orEmpty().substringBeforeLast('.').ifBlank { "lut" }
        return LookDraftValidator.slugFor(stem) + SUFFIX
    }

    /**
     * Streams the document in, stopping one byte past [MAX_BYTES]: enough to
     * tell "too large" from "exactly at the limit" without writing the rest of a
     * huge file to disk first.
     */
    private fun copy(input: InputStream, output: OutputStream): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) return total
            total += n
            if (total > MAX_BYTES) return total
            output.write(buffer, 0, n)
        }
    }
}

