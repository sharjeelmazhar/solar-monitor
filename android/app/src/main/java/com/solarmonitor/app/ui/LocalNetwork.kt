package com.solarmonitor.app.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Android 17 "local network" permission (shown as Nearby devices). Without it the phone can't talk to the monitor. */
const val LAN_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

fun lanAllowed(ctx: Context) = Build.VERSION.SDK_INT < 37 || ctx.checkSelfPermission(LAN_PERMISSION) == PackageManager.PERMISSION_GRANTED

/**
 * Explains and links to the app's settings when the local-network permission was refused and the monitor can't be reached
 * (stock Android 17 still lets apps built for Android 16 through; other phones may not). Re-checked every time the app returns.
 */
@Composable
fun LocalNetworkPrompt(reachable: Boolean) {
    val ctx = LocalContext.current
    var allowed by remember { mutableStateOf(lanAllowed(ctx)) }
    var later by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) allowed = lanAllowed(ctx) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    if (allowed || later || reachable) return
    AlertDialog(
        onDismissRequest = { later = true },
        title = { Text("Allow local network access") },
        text = { Text("Android blocks this app from reaching the solar monitor on your Wi-Fi. Open the app's permissions and allow Nearby devices (local network), then come back.") },
        confirmButton = {
            TextButton(onClick = {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }
            }) { Text("Open settings") }
        },
        dismissButton = { TextButton(onClick = { later = true }) { Text("Not now") } },
    )
}
