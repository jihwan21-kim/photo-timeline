package com.photoroute.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.log2
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sqrt

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
class Fit(val z: Int, val world: Double, val ox: Double, val oy: Double, private val centerWorldX: Double) {
    fun x(lon: Double): Float {
        val raw = mercatorX(lon, world)
        val wrapped = unwrapWorldNear(raw, centerWorldX, world)
        return (wrapped - ox).toFloat()
    }
    fun y(lat: Double) = (mercatorY(lat, world) - oy).toFloat()

    fun xAfter(lon: Double, previousX: Float, forceEast: Boolean): Float {
        val raw = (mercatorX(lon, world) - ox).toFloat()
        var x = unwrapWorldNear(raw.toDouble(), previousX.toDouble(), world).toFloat()
        if (forceEast && x < previousX) x += world.toFloat()
        return x
    }
}

/** One trip between two stops, pre-projected so playback never re-does this work. */
class Segment(
    val path: Path,
    val length: Float,
    val departAt: Long,
    val arriveAt: Long,
    val weight: Int,
    val km: Double,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val spanX: Float,
    val spanY: Float,
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
    val minRouteX get() = dots.minOfOrNull { it.x } ?: 0f
    val maxRouteX get() = dots.maxOfOrNull { it.x } ?: 0f
}

/**
 * Draws the card straight onto a Canvas — no map SDK. Tiles come from CARTO's Positron
 * basemap. The basemap is rendered once into a Bitmap; the route is drawn on top every
 * frame, which is why the animation does not stutter.
 */
object MapRenderer {

    private lateinit var tileStore: TileStore

