package com.emofah.agencies.data.sync

import com.emofah.agencies.data.local.entities.PendingProductDraftEntity
import com.emofah.agencies.data.local.entities.ProductReferencePhotoEntity
import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import org.json.JSONObject

data class CachedFileCandidate(
    val absolutePath: String,
    val name: String,
    val sizeBytes: Long,
    val lastModifiedEpochMs: Long,
)

data class StoragePrunePlan(
    val filesToDelete: List<CachedFileCandidate>,
    val bytesToFree: Long,
    val protectedSkippedCount: Int,
    val retainedCount: Int,
)

data class QueueRelinkRepair(
    val eventId: String,
    val entityType: String,
    val updatedPayloadJson: String,
    val resolvedProductId: Long,
)

data class QueueQuarantineAction(
    val eventId: String,
    val eventType: String,
    val entityId: String,
    val reason: String,
)

data class QueueIntegrityPlan(
    val relinkRepairs: List<QueueRelinkRepair>,
    val dependencyBlocked: List<QueueQuarantineAction>,
    val malformedEvents: List<QueueQuarantineAction>,
) {
    val totalRepairsCount: Int
        get() = relinkRepairs.size + dependencyBlocked.size + malformedEvents.size
}

data class ReliabilityHealthSnapshot(
    val totalCacheBytes: Long,
    val scanCacheBytes: Long,
    val receiptCacheBytes: Long,
    val referenceImagesBytes: Long,
    val protectedAssetCount: Int,
    val inFlightCount: Int,
    val dependencyBlockedCount: Int,
    val malformedCount: Int,
)

data class RecoveryRunReport(
    val inFlightRecovered: Int,
    val relinkedChildren: Int,
    val blockedChildrenFlagged: Int,
    val malformedQuarantined: Int,
    val filesDeleted: Int,
    val bytesFreed: Long,
    val snapshotAfter: ReliabilityHealthSnapshot,
    val summaryMessage: String,
)

/**
 * Stage 9.4 — Pure Reliability, Dependency Recovery & Storage Hygiene Policy.
 * Keeps active offline queue payloads and photos strictly protected while
 * healing interrupted transactions, broken dependency chains, and stale cache bloat.
 */
object ReliabilityRecoveryPolicy {

    const val SCAN_CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L // 24 hours
    const val SCAN_CACHE_MAX_BYTES = 35L * 1024L * 1024L // 35 MB
    const val RECEIPT_CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L // 7 days
    const val RECEIPT_CACHE_MAX_BYTES = 15L * 1024L * 1024L // 15 MB
    const val ACTIVE_CAPTURE_GRACE_MS = 30L * 60L * 1000L // 30 minutes

    fun extractProtectedPhotoPaths(
        drafts: List<PendingProductDraftEntity>,
        queueEvents: List<SyncQueueEventEntity>,
        referencePhotos: List<ProductReferencePhotoEntity>,
    ): Set<String> {
        val protected = linkedSetOf<String>()

        drafts.filter { it.syncStatus != SyncState.SERVER_CONFIRMED }.forEach { draft ->
            draft.localPhotoPath?.takeIf { it.isNotBlank() }?.let { protected.add(it) }
        }

        queueEvents.filter {
            it.status != SyncQueueStatus.SYNCED && it.status != SyncQueueStatus.RESOLVED_LOCALLY
        }.forEach { evt ->
            runCatching {
                val json = JSONObject(evt.payloadJson)
                val path = json.optString("local_photo_path", "")
                if (path.isNotBlank() && path != "null") {
                    protected.add(path)
                }
            }
        }

        referencePhotos.forEach { photo ->
            photo.localCachePath?.takeIf { it.isNotBlank() }?.let { protected.add(it) }
        }

        return protected
    }

