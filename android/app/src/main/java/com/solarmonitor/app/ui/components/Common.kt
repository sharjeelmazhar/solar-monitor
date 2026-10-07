package com.solarmonitor.app.ui.components

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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.solarmonitor.app.ui.theme.NumberStyle

@Composable
fun SectionCard(
    title: String?, modifier: Modifier = Modifier, action: (@Composable RowScope.() -> Unit)? = null,
    info: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp).animateContentSize()) {
            if (title != null || action != null) {
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (title != null) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                            if (info != null) InfoButton(title, info)
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

/** Big number with a smaller unit, e.g. "728" + " W". */
@Composable
fun BigValue(text: String, modifier: Modifier = Modifier) {
    val m = Regex("^(-?[\\d.,]+)\\s*(.*)$").find(text)
    Text(
        buildAnnotatedString {
            if (m == null) append(text) else {
                append(m.groupValues[1])
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = MaterialTheme.typography.titleMedium.fontSize, color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                    append(" " + m.groupValues[2])
                }
            }
        },
        style = MaterialTheme.typography.headlineMedium.merge(NumberStyle), maxLines = 1, modifier = modifier,
    )
}

@Composable
fun KpiTile(
    label: String, color: Color, value: String, line1: String, line2: String, modifier: Modifier = Modifier,
    progress: Float? = null, progressColor: Color = color, smallValue: Boolean = false,
) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(color)
            Spacer(Modifier.width(8.dp))
            Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Spacer(Modifier.height(6.dp))
        if (smallValue) Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        else BigValue(value)
        Spacer(Modifier.height(2.dp))
        if (line1.isNotBlank()) Text(line1, style = MaterialTheme.typography.bodySmall.merge(NumberStyle), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (line2.isNotBlank()) Text(line2, style = MaterialTheme.typography.bodySmall.merge(NumberStyle), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (progress != null) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant)) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(progressColor))
            }
        }
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, info: String? = null, hint: String? = null) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f, fill = false))
            if (info != null) InfoButton(label, info, small = true)
        }
        Text(value, style = MaterialTheme.typography.titleLarge.merge(NumberStyle), fontWeight = FontWeight.Bold)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