    fun initialize(context: android.content.Context) {
        if (!::tileStore.isInitialized) tileStore = TileStore(context.applicationContext)
    }

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
        val centerX = (b[0] + b[1]) / 2
        return Fit(z, world, centerX - cx, (b[2] + b[3]) / 2 - cy, centerX)
    }

    private fun bounds(route: Route, world: Double): DoubleArray {
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var previousX: Double? = null
        for (n in route.stops) {
            val rawX = mercatorX(n.lon, world)
            val x = previousX?.let { unwrapWorldNear(rawX, it, world) } ?: rawX
            previousX = x
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

        val stopPositions = ArrayList<Pair<Float, Float>>(route.stops.size)
        route.stops.forEachIndexed { index, stop ->
            val y = fit.y(stop.lat)
            if (index == 0) {
                stopPositions += fit.x(stop.lon) to y
            } else {
                val previousX = stopPositions.last().first
                stopPositions += fit.xAfter(stop.lon, previousX, false) to y
            }
        }

        for (i in 1 until route.stops.size) {
            val a = route.stops[i - 1]
            val b = route.stops[i]
            if (a.node == b.node) continue

            val (ax, ay) = stopPositions[i - 1]
            val (bx, by) = stopPositions[i]
            val km = haversine(a.lat, a.lon, b.lat, b.lon)

            val path = Path().apply {
                moveTo(ax, ay)
                val steps = ceil(km / 75.0).toInt().coerceIn(1, 320)
                if (steps > 1) {
                    var previousX = ax
                    for (step in 1..steps) {
                        val point = greatCircle(a.lat, a.lon, b.lat, b.lon, step.toDouble() / steps)
                        val x = fit.xAfter(point.second, previousX, false)
                        lineTo(x, fit.y(point.first))
                        previousX = x
                    }
                } else lineTo(bx, by)
            }
            measure.setPath(path, false)
            val pathBounds = RectF().also { path.computeBounds(it, true) }

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
                    km = km,
                    startX = ax, startY = ay,
                    endX = bx, endY = by,
                    spanX = pathBounds.width(), spanY = pathBounds.height(),
                )
            )
        }

        val dots = route.stops.mapIndexed { index, stop ->
            val point = stopPositions[index]
            NodeDot(point.first, point.second, stop.t0)
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
        bmp
    }

    private fun tile(z: Int, x: Int, y: Int): Bitmap? {
        return if (::tileStore.isInitialized) tileStore.load(z, x, y) else null
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
    private fun drawRoute(c: Canvas, plan: Plan, spec: CardSpec, cursor: Long, renderScale: Float = 1f) {
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
            legPaint.strokeWidth = (6.4f + 10.8f * k) / renderScale

            if (cursor >= seg.arriveAt) {
                c.drawPath(seg.path, legPaint)
                kmSoFar += seg.km
                headX = seg.endX; headY = seg.endY
            } else {
                val linear = ((cursor - seg.departAt).toFloat() /
                    (seg.arriveAt - seg.departAt).toFloat()).coerceIn(0f, 1f)
                val f = smoothStep(linear)
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
            if (cursor >= d.firstSeen) c.drawCircle(d.x, d.y, 11f / renderScale, dotPaint)
        }

        // the head of the trip
        ringPaint.color = spec.color
        ringPaint.strokeWidth = 10f / renderScale
        c.drawCircle(headX, headY, 30f / renderScale, ringPaint)
        c.drawCircle(headX, headY, 20f / renderScale, corePaint)

    }

    /** Dynamic tile viewport, following the same camera model as the reference visualizer. */
    @Synchronized
    fun drawScene(c: Canvas, base: Bitmap, plan: Plan, spec: CardSpec, cursor: Long) {
        val w = spec.ratio.w.toFloat()
        val h = spec.ratio.h.toFloat()
        val viewport = viewportAt(plan, spec, cursor)
        c.drawColor(0xFFE9E6E0.toInt())
        drawDynamicTiles(c, viewport, w, h)

        val scale = h / viewport.spanYPixels
        val save = c.save()
        c.translate(w / 2f, h / 2f)
        c.scale(scale, scale)
        c.translate(-viewport.centerX, -viewport.centerY)
        val halfX = viewport.spanYPixels * (w / h) / 2f
        val world = plan.fit.world.toFloat()
        val firstCopy = ceil((viewport.centerX - halfX - plan.maxRouteX) / world).toInt()
        val lastCopy = floor((viewport.centerX + halfX - plan.minRouteX) / world).toInt()
        for (copy in firstCopy..lastCopy) {
            val shifted = c.save()
            c.translate(copy * world, 0f)
            drawRoute(c, plan, spec, cursor, scale)
            c.restoreToCount(shifted)
        }
        c.restoreToCount(save)

        drawCard(c, spec, plan, cursor, distanceAt(plan, cursor))
        drawAttribution(c, spec.ratio.w, spec.ratio.h)
    }

    internal data class Camera(val x: Float, val y: Float, val spanY: Float)
    internal data class DynamicViewport(
        val centerX: Float,
        val centerY: Float,
        val spanYPixels: Float,
        val minWorldX: Double,
        val maxWorldX: Double,
        val minWorldY: Double,
        val maxWorldY: Double,
        val tileZoom: Int,
    )

    internal fun cameraAt(plan: Plan, spec: CardSpec, cursor: Long): Camera {
        val cameraMeasure = PathMeasure()
        val cameraPosition = FloatArray(2)
        val first = plan.dots.firstOrNull()
        var x = first?.x ?: 0f
        var y = first?.y ?: 0f
        val citySpan = (plan.fit.world * 0.00035).toFloat()
        var span = citySpan
        for (seg in plan.segments) {
            if (cursor < seg.departAt) break
            if (cursor >= seg.arriveAt) {
                x = seg.endX; y = seg.endY; span = citySpan
            } else {
                val linear = ((cursor - seg.departAt).toFloat() / (seg.arriveAt - seg.departAt)).coerceIn(0f, 1f)
                val f = smoothStep(linear)
                cameraMeasure.setPath(seg.path, false)
                cameraMeasure.getPosTan(cameraMeasure.length * f, cameraPosition, null)
                x = cameraPosition[0]; y = cameraPosition[1]
                val transferSpan = travelSpan(plan, spec, seg).coerceAtLeast(citySpan)
                val zoomOut = when {
                    linear < 0.20f -> smoothStep(linear / 0.20f)
                    linear <= 0.75f -> 1f
                    else -> 1f - smoothStep((linear - 0.75f) / 0.25f)
                }
                span = kotlin.math.exp(
                    kotlin.math.ln(citySpan) +
                        (kotlin.math.ln(transferSpan) - kotlin.math.ln(citySpan)) * zoomOut,
                )
                break
            }
        }
        return Camera(x, y, span)
    }

    private fun distanceAt(plan: Plan, cursor: Long): Double {
        var km = 0.0
        for (seg in plan.segments) {
            if (cursor <= seg.departAt) break
            if (cursor >= seg.arriveAt) km += seg.km else {
                val f = ((cursor - seg.departAt).toDouble() / (seg.arriveAt - seg.departAt)).coerceIn(0.0, 1.0)
                km += seg.km * f
                break
            }
        }
        return km
    }

    private fun smoothStep(v: Float): Float = v * v * (3f - 2f * v)

    private fun travelSpan(plan: Plan, spec: CardSpec, seg: Segment): Float {
        val dx = seg.spanX.coerceAtLeast(1f)
        val dy = seg.spanY.coerceAtLeast(1f)
        val aspect = spec.ratio.w.toFloat() / spec.ratio.h
        return maxOf(dy * 1.25f, dx * 1.25f / aspect, plan.fit.world.toFloat() * 0.00035f)
            .coerceAtMost(plan.fit.world.toFloat() * 0.72f)
    }

    internal fun viewportAt(plan: Plan, spec: CardSpec, cursor: Long): DynamicViewport {
        val camera = cameraAt(plan, spec, cursor)
        val world = plan.fit.world
        val spanY = (camera.spanY / world).coerceIn(0.00030, 0.72)
        val aspect = spec.ratio.w.toDouble() / spec.ratio.h
        val spanX = spanY * aspect
        val cx = (camera.x + plan.fit.ox) / world
        val cy = (camera.y + plan.fit.oy) / world
        val zoom = floor(log2(spec.ratio.w / (256.0 * spanX))).toInt().coerceIn(2, 15)
        return DynamicViewport(
            camera.x, camera.y, (spanY * world).toFloat(),
            cx - spanX / 2, cx + spanX / 2, cy - spanY / 2, cy + spanY / 2, zoom,
        )
    }

    internal data class DynamicTile(val z: Int, val wrappedX: Int, val y: Int, val worldX: Int)

    suspend fun prepareTiles(plan: Plan, spec: CardSpec, cursor: Long) = coroutineScope {
        requiredTiles(viewportAt(plan, spec, cursor)).distinctBy { Triple(it.z, it.wrappedX, it.y) }
            .map { entry -> async(Dispatchers.IO) { tile(entry.z, entry.wrappedX, entry.y) } }
            .awaitAll()
        Unit
    }

    internal fun requiredTiles(viewport: DynamicViewport): List<DynamicTile> {
        val count = 1 shl viewport.tileZoom
        val x0 = floor(viewport.minWorldX * count).toInt()
        val x1 = floor(viewport.maxWorldX * count).toInt()
        val y0 = floor(viewport.minWorldY * count).toInt().coerceIn(0, count - 1)
        val y1 = floor(viewport.maxWorldY * count).toInt().coerceIn(0, count - 1)
        return buildList {
            for (x in x0..x1) for (y in y0..y1) {
                add(DynamicTile(viewport.tileZoom, ((x % count) + count) % count, y, x))
            }
        }
    }

    private fun drawDynamicTiles(c: Canvas, viewport: DynamicViewport, w: Float, h: Float) {
        val count = 1 shl viewport.tileZoom
        val required = requiredTiles(viewport)
        required.forEach { entry ->
            val left = ((entry.worldX.toDouble() / count - viewport.minWorldX) /
                (viewport.maxWorldX - viewport.minWorldX) * w).toFloat()
            val right = (((entry.worldX + 1).toDouble() / count - viewport.minWorldX) /
                (viewport.maxWorldX - viewport.minWorldX) * w).toFloat()
            val top = ((entry.y.toDouble() / count - viewport.minWorldY) /
                (viewport.maxWorldY - viewport.minWorldY) * h).toFloat()
            val bottom = (((entry.y + 1).toDouble() / count - viewport.minWorldY) /
                (viewport.maxWorldY - viewport.minWorldY) * h).toFloat()
            val destination = RectF(left, top, right + 1f, bottom + 1f)
            val exact = tileStore.cached(entry.z, entry.wrappedX, entry.y)
            if (exact != null) {
                c.drawBitmap(exact, null, destination, null)
            } else {
                drawParentTile(c, entry, destination)
            }
        }
    }

    private fun drawParentTile(c: Canvas, tile: DynamicTile, destination: RectF) {
        for (parentZoom in tile.z - 1 downTo 1) {
            val shift = tile.z - parentZoom
            val divisions = 1 shl shift
            val parentX = tile.wrappedX shr shift
            val parentY = tile.y shr shift
            val bitmap = tileStore.cached(parentZoom, parentX, parentY) ?: continue
            val childX = tile.wrappedX and (divisions - 1)
            val childY = tile.y and (divisions - 1)
            val cellW = bitmap.width.toFloat() / divisions
            val cellH = bitmap.height.toFloat() / divisions
            val source = android.graphics.Rect(
                (childX * cellW).toInt(),
                (childY * cellH).toInt(),
                ((childX + 1) * cellW).toInt().coerceAtMost(bitmap.width),
                ((childY + 1) * cellH).toInt().coerceAtMost(bitmap.height),
            )
            c.drawBitmap(bitmap, source, destination, null)
            return
        }
    }

    private fun greatCircle(
        aLat: Double,
        aLon: Double,
        bLat: Double,
        bLon: Double,
        fraction: Double,
    ): Pair<Double, Double> {
        val lat1 = Math.toRadians(aLat); val lon1 = Math.toRadians(aLon)
        val lat2 = Math.toRadians(bLat); val lon2 = Math.toRadians(bLon)
        val ax = cos(lat1) * cos(lon1); val ay = cos(lat1) * sin(lon1); val az = sin(lat1)
        val bx = cos(lat2) * cos(lon2); val by = cos(lat2) * sin(lon2); val bz = sin(lat2)
        val omega = acos((ax * bx + ay * by + az * bz).coerceIn(-1.0, 1.0))
        val denominator = sin(omega)
        val left = if (denominator < 1e-8) 1.0 - fraction else sin((1.0 - fraction) * omega) / denominator
        val right = if (denominator < 1e-8) fraction else sin(fraction * omega) / denominator
        val x = left * ax + right * bx
        val y = left * ay + right * by
        val z = left * az + right * bz
        return Math.toDegrees(atan2(z, sqrt(x * x + y * y))) to Math.toDegrees(atan2(y, x))
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
            drawScene(Canvas(out), base, plan, spec, cursor)
            out
        }

    fun km(v: Double): String =
        NumberFormat.getIntegerInstance(Locale.KOREA).format(v.roundToInt()) + "km"

    fun day(t: Long): String = dayFormat.format(t)
}
