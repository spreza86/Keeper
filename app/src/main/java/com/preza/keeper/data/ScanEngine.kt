package com.preza.keeper.data

import android.content.Context
import androidx.exifinterface.media.ExifInterface
import com.preza.keeper.analysis.Analyzer
import com.preza.keeper.analysis.ArwPreview
import com.preza.keeper.analysis.Features
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Measures the photos that are new, changed, or measured by an older engine. Used by the app while it is open
 * and by the background scan when it is closed; only one runs at a time. Progress is saved often, so a scan
 * that is stopped (app closed, phone unplugged) resumes where it left off.
 */
object ScanEngine {
    /** Longest side of the preview that gets analysed. The A6700 preview is usually 1616 px. */
    const val ANALYSIS_SIZE = 2048

    val lock = Mutex()
    private val analyzer by lazy { Analyzer() }

    fun needsWork(cache: Cache, item: MediaRepo.Item): Boolean {
        val j = cache.cam[item.id] ?: return true
        return j.optLong("m", -1L) != item.modified || j.optInt("v", 1) != Features.VERSION
    }

    /** Photos in the camera folder still to analyse. */
    fun remaining(ctx: Context, cache: Cache, prefs: Prefs): Int =
        MediaRepo(ctx).camera(prefs.cameraFolder).count { needsWork(cache, it) }

    /**
     * Analyses what's missing. [onProgress] gets (done, total). Stops early when [stop] says so.
     * Returns how many photos are still left.
     */
    suspend fun analyzeMissing(
        ctx: Context, cache: Cache, prefs: Prefs,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        stop: () -> Boolean = { false }
    ): Int = lock.withLock {
        val repo = MediaRepo(ctx)
        val items = withContext(Dispatchers.IO) { repo.camera(prefs.cameraFolder) }
        val todo = items.filter { needsWork(cache, it) }
        val done = AtomicInteger(items.size - todo.size)
        val left = AtomicInteger(todo.size)
        onProgress(done.get(), items.size)
        val sem = Semaphore(2)
        coroutineScope {
            for (item in todo) launch(Dispatchers.Default) {
                sem.withPermit {
                    if (stop()) return@withPermit
                    val p = runCatching { analyzeOne(ctx, repo, item) }.getOrNull()
                    if (p != null) synchronized(cache) { cache.cam[item.id] = Cache.camToJson(p) }
                    left.decrementAndGet()
                    val d = done.incrementAndGet()
                    if (d % 8 == 0 || d == items.size) onProgress(d, items.size)
                    if (d % 50 == 0) withContext(Dispatchers.IO) { cache.save() }
                }
            }
        }
        withContext(Dispatchers.IO) { cache.save() }
        left.get()
    }

    fun analyzeOne(ctx: Context, repo: MediaRepo, item: MediaRepo.Item): CamPhoto? {
        val uri = repo.uri(item.id)
        val exif = repo.exif(item.id)
        val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) ?: 1
        val make = exif?.getAttribute(ExifInterface.TAG_MAKE)?.trim()
        val model = exif?.getAttribute(ExifInterface.TAG_MODEL)?.trim()
        val isJpeg = item.mime.contains("jpeg") || item.name.endsWith(".jpg", ignoreCase = true)
        val raw = if (isJpeg) ArwPreview.Raw(ArwPreview.readAll(ctx.contentResolver, uri), null)
        else ArwPreview.open(ctx.contentResolver, uri, make, model)
        val bmp = raw.jpeg?.let { ArwPreview.decodeSampled(it, ANALYSIS_SIZE, orientation) } ?: return null
        val iso = exif?.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)?.takeIf { it > 0 }
        val f = analyzer.analyze(bmp, raw.focus?.rotated(orientation), iso)
        Thumbs.write(ctx, item.id, bmp)
        bmp.recycle()
        val ll = exif?.latLong
        return CamPhoto(
            id = item.id, uri = uri, name = item.name, mime = item.mime, modified = item.modified,
            size = item.size, takenFallback = item.taken,
            dto = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.trim(),
            subsec = exif?.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL)?.trim(),
            offsetExif = exif?.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)?.trim(),
            model = model,
            iso = iso,
            exposure = exif?.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)?.takeIf { it > 0 },
            flashFired = exif?.getAttributeInt(ExifInterface.TAG_FLASH, -1)?.takeIf { it >= 0 }?.let { (it and 1) == 1 },
            lat = ll?.getOrNull(0), lon = ll?.getOrNull(1),
            f = f,
            focal35 = exif?.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)?.takeIf { it > 0 }
        )
    }
}
