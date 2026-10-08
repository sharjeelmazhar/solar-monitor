package com.solarmonitor.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Plain HTTP client for the monitor. Requests go over Wi-Fi explicitly, so the phone never tries
 * to reach the home device over mobile data.
 */
class Api(context: Context, private val host: () -> String) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    @Volatile private var wifi: Network? = null

    /** Changes every time a Wi-Fi network becomes available (used to reconnect immediately). */
    private val _wifiTick = MutableStateFlow(0)
    val wifiTick: StateFlow<Int> = _wifiTick

    init {
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { wifi = network; _wifiTick.value++ }
            override fun onLost(network: Network) { if (wifi == network) wifi = null; _wifiTick.value++ }
        })
    }

    val onWifi get() = wifi != null

    private fun open(path: String, connectMs: Int, readMs: Int): HttpURLConnection {
        val url = URL("http://${host()}$path")
        val c = (wifi?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        c.connectTimeout = connectMs
        c.readTimeout = readMs
        c.useCaches = false
        return c
    }

    suspend fun get(path: String, timeoutMs: Int = 8000): ByteArray = withContext(Dispatchers.IO) {
        val c = open(path, 5000, timeoutMs)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    suspend fun post(path: String, params: Map<String, String>): ByteArray = withContext(Dispatchers.IO) {
        val body = params.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
        val c = open(path, 5000, 8000)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    /** POST that also returns error answers: (HTTP status, body). Status 0 = the monitor could not be reached. */
    suspend fun postResult(path: String, params: Map<String, String>): Pair<Int, String> = withContext(Dispatchers.IO) {
        val body = params.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
        try {
            val c = open(path, 5000, 8000)
            try {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(body.toByteArray()) }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes()) } ?: ""
                code to text
            } finally {
                c.disconnect()
            }
        } catch (_: IOException) {
            0 to ""
        }
    }

    /**
     * Server-Sent Events. Blocks until the connection drops (throws) or the coroutine is cancelled.
     * [readTimeoutMs] must be longer than the server heartbeat.
     */
    suspend fun stream(path: String, readTimeoutMs: Int, onOpen: () -> Unit, onEvent: (String, String) -> Unit) = coroutineScope {
        val c = withContext(Dispatchers.IO) { open(path, 5000, readTimeoutMs) }
        c.setRequestProperty("Accept", "text/event-stream")
        val closer = launch { try { awaitCancellation() } finally { c.disconnect() } }
        try {
            withContext(Dispatchers.IO) {
                if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
                onOpen()
                val r = java.io.BufferedReader(java.io.InputStreamReader(c.inputStream, Charsets.UTF_8), 4096)
                var event = "message"
                val data = StringBuilder()
                while (true) {
                    val line = r.readLine() ?: throw IOException("stream closed")
                    when {
                        line.isEmpty() -> {
                            if (data.isNotEmpty()) onEvent(event, data.toString())
                            event = "message"; data.setLength(0)
                        }
                        line.startsWith("data:") -> { if (data.isNotEmpty()) data.append('\n'); data.append(line.substring(5).trimStart()) }
                        line.startsWith("event:") -> event = line.substring(6).trim()
                    }
                }
            }
        } finally {
            closer.cancel()
            c.disconnect()
        }
    }
}
