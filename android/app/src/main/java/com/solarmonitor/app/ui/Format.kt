package com.solarmonitor.app.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

fun fmtW(w: Number): String {
    val v = w.toDouble()
    return if (abs(v) >= 1000) String.format(Locale.US, if (abs(v) >= 10000) "%.1f kW" else "%.2f kW", v / 1000) else "${v.roundToInt()} W"
}

fun fmtWh(wh: Number): String {
    val v = wh.toDouble()
    return when {
        v >= 100_000 -> "${(v / 1000).roundToInt()} kWh"
        v >= 10_000 -> String.format(Locale.US, "%.1f kWh", v / 1000)
        v >= 1000 -> String.format(Locale.US, "%.2f kWh", v / 1000)
        else -> "${v.roundToInt()} Wh"
    }
}

fun fmtDuration(minutes: Double): String {
    val m = minutes.roundToLong()
    val h = m / 60
    return if (h > 0) "${h}h ${(m % 60).toString().padStart(2, '0')}m" else "${m}m"
}

fun fmt1(v: Double) = String.format(Locale.US, "%.1f", v)
fun fmt2(v: Double) = String.format(Locale.US, "%.2f", v)

val zone: ZoneId get() = ZoneId.systemDefault()
/** 12-hour clock by default (what most people in Pakistan read); set from Settings. */
@Volatile var hour12 = true
private val hm24 = DateTimeFormatter.ofPattern("HH:mm")
private val hms24 = DateTimeFormatter.ofPattern("HH:mm:ss")
private val hm12 = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val hms12 = DateTimeFormatter.ofPattern("h:mm:ss a", Locale.US)

/** "2:05 PM" or "14:05" */
fun hhmm(ms: Long): String = (if (hour12) hm12 else hm24).format(Instant.ofEpochMilli(ms).atZone(zone))
/** "2:05:09 PM" or "14:05:09" */
fun hhmmss(ms: Long): String = (if (hour12) hms12 else hms24).format(Instant.ofEpochMilli(ms).atZone(zone))
/** An hour of the day: "6 AM" / "06:00" */
fun hourLabel(h: Int): String =
    if (hour12) "${if (h % 12 == 0) 12 else h % 12} ${if (h % 24 < 12) "AM" else "PM"}" else String.format(Locale.US, "%02d:00", h % 24)

fun ymd(d: LocalDate) = d.year * 10000 + d.monthValue * 100 + d.dayOfMonth
fun dateOf(ymd: Int): LocalDate = LocalDate.of(ymd / 10000, ymd / 100 % 100, ymd % 100)
fun dayStartMs(ymd: Int): Long = dateOf(ymd).atStartOfDay(zone).toInstant().toEpochMilli()
fun dayLabel(ymd: Int): String = dateOf(ymd).format(DateTimeFormatter.ofPattern("EEE d MMM"))
fun todayYmd() = ymd(LocalDate.now())
