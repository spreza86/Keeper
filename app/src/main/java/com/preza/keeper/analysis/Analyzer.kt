package com.preza.keeper.analysis

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Measures one photo (0.7.3 engine). Runs on the largest preview inside the RAW, entirely on the phone:
 * the face and object models ship inside the app, nothing is downloaded.
 *
 * Main ideas, each from a photo Saul sent:
 * - Sharpness is judged on the main subject (the face, an animal or object, or the focus point), not the
 *   whole frame, so a soft background (bokeh) never drags a sharp subject down.
 * - Only areas with real edges count: big black or flat areas (silhouettes, sky) are ignored.
 * - Grain is smoothed out before measuring, so it doesn't pass for detail.
 * - Areas whose edges all point one way are streaks (motion, light trails) and never count as a sharp background.
 */
class Analyzer {

    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.06f)
                .build()
        )
    }

    private val objectDetector by lazy {
        ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                .enableMultipleObjects()
                .build()
        )
    }

    private class Stat(val acu: Float, val detail: Float, val coh: Float, val edges: Int)

    /** [focus] is already turned upright. [iso] decides how much grain to smooth out. */
    fun analyze(bmp: Bitmap, focus: FocusPoint?, iso: Int?): Features {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        var g = FloatArray(w * h)
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
            val gg = (c shr 8) and 255
            val b = c and 255
            val y = 0.299f * r + 0.587f * gg + 0.114f * b
            g[i] = y
            sum += y
            if (y < 12f) crush++
            if (y > 245f) blown++
            if (i % 7 == 0) {
                sampled++
                Color.RGBToHSV(r, gg, b, hsv)
                if (hsv[1] > 0.31f && hsv[2] > 0.24f) {
                    colorful++
                    hueBins[(hsv[0] / 15f).toInt().coerceIn(0, 23)]++
                }
            }
        }
        val n = (w * h).toFloat()
        val fp = fingerprint(g, w, h)
        val mean = (sum / n).toFloat()

        // Smooth out grain: once always, twice at high ISO.
        g = boxBlur(g, w, h)
        if ((iso ?: 0) >= 3200) g = boxBlur(g, w, h)

        // ---- faces ----
        val faces = detectFaces(bmp)
        val fx = focus?.let { PointF(it.x * w, it.y * h) }
        val mainFace = faces.firstOrNull { f -> fx != null && grow(f.boundingBox, 0.3f).contains(fx.x.toInt(), fx.y.toInt()) }
            ?: faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
        var faceSharp = 0f
        var eyes = -1f
        var smile = -1f
        var laugh = false
        var cut = false
        if (mainFace != null) {
            val bb = clip(mainFace.boundingBox, w, h)
            if (bb.width() > 8 && bb.height() > 8) faceSharp = region(g, w, h, bb).acu
            smile = mainFace.smilingProbability ?: -1f
            laugh = isLaugh(mainFace)
            val le = mainFace.leftEyeOpenProbability
            val re = mainFace.rightEyeOpenProbability
            // Eyes only on a face big enough to read, and never when laughing.
            if (le != null && re != null && !laugh && mainFace.boundingBox.height() >= 0.08f * min(w, h)) eyes = min(le, re)
            val fb = mainFace.boundingBox
            cut = fb.left <= 2 || fb.top <= 2 || fb.right >= w - 2 || fb.bottom >= h - 2
        }

        // ---- main subject ----
        val objects = detectObjects(bmp).filter { r -> val a = r.width() * r.height() / n; a in 0.04f..0.9f }
        val mainObject = objects.filter { fx != null && it.contains(fx.x.toInt(), fx.y.toInt()) }.maxByOrNull { it.width() * it.height() }
            ?: objects.maxByOrNull { it.width() * it.height() }
        val focusBox = fx?.let { clip(Rect((it.x - 0.1f * w).toInt(), (it.y - 0.1f * h).toInt(), (it.x + 0.1f * w).toInt(), (it.y + 0.1f * h).toInt()), w, h) }

        var src = Features.SRC_NONE
        var subj: Rect? = null
        when {
            mainFace != null -> { src = Features.SRC_FACE; subj = clip(mainFace.boundingBox, w, h) }
            mainObject != null -> { src = Features.SRC_OBJECT; subj = clip(mainObject, w, h) }
        }
        var focusState = if (fx == null) Features.FOCUS_UNKNOWN else Features.FOCUS_NO_SUBJECT
        if (subj != null && fx != null) {
            val on = grow(subj, 0.15f).contains(fx.x.toInt(), fx.y.toInt())
            val area = subj.width() * subj.height() / n
            if (!on && area < 0.10f && src == Features.SRC_FACE) {
                // A small face somewhere else: what the camera focused on is the subject.
                src = Features.SRC_FOCUS; subj = focusBox; focusState = Features.FOCUS_NO_SUBJECT
            } else focusState = if (on) Features.FOCUS_ON_SUBJECT else Features.FOCUS_OFF_SUBJECT
        }
        if (subj == null && focusBox != null) { src = Features.SRC_FOCUS; subj = focusBox }

        // ---- grid map: sharpness per area, streaks and flat areas ----
        val cells = Array(Features.GRID_W * Features.GRID_H) { k ->
            val cx = k % Features.GRID_W
            val cy = k / Features.GRID_W
            region(g, w, h, Rect(cx * w / Features.GRID_W, cy * h / Features.GRID_H, (cx + 1) * w / Features.GRID_W, (cy + 1) * h / Features.GRID_H))
        }
        val map = ByteArray(cells.size)
        val clean = ArrayList<Pair<Int, Float>>()
        var detailed = 0
        var streaky = 0
        cells.forEachIndexed { k, s ->
            when {
                s.edges < 40 || s.detail < 0.03f -> map[k] = Features.CELL_FLAT.toByte()
                s.coh > STREAK_COH -> { map[k] = Features.CELL_STREAK.toByte(); detailed++; streaky++ }
                else -> { map[k] = (s.acu * 2500f).toInt().coerceIn(0, 250).toByte(); detailed++; clean += k to s.acu }
            }
        }
        val grownSubj = subj?.let { grow(it, 0.1f) }
        fun outside(k: Int): Boolean {
            if (grownSubj == null) return true
            val cx = ((k % Features.GRID_W) + 0.5f) * w / Features.GRID_W
            val cy = ((k / Features.GRID_W) + 0.5f) * h / Features.GRID_H
            return !grownSubj.contains(cx.toInt(), cy.toInt())
        }

        return Features(
            sharp = region(g, w, h, Rect(1, 1, w - 2, h - 2)).acu,
            faceSharp = faceSharp,
            crush = crush / n,
            blown = blown / n,
            mean = mean,
            color = colorName(hueBins, colorful, sampled),
            fp = fp,
            faces = faces.size,
            eyesOpen = eyes,
            smile = smile,
            faceCut = cut,
            subjSharp = subj?.let { region(g, w, h, it).acu } ?: 0f,
            bgSharp = top(clean.filter { outside(it.first) }.map { it.second }),
            bestSharp = top(clean.map { it.second }),
            streaks = if (detailed == 0) 0f else streaky.toFloat() / detailed,
            detail = detailed.toFloat() / cells.size,
            subjArea = subj?.let { it.width() * it.height() / n } ?: 0f,
            subjSrc = src,
            focus = focusState,
            focusSharp = focusBox?.let { region(g, w, h, it).acu } ?: 0f,
            laugh = laugh,
            map = map
        )
    }

    /** The 90th percentile, so one odd area can't decide alone. */
    private fun top(v: List<Float>): Float {
        if (v.isEmpty()) return 0f
        val s = v.sorted()
        return s[((s.size * 0.9f).toInt() - 1).coerceIn(0, s.size - 1)]
    }

    private fun isLaugh(f: Face): Boolean {
        val s = f.smilingProbability ?: return false
        if (s >= 0.85f) return true
        val bottom = f.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position ?: return false
        val left = f.getLandmark(FaceLandmark.MOUTH_LEFT)?.position ?: return false
        val right = f.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position ?: return false
        val open = (bottom.y - (left.y + right.y) / 2f) / f.boundingBox.height().coerceAtLeast(1)
        return s >= 0.5f && open >= 0.12f
    }

    private fun detectFaces(bmp: Bitmap): List<Face> = try {
        Tasks.await(faceDetector.process(InputImage.fromBitmap(bmp, 0)))
    } catch (e: Exception) {
        emptyList()
    }

    private fun detectObjects(bmp: Bitmap): List<Rect> = try {
        Tasks.await(objectDetector.process(InputImage.fromBitmap(bmp, 0))).map { it.boundingBox }
    } catch (e: Exception) {
        emptyList()
    }

    private fun grow(r: Rect, k: Float): Rect {
        val dx = (r.width() * k).toInt()
        val dy = (r.height() * k).toInt()
        return Rect(r.left - dx, r.top - dy, r.right + dx, r.bottom + dy)
    }

    private fun clip(r: Rect, w: Int, h: Int) = Rect(max(r.left, 1), max(r.top, 1), min(r.right, w - 2), min(r.bottom, h - 2))

    @Suppress("unused")
    private fun RectF.toRect() = Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())

    /**
     * Edge steepness inside [r]: on real edges only (gradient above the grain level), the strongest 20% of them,
     * laplacian divided by gradient, so contrast doesn't matter. Also the share of real edges ([Stat.detail]) and
     * how much they all point the same way ([Stat.coh], near 1 for streaks).
     */
    private fun region(g: FloatArray, w: Int, h: Int, r: Rect): Stat {
        val left = r.left.coerceAtLeast(1)
        val top = r.top.coerceAtLeast(1)
        val right = r.right.coerceAtMost(w - 2)
        val bottom = r.bottom.coerceAtMost(h - 2)
        if (right <= left || bottom <= top) return Stat(0f, 0f, 0f, 0)
        val area = (right - left + 1) * (bottom - top + 1)
        val hist = IntArray(256)
        var edges = 0
        for (y in top..bottom) {
            val row = y * w
            for (x in left..right) {
                val m = mag(g, row + x, w)
                if (m >= EDGE_MIN) { edges++; hist[(m / 8f).toInt().coerceIn(0, 255)]++ }
            }
        }
        if (edges < 40) return Stat(0f, edges.toFloat() / area, 0f, edges)
        val target = (edges * 0.8f).toInt()
        var acc = 0
        var bin = 0
        while (bin < 255 && acc + hist[bin] < target) { acc += hist[bin]; bin++ }
        val thr = max(bin * 8f, EDGE_MIN)
        var sl = 0.0; var sm = 0.0
        var jxx = 0.0; var jyy = 0.0; var jxy = 0.0
        for (y in top..bottom) {
            val row = y * w
            for (x in left..right) {
                val i = row + x
                val gx = (g[i - w + 1] + 2 * g[i + 1] + g[i + w + 1]) - (g[i - w - 1] + 2 * g[i - 1] + g[i + w - 1])
                val gy = (g[i + w - 1] + 2 * g[i + w] + g[i + w + 1]) - (g[i - w - 1] + 2 * g[i - w] + g[i - w + 1])
                val m = sqrt(gx * gx + gy * gy)
                if (m < EDGE_MIN) continue
                jxx += gx * gx; jyy += gy * gy; jxy += gx * gy
                if (m >= thr) {
                    sl += kotlin.math.abs(4 * g[i] - g[i - 1] - g[i + 1] - g[i - w] - g[i + w])
                    sm += m
                }
            }
        }
        val coh = if (jxx + jyy <= 0.0) 0.0 else sqrt((jxx - jyy) * (jxx - jyy) + 4 * jxy * jxy) / (jxx + jyy)
        return Stat(if (sm <= 0.0) 0f else (sl / sm).toFloat(), edges.toFloat() / area, coh.toFloat(), edges)
    }

    private fun mag(g: FloatArray, i: Int, w: Int): Float {
        val gx = (g[i - w + 1] + 2 * g[i + 1] + g[i + w + 1]) - (g[i - w - 1] + 2 * g[i - 1] + g[i + w - 1])
        val gy = (g[i + w - 1] + 2 * g[i + w] + g[i + w + 1]) - (g[i - w - 1] + 2 * g[i - w] + g[i - w + 1])
        return sqrt(gx * gx + gy * gy)
    }

    private fun boxBlur(g: FloatArray, w: Int, h: Int): FloatArray {
        val tmp = FloatArray(g.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val a = g[row + max(x - 1, 0)]; val b = g[row + x]; val c = g[row + min(x + 1, w - 1)]
                tmp[row + x] = (a + b + c) / 3f
            }
        }
        val out = FloatArray(g.size)
        for (y in 0 until h) {
            val up = max(y - 1, 0) * w; val row = y * w; val dn = min(y + 1, h - 1) * w
            for (x in 0 until w) out[row + x] = (tmp[up + x] + tmp[row + x] + tmp[dn + x]) / 3f
        }
        return out
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

    companion object {
        /** Gradient strength below this is grain or flat tone, not an edge. */
        const val EDGE_MIN = 40f
        /** Areas whose edges point this much the same way are streaks. */
        const val STREAK_COH = 0.55f
    }
}
