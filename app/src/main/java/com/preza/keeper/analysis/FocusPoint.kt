package com.preza.keeper.analysis

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Where the camera focused, as a share of the width and height of the un-rotated sensor image (0..1). */
class FocusPoint(val x: Float, val y: Float) {
    /** The same point in the upright photo, after the EXIF orientation is applied. */
    fun rotated(orientation: Int): FocusPoint = when (orientation) {
        6 -> FocusPoint(1f - y, x)      // turned 90 degrees clockwise
        3 -> FocusPoint(1f - x, 1f - y)
        8 -> FocusPoint(y, 1f - x)
        else -> this
    }
}

/**
 * Reads the focus point a camera saved in its RAW file. One reader per brand, so others can be added later.
 * Only Sony exists for now.
 */
interface FocusReader {
    /** True when this reader understands files from this camera (EXIF Make / Model). */
    fun handles(make: String?, model: String?): Boolean
    fun read(t: Tiff): FocusPoint?
}

object FocusReaders {
    private val all: List<FocusReader> = listOf(SonyFocusReader)

    fun read(t: Tiff, make: String?, model: String?): FocusPoint? =
        all.firstOrNull { it.handles(make, model) }?.let { runCatching { it.read(t) }.getOrNull() }
}

/**
 * Sony keeps the focus point in its MakerNote (inside the EXIF block) as four numbers:
 * image width, image height, then x and y of the focus point in those units.
 * Tag 0x204a ("FocusLocation2") on newer bodies, 0x2027 ("FocusLocation") on older ones.
 */
object SonyFocusReader : FocusReader {
    override fun handles(make: String?, model: String?): Boolean =
        make?.contains("sony", ignoreCase = true) == true || model?.startsWith("ILCE", ignoreCase = true) == true

    override fun read(t: Tiff): FocusPoint? {
        val ifd0 = t.ifd(t.firstIfd) ?: return null
        val exifOff = ifd0[0x8769]?.value ?: return null
        val exif = t.ifd(exifOff) ?: return null
        val mn = exif[0x927C] ?: return null
        val mnOff = mn.value
        val head = t.bytes(mnOff, 12) ?: return null
        val start = if (String(head, Charsets.US_ASCII).startsWith("SONY")) mnOff + 12 else mnOff
        val notes = t.ifd(start) ?: return null
        for (tag in intArrayOf(0x204a, 0x2027)) {
            val e = notes[tag] ?: continue
            if (e.type != 3 || e.count != 4L) continue
            val b = t.bytes(e.value, 8) ?: continue
            val bb = ByteBuffer.wrap(b).order(t.order)
            val w = bb.getShort(0).toInt() and 0xFFFF
            val h = bb.getShort(2).toInt() and 0xFFFF
            val x = bb.getShort(4).toInt() and 0xFFFF
            val y = bb.getShort(6).toInt() and 0xFFFF
            if (w <= 0 || h <= 0 || x <= 0 || y <= 0 || x > w || y > h) continue
            return FocusPoint(x.toFloat() / w, y.toFloat() / h)
        }
        return null
    }
}

/** Minimal TIFF reader over a file channel: enough to walk directories in RAW files. */
class Tiff private constructor(private val ch: FileChannel, val order: ByteOrder, val firstIfd: Long) {
    class Entry(val type: Int, val count: Long, val value: Long)

    val size: Long = ch.size()

    fun bytes(pos: Long, len: Int): ByteArray? {
        if (len <= 0 || pos < 0 || pos + len > size) return null
        val buf = ByteBuffer.allocate(len)
        var p = pos
        while (buf.hasRemaining()) {
            val r = ch.read(buf, p)
            if (r <= 0) break
            p += r
        }
        return if (buf.hasRemaining()) null else buf.array()
    }

    /** Tag -> entry. For values of 4 bytes or less, [Entry.value] is the value itself, else an offset. */
    fun ifd(off: Long): Map<Int, Entry>? {
        val cnt = bytes(off, 2) ?: return null
        val n = ByteBuffer.wrap(cnt).order(order).short.toInt() and 0xFFFF
        if (n == 0 || n > 1000) return null
        val raw = bytes(off + 2, n * 12) ?: return null
        val eb = ByteBuffer.wrap(raw).order(order)
        val out = HashMap<Int, Entry>()
        for (i in 0 until n) {
            val base = i * 12
            val tag = eb.getShort(base).toInt() and 0xFFFF
            val type = eb.getShort(base + 2).toInt() and 0xFFFF
            val count = eb.getInt(base + 4).toLong() and 0xFFFFFFFFL
            val value = if (type == 3 && count == 1L) (eb.getShort(base + 8).toInt() and 0xFFFF).toLong()
            else eb.getInt(base + 8).toLong() and 0xFFFFFFFFL
            out[tag] = Entry(type, count, value)
        }
        return out
    }

    companion object {
        fun open(ch: FileChannel): Tiff? {
            val buf = ByteBuffer.allocate(8)
            if (ch.read(buf, 0) < 8) return null
            val h = buf.array()
            val order = when {
                h[0] == 'I'.code.toByte() && h[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
                h[0] == 'M'.code.toByte() && h[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
                else -> return null
            }
            val bb = ByteBuffer.wrap(h).order(order)
            if (bb.getShort(2).toInt() != 42) return null
            return Tiff(ch, order, bb.getInt(4).toLong() and 0xFFFFFFFFL)
        }
    }
}
