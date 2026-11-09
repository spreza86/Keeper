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
