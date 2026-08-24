package com.photoroute.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldWrapTest {
    @Test
    fun newYorkParisSeoulNewYorkContinuesEastAndReturnsAfterOneWorld() {
        val longitudes = listOf(-74.0, 2.0, 127.0, -74.0)
        val xs = ArrayList<Double>()
        longitudes.forEach { lon ->
            val raw = mercatorX(lon, 1.0)
            xs += xs.lastOrNull()?.let { unwrapWorldNear(raw, it, 1.0) } ?: raw
        }

        assertTrue(xs.zipWithNext().all { (a, b) -> b > a })
        assertEquals(1.0, xs.last() - xs.first(), 1e-9)
    }

    @Test
    fun datelineNeighborsStayNeighbors() {
        val east = mercatorX(179.0, 1.0)
        val west = unwrapWorldNear(mercatorX(-179.0, 1.0), east, 1.0)
        assertTrue(kotlin.math.abs(west - east) < 0.01)
    }

    @Test
    fun datelinePhotoClusterDoesNotCollapseToGreenwich() {
        val route = buildRoute(
            listOf(Photo(37.0, 179.0, 1L), Photo(37.0, -179.0, 2L)),
            radiusKm = 500.0,
        )
        assertEquals(1, route.stops.size)
        assertTrue(kotlin.math.abs(route.stops.single().lon) > 170.0)
    }
}
