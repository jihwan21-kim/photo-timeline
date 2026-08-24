package com.photoroute.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

enum class Ratio(val label: String, val w: Int, val h: Int) {
    STORY("9:16", 1080, 1920),
    PORTRAIT("4:5", 1080, 1350),
    SQUARE("1:1", 1080, 1080),
}

data class CardSpec(
    val title: String = "나의 여행",
    val ratio: Ratio = Ratio.STORY,
    val color: Int = 0xFFFF2D74.toInt(),
    val zoomAdjust: Int = 0,
)

/** Viewport transform: map coordinates -> card pixels. */
class Fit(val z: Int, val world: Double, val ox: Double, val oy: Double) {
    fun x(lon: Double) = (mercatorX(lon, world) - ox).toFloat()
    fun y(lat: Double) = (mercatorY(lat, world) - oy).toFloat()
}

/** One trip between two stops, pre-projected so playback never re-does this work. */
class Segment(
    val path: Path,
    val length: Float,
    val departAt: Long,
    val arriveAt: Long,
    val weight: Int,
    val km: Double,
    val endX: Float,
    val endY: Float,
)

class NodeDot(val x: Float, val y: Float, val firstSeen: Long)

/**
 * Everything the animation needs, computed once per fit change. Per frame we only
 * read from this — that is what keeps playback smooth.
 */
class Plan(
    val fit: Fit,
    val segments: List<Segment>,
    val dots: List<NodeDot>,
    val startAt: Long,
    val endAt: Long,
) {
    val spanMillis get() = (endAt - startAt).coerceAtLeast(1L)
    val totalKm get() = segments.sumOf { it.km }
}

/**
 * Draws the card straight onto a Canvas — no map SDK. Tiles come from CARTO's Positron
 * basemap. The basemap is rendered once into a Bitmap; the route is drawn on top every
 * frame, which is why the animation does not stutter.
 */
object MapRenderer {

    private const val UA = "DongseonMap/1.1 (personal photo route map)"
    private val tiles = HashMap<String, Bitmap?>()

    private const val PAD_TOP = 380
    private const val PAD_SIDE = 88
    private const val PAD_BOTTOM = 160

