package com.solarmonitor.app.data

import org.json.JSONObject

/** A Wi-Fi network the monitor can see. */
data class WifiNet(val ssid: String, val rssi: Int, val open: Boolean) {
    val signal get() = if (rssi > -60) "strong" else if (rssi > -72) "good" else "weak"
}

/** Moving the monitor to another Wi-Fi from inside the app (same logic as the web's lib/wifi.ts). */
object WifiSetup {
    /** Result of GET /api/scan: null while the monitor is still scanning; strongest first, one row per name, hidden names dropped. */
    fun parseScan(json: String): List<WifiNet>? {
        val o = JSONObject(json)
        if (o.optBoolean("scanning")) return null
        val arr = o.optJSONArray("nets") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { WifiNet(it.optString("ssid"), it.optInt("rssi"), it.optBoolean("open")) }
            .filter { it.ssid.isNotEmpty() }.sortedByDescending { it.rssi }.distinctBy { it.ssid }
    }

    /** What is wrong with the form, or null when it can be sent. A network not in the list may still be open, so an empty password is allowed for it. */
    fun formError(ssid: String, pass: String, open: Boolean, admin: String): String? = when {
        ssid.isEmpty() -> "Pick a Wi-Fi network"
        ssid.toByteArray().size > 32 -> "That Wi-Fi name is too long"
        pass.toByteArray().size > 63 -> "That Wi-Fi password is too long"
        !open && pass.isNotEmpty() && pass.length < 8 -> "Wi-Fi passwords have at least 8 characters"
        admin.isEmpty() -> "Enter the monitor's admin password"
        else -> null
    }

    /** True when GET /api/wifistatus says the monitor has joined [ssid]; the second value is its new address. */
    fun joined(json: String, ssid: String): Pair<Boolean, String> {
        val o = JSONObject(json)
        return (o.optBoolean("connected") && o.optString("ssid") == ssid) to o.optString("ip")
    }
}
