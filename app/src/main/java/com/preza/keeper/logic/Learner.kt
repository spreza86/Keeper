package com.preza.keeper.logic

import com.preza.keeper.analysis.Features

/**
 * Learns what you value from your own choices. Every photo in your edits folder was picked
 * over its neighbours in the same scene; each of those choices nudges the weights so the
 * picked frame would have scored higher. Runs on the phone, nothing is sent anywhere.
 */
object Learner {
    const val DIM = 5
    val NAMES = arrayOf("sharpness", "eyes open", "expression", "brightness", "having a face")

    /** sharpness, eyes open, smile, brightness, has face */
    val DEFAULT = floatArrayOf(1.0f, 0.6f, 0.2f, 0.0f, 0.1f)

    fun x(relSharp: Float, f: Features): FloatArray = floatArrayOf(
        relSharp.coerceIn(0f, 2f),
        if (f.eyesOpen < 0f) 0.5f else f.eyesOpen,
        if (f.smile < 0f) 0f else f.smile,
        f.mean / 255f - 0.3f,
        if (f.faces > 0) 1f else 0f
    )

    fun score(w: FloatArray, x: FloatArray): Float {
        var s = 0f
        for (i in 0 until DIM) s += w[i] * x[i]
        return s
    }

    /** pairs: (features of the frame you edited, features of the frames you skipped) */
    fun train(pairs: List<Pair<FloatArray, List<FloatArray>>>): FloatArray {
        val w = DEFAULT.copyOf()
        if (pairs.isEmpty()) return w
        repeat(6) {
            for ((pos, negs) in pairs) for (neg in negs) {
                if (score(w, pos) <= score(w, neg) + 0.05f) {
                    for (i in 0 until DIM) w[i] += 0.04f * (pos[i] - neg[i])
                }
            }
        }
        w[0] = w[0].coerceAtLeast(0.3f)   // sharpness always matters a bit
        return w
    }

    /** Plain-language summary of what changed compared to the defaults. */
    fun describe(w: FloatArray, pairs: Int): String {
        if (pairs == 0) return "Not enough edited bursts yet. Keeper uses its default rules."
        val diffs = (0 until DIM).map { it to (w[it] - DEFAULT[it]) }.sortedByDescending { kotlin.math.abs(it.second) }
        val top = diffs.first()
        if (kotlin.math.abs(top.second) < 0.05f) return "Learned from $pairs of your edits. Your picks match the default rules."
        val name = NAMES[top.first]
        val dir = when {
            top.first == 3 && top.second < 0 -> "you often keep darker frames, like silhouettes"
            top.first == 3 -> "you prefer brighter frames"
            top.second > 0 -> "you weigh $name more than the default"
            else -> "you weigh $name less than the default"
        }
        return "Learned from $pairs of your edits: $dir."
    }
}
