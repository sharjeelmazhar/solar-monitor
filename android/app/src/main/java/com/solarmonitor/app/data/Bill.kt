package com.solarmonitor.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// IESCO residential (tariff A-1, under 5 kW) bill estimate. Same rules and results as web/src/lib/bill.ts
// (both are checked against shared/bill-vectors.json). Rates: NEPRA S.R.O. 279(I)/2026, effective 12 Feb 2026.
//  - Protected = every one of the last 6 months at or under 200 units. Going over once bills that month as
//    unprotected and loses the status for 6 months.
//  - Protected homes get one previous slab at its own rate (150 units = 100 x first rate + 50 x second).
//  - Unprotected homes pay the rate of the slab they reach on every unit.
//  - Fixed charges are Rs per kW of sanctioned load per month, by the slab reached.

/** upTo = 0 means no upper limit. */
data class Slab(val upTo: Int, val rate: Double, val fixedPerKw: Double)

data class BillConfig(
    val protected: Boolean = true,
    val kw: Double = 1.0,
    val day: Int = 1,                 // meter reading day; the billing month starts on this day
    val ps: List<Slab> = DEFAULT_PS,
    val us: List<Slab> = DEFAULT_US,
    val fpa: Double = 0.0,            // fuel price adjustment Rs/unit
    val qta: Double = 0.0,            // quarterly tariff adjustment Rs/unit
    val gst: Double = 18.0,           // %
    val ed: Double = 1.5,             // electricity duty, % of energy charges
    val ptv: Double = 35.0,           // Rs/month
    val extra: Double = 0.0,          // grid units/month the monitor can't see
) {
    val limit get() = ps.last().upTo

    fun toJson(): String = JSONObject().apply {
        put("st", if (protected) "p" else "u"); put("kw", kw); put("day", day)
        put("ps", slabsJson(ps)); put("us", slabsJson(us))
        put("fpa", fpa); put("qta", qta); put("gst", gst); put("ed", ed); put("ptv", ptv); put("extra", extra)
    }.toString()

    companion object {
        val DEFAULT_PS = listOf(Slab(100, 10.54, 200.0), Slab(200, 13.01, 300.0))
        val DEFAULT_US = listOf(
            Slab(100, 22.44, 275.0), Slab(200, 28.91, 300.0), Slab(300, 33.10, 350.0), Slab(400, 36.46, 400.0),
            Slab(500, 38.95, 500.0), Slab(600, 40.22, 675.0), Slab(700, 41.85, 675.0), Slab(0, 47.20, 675.0),
        )

        private fun slabsJson(l: List<Slab>) = JSONArray().apply { l.forEach { put(JSONArray().put(it.upTo).put(it.rate).put(it.fixedPerKw)) } }

        /** Anything missing or invalid falls back to the default, like the web app. */
        fun parse(json: String?): BillConfig {
            val d = BillConfig()
            val j = try { JSONObject(json ?: "{}") } catch (_: Exception) { JSONObject() }
            fun num(k: String, def: Double, lo: Double, hi: Double): Double {
                val v = j.opt(k)
                return if (v is Number && v.toDouble().isFinite()) v.toDouble().coerceIn(lo, hi) else def
            }
            fun slabs(k: String, def: List<Slab>): List<Slab> {
                val a = j.optJSONArray(k) ?: return def
                if (a.length() !in 1..12) return def
                val out = ArrayList<Slab>()
                for (i in 0 until a.length()) {
                    val s = a.optJSONArray(i) ?: return def
                    if (s.length() != 3) return def
                    val v = DoubleArray(3)
                    for (n in 0 until 3) {
                        val x = s.opt(n)
                        if (x !is Number || !x.toDouble().isFinite() || x.toDouble() < 0) return def
                        v[n] = x.toDouble()
                    }
                    out += Slab(v[0].toInt(), v[1], v[2])
                }
                return out
            }
            return BillConfig(
                protected = j.optString("st") != "u",
                kw = num("kw", d.kw, 0.0, 100.0), day = num("day", d.day.toDouble(), 1.0, 28.0).roundToInt(),
                ps = slabs("ps", d.ps), us = slabs("us", d.us),
                fpa = num("fpa", d.fpa, -100.0, 100.0), qta = num("qta", d.qta, -100.0, 100.0),
                gst = num("gst", d.gst, 0.0, 100.0), ed = num("ed", d.ed, 0.0, 100.0),
                ptv = num("ptv", d.ptv, 0.0, 10000.0), extra = num("extra", d.extra, 0.0, 100000.0),
            )
        }
    }
}

data class Bill(
    val units: Int, val protectedTier: Boolean, val lostProtection: Boolean, val slab: Int, val rate: Double,
    val energy: Double, val fixed: Double, val adjust: Double, val duty: Double, val gst: Double, val ptv: Double, val total: Double,
)

