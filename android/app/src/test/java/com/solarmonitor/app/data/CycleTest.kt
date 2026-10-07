package com.solarmonitor.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Same cases as web/src/lib/billnow.test.ts: the month runs from 8 PM on the reading day. */
class CycleTest {
    private val z = ZoneId.of("Asia/Karachi")
    private val c = BillConfig(day = 8, hist = listOf(PastBill(202608, 180, 0), PastBill(202609, 180, 0), PastBill(202610, 180, 0)))
    private fun at(m: Int, d: Int, h: Int) = LocalDateTime.of(2026, m, d, h, 0).atZone(z).toInstant().toEpochMilli()
    private fun cyc(s: Long, gWh: Double, min: Long) = Cyc(s / 1000, s / 1000, gWh, gWh, 0.0, min)

    @Test fun readingDayBeforeAndAfter8pm() {
        assertEquals(20260908, BillCalc.cycleStart(BillCalc.billDate(at(10, 8, 19), 20, z), 8))
        assertEquals(20261008, BillCalc.cycleStart(BillCalc.billDate(at(10, 8, 21), 20, z), 8))
    }

    @Test fun countsFromTheReading() {
        val (m, missing) = BillCalc.fromCounter(cyc(at(10, 8, 20), 500.0, 60), at(10, 8, 21), c, 180.0, z)!!
        assertEquals(20261008, m.start)
        assertEquals(0.5, m.soFar, 1e-6)
        assertEquals(0.0, missing, 1e-6)
    }

    @Test fun fillsInTimeTheMonitorWasOff() {
        val (m, missing) = BillCalc.fromCounter(cyc(at(10, 8, 20), 6000.0, 1440), at(10, 10, 20), c, 180.0, z)!!
        assertEquals(1.0, missing, 1e-6)
        assertTrue(m.soFar > 6)
        assertTrue(m.projected > m.soFar)
    }

    @Test fun ignoresAnotherMonthsCounter() {
        assertNull(BillCalc.fromCounter(cyc(at(10, 1, 20), 6000.0, 1440), at(10, 10, 20), c, 180.0, z))
    }
}
