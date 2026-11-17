package com.emofah.agencies.data.sync

import com.emofah.agencies.data.local.EmofahDatabase
import com.emofah.agencies.data.local.entities.LocalAuditEventEntity
import com.emofah.agencies.data.local.entities.LocalStockProjectionEntity
import com.emofah.agencies.data.local.entities.PendingFinancialEventEntity
import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.util.UUID

enum class ConflictCategory {
    INSUFFICIENT_SERVER_STOCK,
    PRODUCT_MISSING_OR_ARCHIVED,
    FINANCIAL_PROTECTED_RECORD,
    VALIDATION_OR_OTHER,
}

enum class ConflictResolutionOutcome {
    REQUEUED_FOR_SYNC,
    REVERSED_LOCALLY_WITH_AUDIT,
    NOT_FOUND,
}

data class FinancialMergeDecision(
    val shouldOverwriteExisting: Boolean,
    val shouldFlagReview: Boolean,
    val reason: String,
)

/**
 * Stage 3.5 — Pure Conflict Classification & Financial Record Protection Rules.
 * Guarantees financial records and local sales are never silently deleted or overwritten.
 */
object ConflictPolicy {

    fun classifyQueueConflict(event: SyncQueueEventEntity): ConflictCategory {
        val reason = event.failureReason.orEmpty().lowercase()
        return when {
            event.eventType == SyncEventType.FINANCIAL_EVENT ->
                ConflictCategory.FINANCIAL_PROTECTED_RECORD
            reason.contains("insufficient") || reason.contains("stock") || reason.contains("remaining") ->
                ConflictCategory.INSUFFICIENT_SERVER_STOCK
            reason.contains("not exist") || reason.contains("not found") || reason.contains("archived") ->
                ConflictCategory.PRODUCT_MISSING_OR_ARCHIVED
            else -> ConflictCategory.VALIDATION_OR_OTHER
        }
    }

    /**
     * Enforces Blueprint Stage 3.5 rule:
     * An existing confirmed or locally-reviewed financial record must never be overwritten
     * by a duplicate/conflicting reference; flag for human review instead.
     */
    fun protectFinancialRecord(
        existing: PendingFinancialEventEntity?,
        incoming: PendingFinancialEventEntity,
    ): FinancialMergeDecision {
        if (existing == null) {
            return FinancialMergeDecision(
                shouldOverwriteExisting = true,
                shouldFlagReview = false,
                reason = "New financial event.",
            )
        }

        val amountDiffers = kotlin.math.abs(existing.amount - incoming.amount) > 0.009
        val directionDiffers = existing.direction != incoming.direction
        val alreadyConfirmed = existing.syncStatus == SyncState.SERVER_CONFIRMED

        if (alreadyConfirmed || amountDiffers || directionDiffers) {
            return FinancialMergeDecision(
                shouldOverwriteExisting = false,
                shouldFlagReview = true,
                reason = "Protected existing financial record (${existing.transactionReference ?: existing.eventId}) — manual review required.",
            )
        }

        return FinancialMergeDecision(
            shouldOverwriteExisting = false,
            shouldFlagReview = false,
            reason = "Duplicate financial reference safely ignored without overwriting.",
        )
    }
}

/**
 * Stage 3.5 — Coordinates safe non-destructive conflict inspection & resolution
 * with mandatory audit trail entries.
 */
class ConflictManager(
    private val database: EmofahDatabase,
) {
    private val syncQueueDao = database.syncQueueDao()
    private val productDao = database.productDao()
    private val saleDao = database.saleDao()
    private val auditDao = database.auditDao()

    fun observeConflictedEvents(): Flow<List<SyncQueueEventEntity>> =
        syncQueueDao.observeConflictedEvents()

    fun observeConflictedStockProjections(): Flow<List<LocalStockProjectionEntity>> =
        productDao.observeConflictedStockProjections()

    suspend fun retryAfterStockReplenished(
        eventId: String,
        actorId: String,
        deviceId: String,
        reason: String = "Retrying after stock adjustment/replenishment",
    ): ConflictResolutionOutcome {
        val event = syncQueueDao.getEventById(eventId) ?: return ConflictResolutionOutcome.NOT_FOUND
        val targetProductId: Long? = when (event.eventType) {
            SyncEventType.SALE -> SyncQueuePolicy.parseSaleRpcRequest(event)?.productId
            SyncEventType.STOCK_MOVEMENT -> SyncQueuePolicy.parseStockMovementRpcRequest(event)?.productId
            SyncEventType.STOCK_ADJUSTMENT -> SyncQueuePolicy.parseStockAdjustmentRpcRequest(event)?.productId
            SyncEventType.SALE_REVERSAL -> SyncQueuePolicy.parseSaleReversalRpcRequest(event)?.productId
            else -> null
        }
        val now = System.currentTimeMillis()
        val audit = LocalAuditEventEntity(
            auditId = "audit-${UUID.randomUUID()}",
            actorId = actorId,
            deviceId = deviceId,
            operation = "CONFLICT_REQUEUED",
            entityType = event.eventType,
            entityId = event.entityId,
            beforeJson = JSONObject().apply {
                put("status", event.status)
                put("failure_reason", event.failureReason)
            }.toString(),
            afterJson = JSONObject().apply {
                put("status", SyncQueueStatus.QUEUED)
            }.toString(),
            reason = reason,
            syncStatus = SyncState.LOCAL_PENDING,
            occurredAtEpochMs = now,
        )

        syncQueueDao.requeueConflictedEventAtomically(
            eventId = eventId,
            productId = targetProductId,
            nowEpochMs = now,
            auditEvent = audit,
            productDao = productDao,
            auditDao = auditDao,
        )
        return ConflictResolutionOutcome.REQUEUED_FOR_SYNC
    }

    /**
     * Marks a conflicting offline sale as locally reversed instead of deleting it,
     * preserving the sale + audit trail and restoring provisional stock projection.
     */
    suspend fun markConflictedSaleReversedWithAudit(
        eventId: String,
        actorId: String,
        deviceId: String,
        reason: String,
    ): ConflictResolutionOutcome {
        val event = syncQueueDao.getEventById(eventId) ?: return ConflictResolutionOutcome.NOT_FOUND
        val parsedSale = SyncQueuePolicy.parseSaleRpcRequest(event)
        val now = System.currentTimeMillis()
        val cleanReason = reason.trim().ifBlank { "Resolved stock conflict by local reversal" }

        val audit = LocalAuditEventEntity(
            auditId = "audit-${UUID.randomUUID()}",
            actorId = actorId,
            deviceId = deviceId,
            operation = "CONFLICT_SALE_REVERSED_LOCALLY",
            entityType = "SALE",
            entityId = event.entityId,
            beforeJson = event.payloadJson,
            afterJson = JSONObject().apply {
                put("status", SyncQueueStatus.RESOLVED_LOCALLY)
                put("is_reversed", true)
                put("reason", cleanReason)
            }.toString(),
            reason = cleanReason,
            syncStatus = SyncState.LOCAL_PENDING,
            occurredAtEpochMs = now,
        )

        syncQueueDao.resolveConflictedSaleAsReversedAtomically(
            eventId = eventId,
            clientSaleId = event.entityId,
            productId = parsedSale?.productId ?: -1L,
            restoreQty = parsedSale?.quantity ?: 0,
            reason = cleanReason,
            resolvedAtEpochMs = now,
            retryCount = event.retryCount,
            auditEvent = audit,
            saleDao = saleDao,
            productDao = productDao,
            auditDao = auditDao,
        )
        return ConflictResolutionOutcome.REVERSED_LOCALLY_WITH_AUDIT
    }
}
