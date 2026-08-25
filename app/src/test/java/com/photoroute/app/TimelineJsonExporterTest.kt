package com.photoroute.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimelineJsonExporterTest {

    @Test
    fun writesCanonicalTimelinePathInTimeOrder() {
        val points = normalizeTimelinePoints(
            listOf(
                point("2025-08-20T19:20:00Z", 40.7128, -74.0060),
                point("2025-01-15T03:12:44Z", 37.5665, 126.9780),
                point("2025-04-08T14:30:00Z", 48.8566, 2.3522),
            )
        )

        val root = JSONObject(buildTimelineJson(points))
        val segment = root.getJSONArray("semanticSegments").getJSONObject(0)
        val path = segment.getJSONArray("timelinePath")

        assertEquals("2025-01-15T03:12:44Z", segment.getString("startTime"))
        assertEquals("2025-08-20T19:20:00Z", segment.getString("endTime"))
        assertEquals(3, path.length())
        assertEquals("37.5665000,126.9780000", path.getJSONObject(0).getString("point"))
        assertEquals("48.8566000,2.3522000", path.getJSONObject(1).getString("point"))
        assertEquals("40.7128000,-74.0060000", path.getJSONObject(2).getString("point"))
        assertEquals("2025-04-08T14:30:00Z", path.getJSONObject(1).getString("time"))
    }

    @Test
    fun coordinatesStayLocaleIndependentAndTimesStayUtc() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val json = buildTimelineJson(
                listOf(point("2025-03-18T01:30:45.123Z", -33.8688, 151.2093))
            )
            val segment = JSONObject(json).getJSONArray("semanticSegments").getJSONObject(0)
            val exported = segment.getJSONArray("timelinePath").getJSONObject(0)

            assertEquals("-33.8688000,151.2093000", exported.getString("point"))
            assertEquals("2025-03-18T01:30:45.123Z", exported.getString("time"))
            assertEquals(segment.getString("startTime"), segment.getString("endTime"))
            assertFalse(exported.getString("point").contains("151,209"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun removesOnlyExactTriplesAndRejectsUnsupportedCoordinates() {
        val time = "2025-01-01T00:00:00Z"
        val normalized = normalizeTimelinePoints(
            listOf(
                point(time, 37.5, 127.0),
                point(time, 37.5, 127.0),
                point(time, 38.5, 127.0),
                point("2025-01-01T00:00:00.001Z", 37.5, 127.0),
                point(time, 89.0, 10.0),
                TimelinePoint(Double.NaN, 10.0, 1L),
                TimelinePoint(10.0, 181.0, 1L),
                TimelinePoint(10.0, 10.0, 0L),
            )
        )

        assertEquals(3, normalized.size)
        assertEquals(2, normalized.count { it.timeMillis == Instant.parse(time).toEpochMilli() })
        assertTrue(normalized.any { it.latitude == 38.5 })
    }

    @Test
    fun timelineRangeIncludesStartAndExcludesNextDayStart() {
        val start = Instant.parse("2026-03-08T05:00:00Z").toEpochMilli()
        val nextDayStart = Instant.parse("2026-03-09T04:00:00Z").toEpochMilli()

        assertTrue(isInTimelineRange(start, start, nextDayStart))
        assertTrue(isInTimelineRange(nextDayStart - 1L, start, nextDayStart))
        assertFalse(isInTimelineRange(start - 1L, start, nextDayStart))
        assertFalse(isInTimelineRange(nextDayStart, start, nextDayStart))
    }

    private fun point(instant: String, latitude: Double, longitude: Double) = TimelinePoint(
        latitude = latitude,
        longitude = longitude,
        timeMillis = Instant.parse(instant).toEpochMilli(),
    )
}
