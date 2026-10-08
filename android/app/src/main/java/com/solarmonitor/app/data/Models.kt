package com.solarmonitor.app.data

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One reading pushed by the monitor (/events, /api/live). */
data class Live(
    val seq: Long,
    val t: Long,                 // epoch ms, 0 if the device clock is not set
    val ok: Boolean,             // fresh data from the inverter
    val ever: Boolean,
    val mode: Char,
    val pvW: Int, val pvV: Double, val pvA: Double,
    val battV: Double, val battPct: Int, val chgA: Double, val dischgA: Double, val battW: Int,
    val loadW: Int, val loadVA: Int, val loadPct: Int, val outV: Double, val outHz: Double,
    val gridOn: Boolean, val gridV: Double, val gridHz: Double, val gridW: Int,
    val tempC: Int, val busV: Int,
    val st: String, val warn: String,
    val today: Today,
    val pollMs: Int, val okCount: Long, val failCount: Long, val crcErrors: Long, val err: String,
    val cyc: Cyc? = null,
) {
    val solarCharging get() = st.length == 8 && st[6] == '1'
    val gridCharging get() = st.length == 8 && st[7] == '1'

    companion object {
        fun parse(s: String): Live {
            val j = JSONObject(s)
            val td = j.optJSONObject("today") ?: JSONObject()
            val p = j.optJSONObject("poll") ?: JSONObject()
            return Live(
                seq = j.optLong("seq"), t = j.optLong("t"), ok = j.optBoolean("ok"), ever = j.optBoolean("ever"),
                mode = j.optString("mode", "?").firstOrNull() ?: '?',
                pvW = j.optInt("pvW"), pvV = j.optDouble("pvV", 0.0), pvA = j.optDouble("pvA", 0.0),
                battV = j.optDouble("battV", 0.0), battPct = j.optInt("battPct"), chgA = j.optDouble("chgA", 0.0),
                dischgA = j.optDouble("dischgA", 0.0), battW = j.optInt("battW"),
                loadW = j.optInt("loadW"), loadVA = j.optInt("loadVA"), loadPct = j.optInt("loadPct"),
                outV = j.optDouble("outV", 0.0), outHz = j.optDouble("outHz", 0.0),
                gridOn = j.optBoolean("gridOn"), gridV = j.optDouble("gridV", 0.0), gridHz = j.optDouble("gridHz", 0.0),
                gridW = j.optInt("gridW"), tempC = j.optInt("tempC"), busV = j.optInt("busV"),
                st = j.optString("st"), warn = j.optString("warn"),
                today = Today(
                    date = td.optInt("date"), pvWh = td.optDouble("pv", 0.0), loadWh = td.optDouble("load", 0.0),
                    gridWh = td.optDouble("grid", 0.0), chgWh = td.optDouble("chg", 0.0), disWh = td.optDouble("dis", 0.0),
                    gridOnMin = td.optInt("gridOnMin"), onlineMin = td.optInt("onlineMin"), outages = td.optInt("outages"),
                    pvPeak = td.optInt("pvPeak"), loadPeak = td.optInt("loadPeak"),
                ),
                pollMs = p.optInt("ms"), okCount = p.optLong("ok"), failCount = p.optLong("fail"),
                crcErrors = p.optLong("crc"), err = p.optString("err"),
                cyc = j.optJSONObject("cyc")?.let { c ->
                    Cyc(c.optLong("s"), c.optLong("f"), c.optDouble("g", 0.0), c.optDouble("l", 0.0), c.optDouble("p", 0.0), c.optLong("m"))
                }?.takeIf { it.s > 0 },
            )
        }
    }
}

/** Billing month counter kept by the monitor: energy (Wh) since the last meter reading (day + hour from the bill settings). */
data class Cyc(
    val s: Long,          // epoch s of the reading the month started at
    val f: Long,          // epoch s when counting began (later than s if the monitor was off or set up mid-month)
    val gridWh: Double, val loadWh: Double, val pvWh: Double,
    val minutes: Long,    // minutes the monitor was reading the inverter
)

data class Today(
    val date: Int, val pvWh: Double, val loadWh: Double, val gridWh: Double, val chgWh: Double, val disWh: Double,
    val gridOnMin: Int, val onlineMin: Int, val outages: Int, val pvPeak: Int, val loadPeak: Int,
)

