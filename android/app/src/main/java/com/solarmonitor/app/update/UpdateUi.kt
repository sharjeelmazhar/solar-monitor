package com.solarmonitor.app.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solarmonitor.app.ui.components.SectionCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Starts the update, first sending the user to "Install unknown apps" if this app may not install yet (asked once). */
private fun startUpdate(ctx: android.content.Context, scope: CoroutineScope, rel: Release) {
    val u = ctx.updater
    if (u.canInstall()) { scope.launch { u.install(rel) }; return }
    u.waitingForPermission = rel
    runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))) }
}

/**
 * Checks for a new version every time the app comes to the front (at most every 15 minutes) and pops up a dialog
 * when there is one; also resumes an update after the user allowed "Install unknown apps".
 */
@Composable
fun UpdatePrompt() {
    val ctx = LocalContext.current
    val u = ctx.updater
    val st by u.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var dismissed by remember { mutableStateOf(false) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START && System.currentTimeMillis() - u.lastCheck > 15 * 60_000L) scope.launch { u.check(quiet = true) }
            if (e == Lifecycle.Event.ON_RESUME) u.waitingForPermission?.let { rel ->
                if (u.canInstall()) { u.waitingForPermission = null; scope.launch { u.install(rel) } }
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val show = !dismissed && when (val s = st) {
        is UpdState.Available -> !u.snoozed(s.rel)
        is UpdState.Downloading, is UpdState.Installing -> true
        is UpdState.Failed -> s.rel != null
        else -> false
    }
    if (!show) return
    val s = st
    val busy = s is UpdState.Downloading || s is UpdState.Installing
    AlertDialog(
        onDismissRequest = { if (!busy) dismissed = true },
        title = { Text(if (s is UpdState.Failed) "Update didn't finish" else "New version: v${relOf(s)?.version}") },
        text = { UpdateBody(s, u.current) },
        confirmButton = {
            when (s) {
                is UpdState.Available -> TextButton(onClick = { startUpdate(ctx, scope, s.rel) }) { Text("Update now") }
                is UpdState.Failed -> s.rel?.let { rel -> TextButton(onClick = { startUpdate(ctx, scope, rel) }) { Text("Try again") } }
                else -> {}
            }
        },
        dismissButton = {
            if (!busy) TextButton(onClick = { (s as? UpdState.Available)?.rel?.let(u::snooze); dismissed = true }) { Text("Later") }
        },
    )
}

private fun relOf(s: UpdState): Release? = when (s) {
    is UpdState.Available -> s.rel
    is UpdState.Downloading -> s.rel
    is UpdState.Installing -> s.rel
    is UpdState.Failed -> s.rel
    else -> null
}

@Composable
private fun UpdateBody(s: UpdState, current: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (s) {
            is UpdState.Downloading -> {
                Text("Downloading… ${(s.progress * 100).toInt()}%")
                LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
            }
            is UpdState.Installing -> {
                Text("Installing. Android may ask you to confirm; the app restarts as the new version.")
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is UpdState.Failed -> Text(s.msg)
            is UpdState.Available -> {
                Text("You have v$current.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (s.rel.notes.isNotBlank()) Text(s.rel.notes, Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyMedium)
            }
            else -> {}
        }
    }
}

/** "App updates" card on the System tab: version, check button, and the update itself. */
@Composable
fun UpdateCard() {
    val ctx = LocalContext.current
    val u = ctx.updater
    val st by u.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val sub = when (val s = st) {
        UpdState.Checking -> "checking…"
        UpdState.UpToDate -> "you have the latest version"
        is UpdState.Available -> "v${s.rel.version} is ready"
        is UpdState.Downloading -> "downloading v${s.rel.version}… ${(s.progress * 100).toInt()}%"
        is UpdState.Installing -> "installing v${s.rel.version}…"
        is UpdState.Failed -> s.msg
        UpdState.Idle -> "updates come from GitHub until the app is on the Play Store"
    }
    SectionCard("App updates", sub = "v${u.current} · $sub") {
        (st as? UpdState.Downloading)?.let { LinearProgressIndicator(progress = { it.progress }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (val s = st) {
                is UpdState.Available -> FilledTonalButton(onClick = { startUpdate(ctx, scope, s.rel) }) { Text("Update to v${s.rel.version}") }
                is UpdState.Failed -> if (s.rel != null) FilledTonalButton(onClick = { startUpdate(ctx, scope, s.rel) }) { Text("Try again") }
                    else FilledTonalButton(onClick = { scope.launch { u.check() } }) { Text("Check for update") }
                is UpdState.Downloading, is UpdState.Installing, UpdState.Checking -> {}
                else -> FilledTonalButton(onClick = { scope.launch { u.check() } }) { Text("Check for update") }
            }
        }
    }
}
