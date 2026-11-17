package com.emofah.agencies.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emofah.agencies.audit.AuditRecordUi
import com.emofah.agencies.auth.AuthStatus
import com.emofah.agencies.auth.AuthUiState
import com.emofah.agencies.auth.EmofahPermission
import com.emofah.agencies.auth.RolePermissionPolicy
import com.emofah.agencies.data.local.dao.LocalSaleWithItems
import com.emofah.agencies.data.local.entities.LocalAuditEventEntity
import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.sync.ConflictPolicy
import com.emofah.agencies.data.sync.OfflineStatusSummary
import com.emofah.agencies.data.sync.ReliabilityHealthSnapshot
import com.emofah.agencies.data.sync.SyncCheckpoint
import com.emofah.agencies.notifications.EmofahNotificationManager
import com.emofah.agencies.notifications.NotificationPrefs
import com.emofah.agencies.receipts.DigitalReceiptData
import com.emofah.agencies.receipts.DigitalReceiptFormatter
import com.emofah.agencies.reporting.DailyActivitySummary
import com.emofah.agencies.sales.PriceOverridePolicy
import com.emofah.agencies.sales.SaleHistoryRecordUi
import com.emofah.agencies.sales.SaleHistorySyncBadge
import com.emofah.agencies.sales.SaleReversalPolicy
import com.emofah.agencies.sales.StaffRole
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Stage 2.4 + Stage 3.6 — Sales Activity, Safe Corrections & Offline Sync Screen.
 * Displays today's recorded sales (with detail drill-down & authorised safe reversals),
 * live connectivity, pending sync queue, and conflict resolution actions.
 */
