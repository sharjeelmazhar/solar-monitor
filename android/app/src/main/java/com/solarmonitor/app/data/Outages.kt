package com.solarmonitor.app.data

import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Same rules as web/src/lib/outages.ts.

data class Outage(
    val start: Long,          // epoch ms of the first minute without grid
    val end: Long,            // when grid was seen again (or the last data point if unknown)
    val minutes: Int,
    val ongoing: Boolean,     // still off at the latest data point
    val startKnown: Boolean,  // false when the data begins while the grid was already off
    val endKnown: Boolean,    // false when the monitor went offline before the grid came back
)

data class HourMix(
    val hour: Int, val minutes: Int, val offMin: Int,
    val solarWh: Double, val battWh: Double, val gridWh: Double, val pvWh: Double,
) {
    val homeWh get() = solarWh + battWh + gridWh
}

data class DuringOutage(val homeWh: Double, val solarWh: Double, val socFrom: Int, val socTo: Int)

object Outages {
    private const val MIN = 60_000L

    /**
     * Per-minute grid flags to outage events (records may span days). A data gap over [maxGapMin] closes the
     * event with endKnown = false; grid back for at most [flickerMin] minutes inside an outage is merged.
     */
    fun find(recs: List<MinRec>, maxGapMin: Int = 3, flickerMin: Int = 1): List<Outage> {
        val maxGap = maxGapMin * MIN
        val flicker = flickerMin * MIN
        val out = ArrayList<MutableOutage>()
        var cur: MutableOutage? = null
        var prevT = Long.MIN_VALUE
        for (r in recs.sortedBy { it.t }) {
            val gap = prevT == Long.MIN_VALUE || r.t - prevT > maxGap
            cur?.let { c ->
                if (gap) { c.endKnown = false; c.end = prevT + MIN; out += c; cur = null }
            }
            if (!r.gridOn) {
                if (cur == null) {
                    val last = out.lastOrNull()
                    cur = if (last != null && last.endKnown && !gap && r.t - last.end <= flicker) out.removeAt(out.size - 1)
                    else MutableOutage(r.t, r.t + MIN, startKnown = !gap)
                }
                cur!!.end = r.t + MIN
            } else cur?.let { c -> c.end = r.t; out += c; cur = null }
            prevT = r.t
        }
        cur?.let { it.ongoing = true; it.endKnown = false; out += it }
        return out.map { Outage(it.start, it.end, ((it.end - it.start) / MIN.toDouble()).roundToInt(), it.ongoing, it.startKnown, it.endKnown) }
    }

    private class MutableOutage(val start: Long, var end: Long, val startKnown: Boolean, var endKnown: Boolean = true, var ongoing: Boolean = false)

    /** What powered the home each hour: grid share first, then battery discharge, solar covers the rest. */
    fun hourly(recs: List<MinRec>, zone: ZoneId = ZoneId.systemDefault()): List<HourMix> {
        val m = IntArray(24); val off = IntArray(24)
        val s = DoubleArray(24); val b = DoubleArray(24); val g = DoubleArray(24); val pv = DoubleArray(24)
        for (r in recs) {
            val h = Instant.ofEpochMilli(r.t).atZone(zone).hour
            m[h]++
            if (!r.gridOn) off[h]++
            val gw = min(r.loadW, max(0, r.gridW))
            val bw = min(r.loadW - gw, max(0, -r.battW))
            g[h] += gw / 60.0; b[h] += bw / 60.0; s[h] += max(0, r.loadW - gw - bw) / 60.0; pv[h] += r.pvW / 60.0
        }
        return List(24) { HourMix(it, m[it], off[it], s[it], b[it], g[it], pv[it]) }
    }

    fun during(recs: List<MinRec>, o: Outage): DuringOutage? {
        val rs = recs.filter { it.t >= o.start && it.t < o.end }
        if (rs.isEmpty()) return null
        return DuringOutage(rs.sumOf { it.loadW } / 60.0, rs.sumOf { it.pvW } / 60.0, rs.first().battPct, rs.last().battPct)
    }
}
