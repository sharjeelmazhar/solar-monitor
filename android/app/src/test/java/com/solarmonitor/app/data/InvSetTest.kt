package com.solarmonitor.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same ranges as the firmware check (firmware/solar_monitor_v3/invset.h) and the web app (web/src/lib/invset.ts). */
class InvSetTest {
    private val q = "230.0 13.9 230.0 50.0 13.9 3200 3200 24.0 24.0 22.1 27.2 26.8 02 010 050 1 1 1 1 01 0 0 26.5 0 1"
    private val r = Rated.parse(q)!!
    private val chg = "010 020 030 040 050 060 070 080 090 100 110 120"
    private val ac = "002 010 020 030 040 050 060 070 080 090 100"
    private fun values(key: String) = InvSet.choices(InvSet.def(key), r, chg, ac)!!.map { it.value }

    @Test fun bulkNeverBelowFloatAndWithin24VRange() {
        val v = values("bulk")
        assertEquals(26.8, v.first(), 1e-6); assertEquals(29.2, v.last(), 1e-6)
    }

    @Test fun backToGridBetweenCutoffAndBackToBatteryInHalfVolts() {
        val v = values("recharge")
        assertEquals(22.5, v.first(), 1e-6); assertEquals(25.5, v.last(), 1e-6)
        assertTrue(v.zipWithNext().all { (a, b) -> kotlin.math.abs(b - a - 0.5) < 1e-6 })
    }

    @Test fun backToBatteryOffersFullAndAboveBackToGrid() {
        val v = values("redischarge")
        assertEquals(0.0, v.first(), 1e-6); assertEquals(24.5, v[1], 1e-6); assertEquals(29.0, v.last(), 1e-6)
    }

    @Test fun gridChargeCurrentSkipsThreeDigitValues() {
        assertEquals(listOf(2.0, 10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0), values("maxAc"))
        assertEquals(12, values("maxChg").size)
    }

    @Test fun currentValuesAndLabels() {
        assertEquals(1.0, InvSet.current(InvSet.all.first { it.letter == 'a' }, r, "EakxyzDbdjuv")!!, 0.0)
        assertEquals(0.0, InvSet.current(InvSet.all.first { it.letter == 'u' }, r, "EakxyzDbdjuv")!!, 0.0)
        assertEquals("Solar first (SUB)", InvSet.label(InvSet.def("outPrio"), 1.0, r, chg, ac))
        assertEquals("26.5 V", InvSet.label(InvSet.def("redischarge"), 26.5, r, chg, ac))
        assertNull(InvSet.choices(InvSet.def("bulk"), r.copy(battV = 36.0), chg, ac))
    }

    private val beqi = "0 060 030 050 030 29.20 000 120 0 0000"   // real QBEQI from the Inverex Veyron

    @Test fun equalizationReadFromQbeqi() {
        assertEquals(60.0, InvSet.current(InvSet.def("eqTime"), r, "", beqi)!!, 1e-6)
        assertEquals(29.2, InvSet.current(InvSet.def("eqVolt"), r, "", beqi)!!, 1e-6)
        assertEquals(120.0, InvSet.current(InvSet.def("eqTimeout"), r, "", beqi)!!, 1e-6)
        assertNull(InvSet.current(InvSet.def("eqTime"), r, "", ""))
    }

    @Test fun newRangesMatchFirmware() {
        val t = values("eqTime"); assertEquals(5.0, t.first(), 1e-6); assertEquals(900.0, t.last(), 1e-6); assertEquals(180, t.size)
        val v = values("eqVolt"); assertEquals(24.0, v.first(), 1e-6); assertEquals(30.5, v.last(), 1e-6)
        assertEquals(listOf(0.0, 1.0, 2.0, 3.0), values("battType"))
        assertEquals(listOf(220.0, 230.0, 240.0), values("outV"))
        assertEquals(230.0, InvSet.current(InvSet.def("outV"), r, "")!!, 1e-6)
        assertEquals("30 days", InvSet.label(InvSet.def("eqPeriod"), 30.0, r, chg, ac))
        assertEquals(0.0, InvSet.current(InvSet.all.first { it.letter == 'd' }, r, "EakxyzDbdjuv")!!, 1e-6)
    }
}
