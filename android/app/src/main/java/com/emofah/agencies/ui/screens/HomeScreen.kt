package com.emofah.agencies.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PointOfSale
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emofah.agencies.auth.AuthStatus
import com.emofah.agencies.auth.AuthUiState
import com.emofah.agencies.data.SupabaseConfig
import com.emofah.agencies.data.local.entities.PendingFinancialEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import com.emofah.agencies.reporting.DailyActivitySummary
import com.emofah.agencies.ui.components.EmofahAnimatedVisibility
import com.emofah.agencies.ui.components.EmofahBadgeTone
import com.emofah.agencies.ui.components.EmofahCard
import com.emofah.agencies.ui.components.EmofahFeedbackBanner
import com.emofah.agencies.ui.components.EmofahInteractiveCard
import com.emofah.agencies.ui.components.EmofahMetricTile
import com.emofah.agencies.ui.components.EmofahSectionHeader
import com.emofah.agencies.ui.components.EmofahStatusBadge
import com.emofah.agencies.ui.components.emofahPressScale
import com.emofah.agencies.ui.navigation.EmofahDestination
import com.emofah.agencies.ui.theme.emofahColors
import com.emofah.agencies.ui.theme.tabular
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Apple & Notion inspired Home Workspace prioritizing quick daily actions,
 * M-Pesa triage, and honest operational metrics without main-thread overhead.
 */
