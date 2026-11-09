package com.preza.keeper.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface

/** Everything Keeper reads from the phone's photo library. Read-only, except the trash request. */
class MediaRepo(private val ctx: Context) {

    private val cr = ctx.contentResolver
    private val images: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    class Item(
        val id: Long,
        val name: String,
        val path: String,
        val mime: String,
        val modified: Long,
        val taken: Long,
        val size: Long
    )

    fun uri(id: Long): Uri = ContentUris.withAppendedId(images, id)

    private fun list(selection: String?, args: Array<String>?): List<Item> {
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.SIZE
        )
        val out = ArrayList<Item>()
        cr.query(images, proj, selection, args, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(proj[0])
            val iName = c.getColumnIndexOrThrow(proj[1])
            val iPath = c.getColumnIndexOrThrow(proj[2])
            val iMime = c.getColumnIndexOrThrow(proj[3])
            val iMod = c.getColumnIndexOrThrow(proj[4])
            val iTaken = c.getColumnIndexOrThrow(proj[5])
            val iSize = c.getColumnIndexOrThrow(proj[6])
            while (c.moveToNext()) {
                out += Item(
                    id = c.getLong(iId),
                    name = c.getString(iName) ?: "",
                    path = c.getString(iPath) ?: "",
                    mime = c.getString(iMime) ?: "",
                    modified = c.getLong(iMod),
                    taken = c.getLong(iTaken),
                    size = c.getLong(iSize)
                )
            }
        }
        return out
    }

    fun camera(folder: String) = list("${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf("$folder%"))

    fun edits(folder: String) = list("${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf("$folder%"))

    /** Every other photo on the phone with a date: these lend their location to camera shots. */
    fun phone(cameraFolder: String, editsFolder: String): List<PhonePhoto> =
        list(
            "${MediaStore.Images.Media.RELATIVE_PATH} NOT LIKE ? AND ${MediaStore.Images.Media.RELATIVE_PATH} NOT LIKE ? AND ${MediaStore.Images.Media.DATE_TAKEN} > 0",
            arrayOf("$cameraFolder%", "$editsFolder%")
        ).map { PhonePhoto(it.id, it.taken) }

    /** Folders that contain photos, with counts, for the folder picker. */
    fun folders(): List<Pair<String, Int>> {
        val counts = HashMap<String, Int>()
        cr.query(images, arrayOf(MediaStore.Images.Media.RELATIVE_PATH), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val p = c.getString(0) ?: continue
                counts[p] = (counts[p] ?: 0) + 1
            }
        }
        return counts.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    /** Reads EXIF. Asks for the original file so GPS isn't redacted (needs ACCESS_MEDIA_LOCATION). */
    fun exif(id: Long): ExifInterface? {
        val base = uri(id)
        val tries = listOf(
            { MediaStore.setRequireOriginal(base) },
            { base }
        )
        for (t in tries) {
            try {
                cr.openFileDescriptor(t(), "r")?.use { return ExifInterface(it.fileDescriptor) }
            } catch (e: Exception) {
                // fall through to the next attempt
            }
        }
        return null
    }

    fun phoneMeta(id: Long): PhoneMeta {
        val e = exif(id) ?: return PhoneMeta(null, null, null)
        val ll = e.latLong
        return PhoneMeta(ll?.getOrNull(0), ll?.getOrNull(1), parseOffset(e.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)))
    }

    companion object {
        /** "+08:00" -> 480 */
        fun parseOffset(s: String?): Int? {
            if (s == null || s.length < 6) return null
            val sign = when (s[0]) { '+' -> 1; '-' -> -1; else -> return null }
            val hh = s.substring(1, 3).toIntOrNull() ?: return null
            val mm = s.substring(4, 6).toIntOrNull() ?: return null
            return sign * (hh * 60 + mm)
        }
    }
}
