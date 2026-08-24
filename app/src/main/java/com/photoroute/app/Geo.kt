package com.photoroute.app

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

const val EARTH_KM = 6371.0
const val TILE = 256.0

data class Photo(val lat: Double, val lon: Double, val time: Long)

/** A run of consecutive photos taken close together — one place you stopped. */
class Stop(var lat: Double, var lon: Double, val t0: Long) {
    var count: Int = 1
    var t1: Long = t0
    var node: Int = -1
}

class Node(val lat: Double, val lon: Double) { var count: Int = 0 }

/** A trip between two nodes. [weight] is how many times it was travelled. */
class Leg(val a: Int, val b: Int, val km: Double) { var weight: Int = 1 }

class Route(
    val stops: List<Stop>,
    val nodes: List<Node>,
    val legs: List<Leg>,
    val km: Double,
) {
    val isEmpty get() = nodes.isEmpty()
}

fun haversine(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val p = PI / 180.0
    val dLat = (bLat - aLat) * p
    val dLon = (bLon - aLon) * p
    val h = sin(dLat / 2).pow(2) + cos(aLat * p) * cos(bLat * p) * sin(dLon / 2).pow(2)
    return 2.0 * EARTH_KM * asin(minOf(1.0, sqrt(h)))
}

fun mercatorX(lon: Double, world: Double) = (lon + 180.0) / 360.0 * world

internal fun unwrapWorldNear(value: Double, reference: Double, world: Double): Double =
    value + kotlin.math.round((reference - value) / world) * world

fun mercatorY(lat: Double, world: Double): Double {
    val s = sin(lat.coerceIn(-85.0511, 85.0511) * PI / 180.0)
    return (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * world
}

/**
 * Consecutive photos within [radiusKm] collapse into one stop; stops that land back
 * on an earlier place share a node, so a leg travelled twice renders twice as heavy.
 */
fun buildRoute(photos: List<Photo>, radiusKm: Double): Route {
    if (photos.isEmpty()) return Route(emptyList(), emptyList(), emptyList(), 0.0)

    val sorted = photos.sortedBy { it.time }
    val stops = ArrayList<Stop>()
    var cur: Stop? = null

    for (p in sorted) {
        val c = cur
        if (c != null && haversine(c.lat, c.lon, p.lat, p.lon) <= radiusKm) {
            c.count++
            c.lat += (p.lat - c.lat) / c.count      // running centroid
            val continuousLon = unwrapLongitudeNear(p.lon, c.lon)
            c.lon += (continuousLon - c.lon) / c.count
            c.t1 = p.time
        } else {
            cur = Stop(p.lat, p.lon, p.time).also { stops.add(it) }
        }
    }

    val nodes = ArrayList<Node>()
    for (s in stops) {
        var idx = nodes.indexOfFirst { haversine(it.lat, it.lon, s.lat, s.lon) <= radiusKm }
        if (idx < 0) { nodes.add(Node(s.lat, s.lon)); idx = nodes.size - 1 }
        nodes[idx].count += s.count
        s.node = idx
    }

    val legs = ArrayList<Leg>()
    var total = 0.0
    for (i in 1 until stops.size) {
        val a = stops[i - 1]
        val b = stops[i]
        if (a.node == b.node) continue
        val d = haversine(a.lat, a.lon, b.lat, b.lon)
        total += d
        val existing = legs.firstOrNull {
            (it.a == a.node && it.b == b.node) || (it.a == b.node && it.b == a.node)
        }
        if (existing != null) existing.weight++ else legs.add(Leg(a.node, b.node, d))
    }
    return Route(stops, nodes, legs, total)
}

private fun unwrapLongitudeNear(value: Double, reference: Double): Double {
    var result = value
    while (result - reference > 180.0) result -= 360.0
    while (result - reference < -180.0) result += 360.0
    return result
}
