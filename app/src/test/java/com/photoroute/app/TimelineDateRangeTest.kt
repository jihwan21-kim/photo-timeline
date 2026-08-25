package com.photoroute.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

class TimelineDateRangeTest {

    @Test
    fun newYorkSpringDayHas23HoursAndFallDayHas25Hours() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))

            val springSelection = Instant.parse("2026-03-08T00:00:00Z").toEpochMilli()
            val springStart = utcDayToLocalStart(springSelection)
            val springEnd = utcDayToLocalNextStart(springSelection)
            assertEquals(23L * 60L * 60L * 1_000L, springEnd - springStart)

            val fallSelection = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()
            val fallStart = utcDayToLocalStart(fallSelection)
            val fallEnd = utcDayToLocalNextStart(fallSelection)
            assertEquals(25L * 60L * 60L * 1_000L, fallEnd - fallStart)
        } finally {
            TimeZone.setDefault(previous)
        }
    }
}
