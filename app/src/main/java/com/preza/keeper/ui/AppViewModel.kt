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

/** Full-screen photo viewer: the frames of one scene, or two frames side by side to compare. */
class ViewerState(val decisions: List<Decision>, val index: Int, val compare: Boolean)

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
    val viewer: ViewerState? = null
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
        UiState(stats = stats.snapshot(), camFolder = prefs.cameraFolder, editsFolder = prefs.editsFolder, gap = prefs.gapMinutes, weeks = prefs.weeks, appearance = prefs.appearance)
    )
    val ui = _ui.asStateFlow()

    /** MainActivity launches these: Android shows its own "move to trash?" prompt. */
    val trashRequests = MutableSharedFlow<IntentSender>(extraBufferCapacity = 1)

    private var camPhotos: List<CamPhoto> = emptyList()
    private var editKeys = EditKeys(emptySet(), emptySet())
    private var phone: List<PhonePhoto> = emptyList()

    private class Pending(val ids: List<Long>, val restore: Boolean, val source: String)
    private var pending: Pending? = null

    private sealed interface Action
    private class Trashed(val ids: List<Long>, val source: String) : Action
    private class KeptSet(val key: String) : Action
    private class BurstDone(val key: String) : Action
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
                Organizer.Settings(prefs.gapMinutes, prefs.weeks, prefs.keptSessions, prefs.doneBursts, prefs.tripNames)
            )
        }
        prefs.weights = lib.weights
        _ui.update { it.copy(library = lib, learned = Learner.describe(lib.weights, lib.learnedPairs)) }
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

    // ---------- quick clean ----------

    /** Up to 20 weak frames from groups of similar shots, mixed across outings, not decided yet. */
    fun startQuick() {
        val st = _ui.value
        val lib = st.library ?: return
        val perSession = lib.sessions.sortedByDescending { it.start }.map { s ->
            s.scenes.filter { it.size >= 2 }.flatMap { sc ->
                sc.decisions.filter { it.verdict == Verdict.DELETE && it.photo.id !in st.trashed && st.overrides[it.photo.id] == null }
                    .map { QuickCard(it, sc) }
            }
        }.filter { it.isNotEmpty() }
        val cards = ArrayList<QuickCard>()
        var k = 0
        while (cards.size < 20 && perSession.any { it.size > k }) {
            for (l in perSession) if (l.size > k && cards.size < 20) cards += l[k]
            k += 2
        }
        if (cards.isEmpty()) { toast("Nothing to swipe right now. Nice."); return }
        _ui.update { it.copy(quick = cards, quickIndex = 0, quickDeleted = emptyList(), quickKept = 0, stack = listOf(Screen.Quick)) }
    }

    fun quickDecide(keep: Boolean) {
        val st = _ui.value
        val card = st.quick.getOrNull(st.quickIndex) ?: return
        val id = card.decision.photo.id
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

    fun openViewer(decisions: List<Decision>, index: Int, compare: Boolean = false) {
        if (decisions.isEmpty()) return
        _ui.update { it.copy(viewer = ViewerState(decisions, index.coerceIn(0, decisions.size - 1), compare)) }
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
        lastAction = BurstDone(scene.key)
        toast("Kept all ${scene.size} frames", undo = true)
        reorganize()
    }

    fun cullBurst(scene: Scene) {
        requestTrash(scene.decisions.filter { !isKept(it) }.map { it.photo.id }, source = "Bursts")
    }

    private fun requestTrash(ids: List<Long>, restore: Boolean = false, source: String = "Sets") {
        if (ids.isEmpty()) return
        val uris = ids.map { repo.uri(it) }
        val pi = MediaStore.createTrashRequest(ctx.contentResolver, uris, !restore)
        pending = Pending(ids, restore, source)
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
            lastAction = Trashed(p.ids, p.source)
            toast("Moved ${p.ids.size} ${if (p.ids.size == 1) "photo" else "photos"} to trash · ${Fmt.gb(bytes)} freed", undo = true)
            if (_ui.value.screen is Screen.SetDetail) back()
        } else {
            _ui.update { it.copy(trashed = it.trashed - p.ids.toSet()) }
            toast("Restored")
        }
        reorganize()
    }

    fun undo() {
        when (val a = lastAction) {
            is Trashed -> requestTrash(a.ids, restore = true, source = a.source)
            is KeptSet -> { prefs.keptSessions = prefs.keptSessions - a.key; toast("Restored"); reorganize() }
            is BurstDone -> { prefs.doneBursts = prefs.doneBursts - a.key; toast("Restored"); reorganize() }
            null -> {}
        }
        lastAction = null
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
