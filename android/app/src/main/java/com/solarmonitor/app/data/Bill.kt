package com.solarmonitor.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// IESCO residential (tariff A-1, under 5 kW) bill estimate. Same rules and results as web/src/lib/bill.ts
// (both are checked against shared/bill-vectors.json). Rates: NEPRA S.R.O. 279(I)/2026, effective 12 Feb 2026.
//  - Protected = every one of the last 6 months at or under 200 units. Going over once bills that month as
//    unprotected and loses the status for 6 months.
//  - Protected homes get one previous slab at its own rate (150 units = 100 x first rate + 50 x second).
//  - Unprotected homes pay the rate of the slab they reach on every unit.
//  - Fixed charges are Rs per kW of sanctioned load per month, by the slab reached.
// Checked against real IESCO bills (Feb and Sep 2026): F.C. surcharge and QTA are per unit of this month, FPA is per
// unit of the month two bills back, duty = % of (energy + QTA + FPA) without the fixed charge, GST = % of everything.

/** upTo = 0 means no upper limit. */
data class Slab(val upTo: Int, val rate: Double, val fixedPerKw: Double)

/** A real bill entered by the user. month = YYYYMM. */
data class PastBill(val month: Int, val units: Int, val amount: Int)

data class BillConfig(
    val protected: Boolean = true,
    val kw: Double = 1.0,
    val day: Int = 1,                 // meter reading day; the billing month starts on this day
    val ps: List<Slab> = DEFAULT_PS,
    val us: List<Slab> = DEFAULT_US,
    val fc: Double = 0.43,            // financing cost surcharge Rs/unit
    val fpa: Double = 0.0,            // fuel price adjustment Rs/unit
    val qta: Double = 0.0,            // quarterly tariff adjustment Rs/unit
    val gst: Double = 18.0,           // %
    val ed: Double = 1.5,             // electricity duty %
    val ptv: Double = 0.0,            // Rs/month (not on IESCO bills in 2026)
    val extra: Double = 0.0,          // grid units/month the monitor can't see
    val hist: List<PastBill> = emptyList(),   // oldest first
    val hr: Int = 20,                 // hour of the meter reading: the new month starts then on the reading day
) {
    val limit get() = ps.last().upTo

    fun toJson(): String = JSONObject().apply {
        put("st", if (protected) "p" else "u"); put("kw", kw); put("day", day); put("hr", hr)
        put("ps", slabsJson(ps)); put("us", slabsJson(us))
        put("fc", fc); put("fpa", fpa); put("qta", qta); put("gst", gst); put("ed", ed); put("ptv", ptv); put("extra", extra)
        put("hist", JSONArray().apply { hist.forEach { put(JSONArray().put(it.month).put(it.units).put(it.amount)) } })
    }.toString()

    companion object {
        val DEFAULT_PS = listOf(Slab(100, 10.54, 200.0), Slab(200, 13.01, 300.0))
        val DEFAULT_US = listOf(
            Slab(100, 22.44, 275.0), Slab(200, 28.91, 300.0), Slab(300, 33.10, 350.0), Slab(400, 36.46, 400.0),
            Slab(500, 38.95, 500.0), Slab(600, 40.22, 675.0), Slab(700, 41.85, 675.0), Slab(0, 47.20, 675.0),
        )

        private fun slabsJson(l: List<Slab>) = JSONArray().apply { l.forEach { put(JSONArray().put(it.upTo).put(it.rate).put(it.fixedPerKw)) } }

        private fun nums(a: JSONArray?, n: Int): DoubleArray? {
            if (a == null || a.length() != n) return null
            val v = DoubleArray(n)
            for (i in 0 until n) {
                val x = a.opt(i)
                if (x !is Number || !x.toDouble().isFinite() || x.toDouble() < 0) return null
                v[i] = x.toDouble()
            }
            return v
        }

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
                return List(a.length()) { i -> nums(a.optJSONArray(i), 3)?.let { Slab(it[0].toInt(), it[1], it[2]) } ?: return def }
            }
            val hist = j.optJSONArray("hist")?.let { a ->
                (0 until a.length()).mapNotNull { i -> nums(a.optJSONArray(i), 3)?.let { PastBill(it[0].toInt(), it[1].toInt(), it[2].toInt()) } }
                    .filter { it.month in 200001..210012 && it.month % 100 in 1..12 }
                    .sortedBy { it.month }.reversed().distinctBy { it.month }.reversed()   // later entry wins, oldest first
                    .takeLast(24)
            } ?: emptyList()
            return BillConfig(
                protected = j.optString("st") != "u",
                kw = num("kw", d.kw, 0.0, 100.0), day = num("day", d.day.toDouble(), 1.0, 28.0).roundToInt(),
                ps = slabs("ps", d.ps), us = slabs("us", d.us),
                fc = num("fc", d.fc, 0.0, 100.0), fpa = num("fpa", d.fpa, -100.0, 100.0), qta = num("qta", d.qta, -100.0, 100.0),
                gst = num("gst", d.gst, 0.0, 100.0), ed = num("ed", d.ed, 0.0, 100.0),
                ptv = num("ptv", d.ptv, 0.0, 10000.0), extra = num("extra", d.extra, 0.0, 100000.0), hist = hist,
                hr = num("hr", d.hr.toDouble(), 0.0, 23.0).roundToInt(),
            )
        }
    }
}

