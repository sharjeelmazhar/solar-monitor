package com.solarmonitor.app.data

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

// Inverter settings that can be changed from the apps (Advanced mode). Same list, ranges and wording as the web app
// (web/src/lib/invset.ts) and the firmware check (firmware/solar_monitor_v3/invset.h), which refuses anything outside
// these ranges a second time before it reaches the inverter.

enum class SetKind { Choice, Volts, Amps, Flag }

data class SetDef(val key: String, val label: String, val help: String, val kind: SetKind, val letter: Char? = null) {
    val id get() = if (letter != null) "flag:$letter" else key
}

data class Choice(val value: Double, val label: String, val help: String? = null)

data class SetResult(val ok: Boolean, val msg: String)

data class LogEntry(val t: Long, val by: String, val k: String, val o: String, val n: String, val r: String, val m: String)

object InvSet {
    val all = listOf(
        SetDef("outPrio", "Output priority", "Which source powers the home first.", SetKind.Choice),
        SetDef("chgPrio", "Charger priority", "Which source charges the battery.", SetKind.Choice),
        SetDef("bulk", "Bulk charge voltage", "The voltage the charger pushes the battery up to. Follow your battery maker's value.", SetKind.Volts),
        SetDef("float", "Float charge voltage", "The voltage the charger holds once the battery is full. Never above bulk.", SetKind.Volts),
        SetDef("cutoff", "Low cut-off voltage", "Below this the inverter switches the battery off to protect it.", SetKind.Volts),
        SetDef("recharge", "Back to grid at", "Battery voltage at which the home switches to the grid (SBU / Solar first).", SetKind.Volts),
        SetDef("redischarge", "Back to battery at", "Battery voltage at which the home goes back to the battery after recharging. \"Full\" waits for a full battery.", SetKind.Volts),
        SetDef("maxChg", "Max charge current", "Total charging current limit (solar + grid).", SetKind.Amps),
        SetDef("maxAc", "Max grid charge current", "Charging current limit from the grid.", SetKind.Amps),
        SetDef("range", "AC input range", "Appliance accepts a wider grid voltage; UPS switches to battery faster (for computers).", SetKind.Choice),
        SetDef("flag", "Buzzer", "Beeps on alarms and key presses.", SetKind.Flag, 'a'),
        SetDef("flag", "Beep on grid loss", "Beeps when the grid goes off.", SetKind.Flag, 'y'),
        SetDef("flag", "LCD backlight", "Keeps the screen lit.", SetKind.Flag, 'x'),
        SetDef("flag", "LCD returns to home screen", "The screen goes back to the main page after a minute.", SetKind.Flag, 'k'),
        SetDef("flag", "Overload auto-restart", "Restarts by itself after an overload trip.", SetKind.Flag, 'u'),
        SetDef("flag", "Over-temperature auto-restart", "Restarts by itself after cooling down.", SetKind.Flag, 'v'),
        SetDef("flag", "Overload bypass", "Switches to the grid when the load is too big for the inverter.", SetKind.Flag, 'b'),
        SetDef("flag", "Power saving", "Turns the inverter output off when nothing is connected.", SetKind.Flag, 'j'),
        SetDef("flag", "Fault code record", "Keeps a history of fault codes in the inverter.", SetKind.Flag, 'z'),
    )

    fun def(key: String) = all.first { it.key == key }

    /** Allowed values for a setting given the current ratings; null when it can't be changed yet. */
    fun choices(d: SetDef, r: Rated, chgCur: String, acCur: String): List<Choice>? {
        val k = r.battV / 12
        if (k != 1.0 && k != 2.0 && k != 4.0) return null
        fun volts(lo: Double, hi: Double, step: Double): List<Choice> {
            val n = ((hi - lo) / step + 1e-6).toInt()
            return (0..n).map { val v = ((lo + it * step) * 100).roundToInt() / 100.0; Choice(v, "%.1f V".format(v)) }
        }
        fun list(s: String, max: Int = 999) = s.trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
            .filter { it in 1..max }.map { Choice(it.toDouble(), "$it A") }
        return when (d.key) {
            "outPrio" -> Decode.outPrio.mapIndexed { i, l -> Choice(i.toDouble(), l, Decode.outPrioHelp[i]) }
            "chgPrio" -> Decode.chgPrio.mapIndexed { i, l -> Choice(i.toDouble(), l, Decode.chgPrioHelp[i]) }
            "range" -> listOf(Choice(0.0, "Appliance (wide)"), Choice(1.0, "UPS (narrow)"))
            "bulk" -> volts(12 * k, 14.6 * k, 0.1).filter { it.value >= r.float - 1e-6 }
            "float" -> volts(12 * k, 14.6 * k, 0.1).filter { it.value <= r.bulk + 1e-6 }
            "cutoff" -> volts(10.5 * k, 12 * k, 0.1).filter { it.value < r.recharge - 1e-6 }
            "recharge" -> volts(11 * k, 12.75 * k, 0.25 * k).filter { it.value > r.cutoff + 1e-6 && (r.redischarge == null || r.redischarge == 0.0 || it.value < r.redischarge - 1e-6) }
            "redischarge" -> listOf(Choice(0.0, "Full battery")) + volts(12 * k, 14.5 * k, 0.25 * k).filter { it.value > r.recharge + 1e-6 }
            "maxChg" -> list(chgCur)
            "maxAc" -> list(acCur, 99)
            "flag" -> listOf(Choice(1.0, "On"), Choice(0.0, "Off"))
            else -> null
        }
    }