/** Device + inverter information (/api/info). */
data class Info(
    val fw: String, val name: String, val host: String, val ip: String, val mac: String, val ssid: String, val rssi: Int,
    val uptime: Long, val heap: Long, val fsUsed: Long, val fsTotal: Long, val timeOk: Boolean, val tz: String,
    val clients: Int, val histFrom: Int, val battAh: Double, val tariff: Double,
    val cycDay: Int, val cycHour: Int,   // -1 on firmware without the billing counter
    val qpiri: String, val qid: String, val qvfw: String, val qflag: String,
    val proto: String = "", val chgCur: String = "", val acCur: String = "", val beqi: String = "",
) {
    val rated: Rated? get() = Rated.parse(qpiri)

    companion object {
        fun parse(s: String): Info {
            val j = JSONObject(s)
            val inv = j.optJSONObject("inv") ?: JSONObject()
            return Info(
                fw = j.optString("fw"), name = j.optString("name", "Solar"), host = j.optString("host"), ip = j.optString("ip"),
                mac = j.optString("mac"), ssid = j.optString("ssid"), rssi = j.optInt("rssi"), uptime = j.optLong("uptime"),
                heap = j.optLong("heap"), fsUsed = j.optLong("fsUsed"), fsTotal = j.optLong("fsTotal"), timeOk = j.optBoolean("timeOk"),
                tz = j.optString("tz"), clients = j.optInt("clients"), histFrom = j.optInt("histFrom"),
                battAh = j.optDouble("battAh", 0.0), tariff = j.optDouble("tariff", 0.0),
                cycDay = j.optInt("cycDay", -1), cycHour = j.optInt("cycHour", -1),
                qpiri = inv.optString("qpiri"), qid = inv.optString("qid"), qvfw = inv.optString("qvfw"), qflag = inv.optString("qflag"),
                proto = inv.optString("proto"), chgCur = inv.optString("chgCur"), acCur = inv.optString("acCur"),
                beqi = inv.optString("beqi"),
            )
        }
    }
}

/** Inverter ratings and settings from QPIRI. */
data class Rated(
    val outVA: Int, val outW: Int, val battV: Double, val recharge: Double, val cutoff: Double, val bulk: Double, val float: Double,
    val battType: Int, val maxAc: Int, val maxChg: Int, val range: Int, val outPrio: Int, val chgPrio: Int, val redischarge: Double?,
    val outV: Int = 0, val outHz: Int = 0,
) {
    companion object {
        fun parse(q: String): Rated? {
            val f = q.trim().split(Regex("\\s+"))
            if (f.size < 20) return null
            fun d(i: Int) = f[i].toDoubleOrNull() ?: 0.0
            fun n(i: Int) = f[i].toDoubleOrNull()?.toInt() ?: 0
            return Rated(n(5), n(6), d(7), d(8), d(9), d(10), d(11), n(12), n(13), n(14), n(15), n(16), n(17),
                if (f.size > 22) f[22].toDoubleOrNull() else null, n(2), n(3))
        }
    }
}

/** High-resolution sample from /api/recent. */
data class Sample(val t: Long, val pvW: Int, val loadW: Int, val gridW: Int, val battW: Int)

/** One-minute average from /api/day. */
data class MinRec(
    val t: Long, val pvW: Int, val loadW: Int, val gridW: Int, val battW: Int, val battV: Double, val pvV: Double,
    val gridV: Double, val outV: Double, val battPct: Int, val tempC: Int, val mode: Char, val flags: Int,
) {
    val gridOn get() = flags and 1 != 0
}

/** Daily totals from /api/days. */
data class DayRec(
    val date: Int, val pvWh: Float, val loadWh: Float, val gridWh: Float, val chgWh: Float, val disWh: Float,
    val pvPeak: Int, val loadPeak: Int, val gridOnMin: Int, val onlineMin: Int, val battMin: Int, val battMax: Int,
    val tempMax: Int, val outages: Int,
)

