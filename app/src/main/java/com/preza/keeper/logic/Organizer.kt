package com.preza.keeper.logic

import com.preza.keeper.analysis.Features
import com.preza.keeper.data.CamPhoto
import com.preza.keeper.data.Decision
import com.preza.keeper.data.EditKeys
import com.preza.keeper.data.Geo
import com.preza.keeper.data.Library
import com.preza.keeper.data.LocSource
import com.preza.keeper.data.MediaRepo
import com.preza.keeper.data.PhoneMeta
import com.preza.keeper.data.PhonePhoto
import com.preza.keeper.data.Place
import com.preza.keeper.data.Scene
import com.preza.keeper.data.Session
import com.preza.keeper.data.Sun
import com.preza.keeper.data.Trip
import com.preza.keeper.data.Verdict
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Turns a flat list of camera photos into trips, outings (sessions), scenes and
 * keep/delete suggestions. Pure logic: the only outside call is [phoneMeta], which reads
 * the location saved in a phone photo.
 */
class Organizer(
    private val geo: Geo,
    private val homeZone: ZoneId,
    private val now: Instant,
    private val homeCountry: String?,
    private val phoneMeta: (Long) -> PhoneMeta
) {
    companion object {
        /** Frames this close together are always one scene (a burst). */
        const val BURST_SEC = 5.0
        /** Within 2 minutes, a frame joins a scene when it looks at least this alike. */
        const val SAME_SCENE = 0.65f
        /** A lone frame joins a neighbouring scene this close in time... */
        const val MERGE_SEC = 30.0
        /** ...when it looks at least this alike. */
        const val MERGE_SIM = 0.5f
    }

    class Settings(
        val gapMin: Int,
        val weeks: Int,
        val kept: Set<String>,
        val doneBursts: Set<String>,
        val tripNames: Map<String, String>
    )

    private class P(val photo: CamPhoto, var t: Instant, val trueOffset: Int?)

    private class Loc(val t: Long, val lat: Double, val lon: Double)

    private val dtoFormat = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
    private val geoCache = HashMap<String, Geo.Result?>()
    private val metaCache = HashMap<Long, PhoneMeta>()

    fun build(cam: List<CamPhoto>, edits: EditKeys, phone: List<PhonePhoto>, s: Settings): Library {
        val items = cam.map { p ->
            val off = MediaRepo.parseOffset(p.offsetExif)
            val ldt = p.dto?.let { runCatching { LocalDateTime.parse(it.trim(), dtoFormat) }.getOrNull() }
            val base = when {
                ldt != null && off != null -> ldt.toInstant(ZoneOffset.ofTotalSeconds(off * 60))
                ldt != null -> ldt.atZone(homeZone).toInstant()
                else -> Instant.ofEpochMilli(p.takenFallback)
            }
            P(p, base.plusMillis(subsecMillis(p.subsec)), off)
        }.sortedBy { it.t }

        val phoneSorted = phone.sortedBy { it.taken }
        val phoneT = LongArray(phoneSorted.size) { phoneSorted[it].taken }

        // 1. Trips: a gap of 36 hours or more without camera photos starts a new trip.
        val tripGroups = splitBy(items) { a, b -> Duration.between(a.t, b.t).toHours() >= 36 }

        // 2. Camera clock check: if the camera clock was simply wrong, shift it so it lines up
        //    with the phone's photos. A camera left on Montreal time is NOT wrong: the moment
        //    is still right, only the local time differs, which we get from phone photos.
        val shifts = IntArray(tripGroups.size)
        tripGroups.forEachIndexed { i, g ->
            val sh = if (g.all { it.trueOffset != null }) 0 else bestShift(g, phoneT)
            shifts[i] = sh
            if (sh != 0) g.forEach { if (it.trueOffset == null) it.t = it.t.plusSeconds(sh * 60L) }
        }

        // 3. Sessions inside each trip, then location for every photo.
        class Raw(val trip: Int, val ps: List<P>, val offset: ZoneOffset, val places: Map<Long, Place>)
        val fine = ArrayList<Raw>()
        tripGroups.forEachIndexed { ti, g ->
            val groups = splitBy(g) { a, b -> Duration.between(a.t, b.t).toMinutes() > s.gapMin }
            for (sg in groups) {
                val start = sg.first().t.toEpochMilli()
                val end = sg.last().t.toEpochMilli()
                val window = sample(between(phoneSorted, phoneT, start - 30 * 60_000, end + 30 * 60_000), 40)
                val windowMeta = window.map { it to meta(it) }
                // Local time: from phone photos taken during the outing, else the nearest one within 12 h.
                val nearbyOffset = if (windowMeta.any { it.second.offsetMin != null }) null else
                    sample(between(phoneSorted, phoneT, start - 12 * 3_600_000L, end + 12 * 3_600_000L), 30)
                        .sortedBy { abs(it.taken - start) }
                        .firstNotNullOfOrNull { meta(it).offsetMin }
                val offset = (windowMeta.firstNotNullOfOrNull { it.second.offsetMin } ?: nearbyOffset)?.let { ZoneOffset.ofTotalSeconds(it * 60) }
                    ?: sg.firstNotNullOfOrNull { it.trueOffset }?.let { ZoneOffset.ofTotalSeconds(it * 60) }
                    ?: ZoneOffset.ofTotalSeconds(homeZone.rules.getOffset(sg.first().t).totalSeconds + shifts[ti] * 60)
                val located = windowMeta.mapNotNull { (pp, m) -> if (m.lat != null && m.lon != null) Loc(pp.taken, m.lat, m.lon) else null }

                var dayLocated: List<Loc>? = null
                val places = HashMap<Long, Place>()
                for (p in sg) {
                    val ph = p.photo
                    val tMs = p.t.toEpochMilli()
                    if (ph.lat != null && ph.lon != null && !(ph.lat == 0.0 && ph.lon == 0.0)) {
                        place(ph.lat, ph.lon, LocSource.PHOTO_GPS)?.let { places[ph.id] = it }
                        continue
                    }
                    val nearest = located.minByOrNull { abs(it.t - tMs) }
                    if (nearest != null) {
                        val src = if (abs(nearest.t - tMs) <= 15 * 60_000) LocSource.PHONE_15 else LocSource.PHONE_OUTING
                        place(nearest.lat, nearest.lon, src)?.let { places[ph.id] = it }
                        continue
                    }
                    if (dayLocated == null) {
                        val local = p.t.atOffset(offset).toLocalDate()
                        val d0 = local.atStartOfDay().toInstant(offset).toEpochMilli()
                        val d1 = d0 + 86_400_000L
                        dayLocated = sample(between(phoneSorted, phoneT, d0, d1), 30).mapNotNull { pp ->
                            val m = meta(pp)
                            if (m.lat != null && m.lon != null) Loc(pp.taken, m.lat, m.lon) else null
                        }
                    }
                    dayLocated?.minByOrNull { abs(it.t - tMs) }?.let { l ->
                        place(l.lat, l.lon, LocSource.PHONE_DAY)?.let { places[ph.id] = it }
                    }
                }
                fine += Raw(ti, sg, offset, places)
            }
        }

        // One outing = one day out. Groups split by short pauses are merged back when they fall on
        // the same local day (a coffee break or a walk between spots stays one outing).
        val raws = ArrayList<Raw>()
        for (r in fine) {
            val prev = raws.lastOrNull()
            val sameDay = prev != null && prev.trip == r.trip &&
                prev.ps.last().t.atOffset(prev.offset).toLocalDate() == r.ps.first().t.atOffset(prev.offset).toLocalDate() &&
                Duration.between(prev.ps.last().t, r.ps.first().t).toHours() < 12
            if (sameDay && prev != null) {
                raws[raws.size - 1] = Raw(prev.trip, prev.ps + r.ps, prev.offset, prev.places + r.places)
            } else raws += r
        }

        // Neighbouring days: an unlocated outing between two outings in the same city.
        val allPlaces = HashMap<Long, Place>()
        raws.forEach { allPlaces.putAll(it.places) }
        for (i in raws.indices) {
            val r = raws[i]
            if (r.places.isNotEmpty()) continue
            val prev = (i - 1 downTo 0).firstOrNull { raws[it].trip == r.trip && raws[it].places.isNotEmpty() }?.let { mainPlace(raws[it].places.values) }
            val next = (i + 1 until raws.size).firstOrNull { raws[it].trip == r.trip && raws[it].places.isNotEmpty() }?.let { mainPlace(raws[it].places.values) }
            if (prev != null && next != null && prev.city == next.city && prev.cc == next.cc) {
                val guess = Place(prev.city, null, prev.country, prev.cc, prev.lat, prev.lon, LocSource.NEIGHBOUR)
                r.ps.forEach { allPlaces[it.photo.id] = guess }
            }
        }

        // 4. Learn from your edits, then make the keep/delete calls.
        fun isEditedCoarse(p: CamPhoto) = p.coarseKey?.let { it in edits.coarse } == true
        fun isEditedFull(p: CamPhoto) = p.fullKey?.let { it in edits.full } == true

        val splits = raws.map { r -> splitScenes(r.ps) }
        val sessionScenes = splits.map { it.scenes }
        val refs = raws.map { r -> percentile(r.ps.map { it.photo.f.subjectSharp }, 0.75f).coerceAtLeast(0.01f) }

        val pairs = ArrayList<Pair<FloatArray, List<FloatArray>>>()
        sessionScenes.forEachIndexed { si, scenes ->
            for (sc in scenes) {
                if (sc.size < 2) continue
                val picked = sc.filter { isEditedFull(it.photo) }.ifEmpty { sc.filter { isEditedCoarse(it.photo) } }
                if (picked.size != 1) continue
                val pos = Learner.x(picked[0].photo.f.subjectSharp / refs[si], picked[0].photo.f)
                val negs = sc.filter { it !== picked[0] }.map { Learner.x(it.photo.f.subjectSharp / refs[si], it.photo.f) }
                pairs += pos to negs
            }
        }
        val w = Learner.train(pairs)

        val keyFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val sessions = raws.mapIndexed { si, r ->
            val scenes = sessionScenes[si].map { sc -> Scene("sc-${sc.first().photo.id}", decide(sc, refs[si], w, ::isEditedFull, ::isEditedCoarse, splits[si].notes)) }
            val main = mainPlace(r.ps.mapNotNull { allPlaces[it.photo.id] })
            val start = r.ps.first().t
            Session(
                key = "s-${r.ps.first().photo.id}",
                start = start,
                end = r.ps.last().t,
                offset = r.offset,
                photos = r.ps.map { it.photo },
                scenes = scenes,
                edited = r.ps.any { isEditedCoarse(it.photo) },
                place = main,
                holiday = Holidays.lookup(main?.cc, start.atOffset(r.offset).toLocalDate()),
                tripIndex = r.trip
            )
        }

        // 5. Trips
        val countryCounts = HashMap<String, Int>()
        allPlaces.values.forEach { countryCounts[it.cc] = (countryCounts[it.cc] ?: 0) + 1 }
        val homeCc = homeCountry?.takeIf { it.length == 2 } ?: countryCounts.maxByOrNull { it.value }?.key
        val trips = tripGroups.indices.map { ti ->
            val ss = sessions.filter { it.tripIndex == ti }
            val tripPlaces = ss.flatMap { se -> se.photos.mapNotNull { allPlaces[it.id] } }
            val main = mainPlace(tripPlaces)
            val start = ss.first().start
            val key = "t-" + start.atOffset(ss.first().offset).format(keyFmt)
            val mainCc = tripPlaces.groupingBy { it.cc }.eachCount().maxByOrNull { it.value }?.key
            val auto = when {
                main == null -> "Place unknown"
                mainCc != null && mainCc != homeCc -> geo.countryName(mainCc)
                else -> main.city
            }
            Trip(
                index = ti, key = key, name = s.tripNames[key] ?: auto, located = main != null || s.tripNames.containsKey(key),
                isHome = mainCc != null && mainCc == homeCc,
                start = start, end = ss.last().end, offset = ss.first().offset, sessions = ss,
                clockShiftMin = shifts[ti], count = ss.sumOf { it.photos.size }
            )
        }.reversed()

        // 6. Abandoned sets, bursts
        val threshold = s.weeks * 7L
        // A lone photo is not a set: it stays visible in Browse but is never listed as an outing to clean.
        val candidates = sessions.filter { !it.edited && it.key !in s.kept && it.photos.size >= 2 }
        val abandoned = candidates.filter { Duration.between(it.end, now).toDays() >= threshold }.sortedByDescending { it.start }
        val waiting = candidates.filter { Duration.between(it.end, now).toDays() < threshold }.sortedByDescending { it.start }
        val bursts = sessions.flatMap { se -> se.scenes.map { se to it } }
            .filter { (_, sc) -> sc.size >= 2 && sc.key !in s.doneBursts && sc.decisions.any { it.verdict == Verdict.DELETE } }
            .sortedByDescending { it.first.start }

        // 7. Browse groupings
        val places = LinkedHashMap<String, LinkedHashMap<String, MutableList<Long>>>()
        val sourceVotes = HashMap<String, HashMap<LocSource, Int>>()
        val coverage = HashMap<String, Int>()
        val colors = LinkedHashMap<String, MutableList<Long>>()
        val lights = LinkedHashMap<String, MutableList<Long>>()
        val timeById = items.associate { it.photo.id to it.t }
        for (se in sessions.sortedByDescending { it.start }) {
            for (p in se.photos) {
                val pl = allPlaces[p.id]
                coverage[pl?.source?.name ?: "NONE"] = (coverage[pl?.source?.name ?: "NONE"] ?: 0) + 1
                if (pl != null) {
                    places.getOrPut(pl.country) { LinkedHashMap() }.getOrPut(pl.city) { mutableListOf() }.add(p.id)
                    val v = sourceVotes.getOrPut("${pl.country}|${pl.city}") { HashMap() }
                    v[pl.source] = (v[pl.source] ?: 0) + 1
                }
                colors.getOrPut(p.f.color) { mutableListOf() }.add(p.id)
                lights.getOrPut(lightOf(p, timeById[p.id] ?: se.start, se, pl)) { mutableListOf() }.add(p.id)
            }
        }
        val placeSource = sourceVotes.mapValues { (_, v) -> v.maxByOrNull { it.value }!!.key }

        return Library(
            sessions = sessions,
            trips = trips,
            abandoned = abandoned,
            waiting = waiting,
            bursts = bursts,
            places = places,
            placeSource = placeSource,
            colors = colors.entries.sortedByDescending { it.value.size }.associate { it.key to it.value },
            lights = listOf("Day", "Golden hour", "Blue hour", "Night").filter { lights.containsKey(it) }.associateWith { lights[it]!! },
            coverage = coverage,
            total = cam.size,
            weights = w,
            learnedPairs = pairs.size,
            editsMatched = cam.count { isEditedCoarse(it) },
            photoById = cam.associateBy { it.id }
        )
    }

    // ---------- keep / delete ----------

    private fun decide(
        scene: List<P>,
        ref: Float,
        w: FloatArray,
        editedFull: (CamPhoto) -> Boolean,
        editedCoarse: (CamPhoto) -> Boolean,
        groupNotes: Map<Long, String>
    ): List<Decision> {
        val ps = scene.map { it.photo }
        fun rel(p: CamPhoto) = p.f.subjectSharp / ref
        fun score(p: CamPhoto) = Learner.score(w, Learner.x(rel(p), p.f)) - (if (p.f.faceCut) 0.15f else 0f)

        val firedMean = ps.filter { it.flashFired == true }.map { it.f.mean }.average().toFloat()
        fun flag(p: CamPhoto): String? = when {
            p.flashFired == false && ps.any { it.flashFired == true } && p.f.mean < 0.6f * firedMean -> "Flash not ready"
            p.f.faces > 0 && p.f.eyesOpen in 0f..0.25f -> "Eyes closed"
            rel(p) < 0.45f || ((p.exposure ?: 0.0) > 1.0 / 30 && rel(p) < 0.65f) -> "Blurry"
            p.f.blown > 0.25f -> "Too bright"
            else -> null
        }

        /** The numbers behind each photo, in plain words, for the "Why?" panel. */
        fun facts(p: CamPhoto): List<String> {
            val l = ArrayList<String>()
            groupNotes[p.id]?.let { l += it }
            val slow = (p.exposure ?: 0.0) > 1.0 / 30
            val limit = if (slow) 65 else 45
            val on = if (p.f.faces > 0 && p.f.faceSharp > 0f) "on the face" else "whole frame"
            l += "Sharpness ($on): ${pct(rel(p))} of this outing's sharp shots. Under $limit% counts as blurry" +
                (if (slow) " (slow shutter ${shutter(p.exposure!!)})." else ".")
            if (p.f.faces > 0) {
                l += if (p.f.eyesOpen >= 0f)
                    "Faces found: ${p.f.faces}. Lowest eyes-open score ${"%.2f".format(p.f.eyesOpen)}, under 0.25 counts as eyes closed."
                else "Faces found: ${p.f.faces}. Eyes not readable."
            }
            if (p.flashFired == false && ps.any { it.flashFired == true })
                l += "Flash did not fire. Brightness ${p.f.mean.toInt()} vs ${firedMean.toInt()} with flash (under 60% = flash not ready)."
            if (p.f.blown > 0.10f) l += "Blown highlights: ${pct(p.f.blown)} of the frame, over 25% counts as too bright."
            return l
        }

        val out = LinkedHashMap<Long, Decision>()
        val kept = ArrayList<CamPhoto>()
        fun put(p: CamPhoto, v: Verdict, reason: String, rule: String) {
            out[p.id] = Decision(p, v, reason, (facts(p) + rule).joinToString("\n"))
        }

        // Anything you edited stays.
        val full = ps.filter(editedFull)
        val edited = full.ifEmpty {
            val coarse = ps.filter(editedCoarse)
            if (coarse.isEmpty()) emptyList() else listOf(coarse.maxBy { score(it) })
        }
        edited.forEach { put(it, Verdict.KEEP, "Edited in Lightroom", "Kept: it matches one of your Lightroom edits."); kept += it }

        val flags = ps.associate { it.id to flag(it) }
        val cands = ps.filter { it.id !in out && flags[it.id] == null }.sortedByDescending { score(it) }

        if (kept.isEmpty() && cands.isNotEmpty()) {
            val best = cands[0]
            val second = cands.getOrNull(1)
            if (second != null && (rel(best) - rel(second)) / rel(best).coerceAtLeast(0.01f) < 0.05f) {
                val rule = "Your call: the two best frames are within 5% of each other on sharpness."
                put(best, Verdict.CHOOSE, "Too close to call", rule)
                put(second, Verdict.CHOOSE, "Too close to call", rule)
                kept += best; kept += second
            } else {
                val reason = when {
                    ps.size == 1 -> "Only shot of this scene"
                    best.f.faces > 0 && best.f.smile >= 0.6f && best.f.smile >= ps.maxOf { it.f.smile } -> "Best expression"
                    best.f.faces > 0 -> "Sharp, eyes open"
                    else -> "Sharpest of ${ps.size}"
                }
                val rule = if (ps.size == 1) "Kept: no other photo within 30 seconds looks like it, and nothing is wrong with it."
                else "Kept: highest score of the ${ps.size} frames in this scene."
                put(best, Verdict.KEEP, reason, rule)
                kept += best
            }
        }
        // A clearly different moment that is also good stays too.
        val bestScore = cands.firstOrNull()?.let { score(it) } ?: 0f
        for (c in cands) {
            if (c.id in out) continue
            if (bestScore > 0f && score(c) >= 0.92f * bestScore &&
                kept.all { Features.similarity(it.f.fp, c.f.fp) < 0.85f }
            ) {
                put(c, Verdict.KEEP, "Different moment", "Kept: almost as good as the best frame (${pct(score(c) / bestScore)}) and looks different enough.")
                kept += c
            }
        }
        // Never wipe out a whole moment: if every frame was flagged, the least bad one stays.
        if (kept.isEmpty() && ps.size >= 2) {
            val best = ps.maxBy { score(it) }
            val why = flags[best.id]?.let { " It was flagged \"$it\", so check it." } ?: ""
            put(best, Verdict.KEEP, "Best of a weak set", "Kept: every frame in this scene had a problem, so the best-scoring one stays.$why")
            kept += best
        }
        for (p in ps) {
            if (p.id in out) continue
            val f = flags[p.id]
            put(p, Verdict.DELETE, f ?: "Duplicate",
                if (f != null) "Suggested for deletion: \"$f\" (see the numbers above), and a better frame of this scene is kept."
                else "Suggested for deletion: a better, similar frame of this scene is kept.")
        }
        return ps.map { out[it.id]!! }
    }

    // ---------- scenes ----------

    private class Split(val scenes: List<List<P>>, val notes: Map<Long, String>)

    /**
     * Time first: frames a few seconds apart are one scene whatever they look like. Within 2 minutes a frame
     * joins the scene if it looks like any recent frame of it. Then lone frames are joined to a close, similar
     * neighbour, so "Only shot of this scene" is only said when it is true.
     */
    private fun splitScenes(ps: List<P>): Split {
        val notes = HashMap<Long, String>()
        val scenes = ArrayList<MutableList<P>>()
        for (p in ps) {
            val cur = scenes.lastOrNull()
            if (cur == null) {
                scenes += mutableListOf(p)
                notes[p.photo.id] = "Grouping: first photo of the outing."
                continue
            }
            val gap = Duration.between(cur.last().t, p.t).toMillis() / 1000.0
            val sim = cur.takeLast(12).maxOf { Features.robustSimilarity(it.photo.f.fp, p.photo.f.fp) }
            when {
                gap <= BURST_SEC -> {
                    cur += p
                    notes[p.photo.id] = "Grouping: ${secs(gap)} after the previous photo, so same burst (under ${BURST_SEC.toInt()} s always groups)."
                }
                gap <= 120 && sim >= SAME_SCENE -> {
                    cur += p
                    notes[p.photo.id] = "Grouping: ${secs(gap)} after the previous photo and looks alike (${"%.2f".format(sim)}, needs $SAME_SCENE)."
                }
                else -> {
                    scenes += mutableListOf(p)
                    notes[p.photo.id] = if (gap > 120) "Grouping: new scene, ${secs(gap)} after the previous photo."
                    else "Grouping: new scene, ${secs(gap)} later but looks different (${"%.2f".format(sim)}, needs $SAME_SCENE)."
                }
            }
        }
        // Second pass: a lone frame joins the closer, more similar neighbouring scene.
        var i = 0
        while (i < scenes.size) {
            val sc = scenes[i]
            if (sc.size == 1) {
                val p = sc[0]
                fun fit(other: List<P>?, edge: P?): Pair<Float, Double>? {
                    if (other == null || edge == null) return null
                    val gap = abs(Duration.between(edge.t, p.t).toMillis()) / 1000.0
                    if (gap > MERGE_SEC) return null
                    val s = other.maxOf { Features.robustSimilarity(it.photo.f.fp, p.photo.f.fp) }
                    return if (s >= MERGE_SIM) s to gap else null
                }
                val prev = scenes.getOrNull(i - 1)
                val next = scenes.getOrNull(i + 1)
                val a = fit(prev, prev?.last())
                val b = fit(next, next?.first())
                val target = when {
                    a != null && (b == null || a.first >= b.first) -> prev!!.also { it.add(p) } to a
                    b != null -> next!!.also { it.add(0, p) } to b
                    else -> null
                }
                if (target != null) {
                    val (s, gap) = target.second
                    notes[p.photo.id] = "Grouping: joined a scene ${secs(gap)} away that looks alike (${"%.2f".format(s)}, needs $MERGE_SIM for a lone photo)."
                    scenes.removeAt(i)
                    continue
                }
            }
            i++
        }
        return Split(scenes, notes)
    }

    private fun secs(s: Double): String = when {
        s < 10 -> "%.1f s".format(s)
        s < 120 -> "${s.toInt()} s"
        s < 7200 -> "${(s / 60).toInt()} min"
        else -> "${(s / 3600).toInt()} h"
    }

    private fun pct(v: Float): String = "${(v * 100).toInt()}%"

    private fun shutter(e: Double): String = if (e >= 1.0) "%.1f s".format(e) else "1/${Math.round(1 / e)} s"

    private fun <T> splitBy(list: List<T>, breakBetween: (T, T) -> Boolean): List<List<T>> {
        if (list.isEmpty()) return emptyList()
        val out = ArrayList<List<T>>()
        var cur = ArrayList<T>()
        cur.add(list[0])
        for (i in 1 until list.size) {
            if (breakBetween(list[i - 1], list[i])) { out += cur; cur = ArrayList() }
            cur.add(list[i])
        }
        out += cur
        return out
    }

    private fun bestShift(g: List<P>, phoneT: LongArray): Int {
        if (phoneT.isEmpty()) return 0
        val sampleList = sample(g, 300)
        fun score(shiftMin: Int): Int = sampleList.count { p ->
            val t = p.t.toEpochMilli() + shiftMin * 60_000L
            nearestDelta(phoneT, t) <= 20 * 60_000L
        }
        val base = score(0)
        var best = 0
        var bestScore = base
        for (k in -28..28) {
            if (k == 0) continue
            val sc = score(k * 30)
            if (sc > bestScore) { bestScore = sc; best = k * 30 }
        }
        return if (best != 0 && bestScore >= 5 && bestScore >= base * 1.5 + 1) best else 0
    }

    private fun nearestDelta(a: LongArray, t: Long): Long {
        val i = lowerBound(a, t)
        var d = Long.MAX_VALUE
        if (i < a.size) d = minOf(d, abs(a[i] - t))
        if (i > 0) d = minOf(d, abs(a[i - 1] - t))
        return d
    }

    private fun lowerBound(a: LongArray, t: Long): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun between(list: List<PhonePhoto>, t: LongArray, from: Long, to: Long): List<PhonePhoto> {
        val lo = lowerBound(t, from)
        val hi = lowerBound(t, to)
        return if (hi > lo) list.subList(lo, hi) else emptyList()
    }

    private fun <T> sample(list: List<T>, max: Int): List<T> {
        if (list.size <= max) return list
        val step = list.size.toDouble() / max
        return (0 until max).map { list[(it * step).toInt()] }
    }

    private fun meta(pp: PhonePhoto): PhoneMeta = metaCache.getOrPut(pp.id) { phoneMeta(pp.id) }

    private fun place(lat: Double, lon: Double, src: LocSource): Place? {
        val k = "%.2f,%.2f".format(lat, lon)
        val r = geoCache.getOrPut(k) { geo.lookup(lat, lon) } ?: return null
        return Place(r.city, r.area, r.country, r.cc, lat, lon, src)
    }

    private fun mainPlace(ps: Collection<Place>): Place? {
        if (ps.isEmpty()) return null
        val city = ps.groupingBy { it.cc + "|" + it.city }.eachCount().maxByOrNull { it.value }!!.key
        return ps.filter { it.cc + "|" + it.city == city }.minByOrNull { it.source.ordinal }
    }

    private fun lightOf(p: CamPhoto, t: Instant, se: Session, pl: Place?): String {
        if (pl != null) {
            val alt = Sun.altitude(t, pl.lat, pl.lon)
            return when {
                alt > 6 -> "Day"
                alt > -4 -> "Golden hour"
                alt > -6 -> "Blue hour"
                else -> "Night"
            }
        }
        val hour = t.atOffset(se.offset).hour
        return if (hour in 7..17 && p.f.mean > 90f && p.flashFired != true) "Day" else "Night"
    }

    private fun percentile(v: List<Float>, q: Float): Float {
        if (v.isEmpty()) return 0f
        val s = v.sorted()
        return s[((s.size - 1) * q).toInt()]
    }

    private fun subsecMillis(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        val digits = s.trim().take(3).padEnd(3, '0')
        return digits.toLongOrNull() ?: 0
    }
}
