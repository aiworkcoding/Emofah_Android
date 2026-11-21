package com.emofah.agencies.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emofah.agencies.audit.AuditAccountabilityPolicy
import com.emofah.agencies.audit.AuditCategory
import com.emofah.agencies.audit.AuditRecordUi
import com.emofah.agencies.audit.AuditSourceBadge
import com.emofah.agencies.audit.AuditSyncFilter
import com.emofah.agencies.auth.EmofahPermission
import com.emofah.agencies.auth.RolePermissionPolicy
import com.emofah.agencies.data.local.entities.LocalAuditEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import com.emofah.agencies.sales.StaffRole
import com.emofah.agencies.ui.components.EmofahAnimatedVisibility
import com.emofah.agencies.ui.components.EmofahBadgeTone
import com.emofah.agencies.ui.components.EmofahCard
import com.emofah.agencies.ui.components.EmofahEmptyState
import com.emofah.agencies.ui.components.EmofahFeedbackBanner
import com.emofah.agencies.ui.components.EmofahMetricTile
import com.emofah.agencies.ui.components.EmofahSearchField
import com.emofah.agencies.ui.components.EmofahStatusBadge
import com.emofah.agencies.ui.theme.emofahColors
import com.emofah.agencies.ui.theme.tabular
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AuditTrailViewState(
    val selectedCategory: AuditCategory = AuditCategory.ALL,
    val syncFilter: AuditSyncFilter = AuditSyncFilter.ALL,
    val searchQuery: String = "",
    val expandedAuditId: String? = null,
)

/**
 * Emits the Audit Trail controls and records directly inside an outer LazyColumn
 * so long audit logs are fully virtualized without nested vertical scrolling.
 */
fun LazyListScope.auditTrailItems(
    localAudits: List<LocalAuditEventEntity>,
    remoteAudits: List<AuditRecordUi>,
    staffRole: StaffRole,
    currentActorId: String,
    isOnline: Boolean,
    isSyncingAudits: Boolean,
    isLoadingRemoteAudits: Boolean,
    auditStatusMessage: String?,
    onSyncAuditsNow: () -> Unit,
    onLoadRemoteAudits: () -> Unit,
    viewState: AuditTrailViewState,
    onViewStateChange: (AuditTrailViewState) -> Unit,
) {
    val canInspectFullStoreAudit = RolePermissionPolicy.hasPermission(
        staffRole,
        EmofahPermission.VIEW_FULL_AUDIT_LOG,
    )
    val localMapped = AuditAccountabilityPolicy.mapLocalEntities(localAudits)
    val mergedRecords = AuditAccountabilityPolicy.mergeLocalAndRemote(localMapped, remoteAudits)
    val roleScopedRecords = AuditAccountabilityPolicy.scopeForRole(
        records = mergedRecords,
        staffRole = staffRole,
        currentActorId = currentActorId,
    )
    val summaryMetrics = AuditAccountabilityPolicy.computeMetrics(roleScopedRecords)
    val filteredRecords = AuditAccountabilityPolicy.applyFilters(
        records = roleScopedRecords,
        category = viewState.selectedCategory,
        syncFilter = viewState.syncFilter,
        searchQuery = viewState.searchQuery,
    ).take(150)

    item(key = "audit_header_controls") {
        val colors = MaterialTheme.emofahColors
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            EmofahCard(
                containerColor = colors.surfaceElevated,
                borderColor = colors.borderSubtle,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Full Accountability Ledger",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                }
                Text(
                    text = "Append-only audit records preserve before/after values, actor, device, timestamp, and reasons. Entries cannot be edited or deleted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EmofahMetricTile("Visible", summaryMetrics.totalVisible.toString(), Modifier.weight(1f))
                EmofahMetricTile(
                    label = "Pending",
                    value = summaryMetrics.pendingUploadCount.toString(),
                    modifier = Modifier.weight(1f),
                    tone = if (summaryMetrics.pendingUploadCount > 0) EmofahBadgeTone.WARNING else EmofahBadgeTone.NEUTRAL,
                )
                EmofahMetricTile(
                    label = "Synced",
                    value = summaryMetrics.confirmedCount.toString(),
                    modifier = Modifier.weight(1f),
                    tone = EmofahBadgeTone.SUCCESS,
                )
                EmofahMetricTile(
                    label = "Sensitive",
                    value = summaryMetrics.sensitiveActionsCount.toString(),
                    modifier = Modifier.weight(1f),
                    tone = if (summaryMetrics.sensitiveActionsCount > 0) EmofahBadgeTone.DANGER else EmofahBadgeTone.NEUTRAL,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onSyncAuditsNow,
                    enabled = !isSyncingAudits && isOnline,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isSyncingAudits) "Uploading..." else "Upload (${summaryMetrics.pendingUploadCount})",
                        maxLines = 1,
                    )
                }
                OutlinedButton(
                    onClick = onLoadRemoteAudits,
                    enabled = !isLoadingRemoteAudits && isOnline,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isLoadingRemoteAudits) "Loading..." else "Cloud Trail",
                        maxLines = 1,
                    )
                }
            }

            auditStatusMessage?.let { msg ->
                EmofahFeedbackBanner(
                    title = "Audit Sync",
                    message = msg,
                    tone = EmofahBadgeTone.INFO,
                )
            }

            EmofahSearchField(
                value = viewState.searchQuery,
                onValueChange = { onViewStateChange(viewState.copy(searchQuery = it)) },
                placeholder = "Filter by action, item/ID, staff, or reason",
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AuditCategory.entries.forEach { category ->
                    FilterChip(
                        selected = viewState.selectedCategory == category,
                        onClick = { onViewStateChange(viewState.copy(selectedCategory = category)) },
                        label = { Text(category.label) },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AuditSyncFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = viewState.syncFilter == filter,
                        onClick = { onViewStateChange(viewState.copy(syncFilter = filter)) },
                        label = { Text(filter.label) },
                    )
                }
            }

            if (filteredRecords.isNotEmpty()) {
                Text(
                    text = "Showing ${filteredRecords.size} immutable audit record(s)",
                    style = MaterialTheme.typography.labelSmall.tabular(),
                    color = colors.textMuted,
                )
            }
        }
    }

    if (filteredRecords.isEmpty()) {
        item(key = "audit_empty") {
            EmofahEmptyState(
                icon = Icons.Outlined.History,
                title = "No Matching Audit Events",
                message = "Adjust the category, sync status, or search filter to inspect other audit entries.",
            )
        }
    } else {
        items(
            items = filteredRecords,
            key = { it.auditId },
        ) { record ->
            val expanded = viewState.expandedAuditId == record.auditId
            AuditEventCard(
                record = record,
                expanded = expanded,
                onToggleExpand = {
                    val nextId = if (expanded) null else record.auditId
                    onViewStateChange(viewState.copy(expandedAuditId = nextId))
                },
            )
        }
    }
}

