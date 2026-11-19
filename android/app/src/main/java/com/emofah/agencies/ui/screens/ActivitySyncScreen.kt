package com.emofah.agencies.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.emofah.agencies.ui.components.EmofahBadgeTone
import com.emofah.agencies.ui.components.EmofahCard
import com.emofah.agencies.ui.components.EmofahEmptyState
import com.emofah.agencies.ui.components.EmofahFeedbackBanner
import com.emofah.agencies.ui.components.EmofahInteractiveCard
import com.emofah.agencies.ui.components.EmofahLoadingButton
import com.emofah.agencies.ui.components.EmofahMetricTile
import com.emofah.agencies.ui.components.EmofahSectionHeader
import com.emofah.agencies.ui.components.EmofahStatusBadge
import com.emofah.agencies.ui.theme.emofahColors
import com.emofah.agencies.ui.theme.tabular
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

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
    val colors = MaterialTheme.emofahColors
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var inspectedSaleClientId by remember { mutableStateOf<String?>(null) }
    var showReversalForm by remember { mutableStateOf(false) }
    var reversalReasonInput by remember { mutableStateOf("") }
    var previewedReceipt by remember { mutableStateOf<DigitalReceiptData?>(null) }
    var auditViewState by remember { mutableStateOf(AuditTrailViewState()) }

    val checkpointFormat = remember { SimpleDateFormat("dd MMM · HH:mm", Locale.getDefault()) }
    val queueTimeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

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
    val queueAttentionCount = summary.pendingCount + summary.conflictCount + summary.authBlockedCount

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "activity_health_header") {
            val hasAttention = summary.conflictCount > 0 || summary.authBlockedCount > 0
            val containerColor = when {
                hasAttention -> colors.dangerContainer
                summary.isOnline -> colors.successContainer
                else -> colors.warningContainer
            }
            val accentColor = when {
                hasAttention -> colors.danger
                summary.isOnline -> colors.success
                else -> colors.warning
            }

            EmofahCard(
                containerColor = containerColor,
                borderColor = accentColor.copy(alpha = 0.28f),
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
                            tint = accentColor,
                        )
                        Column {
                            Text(
                                text = if (summary.isOnline) "Connected" else "Offline Mode",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary,
                            )
                            Text(
                                text = summary.statusHeadline,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textPrimary,
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = onSyncNow,
                        enabled = !isSyncing,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.4f)),
                    ) {
                        Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (isSyncing) "Syncing" else "Sync", maxLines = 1)
                    }
                }

                checkpoint.lastSuccessEpochMs?.let { ts ->
                    val formatted = remember(ts) { checkpointFormat.format(Date(ts)) }
                    Text(
                        text = "Last successful sync: $formatted",
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = colors.textMuted,
                    )
                }

                checkpoint.lastMessage?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textMuted,
                    )
                }
            }
        }

        lastActionMessage?.let { msg ->
            item(key = "activity_last_action") {
                EmofahFeedbackBanner(
                    title = "Activity Update",
                    message = msg,
                    tone = EmofahBadgeTone.INFO,
                )
            }
        }

        if (selectedTabIndex == 0 && (summary.conflictCount > 0 || summary.authBlockedCount > 0)) {
            item(key = "activity_conflict_alert") {
                EmofahFeedbackBanner(
                    title = "${summary.conflictCount + summary.authBlockedCount} Sync Item(s) Need Attention",
                    message = "Open the Queue tab to review conflicts or sign-in holds.",
                    tone = EmofahBadgeTone.DANGER,
                    actionLabel = "Open Queue",
                    onAction = { selectedTabIndex = 1 },
                )
            }
        }

        item(key = "activity_tabs") {
            TabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = colors.surfaceCard,
            ) {
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
        }

        when (selectedTabIndex) {
            0 -> {
                dailySummary?.let { daily ->
                    item(key = "daily_summary_card") {
                        DailyOperationalSummaryCard(summary = daily)
                    }
                }

                item(key = "today_sales_metrics") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        EmofahMetricTile("Today Active", activeTodaySales.size.toString(), Modifier.weight(1f))
                        EmofahMetricTile(
                            label = "Net Ksh",
                            value = todayActiveTotal.roundToInt().toString(),
                            modifier = Modifier.weight(1f),
                            tone = EmofahBadgeTone.INFO,
                        )
                        EmofahMetricTile(
                            label = "Reversed",
                            value = reversedTodayCount.toString(),
                            modifier = Modifier.weight(1f),
                            tone = if (reversedTodayCount > 0) EmofahBadgeTone.WARNING else EmofahBadgeTone.NEUTRAL,
                        )
                    }
                }

                item(key = "today_sales_header") {
                    EmofahSectionHeader(title = "Today's Recorded Sales")
                }

                if (todaySales.isEmpty()) {
                    item(key = "today_sales_empty") {
                        EmofahEmptyState(
                            icon = Icons.Outlined.ReceiptLong,
                            title = "No Sales Recorded Today",
                            message = "Tap Scan or Record Sale from Home to capture today's transactions.",
                        )
                    }
                } else {
                    items(
                        items = todaySales,
                        key = { "today_${it.saleWithItems.sale.clientSaleId}" },
                    ) { record ->
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
                    item(key = "earlier_sales_header") {
                        EmofahSectionHeader(
                            title = "Earlier Sales",
                            subtitle = "${earlierSales.size} previous record(s)",
                        )
                    }
                    items(
                        items = earlierSales.take(25),
                        key = { "earlier_${it.saleWithItems.sale.clientSaleId}" },
                    ) { record ->
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
            }

            1 -> {
                item(key = "queue_counters_and_sync") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            EmofahMetricTile(
                                label = "Pending",
                                value = summary.pendingCount.toString(),
                                modifier = Modifier.weight(1f),
                                tone = if (summary.pendingCount > 0) EmofahBadgeTone.WARNING else EmofahBadgeTone.NEUTRAL,
                            )
                            EmofahMetricTile(
                                label = "Conflicts",
                                value = summary.conflictCount.toString(),
                                modifier = Modifier.weight(1f),
                                tone = if (summary.conflictCount > 0) EmofahBadgeTone.DANGER else EmofahBadgeTone.NEUTRAL,
                            )
                            EmofahMetricTile(
                                label = "Synced",
                                value = summary.syncedCount.toString(),
                                modifier = Modifier.weight(1f),
                                tone = EmofahBadgeTone.SUCCESS,
                            )
                        }

                        EmofahLoadingButton(
                            text = "Sync Pending Items Now",
                            loadingText = "Syncing Queue...",
                            isLoading = isSyncing,
                            icon = Icons.Outlined.Sync,
                            onClick = onSyncNow,
                        )
                    }
                }

                if (summary.authBlockedCount > 0 || authState.status == AuthStatus.SESSION_EXPIRED) {
                    item(key = "queue_auth_hold") {
                        EmofahFeedbackBanner(
                            title = "Authentication Hold (${summary.authBlockedCount} item(s))",
                            message = "Sign in again to resume syncing paused records.",
                            tone = EmofahBadgeTone.DANGER,
                            actionLabel = "Sign In",
                            onAction = onOpenAuthDialog,
                        )
                    }
                }

                if (summary.conflictEvents.isNotEmpty()) {
                    item(key = "queue_conflicts_header") {
                        EmofahSectionHeader(
                            title = "Conflicts Requiring Review",
                            subtitle = "Resolve or retry items rejected by the server",
                        )
                    }
                    items(
                        items = summary.conflictEvents,
                        key = { "conflict_${it.eventId}" },
                    ) { conflict ->
                        val category = ConflictPolicy.classifyQueueConflict(conflict)
                        val canResolveConflictReversal = RolePermissionPolicy.hasPermission(
                            staffRole,
                            EmofahPermission.RESOLVE_SYNC_CONFLICT_REVERSAL,
                        )
                        EmofahCard(
                            containerColor = colors.dangerContainer,
                            borderColor = colors.danger.copy(alpha = 0.28f),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = colors.danger)
                                Text(
                                    text = "${conflict.eventType} · ${conflict.entityId.take(10)}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.danger,
                                )
                            }
                            Text(
                                text = "${category.name}: ${conflict.failureReason ?: "Server rejected update."}",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textPrimary,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = { onRetryConflictAfterRestock(conflict) },
                                    enabled = !isSyncing,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Retry Sync", maxLines = 1)
                                }
                                if (conflict.eventType == "SALE" && canResolveConflictReversal) {
                                    Button(
                                        onClick = { onReverseConflictLocally(conflict) },
                                        enabled = !isSyncing,
                                        shape = RoundedCornerShape(12.dp),
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
                                        tint = colors.textMuted,
                                    )
                                    Text(
                                        text = "Local reversal requires Supervisor or Owner/Manager sign-in.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.textMuted,
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "queue_pending_header") {
                    EmofahSectionHeader(title = "Pending Queue (${summary.actionableEvents.size})")
                }

                if (summary.actionableEvents.isEmpty()) {
                    item(key = "queue_pending_empty") {
                        EmofahEmptyState(
                            icon = Icons.Outlined.CloudDone,
                            title = "Queue Clear",
                            message = "No queued items waiting to upload.",
                        )
                    }
                } else {
                    items(
                        items = summary.actionableEvents,
                        key = { "pending_${it.eventId}" },
                    ) { evt ->
                        QueueEventCard(
                            event = evt,
                            timeFormat = queueTimeFormat,
                            canRetry = !isSyncing,
                            onRetry = { onRetryEvent(evt) },
                        )
                    }
                }

                if (summary.recentSyncedEvents.isNotEmpty()) {
                    item(key = "queue_recent_header") {
                        EmofahSectionHeader(title = "Recently Synced (${summary.recentSyncedEvents.size})")
                    }
                    items(
                        items = summary.recentSyncedEvents,
                        key = { "synced_${it.eventId}" },
                    ) { evt ->
                        QueueEventCard(
                            event = evt,
                            timeFormat = queueTimeFormat,
                            canRetry = false,
                            onRetry = {},
                        )
                    }
                }

                item(key = "queue_settings_cards") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        StaffAuthStatusCard(
                            authState = authState,
                            isOnline = summary.isOnline,
                            pendingSyncCount = summary.pendingCount + summary.authBlockedCount,
                            onOpenSignIn = onOpenAuthDialog,
                            onRefreshSession = onRefreshSession,
                            onSignOut = onSignOut,
                        )
                        NotificationSettingsCard()
                        ReliabilityRecoveryCard(
                            snapshot = reliabilitySnapshot,
                            isRunning = isRunningRecovery,
                            statusMessage = recoveryStatusMessage,
                            onRunRecovery = onRunRecoveryCleanup,
                        )
                    }
                }
            }

            else -> {
                auditTrailItems(
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
                    viewState = auditViewState,
                    onViewStateChange = { auditViewState = it },
                )
            }
        }
    }

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
                    fontWeight = FontWeight.Bold,
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
                            style = MaterialTheme.typography.labelSmall.tabular(),
                            color = colors.textMuted,
                        )
                    }

                    HorizontalDivider(color = colors.borderSubtle)

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
                                style = MaterialTheme.typography.bodySmall.tabular(),
                            )
                            if (itemOverridden) {
                                Text(
                                    text = "Price override from Ksh ${item.originalSellPrice.roundToInt()} (${item.priceOverrideReason ?: "Authorized"})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.warning,
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = colors.borderSubtle)

                    Text(
                        text = "Total: Ksh ${sale.totalAmount.roundToInt()} · ${inspectedRecord.paymentLabel}",
                        style = MaterialTheme.typography.bodyMedium.tabular(),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Recorded by ${sale.actorId} on ${sale.deviceId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textMuted,
                    )
                    Text(
                        text = buildString {
                            append("Sale ID: ${sale.clientSaleId.take(12)}")
                            if (sale.serverSaleId != null) {
                                append(" · Server #${sale.serverSaleId}")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = colors.textMuted,
                    )

                    if (sale.isReversed) {
                        EmofahFeedbackBanner(
                            title = "Sale Reversed (Original Preserved)",
                            message = "Reason: ${sale.reversalReason ?: "Authorized reversal"}",
                            tone = EmofahBadgeTone.DANGER,
                        )
                    } else if (!canReverseRole) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = colors.textMuted,
                            )
                            Text(
                                text = "Reversing a completed sale requires Supervisor or Owner/Manager authorization (Current role: ${staffRole.label}).",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textMuted,
                            )
                        }
                    } else if (showReversalForm) {
                        HorizontalDivider(color = colors.borderSubtle)
                        Text(
                            text = "Authorised Sale Reversal",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = colors.danger,
                        )
                        Text(
                            text = "The original transaction stays in history, stock (${inspectedRecord.totalQuantity} unit(s)) is restored, and an accounting/audit entry is logged.",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textMuted,
                        )
                        OutlinedTextField(
                            value = reversalReasonInput,
                            onValueChange = { reversalReasonInput = it },
                            label = { Text("Reason for reversal *") },
                            placeholder = { Text("e.g. Wrong variant / customer cancelled") },
                            singleLine = false,
                            maxLines = 3,
                            shape = RoundedCornerShape(12.dp),
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
                                colors = ButtonDefaults.buttonColors(containerColor = colors.danger),
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
    val colors = MaterialTheme.emofahColors
    val isReversed = record.saleWithItems.sale.isReversed
    EmofahInteractiveCard(
        onClick = onOpenDetails,
        containerColor = if (isReversed) colors.surfaceMuted else colors.surfaceCard,
        contentPadding = PaddingValues(14.dp),
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
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Ksh ${record.totalAmount.roundToInt()}",
                style = MaterialTheme.typography.titleSmall.tabular(),
                fontWeight = FontWeight.Bold,
                textDecoration = if (isReversed) TextDecoration.LineThrough else TextDecoration.None,
                color = if (isReversed) colors.textMuted else MaterialTheme.colorScheme.primary,
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
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            SaleSyncStatusBadge(record.syncBadge)
        }
    }
}

@Composable
private fun SaleSyncStatusBadge(badge: SaleHistorySyncBadge) {
    val tone = when (badge) {
        SaleHistorySyncBadge.SERVER_CONFIRMED -> EmofahBadgeTone.SUCCESS
        SaleHistorySyncBadge.LOCAL_PENDING -> EmofahBadgeTone.WARNING
        SaleHistorySyncBadge.CONFLICT, SaleHistorySyncBadge.FAILED -> EmofahBadgeTone.DANGER
        SaleHistorySyncBadge.REVERSED -> EmofahBadgeTone.NEUTRAL
    }
    EmofahStatusBadge(text = badge.label, tone = tone)
}

@Composable
private fun QueueEventCard(
    event: SyncQueueEventEntity,
    timeFormat: SimpleDateFormat,
    canRetry: Boolean,
    onRetry: () -> Unit,
) {
    val colors = MaterialTheme.emofahColors
    val created = remember(event.createdAtEpochMs) {
        timeFormat.format(Date(event.createdAtEpochMs))
    }
    EmofahCard(contentPadding = PaddingValues(13.dp)) {
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
                    text = "${event.eventType} · ${event.entityId.take(12)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Text(
                    text = "Status: ${event.status} · Retries: ${event.retryCount} · $created",
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = colors.textMuted,
                )
                event.failureReason?.let { reason ->
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.danger,
                    )
                }
            }

            if (canRetry && event.status != "SYNCED" && event.status != "RESOLVED_LOCALLY") {
                OutlinedButton(
                    onClick = onRetry,
                    shape = RoundedCornerShape(10.dp),
                ) {
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
    val colors = MaterialTheme.emofahColors
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

    EmofahCard {
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
                    color = colors.textPrimary,
                )
                Text(
                    text = if (systemEnabled) "Device alerts active" else "Notification permission disabled",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (systemEnabled) colors.success else colors.danger,
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
                shape = RoundedCornerShape(12.dp),
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

@Composable
private fun NotificationSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.emofahColors
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
                color = colors.textPrimary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textMuted,
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
    val colors = MaterialTheme.emofahColors
    val totalMb = remember(snapshot?.totalCacheBytes) {
        val bytes = snapshot?.totalCacheBytes ?: 0L
        String.format(Locale.getDefault(), "%.2f MB", bytes / (1024.0 * 1024.0))
    }

    EmofahCard {
        EmofahSectionHeader(title = "Storage & Recovery Guard")
        Text(
            text = if (snapshot == null) {
                "Checking local queue integrity and cached scan storage..."
            } else {
                "Cache used: $totalMb · Active protected assets: ${snapshot.protectedAssetCount} · In-flight: ${snapshot.inFlightCount}"
            },
            style = MaterialTheme.typography.bodySmall.tabular(),
            color = colors.textMuted,
        )
        if (snapshot != null && (snapshot.dependencyBlockedCount > 0 || snapshot.malformedCount > 0)) {
            Text(
                text = "Detected ${snapshot.dependencyBlockedCount} dependency-blocked and ${snapshot.malformedCount} malformed item(s). Run self-check to quarantine safely.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.danger,
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
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (isRunning) "Running Self-Check..." else "Run Self-Check & Clean Cache")
        }
    }
}