    /** Current value from QPIRI / QFLAG. */
    fun current(d: SetDef, r: Rated, qflag: String): Double? {
        if (d.kind == SetKind.Flag) {
            val m = Regex("^E([a-z]*)D([a-z]*)$").find(qflag) ?: return null
            val l = d.letter ?: return null
            return if (l in m.groupValues[1]) 1.0 else if (l in m.groupValues[2]) 0.0 else null
        }
        return when (d.key) {
            "outPrio" -> r.outPrio.toDouble(); "chgPrio" -> r.chgPrio.toDouble(); "range" -> r.range.toDouble()
            "bulk" -> r.bulk; "float" -> r.float; "cutoff" -> r.cutoff; "recharge" -> r.recharge; "redischarge" -> r.redischarge
            "maxChg" -> r.maxChg.toDouble(); "maxAc" -> r.maxAc.toDouble()
            else -> null
        }
    }

    fun label(d: SetDef, v: Double?, r: Rated, chgCur: String, acCur: String): String {
        if (v == null) return "–"
        choices(d, r, chgCur, acCur)?.firstOrNull { abs(it.value - v) < 0.01 }?.let { return it.label }
        return when (d.kind) {
            SetKind.Volts -> if (v == 0.0) "Full battery" else "%.1f V".format(v)
            SetKind.Amps -> "${v.toInt()} A"
            else -> v.toString()
        }
    }

    fun logLabel(k: String) = all.firstOrNull { it.id == k }?.label ?: k

    // ---- talking to the monitor ----

    /** "ok", "wrong" or "offline" */
    suspend fun checkPassword(api: Api, pw: String): String {
        val (code, _) = api.postResult("/api/inv/auth", mapOf("pw" to pw))
        return when (code) { in 200..299 -> "ok"; 401 -> "wrong"; else -> "offline" }
    }

    /** Sends one change and waits for the monitor to read it back. */
    suspend fun send(api: Api, d: SetDef, value: Double, pw: String): SetResult {
        val p = mutableMapOf("pw" to pw, "key" to d.key, "value" to value.toString(), "by" to "android")
        d.letter?.let { p["letter"] = it.toString() }
        val (code, body) = api.postResult("/api/inv/set", p)
        if (code == 0) return SetResult(false, "Could not reach the monitor")
        val j = runCatching { JSONObject(body) }.getOrNull()
        if (code !in 200..299) return SetResult(false, j?.optString("error")?.takeIf { it.isNotEmpty() } ?: "The monitor refused the change")
        val id = j?.optLong("id") ?: return SetResult(false, "Unexpected answer from the monitor")
        val until = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < until) {
            delay(600)
            val s = runCatching { JSONObject(String(api.get("/api/inv/job"))) }.getOrNull() ?: continue
            if (s.optLong("id") != id) continue
            val st = s.optString("state")
            if (st == "ok" || st == "failed") return SetResult(st == "ok", s.optString("msg"))
        }
        return SetResult(false, "No answer from the monitor in 20 s. Check the change log.")
    }

    suspend fun log(api: Api): List<LogEntry> = runCatching {
        val a = JSONArray(String(api.get("/api/inv/log")))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            LogEntry(o.optLong("t"), o.optString("by"), o.optString("k"), o.optString("o"), o.optString("n"), o.optString("r"), o.optString("m"))
        }.reversed()
    }.getOrDefault(emptyList())

    suspend fun startProbe(api: Api, pw: String) = api.postResult("/api/probe", mapOf("pw" to pw)).first in 200..299
    suspend fun readProbe(api: Api): String = runCatching { String(api.get("/api/probe")) }.getOrDefault("")
}