@Composable
fun AuditTrailSection(
    localAudits: List<LocalAuditEventEntity>,
    remoteAudits: List<AuditRecordUi>,
    staffRole: StaffRole,
    currentActorId: String,
    isOnline: Boolean,
    isSyncingAudits: Boolean,
    isLoadingRemoteAudits: Boolean,
    auditStatusMessage: String?,
    onSyncAuditsNow: () -> Unit,
    onLoadRemoteAudits: () -> Unit,
) {
    var viewState by remember { mutableStateOf(AuditTrailViewState()) }
    val canInspectFullStoreAudit = remember(staffRole) {
        RolePermissionPolicy.hasPermission(staffRole, EmofahPermission.VIEW_FULL_AUDIT_LOG)
    }
    val mergedRecords = remember(localAudits, remoteAudits) {
        val localMapped = AuditAccountabilityPolicy.mapLocalEntities(localAudits)
        AuditAccountabilityPolicy.mergeLocalAndRemote(localMapped, remoteAudits)
    }
    val roleScopedRecords = remember(mergedRecords, staffRole, currentActorId) {
        AuditAccountabilityPolicy.scopeForRole(
            records = mergedRecords,
            staffRole = staffRole,
            currentActorId = currentActorId,
        )
    }
    val filteredRecords = remember(roleScopedRecords, viewState.selectedCategory, viewState.syncFilter, viewState.searchQuery) {
        AuditAccountabilityPolicy.applyFilters(
            records = roleScopedRecords,
            category = viewState.selectedCategory,
            syncFilter = viewState.syncFilter,
            searchQuery = viewState.searchQuery,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EmofahCard {
            Text(
                text = "Full Accountability Ledger",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        filteredRecords.take(30).forEach { record ->
            val expanded = viewState.expandedAuditId == record.auditId
            AuditEventCard(
                record = record,
                expanded = expanded,
                onToggleExpand = {
                    val nextId = if (expanded) null else record.auditId
                    viewState = viewState.copy(expandedAuditId = nextId)
                },
            )
        }
    }
}

@Composable
private fun AuditEventCard(
    record: AuditRecordUi,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
) {
    val colors = MaterialTheme.emofahColors
    val formattedTime = remember(record.occurredAtEpochMs) {
        SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()).format(Date(record.occurredAtEpochMs))
    }
    val isPending = record.syncStatus == SyncState.LOCAL_PENDING

    EmofahCard(
        containerColor = if (record.isSensitive) colors.surfaceElevated else colors.surfaceCard,
        borderColor = if (record.isSensitive) colors.danger.copy(alpha = 0.25f) else colors.borderSubtle,
        contentPadding = PaddingValues(13.dp),
        modifier = Modifier.clickable(onClick = onToggleExpand),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = record.operation,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (record.isSensitive) colors.danger else MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            EmofahStatusBadge(
                text = if (isPending) "LOCAL PENDING" else "CONFIRMED",
                tone = if (isPending) EmofahBadgeTone.WARNING else EmofahBadgeTone.SUCCESS,
            )
        }

        Text(
            text = "${record.entityType} · ${record.entityId}",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = buildString {
                    append(record.actorId)
                    if (!record.actorRoleLabel.isNullOrBlank()) {
                        append(" (${record.actorRoleLabel})")
                    }
                    append(" · $formattedTime")
                },
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = colors.textMuted,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = colors.textMuted,
            )
        }

        record.reason?.takeIf { it.isNotBlank() }?.let { r ->
            Text(
                text = "Reason: $r",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = if (expanded) 5 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        EmofahAnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HorizontalDivider(color = colors.borderSubtle)
                Text(
                    text = "Audit ID: ${record.auditId} · Device: ${record.deviceId}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
                if (record.sourceBadge == AuditSourceBadge.CLOUD_CONFIRMED) {
                    Text(
                        text = "Source: Immutable Supabase Ledger (append-only)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                AuditAccountabilityPolicy.formatPrettyJsonSnippet(record.beforeJson)?.let { beforePretty ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Before State",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = colors.surfaceMuted,
                        border = BorderStroke(1.dp, colors.borderSubtle),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = beforePretty,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = colors.textPrimary,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }

                AuditAccountabilityPolicy.formatPrettyJsonSnippet(record.afterJson)?.let { afterPretty ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "After State",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = colors.surfaceMuted,
                        border = BorderStroke(1.dp, colors.borderSubtle),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = afterPretty,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = colors.textPrimary,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}
