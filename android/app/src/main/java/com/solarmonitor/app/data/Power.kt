package com.solarmonitor.app.data

import java.time.LocalTime
import kotlin.math.max

// Plain-language reading of what is powering the home. Same rules as web/src/lib/power.ts.
object Power {
    /** Smaller solar / grid flows are treated as zero so labels don't flicker. */
    const val DEADBAND = 15

    enum class Batt { Charging, Discharging, Idle }

    /** idleW: battery flows smaller than this count as idle (the inverter draws a little from a full battery). */
    fun batt(d: Live, idleW: Int): Batt = when {
        d.battW >= max(DEADBAND, idleW) -> Batt.Charging
        -d.battW >= max(DEADBAND, idleW) -> Batt.Discharging
        else -> Batt.Idle
    }

    fun sources(d: Live, idleW: Int): List<String> {
        if (!d.ok) return emptyList()
        val out = ArrayList<String>(3)
        if (d.pvW >= DEADBAND) out += "Solar"
        if (d.gridOn && d.gridW >= DEADBAND) out += "Grid"
        if (batt(d, idleW) == Batt.Discharging) out += "Battery"
        return out
    }

    /** "Solar + Grid", "Solar + Battery", "Battery"… or the inverter mode when nothing is flowing. */
    fun label(d: Live, idleW: Int): String = sources(d, idleW).joinToString(" + ").ifEmpty { Decode.modeName(d.mode) }

    fun sentence(d: Live, idleW: Int): String {
        val s = sources(d, idleW).map { when (it) { "Grid" -> "the grid"; "Battery" -> "the battery"; else -> "solar" } }
        if (s.isEmpty()) return Decode.modeDescription(d.mode)
        val list = if (s.size == 1) s[0] else s.dropLast(1).joinToString(", ") + " and " + s.last()
        return list.replaceFirstChar { it.uppercase() } + (if (s.size == 1) " powers" else " power") + " the home"
    }

    /**
     * Daytime (7 AM to 6 PM, solar already made real power today), grid off, little sun and the battery is carrying
     * the home: usually clouds, or the grid was switched off and forgotten.
     */
    fun weakSolar(d: Live, idleW: Int, now: LocalTime = LocalTime.now()): Boolean {
        if (!d.ok || d.gridOn) return false
        if (now.hour < 7 || now.hour >= 18 || d.today.pvPeak < 300) return false
        return d.pvW < max(80.0, 0.3 * d.loadW) && batt(d, max(idleW, 50)) == Batt.Discharging
    }
}
