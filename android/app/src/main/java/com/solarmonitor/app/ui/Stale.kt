package com.solarmonitor.app.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solarmonitor.app.data.Repository
import kotlinx.coroutines.delay

/** No message for this long means the monitor (or the Wi-Fi link to it) is down. Its heartbeat is 5 s. */
const val STALE_MS = 12_000L

/**
 * null while readings arrive; otherwise milliseconds since the last one. Counts from when the app came to the
 * screen at the earliest, so reopening the app doesn't flash "offline" while it reconnects.
 */
@Composable
fun rememberStaleMs(repo: Repository): Long? {
    val last by repo.lastRx.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = SystemClock.elapsedRealtime() } }
    val since = maxOf(last, repo.uiSince)
    val age = now - since
    return if (since == 0L || age < STALE_MS) null else now - (if (last > 0) last else since)
}
