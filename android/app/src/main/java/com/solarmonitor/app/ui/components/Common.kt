package com.solarmonitor.app.ui.components

import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.solarmonitor.app.ui.theme.NumberStyle

@Composable
fun SectionCard(
    title: String?, modifier: Modifier = Modifier, action: (@Composable RowScope.() -> Unit)? = null,
    info: String? = null, sub: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp).animateContentSize()) {
            if (title != null || action != null) {
                // same header as the web CardHeader: title (+ info), a muted line under it, actions on the right
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (title != null) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                                if (info != null) InfoButton(title, info, small = true)
                            }
                            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else Spacer(Modifier.weight(1f))
                    action?.invoke(this)
                }
            }
            content()
        }
    }
}

@Composable
fun Dot(color: Color, size: Int = 10) = Box(Modifier.size(size.dp).clip(CircleShape).background(color))

/** Big number with a smaller unit, e.g. "728" + " W". The number glides to a new value (0.45 s), like the web counter. */
@Composable
fun BigValue(text: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.headlineMedium) {
    val m = Regex("""^(-?\d+(?:\.\d+)?)\s*(.*)$""").find(text)
    val target = m?.groupValues?.get(1)?.toFloatOrNull()
    val decimals = m?.groupValues?.get(1)?.substringAfter('.', "")?.length ?: 0
    val shown by androidx.compose.animation.core.animateFloatAsState(target ?: 0f, androidx.compose.animation.core.tween(450), label = "count")
    Text(
        buildAnnotatedString {
            if (m == null || target == null) append(text) else {
                append(String.format(java.util.Locale.US, "%." + decimals + "f", shown))
                if (m.groupValues[2].isNotEmpty()) withStyle(SpanStyle(fontWeight = FontWeight.Medium, fontSize = style.fontSize * 0.55f, color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                    append(" " + m.groupValues[2])
                }
            }
        },
        style = style.merge(NumberStyle).copy(fontWeight = FontWeight.SemiBold), maxLines = 1, modifier = modifier,
    )
}

/** The web "Right now" tile: coloured dot + caps label, big value, two lines, optional bar, soft colour glow in the corner. */
@Composable
fun KpiTile(
    label: String, color: Color, value: String, line1: String, line2: String, modifier: Modifier = Modifier,
    progress: Float? = null, progressColor: Color = color, smallValue: Boolean = false,
) {
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .drawBehind {
            val c = androidx.compose.ui.geometry.Offset(size.width - 16.dp.toPx(), 16.dp.toPx())   // web: 80 px blob at -24 px, blurred
            drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(color.copy(alpha = 0.25f), Color.Transparent), c, 64.dp.toPx()), 64.dp.toPx(), c)
        }) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(color, 8)
                Spacer(Modifier.width(8.dp))
                Text(label.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.06.em,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(4.dp))
            if (smallValue) Text(value, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp)
            else BigValue(value, style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp, lineHeight = 32.sp))
            if (line1.isNotBlank()) Text(line1, style = MaterialTheme.typography.bodySmall.merge(NumberStyle), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (line2.isNotBlank()) Text(line2, style = MaterialTheme.typography.bodySmall.merge(NumberStyle), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (progress != null) {
                Spacer(Modifier.height(8.dp))
                val w by androidx.compose.animation.core.animateFloatAsState(progress.coerceIn(0f, 1f), androidx.compose.animation.core.tween(700), label = "bar")
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    Box(Modifier.fillMaxWidth(w).height(6.dp).clip(CircleShape).background(progressColor))
                }
            }
        }
    }
}

/** The web Stat tile: optional colour dot, label (+ info), value with a small unit, optional hint. */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, info: String? = null, hint: String? = null, tone: Color? = null) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 22.dp)) {
            if (tone != null) { Dot(tone, 8); Spacer(Modifier.width(8.dp)) }
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f, fill = false))
            if (info != null) InfoButton(label, info, small = true)
        }
        BigValue(value, style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp))
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}


/** Small (i) button that explains something in plain language. */
@Composable
fun InfoButton(title: String, text: String, small: Boolean = false) {
    var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.material3.IconButton(onClick = { open = true }, modifier = Modifier.size(if (small) 30.dp else 36.dp)) {
        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Info, "More about $title",
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(if (small) 16.dp else 19.dp))
    }
    if (open) androidx.compose.material3.AlertDialog(
        onDismissRequest = { open = false },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { open = false }) { Text("OK") } },
        title = { Text(title) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
    )
}

