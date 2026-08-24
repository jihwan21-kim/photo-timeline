package com.photoroute.app

import android.graphics.PathMeasure
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapRendererTest {
    private lateinit var route: Route
    private lateinit var plan: Plan

    @Before
    fun setUp() {
        MapRenderer.initialize(ApplicationProvider.getApplicationContext())
        route = buildRoute(
            listOf(
                Photo(40.7128, -74.0060, 1_000L),
                Photo(48.8566, 2.3522, 2_000L),
                Photo(37.5665, 126.9780, 3_000L),
                Photo(40.7128, -74.0060, 4_000L),
            ),
            radiusKm = 1.0,
        )
        val spec = CardSpec(ratio = Ratio.STORY)
        val fit = MapRenderer.fitFor(route, spec)
        plan = MapRenderer.plan(route, fit, 1_000L, 4_000L)
    }

    @Test
    fun worldTourReturnsToSameCityOneWorldToTheRight() {
        assertEquals(4, plan.dots.size)
        assertTrue(plan.dots.zipWithNext().all { (a, b) -> b.x > a.x })
        assertEquals(plan.fit.world, (plan.dots.last().x - plan.dots.first().x).toDouble(), 0.01)
    }

    @Test
    fun transferZoomsOutThenReturnsToCitySpan() {
        val spec = CardSpec(ratio = Ratio.STORY)
        val segment = plan.segments.first()
        val atDeparture = MapRenderer.cameraAt(plan, spec, segment.departAt)
        val halfway = MapRenderer.cameraAt(plan, spec, (segment.departAt + segment.arriveAt) / 2)
        val atArrival = MapRenderer.cameraAt(plan, spec, segment.arriveAt)

        assertTrue(halfway.spanY > atDeparture.spanY * 10f)
        assertEquals(atDeparture.spanY, atArrival.spanY, 0.001f)
    }

    @Test
    fun viewportMatchesEveryCardAspectRatio() {
        Ratio.entries.forEach { ratio ->
            val spec = CardSpec(ratio = ratio)
            val segment = plan.segments.first()
            val viewport = MapRenderer.viewportAt(plan, spec, (segment.departAt + segment.arriveAt) / 2)
            val actual = (viewport.maxWorldX - viewport.minWorldX) /
                (viewport.maxWorldY - viewport.minWorldY)
            assertEquals(ratio.w.toDouble() / ratio.h, actual, 1e-9)
        }
    }

    @Test
    fun newYorkToParisUsesNorthernGreatCircle() {
        val segment = plan.segments.first()
        val measure = PathMeasure(segment.path, false)
        val position = FloatArray(2)
        measure.getPosTan(measure.length / 2f, position, null)
        val linearMidY = (segment.startY + segment.endY) / 2f
        assertTrue("great-circle midpoint should bend north", position[1] < linearMidY)
    }

    @Test
    fun wrappedTileKeepsUnboundedWorldPlacement() {
        val spec = CardSpec(ratio = Ratio.STORY)
        val viewport = MapRenderer.viewportAt(plan, spec, plan.endAt)
        val tiles = MapRenderer.requiredTiles(viewport)
        val count = 1 shl viewport.tileZoom
        assertTrue(tiles.any { it.worldX >= count })
        assertTrue(tiles.all { it.wrappedX in 0 until count })
    }
}
