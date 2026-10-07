package com.solarmonitor.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class Settings(
    val host: String = "solar.local",
    val background: Boolean = true,      // keep a connection in the background for alerts
    val alertGrid: Boolean = true,
    val alertBattLow: Boolean = true,
    val battLowPct: Int = 25,
    val alertBattFull: Boolean = false,
    val alertFault: Boolean = true,
    val alertWeakSolar: Boolean = true,  // daytime, grid off, little sun, battery carrying the home
    val alertUnits: Boolean = true,      // monthly grid units: 150 / 175 / 190 and "heading over 200"
    val battIdleOn: Boolean = true,      // small battery flows count as idle
    val battIdleW: Int = 100,
    val fx3d: Boolean = true,            // 3D energy core behind the power flow
    val theme: Int = 0,                  // 0 system, 1 light, 2 dark
    val dynamicColor: Boolean = true,
    val askedNotifications: Boolean = false,
    val hour12: Boolean = true,          // 12-hour clock (AM/PM)
) {
    /** Battery flows below this many watts are shown as idle. */
    val idleW get() = if (battIdleOn) battIdleW else Power.DEADBAND
}

/** One entry in the in-app notification history. */
data class Notice(val t: Long, val kind: String, val title: String, val text: String)

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
            alertWeakSolar = sp.getBoolean("alertWeakSolar", d.alertWeakSolar),
            alertUnits = sp.getBoolean("alertUnits", d.alertUnits),
            battIdleOn = sp.getBoolean("battIdleOn", d.battIdleOn),
            battIdleW = sp.getInt("battIdleW", d.battIdleW),
            fx3d = sp.getBoolean("fx3d", d.fx3d),
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
            .putBoolean("alertWeakSolar", s.alertWeakSolar)
            .putBoolean("alertUnits", s.alertUnits)
            .putBoolean("battIdleOn", s.battIdleOn)
            .putInt("battIdleW", s.battIdleW)
            .putBoolean("fx3d", s.fx3d)
            .putInt("theme", s.theme)
            .putBoolean("dynamicColor", s.dynamicColor)
            .putBoolean("askedNotifications", s.askedNotifications)
            .putBoolean("hour12", s.hour12)
            .apply()
        _state.value = s.copy(host = s.host.trim())
    }

    // ---- notification history (newest first, last 60) ----
    private val _notices = MutableStateFlow(loadNotices())
    val notices: StateFlow<List<Notice>> = _notices.asStateFlow()
    /** Number of notices since the user last opened the list. */
    private val _unseen = MutableStateFlow(sp.getInt("unseen", 0))
    val unseen: StateFlow<Int> = _unseen.asStateFlow()

    private fun loadNotices(): List<Notice> = try {
        val a = JSONArray(sp.getString("notices", "[]"))
        List(a.length()) { a.getJSONObject(it).let { o -> Notice(o.getLong("t"), o.optString("k"), o.optString("ti"), o.optString("tx")) } }
    } catch (_: Exception) { emptyList() }

    @Synchronized fun addNotice(n: Notice) {
        val list = (listOf(n) + _notices.value).take(60)
        sp.edit().putString("notices", JSONArray().apply {
            list.forEach { put(JSONObject().put("t", it.t).put("k", it.kind).put("ti", it.title).put("tx", it.text)) }
        }.toString()).putInt("unseen", _unseen.value + 1).apply()
        _notices.value = list
        _unseen.value += 1
    }

    fun markSeen() { sp.edit().putInt("unseen", 0).apply(); _unseen.value = 0 }
    fun clearNotices() { sp.edit().putString("notices", "[]").putInt("unseen", 0).apply(); _notices.value = emptyList(); _unseen.value = 0 }

    // ---- small persisted markers for alerts that must not repeat (e.g. unit steps per billing month) ----
    fun getMark(key: String): String? = sp.getString("mark_$key", null)
    fun setMark(key: String, v: String) = sp.edit().putString("mark_$key", v).apply()
}
