package com.preza.keeper.analysis

/** Everything Keeper measures about one photo. Saved in the cache so a rescan is fast. */
class Features(
    val sharp: Float,        // fine detail on the strongest edges of the whole frame
    val faceSharp: Float,    // same measure, on faces only (0 if no face)
    val crush: Float,        // share of near-black pixels
    val blown: Float,        // share of blown-out pixels
    val mean: Float,         // average brightness 0..255
    val color: String,       // dominant color name
    val fp: ByteArray,       // 24x24 blurred "visual fingerprint" used to group near-identical shots
    val faces: Int,
    val eyesOpen: Float,     // lowest eyes-open probability among faces, -1 if unknown
    val smile: Float,        // highest smile probability, -1 if unknown
    val faceCut: Boolean     // a face touches the frame edge
) {
    /** The sharpness that matters: on faces when there are faces, otherwise the whole frame. */
    val subjectSharp: Float get() = if (faces > 0 && faceSharp > 0f) faceSharp else sharp

    companion object {
        const val FP = 24

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