    fun buildPrunePlan(
        candidates: List<CachedFileCandidate>,
        protectedPaths: Set<String>,
        nowEpochMs: Long,
        maxAgeMs: Long,
        maxTotalBytes: Long,
        gracePeriodMs: Long = ACTIVE_CAPTURE_GRACE_MS,
    ): StoragePrunePlan {
        val toDelete = mutableListOf<CachedFileCandidate>()
        val surviving = mutableListOf<CachedFileCandidate>()
        var protectedSkipped = 0

        for (file in candidates) {
            if (file.absolutePath in protectedPaths) {
                protectedSkipped++
                surviving += file
                continue
            }
            val ageMs = (nowEpochMs - file.lastModifiedEpochMs).coerceAtLeast(0L)
            if (ageMs >= maxAgeMs && ageMs >= gracePeriodMs) {
                toDelete += file
            } else {
                surviving += file
            }
        }

        // Enforce byte ceiling across remaining unprotected files (oldest first)
        val unprotectedRemaining = surviving
            .filter { it.absolutePath !in protectedPaths }
            .sortedBy { it.lastModifiedEpochMs }
        var totalUnprotectedBytes = unprotectedRemaining.sumOf { it.sizeBytes }
        for (file in unprotectedRemaining) {
            if (totalUnprotectedBytes <= maxTotalBytes) break
            val ageMs = (nowEpochMs - file.lastModifiedEpochMs).coerceAtLeast(0L)
            // Keep very recent active captures (< 5 minutes) unless critically oversized
            if (ageMs < 5L * 60L * 1000L && totalUnprotectedBytes <= maxTotalBytes * 2) {
                continue
            }
            toDelete += file
            totalUnprotectedBytes -= file.sizeBytes
        }

        val uniqueDelete = toDelete.distinctBy { it.absolutePath }
        return StoragePrunePlan(
            filesToDelete = uniqueDelete,
            bytesToFree = uniqueDelete.sumOf { it.sizeBytes },
            protectedSkippedCount = protectedSkipped,
            retainedCount = candidates.size - uniqueDelete.size,
        )
    }

    fun isPayloadStructurallyValid(event: SyncQueueEventEntity): Boolean {
        return when (event.eventType) {
            SyncEventType.PRODUCT_DRAFT -> SyncQueuePolicy.parseProductDraftPayload(event) != null
            SyncEventType.SALE -> SyncQueuePolicy.parseSaleRpcRequest(event) != null
            SyncEventType.SALE_REVERSAL -> SyncQueuePolicy.parseSaleReversalRpcRequest(event) != null
            SyncEventType.STOCK_MOVEMENT -> SyncQueuePolicy.parseStockMovementRpcRequest(event) != null
            SyncEventType.STOCK_ADJUSTMENT -> SyncQueuePolicy.parseStockAdjustmentRpcRequest(event) != null
            SyncEventType.STOCK_COUNT -> SyncQueuePolicy.parseStockCountRpcRequest(event) != null
            SyncEventType.FINANCIAL_EVENT -> SyncQueuePolicy.parseFinancialEventRpcRequest(event) != null
            SyncEventType.PURCHASE_RECEIPT -> SyncQueuePolicy.parsePurchaseRpcRequest(event) != null
            SyncEventType.SERVICE_OPERATION -> SyncQueuePolicy.parseServiceOperationRpcRequest(event) != null
            SyncEventType.ORDER_UPDATE -> SyncQueuePolicy.parseOrderStatusRpcRequest(event) != null
            SyncEventType.ORDER_TO_SALE -> SyncQueuePolicy.parseOrderConversionRpcRequest(event) != null
            SyncEventType.RECOGNITION_CORRECTION -> SyncQueuePolicy.parseRecognitionCorrectionPayload(event) != null
            SyncEventType.PRODUCT_IMAGE -> true
            else -> false
        }
    }

