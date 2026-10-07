package com.solarmonitor.app.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

// Same cases as web/src/lib/sun.test.ts
class SunTest {
    private val pkt = ZoneId.of("Asia/Karachi")
    private fun min(ms: Long) = Instant.ofEpochMilli(ms).atZone(pkt).let { it.hour * 60 + it.minute }

    @Test fun october() {
        val s = Sun.times(LocalDate.of(2026, 10, 7), zone = pkt)
        assertTrue(abs(min(s.rise) - (6 * 60 + 6)) <= 4); assertTrue(abs(min(s.set) - (17 * 60 + 45)) <= 4)
    }

    @Test fun june() {
        val s = Sun.times(LocalDate.of(2026, 6, 21), zone = pkt)
        assertTrue(abs(min(s.rise) - 5 * 60) <= 4); assertTrue(abs(min(s.set) - (19 * 60 + 21)) <= 4)
    }
}