@Composable
fun ActivitySyncScreen(
    todaySales: List<SaleHistoryRecordUi>,
    earlierSales: List<SaleHistoryRecordUi>,
    staffRole: StaffRole,
    summary: OfflineStatusSummary,
    checkpoint: SyncCheckpoint,
    isSyncing: Boolean,
    isReversingSale: Boolean,
    lastActionMessage: String?,
    onSyncNow: () -> Unit,
    onRetryEvent: (SyncQueueEventEntity) -> Unit,
    onRetryConflictAfterRestock: (SyncQueueEventEntity) -> Unit,
    onReverseConflictLocally: (SyncQueueEventEntity) -> Unit,
    onReverseSale: (LocalSaleWithItems, String) -> Unit,
    dailySummary: DailyActivitySummary? = null,
    authState: AuthUiState = AuthUiState(),
    onOpenAuthDialog: () -> Unit = {},
    onRefreshSession: () -> Unit = {},
    onSignOut: () -> Unit = {},
    localAudits: List<LocalAuditEventEntity> = emptyList(),
    remoteAudits: List<AuditRecordUi> = emptyList(),
    pendingAuditCount: Int = 0,
    isSyncingAudits: Boolean = false,
    isLoadingRemoteAudits: Boolean = false,
    auditStatusMessage: String? = null,
    onSyncAuditsNow: () -> Unit = {},
    onLoadRemoteAudits: () -> Unit = {},
    reliabilitySnapshot: ReliabilityHealthSnapshot? = null,
    isRunningRecovery: Boolean = false,
    recoveryStatusMessage: String? = null,
    onRunRecoveryCleanup: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var inspectedSaleClientId by remember { mutableStateOf<String?>(null) }
    var showReversalForm by remember { mutableStateOf(false) }
    var reversalReasonInput by remember { mutableStateOf("") }
    var previewedReceipt by remember { mutableStateOf<DigitalReceiptData?>(null) }

    val allMappedSales = remember(todaySales, earlierSales) { todaySales + earlierSales }
    val inspectedRecord = remember(inspectedSaleClientId, allMappedSales) {
        inspectedSaleClientId?.let { targetId ->
            allMappedSales.firstOrNull { it.saleWithItems.sale.clientSaleId == targetId }
        }
    }

    val activeTodaySales = remember(todaySales) {
        todaySales.filterNot { it.saleWithItems.sale.isReversed }
    }
    val reversedTodayCount = remember(todaySales) {
        todaySales.count { it.saleWithItems.sale.isReversed }
    }
    val todayActiveTotal = remember(activeTodaySales) {
        activeTodaySales.sumOf { it.totalAmount }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Connectivity & Sync Health Header
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (summary.conflictCount > 0 || summary.authBlockedCount > 0) {
                    MaterialTheme.colorScheme.errorContainer
                } else if (summary.isOnline) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.tertiaryContainer
                },
            ),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
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
                        Icon(
                            imageVector = if (summary.isOnline) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff,
                            contentDescription = null,
                        )
                        Column {
                            Text(
                                text = if (summary.isOnline) "Connected" else "Offline Mode",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = summary.statusHeadline,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = onSyncNow,
                        enabled = !isSyncing,
                    ) {
                        Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (isSyncing) "Syncing" else "Sync", maxLines = 1)
                    }
                }

                checkpoint.lastSuccessEpochMs?.let { ts ->
                    val formatted = SimpleDateFormat("dd MMM · HH:mm", Locale.getDefault()).format(Date(ts))
                    Text(
                        text = "Last successful sync: $formatted",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                checkpoint.lastMessage?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        lastActionMessage?.let { msg ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        // Quick conflict alert banner when viewing Sales tab
        if (selectedTabIndex == 0 && (summary.conflictCount > 0 || summary.authBlockedCount > 0)) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectedTabIndex = 1 },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(14.dp),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(
                        text = "${summary.conflictCount + summary.authBlockedCount} sync item(s) need attention. Tap to inspect Sync Queue.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // Section Switcher: Sales vs Sync Queue vs Audit Log
        val queueAttentionCount = summary.pendingCount + summary.conflictCount + summary.authBlockedCount
        TabRow(selectedTabIndex = selectedTabIndex) {
            Tab(
                selected = selectedTabIndex == 0,
                onClick = { selectedTabIndex = 0 },
                text = { Text("Sales (${todaySales.size})", maxLines = 1) },
            )
            Tab(
                selected = selectedTabIndex == 1,
                onClick = { selectedTabIndex = 1 },
                text = {
                    val suffix = if (queueAttentionCount > 0) " ($queueAttentionCount)" else ""
                    Text("Queue$suffix", maxLines = 1)
                },
            )
            Tab(
                selected = selectedTabIndex == 2,
                onClick = { selectedTabIndex = 2 },
                text = {
                    val suffix = if (pendingAuditCount > 0) " ($pendingAuditCount)" else ""
                    Text("Audit$suffix", maxLines = 1)
                },
            )
        }

        if (selectedTabIndex == 0) {
            dailySummary?.let { daily ->
                DailyOperationalSummaryCard(summary = daily)
            }

            // Today's Sales Summary Strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QueueCountCard("Today Active", activeTodaySales.size, Modifier.weight(1f))
                QueueCountCard("Net Ksh", todayActiveTotal.roundToInt(), Modifier.weight(1f))
                QueueCountCard("Reversed", reversedTodayCount, Modifier.weight(1f))
            }

            Text(
                text = "Today's Recorded Sales",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )

            if (todaySales.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ReceiptLong,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "No sales recorded today yet. Tap Scan or Record Sale to capture transactions.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                todaySales.forEach { record ->
                    SaleHistoryCard(
                        record = record,
                        onOpenDetails = {
                            inspectedSaleClientId = record.saleWithItems.sale.clientSaleId
                            showReversalForm = false
                            reversalReasonInput = ""
                        },
                    )
                }
            }

            if (earlierSales.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Earlier Sales (${earlierSales.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                earlierSales.take(25).forEach { record ->
                    SaleHistoryCard(
                        record = record,
                        onOpenDetails = {
                            inspectedSaleClientId = record.saleWithItems.sale.clientSaleId
                            showReversalForm = false
                            reversalReasonInput = ""
                        },
                    )
                }
            }
        } else if (selectedTabIndex == 1) {
            // Queue Counters Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QueueCountCard("Pending", summary.pendingCount, Modifier.weight(1f))
                QueueCountCard("Conflicts", summary.conflictCount, Modifier.weight(1f))
                QueueCountCard("Synced", summary.syncedCount, Modifier.weight(1f))
            }

            // Full-width Manual Sync Trigger inside Queue tab
            Button(
                onClick = onSyncNow,
                enabled = !isSyncing,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) {
                Icon(Icons.Outlined.Sync, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isSyncing) "Syncing Queue..." else "Sync Pending Items Now",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (summary.authBlockedCount > 0 || authState.status == AuthStatus.SESSION_EXPIRED) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Authentication Hold (${summary.authBlockedCount} item(s))",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                text = "Sign in again to resume syncing paused records.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        Button(onClick = onOpenAuthDialog) {
                            Text("Sign In")
                        }
                    }
                }
            }

            // Conflicts Requiring Safe Review (Stage 3.5 integration)
            if (summary.conflictEvents.isNotEmpty()) {
                Text(
                    text = "Conflicts Requiring Review",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
                summary.conflictEvents.forEach { conflict ->
                    val category = ConflictPolicy.classifyQueueConflict(conflict)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.WarningAmber, contentDescription = null)
                                Text(
                                    text = "${conflict.eventType} · ${conflict.entityId.take(10)}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Text(
                                text = "${category.name}: ${conflict.failureReason ?: "Server rejected update."}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            val canResolveConflictReversal = RolePermissionPolicy.hasPermission(
                                staffRole,
                                EmofahPermission.RESOLVE_SYNC_CONFLICT_REVERSAL,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = { onRetryConflictAfterRestock(conflict) },
                                    enabled = !isSyncing,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Retry Sync", maxLines = 1)
                                }
                                if (conflict.eventType == "SALE" && canResolveConflictReversal) {
                                    Button(
                                        onClick = { onReverseConflictLocally(conflict) },
                                        enabled = !isSyncing,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text("Reverse Locally", maxLines = 1)
                                    }
                                }
                            }
                            if (conflict.eventType == "SALE" && !canResolveConflictReversal) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Lock,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(
                                        text = "Local reversal requires Supervisor or Owner/Manager sign-in.",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Pending / Retrying / Auth-Hold Events
            Text(
                text = "Pending Queue (${summary.actionableEvents.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )

            if (summary.actionableEvents.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Text(
                        text = "No queued items waiting to upload.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            } else {
                summary.actionableEvents.forEach { evt ->
                    QueueEventCard(
                        event = evt,
                        canRetry = !isSyncing,
                        onRetry = { onRetryEvent(evt) },
                    )
                }
            }

            // Recently Synced History
            if (summary.recentSyncedEvents.isNotEmpty()) {
                Text(
                    text = "Recently Synced (${summary.recentSyncedEvents.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                summary.recentSyncedEvents.forEach { evt ->
                    QueueEventCard(
                        event = evt,
                        canRetry = false,
                        onRetry = {},
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            StaffAuthStatusCard(
                authState = authState,
                isOnline = summary.isOnline,
                pendingSyncCount = summary.pendingCount + summary.authBlockedCount,
                onOpenSignIn = onOpenAuthDialog,
                onRefreshSession = onRefreshSession,
                onSignOut = onSignOut,
            )

            Spacer(Modifier.height(4.dp))
            NotificationSettingsCard()

            Spacer(Modifier.height(4.dp))
            ReliabilityRecoveryCard(
                snapshot = reliabilitySnapshot,
                isRunning = isRunningRecovery,
                statusMessage = recoveryStatusMessage,
                onRunRecovery = onRunRecoveryCleanup,
            )
        } else {
            AuditTrailSection(
                localAudits = localAudits,
                remoteAudits = remoteAudits,
                staffRole = staffRole,
                currentActorId = authState.activeActorId,
                isOnline = summary.isOnline,
                isSyncingAudits = isSyncingAudits,
                isLoadingRemoteAudits = isLoadingRemoteAudits,
                auditStatusMessage = auditStatusMessage,
                onSyncAuditsNow = onSyncAuditsNow,
                onLoadRemoteAudits = onLoadRemoteAudits,
            )
        }
    }

    // Sale Detail & Authorised Safe Reversal Modal
    if (inspectedRecord != null) {
        val sale = inspectedRecord.saleWithItems.sale
        val items = inspectedRecord.saleWithItems.items
        val canReverseRole = SaleReversalPolicy.canReverseSales(staffRole)

        AlertDialog(
            onDismissRequest = {
                if (!isReversingSale) {
                    inspectedSaleClientId = null
                    showReversalForm = false
                }
            },
            title = {
                Text(
                    text = inspectedRecord.primaryProductName,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SaleSyncStatusBadge(inspectedRecord.syncBadge)
                        Text(
                            text = inspectedRecord.formattedDateTime,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    HorizontalDivider()

                    items.forEach { item ->
                        val itemOverridden = item.originalSellPrice > 0.0 &&
                            abs(item.unitSellPrice - item.originalSellPrice) > PriceOverridePolicy.PRICE_EPSILON
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "${item.quantity} × ${item.productNameSnapshot}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Unit: Ksh ${item.unitSellPrice.roundToInt()} · Line total: Ksh ${item.lineTotal.roundToInt()}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (itemOverridden) {
                                Text(
                                    text = "Price override from Ksh ${item.originalSellPrice.roundToInt()} (${item.priceOverrideReason ?: "Authorized"})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                    }

                    HorizontalDivider()

                    Text(
                        text = "Total: Ksh ${sale.totalAmount.roundToInt()} · ${inspectedRecord.paymentLabel}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Recorded by ${sale.actorId} on ${sale.deviceId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = buildString {
                            append("Sale ID: ${sale.clientSaleId.take(12)}")
                            if (sale.serverSaleId != null) {
                                append(" · Server #${sale.serverSaleId}")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (sale.isReversed) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            ),
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "Sale Reversed (Original Record Preserved)",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    text = "Reason: ${sale.reversalReason ?: "Authorized reversal"}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    } else if (!canReverseRole) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "Reversing a completed sale requires Supervisor or Owner/Manager authorization (Current role: ${staffRole.label}).",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (showReversalForm) {
                        HorizontalDivider()
                        Text(
                            text = "Authorised Sale Reversal",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = "The original transaction stays in history, stock (${inspectedRecord.totalQuantity} unit(s)) is restored, and an accounting/audit entry is logged.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = reversalReasonInput,
                            onValueChange = { reversalReasonInput = it },
                            label = { Text("Reason for reversal *") },
                            placeholder = { Text("e.g. Wrong variant / customer cancelled") },
                            singleLine = false,
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            previewedReceipt = DigitalReceiptFormatter.buildFromSale(inspectedRecord.saleWithItems)
                        },
                        enabled = !isReversingSale,
                    ) {
                        Icon(Icons.Outlined.ReceiptLong, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Receipt")
                    }
                    if (!sale.isReversed && canReverseRole) {
                        if (!showReversalForm) {
                            OutlinedButton(
                                onClick = { showReversalForm = true },
                                enabled = !isReversingSale,
                            ) {
                                Icon(Icons.Outlined.Undo, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Reverse")
                            }
                        } else {
                            val canConfirmReversal = reversalReasonInput.trim().length >= SaleReversalPolicy.MIN_REASON_LENGTH
                            Button(
                                onClick = {
                                    onReverseSale(inspectedRecord.saleWithItems, reversalReasonInput.trim())
                                    showReversalForm = false
                                    inspectedSaleClientId = null
                                },
                                enabled = canConfirmReversal && !isReversingSale,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                ),
                            ) {
                                Text(if (isReversingSale) "Reversing..." else "Confirm Reversal")
                            }
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (!isReversingSale) {
                            inspectedSaleClientId = null
                            showReversalForm = false
                        }
                    },
                ) {
                    Text("Close")
                }
            },
        )
    }

    previewedReceipt?.let { receiptData ->
        DigitalReceiptPreviewDialog(
            receipt = receiptData,
            onDismiss = { previewedReceipt = null },
        )
    }
}

@Composable
private fun SaleHistoryCard(
    record: SaleHistoryRecordUi,
    onOpenDetails: () -> Unit,
) {
    val isReversed = record.saleWithItems.sale.isReversed
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDetails),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isReversed) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isReversed) 0.dp else 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = record.primaryProductName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (isReversed) TextDecoration.LineThrough else TextDecoration.None,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Ksh ${record.totalAmount.roundToInt()}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    textDecoration = if (isReversed) TextDecoration.LineThrough else TextDecoration.None,
                    color = if (isReversed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = buildString {
                        append("Qty ${record.totalQuantity}")
                        append(" · ${record.paymentLabel}")
                        append(" · ${if (record.isToday) record.formattedTime else record.formattedDateTime}")
                        if (record.hasPriceOverride) append(" · Override")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                SaleSyncStatusBadge(record.syncBadge)
            }
        }
    }
}

@Composable
private fun SaleSyncStatusBadge(badge: SaleHistorySyncBadge) {
    val (bgColor, fgColor) = when (badge) {
        SaleHistorySyncBadge.SERVER_CONFIRMED ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        SaleHistorySyncBadge.LOCAL_PENDING ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        SaleHistorySyncBadge.CONFLICT, SaleHistorySyncBadge.FAILED ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        SaleHistorySyncBadge.REVERSED ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bgColor,
    ) {
        Text(
            text = badge.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = fgColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun QueueCountCard(label: String, count: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QueueEventCard(
    event: SyncQueueEventEntity,
    canRetry: Boolean,
    onRetry: () -> Unit,
) {
    val created = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(event.createdAtEpochMs))
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "${event.eventType} · ${event.entityId.take(12)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Status: ${event.status} · Retries: ${event.retryCount} · $created",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                event.failureReason?.let { reason ->
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (canRetry && event.status != "SYNCED" && event.status != "RESOLVED_LOCALLY") {
                OutlinedButton(onClick = onRetry) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Retry")
                }
            }
        }
    }
}

@Composable
private fun NotificationSettingsCard() {
    val context = LocalContext.current
    val notifPrefs = remember(context) { NotificationPrefs(context) }
    var prefsState by remember(context) { mutableStateOf(notifPrefs.readState()) }
    var systemEnabled by remember(context) {
        mutableStateOf(EmofahNotificationManager.areSystemNotificationsEnabled(context))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        systemEnabled = EmofahNotificationManager.areSystemNotificationsEnabled(context)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.NotificationsActive,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Notifications & Operational Alerts",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (systemEnabled) {
                            "Device alerts active"
                        } else {
                            "Notification permission disabled on this device"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (systemEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }

            if (!systemEnabled) {
                OutlinedButton(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            !EmofahNotificationManager.hasPostNotificationsPermission(context)
                        ) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            runCatching {
                                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    if (context !is android.app.Activity) {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                }
                                context.startActivity(intent)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Allow Notifications")
                }
            }

            NotificationSwitchRow(
                title = "New Online Orders",
                subtitle = "Alert when storefront orders arrive pending action",
                checked = prefsState.ordersEnabled,
                onCheckedChange = { enabled ->
                    prefsState = notifPrefs.setOrdersEnabled(enabled)
                },
            )
            NotificationSwitchRow(
                title = "Sync Conflicts & Sign-In Holds",
                subtitle = "Alert when queued sales or stock changes need review",
                checked = prefsState.syncAlertsEnabled,
                onCheckedChange = { enabled ->
                    prefsState = notifPrefs.setSyncAlertsEnabled(enabled)
                },
            )
            NotificationSwitchRow(
                title = "Low & Out-of-Stock Alerts",
                subtitle = "Alert when active products drop to threshold or 0",
                checked = prefsState.stockAlertsEnabled,
                onCheckedChange = { enabled ->
                    prefsState = notifPrefs.setStockAlertsEnabled(enabled)
                },
            )
        }
    }
}

@Composable
private fun NotificationSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun ReliabilityRecoveryCard(
    snapshot: ReliabilityHealthSnapshot?,
    isRunning: Boolean,
    statusMessage: String?,
    onRunRecovery: () -> Unit,
) {
    val totalMb = remember(snapshot?.totalCacheBytes) {
        val bytes = snapshot?.totalCacheBytes ?: 0L
        String.format(Locale.getDefault(), "%.2f MB", bytes / (1024.0 * 1024.0))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Storage & Recovery Guard",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (snapshot == null) {
                    "Checking local queue integrity and cached scan storage..."
                } else {
                    "Cache used: $totalMb · Active protected assets: ${snapshot.protectedAssetCount} · In-flight: ${snapshot.inFlightCount}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (snapshot != null && (snapshot.dependencyBlockedCount > 0 || snapshot.malformedCount > 0)) {
                Text(
                    text = "Detected ${snapshot.dependencyBlockedCount} dependency-blocked and ${snapshot.malformedCount} malformed item(s). Run self-check to quarantine safely.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            statusMessage?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            OutlinedButton(
                onClick = onRunRecovery,
                enabled = !isRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (isRunning) "Running Self-Check..." else "Run Self-Check & Clean Cache")
            }
        }
    }
}