    /**
     * Inspects active queue events for:
     * 1. Child events still holding negative temporary product IDs after their parent draft succeeded.
     * 2. Child events permanently deadlocked behind a CONFLICT/RESOLVED_LOCALLY/missing parent event.
     * 3. Active events whose payload JSON became malformed/corrupted so they surface for safe review.
     */
    fun analyzeQueueIntegrity(
        events: List<SyncQueueEventEntity>,
        drafts: List<PendingProductDraftEntity>,
    ): QueueIntegrityPlan {
        val eventsById = events.associateBy { it.eventId }
        val draftsById = drafts.associateBy { it.draftId }

        val relinkRepairs = mutableListOf<QueueRelinkRepair>()
        val dependencyBlocked = mutableListOf<QueueQuarantineAction>()
        val malformedEvents = mutableListOf<QueueQuarantineAction>()

        val activeEvents = events.filter {
            it.status == SyncQueueStatus.QUEUED ||
                it.status == SyncQueueStatus.FAILED_RETRYABLE ||
                it.status == SyncQueueStatus.AUTH_REQUIRED
        }

        for (event in activeEvents) {
            val depId = event.dependencyEventId
            if (depId != null) {
                val parent = eventsById[depId]
                val resolvedDraft = parent?.entityId?.let { draftsById[it] }
                val resolvedProductId = resolvedDraft?.serverProductId?.takeIf { it > 0L }
                    ?: parent?.serverAckId?.toLongOrNull()?.takeIf { it > 0L }

                if (parent == null) {
                    dependencyBlocked += QueueQuarantineAction(
                        eventId = event.eventId,
                        eventType = event.eventType,
                        entityId = event.entityId,
                        reason = "Blocked: missing prerequisite sync event ($depId).",
                    )
                    continue
                }

                if (parent.status == SyncQueueStatus.CONFLICT || parent.status == SyncQueueStatus.RESOLVED_LOCALLY) {
                    dependencyBlocked += QueueQuarantineAction(
                        eventId = event.eventId,
                        eventType = event.eventType,
                        entityId = event.entityId,
                        reason = "Blocked: prerequisite ${parent.eventType} (${parent.entityId}) requires conflict review first.",
                    )
                    continue
                }

                if (parent.status == SyncQueueStatus.SYNCED && resolvedProductId != null) {
                    val currentPid = extractReferencedProductId(event)
                    if (currentPid != null && currentPid < 0L) {
                        val relinked = when (event.eventType) {
                            SyncEventType.SALE ->
                                SyncQueuePolicy.relinkSalePayloadProductId(event.payloadJson, resolvedProductId)
                            SyncEventType.STOCK_MOVEMENT ->
                                SyncQueuePolicy.relinkStockMovementPayloadProductId(event.payloadJson, resolvedProductId)
                            SyncEventType.STOCK_ADJUSTMENT ->
                                SyncQueuePolicy.relinkStockAdjustmentPayloadProductId(event.payloadJson, resolvedProductId)
                            SyncEventType.RECOGNITION_CORRECTION ->
                                SyncQueuePolicy.relinkRecognitionCorrectionPayloadProductId(
                                    payloadJson = event.payloadJson,
                                    oldTempProductId = currentPid,
                                    newProductId = resolvedProductId,
                                )
                            else -> event.payloadJson
                        }
                        if (relinked != event.payloadJson) {
                            relinkRepairs += QueueRelinkRepair(
                                eventId = event.eventId,
                                entityType = event.eventType,
                                updatedPayloadJson = relinked,
                                resolvedProductId = resolvedProductId,
                            )
                            continue
                        }
                    }
                }
            }

            // Skip malformed check if event is waiting on an un-synced parent product draft with temp ID
            val waitingOnPendingParent = depId != null &&
                eventsById[depId]?.status != SyncQueueStatus.SYNCED
            if (!waitingOnPendingParent && !isPayloadStructurallyValid(event)) {
                malformedEvents += QueueQuarantineAction(
                    eventId = event.eventId,
                    eventType = event.eventType,
                    entityId = event.entityId,
                    reason = "Quarantined: unreadable or incomplete ${event.eventType} payload (${event.entityId}).",
                )
            }
        }

        return QueueIntegrityPlan(
            relinkRepairs = relinkRepairs,
            dependencyBlocked = dependencyBlocked,
            malformedEvents = malformedEvents,
        )
    }

    private fun extractReferencedProductId(event: SyncQueueEventEntity): Long? {
        return runCatching {
            val json = JSONObject(event.payloadJson)
            when (event.eventType) {
                SyncEventType.RECOGNITION_CORRECTION ->
                    json.optLong("confirmed_product_id", 0L).takeIf { it != 0L }
                else ->
                    json.optLong("product_id", 0L).takeIf { it != 0L }
            }
        }.getOrNull()
    }
}