object Binary {
    private fun buf(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    private fun ByteBuffer.u16(i: Int) = getShort(i).toInt() and 0xFFFF
    private fun ByteBuffer.u32(i: Int) = getInt(i).toLong() and 0xFFFFFFFFL
    private fun ByteBuffer.u8(i: Int) = get(i).toInt() and 0xFF

    fun samples(b: ByteArray): List<Sample> {
        val bb = buf(b)
        return (0 until b.size / 16).mapNotNull { k ->
            val o = k * 16
            val t = bb.u32(o)
            if (t == 0L) null else Sample(t * 1000 + bb.u16(o + 4), bb.u16(o + 6), bb.u16(o + 8), bb.u16(o + 10), bb.getShort(o + 12).toInt())
        }
    }

    fun minutes(b: ByteArray): List<MinRec> {
        val bb = buf(b)
        return List(b.size / 24) { k ->
            val o = k * 24
            MinRec(
                t = bb.u32(o) * 1000, pvW = bb.u16(o + 4), loadW = bb.u16(o + 6), gridW = bb.u16(o + 8), battW = bb.getShort(o + 10).toInt(),
                battV = bb.u16(o + 12) / 100.0, pvV = bb.u16(o + 14) / 10.0, gridV = bb.u16(o + 16) / 10.0, outV = bb.u16(o + 18) / 10.0,
                battPct = bb.u8(o + 20), tempC = bb.get(o + 21).toInt(), mode = bb.u8(o + 22).toChar(), flags = bb.u8(o + 23),
            )
        }
    }

    fun days(b: ByteArray): List<DayRec> {
        val bb = buf(b)
        return List(b.size / 40) { k ->
            val o = k * 40
            DayRec(
                date = bb.u32(o).toInt(), pvWh = bb.getFloat(o + 4), loadWh = bb.getFloat(o + 8), gridWh = bb.getFloat(o + 12),
                chgWh = bb.getFloat(o + 16), disWh = bb.getFloat(o + 20), pvPeak = bb.u16(o + 24), loadPeak = bb.u16(o + 26),
                gridOnMin = bb.u16(o + 28), onlineMin = bb.u16(o + 30), battMin = bb.u8(o + 32), battMax = bb.u8(o + 33),
                tempMax = bb.get(o + 34).toInt(), outages = bb.u8(o + 35),
            )
        }
    }
}

/** Human-readable decoding of inverter codes (Voltronic PI30). */
object Decode {
    fun modeName(m: Char) = when (m) {
        'L' -> "Grid"; 'B' -> "Battery / Solar"; 'S' -> "Standby"; 'F' -> "Fault"
        'H' -> "Power saving"; 'P' -> "Power on"; 'D' -> "Shutdown"; else -> "Unknown"
    }

    fun modeDescription(m: Char) = when (m) {
        'L' -> "Grid is powering the home"; 'B' -> "Running from solar and battery"; 'S' -> "Standby"
        'F' -> "Inverter fault"; 'H' -> "Power saving"; 'P' -> "Starting up"; 'D' -> "Shut down"; else -> ""
    }

    val warnings = mapOf(
        1 to "Inverter fault", 2 to "Bus over-voltage", 3 to "Bus under-voltage", 4 to "Bus soft-start failed",
        5 to "Grid not available", 6 to "Output short circuit", 7 to "Inverter voltage too low", 8 to "Inverter voltage too high",
        9 to "Over temperature", 10 to "Fan locked", 11 to "Battery voltage too high", 12 to "Battery low",
        14 to "Battery under-voltage shutdown", 16 to "Overload", 17 to "EEPROM fault", 18 to "Inverter over-current",
        19 to "Inverter soft-start failed", 20 to "Self-test failed", 21 to "DC voltage on output", 22 to "Battery disconnected",
        23 to "Current sensor failed", 24 to "Battery short circuit", 25 to "Power limit active", 26 to "Solar (PV) voltage too high",
        27 to "MPPT overload fault", 28 to "MPPT overload warning", 29 to "Battery too low to charge",
    )
    val severe = setOf(1, 2, 3, 4, 6, 9, 10, 11, 14, 16, 17, 18, 19, 20, 21, 22, 23, 24, 27)

    /** Active warning bits, excluding "grid not available" (shown separately). */
    fun activeWarnings(warn: String): List<Int> = warn.indices.filter { warn[it] == '1' && it in warnings && it != 5 }

    val battTypes = listOf("AGM", "Flooded", "User defined", "Pylontech (lithium)", "Shinheung (lithium)", "WECO (lithium)", "Soltaro (lithium)", "BAK (lithium)", "Lithium")
    val outPrio = listOf("Utility first (USB)", "Solar first (SUB)", "Solar → Battery → Utility (SBU)")
    val outPrioHelp = listOf(
        "The grid powers the home whenever it is available; solar and battery only take over during outages.",
        "Solar powers the home first; the grid fills in when solar is not enough; the battery is kept for outages.",
        "Solar first, then the battery, and the grid only when the battery reaches its low limit. Saves the most units.",
    )
    val chgPrio = listOf("Utility first", "Solar first", "Solar + Utility", "Solar only")
    val chgPrioHelp = listOf(
        "The battery charges from the grid first, solar helps.",
        "The battery charges from solar first; the grid only charges it when there is no solar.",
        "Solar and grid charge the battery together (fastest).",
        "Only solar charges the battery; the grid never does.",
    )
    private val flagNames = mapOf(
        'a' to "Buzzer", 'b' to "Overload bypass", 'd' to "Solar feed to grid", 'j' to "Power saving", 'k' to "LCD back to home screen",
        'u' to "Overload auto-restart", 'v' to "Over-temp auto-restart", 'x' to "LCD backlight", 'y' to "Beep on grid loss", 'z' to "Fault code record",
    )

    fun enabledFlags(q: String): String? {
        val m = Regex("^E([a-z]*)D([a-z]*)$").find(q) ?: return null
        return m.groupValues[1].mapNotNull { flagNames[it] }.joinToString(", ").ifEmpty { "–" }
    }
}
