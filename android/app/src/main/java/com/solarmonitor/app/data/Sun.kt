package com.solarmonitor.app.data

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Sunrise and sunset worked out on the phone (the standard sunrise equation, within about a minute), so no internet
 * is needed. Default place: Islamabad / Rawalpindi (IESCO area). Same code as web/src/lib/sun.ts.
 */
object Sun {
    const val LAT = 33.684
    const val LON = 73.048

    enum class Phase { Night, Low, Day }

    data class Times(val rise: Long, val set: Long)

    fun times(day: LocalDate = LocalDate.now(), lat: Double = LAT, lon: Double = LON, zone: ZoneId = ZoneId.systemDefault()): Times {
        val r = Math.PI / 180
        val noon = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val n = Math.round(noon / 86400000.0 + 2440587.5 - 2451545.0 + 0.0008).toDouble()
        val j = n - lon / 360
        val m = (357.5291 + 0.98560028 * j) % 360
        val c = 1.9148 * sin(m * r) + 0.02 * sin(2 * m * r) + 0.0003 * sin(3 * m * r)
        val l = (m + c + 180 + 102.9372) % 360
        val transit = 2451545 + j + 0.0053 * sin(m * r) - 0.0069 * sin(2 * l * r)
        val dec = asin(sin(l * r) * sin(23.44 * r))
        val cosW = (sin(-0.833 * r) - sin(lat * r) * sin(dec)) / (cos(lat * r) * cos(dec))
        val w = acos(cosW.coerceIn(-1.0, 1.0)) / r
        fun ms(jd: Double) = ((jd - 2440587.5) * 86400000).roundToLong()
        return Times(ms(transit - w / 360), ms(transit + w / 360))
    }

    /** Night = before sunrise or after sunset; Low = within 90 min of either (little solar is normal); Day otherwise. */
    fun phase(now: Long = System.currentTimeMillis()): Phase {
        val t = times(java.time.Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate())
        return when {
            now < t.rise || now > t.set -> Phase.Night
            now < t.rise + 90 * 60_000 || now > t.set - 90 * 60_000 -> Phase.Low
            else -> Phase.Day
        }
    }
}
