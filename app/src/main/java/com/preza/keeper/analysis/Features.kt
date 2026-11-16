package com.preza.keeper.analysis

/**
 * Everything Keeper measures about one photo. Saved in the cache so a rescan is fast.
 *
 * Sharpness numbers are "edge steepness": how abruptly real edges change, independent of contrast,
 * measured after smoothing out grain and only where the photo has real edges (flat or black areas are
 * ignored). They are compared with the other photos of the same outing, never used as absolute values.
 */
class Features(
    val sharp: Float,        // whole frame
    val faceSharp: Float,    // main face (0 if no face)
    val crush: Float,        // share of near-black pixels
    val blown: Float,        // share of blown-out pixels
    val mean: Float,         // average brightness 0..255
    val color: String,       // dominant color name
    val fp: ByteArray,       // 24x24 blurred "visual fingerprint" used to group near-identical shots
    val faces: Int,
    val eyesOpen: Float,     // eyes-open probability of the main face, -1 if unknown, small face or laughing
    val smile: Float,        // smile probability of the main face, -1 if unknown
    val faceCut: Boolean,    // the main face touches the frame edge
    // ---- 0.7.3 engine ----
    val subjSharp: Float = 0f,   // the main subject (focus point, face or object), 0 if none
    val bgSharp: Float = 0f,     // sharpest clean parts outside the subject (light streaks excluded)
    val bestSharp: Float = 0f,   // sharpest clean parts anywhere (light streaks excluded)
    val streaks: Float = 0f,     // share of detailed areas that are streaks (motion or light trails)
    val detail: Float = 1f,      // share of the frame with real edges (low = silhouette, sky, black)
    val subjArea: Float = 0f,    // share of the frame covered by the main subject
    val subjSrc: Int = SRC_NONE, // where the subject came from
    val focus: Int = FOCUS_UNKNOWN, // focus point found, and whether it sits on the subject
    val focusSharp: Float = 0f,  // sharpness around the focus point, 0 if unknown
    val laugh: Boolean = false,  // big laugh on the main face (closed eyes don't count then)
    val map: ByteArray = ByteArray(0), // GRID_W x GRID_H cells: sharpness 0..250, 252 streak, 255 no detail
    val version: Int = VERSION
) {
    /** The sharpness that matters: the main subject when there is one, otherwise the sharpest clean parts. */
    val subjectSharp: Float get() = when {
        subjSharp > 0f -> subjSharp
        bestSharp > 0f -> bestSharp
        faces > 0 && faceSharp > 0f -> faceSharp
        else -> sharp
    }

    companion object {
        /** Bump when the measurements change: photos measured by an older engine are analysed again. */
        const val VERSION = 2
        const val FP = 24
        const val GRID_W = 8
        const val GRID_H = 6
        const val CELL_STREAK: Int = 252
        const val CELL_FLAT: Int = 255

        const val SRC_NONE = 0
        const val SRC_FOCUS = 1
        const val SRC_FACE = 2
        const val SRC_OBJECT = 3

        const val FOCUS_UNKNOWN = -1
        const val FOCUS_OFF_SUBJECT = 0
        const val FOCUS_ON_SUBJECT = 1
        const val FOCUS_NO_SUBJECT = 2

        fun srcName(src: Int) = when (src) {
            SRC_FOCUS -> "around the focus point"
            SRC_FACE -> "on the main face"
            SRC_OBJECT -> "on the main subject"
            else -> "on the sharpest part of the scene"
        }

        /** Similarity of two fingerprints, -1..1. Near-identical shots score above ~0.65. */
        fun similarity(a: ByteArray, b: ByteArray): Float {
            if (a.size != b.size || a.isEmpty()) return 0f
            var s = 0L
            for (i in a.indices) s += a[i] * b[i]
            return s.toFloat() / (a.size * 32f * 32f)
        }

        /**
         * Like [similarity], but bright spots (a lamp, a flash, a headlight) are capped first so one
         * small light can't make two near-identical frames look different. Correlation, -1..1.
         */
        fun robustSimilarity(a: ByteArray, b: ByteArray): Float {
            if (a.size != b.size || a.isEmpty()) return 0f
            val n = a.size
            var sa = 0.0; var sb = 0.0
            val ca = FloatArray(n) { a[it].toFloat().coerceIn(-64f, 64f) }
            val cb = FloatArray(n) { b[it].toFloat().coerceIn(-64f, 64f) }
            for (i in 0 until n) { sa += ca[i]; sb += cb[i] }
            val ma = sa / n; val mb = sb / n
            var cov = 0.0; var va = 0.0; var vb = 0.0
            for (i in 0 until n) {
                val da = ca[i] - ma; val db = cb[i] - mb
                cov += da * db; va += da * da; vb += db * db
            }
            if (va <= 0.0 || vb <= 0.0) return 0f
            return (cov / Math.sqrt(va * vb)).toFloat()
        }
    }
}
