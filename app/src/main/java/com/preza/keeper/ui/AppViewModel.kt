package com.preza.keeper.ui

import android.app.Application
import android.content.IntentSender
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.preza.keeper.analysis.Analyzer
import com.preza.keeper.analysis.ArwPreview
import com.preza.keeper.data.Cache
import com.preza.keeper.data.CamPhoto
import com.preza.keeper.data.Decision
import com.preza.keeper.data.EditKeys
import com.preza.keeper.data.Geo
import com.preza.keeper.data.Library
import com.preza.keeper.data.MediaRepo
import com.preza.keeper.data.PhoneMeta
import com.preza.keeper.data.PhonePhoto
import com.preza.keeper.data.Prefs
import com.preza.keeper.data.Scene
import com.preza.keeper.data.Session
import com.preza.keeper.data.Thumbs
import com.preza.keeper.data.Verdict
import com.preza.keeper.logic.Checks
import com.preza.keeper.logic.Learner
import com.preza.keeper.logic.Organizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

sealed interface Screen {
    data object Home : Screen
    data object Sets : Screen
    data class SetDetail(val key: String) : Screen
    data object Bursts : Screen
    data object Browse : Screen
    data class Group(val title: String, val subtitle: String, val ids: List<Long>, val tags: List<String>) : Screen
    data object Settings : Screen
    data object Quick : Screen
}

class QuickCard(val decision: Decision, val scene: Scene)

/** The photo behind the Home hero: picked at random once per app open. */
class HeroPick(val decision: Decision, val scene: Scene, val session: Session)

/** Full-screen photo viewer: the frames of one scene, or two frames side by side to compare. */
/** [label] names the group in the title, e.g. "Scene 2" or "Single shots". */
class ViewerState(val decisions: List<Decision>, val index: Int, val compare: Boolean, val label: String? = null)

