package com.preza.keeper.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.preza.keeper.analysis.ArwPreview
import java.io.File

/** Small thumbnails saved during the scan, plus on-demand larger previews. */
object Thumbs {
    private val mem = LruCache<Long, ImageBitmap>(400)
    private val big = LruCache<Long, ImageBitmap>(4)

    private fun file(ctx: Context, id: Long) = File(File(ctx.filesDir, "thumbs").apply { mkdirs() }, "$id.jpg")

    fun write(ctx: Context, id: Long, bmp: Bitmap) {
        val longest = maxOf(bmp.width, bmp.height)
        val k = 360f / longest
        val small = if (k < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true) else bmp
        file(ctx, id).outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 82, it) }
    }

    fun load(ctx: Context, id: Long): ImageBitmap? {
        mem.get(id)?.let { return it }
        val f = file(ctx, id)
        if (!f.exists()) return null
        val b = BitmapFactory.decodeFile(f.path)?.asImageBitmap() ?: return null
        mem.put(id, b)
        return b
    }

    /** Reads the EXIF orientation straight from the file (no location access needed for this tag). */
    private fun orientationOf(ctx: Context, p: CamPhoto): Int = runCatching {
        ctx.contentResolver.openInputStream(p.uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

    fun loadLarge(ctx: Context, p: CamPhoto, orientation: Int? = null): ImageBitmap? {
        big.get(p.id)?.let { return it }
        val orientation = orientation ?: orientationOf(ctx, p)
        val bytes = if (p.mime.contains("jpeg")) ArwPreview.readAll(ctx.contentResolver, p.uri)
        else ArwPreview.extractJpeg(ctx.contentResolver, p.uri)
        val bmp = bytes?.let { ArwPreview.decodeSampled(it, 2400, orientation) } ?: return load(ctx, p.id)
        val ib = bmp.asImageBitmap()
        big.put(p.id, ib)
        return ib
    }

    fun clear(ctx: Context) {
        mem.evictAll(); big.evictAll()
        File(ctx.filesDir, "thumbs").deleteRecursively()
    }
}
