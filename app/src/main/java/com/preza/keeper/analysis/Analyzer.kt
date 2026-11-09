package com.preza.keeper.analysis

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class Analyzer {

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.08f)
                .build()
        )
    }

    fun analyze(bmp: Bitmap): Features {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        val gray = FloatArray(w * h)
        var crush = 0
        var blown = 0
        var sum = 0.0
        val hueBins = IntArray(24)
        var colorful = 0
        var sampled = 0
        val hsv = FloatArray(3)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val b = c and 255
            val y = 0.299f * r + 0.587f * g + 0.114f * b
            gray[i] = y
            sum += y
            if (y < 12f) crush++
            if (y > 245f) blown++
            if (i % 7 == 0) {
                sampled++
                Color.RGBToHSV(r, g, b, hsv)
                if (hsv[1] > 0.31f && hsv[2] > 0.24f) {
                    colorful++
                    hueBins[(hsv[0] / 15f).toInt().coerceIn(0, 23)]++
                }
            }
        }
        val n = (w * h).toFloat()

        val faces = detectFaces(bmp)
        var faceSharp = 0f
        var eyes = -1f
        var smile = -1f
        var cut = false
        for (f in faces) {
            val bb = f.boundingBox
            val r = Rect(max(bb.left, 1), max(bb.top, 1), min(bb.right, w - 2), min(bb.bottom, h - 2))
            if (r.width() > 8 && r.height() > 8) faceSharp = max(faceSharp, sharpness(gray, w, h, r))
            val le = f.leftEyeOpenProbability
            val re = f.rightEyeOpenProbability
            if (le != null && re != null) {
                val e = min(le, re)
                eyes = if (eyes < 0f) e else min(eyes, e)
            }
            f.smilingProbability?.let { smile = max(smile, it) }
            if (bb.left <= 2 || bb.top <= 2 || bb.right >= w - 2 || bb.bottom >= h - 2) cut = true
        }

        return Features(
            sharp = sharpness(gray, w, h, Rect(1, 1, w - 2, h - 2)),
            faceSharp = faceSharp,
            crush = crush / n,
            blown = blown / n,
            mean = (sum / n).toFloat(),
            color = colorName(hueBins, colorful, sampled),
            fp = fingerprint(gray, w, h),
            faces = faces.size,
            eyesOpen = eyes,
            smile = smile,
            faceCut = cut
        )
    }

    private fun detectFaces(bmp: Bitmap): List<Face> = try {
        Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0)))
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Mean absolute Laplacian measured only on the 10% strongest edges inside [r].
     * Measuring on edges keeps a soft background (bokeh, night sky) from dragging
     * a sharp subject down.
     */
    private fun sharpness(g: FloatArray, w: Int, h: Int, r: Rect): Float {
        val left = r.left.coerceAtLeast(1)
        val top = r.top.coerceAtLeast(1)
        val right = r.right.coerceAtMost(w - 2)
        val bottom = r.bottom.coerceAtMost(h - 2)
        if (right <= left || bottom <= top) return 0f
        val count = (right - left + 1) * (bottom - top + 1)
        val mags = FloatArray(count)
        val laps = FloatArray(count)
        val hist = IntArray(256)
        var k = 0
        for (y in top..bottom) {
            val row = y * w
            for (x in left..right) {
                val i = row + x
                val gx = (g[i - w + 1] + 2 * g[i + 1] + g[i + w + 1]) - (g[i - w - 1] + 2 * g[i - 1] + g[i + w - 1])
                val gy = (g[i + w - 1] + 2 * g[i + w] + g[i + w + 1]) - (g[i - w - 1] + 2 * g[i - w] + g[i - w + 1])
                val m = sqrt(gx * gx + gy * gy)
                mags[k] = m
                laps[k] = abs(4 * g[i] - g[i - 1] - g[i + 1] - g[i - w] - g[i + w])
                hist[(m / 6f).toInt().coerceIn(0, 255)]++
                k++
            }
        }
        // 90th percentile of edge strength
        val target = (count * 0.9).toInt()
        var acc = 0
        var bin = 0
        while (bin < 255 && acc + hist[bin] < target) { acc += hist[bin]; bin++ }
        val thr = bin * 6f
        var s = 0.0
        var c = 0
        for (j in 0 until k) if (mags[j] >= thr) { s += laps[j]; c++ }
        return if (c == 0) 0f else (s / c).toFloat()
    }

    /** Area-average down to 24x24, light blur, then normalise. Robust on dark scenes. */
    private fun fingerprint(g: FloatArray, w: Int, h: Int): ByteArray {
        val n = Features.FP
        val cells = FloatArray(n * n)
        for (cy in 0 until n) {
            val y0 = cy * h / n
            val y1 = max(y0 + 1, (cy + 1) * h / n)
            for (cx in 0 until n) {
                val x0 = cx * w / n
                val x1 = max(x0 + 1, (cx + 1) * w / n)
                var s = 0f
                for (y in y0 until y1) for (x in x0 until x1) s += g[y * w + x]
                cells[cy * n + cx] = s / ((y1 - y0) * (x1 - x0))
            }
        }
        val blurred = FloatArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            var s = 0f
            var c = 0
            for (dy in -1..1) for (dx in -1..1) {
                val yy = y + dy
                val xx = x + dx
                if (yy in 0 until n && xx in 0 until n) { s += cells[yy * n + xx]; c++ }
            }
            blurred[y * n + x] = s / c
        }
        val mean = blurred.average().toFloat()
        var v = 0f
        for (b in blurred) v += (b - mean) * (b - mean)
        val sd = sqrt(v / blurred.size) + 1e-3f
        return ByteArray(n * n) { i -> ((blurred[i] - mean) / sd * 32f).toInt().coerceIn(-127, 127).toByte() }
    }

    private fun colorName(bins: IntArray, colorful: Int, sampled: Int): String {
        if (sampled == 0 || colorful < sampled * 0.05f) return "Muted"
        var best = 0
        for (i in bins.indices) if (bins[i] > bins[best]) best = i
        val hue = best * 15f + 7.5f
        return when {
            hue < 15f || hue >= 345f -> "Red"
            hue < 45f -> "Orange"
            hue < 70f -> "Yellow"
            hue < 165f -> "Green"
            hue < 200f -> "Teal"
            hue < 260f -> "Blue"
            hue < 300f -> "Purple"
            else -> "Magenta"
        }
    }
}