data class Bill(
    val units: Int, val protectedTier: Boolean, val lostProtection: Boolean, val slab: Int, val rate: Double,
    val energy: Double, val fixed: Double, val fcs: Double, val qta: Double, val fpa: Double, val fpaUnits: Int,
    val duty: Double, val gst: Double, val ptv: Double, val total: Double,
)

/** The most important note about this month's units (same steps as the web app). key = step, one notification each. */
data class UnitAlert(val key: Int, val level: Int, val title: String, val text: String)

object BillCalc {
    private fun slabOf(l: List<Slab>, u: Int): Int = l.indexOfFirst { it.upTo == 0 || u <= it.upTo }.let { if (it < 0) l.size - 1 else it }
    private fun r2(x: Double) = (x * 100).roundToLong() / 100.0
    private fun round(x: Double) = floor(x + 0.5).toInt()   // like JS Math.round

    /** fpaUnits: units of the month two bills back (FPA is charged on those); defaults to this month's units. */
    fun compute(unitsIn: Double, c: BillConfig, fpaUnits: Double? = null): Bill {
        val u = round(max(0.0, unitsIn))
        val fu = round(max(0.0, fpaUnits ?: u.toDouble()))
        val prot = c.protected && u <= c.limit
        val list = if (prot) c.ps else c.us
        val i = slabOf(list, u)
        val s = list[i]
        val energy = if (prot && i > 0) list[i - 1].upTo * list[i - 1].rate + (u - list[i - 1].upTo) * s.rate else u * s.rate
        val fixed = s.fixedPerKw * c.kw
        val fcs = u * c.fc
        val qta = u * c.qta
        val fpa = fu * c.fpa
        val duty = max(0.0, energy + qta + fpa) * c.ed / 100   // not on the fixed charge
        val gst = max(0.0, energy + fixed + fcs + qta + fpa + duty) * c.gst / 100
        val total = energy + fixed + fcs + qta + fpa + duty + gst + c.ptv
        return Bill(u, prot, c.protected && !prot, i, s.rate, r2(energy), r2(fixed), r2(fcs), r2(qta), r2(fpa), fu, r2(duty), r2(gst), c.ptv, r2(total))
    }

    /** Units left before the next price step, or null above the last slab. */
    fun unitsToNextStep(units: Double, c: BillConfig): Pair<Int, Int>? {
        val u = round(max(0.0, units))
        val list = if (c.protected && u <= c.limit) c.ps else c.us
        val s = list[slabOf(list, u)]
        return if (s.upTo == 0) null else (s.upTo - u) to s.upTo
    }

    fun unitAlert(soFar: Double, projected: Double, c: BillConfig): UnitAlert? {
        val u = floor(soFar).toInt()
        val p = round(projected)
        if (c.protected) {
            val l = c.limit
            val left = l - u
            return when {
                u > l -> UnitAlert(l, 2, "Over $l units this month", "$u units used. This month will be billed at the unprotected rate and protected status is lost for the next 6 months.")
                left <= 10 -> UnitAlert(l - 10, 2, "$u units used: only $left left before $l",
                    "If you use more than $l units this month, the whole month is billed at the unprotected rate and you lose protected status for 6 months. Please keep grid use to a minimum until the meter reading.")
                u >= l - 25 -> UnitAlert(l - 25, 1, "High use: $u units this month", "$left units left before the protected limit of $l. Expected by month end: about $p.")
                p > l -> UnitAlert(-1, 1, "Heading over $l units", "At this pace the month ends near $p units. To stay protected, use less grid power for the rest of the month.")
                u >= l - 50 -> UnitAlert(l - 50, 0, "$u units used this month", "$left units left before $l. Expected by month end: about $p.")
                else -> null
            }
        }
        val next = unitsToNextStep(u.toDouble(), c) ?: return null
        return if (next.first <= 10) UnitAlert(next.second - 10, 1, "${next.first} units before the next slab", "Above ${next.second} units every unit this month is charged at the higher rate.") else null
    }

