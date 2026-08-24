package com.photoroute.app

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.sqrt

class RouteViewModel(app: Application) : AndroidViewModel(app) {

    private data class PacingPhase(val from: Long, val to: Long, val weight: Double)

    private val scanner = PhotoScanner(app)
    private var basemapJob: Job? = null
    private var tilePrepareJob: Job? = null
    private var lastTileBucket = -1

    // ---- scan state ----
    var scanning by mutableStateOf(false); private set
    var progress by mutableStateOf(0f); private set
    var status by mutableStateOf(""); private set

    // ---- filters ----
    var source by mutableStateOf(Source.CAMERA); private set
    var buckets by mutableStateOf<List<Bucket>>(emptyList()); private set
    var selectedBuckets by mutableStateOf<Set<Long>>(emptySet()); private set
    var radiusKm by mutableStateOf(25f); private set

    var fromMillis by mutableStateOf(monthsAgo(3)); private set
    var toMillis by mutableStateOf(endOfToday()); private set

    // ---- render state ----
    var spec by mutableStateOf(CardSpec()); private set
    var route by mutableStateOf<Route?>(null); private set
    var plan by mutableStateOf<Plan?>(null); private set
    var basemap by mutableStateOf<Bitmap?>(null); private set

    // ---- playback ----
    var playing by mutableStateOf(false); private set
    var progressT by mutableStateOf(1f); private set   // 0..1 along the trip
    var speed by mutableStateOf(1f); private set
    var durationSec by mutableStateOf(14f); private set
    var exportingVideo by mutableStateOf(false); private set
    var videoProgress by mutableStateOf(0f); private set
    var tileRevision by mutableStateOf(0); private set
    /** true = every leg gets equal screen time; false = wall-clock proportional. */
    var evenPacing by mutableStateOf(false); private set

    private var allPhotos: List<Photo> = emptyList()
    private val removedPhotoGroups = ArrayDeque<List<Photo>>()
    private var pacingPhases: List<PacingPhase> = emptyList()
    private var pacingWeight = 0.0

    init {
        MapRenderer.initialize(app)
    }

    val cursor: Long get() = cursorAt(progressT)

    /**
     * Maps playback progress onto a moment in time. Real time is honest but unwatchable —
     * two months parked in one city would be two months of a motionless map — so by default
     * each leg gets the same slice of the animation, with a short dwell at each arrival.
     */
    private fun cursorAt(t: Float): Long {
        val p = plan ?: return 0L
        if (!evenPacing) {
            if (pacingPhases.isEmpty()) return p.startAt
            var target = t.coerceIn(0f, 1f) * pacingWeight
            for (phase in pacingPhases) {
                if (target <= phase.weight) {
                    val f = (target / phase.weight).coerceIn(0.0, 1.0)
                    return phase.from + ((phase.to - phase.from) * f).toLong()
                }
                target -= phase.weight
            }
            return p.endAt
        }
        val n = p.segments.size
        if (n == 0) return p.startAt
        val x = (t * n).coerceIn(0f, n.toFloat())
        val i = minOf(n - 1, kotlin.math.floor(x).toInt())
        val f = (x - i).coerceIn(0f, 1f)
        val seg = p.segments[i]
        return if (f <= 0.8f) {
            seg.departAt + ((seg.arriveAt - seg.departAt) * (f / 0.8f)).toLong()
        } else {
            val nextDepart = p.segments.getOrNull(i + 1)?.departAt ?: p.endAt
            seg.arriveAt + ((nextDepart - seg.arriveAt) * ((f - 0.8f) / 0.2f)).toLong()
        }
    }

    // ---- setters ----
    fun updateSource(s: Source) { source = s }
    fun setRange(from: Long, to: Long) { fromMillis = from; toMillis = to }
    fun toggleBucket(id: Long) {
        selectedBuckets = if (id in selectedBuckets) selectedBuckets - id else selectedBuckets + id
    }
    fun clearBuckets() { selectedBuckets = emptySet() }
    fun setRadius(v: Float) { radiusKm = v; rebuild() }
    fun updateSpec(s: CardSpec) {
        val refit = s.ratio != spec.ratio || s.zoomAdjust != spec.zoomAdjust
        spec = s
        if (refit) rebuild()
    }
    fun updateSpeed(v: Float) { speed = v }
    fun updateEvenPacing(v: Boolean) { evenPacing = v }
    fun seekTo(v: Float) { progressT = v.coerceIn(0f, 1f); if (v < 1f) playing = false }

    fun play() {
        val p = plan ?: return
        if (p.segments.isEmpty()) return
        if (progressT >= 1f) progressT = 0f
        val start = cursorAt(progressT)
        val ahead1 = cursorAt((progressT + 0.015f).coerceAtMost(1f))
        val ahead2 = cursorAt((progressT + 0.030f).coerceAtMost(1f))
        tilePrepareJob?.cancel()
        tilePrepareJob = viewModelScope.launch {
            MapRenderer.prepareTiles(p, spec, start)
            MapRenderer.prepareTiles(p, spec, ahead1)
            MapRenderer.prepareTiles(p, spec, ahead2)
            tileRevision++
            playing = true
        }
    }
    fun pause() { playing = false }
    fun advance(deltaSec: Float) {
        val step = deltaSec * speed / durationSec
        val next = progressT + step
        if (next >= 1f) { progressT = 1f; playing = false } else progressT = next
        prepareCameraTiles()
    }

    // ---- work ----
    fun loadBuckets() {
        viewModelScope.launch { buckets = runCatching { scanner.buckets() }.getOrDefault(emptyList()) }
    }

