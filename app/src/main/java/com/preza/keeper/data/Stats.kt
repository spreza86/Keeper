package com.preza.keeper.data

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate

/**
 * Running total of what Keeper has freed. Stored only on this phone.
 * Each source ("Sets", "Bursts", "Quick clean") and each month keeps its own count and bytes.
 */
class Stats(ctx: Context) {
    private val sp = ctx.getSharedPreferences("keeper_stats", Context.MODE_PRIVATE)

    class Line(val count: Int, val bytes: Long)
    class Snapshot(
        val total: Line,
        val thisMonth: Line,
        val since: String?,          // "2026-11-08", first time anything was freed
        val bySource: Map<String, Line>,
        /** Monday of the week ("2026-11-02") -> what was freed that week */
        val weeks: Map<String, Line> = emptyMap()
    )

    companion object {
        fun weekKey(day: LocalDate): String = day.minusDays((day.dayOfWeek.value - 1).toLong()).toString()
    }

    private fun load(): JSONObject = runCatching { JSONObject(sp.getString("s", "{}")!!) }.getOrDefault(JSONObject())

    private fun bump(o: JSONObject, key: String, count: Int, bytes: Long) {
        val l = o.optJSONObject(key) ?: JSONObject()
        l.put("c", (l.optInt("c") + count).coerceAtLeast(0))
        l.put("b", (l.optLong("b") + bytes).coerceAtLeast(0L))
        o.put(key, l)
    }

    /** count/bytes are negative when photos are restored from the trash. */
    @Synchronized
    fun add(source: String, count: Int, bytes: Long, day: LocalDate = LocalDate.now()) {
        val root = load()
        if (!root.has("since") && count > 0) root.put("since", day.toString())
        bump(root, "total", count, bytes)
        val months = root.optJSONObject("months") ?: JSONObject()
        bump(months, day.toString().substring(0, 7), count, bytes)
        root.put("months", months)
        val weeks = root.optJSONObject("weeks") ?: JSONObject()
        bump(weeks, weekKey(day), count, bytes)
        root.put("weeks", weeks)
        val sources = root.optJSONObject("sources") ?: JSONObject()
        bump(sources, source, count, bytes)
        root.put("sources", sources)
        sp.edit().putString("s", root.toString()).apply()
    }

    fun snapshot(day: LocalDate = LocalDate.now()): Snapshot {
        val root = load()
        fun line(o: JSONObject?) = Line(o?.optInt("c") ?: 0, o?.optLong("b") ?: 0L)
        val src = root.optJSONObject("sources")
        return Snapshot(
            total = line(root.optJSONObject("total")),
            thisMonth = line(root.optJSONObject("months")?.optJSONObject(day.toString().substring(0, 7))),
            since = if (root.has("since")) root.getString("since") else null,
            bySource = src?.keys()?.asSequence()?.associateWith { line(src.optJSONObject(it)) }.orEmpty(),
            weeks = root.optJSONObject("weeks")?.let { w -> w.keys().asSequence().associateWith { line(w.optJSONObject(it)) } }.orEmpty()
        )
    }
}
