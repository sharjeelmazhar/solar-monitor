package com.solarmonitor.app

import com.solarmonitor.app.ui.components.reportTaps
import com.solarmonitor.app.ui.components.LocalTapBus
import com.solarmonitor.app.ui.components.TapBus
import android.Manifest
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PowerOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solarmonitor.app.data.Conn
import com.solarmonitor.app.data.Repository
import com.solarmonitor.app.notify.MonitorService
import com.solarmonitor.app.ui.rememberStaleMs
import com.solarmonitor.app.ui.screens.EnergyScreen
import com.solarmonitor.app.ui.screens.HistoryScreen
import com.solarmonitor.app.ui.screens.LiveScreen
import com.solarmonitor.app.ui.screens.NoticesScreen
import com.solarmonitor.app.ui.screens.OutagesScreen
import com.solarmonitor.app.ui.screens.SettingsScreen
import com.solarmonitor.app.ui.screens.SystemScreen
import com.solarmonitor.app.ui.theme.LocalEnergy
import com.solarmonitor.app.ui.theme.SolarTheme
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Phones stay upright even when auto-rotate is on; tablets and unfolded foldables (smallest side 600 dp+) turn freely.
        requestedOrientation = if (resources.configuration.smallestScreenWidthDp < 600) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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

private enum class Overlay { None, Settings, Notices }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(repo: Repository) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.Live) }
    var overlay by rememberSaveable { mutableStateOf(Overlay.None) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val toast: (String) -> Unit = { m -> scope.launch { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(m) } }
    val info by repo.info.collectAsStateWithLifecycle()
    val unseen by repo.prefs.unseen.collectAsStateWithLifecycle()
    val haze = rememberHazeState()

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

    BackHandler(overlay != Overlay.None) { overlay = Overlay.None }
    BackHandler(overlay == Overlay.None && tab != Tab.Live) { tab = Tab.Live }

    val taps = remember { TapBus() }
    androidx.compose.runtime.CompositionLocalProvider(LocalTapBus provides taps) {
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).reportTaps(taps)) {
        val wide = maxWidth >= 600.dp
        val showBar = !wide && overlay == Overlay.None
        Row(Modifier.fillMaxSize()) {
            if (wide && overlay == Overlay.None) {
                NavigationRail(Modifier.padding(top = 24.dp), containerColor = MaterialTheme.colorScheme.surface) {
                    Tab.entries.forEach { t -> NavigationRailItem(selected = tab == t, onClick = { tab = t }, icon = { Icon(t.icon, null) }, label = { Text(t.label) }) }
                }
            }
            Scaffold(
                containerColor = MaterialTheme.colorScheme.surface,
                snackbarHost = { SnackbarHost(snack, Modifier.padding(bottom = if (showBar) 84.dp else 0.dp)) },
                topBar = {
                    TopAppBar(
                        title = {
                            if (overlay != Overlay.None) Text(if (overlay == Overlay.Settings) "Settings" else "Notifications", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            else Row(verticalAlignment = Alignment.CenterVertically) {
                                // same header as the web: sun logo, name, and the monitor's clock under it
                                Logo()
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(info?.name?.takeIf { it.isNotBlank() && it != "Solar" } ?: "Solar Monitor", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, lineHeight = 22.sp))
                                    HeaderClock(repo)
                                }
                            }
                        },
                        navigationIcon = { if (overlay != Overlay.None) IconButton(onClick = { overlay = Overlay.None }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                        actions = {
                            if (overlay == Overlay.None) {
                                StatusPill(repo)
                                IconButton(onClick = { overlay = Overlay.Notices }) {
                                    BadgedBox(badge = { if (unseen > 0) Badge { Text(if (unseen > 9) "9+" else "$unseen") } }) {
                                        Icon(Icons.Rounded.Notifications, "Notifications" + if (unseen > 0) ", $unseen new" else "")
                                    }
                                }
                                IconButton(onClick = { overlay = Overlay.Settings }) { Icon(Icons.Rounded.Settings, "Settings") }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface, scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
                    )
                },
            ) { p ->
                // screens scroll under the floating bar; leave room so the last item can be scrolled above it
                val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                val pad = PaddingValues(top = p.calculateTopPadding(), bottom = if (showBar) nav + 92.dp else p.calculateBottomPadding())
                Box(Modifier.fillMaxSize().hazeSource(haze)) {
                    when (overlay) {
                        Overlay.Settings -> SettingsScreen(repo, pad, toast)
                        Overlay.Notices -> NoticesScreen(repo, pad)
                        Overlay.None -> AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
                            when (t) {
                                Tab.Live -> LiveScreen(repo, pad, wide)
                                Tab.History -> HistoryScreen(repo, pad)
                                Tab.Energy -> EnergyScreen(repo, pad)
                                Tab.Outages -> OutagesScreen(repo, pad)
                                Tab.System -> SystemScreen(repo, pad, toast)
                            }
                        }
                    }
                }
            }
        }
        if (showBar) FloatingBar(tab, { tab = it }, haze, Modifier.align(Alignment.BottomCenter))
    }
    }
}