object BillCalc {
    private fun slabOf(l: List<Slab>, u: Int): Int = l.indexOfFirst { it.upTo == 0 || u <= it.upTo }.let { if (it < 0) l.size - 1 else it }
    private fun r2(x: Double) = (x * 100).roundToLong() / 100.0

    fun compute(unitsIn: Double, c: BillConfig): Bill {
        val u = max(0.0, unitsIn).roundToInt()   // the meter bills whole units (JS Math.round: halves round up, same here for >= 0)
        val prot = c.protected && u <= c.limit
        val list = if (prot) c.ps else c.us
        val i = slabOf(list, u)
        val s = list[i]
        val energy = if (prot && i > 0) list[i - 1].upTo * list[i - 1].rate + (u - list[i - 1].upTo) * s.rate else u * s.rate
        val fixed = s.fixedPerKw * c.kw
        val adjust = u * (c.fpa + c.qta)
        val duty = energy * c.ed / 100
        val gst = max(0.0, energy + fixed + adjust) * c.gst / 100
        val total = energy + fixed + adjust + duty + gst + c.ptv
        return Bill(u, prot, c.protected && !prot, i, s.rate, r2(energy), r2(fixed), r2(adjust), r2(duty), r2(gst), c.ptv, r2(total))
    }

    /** Units left before the next price step, or null above the last slab. */
    fun unitsToNextStep(units: Double, c: BillConfig): Pair<Int, Int>? {
        val u = max(0.0, units).roundToInt()
        val list = if (c.protected && u <= c.limit) c.ps else c.us
        val s = list[slabOf(list, u)]
        return if (s.upTo == 0) null else (s.upTo - u) to s.upTo
    }

    // ---- billing months (dates as YYYYMMDD) ----
    private fun date(n: Int) = LocalDate.of(n / 10000, n / 100 % 100, n % 100)
    private fun num(d: LocalDate) = d.year * 10000 + d.monthValue * 100 + d.dayOfMonth

    fun cycleStart(today: Int, day: Int): Int {
        val d = date(today)
        val s = d.withDayOfMonth(day)
        return num(if (d.dayOfMonth < day) s.minusMonths(1) else s)
    }
    fun nextCycle(start: Int) = num(date(start).plusMonths(1))
    fun prevCycle(start: Int) = num(date(start).minusMonths(1))
    fun addDays(n: Int, k: Long) = num(date(n).plusDays(k))
    fun daysBetween(a: Int, b: Int) = (date(b).toEpochDay() - date(a).toEpochDay()).toInt()

    data class MonthUse(
        val start: Int, val end: Int, val totalDays: Int, val elapsed: Int, val covered: Int,
        val gridUnits: Double, val selfUnits: Double, val solarUnits: Double, val soFar: Double, val projected: Double, val perDay: Double,
    )

    /** This billing month's grid units so far and projected to the end, from daily totals. [dayFrac] = share of today gone. */
    fun monthUse(byDate: Map<Int, DayRec>, today: Int, c: BillConfig, dayFrac: Double): MonthUse {
        val start = cycleStart(today, c.day)
        val end = addDays(nextCycle(start), -1)
        val total = daysBetween(start, end) + 1
        val elapsed = daysBetween(start, today) + 1
        var grid = 0.0; var self = 0.0; var solar = 0.0; var covered = 0
        var k = start
        while (k <= today) {
            byDate[k]?.let { r -> covered++; grid += r.gridWh / 1000.0; self += max(0f, r.loadWh - r.gridWh) / 1000.0; solar += r.pvWh / 1000.0 }
            k = addDays(k, 1)
        }
        val frac = max(0.25, dayFrac)
        val coveredDays = max(frac, covered - 1 + frac)
        val perDay = if (covered > 0) grid / coveredDays else 0.0
        val extraPerDay = c.extra / total
        return MonthUse(start, end, total, elapsed, covered, grid, self, solar,
            soFar = grid + extraPerDay * (elapsed - 1 + frac), projected = perDay * total + c.extra, perDay = perDay + extraPerDay)
    }

    data class Past(val start: Int, val units: Double, val full: Boolean)

    /** The 6 billing months before this one, oldest last. verdict: true protected, false unprotected, null unknown. */
    fun history(byDate: Map<Int, DayRec>, today: Int, c: BillConfig): Pair<List<Past>, Boolean?> {
        val out = ArrayList<Past>()
        var s = prevCycle(cycleStart(today, c.day))
        repeat(6) {
            val e = addDays(nextCycle(s), -1)
            var units = 0.0; var days = 0; var k = s
            while (k <= e) { byDate[k]?.let { units += it.gridWh / 1000.0; days++ }; k = addDays(k, 1) }
            out += Past(s, units + c.extra, days >= daysBetween(s, e) + 1 - 2)
            s = prevCycle(s)
        }
        val verdict = when { out.any { it.full && it.units > c.limit } -> false; out.all { it.full } -> true; else -> null }
        return out to verdict
    }
}

