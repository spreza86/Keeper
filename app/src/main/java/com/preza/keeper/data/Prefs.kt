package com.preza.keeper.data

import android.content.Context
import com.preza.keeper.logic.Learner
import org.json.JSONObject

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("keeper", Context.MODE_PRIVATE)

    /** MediaStore relative path, e.g. "A6700/" for Internal storage/A6700 */
    var cameraFolder: String
        get() = sp.getString("cam", "A6700/")!!
        set(v) = sp.edit().putString("cam", v).apply()

    var editsFolder: String
        get() = sp.getString("edits", "Pictures/AdobeLightroom/")!!
        set(v) = sp.edit().putString("edits", v).apply()

    /** "system", "light" or "dark" */
    var appearance: String
        get() = sp.getString("appearance", "system")!!
        set(v) = sp.edit().putString("appearance", v).apply()

    var gapMinutes: Int
        get() = sp.getInt("gap", 30)
        set(v) = sp.edit().putInt("gap", v).apply()

    var weeks: Int
        get() = sp.getInt("weeks", 3)
        set(v) = sp.edit().putInt("weeks", v).apply()

    var keptSessions: Set<String>
        get() = sp.getStringSet("kept", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("kept", v).apply()

    var doneBursts: Set<String>
        get() = sp.getStringSet("doneBursts", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("doneBursts", v).apply()

    /** Photo shown behind the Home hero last time, so the next app open picks another one. */
    var lastHero: Long
        get() = sp.getLong("lastHero", -1L)
        set(v) = sp.edit().putLong("lastHero", v).apply()

    /** "olive", "ocean", "terracotta", "plum", "graphite" or "wallpaper" */
    var colorTheme: String
        get() = sp.getString("colorTheme", "olive")!!
        set(v) = sp.edit().putString("colorTheme", v).apply()

    /** "random", "newest", "oldest" or "free" */
    var setsSort: String
        get() = sp.getString("setsSort", "random")!!
        set(v) = sp.edit().putString("setsSort", v).apply()

    var burstsSort: String
        get() = sp.getString("burstsSort", "random")!!
        set(v) = sp.edit().putString("burstsSort", v).apply()

    /**
     * Your confirmed choices, photo id -> keep (true) or delete (false). Saved when you press the delete
     * (or done) button in Sets or Bursts, so reviewed photos are never suggested again.
     */
    var decided: Map<Long, Boolean>
        get() = sp.getString("decided", "")!!.split(',').mapNotNull { e ->
            val i = e.indexOf(':')
            if (i <= 0) null else e.substring(0, i).toLongOrNull()?.let { it to (e.substring(i + 1) == "1") }
        }.toMap()
        set(v) = sp.edit().putString("decided", v.entries.joinToString(",") { "${it.key}:${if (it.value) 1 else 0}" }).apply()

    /** What Keeper learned from your corrections: check name -> {level, recent "1010", note}. */
    var loosen: JSONObject
        get() = runCatching { JSONObject(sp.getString("loosen", "{}")!!) }.getOrDefault(JSONObject())
        set(v) = sp.edit().putString("loosen", v.toString()).apply()

    var weights: FloatArray
        get() = sp.getString("w", null)
            ?.split(",")?.mapNotNull { it.toFloatOrNull() }?.toFloatArray()
            ?.takeIf { it.size == Learner.DIM } ?: Learner.DEFAULT.copyOf()
        set(v) = sp.edit().putString("w", v.joinToString(",")).apply()

    var tripNames: Map<String, String>
        get() {
            val j = JSONObject(sp.getString("tripNames", "{}")!!)
            return j.keys().asSequence().associateWith { j.getString(it) }
        }
        set(v) = sp.edit().putString("tripNames", JSONObject(v).toString()).apply()
}
