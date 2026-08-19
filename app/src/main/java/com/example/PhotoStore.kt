package com.example

import android.content.ContentValues
import android.content.Context
import android.media.ExifInterface
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Owns the on-disk + MediaStore side of the gallery: scanning the two capture
 * locations (app-private working copies and the public Pictures/ZoomBoxCamera
 * mirror), saving JPEG/DNG via MediaStore, deleting files + their MediaStore
 * rows, and reading EXIF metadata.
 *
 * Pure mechanics — no ViewModel state. The ViewModel keeps the state-flow
 * choreography (which photo is selected, auto-advance after delete) and
 * delegates all file/MediaStore work here.
 */
class PhotoStore(private val context: Context) {

    private fun privateDir(): File? =
        context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)

    private fun isOurPhoto(file: File): Boolean =
        file.isFile && file.extension.lowercase() in listOf("jpg", "jpeg", "dng")

    /**
     * Synchronous gallery scan, newest-first (index 0 = most recent capture).
     * [skipOrphanCleanup] should be true while a capture is in flight: the
     * working copy is on disk before its MediaStore row exists, and the
     * orphan pass would otherwise race-delete the photo being taken.
     */
    fun listPhotos(skipOrphanCleanup: Boolean = false): List<File> {
        // Two locations hold our captures:
        //   1. App-private: getExternalFilesDir(DIRECTORY_PICTURES) — working
        //      copies written straight from the capture pipeline. File.listFiles
        //      works fine here because we own the directory and it lives
        //      inside our scope (`/sdcard/Android/data/<package>/files/...`).
        //   2. Public-shared MediaStore mirror: Pictures/ZoomBoxCamera/ (plus
        //      any subfolders, including the RAW/ tree). After an app reinstall
        //      the private copy is wiped but the MediaStore entries survive —
        //      and only MediaStore can see them on Android 10+ scoped storage
        //      without READ_MEDIA_IMAGES.
        val publicFiles = listPublicPhotosViaMediaStore()
        // Drop orphan app-private cache mirrors whose MediaStore row has gone
        // away (external delete via file manager, sideload via ADB, etc.).
        // Without this pass, distinctBy-{name} below would resurrect those
        // names from the private mirror after the corresponding public file
        // disappears — the gallery would keep showing photos the user just
        // removed from /sdcard/Pictures/ZoomBoxCamera/.
        if (!skipOrphanCleanup) {
            cleanupOrphanPrivateFiles(publicFiles.map { it.name }.toSet())
        }
        val privateFiles = privateDir()?.listFiles(::isOurPhoto)?.toList() ?: emptyList()

        // Public first, then private — distinctBy { it.name } keeps the
        // public entry when both copies exist, so the file we hand to
        // deletePhoto() is the canonical (reinstall-survived) path.
        return (publicFiles + privateFiles)
            .distinctBy { it.name }
            .sortedByDescending { it.lastModified() }
    }

    /** Inserts [file] into the public gallery as a JPEG under Pictures/ZoomBoxCamera. */
    fun saveJpeg(file: File) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.IS_PENDING, 1)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ZoomBoxCamera")
            }
            val resolver = context.contentResolver
            val contentUri =
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(contentUri, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { `in` -> `in`.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        } catch (e: Exception) { Log.e(TAG, "Error saving to gallery", e) }
    }

    /**
     * Inserts [file] into MediaStore under Pictures/ZoomBoxCamera/RAW. RAW files
     * are kept separate from JPEGs both by extension and by subfolder so the
     * retro-roll filmstrip (which decodes JPEGs) isn't polluted.
     */
    fun saveDng(file: File) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                put(MediaStore.Images.Media.IS_PENDING, 1)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ZoomBoxCamera/RAW")
            }
            val resolver = context.contentResolver
            val contentUri =
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(contentUri, values) ?: return
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { `in` -> `in`.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving DNG to gallery", e)
        }
    }

    /**
     * Deletes [file] everywhere (the public copy handed in, its same-name
     * app-private mirror, and the MediaStore rows so other gallery apps see
     * the removal) and returns the refreshed gallery list, newest-first.
     * [skipOrphanCleanup] mirrors [listPhotos].
     */
    fun deleteAndRefresh(file: File, skipOrphanCleanup: Boolean = false): List<File> {
        // The file passed in is whichever copy listPhotos surfaced (public
        // takes precedence). There may still be a same-name mirror in the
        // app-private dir — delete that too so a subsequent startup scan
        // doesn't re-surface it as a duplicate after the public copy was
        // removed.
        val privateMirror = privateDir()?.let { File(it, file.name) }
        listOfNotNull(file, privateMirror).distinct().forEach { candidate ->
            if (candidate.exists()) candidate.delete()
        }

        // Remove the MediaStore row we created in saveJpeg / saveDng so the
        // deletion propagates to OTHER gallery apps (Google Photos, Files
        // app, OEM gallery, etc.) instead of only cleaning up our own file
        // system copy. Without this, the vice-versa half of the
        // gallery-sync contract — "delete from in-app gallery" → "delete
        // from system gallery" — is broken; other apps keep showing the
        // photo until their next background scan re-detects the missing
        // file on disk (often hours later).
        deleteMediaStoreRow(file)

        return listPhotos(skipOrphanCleanup = skipOrphanCleanup)
    }

    /** Reads [file]'s EXIF into display-ready [ExifData] labels. */
    fun readExif(file: File): ExifData {
        return try {
            val exif = ExifInterface(file.absolutePath)
            val exposureTime = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
            val shutterSpeed = if (exposureTime > 0.0) {
                if (exposureTime < 1.0) { val denom = kotlin.math.round(1.0 / exposureTime).toInt(); "1/${denom}s" }
                else { "${kotlin.math.round(exposureTime).toInt()}s" }
            } else "--"
            val isoRaw = exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0)
            val iso = if (isoRaw > 0) "ISO $isoRaw" else "--"
            val orientationTag = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val orientation = when (orientationTag) {
                ExifInterface.ORIENTATION_ROTATE_90 -> "90°"
                ExifInterface.ORIENTATION_ROTATE_180 -> "180°"
                ExifInterface.ORIENTATION_ROTATE_270 -> "270°"
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> "Mirrored H"
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> "Mirrored V"
                ExifInterface.ORIENTATION_TRANSPOSE -> "90° Mirrored"
                ExifInterface.ORIENTATION_TRANSVERSE -> "270° Mirrored"
                else -> "--"
            }
            val name = file.nameWithoutExtension
            val focalMatch = Regex("""_(\d+)mm$""").find(name)
            val focalLength = focalMatch?.groupValues?.get(1)?.let { "${it}mm" } ?: "--"
            ExifData(focalLength = focalLength, shutterSpeed = shutterSpeed, iso = iso, orientation = orientation)
        } catch (e: Exception) { Log.e(TAG, "Error reading EXIF", e); ExifData() }
    }

    /**
     * Delete app-private cache mirrors whose MediaStore row is no longer
     * present, so external deletes (file manager, MTP, Google Photos) are
     * reflected in the in-app gallery even though the app-private copy is
     * technically still on disk.
     *
     * The capture pipeline writes each photo to BOTH places
     * (`getExternalFilesDir(DIRECTORY_PICTURES)/...` for the working copy and
     * `Pictures/ZoomBoxCamera/...` via `MediaStore.insert` for the public
     * mirror). If the user removes the public side, the working copy
     * lingers and `listPhotos`'s distinctBy-{name} pass falls back to it,
     * re-surfacing the supposedly-deleted photo. This pass makes the
     * external delete two-sided by also trashing the cache mirror.
     *
     * Done as a single bulk MediaStore query rather than one round-trip per
     * private file: even on a heavily-used device a few hundred files would
     * mean hundreds of binder calls, vs. one. We also scope the bulk probe to
     * our own folder so a name collision with an unrelated MediaStore row
     * (some other app's `IMG_20240301_120000.jpg` for instance) can't fool
     * us into keeping a true orphan.
     *
     * Mid-capture safety: callers should skip this pass while a capture is in
     * flight; between the working-copy rename and the MediaStore.insert call
     * a file legitimately has no row yet, and we'd race-delete it.
     */
    private fun cleanupOrphanPrivateFiles(publicFileNames: Set<String>) {
        val privateDir = privateDir() ?: return
        val candidateFiles = privateDir.listFiles()?.filter { f ->
            f.isFile && f.extension.lowercase() in listOf("jpg", "jpeg", "dng")
        } ?: return

        // Candidates that aren't already covered by the public set need a
        // MediaStore probe. Same slash-anchored scope as listPublicPhotos…
        // so we don't accidentally accept a name that already exists
        // somewhere else on the device.
        val orphanCandidates = candidateFiles.filter { it.name !in publicFileNames }
        if (orphanCandidates.isEmpty()) return

        val resolver = context.contentResolver
        val imageUri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val folderBase = Environment.DIRECTORY_PICTURES.lowercase()
        // Slash-anchored: = (bare or trailing-slash root) + LIKE "ZoomBoxCamera/%"
        // Mirrors listPublicPhotosViaMediaStore so probe-cached rows match the
        // same population the public query uses.
        val selectionArgs = mutableListOf<String>()
        val selection = buildString {
            append(MediaStore.Images.Media.DISPLAY_NAME).append(" IN (")
            repeat(orphanCandidates.size) { idx ->
                if (idx > 0) append(", ")
                append("?")
                selectionArgs += orphanCandidates[idx].name
            }
            append(") AND (")
            // 'Pictures/zoomboxcamera'
            append("LOWER(").append(MediaStore.Images.Media.RELATIVE_PATH).append(") = ?")
            selectionArgs += "$folderBase/zoomboxcamera"
            append(" OR ")
            // 'Pictures/zoomboxcamera/'
            append("LOWER(").append(MediaStore.Images.Media.RELATIVE_PATH).append(") = ?")
            selectionArgs += "$folderBase/zoomboxcamera/"
            append(" OR ")
            // 'Pictures/zoomboxcamera/...'
            append("LOWER(").append(MediaStore.Images.Media.RELATIVE_PATH).append(") LIKE ?")
            selectionArgs += "$folderBase/zoomboxcamera/%"
            append(")")
        }

        // Probe once: collect all display names that have a row under our
        // folder. Anything not in this set is an orphan.
        val protectedNames: Set<String> = runCatching {
            resolver.query(
                imageUri,
                arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
                selection,
                selectionArgs.toTypedArray(),
                null
            )?.use { cursor ->
                val idx = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                buildSet {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) add(name)
                    }
                }
            }
        }.getOrNull() ?: emptySet()

        var deletedCount = 0
        for (f in orphanCandidates) {
            if (f.name in protectedNames) continue
            val deleted = runCatching { f.delete() }.getOrDefault(false)
            if (deleted) deletedCount++
        }
        if (deletedCount > 0) {
            Log.i(TAG, "Cleaned up $deletedCount orphan private file(s)")
        }
    }

    /**
     * Query MediaStore for every image in (and under) `Pictures/ZoomBoxCamera/`
     * and project the result back into `File` objects so the rest of the
     * capture/delete pipeline keeps working with `File` references unchanged.
     *
     * Why MediaStore instead of `File.listFiles()` on the public tree:
     *   - On Android 10+ scoped storage, `File.listFiles()` returns null/empty
     *     on `/sdcard/Pictures/...` unless the app has MANAGE_EXTERNAL_STORAGE
     *     or the corresponding READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE
     *     permission. Our app previously assumed the public tree was readable
     *     and would silently show an empty gallery whenever the user reinstalled
     *     (UID changes → OS no longer treats us as the owner of pre-reinstall
     *     rows, and File API falls back to "no access").
     *   - MediaStore queries work for our own rows even without the runtime
     *     permission (we implicitly own them) and naturally return both our
     *     own files and any other-app file in the same folder once the
     *     permission is granted.
     *
     * RELATIVE_PATH matching uses slash-anchored equality + LIKE so we cover
     * every vendor normalizer without over-matching sibling folders:
     *   - "Pictures/ZoomBoxCamera"        (= exact match for non-slash form)
     *   - "Pictures/ZoomBoxCamera/"       (= exact match for AOSP normalised form)
     *   - "Pictures/ZoomBoxCamera/RAW/"   (LIKE prefix for subfolders)
     *   - any future subfolder the user might create (LIKE prefix)
     * Importantly we reject similarly-named sibling folders like
     * "Pictures/ZoomBoxCameraBackup/" or "Pictures/ZoomBoxCamera2/" because
     * the LIKE pattern is anchored with the trailing slash instead of a bare
     * `%` — otherwise those folders would also appear in the gallery.
     * IS_PENDING = 0 filters out half-written rows that the capture pipeline
     * hasn't finished copying yet — without this the gallery can briefly show
     * a thumbnail pointing at a file that's still being flushed to disk.
     */
    private fun listPublicPhotosViaMediaStore(): List<File> {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            @Suppress("DEPRECATION") MediaStore.Images.Media.DATA
        )
        // IS_PENDING = 0 keeps in-flight writes out of the gallery.
        // MIME_TYPE filter narrows to JPEG/DNG so we don't accidentally pick up
        // any other image format a vendor MediaProvider stuffs under our folder.
        // RELATIVE_PATH matching is slash-anchored: '=' fixes the bare root
        // (some vendors keep "Pictures/ZoomBoxCamera" with no trailing slash;
        // AOSP inserts "Pictures/ZoomBoxCamera/"); the two LIKE patterns only
        // match true descendants ("ZoomBoxCamera/RAW/…", or any future
        // subfolder like "ZoomBoxCamera/2024/…"). Without the slash anchor a
        // sibling folder such as "Pictures/ZoomBoxCameraBackup/" or
        // "Pictures/ZoomBoxCamera2/" would also satisfy the prefix match and
        // we'd leak foreign images into the gallery.
        val selection = "${MediaStore.Images.Media.IS_PENDING} = 0 " +
            "AND (${MediaStore.Images.Media.MIME_TYPE} = ? " +
                 "OR ${MediaStore.Images.Media.MIME_TYPE} = ?) " +
            "AND (LOWER(${MediaStore.Images.Media.RELATIVE_PATH}) = ? " +
                 "OR LOWER(${MediaStore.Images.Media.RELATIVE_PATH}) LIKE ? " +
                 "OR LOWER(${MediaStore.Images.Media.RELATIVE_PATH}) LIKE ?)"
        val args = arrayOf(
            "image/jpeg", "image/x-adobe-dng",
            "${Environment.DIRECTORY_PICTURES}/zoomboxcamera/".lowercase(),
            "${Environment.DIRECTORY_PICTURES}/zoomboxcamera".lowercase(),
            "${Environment.DIRECTORY_PICTURES}/zoomboxcamera/%".lowercase()
        )
        // DATE_ADDED DESC matches the newest-first gallery ordering
        // (lastModified on File can drift if a user copies files in via MTP).
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        return runCatching {
            resolver.query(collection, projection, selection, args, sortOrder)?.use { cursor ->
                @Suppress("DEPRECATION")
                val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                val result = mutableListOf<File>()
                while (cursor.moveToNext()) {
                    @Suppress("DEPRECATION")
                    val dataPath = cursor.getString(dataIdx)
                    if (dataPath.isNullOrBlank()) continue
                    val f = File(dataPath)
                    // Skip rows whose on-disk file is gone — MediaStore rows
                    // can outlive the actual file during a half-completed delete;
                    // listing them in the gallery would dereference to nothing.
                    if (f.exists()) result.add(f)
                }
                result
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /**
     * Removes the MediaStore row(s) that correspond to [file] so the deletion
     * propagates to other gallery apps. Min SDK is 29 so we always deal in
     * scoped-storage semantics: the row was inserted by us via
     * MediaStore.Images.Media with a known absolute DATA path.
     *
     * The WHERE clause matches DISPLAY_NAME + DATA for two reasons:
     *   - DISPLAY_NAME alone risks colliding with a same-named JPEG from
     *     another app (e.g. user copies IMG_1234.jpg into another folder);
     *   - RELATIVE_PATH is unreliable as a selection key: saveJpeg writes it
     *     without a trailing slash while saveDng writes
     *     "Pictures/ZoomBoxCamera/RAW/", and the platform MediaProvider
     *     doesn't always normalise the trailing slash on insert, so a
     *     RELATIVE_PATH comparison can fail to match our own freshly-written
     *     rows. DATA is the canonical "this file lives at this absolute
     *     path" column and works for both the JPEG root and the RAW subfolder.
     *
     * Deletion is best-effort: zero rows deleted is fine (file may have been
     * only in the app-private directory and never inserted into the public
     * tree). We log the count so it's traceable but don't surface as an error.
     */
    private fun deleteMediaStoreRow(file: File) {
        try {
            val resolver = context.contentResolver
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            @Suppress("DEPRECATION")
            val rows = resolver.delete(
                collection,
                "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.DATA} = ?",
                arrayOf(file.name, file.absolutePath)
            )
            Log.i(TAG, "Deleted $rows MediaStore row(s) for ${file.name}")
        } catch (e: SecurityException) {
            // Vendors have been known to throw SecurityException when revoking
            // a delete on a row owned by a different package; log + swallow
            // rather than failing the in-app delete.
            Log.e(TAG, "Permission error deleting MediaStore row for ${file.name}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting MediaStore row for ${file.name}", e)
        }
    }

    private companion object {
        const val TAG = "PhotoStore"
    }
}
