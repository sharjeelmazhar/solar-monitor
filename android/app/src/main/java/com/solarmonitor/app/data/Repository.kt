package com.solarmonitor.app.data

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

enum class Conn { Idle, Connecting, Live, Offline }

/**
 * Single source of truth. Keeps one connection to the monitor:
 *  - app visible  -> fast stream (/events, every reading, ~0.6 s)
 *  - only the background service -> quiet stream (/events/status, only on important changes)
 */
class Repository(private val context: Context, val prefs: Prefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val api = Api(context) { prefs.value.host }

    private val _live = MutableStateFlow<Live?>(null)
    val live: StateFlow<Live?> = _live.asStateFlow()
    private val _conn = MutableStateFlow(Conn.Idle)
    val conn: StateFlow<Conn> = _conn.asStateFlow()
    private val _info = MutableStateFlow<Info?>(null)
    val info: StateFlow<Info?> = _info.asStateFlow()
    private val _recent = MutableStateFlow<List<Sample>>(emptyList())
    val recent: StateFlow<List<Sample>> = _recent.asStateFlow()
    private val _interval = MutableStateFlow(0L)
    val updateInterval: StateFlow<Long> = _interval.asStateFlow()
    private val _lastMsg = MutableStateFlow(0L)       // elapsedRealtime of last message
    val lastMsg: StateFlow<Long> = _lastMsg.asStateFlow()
    private val _lastRx = MutableStateFlow(0L)        // elapsedRealtime of any message, heartbeats too
    val lastRx: StateFlow<Long> = _lastRx.asStateFlow()
    private val _bill = MutableStateFlow<BillConfig?>(null)
    val bill: StateFlow<BillConfig?> = _bill.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val uiActive = MutableStateFlow(false)
    /** elapsedRealtime when the app last came to the screen (grace period before calling the monitor offline). */
    @Volatile var uiSince = 0L
        private set
    val serviceActive = MutableStateFlow(false)

    /** Called for every new reading (alerts hook in here). */
    var onReading: ((prev: Live?, cur: Live) -> Unit)? = null

    private var lastDiscovery = 0L
    private val intervals = ArrayDeque<Long>()
    private val dayCache = ConcurrentHashMap<Int, List<MinRec>>()

    init { start() }

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private fun start() {
        val wanted = combine(uiActive, serviceActive, prefs.state.map { it.host }.distinctUntilChanged(), api.wifiTick) { ui, svc, host, tick ->
            Triple(if (ui) "/events" else if (svc) "/events/status" else null, host, tick)
        }.debounce(150)
        scope.launch {
            wanted.collectLatest { (path, _, _) ->
                if (path == null) { _conn.value = Conn.Idle; return@collectLatest }
                runStream(path, fast = path == "/events")
            }
        }
        // info refresh while visible
        scope.launch {
            uiActive.collectLatest { ui ->
                if (ui) uiSince = SystemClock.elapsedRealtime()
                while (ui && isActive) { refreshInfo(); delay(30_000) }
            }
        }
    }

    private suspend fun runStream(path: String, fast: Boolean) {
        var failures = 0
        while (true) {
            _conn.value = Conn.Connecting
            try {
                api.stream(path, if (fast) 15_000 else 150_000, onOpen = {
                    _conn.value = Conn.Live
                    _error.value = null
                    failures = 0
                    if (fast) scope.launch { loadRecent(); if (_info.value == null) refreshInfo(); if (_bill.value == null) loadBill() }
                }) { event, data -> if (event == "live") handle(data) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = if (!api.onWifi) "Not on Wi-Fi" else e.message ?: "Connection failed"
            }
            _conn.value = Conn.Offline
            failures++
            // Address not working (e.g. "solar.local" on Android < 12, or the router gave a new IP): look it up via mDNS.
            if (failures == 2 && api.onWifi && SystemClock.elapsedRealtime() - lastDiscovery > 60_000) {
                lastDiscovery = SystemClock.elapsedRealtime()
                val ip = Discovery.find(context)
                if (ip != null && ip != prefs.value.host) { prefs.update { it.copy(host = ip) }; return }
            }
            // fast: quick retries; background: back off up to a minute (also restarts at once when Wi-Fi changes)
            delay(if (fast) minOf(5_000L, 700L * failures) else minOf(60_000L, 5_000L * failures))
        }
    }

    private fun handle(json: String) {
        val d = try { Live.parse(json) } catch (e: Exception) { return }
        val prev = _live.value
        val now = SystemClock.elapsedRealtime()
        _lastRx.value = now
        if (prev == null || d.seq != prev.seq) {
            val last = _lastMsg.value
            if (last != 0L && prev != null && d.seq == prev.seq + 1) {
                intervals.addLast(now - last); if (intervals.size > 12) intervals.removeFirst()
                _interval.value = intervals.sum() / intervals.size
            }
            _lastMsg.value = now
        }
        _live.value = d
        if (d.ok && d.t > 0 && uiActive.value) {
            val cut = d.t - 16 * 60_000
            val list = _recent.value
            val start = list.indexOfFirst { it.t >= cut }.let { if (it < 0) list.size else it }
            _recent.value = list.subList(start, list.size) + Sample(d.t, d.pvW, d.loadW, d.gridW, d.battW)
        }
        onReading?.invoke(prev, d)
    }

    private suspend fun loadRecent() {
        try {
            val s = Binary.samples(api.get("/api/recent"))
            val last = s.lastOrNull()?.t ?: 0
            _recent.value = s + _recent.value.filter { it.t > last }
        } catch (_: Exception) {}
    }

    suspend fun refreshInfo(): Info? = try {
        Info.parse(String(api.get("/api/info"))).also { _info.value = it }
    } catch (_: Exception) { null }

    suspend fun day(date: Int, today: Boolean): List<MinRec>? {
        if (!today) dayCache[date]?.let { return it }
        return try {
            Binary.minutes(api.get("/api/day?d=$date", 15_000)).also { if (!today) dayCache[date] = it }
        } catch (e: java.io.IOException) {
            if (e.message?.contains("404") == true) emptyList() else null
        } catch (_: Exception) { null }
    }

    suspend fun days(): List<DayRec>? = try { Binary.days(api.get("/api/days", 15_000)) } catch (_: Exception) { null }

    suspend fun saveDeviceSettings(name: String, battAh: Double, tariff: Double): Boolean = try {
        _info.value = Info.parse(String(api.post("/api/settings", mapOf("name" to name.ifBlank { "Solar" }, "battAh" to battAh.toString(), "tariff" to tariff.toString()))))
        true
    } catch (_: Exception) { false }

    suspend fun loadBill() {
        _bill.value = try { BillConfig.parse(String(api.get("/api/bill"))) } catch (_: Exception) { _bill.value ?: return }
        syncCycle()
    }

    /** The monitor counts grid units from the meter reading (day + hour from the bill settings): keep it told. */
    private suspend fun syncCycle() {
        val b = _bill.value ?: return
        val i = _info.value ?: return
        if (i.cycDay < 0 || (i.cycDay == b.day && i.cycHour == b.hr)) return
        runCatching { _info.value = Info.parse(String(api.post("/api/settings", mapOf("cycDay" to b.day.toString(), "cycHour" to b.hr.toString())))) }
    }

    /** Bill settings live on the monitor so the web dashboard and every phone share them. */
    suspend fun saveBill(c: BillConfig): Boolean = try {
        _bill.value = BillConfig.parse(String(api.post("/api/bill", mapOf("v" to c.toJson()))))
        syncCycle()
        true
    } catch (_: Exception) { false }

    suspend fun refreshInverter() = runCatching { api.post("/api/refresh", emptyMap()) }.isSuccess

    fun clearCache() { dayCache.clear(); _recent.value = emptyList(); _live.value = null; _info.value = null; _bill.value = null }
}
