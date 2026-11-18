package com.emofah.agencies.audit

import com.emofah.agencies.auth.EmofahPermission
import com.emofah.agencies.auth.RolePermissionPolicy
import com.emofah.agencies.data.local.entities.LocalAuditEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import com.emofah.agencies.sales.StaffRole
import org.json.JSONArray
import org.json.JSONObject

enum class AuditCategory(val label: String) {
    ALL("All"),
    SALES("Sales & Reversals"),
    STOCK("Stock & Catalogue"),
    FINANCIAL("Money & Receipts"),
    ORDERS_SERVICES("Orders & Services"),
    AUTH_SECURITY("Security & Sync"),
}

enum class AuditSyncFilter(val label: String) {
    ALL("All"),
    PENDING_ONLY("Pending Sync"),
    CONFIRMED_ONLY("Confirmed"),
}

enum class AuditSourceBadge {
    LOCAL_DEVICE,
    CLOUD_CONFIRMED,
}

data class AuditRecordUi(
    val auditId: String,
    val actorId: String,
    val actorRoleLabel: String?,
    val deviceId: String,
    val operation: String,
    val entityType: String,
    val entityId: String,
    val beforeJson: String?,
    val afterJson: String?,
    val reason: String?,
    val syncStatus: String,
    val occurredAtEpochMs: Long,
    val category: AuditCategory,
    val isSensitive: Boolean,
    val sourceBadge: AuditSourceBadge,
)

data class AuditSummaryMetrics(
    val totalVisible: Int,
    val pendingUploadCount: Int,
    val confirmedCount: Int,
    val sensitiveActionsCount: Int,
    val uniqueActorsCount: Int,
)

/**
 * Stage 9.3 — Audit & Accountability Domain Policy.
 * Enforces role-based audit trail visibility, deterministic categorization,
 * and immutable snapshot formatting.
 */
object AuditAccountabilityPolicy {

    fun classifyCategory(operation: String, entityType: String): AuditCategory {
        val op = operation.uppercase()
        val entity = entityType.uppercase()

        return when {
            op.contains("AUTH_") || op.contains("CONFLICT") || entity == "AUTH_SESSION" || entity == "SYNC_QUEUE" ->
                AuditCategory.AUTH_SECURITY
            entity == "SALE" || op.contains("SALE") || op.contains("REVERSAL") || op.contains("PRICE_OVERRIDE") ->
                AuditCategory.SALES
            entity == "ORDER" || op.contains("ORDER") || entity == "SERVICE_OPERATION" || op.contains("SERVICE") ->
                AuditCategory.ORDERS_SERVICES
            entity == "FINANCIAL_EVENT" || entity == "PURCHASE" || op.contains("MPESA") ||
                op.contains("FINANCIAL") || op.contains("PURCHASE") ->
                AuditCategory.FINANCIAL
            entity == "PRODUCT" || entity == "STOCK" || entity == "STOCK_COUNT" ||
                op.contains("STOCK") || op.contains("PRODUCT") || op.contains("RECOGNITION") ->
                AuditCategory.STOCK
            else -> AuditCategory.AUTH_SECURITY
        }
    }

    fun isSensitiveOperation(operation: String): Boolean {
        val op = operation.uppercase()
        return op.contains("OVERRIDE") ||
            op.contains("REVERS") ||
            op.contains("ADJUST") ||
            op.contains("PHYSICAL_COUNT") ||
            op.contains("STOCK_COUNT") ||
            op.contains("CONFLICT") ||
            op.contains("AUTH_") ||
            op.contains("PURCHASE") ||
            op.contains("MANUAL_FINANCIAL")
    }

    fun mapLocalEntities(entities: List<LocalAuditEventEntity>): List<AuditRecordUi> {
        return entities.map { entity ->
            AuditRecordUi(
                auditId = entity.auditId,
                actorId = entity.actorId,
                actorRoleLabel = extractRoleHint(entity.afterJson ?: entity.beforeJson),
                deviceId = entity.deviceId,
                operation = entity.operation,
                entityType = entity.entityType,
                entityId = entity.entityId,
                beforeJson = entity.beforeJson,
                afterJson = entity.afterJson,
                reason = entity.reason,
                syncStatus = entity.syncStatus,
                occurredAtEpochMs = entity.occurredAtEpochMs,
                category = classifyCategory(entity.operation, entity.entityType),
                isSensitive = isSensitiveOperation(entity.operation),
                sourceBadge = if (entity.syncStatus == SyncState.SERVER_CONFIRMED) {
                    AuditSourceBadge.CLOUD_CONFIRMED
                } else {
                    AuditSourceBadge.LOCAL_DEVICE
                },
            )
        }
    }