data class UiState(
    val permission: Boolean = false,
    val scanning: Boolean = false,
    val stage: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val library: Library? = null,
    val overrides: Map<Long, Boolean> = emptyMap(),
    val trashed: Set<Long> = emptySet(),
    val stack: List<Screen> = listOf(Screen.Home),
    val toast: String? = null,
    val canUndo: Boolean = false,
    val camFolder: String = "",
    val editsFolder: String = "",
    val gap: Int = 30,
    val weeks: Int = 3,
    val folders: List<Pair<String, Int>> = emptyList(),
    val learned: String = "",
    val browseTab: Int = 0,
    val appearance: String = "system",
    val quick: List<QuickCard> = emptyList(),
    val quickIndex: Int = 0,
    val quickDeleted: List<Long> = emptyList(),
    val quickKept: Int = 0,
    val error: String? = null,
    val stats: com.preza.keeper.data.Stats.Snapshot? = null,
    val viewer: ViewerState? = null,
    val hero: HeroPick? = null,
    /** Your saved choices (photo id -> keep), see Prefs.decided. */
    val decided: Map<Long, Boolean> = emptyMap(),
    /** Scenes ticked "Done" in a set, during this visit. */
    val checked: Set<String> = emptySet(),
    val setsSort: String = "random",
    val burstsSort: String = "random",
    val colorTheme: String = "olive",
    /** Where you are in Bursts during this visit. */
    val burstKey: String? = null,
    val burstIdx: Int = 0,
    /** What your corrections changed, one line per check. */
    val loosened: String = ""
) {
    val screen: Screen get() = stack.last()
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app.applicationContext
    private val prefs = Prefs(ctx)
    private val repo = MediaRepo(ctx)
    private val cache = Cache(ctx)
    private val geo = Geo(ctx)
    private val analyzer = Analyzer()
    private val stats = com.preza.keeper.data.Stats(ctx)

    private val _ui = MutableStateFlow(
        prefs.decided.let { d ->
            UiState(
                stats = stats.snapshot(), camFolder = prefs.cameraFolder, editsFolder = prefs.editsFolder, gap = prefs.gapMinutes,
                weeks = prefs.weeks, appearance = prefs.appearance, colorTheme = prefs.colorTheme,
                setsSort = prefs.setsSort, burstsSort = prefs.burstsSort, decided = d, overrides = d
            )
        }
    )

    /** What your corrections taught each check. */
    private val checks = Checks.read(prefs.loosen)

    /** Random orders are drawn from this, so they change each time the app opens but hold during a visit. */
    private val seed = kotlin.random.Random.nextLong()
    val ui = _ui.asStateFlow()

    /** MainActivity launches these: Android shows its own "move to trash?" prompt. */
    val trashRequests = MutableSharedFlow<IntentSender>(extraBufferCapacity = 1)

    private var camPhotos: List<CamPhoto> = emptyList()
    private var editKeys = EditKeys(emptySet(), emptySet())
    private var phone: List<PhonePhoto> = emptyList()

    private class Pending(
        val ids: List<Long>,
        val restore: Boolean,
        val source: String,
        /** Choices to save once the trash prompt is accepted. */
        val save: Map<Long, Boolean> = emptyMap(),
        /** Decisions to learn from once the trash prompt is accepted. */
        val learn: List<Decision> = emptyList(),
        /** Leave the set screen afterwards (the whole set was handled). */
        val closeAfter: Boolean = true
    )
    private var pending: Pending? = null

    private sealed interface Action
    private class Trashed(val ids: List<Long>, val source: String, val saved: Set<Long>) : Action
    private class KeptSet(val key: String) : Action
    private class BurstDone(val key: String, val saved: Set<Long>) : Action
    private var lastAction: Action? = null

    private var scanJob: Job? = null
    private var toastJob: Job? = null

    // ---------- permissions & scanning ----------

    fun onPermission(granted: Boolean) {
        _ui.update { it.copy(permission = granted) }
        if (granted && _ui.value.library == null) scan()
    }

    fun scan(full: Boolean = false) {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            try {
                _ui.update { it.copy(scanning = true, stage = "Reading folders", error = null) }
                withContext(Dispatchers.IO) {
                    if (full) { cache.clear(); Thumbs.clear(ctx) } else cache.load()
                }
                val items = withContext(Dispatchers.IO) { repo.camera(prefs.cameraFolder) }
                val todo = items.filter { cache.cam[it.id]?.optLong("m", -1L) != it.modified }
                val done = AtomicInteger(items.size - todo.size)
                _ui.update { it.copy(stage = "Analyzing photos", done = done.get(), total = items.size) }

                val sem = Semaphore(3)
                coroutineScope {
                    for (item in todo) launch(Dispatchers.Default) {
                        sem.withPermit {
                            val p = runCatching { analyzeOne(item) }.getOrNull()
                            if (p != null) synchronized(cache) { cache.cam[item.id] = Cache.camToJson(p) }
                            val d = done.incrementAndGet()
                            if (d % 8 == 0 || d == items.size) _ui.update { it.copy(done = d) }
                            if (d % 400 == 0) withContext(Dispatchers.IO) { cache.save() }
                        }
                    }
                }

                _ui.update { it.copy(stage = "Matching your edits") }
                editKeys = withContext(Dispatchers.IO) { loadEdits() }

                _ui.update { it.copy(stage = "Reading phone photos") }
                phone = withContext(Dispatchers.IO) { repo.phone(prefs.cameraFolder, prefs.editsFolder) }

                camPhotos = items.mapNotNull { item -> cache.cam[item.id]?.let { Cache.jsonToCam(it, item, repo.uri(item.id)) } }
                _ui.update { it.copy(stage = "Grouping outings and places") }
                organize()
                withContext(Dispatchers.IO) { cache.save() }
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _ui.update { it.copy(scanning = false) }
            }
        }
    }

    private fun analyzeOne(item: MediaRepo.Item): CamPhoto? {
        val uri = repo.uri(item.id)
        val exif = repo.exif(item.id)
        val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) ?: 1
        val isJpeg = item.mime.contains("jpeg") || item.name.endsWith(".jpg", ignoreCase = true)
        val bytes = if (isJpeg) ArwPreview.readAll(ctx.contentResolver, uri) else ArwPreview.extractJpeg(ctx.contentResolver, uri)
        val bmp = bytes?.let { ArwPreview.decodeSampled(it, 512, orientation) } ?: return null
        val f = analyzer.analyze(bmp)
        Thumbs.write(ctx, item.id, bmp)
        val ll = exif?.latLong
        return CamPhoto(
            id = item.id, uri = uri, name = item.name, mime = item.mime, modified = item.modified,
            size = item.size, takenFallback = item.taken,
            dto = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.trim(),
            subsec = exif?.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL)?.trim(),
            offsetExif = exif?.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)?.trim(),
            model = exif?.getAttribute(ExifInterface.TAG_MODEL)?.trim(),
            iso = exif?.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)?.takeIf { it > 0 },
            exposure = exif?.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)?.takeIf { it > 0 },
            flashFired = exif?.getAttributeInt(ExifInterface.TAG_FLASH, -1)?.takeIf { it >= 0 }?.let { (it and 1) == 1 },
            lat = ll?.getOrNull(0), lon = ll?.getOrNull(1),
            f = f
        )
    }

    /** Builds the set of "this photo was edited" keys from the edits folder. */
    private fun loadEdits(): EditKeys {
        val full = HashSet<String>()
        val coarse = HashSet<String>()
        for (item in repo.edits(prefs.editsFolder)) {
            val cached = synchronized(cache) { cache.edits[item.id] }?.takeIf { it.optLong("m", -1L) == item.modified }
            val j = cached ?: run {
                val e = repo.exif(item.id)
                JSONObject().apply {
                    put("m", item.modified)
                    e?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.trim()?.let { put("dto", it) }
                    e?.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL)?.trim()?.let { put("ss", it) }
                    e?.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.let { put("model", it) }
                }.also { synchronized(cache) { cache.edits[item.id] = it } }
            }
            val dto = j.optString("dto", "")
            if (dto.isEmpty()) continue
            val model = if (j.has("model")) j.getString("model") else "null"
            coarse += "$dto|$model"
            if (j.has("ss")) full += "$dto|${j.getString("ss")}|$model"
        }
        return EditKeys(full, coarse)
    }

    private fun phoneMeta(id: Long): PhoneMeta {
        synchronized(cache) { cache.phone[id] }?.let { j ->
            return PhoneMeta(
                if (j.has("lat")) j.getDouble("lat") else null,
                if (j.has("lon")) j.getDouble("lon") else null,
                if (j.has("off")) j.getInt("off") else null
            )
        }
        val m = repo.phoneMeta(id)
        val j = JSONObject().apply {
            m.lat?.let { put("lat", it) }
            m.lon?.let { put("lon", it) }
            m.offsetMin?.let { put("off", it) }
        }
        synchronized(cache) { cache.phone[id] = j }
        return m
    }

    private suspend fun organize() {
        val trashed = _ui.value.trashed
        val lib = withContext(Dispatchers.IO) {
            Organizer(geo, ZoneId.systemDefault(), Instant.now(), java.util.Locale.getDefault().country, ::phoneMeta).build(
                camPhotos.filter { it.id !in trashed }, editKeys, phone,
                Organizer.Settings(
                    prefs.gapMinutes, prefs.weeks, prefs.keptSessions, prefs.doneBursts, prefs.tripNames,
                    decided = _ui.value.decided.keys, loosen = Checks.levels(checks), loosenNotes = checks.mapValues { it.value.note }
                )
            )
        }
        prefs.weights = lib.weights
        _ui.update {
            it.copy(
                library = lib, learned = Learner.describe(lib.weights, lib.learnedPairs), loosened = Checks.describe(checks),
                hero = heroFor(lib, it.hero, it.trashed)
            )
        }
    }

    /**
     * Keeps the current hero photo while the app stays open (re-found in the new library), else picks a random
     * photo from the whole library: any photo, not the one shown last time, never a near-black frame.
     */
    private fun heroFor(lib: Library, current: HeroPick?, trashed: Set<Long>): HeroPick? {
        val all = lib.sessions.flatMap { se -> se.scenes.flatMap { sc -> sc.decisions.map { HeroPick(it, sc, se) } } }
            .filter { it.decision.photo.id !in trashed }
        if (current != null) all.firstOrNull { it.decision.photo.id == current.decision.photo.id }?.let { return it }
        val last = prefs.lastHero
        val pool = all.filter { it.decision.photo.f.mean >= 18f && it.decision.photo.id != last }.ifEmpty { all }
        val pick = pool.randomOrNull() ?: return null
        prefs.lastHero = pick.decision.photo.id
        return pick
    }

    private fun reorganize() {
        viewModelScope.launch { runCatching { organize() } }
    }

    // ---------- keep / delete ----------

    fun isKept(d: Decision, overrides: Map<Long, Boolean> = _ui.value.overrides): Boolean =
        overrides[d.photo.id] ?: (d.verdict != Verdict.DELETE)

    fun toggle(d: Decision) {
        val now = isKept(d)
        _ui.update { it.copy(overrides = it.overrides + (d.photo.id to !now)) }
    }

    fun setMany(values: Map<Long, Boolean>) {
        _ui.update { it.copy(overrides = it.overrides + values) }
    }

    /** A scene you already reviewed: all its photos have a saved choice. */
    fun reviewed(sc: Scene, decided: Map<Long, Boolean> = _ui.value.decided): Boolean = sc.decisions.all { it.photo.id in decided }

    // ---------- saved choices and learning ----------

    private fun commitSave(save: Map<Long, Boolean>, learn: List<Decision>) {
        if (save.isNotEmpty()) {
            val all = prefs.decided + save
            prefs.decided = all
            _ui.update { it.copy(decided = all, overrides = it.overrides + save) }
        }
        learnFrom(learn.map { it to (save[it.photo.id] ?: isKept(it)) })
    }

    /**
     * Each confirmed choice on a photo Keeper suggested deleting teaches the check that flagged it:
     * keeping it is a vote to loosen, deleting it a vote to keep the check as it is.
     */
    private fun learnFrom(answers: List<Pair<Decision, Boolean>>) {
        var changed = false
        for ((d, kept) in answers) {
            if (d.verdict != Verdict.DELETE) continue
            d.flag?.let { if (Checks.record(checks, it, kept)) changed = true }
            if (d.eyesLow && Checks.record(checks, Checks.EYES, kept)) changed = true
        }
        prefs.loosen = Checks.write(checks)
        if (changed) toast("Keeper adjusted a check from your choices. See Settings.")
    }

    /** Settings: start over. Saved choices and what was learned from them are cleared. */
    fun forgetChoices() {
        prefs.decided = emptyMap()
        checks.values.forEach { it.level = 0; it.recent = ""; it.note = "" }
        prefs.loosen = Checks.write(checks)
        _ui.update { it.copy(decided = emptyMap(), overrides = emptyMap(), checked = emptySet()) }
        toast("Choices forgotten")
        reorganize()
    }

    // ---------- sets: Done checks and partial delete ----------

    fun toggleChecked(key: String) = _ui.update { it.copy(checked = if (key in it.checked) it.checked - key else it.checked + key) }
    fun check(key: String) = _ui.update { it.copy(checked = it.checked + key) }

    /**
     * Finishes part of a set (the checked scenes) or all of it: deletes what is marked delete, then saves
     * every choice so these scenes are not suggested again.
     */
    fun finishScenes(target: List<Decision>, wholeSet: Boolean) {
        val save = target.associate { it.photo.id to isKept(it) }
        val del = target.filter { !isKept(it) }.map { it.photo.id }
        if (del.isEmpty()) {
            commitSave(save, target)
            _ui.update { it.copy(checked = emptySet()) }
            toast(if (wholeSet) "Set reviewed, all kept" else "Saved, these scenes won't be suggested again")
            if (wholeSet && _ui.value.screen is Screen.SetDetail) back()
            reorganize()
        } else {
            requestTrash(del, source = "Sets", save = save, learn = target, closeAfter = wholeSet)
        }
    }

    // ---------- sorting ----------

    fun setSetsSort(v: String) { prefs.setsSort = v; _ui.update { it.copy(setsSort = v) } }
    fun setBurstsSort(v: String) { prefs.burstsSort = v; _ui.update { it.copy(burstsSort = v, burstKey = null, burstIdx = 0) } }

    private fun shuffleKey(key: String): Long = java.util.Random(seed xor key.hashCode().toLong()).nextLong()

    /** Space the suggested deletions would free, counting only scenes not reviewed yet. */
    fun toFree(scenes: List<Scene>, st: UiState = _ui.value): Long =
        scenes.filter { !reviewed(it, st.decided) }.sumOf { sc -> sc.decisions.filter { !isKept(it, st.overrides) }.sumOf { it.photo.size } }

    fun sortedSets(list: List<Session>, st: UiState): List<Session> = when (st.setsSort) {
        "newest" -> list.sortedByDescending { it.start }
        "oldest" -> list.sortedBy { it.start }
        "free" -> list.sortedByDescending { toFree(it.scenes, st) }
        else -> list.sortedBy { shuffleKey(it.key) }
    }

    fun sortedBursts(list: List<Pair<Session, Scene>>, st: UiState): List<Pair<Session, Scene>> = when (st.burstsSort) {
        "newest" -> list.sortedByDescending { it.first.start }
        "oldest" -> list.sortedBy { it.first.start }
        "free" -> list.sortedByDescending { toFree(listOf(it.second), st) }
        else -> list.sortedBy { shuffleKey(it.second.key) }
    }

    /** Moves to burst [i] (wraps around) and remembers it for this visit. */
    fun burstGo(list: List<Pair<Session, Scene>>, i: Int) {
        if (list.isEmpty()) return
        val j = i.mod(list.size)
        _ui.update { it.copy(burstKey = list[j].second.key, burstIdx = j) }
    }

    // ---------- appearance ----------

    fun setColorTheme(v: String) { prefs.colorTheme = v; _ui.update { it.copy(colorTheme = v) } }

    // ---------- quick clean ----------

    /**
     * Up to 20 weak frames from groups of similar shots, in a new random order every round, at most 3 per outing
     * so a round mixes outings. [first] (the Home hero photo) opens the round when given.
     */
    fun startQuick(first: HeroPick? = null) {
        val st = _ui.value
        val lib = st.library ?: return
        val pool = lib.sessions.flatMap { s ->
            s.scenes.filter { it.size >= 2 }.flatMap { sc ->
                sc.decisions.filter { it.verdict == Verdict.DELETE && it.photo.id !in st.trashed && st.overrides[it.photo.id] == null }
                    .map { s.key to QuickCard(it, sc) }
            }
        }.filter { it.second.decision.photo.id != first?.decision?.photo?.id }.shuffled()
        val cards = ArrayList<QuickCard>()
        if (first != null && first.decision.photo.id !in st.trashed) cards += QuickCard(first.decision, first.scene)
        val perSession = HashMap<String, Int>()
        val skipped = ArrayList<QuickCard>()
        for ((key, card) in pool) {
            if (cards.size >= 20) break
            val n = perSession[key] ?: 0
            if (n < 3) { cards += card; perSession[key] = n + 1 } else skipped += card
        }
        for (card in skipped) { if (cards.size >= 20) break; cards += card }
        if (cards.isEmpty()) { toast("Nothing to swipe right now. Nice."); return }
        roundCounted.clear()
        _ui.update { it.copy(quick = cards, quickIndex = 0, quickDeleted = emptyList(), quickKept = 0, stack = listOf(Screen.Quick)) }
    }

    /** Photos already counted for learning in this Tinder round. */
    private val roundCounted = HashSet<Long>()

    fun quickDecide(keep: Boolean) {
        val st = _ui.value
        val card = st.quick.getOrNull(st.quickIndex) ?: return
        val id = card.decision.photo.id
        // Swipes are not saved as choices (photos can come back), but they do teach the checks.
        if (roundCounted.add(id)) learnFrom(listOf(card.decision to keep))
        _ui.update {
            it.copy(
                overrides = it.overrides + (id to keep),
                quickIndex = it.quickIndex + 1,
                quickDeleted = if (keep) it.quickDeleted else it.quickDeleted + id,
                quickKept = if (keep) it.quickKept + 1 else it.quickKept
            )
        }
    }

    /** One Android trash prompt for the whole round. */
    fun quickTrash() {
        val ids = _ui.value.quickDeleted
        _ui.update { it.copy(quickDeleted = emptyList(), quick = emptyList(), quickIndex = 0, quickKept = 0) }
        requestTrash(ids, source = "Quick clean")
    }

    /** Leave the round. Decisions already made stay; nothing goes to the trash without the prompt. */
    fun quickStop() {
        _ui.update { it.copy(quick = emptyList(), quickIndex = 0, quickKept = 0) }
    }

    // ---------- viewer ----------

    fun openViewer(decisions: List<Decision>, index: Int, compare: Boolean = false, label: String? = null) {
        if (decisions.isEmpty()) return
        _ui.update { it.copy(viewer = ViewerState(decisions, index.coerceIn(0, decisions.size - 1), compare, label)) }
    }

    fun closeViewer() = _ui.update { it.copy(viewer = null) }

    fun setKeep(d: Decision, keep: Boolean) = setMany(mapOf(d.photo.id to keep))

    fun keepSession(s: Session) {
        prefs.keptSessions = prefs.keptSessions + s.key
        lastAction = KeptSet(s.key)
        toast("Set kept, it won't be suggested again", undo = true)
        if (_ui.value.screen is Screen.SetDetail) back()
        reorganize()
    }

    fun deleteFromSession(s: Session) {
        requestTrash(s.decisions.filter { !isKept(it) }.map { it.photo.id }, source = "Sets")
    }

    fun keepAllBurst(scene: Scene) {
        prefs.doneBursts = prefs.doneBursts + scene.key
        val save = scene.decisions.associate { it.photo.id to true }
        commitSave(save, scene.decisions)
        lastAction = BurstDone(scene.key, save.keys)
        toast("Kept all ${scene.size} frames", undo = true)
        reorganize()
    }

    fun cullBurst(scene: Scene) {
        requestTrash(
            scene.decisions.filter { !isKept(it) }.map { it.photo.id }, source = "Bursts",
            save = scene.decisions.associate { it.photo.id to isKept(it) }, learn = scene.decisions
        )
    }

    private fun requestTrash(
        ids: List<Long>, restore: Boolean = false, source: String = "Sets",
        save: Map<Long, Boolean> = emptyMap(), learn: List<Decision> = emptyList(), closeAfter: Boolean = true
    ) {
        if (ids.isEmpty()) return
        val uris = ids.map { repo.uri(it) }
        val pi = MediaStore.createTrashRequest(ctx.contentResolver, uris, !restore)
        pending = Pending(ids, restore, source, save, learn, closeAfter)
        trashRequests.tryEmit(pi.intentSender)
    }

    fun onTrashResult(ok: Boolean) {
        val p = pending ?: return
        pending = null
        if (!ok) return
        val bytes = p.ids.sumOf { id -> camPhotos.firstOrNull { it.id == id }?.size ?: 0L }
        stats.add(p.source, if (p.restore) -p.ids.size else p.ids.size, if (p.restore) -bytes else bytes)
        _ui.update { it.copy(stats = stats.snapshot()) }
        if (!p.restore) {
            _ui.update { it.copy(trashed = it.trashed + p.ids) }
            commitSave(p.save, p.learn)
            if (p.source == "Sets") _ui.update { it.copy(checked = emptySet()) }
            lastAction = Trashed(p.ids, p.source, p.save.keys)
            toast("Moved ${p.ids.size} ${if (p.ids.size == 1) "photo" else "photos"} to trash · ${Fmt.gb(bytes)} freed", undo = true)
            if (p.closeAfter && _ui.value.screen is Screen.SetDetail) back()
        } else {
            _ui.update { it.copy(trashed = it.trashed - p.ids.toSet()) }
            toast("Restored")
        }
        reorganize()
    }

    fun undo() {
        when (val a = lastAction) {
            is Trashed -> { unsave(a.saved); requestTrash(a.ids, restore = true, source = a.source) }
            is KeptSet -> { prefs.keptSessions = prefs.keptSessions - a.key; toast("Restored"); reorganize() }
            is BurstDone -> { prefs.doneBursts = prefs.doneBursts - a.key; unsave(a.saved); toast("Restored"); reorganize() }
            null -> {}
        }
        lastAction = null
    }

    /** Undo: the scenes come back as not reviewed. */
    private fun unsave(ids: Set<Long>) {
        if (ids.isEmpty()) return
        val all = prefs.decided - ids
        prefs.decided = all
        _ui.update { it.copy(decided = all, overrides = it.overrides - ids) }
    }

    private fun toast(msg: String, undo: Boolean = false) {
        toastJob?.cancel()
        _ui.update { it.copy(toast = msg, canUndo = undo) }
        toastJob = viewModelScope.launch {
            delay(5000)
            _ui.update { it.copy(toast = null, canUndo = false) }
        }
    }

    // ---------- navigation ----------

    fun tab(s: Screen) = _ui.update { it.copy(stack = listOf(s)) }
    fun open(s: Screen) = _ui.update { it.copy(stack = it.stack + s) }
    fun back(): Boolean {
        if (_ui.value.stack.size <= 1) return false
        _ui.update { it.copy(stack = it.stack.dropLast(1)) }
        return true
    }
    fun browseTab(i: Int) = _ui.update { it.copy(browseTab = i) }

    // ---------- settings ----------

    fun setGap(v: Int) {
        prefs.gapMinutes = v.coerceIn(10, 180)
        _ui.update { it.copy(gap = prefs.gapMinutes) }
        reorganize()
    }

    fun setWeeks(v: Int) {
        prefs.weeks = v.coerceIn(1, 12)
        _ui.update { it.copy(weeks = prefs.weeks) }
        reorganize()
    }

    fun loadFolders() = viewModelScope.launch {
        val f = withContext(Dispatchers.IO) { repo.folders() }
        _ui.update { it.copy(folders = f) }
    }

    fun setCameraFolder(path: String) {
        prefs.cameraFolder = path
        _ui.update { it.copy(camFolder = path, library = null) }
        scan()
    }

    fun setEditsFolder(path: String) {
        prefs.editsFolder = path
        _ui.update { it.copy(editsFolder = path) }
        scan()
    }

    fun nameTrip(key: String, name: String) {
        if (name.isBlank()) return
        prefs.tripNames = prefs.tripNames + (key to name.trim())
        toast("Trip named ${name.trim()}")
        reorganize()
    }

    fun rescan() = scan(full = true)

    fun setAppearance(mode: String) {
        prefs.appearance = mode
        _ui.update { it.copy(appearance = mode) }
    }
}