    private val dayFormat = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA)

    // ---------- viewport ----------

    fun fitFor(route: Route, spec: CardSpec): Fit {
        val w = spec.ratio.w
        val h = spec.ratio.h
        val boxW = w - PAD_SIDE * 2
        val boxH = h - PAD_TOP - PAD_BOTTOM

        var z = 12
        while (z >= 1) {
            val world = TILE * (1 shl z)
            val b = bounds(route, world)
            if (b[1] - b[0] <= boxW && b[3] - b[2] <= boxH) break
            z--
        }
        z = (z + spec.zoomAdjust).coerceIn(1, 14)
        val world = TILE * (1 shl z)
        val b = bounds(route, world)
        val cx = PAD_SIDE + boxW / 2.0
        val cy = PAD_TOP + boxH / 2.0
        return Fit(z, world, (b[0] + b[1]) / 2 - cx, (b[2] + b[3]) / 2 - cy)
    }

    private fun bounds(route: Route, world: Double): DoubleArray {
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (n in route.nodes) {
            val x = mercatorX(n.lon, world)
            val y = mercatorY(n.lat, world)
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        if (minX > maxX) return doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        return doubleArrayOf(minX, maxX, minY, maxY)
    }

    // ---------- plan ----------

    fun plan(route: Route, fit: Fit, from: Long, to: Long): Plan {
        val segments = ArrayList<Segment>()
        val seen = HashMap<String, Int>()
        val measure = PathMeasure()

        for (i in 1 until route.stops.size) {
            val a = route.stops[i - 1]
            val b = route.stops[i]
            if (a.node == b.node) continue

            val ax = fit.x(a.lon); val ay = fit.y(a.lat)
            val bx = fit.x(b.lon); val by = fit.y(b.lat)
            val len = hypot(bx - ax, by - ay)

            val path = Path().apply {
                moveTo(ax, ay)
                if (len > 180f) {
                    // bow the long hops so overlapping legs stay readable
                    val nx = -(by - ay) / len
                    val ny = (bx - ax) / len
                    quadTo((ax + bx) / 2 + nx * len * 0.11f, (ay + by) / 2 + ny * len * 0.11f, bx, by)
                } else lineTo(bx, by)
            }
            measure.setPath(path, false)

            val key = if (a.node < b.node) "${a.node}-${b.node}" else "${b.node}-${a.node}"
            val weight = (seen[key] ?: 0) + 1
            seen[key] = weight

            segments.add(
                Segment(
                    path = path,
                    length = measure.length,
                    departAt = a.t1,
                    arriveAt = maxOf(b.t0, a.t1 + 1),
                    weight = weight,
                    km = haversine(a.lat, a.lon, b.lat, b.lon),
                    endX = bx, endY = by,
                )
            )
        }

        val firstSeen = HashMap<Int, Long>()
        for (s in route.stops) firstSeen.putIfAbsent(s.node, s.t0)
        val dots = route.nodes.mapIndexed { i, n ->
            NodeDot(fit.x(n.lon), fit.y(n.lat), firstSeen[i] ?: from)
        }

        val start = route.stops.firstOrNull()?.t0 ?: from
        val end = route.stops.lastOrNull()?.t1 ?: to
        return Plan(fit, segments, dots, start, maxOf(end, start + 1))
    }

    // ---------- basemap ----------

    suspend fun basemap(fit: Fit, spec: CardSpec): Bitmap = withContext(Dispatchers.IO) {
        val w = spec.ratio.w
        val h = spec.ratio.h
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFFE9E6E0.toInt())

        // floor(), not toInt() — truncation drops a tile when the origin goes negative
        val t0 = floor(fit.ox / 256.0).toInt()
        val t1 = floor((fit.ox + w) / 256.0).toInt() + 1
        val s0 = floor(fit.oy / 256.0).toInt()
        val s1 = floor((fit.oy + h) / 256.0).toInt() + 1
        if ((t1 - t0).toLong() * (s1 - s0) <= 400) {
            val max = 1 shl fit.z
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            for (x in t0 until t1) for (y in s0 until s1) {
                if (y < 0 || y >= max) continue
                val wx = ((x % max) + max) % max
                val tile = tile(fit.z, wx, y) ?: continue
                val left = (x * 256 - fit.ox).toFloat()
                val top = (y * 256 - fit.oy).toFloat()
                c.drawBitmap(tile, null, RectF(left, top, left + 256f, top + 256f), paint)
            }
        }
        drawAttribution(c, w, h)
        bmp
    }

    private fun tile(z: Int, x: Int, y: Int): Bitmap? {
        val key = "$z/$x/$y"
        if (tiles.containsKey(key)) return tiles[key]
        val sub = "abcd"[(x + y) % 4]
        val bmp = runCatching {
            val conn = URL("https://$sub.basemaps.cartocdn.com/light_all/$z/$x/$y@2x.png")
                .openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", UA)
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
        tiles[key] = bmp
        return bmp
    }

    // ---------- overlay (redrawn every frame) ----------

    private val legPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF22201F.toInt() }
    private val measure = PathMeasure()
    private val scratch = Path()
    private val pos = FloatArray(2)

    /** Synchronized: the UI draw pass and snapshot() share these Paint/PathMeasure objects. */
    @Synchronized
    fun drawOverlay(c: Canvas, plan: Plan, spec: CardSpec, cursor: Long) {
        var kmSoFar = 0.0
        var headX = plan.dots.firstOrNull()?.x ?: 0f
        var headY = plan.dots.firstOrNull()?.y ?: 0f
        val heaviest = plan.segments.maxOfOrNull { it.weight } ?: 1

        // finished legs first, then the one in flight
        for (seg in plan.segments) {
            if (cursor < seg.departAt) continue
            val k = seg.weight.toFloat() / heaviest
            legPaint.color = spec.color
            legPaint.alpha = (255 * (0.42f + 0.58f * k)).toInt()
            legPaint.strokeWidth = 6.4f + 10.8f * k

            if (cursor >= seg.arriveAt) {
                c.drawPath(seg.path, legPaint)
                kmSoFar += seg.km
                headX = seg.endX; headY = seg.endY
            } else {
                val f = ((cursor - seg.departAt).toFloat() /
                    (seg.arriveAt - seg.departAt).toFloat()).coerceIn(0f, 1f)
                measure.setPath(seg.path, false)
                scratch.reset()
                if (measure.getSegment(0f, measure.length * f, scratch, true)) {
                    c.drawPath(scratch, legPaint)
                }
                measure.getPosTan(measure.length * f, pos, null)
                kmSoFar += seg.km * f
                headX = pos[0]; headY = pos[1]
            }
        }

        // stops that exist by now
        dotPaint.color = spec.color
        for (d in plan.dots) {
            if (cursor >= d.firstSeen) c.drawCircle(d.x, d.y, 11f, dotPaint)
        }

        // the head of the trip
        ringPaint.color = spec.color
        c.drawCircle(headX, headY, 30f, ringPaint)
        c.drawCircle(headX, headY, 20f, corePaint)

        drawCard(c, spec, plan, cursor, kmSoFar)
    }

    private fun drawCard(c: Canvas, spec: CardSpec, plan: Plan, cursor: Long, kmSoFar: Double) {
        val w = spec.ratio.w
        val sub = "${dayFormat.format(plan.startAt)} – ${dayFormat.format(cursor)} · ${km(kmSoFar)}"
        val cw = w - 176f
        val left = 88f
        val top = 104f
        val height = 192f

        val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF0FCFBF9.toInt() }
        panel.setShadowLayer(28f, 0f, 8f, 0x2B000000)
        c.drawRoundRect(RectF(left, top, left + cw, top + height), 30f, 30f, panel)

        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF141414.toInt()
            textSize = 66f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        c.drawText(spec.title.ifBlank { "나의 여행" }, left + cw / 2, top + 88f, title)

        val s = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF5C5C5C.toInt(); textSize = 36f; textAlign = Paint.Align.CENTER
        }
        c.drawText(sub, left + cw / 2, top + 148f, s)
    }

    /** CARTO and OpenStreetMap both require visible attribution — keep this. */
    private fun drawAttribution(c: Canvas, w: Int, h: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x9E282E34.toInt(); textSize = 22f; textAlign = Paint.Align.RIGHT
        }
        c.drawText("© OpenStreetMap contributors  © CARTO", w - 24f, h - 22f, p)
    }

    /** Flattens basemap + overlay at one moment into a shareable PNG. */
    suspend fun snapshot(base: Bitmap, plan: Plan, spec: CardSpec, cursor: Long): Bitmap =
        withContext(Dispatchers.Default) {
            val out = base.copy(Bitmap.Config.ARGB_8888, true)
            drawOverlay(Canvas(out), plan, spec, cursor)
            out
        }

    fun km(v: Double): String =
        NumberFormat.getIntegerInstance(Locale.KOREA).format(v.roundToInt()) + "km"

    fun day(t: Long): String = dayFormat.format(t)
}
