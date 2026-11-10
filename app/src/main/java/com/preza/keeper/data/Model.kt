package com.preza.keeper.data

import android.net.Uri
import com.preza.keeper.analysis.Features
import java.time.Instant
import java.time.ZoneOffset

/** One photo from the camera folder, with its metadata and measurements. */
class CamPhoto(
    val id: Long,
    val uri: Uri,
    val name: String,
    val mime: String,
    val modified: Long,
    val size: Long,
    val takenFallback: Long,      // MediaStore DATE_TAKEN, used when EXIF has no date
    val dto: String?,             // EXIF DateTimeOriginal, camera clock, "yyyy:MM:dd HH:mm:ss"
    val subsec: String?,
    val offsetExif: String?,      // EXIF OffsetTimeOriginal like "+07:00", when the camera wrote one
    val model: String?,
    val iso: Int?,
    val exposure: Double?,        // seconds
    val flashFired: Boolean?,
    val lat: Double?,
    val lon: Double?,
    val f: Features
) {
    val fullKey: String? get() = if (dto != null && subsec != null) "$dto|$subsec|$model" else null
    val coarseKey: String? get() = dto?.let { "$it|$model" }
}

class PhonePhoto(val id: Long, val taken: Long)
class PhoneMeta(val lat: Double?, val lon: Double?, val offsetMin: Int?)
class EditKeys(val full: Set<String>, val coarse: Set<String>)

enum class LocSource(val label: String, val short: String) {
    PHOTO_GPS("GPS saved in the photo", "Camera GPS"),
    PHONE_15("Phone photo within 15 min", "Phone, 15 min"),
    PHONE_OUTING("Phone photo, same outing", "Phone, same outing"),
    PHONE_DAY("Phone photo, same day (likely)", "Phone, same day"),
    NEIGHBOUR("Neighbouring days (guess)", "Guess")
}

class Place(
    val city: String,
    val area: String?,
    val country: String,
    val cc: String,
    val lat: Double,
    val lon: Double,
    val source: LocSource
)

enum class Verdict { KEEP, CHOOSE, DELETE }

/**
 * [why] is the plain-language trail of numbers and rules behind [reason], shown in the viewer.
 * [flag] is the quality check that failed (Checks.BLURRY...), [eyesLow] says eyes may be closed: both feed learning.
 */
class Decision(
    val photo: CamPhoto,
    val verdict: Verdict,
    val reason: String,
    val why: String = "",
    val flag: String? = null,
    val eyesLow: Boolean = false
)

class Scene(val key: String, val decisions: List<Decision>) {
    val size: Int get() = decisions.size
}

class Session(
    val key: String,
    val start: Instant,
    val end: Instant,
    val offset: ZoneOffset,       // local time where the photos were taken
    val photos: List<CamPhoto>,
    val scenes: List<Scene>,
    val edited: Boolean,
    val place: Place?,
    val holiday: String?,
    val tripIndex: Int
) {
    val decisions: List<Decision> get() = scenes.flatMap { it.decisions }
}

class Trip(
    val index: Int,
    val key: String,
    val name: String,
    val located: Boolean,
    val isHome: Boolean,
    val start: Instant,
    val end: Instant,
    val offset: ZoneOffset,
    val sessions: List<Session>,
    val clockShiftMin: Int,
    val count: Int
)

class Library(
    val sessions: List<Session>,
    val trips: List<Trip>,
    val abandoned: List<Session>,
    val waiting: List<Session>,
    val bursts: List<Pair<Session, Scene>>,
    /** country -> city -> photo ids */
    val places: Map<String, Map<String, List<Long>>>,
    /** "country|city" -> most common way the place was found */
    val placeSource: Map<String, LocSource>,
    val colors: Map<String, List<Long>>,
    val lights: Map<String, List<Long>>,
    /** LocSource name or "NONE" -> photo count */
    val coverage: Map<String, Int>,
    val total: Int,
    val weights: FloatArray,
    val learnedPairs: Int,
    val editsMatched: Int,
    val photoById: Map<Long, CamPhoto>
)
