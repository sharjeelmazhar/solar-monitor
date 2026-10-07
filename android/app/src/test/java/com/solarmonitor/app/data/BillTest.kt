package com.solarmonitor.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

class BillTest {
    /** The same cases the web tests use, so both apps give the same bill. */
    @Test fun sharedVectors() {
        val f = listOf("../../shared/bill-vectors.json", "../shared/bill-vectors.json", "shared/bill-vectors.json").map(::File).first { it.exists() }
        val cases = JSONObject(f.readText()).getJSONArray("cases")
        val base = JSONObject(BillConfig().toJson())
        for (i in 0 until cases.length()) {
            val v = cases.getJSONObject(i)
            val cfg = JSONObject(base.toString())
            v.getJSONObject("cfg").let { o -> o.keys().forEach { cfg.put(it, o.get(it)) } }
            val b = BillCalc.compute(v.getDouble("units"), BillConfig.parse(cfg.toString()))
            val name = v.getString("name")
            assertEquals(name, v.getString("tier") == "protected", b.protectedTier)
            assertEquals(name, v.optBoolean("lost", false), b.lostProtection)
            assertEquals(name, v.getDouble("energy"), b.energy, 0.05)
            assertEquals(name, v.getDouble("fixed"), b.fixed, 0.05)
            assertEquals(name, v.getDouble("total"), b.total, 0.02)
        }
        assertTrue(cases.length() >= 10)
    }

    @Test fun parseFallsBackToDefaults() {
        assertEquals(BillConfig(), BillConfig.parse(null))
        assertEquals(BillConfig(), BillConfig.parse("not json"))
        assertEquals(BillConfig.DEFAULT_PS, BillConfig.parse("""{"ps":[[1,2]]}""").ps)
        assertEquals(100.0, BillConfig.parse("""{"gst":500}""").gst, 0.0)
        val c = BillConfig(protected = false, kw = 2.5, fpa = -1.2)
        assertEquals(c, BillConfig.parse(c.toJson()))
    }

    @Test fun nextStepAndCycles() {
        assertEquals(50 to 200, BillCalc.unitsToNextStep(150.0, BillConfig()))
        assertEquals(50 to 300, BillCalc.unitsToNextStep(250.0, BillConfig(protected = false)))
        assertNull(BillCalc.unitsToNextStep(900.0, BillConfig(protected = false)))
        assertEquals(20261001, BillCalc.cycleStart(20261007, 1))
        assertEquals(20260915, BillCalc.cycleStart(20261007, 15))
        assertEquals(20251210, BillCalc.cycleStart(20260105, 10))
        assertEquals(20260110, BillCalc.nextCycle(20251210))
    }
}

class OutagesTest {
    private val zone = ZoneId.of("Asia/Karachi")
    private val base = LocalDateTime.of(2026, 10, 7, 14, 0).atZone(zone).toInstant().toEpochMilli()
    private fun rec(k: Int, grid: Boolean, load: Int = 600, gridW: Int = 0, batt: Int = 0, pv: Int = 0) =
        MinRec(base + k * 60_000L, pv, load, gridW, batt, 26.0, 0.0, 0.0, 230.0, 80, 30, 'B', if (grid) 1 else 0)

    @Test fun findsOutagesAndMergesFlicker() {
        val recs = (0..4).map { rec(it, true) } + (5..9).map { rec(it, false) } + listOf(rec(10, true)) + (11..14).map { rec(it, false) } + (15..20).map { rec(it, true) }
        val o = Outages.find(recs)
        assertEquals(1, o.size)               // the 1-minute return at k=10 is a flicker
        assertEquals(base + 5 * 60_000L, o[0].start)
        assertEquals(10, o[0].minutes)
        assertTrue(o[0].startKnown && o[0].endKnown && !o[0].ongoing)
    }

    @Test fun gapAndOngoing() {
        val o = Outages.find((0..3).map { rec(it, false) } + (20..22).map { rec(it, false) })
        assertEquals(2, o.size)
        assertTrue(!o[0].startKnown && !o[0].endKnown)
        assertTrue(o[1].ongoing)
    }

    @Test fun hourlyMix() {
        val h = Outages.hourly(listOf(
            rec(0, true, gridW = 600), rec(1, false, batt = -600), rec(2, true, pv = 900, batt = 300), rec(3, true, pv = 200, batt = -400),
        ), zone)[14]
        assertEquals(4, h.minutes); assertEquals(1, h.offMin)
        assertEquals(10.0, h.gridWh, 1e-6)
        assertEquals(10 + 400 / 60.0, h.battWh, 1e-6)
        assertEquals(10 + 200 / 60.0, h.solarWh, 1e-6)
    }
}
