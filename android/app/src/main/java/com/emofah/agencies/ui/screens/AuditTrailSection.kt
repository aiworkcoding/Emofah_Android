package com.emofah.agencies.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stage 9.3 — Immutable Audit & Accountability Trail Inspector.
 * Displays locally recorded + cloud-confirmed audit events with role-scoped visibility,
 * category/sync filters, and expandable before/after state snapshots.
 */
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
    var selectedCategory by remember { mutableStateOf(AuditCategory.ALL) }
    var syncFilter by remember { mutableStateOf(AuditSyncFilter.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var expandedAuditId by remember { mutableStateOf<String?>(null) }

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

    val summaryMetrics = remember(roleScopedRecords) {
        AuditAccountabilityPolicy.computeMetrics(roleScopedRecords)
    }

    val filteredRecords = remember(roleScopedRecords, selectedCategory, syncFilter, searchQuery) {
        AuditAccountabilityPolicy.applyFilters(
            records = roleScopedRecords,
            category = selectedCategory,
            syncFilter = syncFilter,
            searchQuery = searchQuery,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Immutability & Role Accountability Header
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = if (canInspectFullStoreAudit) {
                            Icons.Outlined.Security
                        } else {
                            Icons.Outlined.Lock
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = if (canInspectFullStoreAudit) {
                            "Full Accountability Ledger (${staffRole.label})"
                        } else {
                            "My Device Audit Trail (${staffRole.label})"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = if (canInspectFullStoreAudit) {
                        "Append-only audit records preserve before/after values, actor, device, timestamp, and reasons. Entries cannot be edited or deleted."
                    } else {
                        "Showing actions attributed to $currentActorId. Store-wide cross-staff audit inspection requires Supervisor or Owner/Manager sign-in."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        // Metrics Strip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AuditMetricTile("Visible", summaryMetrics.totalVisible, Modifier.weight(1f))
            AuditMetricTile("Pending", summaryMetrics.pendingUploadCount, Modifier.weight(1f))
            AuditMetricTile("Confirmed", summaryMetrics.confirmedCount, Modifier.weight(1f))
            AuditMetricTile("Sensitive", summaryMetrics.sensitiveActionsCount, Modifier.weight(1f))
        }

        // Sync & Cloud Fetch Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onSyncAuditsNow,
                enabled = !isSyncingAudits && isOnline,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isSyncingAudits) "Uploading..." else "Upload Pending (${summaryMetrics.pendingUploadCount})",
                    maxLines = 1,
                )
            }
            OutlinedButton(
                onClick = onLoadRemoteAudits,
                enabled = !isLoadingRemoteAudits && isOnline,
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
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }

        // Search Box
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Filter by operation, SKU/ID, actor, or reason") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Category Filter Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AuditCategory.entries.forEach { category ->
                FilterChip(
                    selected = selectedCategory == category,
                    onClick = { selectedCategory = category },
                    label = { Text(category.label) },
                )
            }
        }

        // Sync State Filter Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AuditSyncFilter.entries.forEach { filter ->
                FilterChip(
                    selected = syncFilter == filter,
                    onClick = { syncFilter = filter },
                    label = { Text(filter.label) },
                )
            }
        }

        if (filteredRecords.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "No audit events match the active filters.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Text(
                text = "Showing ${filteredRecords.size} immutable audit record(s)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            filteredRecords.take(150).forEach { record ->
                AuditEventCard(
                    record = record,
                    expanded = expandedAuditId == record.auditId,
                    onToggleExpand = {
                        expandedAuditId = if (expandedAuditId == record.auditId) null else record.auditId
                    },
                )
            }
        }
    }
}

@Composable
private fun AuditMetricTile(label: String, count: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
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
    val formattedTime = remember(record.occurredAtEpochMs) {
        SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()).format(Date(record.occurredAtEpochMs))
    }
    val isPending = record.syncStatus == SyncState.LOCAL_PENDING

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpand),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (record.isSensitive) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = record.operation,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (record.isSensitive) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isPending) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                ) {
                    Text(
                        text = if (isPending) "LOCAL PENDING" else "CONFIRMED",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isPending) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            Text(
                text = "${record.entityType} · ${record.entityId}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
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
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            record.reason?.takeIf { it.isNotBlank() }?.let { r ->
                Text(
                    text = "Reason: $r",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) 5 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (expanded) {
                HorizontalDivider()
                Text(
                    text = "Audit ID: ${record.auditId} · Device: ${record.deviceId}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        text = "Before State:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = beforePretty,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }

                AuditAccountabilityPolicy.formatPrettyJsonSnippet(record.afterJson)?.let { afterPretty ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "After State:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = afterPretty,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}