    fun scan() {
        if (scanning) return
        scanning = true
        progress = 0f
        status = "사진 훑는 중"
        playing = false
        viewModelScope.launch {
            val result = scanner.scan(fromMillis, toMillis, source, selectedBuckets) { done, total ->
                progress = if (total > 0) done.toFloat() / total else 0f
            }
            allPhotos = result.photos
            removedPhotoGroups.clear()
            status = buildString {
                append("${result.scanned}장 확인 · 좌표 있음 ${result.photos.size}장")
                if (result.skippedNotMine > 0) append(" · 내 촬영 아님 ${result.skippedNotMine}장 제외")
                if (result.skippedNoLocation > 0) append(" · 좌표 없음 ${result.skippedNoLocation}장")
            }
            if (result.photos.isEmpty()) {
                status = "좌표가 있는 사진이 없어. 기간을 넓히거나 필터를 '전체'로 바꿔봐."
            }
            scanning = false
            rebuild()
        }
    }

    private fun rebuild() {
        val inRange = allPhotos.filter { it.time in fromMillis..toMillis }
        if (inRange.isEmpty()) {
            route = null; plan = null; basemap = null; playing = false
            return
        }
        val r = buildRoute(inRange, radiusKm.toDouble())
        route = r
        val fit = MapRenderer.fitFor(r, spec)
        val p = MapRenderer.plan(r, fit, fromMillis, toMillis)
        plan = p
        pacingPhases = buildPacing(p)
        pacingWeight = pacingPhases.sumOf { it.weight }
        progressT = 1f
        playing = false
        lastTileBucket = -1

        basemapJob?.cancel()
        basemapJob = viewModelScope.launch {
            basemap = null
            basemap = MapRenderer.basemap(fit, spec)
            prepareCameraTiles(force = true)
        }
    }

    private fun prepareCameraTiles(force: Boolean = false) {
        val p = plan ?: return
        val bucket = (progressT * 100).toInt()
        if (!force && bucket == lastTileBucket) return
        if (tilePrepareJob?.isActive == true) return
        lastTileBucket = bucket
        val current = cursorAt(progressT)
        val ahead1 = cursorAt((progressT + 0.015f).coerceAtMost(1f))
        val ahead2 = cursorAt((progressT + 0.030f).coerceAtMost(1f))
        tilePrepareJob = viewModelScope.launch {
            MapRenderer.prepareTiles(p, spec, current)
            MapRenderer.prepareTiles(p, spec, ahead1)
            MapRenderer.prepareTiles(p, spec, ahead2)
            tileRevision++
        }
    }

    /** Writes the current frame into Pictures/동선지도 so it lands in the gallery. */
    fun save(onDone: (Boolean) -> Unit = {}) {
        val base = basemap ?: return onDone(false)
        val p = plan ?: return onDone(false)
        val at = cursor
        viewModelScope.launch {
            val frame = MapRenderer.snapshot(base, p, spec, at)
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "dongseon_${System.currentTimeMillis()}.png")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(
                                MediaStore.Images.Media.RELATIVE_PATH,
                                Environment.DIRECTORY_PICTURES + "/동선지도",
                            )
                        }
                    }
                    val resolver = getApplication<Application>().contentResolver
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: return@runCatching false
                    resolver.openOutputStream(uri)?.use {
                        frame.compress(Bitmap.CompressFormat.PNG, 100, it)
                    } ?: return@runCatching false
                    true
                }.getOrDefault(false)
            }
            status = if (ok) "갤러리에 저장했어" else "저장 실패"
            onDone(ok)
        }
    }

    fun removeStop(stop: Stop) {
        val removed = allPhotos.filter { it.time in stop.t0..stop.t1 }
        if (removed.isEmpty()) return
        allPhotos = allPhotos.filterNot { it.time in stop.t0..stop.t1 }
        removedPhotoGroups.addLast(removed)
        status = "정거장 1개를 동선에서 제거했어"
        rebuild()
    }

    fun undoRemoveStop() {
        val restored = removedPhotoGroups.removeLastOrNull() ?: return
        allPhotos = (allPhotos + restored).sortedBy { it.time }
        status = "마지막으로 제거한 정거장을 복구했어"
        rebuild()
    }

    val canUndoRemove: Boolean get() = removedPhotoGroups.isNotEmpty()

    private fun buildPacing(p: Plan): List<PacingPhase> = buildList {
        p.segments.forEachIndexed { i, seg ->
            val travelHours = (seg.arriveAt - seg.departAt).coerceAtLeast(1L) / 3_600_000.0
            add(PacingPhase(seg.departAt, seg.arriveAt, sqrt(travelHours.coerceAtLeast(0.25))))
            val next = p.segments.getOrNull(i + 1)?.departAt ?: p.endAt
            if (next > seg.arriveAt) {
                val stayHours = (next - seg.arriveAt) / 3_600_000.0
                add(PacingPhase(seg.arriveAt, next, sqrt(stayHours.coerceAtLeast(0.25)) * 1.35))
            }
        }
    }

    fun saveVideo() {
        if (exportingVideo) return
        val base = basemap ?: return
        val p = plan ?: return
        exportingVideo = true
        videoProgress = 0f
        playing = false
        viewModelScope.launch {
            val ok = VideoExporter.export(
                getApplication(), base, p, spec, durationSec,
                cursorAt = { cursorAt(it) },
                onProgress = { videoProgress = it },
            )
            exportingVideo = false
            status = if (ok) "영상이 갤러리에 저장됐어" else "영상 저장에 실패했어"
        }
    }

    companion object {
        fun monthsAgo(n: Int): Long = Calendar.getInstance().apply {
            add(Calendar.MONTH, -n)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun endOfToday(): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
        }.timeInMillis
    }
}
