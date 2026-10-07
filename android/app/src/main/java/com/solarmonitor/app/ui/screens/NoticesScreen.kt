package com.solarmonitor.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.ElectricMeter
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.PowerOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solarmonitor.app.data.Notice
import com.solarmonitor.app.data.Repository
import com.solarmonitor.app.notify.Alerts
import com.solarmonitor.app.ui.dayLabel
import com.solarmonitor.app.ui.hhmm
import com.solarmonitor.app.ui.theme.LocalEnergy
import com.solarmonitor.app.ui.todayYmd
import com.solarmonitor.app.ui.ymd
import com.solarmonitor.app.ui.zone
import java.time.Instant

/** Everything the app has notified about, newest first (kept on this phone, last 60). */
@Composable
fun NoticesScreen(repo: Repository, padding: PaddingValues) {
    val list by repo.prefs.notices.collectAsStateWithLifecycle()
    LaunchedEffect(list.size) { repo.prefs.markSeen() }
    val today = todayYmd()
    val groups = list.groupBy { ymd(Instant.ofEpochMilli(it.t).atZone(zone).toLocalDate()) }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (list.isEmpty()) {
            item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.NotificationsNone, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.size(12.dp))
                    Text("No notifications yet", style = MaterialTheme.typography.titleMedium)
                    Text("Grid cuts, battery, unit and inverter alerts will be listed here.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp))
                }
            }
            return@LazyColumn
        }
        item(key = "clear") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${list.size} notification${if (list.size > 1) "s" else ""}, kept on this phone", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { repo.prefs.clearNotices() }) { Text("Clear all") }
            }
        }
        groups.forEach { (day, items) ->
            item(key = "d$day") {
                Text(if (day == today) "TODAY" else dayLabel(day).uppercase(), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
            }
            items(items, key = { "${it.t}${it.title}" }) { NoticeRow(it) }
        }
    }
}

@Composable
private fun NoticeRow(n: Notice) {
    val e = LocalEnergy.current
    val (icon, tint) = when {
        n.kind == Alerts.CH_GRID -> Icons.Rounded.PowerOff to e.grid
        n.kind == Alerts.CH_BATTERY -> Icons.Rounded.BatteryAlert to e.batt
        n.kind == Alerts.CH_USAGE -> Icons.Rounded.ElectricMeter to e.warn
        else -> Icons.Rounded.Warning to e.crit
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(14.dp)) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(n.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(hhmm(n.t), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(n.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
