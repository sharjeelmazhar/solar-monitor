package com.solarmonitor.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.solarmonitor.app.R

// Same picture and steps as the web app (web/src/components/BillGuide.tsx). The picture is an illustration of the
// 2026 IESCO bill layout with no real name, address or account number.
private val STEPS = listOf(
    "Bill month" to "the month to choose under Your bills",
    "Reading date" to "its day (8) is the Meter reading day",
    "Load" to "Sanctioned load in kW",
    "Units" to "the units of that bill",
    "Payable within due date" to "the amount of that bill",
    "FPA … @ 2.0581" to "Fuel adjustment, Rs per unit",
    "QTR. Tariff Adj." to "divide by the units: −235.72 ÷ 166 = −1.42 Rs per unit",
    "F.C Surcharge" to "divide by the units: 71.38 ÷ 166 = 0.43 Rs per unit",
    "Month / Units / Bill table" to "the last 12 months: add each one under Your bills",
)

/** Button that shows where each value is on an IESCO bill. */
@Composable
fun BillGuideButton() {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) {
        Icon(Icons.Outlined.Info, null)
        Spacer(Modifier.width(6.dp))
        Text("Where to find it on your bill")
    }
    if (!open) return
    Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth(0.94f)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp)) {
                Text("Where to find it on your bill", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Image(painterResource(R.drawable.bill_guide), "Illustration of an IESCO bill with the fields to copy highlighted",
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.FillWidth)
                Spacer(Modifier.height(12.dp))
                STEPS.forEachIndexed { i, (k, v) ->
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(24.dp).clip(CircleShape).background(Color(0xFFE5007A)), contentAlignment = Alignment.Center) {
                            Text("${i + 1}", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(k) }; append(": $v") }, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text("Illustration of the 2026 IESCO bill layout; the numbers are an example.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                TextButton(onClick = { open = false }, modifier = Modifier.align(Alignment.End)) { Text("Close") }
            }
        }
    }
}
