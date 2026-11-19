package com.emofah.agencies.ui.screens

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emofah.agencies.reporting.DailyActivitySummary
import com.emofah.agencies.reporting.DailyActivitySummaryCalculator
import com.emofah.agencies.ui.components.EmofahBadgeTone
import com.emofah.agencies.ui.components.EmofahCard
import com.emofah.agencies.ui.components.EmofahStatusBadge
import com.emofah.agencies.ui.theme.emofahColors
import com.emofah.agencies.ui.theme.tabular
import kotlin.math.roundToInt

/**
 * Refined Daily Operational Summary Card for HomeScreen & ActivitySyncScreen.
 */
@Composable
fun DailyOperationalSummaryCard(
    summary: DailyActivitySummary,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.emofahColors

    EmofahCard(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = colors.infoContainer,
                    modifier = Modifier.size(34.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Assessment,
                            contentDescription = null,
                            tint = colors.info,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Column {
                    Text(
                        text = "Today's Summary",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = "Revenue & operational count",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textMuted,
                    )
                }
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
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, colors.borderStrong),
                modifier = Modifier.height(36.dp),
            ) {
                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text("Share", style = MaterialTheme.typography.labelMedium)
            }
        }

        // Hero Revenue Surface
        Surface(
            color = colors.infoContainer,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, colors.info.copy(alpha = 0.22f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Net Operational Revenue",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.info,
                    )
                    Text(
                        text = "${summary.totalActiveSalesCount} sale(s) + ${summary.serviceOperationsCount} service(s)",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textMuted,
                    )
                }
                Text(
                    text = "Ksh ${summary.netOperationalRevenue.roundToInt()}",
                    style = MaterialTheme.typography.titleLarge.tabular(),
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
            }
        }

        HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle)

        SummaryMetricRow(
            label = "Product Sales",
            value = "${summary.totalActiveSalesCount} · Ksh ${summary.totalActiveSalesAmount.roundToInt()}" +
                if (summary.reversedSalesCount > 0) " (${summary.reversedSalesCount} reversed)" else "",
        )
        if (summary.salesPaymentBreakdown.isNotEmpty()) {
            val breakdownStr = summary.salesPaymentBreakdown.entries.joinToString(" · ") {
                "${it.key}: Ksh ${it.value.roundToInt()}"
            }
            SummaryMetricRow(label = "Payment Breakdown", value = breakdownStr)
        }
        SummaryMetricRow(
            label = "Service Operations",
            value = "${summary.serviceOperationsCount} · Ksh ${summary.serviceOperationsRevenue.roundToInt()}",
        )
        if (summary.mpesaInflowTotal > 0.0 || summary.mpesaOutflowTotal > 0.0) {
            SummaryMetricRow(
                label = "M-Pesa / Money",
                value = "In +Ksh ${summary.mpesaInflowTotal.roundToInt()} · Out -Ksh ${summary.mpesaOutflowTotal.roundToInt()}",
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Sync Health",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
            )
            EmofahStatusBadge(
                text = if (summary.pendingSyncCount == 0 && summary.conflictCount == 0) {
                    "Fully synced"
                } else {
                    "${summary.pendingSyncCount} waiting · ${summary.conflictCount} conflict(s)"
                },
                tone = when {
                    summary.conflictCount > 0 -> EmofahBadgeTone.DANGER
                    summary.pendingSyncCount > 0 -> EmofahBadgeTone.WARNING
                    else -> EmofahBadgeTone.SUCCESS
                },
                leadingDot = true,
            )
        }

        Text(
            text = "Operational capture only. Accounting classification is managed on Desktop.",
            style = MaterialTheme.typography.labelSmall,
            color = colors.textMuted,
        )
    }
}

@Composable
private fun SummaryMetricRow(label: String, value: String) {
    val colors = MaterialTheme.emofahColors
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
            color = colors.textMuted,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.tabular(),
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
