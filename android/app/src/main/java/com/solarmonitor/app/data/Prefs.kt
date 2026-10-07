package com.solarmonitor.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    val host: String = "solar.local",
    val background: Boolean = true,      // keep a connection in the background for alerts
    val alertGrid: Boolean = true,
    val alertBattLow: Boolean = true,
    val battLowPct: Int = 25,
    val alertBattFull: Boolean = false,
    val alertFault: Boolean = true,
    val theme: Int = 0,                  // 0 system, 1 light, 2 dark
    val dynamicColor: Boolean = true,
    val askedNotifications: Boolean = false,
    val hour12: Boolean = true,          // 12-hour clock (AM/PM)
)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("solar", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Settings> = _state.asStateFlow()
    val value get() = _state.value

    private fun load(): Settings {
        val d = Settings()
        return Settings(
            host = sp.getString("host", d.host) ?: d.host,
            background = sp.getBoolean("background", d.background),
            alertGrid = sp.getBoolean("alertGrid", d.alertGrid),
            alertBattLow = sp.getBoolean("alertBattLow", d.alertBattLow),
            battLowPct = sp.getInt("battLowPct", d.battLowPct),
            alertBattFull = sp.getBoolean("alertBattFull", d.alertBattFull),
            alertFault = sp.getBoolean("alertFault", d.alertFault),
            theme = sp.getInt("theme", d.theme),
            dynamicColor = sp.getBoolean("dynamicColor", d.dynamicColor),
            askedNotifications = sp.getBoolean("askedNotifications", d.askedNotifications),
            hour12 = sp.getBoolean("hour12", d.hour12),
        )
    }

    fun update(f: (Settings) -> Settings) {
        val s = f(_state.value)
        sp.edit()
            .putString("host", s.host.trim())
            .putBoolean("background", s.background)
            .putBoolean("alertGrid", s.alertGrid)
            .putBoolean("alertBattLow", s.alertBattLow)
            .putInt("battLowPct", s.battLowPct)
            .putBoolean("alertBattFull", s.alertBattFull)
            .putBoolean("alertFault", s.alertFault)
            .putInt("theme", s.theme)
            .putBoolean("dynamicColor", s.dynamicColor)
            .putBoolean("askedNotifications", s.askedNotifications)
            .putBoolean("hour12", s.hour12)
            .apply()
        _state.value = s.copy(host = s.host.trim())
    }
}
