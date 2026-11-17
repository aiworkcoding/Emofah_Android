package com.emofah.agencies.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.emofah.agencies.reporting.DailyActivitySummary
import com.emofah.agencies.reporting.DailyActivitySummaryCalculator
import android.content.Intent
import kotlin.math.roundToInt

/**
 * Stage 8.1 — Daily Operational Summary Card widget for HomeScreen & ActivitySyncScreen.
 */
@Composable
fun DailyOperationalSummaryCard(
    summary: DailyActivitySummary,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Assessment,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Today's Operational Summary",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                OutlinedButton(
                    onClick = {
                        val reportText = DailyActivitySummaryCalculator.formatSummaryReport(summary)
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Emofah Daily Operations Summary")
                            putExtra(Intent.EXTRA_TEXT, reportText)
                        }
                        val chooser = Intent.createChooser(sendIntent, "Share Summary").apply {
                            if (context !is android.app.Activity) {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        }
                        context.startActivity(chooser)
                    },
                    modifier = Modifier.height(36.dp),
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Share")
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = "Net Operational Revenue",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = "${summary.totalActiveSalesCount} sale(s) + ${summary.serviceOperationsCount} service(s)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                    }
                    Text(
                        text = "Ksh ${summary.netOperationalRevenue.roundToInt()}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            SummaryMetricRow(
                label = "Product Sales",
                value = "${summary.totalActiveSalesCount} · Ksh ${summary.totalActiveSalesAmount.roundToInt()}" +
                    if (summary.reversedSalesCount > 0) " (${summary.reversedSalesCount} reversed)" else "",
            )
            if (summary.salesPaymentBreakdown.isNotEmpty()) {
                val breakdownStr = summary.salesPaymentBreakdown.entries.joinToString(" · ") {
                    "${it.key}: Ksh ${it.value.roundToInt()}"
                }
                SummaryMetricRow(label = "Payment Methods", value = breakdownStr)
            }
            SummaryMetricRow(
                label = "Service Operations",
                value = "${summary.serviceOperationsCount} · Ksh ${summary.serviceOperationsRevenue.roundToInt()}",
            )
            if (summary.mpesaInflowTotal > 0.0 || summary.mpesaOutflowTotal > 0.0) {
                SummaryMetricRow(
                    label = "M-Pesa / Money",
                    value = "In: +Ksh ${summary.mpesaInflowTotal.roundToInt()} · Out: -Ksh ${summary.mpesaOutflowTotal.roundToInt()}",
                )
            }
            SummaryMetricRow(
                label = "Sync Health",
                value = if (summary.pendingSyncCount == 0 && summary.conflictCount == 0) {
                    "Fully synced (0 pending)"
                } else {
                    "${summary.pendingSyncCount} pending · ${summary.conflictCount} conflict(s)"
                },
            )

            Text(
                text = "Operational capture only. Accounting classification rules are managed by Desktop.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SummaryMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