/** Floating capsule tab bar in Apple's "Liquid Glass" style: about 3/4 of the screen wide, almost clear (no tint, no gloss),
 *  the page behind only softened by a light blur, with a thin specular rim that catches light at the top-left and bottom-right. */
@Composable
private fun FloatingBar(tab: Tab, onSelect: (Tab) -> Unit, haze: dev.chrisbanes.haze.HazeState, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    val tint = (if (dark) Color.Black else Color.White).copy(alpha = if (dark) 0.10f else 0.08f)   // barely there: clear glass, not frosted
    val shape = RoundedCornerShape(50)
    val rim = androidx.compose.ui.graphics.Brush.linearGradient(listOf(
        Color.White.copy(alpha = if (dark) 0.45f else 0.95f), Color.White.copy(alpha = if (dark) 0.06f else 0.20f), Color.White.copy(alpha = if (dark) 0.30f else 0.70f)))
    Box(
        modifier
            .padding(WindowInsets.navigationBars.asPaddingValues())
            .padding(bottom = 12.dp)
            .fillMaxWidth(0.75f)
            .widthIn(max = 440.dp)
            .shadow(18.dp, shape, ambientColor = Color.Black.copy(alpha = 0.22f), spotColor = Color.Black.copy(alpha = 0.22f))
            .clip(shape)
            .hazeBlur(HazeInput.Sources(haze), HazeBlurStyle {
                blurRadius(9.dp)   // light blur: the page stays recognisable through the glass
                colorEffects(listOf(HazeColorEffect.tint(tint)))
                fallbackColorEffect(HazeColorEffect.tint(cs.surfaceContainer.copy(alpha = 0.96f)))
            })
            .border(1.2.dp, rim, shape)
            // the light bending at the glass edge: a soft bright band just inside the rim, strongest top and bottom
            .padding(1.2.dp)
            .border(3.dp, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.16f else 0.40f), Color.White.copy(alpha = 0f), Color.White.copy(alpha = if (dark) 0.08f else 0.22f))), shape)
            .padding(5.dp),
    ) {
        // one highlight that slides between tabs with a soft spring (same motion as the web bar: bounce 0.15, 0.4 s)
        BoxWithConstraints(Modifier.fillMaxWidth().height(56.dp)) {
            val gap = 2.dp
            val itemW = (maxWidth - gap * (Tab.entries.size - 1)) / Tab.entries.size
            val x by androidx.compose.animation.core.animateDpAsState(
                (itemW + gap) * tab.ordinal,
                androidx.compose.animation.core.spring(dampingRatio = 0.72f, stiffness = 260f), label = "tabPill",
            )
            Box(Modifier.offset(x = x).width(itemW).fillMaxHeight().clip(RoundedCornerShape(50)).background((if (dark) Color.White else Color.Black).copy(alpha = if (dark) 0.14f else 0.07f))
                .border(1.dp, Color.White.copy(alpha = if (dark) 0.18f else 0.6f), RoundedCornerShape(50)))
        Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
            Tab.entries.forEach { t ->
                val on = t == tab
                val fg by animateColorAsState(if (on) cs.onSecondaryContainer else cs.onSurfaceVariant, label = "tabFg")
                Column(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(50))
                        .clickable(remember { MutableInteractionSource() }, indication = androidx.compose.material3.ripple()) { onSelect(t) }
                        .semantics { role = Role.Tab; selected = on; contentDescription = t.label },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Icon(t.icon, null, tint = fg, modifier = Modifier.size(21.dp))
                    Spacer(Modifier.height(2.dp))
                    // fixed size: with a large system font the labels would be cut ("Histor"), so they do not scale (like iOS tab bars)
                    Text(t.label, color = fg, fontSize = (11f / androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)).sp, softWrap = false, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
        }
    }
}

