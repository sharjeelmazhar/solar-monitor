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
import com.solarmonitor.app.ui.theme.NumberStyle

@Composable
fun SectionCard(
    title: String?, modifier: Modifier = Modifier, action: (@Composable RowScope.() -> Unit)? = null,
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
                    if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    else Spacer(Modifier.weight(1f))
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
        if (smallValue) Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        else BigValue(value)
        Spacer(Modifier.height(2.dp))
        Text(line1, style = MaterialTheme.typography.bodySmall.merge(NumberStyle), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(line2, style = MaterialTheme.typography.bodySmall.merge(NumberStyle), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (progress != null) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant)) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(progressColor))
            }
        }
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 11.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.titleLarge.merge(NumberStyle), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Lays children out in rows of [columns] equal-width cells. */
@Composable
fun Grid(columns: Int, items: List<@Composable (Modifier) -> Unit>, spacing: Int = 10) {
    Column(verticalArrangement = Arrangement.spacedBy(spacing.dp)) {
        items.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { it(Modifier.weight(1f)) }
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
