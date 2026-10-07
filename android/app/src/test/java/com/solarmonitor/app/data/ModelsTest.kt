package com.solarmonitor.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ModelsTest {
    // Captured from the device's /api/live
    private val liveJson = """{"seq":221,"t":1791355726237,"ok":true,"ever":true,"age":0,"mode":"B","pvW":701,"pvV":398.2,"pvA":1.7,
        "pvChgW":701,"battV":27.50,"battVscc":0.00,"battPct":100,"chgA":0.0,"dischgA":2.0,"battW":-55,"loadW":729,"loadVA":756,
        "loadPct":23,"outV":229.2,"outHz":50.0,"gridOn":false,"gridV":0.0,"gridHz":0.0,"gridW":0,"tempC":42,"busV":437,
        "st":"00010000","st2":"011","warn":"000001000000000000000000000000000000","today":{"date":20261007,"pv":209.1,
        "load":226.0,"grid":7.1,"chg":1.4,"dis":24.9,"gridOnMin":3,"onlineMin":18,"outages":1,"pvPeak":837,"loadPeak":950},
        "poll":{"ms":609,"ok":221,"fail":0,"crc":0,"err":""},"timeOk":true}"""

    @Test fun parsesLiveReading() {
        val d = Live.parse(liveJson)
        assertEquals(221L, d.seq)
        assertEquals('B', d.mode)
        assertEquals(701, d.pvW)
        assertEquals(-55, d.battW)
        assertFalse(d.gridOn)
        assertEquals(20261007, d.today.date)
        assertEquals(1, d.today.outages)
        assertEquals(609, d.pollMs)
        assertFalse(d.solarCharging)
        assertFalse(d.gridCharging)
    }

    @Test fun missingFieldsFallBackToDefaults() {
        val d = Live.parse("""{"ok":false}""")
        assertFalse(d.ever)
        assertEquals('?', d.mode)
        assertEquals(0, d.today.date)
    }

    @Test fun parsesRatedSettings() {
        val r = Rated.parse("230.0 13.9 230.0 50.0 13.9 3200 3200 24.0 25.5 22.1 27.8 27.5 02 010 050 0 1 1 1 01 0 0 26.5 0 1")!!
        assertEquals(3200, r.outW)
        assertEquals(24.0, r.battV, 1e-9)
        assertEquals(22.1, r.cutoff, 1e-9)
        assertEquals(27.8, r.bulk, 1e-9)
        assertEquals(2, r.battType)
        assertEquals(50, r.maxChg)
        assertEquals(1, r.outPrio)
        assertEquals(26.5, r.redischarge!!, 1e-9)
        assertNull(Rated.parse("230.0 13.9"))
        assertNull(Rated.parse(""))
    }

    @Test fun decodesWarningsAndFlags() {
        // bit 5 (grid lost) is shown separately, bit 9 = over temperature
        assertEquals(listOf(9), Decode.activeWarnings("000001000100000000000000000000000000"))
        assertEquals(emptyList<Int>(), Decode.activeWarnings(""))
        assertEquals("Buzzer, LCD backlight, Beep on grid loss, Fault code record", Decode.enabledFlags("EakxyzDbdjuv")?.replace("LCD back to home screen, ", ""))
        assertNull(Decode.enabledFlags("garbage"))
        assertEquals("Battery / Solar", Decode.modeName('B'))
        assertEquals("Unknown", Decode.modeName('Z'))
    }

    private fun le(size: Int, fill: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array()

    @Test fun parsesMinuteRecords() {
        val b = le(48) {
            putInt(1791355680); putShort(700); putShort(729); putShort(0); putShort((-55).toShort())
            putShort(2750); putShort(3982); putShort(0); putShort(2292); put(100); put(42); put('B'.code.toByte()); put(8)
            putInt(1791355740); putShort(0); putShort(500); putShort(500); putShort(0)
            putShort(2700); putShort(0); putShort(2300); putShort(2300); put(99); put(-3); put('L'.code.toByte()); put(1)
        }
        val m = Binary.minutes(b)
        assertEquals(2, m.size)
        assertEquals(1791355680000L, m[0].t)
        assertEquals(-55, m[0].battW)
        assertEquals(27.5, m[0].battV, 1e-9)
        assertEquals(398.2, m[0].pvV, 1e-9)
        assertFalse(m[0].gridOn)
        assertTrue(m[1].gridOn)
        assertEquals(-3, m[1].tempC)
        assertEquals('L', m[1].mode)
        // a truncated trailing record is ignored
        assertEquals(1, Binary.minutes(b.copyOf(30)).size)
    }

    @Test fun parsesDaysAndSamples() {
        val d = le(40) {
            putInt(20261007); putFloat(1200f); putFloat(1500f); putFloat(300f); putFloat(100f); putFloat(400f)
            putShort(1100); putShort(950); putShort(30); putShort(600); put(80); put(100); put(45); put(2); putInt(0)
        }
        val day = Binary.days(d).single()
        assertEquals(20261007, day.date)
        assertEquals(1500f, day.loadWh)
        assertEquals(2, day.outages)
        assertEquals(600, day.onlineMin)

        val s = le(32) {
            putInt(0); putShort(0); putShort(1); putShort(2); putShort(3); putShort(4); put(5); put(6)   // clock not set: skipped
            putInt(1791355680); putShort(250); putShort(700); putShort(729); putShort(0); putShort((-30).toShort()); put(100); put(8)
        }
        val one = Binary.samples(s).single()
        assertEquals(1791355680250L, one.t)
        assertEquals(-30, one.battW)
    }
}