private fun Color.luminance() = 0.2126f * red + 0.7152f * green + 0.0722f * blue

@Composable
private fun StatusPill(repo: Repository) {
    val conn by repo.conn.collectAsStateWithLifecycle()
    val live by repo.live.collectAsStateWithLifecycle()
    val last by repo.lastMsg.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = android.os.SystemClock.elapsedRealtime() } }
    val e = LocalEnergy.current
    val age = if (last > 0) (now - last) / 1000 else -1
    val stale = rememberStaleMs(repo)
    val (text, color) = when {
        conn == Conn.Offline || (live != null && stale != null) -> "Offline" to e.crit
        conn != Conn.Live || live == null -> "Connecting" to MaterialTheme.colorScheme.outline
        live?.ever != true -> "No inverter" to e.crit
        live?.ok != true -> "No data" to e.crit   // short so the name and clock keep their room
        age > 10 -> "${age}s ago" to e.warn
        else -> "Live" to e.good
    }
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(7.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The web header's sun mark: a soft yellow glow on a rounded tile with the sun drawn on top. */
@Composable
private fun Logo() {
    val e = LocalEnergy.current
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(40.dp)) {
            drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(e.solar.copy(alpha = 0.28f), Color.Transparent)), size.minDimension / 2.2f)
            val s = 24.dp.toPx() / 32f
            val o = androidx.compose.ui.geometry.Offset((size.width - 32 * s) / 2, (size.height - 32 * s) / 2)
            fun p(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(o.x + x * s, o.y + y * s)
            drawCircle(e.solar, 6.5f * s, p(16f, 16f))
            val rays = listOf(16f to 2.5f, 16f to 6f, 16f to 26f, 16f to 29.5f, 2.5f to 16f, 6f to 16f, 26f to 16f, 29.5f to 16f,
                6.5f to 6.5f, 8.9f to 8.9f, 23.1f to 23.1f, 25.5f to 25.5f, 6.5f to 25.5f, 8.9f to 23.1f, 23.1f to 8.9f, 25.5f to 6.5f)
            for (i in rays.indices step 2) drawLine(e.solar, p(rays[i].first, rays[i].second), p(rays[i + 1].first, rays[i + 1].second), 2.6f * s, androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

/** "Thu 8 Oct · 11:14:38 AM" from the monitor's clock, ticking every second (the web header clock). */
@Composable
private fun HeaderClock(repo: Repository) {
    val live by repo.live.collectAsStateWithLifecycle()
    val last by repo.lastMsg.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = android.os.SystemClock.elapsedRealtime() } }
    val t = live?.t?.takeIf { it > 0 }?.let { it + (now - last).coerceAtLeast(0) } ?: System.currentTimeMillis()
    val day = java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"))
    // time first: on narrow phones the end of the line is what gets cut, and the time matters more than the date
    Text("${com.solarmonitor.app.ui.hhmmss(t)} · $day", overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall.merge(com.solarmonitor.app.ui.theme.NumberStyle),
        color = MaterialTheme.colorScheme.outline, maxLines = 1)
}
