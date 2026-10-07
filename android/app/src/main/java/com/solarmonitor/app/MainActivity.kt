package com.solarmonitor.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PowerOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solarmonitor.app.data.Conn
import com.solarmonitor.app.data.Repository
import com.solarmonitor.app.notify.MonitorService
import com.solarmonitor.app.ui.screens.EnergyScreen
import com.solarmonitor.app.ui.screens.HistoryScreen
import com.solarmonitor.app.ui.screens.LiveScreen
import com.solarmonitor.app.ui.screens.OutagesScreen
import com.solarmonitor.app.ui.screens.SettingsScreen
import com.solarmonitor.app.ui.screens.SystemScreen
import com.solarmonitor.app.ui.theme.LocalEnergy
import com.solarmonitor.app.ui.theme.SolarTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val repo = repo
        setContent {
            val s by repo.prefs.state.collectAsStateWithLifecycle()
            val dark = when (s.theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.Transparent.toArgb()) else SystemBarStyle.light(Color.Transparent.toArgb(), Color.Transparent.toArgb())
                enableEdgeToEdge(style, style)
            }
            com.solarmonitor.app.ui.hour12 = s.hour12
            // key: re-draw every screen (incl. canvases) when the clock format changes
            androidx.compose.runtime.key(s.hour12) { SolarTheme(s.theme, s.dynamicColor) { AppRoot(repo) } }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Live("Live", Icons.Rounded.Bolt), History("History", Icons.Rounded.History), Energy("Energy", Icons.Rounded.BarChart),
    Outages("Outages", Icons.Rounded.PowerOff), System("System", Icons.Rounded.Memory)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(repo: Repository) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.Live) }
    var settings by rememberSaveable { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val toast: (String) -> Unit = { m -> scope.launch { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(m) } }
    val info by repo.info.collectAsStateWithLifecycle()

    // fast stream only while the app is on screen
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) repo.uiActive.value = true
            if (e == Lifecycle.Event.ON_STOP) repo.uiActive.value = false
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); repo.uiActive.value = false }
    }

    // first run: ask for notifications, then start background monitoring
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        repo.prefs.update { it.copy(askedNotifications = true) }
        if (granted && repo.prefs.value.background) MonitorService.start(ctx)
    }
    LaunchedEffect(Unit) {
        val p = repo.prefs.value
        if (Build.VERSION.SDK_INT >= 33 && !p.askedNotifications) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else if (p.background) MonitorService.start(ctx)
    }

    BackHandler(settings) { settings = false }
    BackHandler(!settings && tab != Tab.Live) { tab = Tab.Live }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        val wide = maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (wide && !settings) {
                NavigationRail(Modifier.padding(top = 24.dp), containerColor = MaterialTheme.colorScheme.surface) {
                    Tab.entries.forEach { t -> NavigationRailItem(selected = tab == t, onClick = { tab = t }, icon = { Icon(t.icon, null) }, label = { Text(t.label) }) }
                }
            }
            Scaffold(
                containerColor = MaterialTheme.colorScheme.surface,
                snackbarHost = { SnackbarHost(snack) },
                topBar = {
                    TopAppBar(
                        title = {
                            Text(if (settings) "Settings" else info?.name?.takeIf { it.isNotBlank() && it != "Solar" } ?: "Solar Monitor",
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        navigationIcon = { if (settings) IconButton(onClick = { settings = false }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                        actions = {
                            if (!settings) {
                                StatusPill(repo)
                                IconButton(onClick = { settings = true }) { Icon(Icons.Rounded.Settings, "Settings") }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface, scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
                    )
                },
                bottomBar = {
                    if (!wide && !settings) NavigationBar {
                        Tab.entries.forEach { t -> NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = { Icon(t.icon, null) }, label = { Text(t.label, maxLines = 1) }) }
                    }
                },
            ) { p ->
                if (settings) SettingsScreen(repo, p, toast)
                else AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
                    when (t) {
                        Tab.Live -> LiveScreen(repo, p, wide)
                        Tab.History -> HistoryScreen(repo, p)
                        Tab.Energy -> EnergyScreen(repo, p)
                        Tab.Outages -> OutagesScreen(repo, p)
                        Tab.System -> SystemScreen(repo, p)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(repo: Repository) {
    val conn by repo.conn.collectAsStateWithLifecycle()
    val live by repo.live.collectAsStateWithLifecycle()
    val last by repo.lastMsg.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = SystemClock.elapsedRealtime() } }
    val e = LocalEnergy.current
    val age = if (last > 0) (now - last) / 1000 else -1
    val stale = com.solarmonitor.app.ui.rememberStaleMs(repo)
    val (text, color) = when {
        conn == Conn.Offline || (live != null && stale != null) -> "Offline" to e.crit
        conn != Conn.Live || live == null -> "Connecting" to MaterialTheme.colorScheme.outline
        live?.ever != true -> "No inverter" to e.crit
        live?.ok != true -> "Inverter silent" to e.crit
        age > 10 -> "${age}s ago" to e.warn
        else -> "Live" to e.good
    }
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(7.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}
