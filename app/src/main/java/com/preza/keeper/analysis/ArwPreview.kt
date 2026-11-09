package com.preza.keeper.analysis

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Sony ARW files are TIFF containers. Besides the RAW data they carry JPEG previews
 * (a small thumbnail and a larger preview). We walk the TIFF directories, collect every
 * embedded JPEG and return the largest one. Decoding that JPEG is much faster than
 * decoding the RAW and is plenty for judging sharpness and exposure.
 */
object ArwPreview {

    fun extractJpeg(cr: ContentResolver, uri: Uri): ByteArray? = try {
        cr.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { extract(it) }
        }
    } catch (e: Exception) {
        null
    }

    fun readAll(cr: ContentResolver, uri: Uri): ByteArray? = try {
        cr.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) {
        null
    }

    private fun extract(ch: FileChannel): ByteArray? {
        val size = ch.size()
        val head = read(ch, 0, 8) ?: return null
        val order = when {
            head[0] == 'I'.code.toByte() && head[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
            head[0] == 'M'.code.toByte() && head[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
            else -> return null
        }
        val hb = ByteBuffer.wrap(head).order(order)
        if (hb.getShort(2).toInt() != 42) return null

        val candidates = mutableListOf<Pair<Long, Long>>()
        val visited = HashSet<Long>()
        val queue = ArrayDeque<Long>()
        queue.add(hb.getInt(4).toLong() and 0xFFFFFFFFL)

        while (queue.isNotEmpty() && visited.size < 40) {
            val off = queue.removeFirst()
            if (off <= 0 || off >= size || !visited.add(off)) continue
            val cnt = read(ch, off, 2) ?: continue
            val n = ByteBuffer.wrap(cnt).order(order).short.toInt() and 0xFFFF
            if (n == 0 || n > 1000) continue
            val entries = read(ch, off + 2, n * 12 + 4) ?: continue
            val eb = ByteBuffer.wrap(entries).order(order)
            var jOff = -1L
            var jLen = -1L
            for (i in 0 until n) {
                val base = i * 12
                val tag = eb.getShort(base).toInt() and 0xFFFF
                val type = eb.getShort(base + 2).toInt() and 0xFFFF
                val count = eb.getInt(base + 4).toLong() and 0xFFFFFFFFL
                val value = when (type) {
                    3 -> (eb.getShort(base + 8).toInt() and 0xFFFF).toLong()
                    else -> eb.getInt(base + 8).toLong() and 0xFFFFFFFFL
                }
                when (tag) {
                    0x0201 -> jOff = value                // JPEGInterchangeFormat
                    0x0202 -> jLen = value                // JPEGInterchangeFormatLength
                    0x014A -> {                           // SubIFDs
                        if (count == 1L) queue.add(value)
                        else if (count in 2..16) {
                            val arr = read(ch, value, (count * 4).toInt())
                            if (arr != null) {
                                val ab = ByteBuffer.wrap(arr).order(order)
                                for (k in 0 until count.toInt()) queue.add(ab.getInt(k * 4).toLong() and 0xFFFFFFFFL)
                            }
                        }
                    }
                }
            }
            if (jOff > 0 && jLen > 0 && jOff + jLen <= size) candidates.add(jOff to jLen)
            val next = eb.getInt(n * 12).toLong() and 0xFFFFFFFFL
            if (next != 0L) queue.add(next)
        }

        val best = candidates.filter { it.second < 30_000_000 }.maxByOrNull { it.second } ?: return null
        val bytes = read(ch, best.first, best.second.toInt()) ?: return null
        if (bytes.size < 2 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
        return bytes
    }

    private fun read(ch: FileChannel, pos: Long, len: Int): ByteArray? {
        if (len <= 0) return null
        val buf = ByteBuffer.allocate(len)
        var p = pos
        while (buf.hasRemaining()) {
            val r = ch.read(buf, p)
            if (r <= 0) break
            p += r
        }
        return if (buf.hasRemaining()) null else buf.array()
    }

    /** Decodes so that the longest side is close to [target] pixels, then rotates upright. */
    fun decodeSampled(bytes: ByteArray, target: Int, orientation: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        val longest = maxOf(raw.width, raw.height)
        val scaled = if (longest > target * 1.2) {
            val k = target.toFloat() / longest
            Bitmap.createScaledBitmap(raw, (raw.width * k).toInt(), (raw.height * k).toInt(), true)
        } else raw
        val degrees = when (orientation) {
            6 -> 90f
            3 -> 180f
            8 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return scaled
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, m, true)
    }
}
