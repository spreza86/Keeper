package com.preza.keeper.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.preza.keeper.analysis.Features
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Stores what was measured for each photo so only new or changed photos are analysed
 * on the next scan. Lives in the app's private storage and never leaves the phone.
 */
class Cache(ctx: Context) {
    private val file = File(ctx.filesDir, "cache.json")

    val cam = HashMap<Long, JSONObject>()
    val edits = HashMap<Long, JSONObject>()
    val phone = HashMap<Long, JSONObject>()

    /** True once read from disk; the app and the background scan share one instance. */
    @Volatile var loaded = false

    @Synchronized
    fun load() {
        cam.clear(); edits.clear(); phone.clear()
        loaded = true
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            fun fill(name: String, into: HashMap<Long, JSONObject>) {
                val o = root.optJSONObject(name) ?: return
                for (k in o.keys()) k.toLongOrNull()?.let { into[it] = o.getJSONObject(k) }
            }
            fill("cam", cam); fill("edits", edits); fill("phone", phone)
        } catch (e: Exception) {
            // A broken cache just means a full rescan.
        }
    }

    @Synchronized
    fun save() {
        val root = JSONObject()
        fun dump(m: HashMap<Long, JSONObject>) = JSONObject().also { o -> m.forEach { (k, v) -> o.put(k.toString(), v) } }
        root.put("cam", dump(cam)); root.put("edits", dump(edits)); root.put("phone", dump(phone))
        val tmp = File(file.parentFile, "cache.tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file)
    }

    @Synchronized
    fun clear() {
        cam.clear(); edits.clear(); phone.clear()
        file.delete()
    }

    companion object {
        @Volatile private var inst: Cache? = null
        fun get(ctx: Context): Cache = inst ?: synchronized(this) { inst ?: Cache(ctx.applicationContext).also { inst = it } }

        fun camToJson(p: CamPhoto): JSONObject = JSONObject().apply {
            put("m", p.modified)
            p.dto?.let { put("dto", it) }
            p.subsec?.let { put("ss", it) }
            p.offsetExif?.let { put("off", it) }
            p.model?.let { put("model", it) }
            p.iso?.let { put("iso", it) }
            p.exposure?.let { put("exp", it) }
            p.flashFired?.let { put("flash", it) }
            p.lat?.let { put("lat", it) }
            p.lon?.let { put("lon", it) }
            p.focal35?.let { put("f35", it) }
            val f = p.f
            put("v", f.version)
            put("g", JSONArray(listOf(f.subjSharp, f.bgSharp, f.bestSharp, f.streaks, f.detail, f.subjArea, f.subjSrc, f.focus, f.focusSharp, if (f.laugh) 1 else 0).map { it.toDouble() }))
            put("map", Base64.encodeToString(f.map, Base64.NO_WRAP))
            put("f", JSONArray(listOf(f.sharp, f.faceSharp, f.crush, f.blown, f.mean, f.faces, f.eyesOpen, f.smile, if (f.faceCut) 1 else 0).map { it.toDouble() }))
            put("color", f.color)
            put("fp", Base64.encodeToString(f.fp, Base64.NO_WRAP))
        }

        fun jsonToCam(j: JSONObject, item: MediaRepo.Item, uri: Uri): CamPhoto? = try {
            val a = j.getJSONArray("f")
            val gv = j.optJSONArray("g")
            fun gf(i: Int) = gv?.optDouble(i, 0.0)?.toFloat() ?: 0f
            val f = Features(
                sharp = a.getDouble(0).toFloat(),
                faceSharp = a.getDouble(1).toFloat(),
                crush = a.getDouble(2).toFloat(),
                blown = a.getDouble(3).toFloat(),
                mean = a.getDouble(4).toFloat(),
                faces = a.getDouble(5).toInt(),
                eyesOpen = a.getDouble(6).toFloat(),
                smile = a.getDouble(7).toFloat(),
                faceCut = a.getDouble(8) > 0.5,
                color = j.optString("color", "Muted"),
                fp = Base64.decode(j.getString("fp"), Base64.NO_WRAP),
                subjSharp = gf(0), bgSharp = gf(1), bestSharp = gf(2), streaks = gf(3),
                detail = if (gv == null) 1f else gf(4), subjArea = gf(5), subjSrc = gf(6).toInt(),
                focus = if (gv == null) Features.FOCUS_UNKNOWN else gf(7).toInt(), focusSharp = gf(8), laugh = gf(9) > 0.5f,
                map = j.optString("map", "").let { if (it.isEmpty()) ByteArray(0) else Base64.decode(it, Base64.NO_WRAP) },
                version = j.optInt("v", 1)
            )
            CamPhoto(
                id = item.id, uri = uri, name = item.name, mime = item.mime, modified = item.modified,
                size = item.size, takenFallback = item.taken,
                dto = j.optStringOrNull("dto"), subsec = j.optStringOrNull("ss"), offsetExif = j.optStringOrNull("off"),
                model = j.optStringOrNull("model"),
                iso = if (j.has("iso")) j.getInt("iso") else null,
                exposure = if (j.has("exp")) j.getDouble("exp") else null,
                flashFired = if (j.has("flash")) j.getBoolean("flash") else null,
                lat = if (j.has("lat")) j.getDouble("lat") else null,
                lon = if (j.has("lon")) j.getDouble("lon") else null,
                f = f,
                focal35 = if (j.has("f35")) j.getInt("f35") else null
            )
        } catch (e: Exception) {
            null
        }

        private fun JSONObject.optStringOrNull(k: String): String? = if (has(k)) getString(k) else null
    }
}