@Composable
fun HomeScreen(
    todaySalesCount: Int,
    todaySalesTotal: Double,
    pendingSyncCount: Int,
    cachedProductCount: Int,
    onNavigate: (EmofahDestination) -> Unit,
    onOpenReceiveStock: (() -> Unit)? = null,
    onOpenScanReceipt: (() -> Unit)? = null,
    onOpenServices: (() -> Unit)? = null,
    lowStockAlertCount: Int = 0,
    outOfStockCount: Int = 0,
    dailySummary: DailyActivitySummary? = null,
    authState: AuthUiState = AuthUiState(),
    onOpenAuth: (() -> Unit)? = null,
    unlinkedMpesaInflow: PendingFinancialEventEntity? = null,
    pendingClassificationTotalCount: Int = 0,
    onUseMpesaForSale: (PendingFinancialEventEntity) -> Unit = {},
    onUseMpesaForService: (PendingFinancialEventEntity) -> Unit = {},
    onDismissMpesaInflow: (PendingFinancialEventEntity) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.emofahColors
    val totalAlerts = outOfStockCount + lowStockAlertCount
    val triageTimeFmt = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Workspace Greeting Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Daily Operations",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
                Text(
                    text = when (authState.status) {
                        AuthStatus.AUTHENTICATED ->
                            "${authState.session?.displayName ?: authState.session?.email.orEmpty()} · ${authState.activeRole.label}"
                        AuthStatus.OFFLINE_CACHED ->
                            "${authState.session?.email.orEmpty()} · Offline mode"
                        AuthStatus.SESSION_EXPIRED ->
                            "Session expired · Sign in to upload"
                        AuthStatus.UNAUTHENTICATED ->
                            "Counter workspace · Tap Staff icon to sign in"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            EmofahStatusBadge(
                text = if (pendingSyncCount == 0) "Synced" else "$pendingSyncCount queued",
                tone = if (pendingSyncCount == 0) EmofahBadgeTone.SUCCESS else EmofahBadgeTone.WARNING,
                leadingDot = true,
            )
        }

        // Top Metric Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            EmofahMetricTile(
                label = "Today's Sales",
                value = "Ksh ${todaySalesTotal.roundToInt()}",
                sublabel = "$todaySalesCount transaction(s)",
                tone = EmofahBadgeTone.INFO,
                modifier = Modifier.weight(1f),
            )
            EmofahMetricTile(
                label = "Stock Alerts",
                value = if (totalAlerts == 0) "All Clear" else "$totalAlerts item(s)",
                sublabel = if (totalAlerts == 0) "$cachedProductCount items saved" else "$outOfStockCount out · $lowStockAlertCount low",
                tone = when {
                    outOfStockCount > 0 -> EmofahBadgeTone.DANGER
                    lowStockAlertCount > 0 -> EmofahBadgeTone.WARNING
                    else -> EmofahBadgeTone.NEUTRAL
                },
                modifier = Modifier.weight(1f),
            )
        }

        // Shared Pending-Classification Triage Card
        EmofahAnimatedVisibility(visible = unlinkedMpesaInflow != null) {
            unlinkedMpesaInflow?.let { inflow ->
                val isInflow = inflow.direction.equals("INFLOW", ignoreCase = true)
                val directionLabel = when (inflow.direction.uppercase()) {
                    "INFLOW" -> "Received"
                    "OUTFLOW" -> "Sent"
                    else -> "Transfer"
                }
                val isServerSaved = inflow.syncStatus == SyncState.SERVER_CONFIRMED
                EmofahCard(
                    containerColor = colors.infoContainer,
                    borderColor = colors.info.copy(alpha = 0.34f),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = colors.info.copy(alpha = 0.14f),
                                modifier = Modifier.size(34.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Outlined.AccountBalanceWallet,
                                        contentDescription = null,
                                        tint = colors.info,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = "$directionLabel · Ksh ${inflow.amount.roundToInt()}",
                                    style = MaterialTheme.typography.titleSmall.tabular(),
                                    fontWeight = FontWeight.Bold,
                                    color = colors.textPrimary,
                                )
                                Text(
                                    text = buildString {
                                        append(if (isInflow) "From " else "To ")
                                        append(inflow.counterparty?.takeIf { it.isNotBlank() } ?: "Counterparty")
                                        inflow.transactionReference?.takeIf { it.isNotBlank() }?.let {
                                            append(" · Ref $it")
                                        }
                                        append(" · ${triageTimeFmt.format(Date(inflow.eventTimestampEpochMs))}")
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textMuted,
                                )
                            }
                        }
                        EmofahStatusBadge(
                            text = if (pendingClassificationTotalCount > 1) {
                                "1 of $pendingClassificationTotalCount"
                            } else {
                                "Classify"
                            },
                            tone = EmofahBadgeTone.WARNING,
                        )
                    }

                    Text(
                        text = if (isServerSaved) {
                            "Saved in Supabase · awaiting classification (excluded from Business Balance until classified)"
                        } else {
                            "Saved on phone · uploading to Supabase"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textPrimary,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { onUseMpesaForSale(inflow) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Link Product", fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { onUseMpesaForService(inflow) },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Link Service", fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (pendingClassificationTotalCount > 1) {
                            TextButton(onClick = { onNavigate(EmofahDestination.Money) }) {
                                Text("View all ($pendingClassificationTotalCount)", style = MaterialTheme.typography.labelMedium)
                            }
                        } else {
                            Spacer(Modifier.width(1.dp))
                        }
                        TextButton(onClick = { onDismissMpesaInflow(inflow) }) {
                            Text("Remove from Business", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }

        // Expired Auth Warning Banner
        if (authState.status == AuthStatus.SESSION_EXPIRED) {
            EmofahFeedbackBanner(
                title = "Please sign in again",
                message = "Sign in to upload saved work. Offline sales continue saving safely.",
                tone = EmofahBadgeTone.DANGER,
                actionLabel = "Sign In",
                onAction = { onOpenAuth?.invoke() },
            )
        }

        // Hero Primary Actions
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val scanInteraction = remember { MutableInteractionSource() }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .emofahPressScale(scanInteraction)
                    .clip(RoundedCornerShape(18.dp))
                    .clickable(
                        interactionSource = scanInteraction,
                        indication = ripple(),
                        onClick = { onNavigate(EmofahDestination.Scan) },
                    ),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 3.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f),
                            modifier = Modifier.size(42.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.CameraAlt,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "Scan Product",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Text(
                                text = "Point camera to identify item & start sale",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.86f),
                            )
                        }
                    }
                }
            }

            val saleInteraction = remember { MutableInteractionSource() }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .emofahPressScale(saleInteraction)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(
                        interactionSource = saleInteraction,
                        indication = ripple(),
                        onClick = { onNavigate(EmofahDestination.RecordSale) },
                    ),
                shape = RoundedCornerShape(16.dp),
                color = colors.surfaceCard,
                border = BorderStroke(1.dp, colors.borderStrong),
                shadowElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = colors.infoContainer,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.PointOfSale,
                                contentDescription = null,
                                tint = colors.info,
                                modifier = Modifier.size(19.dp),
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Record Sale",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                        )
                        Text(
                            text = "Pick item manually from shop catalogue",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textMuted,
                        )
                    }
                }
            }
        }

        // Workspace Quick Grid
        EmofahSectionHeader(
            title = "Quick Actions",
            subtitle = "Everyday counter & store operations",
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HomeActionTile(
                    title = "Receive Stock",
                    subtitle = "Add deliveries",
                    icon = Icons.Outlined.AddBox,
                    tint = colors.success,
                    tintBg = colors.successContainer,
                    onClick = {
                        if (onOpenReceiveStock != null) {
                            onOpenReceiveStock()
                        } else {
                            onNavigate(EmofahDestination.Stock)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                HomeActionTile(
                    title = "Scan Receipt",
                    subtitle = "Stock & expenses",
                    icon = Icons.Outlined.DocumentScanner,
                    tint = colors.info,
                    tintBg = colors.infoContainer,
                    onClick = {
                        if (onOpenScanReceipt != null) {
                            onOpenScanReceipt()
                        } else {
                            onNavigate(EmofahDestination.Money)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HomeActionTile(
                    title = "Services",
                    subtitle = "Print, copy, design",
                    icon = Icons.Outlined.Print,
                    tint = MaterialTheme.colorScheme.primary,
                    tintBg = colors.infoContainer,
                    onClick = { onOpenServices?.invoke() },
                    modifier = Modifier.weight(1f),
                )
                HomeActionTile(
                    title = "Record Expense",
                    subtitle = "M-Pesa & cash",
                    icon = Icons.Outlined.ReceiptLong,
                    tint = colors.warning,
                    tintBg = colors.warningContainer,
                    onClick = { onNavigate(EmofahDestination.Money) },
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                HomeActionTile(
                    title = "View Orders",
                    subtitle = "Online orders",
                    icon = Icons.Outlined.ShoppingBag,
                    tint = colors.info,
                    tintBg = colors.infoContainer,
                    onClick = { onNavigate(EmofahDestination.Orders) },
                    modifier = Modifier.weight(1f),
                )
                HomeActionTile(
                    title = "Activity & Sync",
                    subtitle = "History & queue",
                    icon = Icons.Outlined.History,
                    tint = colors.textPrimary,
                    tintBg = colors.surfaceMuted,
                    onClick = { onNavigate(EmofahDestination.Activity) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        dailySummary?.let { summary ->
            DailyOperationalSummaryCard(summary = summary)
        }

        // Honest Store & Device Snapshot Card
        EmofahCard(
            contentPadding = PaddingValues(16.dp),
        ) {
            EmofahSectionHeader(
                title = "Shop Status",
                subtitle = "Live local & cloud overview",
            )
            HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle)
            StatusRow(
                label = "Sales recorded today",
                value = "$todaySalesCount sale(s) · Ksh ${todaySalesTotal.roundToInt()}",
            )
            StatusRow(
                label = "Waiting to upload",
                value = if (pendingSyncCount == 0) "All uploaded (0 waiting)" else "$pendingSyncCount waiting",
            )
            StatusRow(
                label = "Saved shop items",
                value = "$cachedProductCount items on phone",
            )
            StatusRow(
                label = "Stock alerts",
                value = if (outOfStockCount == 0 && lowStockAlertCount == 0) {
                    "All stocked (0 alerts)"
                } else {
                    "$outOfStockCount out · $lowStockAlertCount low"
                },
            )
            StatusRow(
                label = "Server connection",
                value = if (SupabaseConfig.isConfigured) "Configured" else "Not configured",
            )
            StatusRow(
                label = "Signed-in staff",
                value = when (authState.status) {
                    AuthStatus.AUTHENTICATED -> "${authState.session?.email.orEmpty()} (${authState.activeRole.label})"
                    AuthStatus.OFFLINE_CACHED -> "Signed in offline (${authState.session?.email.orEmpty()})"
                    AuthStatus.SESSION_EXPIRED -> "Sign in again needed"
                    AuthStatus.UNAUTHENTICATED -> "Not signed in (${authState.activeActorId})"
                },
            )
        }

        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun HomeActionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    tint: Color,
    tintBg: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.emofahColors
    EmofahInteractiveCard(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(14.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = tintBg,
            modifier = Modifier.size(34.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    val colors = MaterialTheme.emofahColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
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
            style = MaterialTheme.typography.labelMedium.tabular(),
            fontWeight = FontWeight.Medium,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