    // ---- billing months (dates as YYYYMMDD, months as YYYYMM) ----
    private fun date(n: Int) = LocalDate.of(n / 10000, n / 100 % 100, n % 100)
    private fun num(d: LocalDate) = d.year * 10000 + d.monthValue * 100 + d.dayOfMonth

    fun cycleStart(today: Int, day: Int): Int {
        val d = date(today)
        val s = d.withDayOfMonth(day)
        return num(if (d.dayOfMonth < day) s.minusMonths(1) else s)
    }
    fun nextCycle(start: Int) = num(date(start).plusMonths(1))
    /** The date whose billing month time t (epoch ms) falls in: on the reading day, only from the reading hour on. */
    fun billDate(t: Long, hr: Int, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()) =
        num(java.time.Instant.ofEpochMilli(t - hr * 3_600_000L).atZone(zone).toLocalDate())
    /** Epoch ms of the meter reading that starts the month beginning on date [start]. */
    fun readingMs(start: Int, hr: Int, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()) =
        date(start).atTime(hr, 0).atZone(zone).toInstant().toEpochMilli()
    fun prevCycle(start: Int) = num(date(start).minusMonths(1))
    fun addDays(n: Int, k: Long) = num(date(n).plusDays(k))
    fun daysBetween(a: Int, b: Int) = (date(b).toEpochDay() - date(a).toEpochDay()).toInt()
    /** The bill month a billing period belongs to: the month of the reading that closes it. */
    fun billMonth(start: Int) = nextCycle(start) / 100
    fun addMonths(ym: Int, k: Int): Int { val t = ym / 100 * 12 + ym % 100 - 1 + k; return t / 12 * 100 + t % 12 + 1 }

    data class MonthUse(
        val start: Int, val end: Int, val totalDays: Int, val elapsed: Int, val covered: Int,
        val gridUnits: Double, val selfUnits: Double, val soFar: Double, val projected: Double, val perDay: Double,
        val dayFrac: Double = 1.0,
    )

    /** This billing month's grid units so far and projected to the end, from daily totals. [dayFrac] = share of today gone. */
    fun monthUse(byDate: Map<Int, DayRec>, today: Int, c: BillConfig, dayFrac: Double): MonthUse {
        val start = cycleStart(today, c.day)
        val end = addDays(nextCycle(start), -1)
        val total = daysBetween(start, end) + 1
        val elapsed = daysBetween(start, today) + 1
        var grid = 0.0; var self = 0.0; var covered = 0
        var k = start
        while (k <= today) {
            byDate[k]?.let { r -> covered++; grid += r.gridWh / 1000.0; self += max(0f, r.loadWh - r.gridWh) / 1000.0 }
            k = addDays(k, 1)
        }
        val frac = max(0.25, dayFrac)
        val coveredDays = max(frac, covered - 1 + frac)
        val perDay = if (covered > 0) grid / coveredDays else 0.0
        val extraPerDay = c.extra / total
        return MonthUse(start, end, total, elapsed, covered, grid, self,
            soFar = grid + extraPerDay * (elapsed - 1 + frac), projected = perDay * total + c.extra, perDay = perDay + extraPerDay, dayFrac = frac)
    }

    /** Units of a past bill month: the bill the user entered, else the monitor's data if it covered the whole month. */
    fun unitsOf(ym: Int, byDate: Map<Int, DayRec>, c: BillConfig): Pair<Double, Boolean>? {
        c.hist.firstOrNull { it.month == ym }?.let { return it.units.toDouble() to true }
        val start = prevCycle(ym * 100 + c.day)
        val end = addDays(nextCycle(start), -1)
        var units = 0.0; var days = 0; var k = start
        while (k <= end) { byDate[k]?.let { units += it.gridWh / 1000.0; days++ }; k = addDays(k, 1) }
        return if (days >= daysBetween(start, end) - 1) (units + c.extra) to false else null
    }

    data class Past(val month: Int, val units: Double?, val fromBill: Boolean)

    /** The 6 bills before this one, newest first; verdict true protected, false unprotected, null unknown. */
    fun history(byDate: Map<Int, DayRec>, today: Int, c: BillConfig): Pair<List<Past>, Boolean?> {
        val cur = billMonth(cycleStart(today, c.day))
        val out = (1..6).map { k -> val ym = addMonths(cur, -k); unitsOf(ym, byDate, c).let { Past(ym, it?.first, it?.second == true) } }
        val verdict = when { out.any { (it.units ?: 0.0) > c.limit } -> false; out.all { it.units != null } -> true; else -> null }
        return out to verdict
    }

