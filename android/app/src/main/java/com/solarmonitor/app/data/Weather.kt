package com.solarmonitor.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Sky over the house, so the app can say "cloudy" when solar is low in daytime.
 * The phone asks Open-Meteo (free, no key) itself: the monitor board has no room for HTTPS.
 * Same logic as the web's lib/weather.ts.
 */
object Weather {
    const val PLACE = "Gujar Khan"
    private const val LAT = 33.253
    private const val LON = 73.304
    private const val EVERY_MS = 15 * 60_000L

    enum class Sky { Clear, Partly, Cloudy, Rain }
    data class Now(val sky: Sky, val cloud: Int, val at: Long)
    enum class Badge { Night, Cloudy, Rain, CloudyGuess }

    @Volatile private var cache: Now? = null
    @Volatile private var inFlight = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** WMO weather code + cloud cover (%) to a simple sky. */
    fun skyOf(code: Int, cloud: Int): Sky = when {
        code in 51..67 || code in 80..82 || code >= 95 -> Sky.Rain
        code == 3 || code == 45 || code == 48 || cloud >= 70 -> Sky.Cloudy
        code == 2 || cloud >= 30 -> Sky.Partly
        else -> Sky.Clear
    }

    /** Latest weather, or null when unknown / no internet / older than 45 minutes. Starts a refresh when due. */
    fun current(): Now? {
        refresh()
        return cache?.takeIf { System.currentTimeMillis() - it.at < 3 * EVERY_MS }
    }

    private fun refresh() {
        val c = cache
        if (inFlight || (c != null && System.currentTimeMillis() - c.at < EVERY_MS)) return
        inFlight = true
        scope.launch {
            try {
                val u = URL("https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON&current=cloud_cover,weather_code")
                val con = (u.openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
                val j = JSONObject(con.inputStream.bufferedReader().use { it.readText() }).getJSONObject("current")
                val cloud = j.getDouble("cloud_cover").roundToInt()
                cache = Now(skyOf(j.getInt("weather_code"), cloud), cloud, System.currentTimeMillis())
            } catch (_: Exception) {
                // no internet on this network: the watts-only guess still works
            } finally {
                inFlight = false
            }
        }
    }

    /** Moon at night; cloud when the sky is cloudy (or, with no weather, when solar is far below today's peak midday). */
    fun badge(d: Live, w: Now?, now: Long = if (d.t > 0) d.t else System.currentTimeMillis()): Badge? {
        val phase = Sun.phase(now)
        if (phase == Sun.Phase.Night) return if (d.pvW < 15) Badge.Night else null
        if (w != null && w.sky == Sky.Rain) return Badge.Rain
        if (w != null && w.sky == Sky.Cloudy) return Badge.Cloudy
        if (w == null && phase == Sun.Phase.Day && d.today.pvPeak >= 300 && d.pvW < max(80.0, 0.25 * d.today.pvPeak)) return Badge.CloudyGuess
        return null
    }

    /** "cloudy in Gujar Khan (85% cloud)" / "raining in Gujar Khan" for alert texts, else null. */
    fun words(w: Now?): String? = when (w?.sky) {
        Sky.Rain -> "raining in $PLACE"
        Sky.Cloudy -> "cloudy in $PLACE (${w.cloud}% cloud)"
        else -> null
    }
}
