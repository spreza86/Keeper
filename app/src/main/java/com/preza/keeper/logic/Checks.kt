package com.preza.keeper.logic

import org.json.JSONObject

/**
 * The quality checks Keeper runs, and how far each one has been loosened by your corrections.
 * Each time you keep or delete a photo a check flagged, that answer is recorded. After 10 answers,
 * if you kept 7 or more, the check gets one step less strict (3 steps at most). If you later confirm
 * nearly all of them again (2 or fewer kept), it goes one step back. Never stricter than the default.
 */
object Checks {
    const val BLURRY = "Blurry"
    const val BRIGHT = "Too bright"
    const val FLASH = "Flash not ready"
    const val EYES = "Eyes closed"
    val ALL = listOf(BLURRY, BRIGHT, FLASH, EYES)
    const val MAX = 3
    private const val WINDOW = 10

    /** Multiplies the "blurry" limits (45%, or 65% with a slow shutter). */
    fun blurFactor(level: Int) = floatArrayOf(1f, 0.85f, 0.7f, 0.55f)[level.coerceIn(0, MAX)]
    /** Share of blown-out pixels above which a photo is "too bright". */
    fun brightLimit(level: Int) = floatArrayOf(0.25f, 0.33f, 0.41f, 0.5f)[level.coerceIn(0, MAX)]
    /** A no-flash frame darker than this share of the flash frames is "flash not ready". */
    fun flashRatio(level: Int) = floatArrayOf(0.6f, 0.5f, 0.4f, 0.3f)[level.coerceIn(0, MAX)]
    /** How much "eyes open" counts in the ranking. */
    fun eyesFactor(level: Int) = floatArrayOf(1f, 0.66f, 0.33f, 0f)[level.coerceIn(0, MAX)]

    class State(var level: Int = 0, var recent: String = "", var note: String = "")

    fun read(j: JSONObject): Map<String, State> = ALL.associateWith { k ->
        j.optJSONObject(k)?.let { State(it.optInt("level", 0), it.optString("recent", ""), it.optString("note", "")) } ?: State()
    }

    fun write(states: Map<String, State>): JSONObject = JSONObject().apply {
        states.forEach { (k, s) -> put(k, JSONObject().put("level", s.level).put("recent", s.recent).put("note", s.note)) }
    }

    fun levels(states: Map<String, State>): Map<String, Int> = states.mapValues { it.value.level }

    /** Records one answer. Returns true when the check's strictness changed. */
    fun record(states: Map<String, State>, check: String, kept: Boolean): Boolean {
        val s = states[check] ?: return false
        s.recent = (s.recent + if (kept) "1" else "0").takeLast(WINDOW)
        if (s.recent.length < WINDOW) return false
        val k = s.recent.count { it == '1' }
        return when {
            k >= 7 && s.level < MAX -> {
                s.level++; s.note = "you kept $k of the last $WINDOW photos it flagged"; s.recent = ""; true
            }
            k <= 2 && s.level > 0 -> {
                s.level--; s.note = "you confirmed ${WINDOW - k} of the last $WINDOW photos it flagged"; s.recent = ""; true
            }
            else -> false
        }
    }

    /** One line per loosened check, for Settings and Home. */
    fun describe(states: Map<String, State>): String = states.filter { it.value.level > 0 }.entries.joinToString("\n") { (k, s) ->
        "$k: ${limitText(k, s.level)} (${s.note})."
    }

    fun limitText(check: String, level: Int): String = when (check) {
        BLURRY -> "limit loosened to ${(45 * blurFactor(level)).toInt()}%"
        BRIGHT -> "limit loosened to ${(100 * brightLimit(level)).toInt()}% blown highlights"
        FLASH -> "limit loosened to ${(100 * flashRatio(level)).toInt()}% of the flash brightness"
        EYES -> if (level >= MAX) "eyes no longer count in the ranking" else "eyes count ${(100 * eyesFactor(level)).toInt()}% as much in the ranking"
        else -> ""
    }
}