    /** basis: "measured" = the monitor has every day since the meter reading, so "so far" is the real count;
     *  "monitor" = a week or more of data with gaps; "bills" = too little data, the last 3 bills fill in. */
    data class Now(val m: MonthUse, val ym: Int, val bill: Bill, val saved: Double, val alert: UnitAlert?, val basis: String, val recentAvg: Int,
                   val counter: Boolean = false, val missingDays: Double = 0.0, val countFrom: Long = 0)

    /** This month from the monitor's own counter (same as monthFromCounter in the web app). Null if it belongs to another month. */
    fun fromCounter(cyc: Cyc, nowMs: Long, c: BillConfig, avg: Double?, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Pair<MonthUse, Double>? {
        val start = cycleStart(billDate(nowMs, c.hr, zone), c.day)
        val t0 = readingMs(start, c.hr, zone)
        if (kotlin.math.abs(cyc.s * 1000 - t0) > 3_600_000L) return null
        val t1 = readingMs(nextCycle(start), c.hr, zone)
        val total = ((t1 - t0) / 86_400_000.0).roundToInt()
        val gone = ((nowMs - t0) / 86_400_000.0).coerceIn(0.0, total.toDouble())
        val grid = cyc.gridWh / 1000
        val seen = min(gone, cyc.minutes / 1440.0)
        val missing = max(0.0, gone - seen)
        val extraPerDay = c.extra / total
        val measured = if (seen >= 0.25) grid / seen else null
        val fromBills = avg?.let { max(0.0, it - c.extra) / total }
        val w = min(1.0, seen / 7)
        val rate = when { measured == null -> fromBills ?: 0.0; fromBills == null -> measured; else -> w * measured + (1 - w) * fromBills }
        val soFar = grid + missing * rate + extraPerDay * gone
        return MonthUse(start, addDays(nextCycle(start), -1), total, max(1, kotlin.math.ceil(gone).toInt()), seen.roundToInt(),
            grid, max(0.0, cyc.loadWh - cyc.gridWh) / 1000, soFar, soFar + (rate + extraPerDay) * (total - gone), rate + extraPerDay, gone % 1) to missing
    }

    fun now(byDate: Map<Int, DayRec>, today: Int, c: BillConfig, dayFrac: Double, nowMs: Long = 0, cyc: Cyc? = null): Now {
        // the monitor's own counter from the meter reading (day + hour) is the most exact source
        if (cyc != null && nowMs > 0) {
            val ym0 = billMonth(cycleStart(billDate(nowMs, c.hr), c.day))
            val r0 = (1..3).mapNotNull { k -> c.hist.firstOrNull { it.month == addMonths(ym0, -k) }?.units }
            val a0 = if (r0.isEmpty()) null else r0.average()
            fromCounter(cyc, nowMs, c, a0)?.let { (cm, missing) ->
                val fpa = unitsOf(addMonths(ym0, -2), byDate, c)?.first
                val bill = compute(cm.projected, c, fpa)
                val without = compute(cm.projected + (if (cm.covered > 0) cm.selfUnits / cm.covered else 0.0) * cm.totalDays, c, fpa)
                return Now(cm, ym0, bill, if (cm.covered >= 7) without.total - bill.total else 0.0, unitAlert(cm.soFar, cm.projected, c),
                    if (missing <= 0.25) "measured" else "monitor", (a0 ?: 0.0).roundToInt(), true, missing,
                    if (cyc.f > cyc.s + 1800) cyc.f * 1000 else 0)
            }
        }
        var m = monthUse(byDate, today, c, dayFrac)
        val ym = billMonth(m.start)
        // with under a week of monitor data a projection is guesswork: use the last 3 bills instead
        val recent = (1..3).mapNotNull { k -> c.hist.firstOrNull { it.month == addMonths(ym, -k) }?.units }
        val avg = if (recent.isEmpty()) 0.0 else recent.average()
        val measured = m.covered > 0 && m.covered == m.elapsed
        val basis = when { measured -> "measured"; m.covered < 7 && recent.isNotEmpty() -> "bills"; else -> "monitor" }
        if (basis == "bills") {
            m = m.copy(projected = max(m.soFar, avg), soFar = max(m.soFar, avg * m.elapsed / m.totalDays), perDay = avg / m.totalDays)
        } else if (measured && recent.isNotEmpty() && m.covered < 7) {
            // first days of the month: the real count so far, plus the rest of the month at a rate that moves
            // from the past bills towards what the monitor measures as the week goes on
            val w = min(1.0, m.covered / 7.0)
            val extraPerDay = c.extra / m.totalDays
            val rate = max(0.0, w * (m.perDay - extraPerDay) + (1 - w) * ((avg - c.extra) / m.totalDays))
            val left = m.totalDays - m.elapsed + 1 - m.dayFrac
            m = m.copy(projected = max(m.soFar, m.gridUnits + rate * left + c.extra), perDay = rate + extraPerDay)
        }
        val fpaUnits = unitsOf(addMonths(ym, -2), byDate, c)?.first
        val bill = compute(m.projected, c, fpaUnits)
        val without = compute(m.projected + m.selfUnits / max(1, m.covered) * m.totalDays, c, fpaUnits)
        // both need real measurements: a week of monitor data, not an estimate from past bills
        return Now(m, ym, bill, if (m.covered >= 7) without.total - bill.total else 0.0,
            if (basis != "bills" && (measured || m.covered >= 3)) unitAlert(m.soFar, m.projected, c) else null, basis, avg.roundToInt())
    }

    // ---- insights from the bills the user entered (same wording as web/src/lib/bill.ts) ----
    data class Insight(val tone: String, val text: String)   // good / warn / crit / info

    private val MON = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private fun mName(ym: Int) = "${MON[ym % 100 - 1]} ${(ym / 100).toString().takeLast(2)}"
    private fun rsFmt(v: Int) = String.format(java.util.Locale.US, "%,d", v)

    fun insights(hist: List<PastBill>, limit: Int = 200): List<Insight> {
        if (hist.size < 2) return emptyList()
        val out = ArrayList<Insight>()
        val last12 = hist.takeLast(12)
        val avgU = last12.map { it.units }.average()
        val paid = last12.filter { it.amount > 0 }
        val avgRs = if (paid.isEmpty()) 0.0 else paid.map { it.amount }.average()
        out += Insight("info", "Last ${last12.size} bills: about ${avgU.roundToInt()} units and Rs ${rsFmt(avgRs.roundToInt())} a month on average.")
        val over = last12.filter { it.units > limit }
        val close = last12.filter { it.units >= limit - 25 && it.units <= limit }
        out += if (over.isNotEmpty()) Insight("crit", "${over.size} month${if (over.size > 1) "s" else ""} went over $limit units (${over.joinToString { mName(it.month) }}).")
        else Insight("good", "Every month stayed at or under $limit units, so you keep the protected rates.")
        if (close.isNotEmpty()) out += Insight("warn", "${close.size} close call${if (close.size > 1) "s" else ""} at ${limit - 25}+ units: ${close.joinToString { "${mName(it.month)} (${it.units})" }}. Summer months need the most care.")
        val top = last12.maxBy { it.units }
        out += Insight("info", "Highest: ${mName(top.month)} with ${top.units} units${if (top.amount > 0) " (Rs ${rsFmt(top.amount)})" else ""}.")
        val latest = hist.last()
        hist.firstOrNull { it.month == addMonths(latest.month, -12) }?.let { ya ->
            val du = latest.units - ya.units
            val dr = if (latest.amount > 0 && ya.amount > 0) latest.amount - ya.amount else 0
            val sign = if (du <= 0) "" else "+"
            out += Insight(if (du <= 0) "good" else "warn", "${mName(latest.month)}: ${latest.units} units vs ${ya.units} a year ago ($sign$du" +
                (if (ya.units > 0) ", $sign${(du * 100.0 / ya.units).roundToInt()}%" else "") + ")" +
                (if (dr != 0) ", but the bill was " + (if (dr > 0) "Rs ${rsFmt(dr)} higher" else "Rs ${rsFmt(-dr)} lower") else "") + ".")
        }
        fun rate(bs: List<PastBill>): Double { val p = bs.filter { it.units > 0 && it.amount > 0 }; return if (p.isEmpty()) 0.0 else p.sumOf { it.amount }.toDouble() / p.sumOf { it.units } }
        val rNow = rate(hist.takeLast(3))
        val rThen = rate(hist.filter { it.month <= addMonths(latest.month, -10) && it.month >= addMonths(latest.month, -14) })
        if (rNow > 0 && rThen > 0) out += Insight(if (rNow > rThen * 1.1) "warn" else "info",
            "Each unit now costs about Rs ${String.format(java.util.Locale.US, "%.1f", rNow)} all-in, against Rs ${String.format(java.util.Locale.US, "%.1f", rThen)} a year ago" +
                (if (rNow > rThen * 1.1) " (fixed charges since Feb 2026 and fuel adjustments)" else "") + ".")
        return out
    }
}