    /**
     * Merges local device records with cloud audit history without duplicating `auditId`,
     * keeping newest events first.
     */
    fun mergeLocalAndRemote(
        localRecords: List<AuditRecordUi>,
        remoteRecords: List<AuditRecordUi>,
    ): List<AuditRecordUi> {
        val byId = linkedMapOf<String, AuditRecordUi>()
        // Prefer local record if present unless local is pending and remote already exists
        localRecords.forEach { rec -> byId[rec.auditId] = rec }
        remoteRecords.forEach { remote ->
            val existing = byId[remote.auditId]
            if (existing == null) {
                byId[remote.auditId] = remote
            } else if (existing.syncStatus != SyncState.SERVER_CONFIRMED ||
                (existing.actorRoleLabel == null && remote.actorRoleLabel != null)
            ) {
                byId[remote.auditId] = existing.copy(
                    actorRoleLabel = existing.actorRoleLabel ?: remote.actorRoleLabel,
                    syncStatus = SyncState.SERVER_CONFIRMED,
                    sourceBadge = AuditSourceBadge.CLOUD_CONFIRMED,
                )
            }
        }
        return byId.values.sortedByDescending { it.occurredAtEpochMs }
    }

    /**
     * Supervisors & Owner/Managers inspect all device/store records;
     * Cashiers see only entries attributed to their own actor ID.
     */
    fun scopeForRole(
        records: List<AuditRecordUi>,
        staffRole: StaffRole,
        currentActorId: String,
    ): List<AuditRecordUi> {
        if (RolePermissionPolicy.hasPermission(staffRole, EmofahPermission.VIEW_FULL_AUDIT_LOG)) {
            return records
        }
        val normalizedActor = currentActorId.trim().lowercase()
        return records.filter { rec ->
            rec.actorId.trim().lowercase() == normalizedActor ||
                (rec.category == AuditCategory.AUTH_SECURITY && rec.actorId.trim().lowercase() == "guest")
        }
    }

    fun applyFilters(
        records: List<AuditRecordUi>,
        category: AuditCategory,
        syncFilter: AuditSyncFilter,
        searchQuery: String,
    ): List<AuditRecordUi> {
        val q = searchQuery.trim().lowercase()
        return records.filter { rec ->
            val matchesCategory = category == AuditCategory.ALL || rec.category == category
            val matchesSync = when (syncFilter) {
                AuditSyncFilter.ALL -> true
                AuditSyncFilter.PENDING_ONLY -> rec.syncStatus == SyncState.LOCAL_PENDING
                AuditSyncFilter.CONFIRMED_ONLY -> rec.syncStatus == SyncState.SERVER_CONFIRMED
            }
            val matchesQuery = q.isEmpty() ||
                rec.operation.lowercase().contains(q) ||
                rec.entityType.lowercase().contains(q) ||
                rec.entityId.lowercase().contains(q) ||
                rec.actorId.lowercase().contains(q) ||
                rec.deviceId.lowercase().contains(q) ||
                (rec.reason?.lowercase()?.contains(q) == true)

            matchesCategory && matchesSync && matchesQuery
        }
    }

    fun computeMetrics(records: List<AuditRecordUi>): AuditSummaryMetrics {
        return AuditSummaryMetrics(
            totalVisible = records.size,
            pendingUploadCount = records.count { it.syncStatus == SyncState.LOCAL_PENDING },
            confirmedCount = records.count { it.syncStatus == SyncState.SERVER_CONFIRMED },
            sensitiveActionsCount = records.count { it.isSensitive },
            uniqueActorsCount = records.map { it.actorId.trim().lowercase() }.distinct().size,
        )
    }

    fun formatPrettyJsonSnippet(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return runCatching {
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed).toString(2)
                trimmed.startsWith("[") -> JSONArray(trimmed).toString(2)
                else -> trimmed
            }
        }.getOrDefault(trimmed)
    }

    private fun extractRoleHint(jsonText: String?): String? {
        val trimmed = jsonText?.trim().orEmpty()
        if (!trimmed.startsWith("{")) return null
        return runCatching {
            val obj = JSONObject(trimmed)
            obj.optString("actor_role", "")
                .ifBlank { obj.optString("role", "") }
                .takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