/** Lays children out in rows of [columns] equal-width cells. */
@Composable
fun Grid(columns: Int, items: List<@Composable (Modifier) -> Unit>, spacing: Int = 10) {
    Column(verticalArrangement = Arrangement.spacedBy(spacing.dp)) {
        items.chunked(columns).forEach { row ->
            // equal height per row, so wrapped text in one tile doesn't leave its neighbours short
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.dp), modifier = Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                row.forEach { it(Modifier.weight(1f).fillMaxHeight()) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun InfoRows(rows: List<Pair<String, String>>) {
    Column {
        rows.forEachIndexed { i, (k, v) ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
                Text(k, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.45f))
                Text(v, style = MaterialTheme.typography.bodyMedium.merge(NumberStyle), fontWeight = FontWeight.Medium, textAlign = TextAlign.End, modifier = Modifier.weight(0.55f))
            }
        }
    }
}

/** Screen body: one column on phones, two on tablets (each card goes to the shorter column).
 *  [columns] = false keeps one centred column on tablets too (lists that read top to bottom). Use [full] for items that span the width. */
@Composable
fun ScreenList(padding: androidx.compose.foundation.layout.PaddingValues, columns: Boolean = true, spacing: androidx.compose.ui.unit.Dp = 14.dp,
               content: androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope.() -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(androidx.compose.ui.Modifier.fillMaxWidth()) {
        val side = if (!columns && maxWidth > 792.dp) (maxWidth - 760.dp) / 2 else 16.dp
        androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid(
            columns = if (columns) androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells.Adaptive(400.dp)
                      else androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells.Fixed(1),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = side, end = side,
                top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 16.dp),
            verticalItemSpacing = spacing,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

/** An item that spans every column of a [ScreenList]. */
fun androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope.full(key: Any, content: @Composable () -> Unit) =
    item(key = key, span = androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan.FullLine) { content() }

/** Every tap on the screen (position in the window, and a counter so repeated taps on one spot still count).
 *  MainActivity feeds it; [clearOnOutsideTap] uses it to drop a selection when the user taps somewhere else. */
class TapBus { val taps = kotlinx.coroutines.flow.MutableSharedFlow<androidx.compose.ui.geometry.Offset>(extraBufferCapacity = 4) }
val LocalTapBus = androidx.compose.runtime.staticCompositionLocalOf { TapBus() }

/** Watches every tap in the window without consuming it (put on the root). */
fun Modifier.reportTaps(bus: TapBus) = this.pointerInput(bus) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
        bus.taps.tryEmit(down.position)
    }
}

/** Calls [onOutside] when the user taps anywhere outside this element. */
@Composable
fun Modifier.clearOnOutsideTap(onOutside: () -> Unit): Modifier {
    val bus = LocalTapBus.current
    val bounds = androidx.compose.runtime.remember { arrayOfNulls<androidx.compose.ui.geometry.Rect>(1) }
    val cb = androidx.compose.runtime.rememberUpdatedState(onOutside)
    androidx.compose.runtime.LaunchedEffect(bus) { bus.taps.collect { p -> val b = bounds[0]; if (b != null && !b.contains(p)) cb.value() } }
    return this.onGloballyPositioned { bounds[0] = it.boundsInWindow() }
}

/**
 * The web Segmented control: options in a soft rounded tray, the chosen one on a raised pill that slides over with a
 * spring (bounce 0.15, 0.4 s), exactly like the web's shared-layout animation.
 */
@Composable
fun <T> Segmented(value: T, options: List<Pair<T, String>>, onChange: (T) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val density = androidx.compose.ui.platform.LocalDensity.current
    val pos = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateMapOf<Int, Pair<Float, Float>>() }
    val sel = options.indexOfFirst { it.first == value }.coerceAtLeast(0)
    val spec = androidx.compose.animation.core.spring<androidx.compose.ui.unit.Dp>(dampingRatio = 0.72f, stiffness = 260f)
    val x by androidx.compose.animation.core.animateDpAsState(with(density) { (pos[sel]?.first ?: 0f).toDp() }, spec, label = "segX")
    val w by androidx.compose.animation.core.animateDpAsState(with(density) { (pos[sel]?.second ?: 0f).toDp() }, spec, label = "segW")
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHigh).padding(4.dp)) {
        if (pos[sel] != null) Box(Modifier.offset(x = x).width(w).height(40.dp).shadow(1.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))
            .background(cs.surfaceContainerLowest).border(1.dp, cs.outlineVariant, RoundedCornerShape(12.dp)))
        Row {
            options.forEachIndexed { i, (v, label) ->
                val on = i == sel
                Box(Modifier.height(40.dp).clip(RoundedCornerShape(12.dp))
                    .onGloballyPositioned { c -> pos[i] = c.positionInParent().x to c.size.width.toFloat() }
                    .clickable(role = androidx.compose.ui.semantics.Role.RadioButton) { onChange(v) }
                    .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1,
                        color = if (on) cs.onSurface else cs.onSurfaceVariant)
                }
            }
        }
    }
}
