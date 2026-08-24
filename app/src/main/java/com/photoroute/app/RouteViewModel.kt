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

class RouteViewModel(app: Application) : AndroidViewModel(app) {

    private val scanner = PhotoScanner(app)
    private var basemapJob: Job? = null

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
    /** true = every leg gets equal screen time; false = wall-clock proportional. */
    var evenPacing by mutableStateOf(true); private set

    private var allPhotos: List<Photo> = emptyList()

    val cursor: Long get() = cursorAt(progressT)

    /**
     * Maps playback progress onto a moment in time. Real time is honest but unwatchable —
     * two months parked in one city would be two months of a motionless map — so by default
     * each leg gets the same slice of the animation, with a short dwell at each arrival.
     */
    private fun cursorAt(t: Float): Long {
        val p = plan ?: return 0L
        if (!evenPacing) return p.startAt + (p.spanMillis * t).toLong()
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
        playing = true
    }
    fun pause() { playing = false }
    fun advance(deltaSec: Float) {
        val step = deltaSec * speed / durationSec
        val next = progressT + step
        if (next >= 1f) { progressT = 1f; playing = false } else progressT = next
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
        progressT = 1f
        playing = false

        basemapJob?.cancel()
        basemapJob = viewModelScope.launch {
            basemap = null
            basemap = MapRenderer.basemap(fit, spec)
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
