package com.emofah.agencies

import com.emofah.agencies.data.local.entities.PendingFinancialEventEntity
import com.emofah.agencies.data.local.entities.PendingProductDraftEntity
import com.emofah.agencies.data.local.entities.ProductReferencePhotoEntity
import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import com.emofah.agencies.data.sync.CachedFileCandidate
import com.emofah.agencies.data.sync.ConflictCategory
import com.emofah.agencies.data.sync.ConflictPolicy
import com.emofah.agencies.data.sync.OfflineStatusSummary
import com.emofah.agencies.data.sync.ReliabilityRecoveryPolicy
import com.emofah.agencies.data.sync.SyncEventType
import com.emofah.agencies.data.sync.SyncPassSummary
import com.emofah.agencies.data.sync.SyncQueuePolicy
import com.emofah.agencies.data.sync.SyncQueueStatus
import com.emofah.agencies.data.sync.SyncWorkerOutcomeMapper
import com.emofah.agencies.data.sync.WorkerAction
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 10.3 — Offline testing end-to-end verification suite:
 * Exercises multi-entity dependency ordering, temp-ID relinking & self-healing,
 * NaN/corrupt payload quarantine, WorkManager retry/auth-hold bounds,
 * offline status summaries, conflict classification, and photo-safe cache hygiene.
 */
class OfflineTestingStage103Test {

    private fun event(
        id: String,
        type: String,
        status: String = SyncQueueStatus.QUEUED,
        depId: String? = null,
        payload: String = "{}",
        createdAt: Long = 1_000L,
        nextRetryAt: Long = 1_000L,
        failureReason: String? = null,
        serverAckId: String? = null,
    ) = SyncQueueEventEntity(
        eventId = id,
        eventType = type,
        entityId = "ent-$id",
        dependencyEventId = depId,
        payloadJson = payload,
        actorId = "cashier_1",
        deviceId = "dev_android_1",
        status = status,
        retryCount = 0,
        nextRetryAtEpochMs = nextRetryAt,
        failureReason = failureReason,
        serverAckId = serverAckId,
        createdAtEpochMs = createdAt,
    )

    @Test
    fun dependencyChainAndCycleSafeSortEnforceStrictParentBeforeChildOrder() {
        val draftParent = event(
            id = "evt-draft",
            type = SyncEventType.PRODUCT_DRAFT,
            createdAt = 500L,
            payload = """{"draft_id":"ent-evt-draft","idempotency_key":"dk-1","local_temp_product_id":-12,"name":"Deli Stapler","sell_price":350.0,"buy_price":220.0,"initial_qty":4}""",
        )
        val childSale = event(
            id = "evt-sale-child",
            type = SyncEventType.SALE,
            depId = "evt-draft",
            createdAt = 100L, // Earlier timestamp edge case must STILL wait behind parent
            payload = """{"client_sale_id":"s-child","idempotency_key":"sk-child","product_id":-12,"qty_sold":1,"total_price":350.0,"sale_date":"2026-11-16T09:00:00Z"}""",
        )
        val childCorrection = event(
            id = "evt-corr-child",
            type = SyncEventType.RECOGNITION_CORRECTION,
            depId = "evt-draft",
            createdAt = 200L,
            payload = """{"correction_id":"c-child","idempotency_key":"ck-child","confirmed_product_id":-12,"learned_alias":"DELI NO10"}""",
        )

        // Before parent syncs, children are blocked even if their timestamps are earlier
        val runnableRound1 = SyncQueuePolicy.filterRunnable(
            events = listOf(childSale, childCorrection, draftParent),
            nowEpochMs = 10_000L,
        )
        assertEquals(listOf("evt-draft"), runnableRound1.map { it.eventId })

        // Topological sort puts parent before children safely and survives cycles without StackOverflow
        val sorted = SyncQueuePolicy.sortDependencySafe(listOf(childSale, childCorrection, draftParent))
        assertEquals(listOf("evt-draft", "evt-sale-child", "evt-corr-child"), sorted.map { it.eventId })

        val cycleA = event("cyc-a", SyncEventType.SALE, depId = "cyc-b", createdAt = 10L)
        val cycleB = event("cyc-b", SyncEventType.SALE, depId = "cyc-a", createdAt = 20L)
        val cycleSorted = SyncQueuePolicy.sortDependencySafe(listOf(cycleA, cycleB))
        assertEquals(2, cycleSorted.size)
    }

    @Test
    fun queueSelfHealingRelinksAllChildTypesAndQuarantinesCorruptOrNanPayloads() {
        val syncedParent = event(
            id = "evt-parent",
            type = SyncEventType.PRODUCT_DRAFT,
            status = SyncQueueStatus.SYNCED,
            serverAckId = "305",
            payload = """{"draft_id":"draft-305","idempotency_key":"dk-305","local_temp_product_id":-15,"name":"A4 Counter Book","sell_price":280.0}""",
        ).copy(entityId = "draft-305")

        val drafts = listOf(
            PendingProductDraftEntity(
                draftId = "draft-305",
                name = "A4 Counter Book",
                category = "Books",
                sellPrice = 280.0,
                buyPrice = 190.0,
                initialQty = 12,
                localPhotoPath = "/cache/captured_scans/a4_book.jpg",
                ocrText = "COUNTER BOOK",
                syncStatus = SyncState.SERVER_CONFIRMED,
                serverProductId = 305L,
                createdAtEpochMs = 1_000L,
            )
        )

        val orphanedStockReceipt = event(
            id = "evt-receipt-orphan",
            type = SyncEventType.STOCK_MOVEMENT,
            depId = "evt-parent",
            payload = """{"client_receipt_id":"rcpt-1","idempotency_key":"rk-1","product_id":-15,"qty":6,"unit_cost":190.0}""",
        )
        val orphanedAdjustment = event(
            id = "evt-adj-orphan",
            type = SyncEventType.STOCK_ADJUSTMENT,
            depId = "evt-parent",
            payload = """{"client_adjustment_id":"adj-1","idempotency_key":"ak-1","product_id":-15,"delta_qty":-1,"reason_code":"DAMAGED","notes":"Damaged corner"}""",
        )
        val nanTotalSale = event(
            id = "evt-nan-sale",
            type = SyncEventType.SALE,
            payload = """{"client_sale_id":"s-nan","idempotency_key":"sk-nan","product_id":18,"qty_sold":2,"total_price":"NaN","sale_date":"2026-11-16T09:00:00Z"}""",
        )
        val missingParentSale = event(
            id = "evt-missing-parent",
            type = SyncEventType.SALE,
            depId = "evt-nonexistent",
            payload = """{"client_sale_id":"s-miss","idempotency_key":"sk-miss","product_id":-99,"qty_sold":1,"total_price":100.0,"sale_date":"2026-11-16T09:00:00Z"}""",
        )

        // Verify NaN deserialization guard rejects nanTotalSale cleanly
        assertNull(SyncQueuePolicy.parseSaleRpcRequest(nanTotalSale))

        val plan = ReliabilityRecoveryPolicy.analyzeQueueIntegrity(
            events = listOf(syncedParent, orphanedStockReceipt, orphanedAdjustment, nanTotalSale, missingParentSale),
            drafts = drafts,
        )

        assertEquals(2, plan.relinkRepairs.size)
        val relinkedReceiptJson = JSONObject(plan.relinkRepairs.first { it.eventId == "evt-receipt-orphan" }.updatedPayloadJson)
        val relinkedAdjJson = JSONObject(plan.relinkRepairs.first { it.eventId == "evt-adj-orphan" }.updatedPayloadJson)
        assertEquals(305L, relinkedReceiptJson.getLong("product_id"))
        assertEquals(305L, relinkedAdjJson.getLong("product_id"))

        assertEquals(1, plan.dependencyBlocked.size)
        assertEquals("evt-missing-parent", plan.dependencyBlocked.first().eventId)

        assertEquals(1, plan.malformedEvents.size)
        assertEquals("evt-nan-sale", plan.malformedEvents.first().eventId)

        // Confirm repaired payloads pass strict parsing
        val repairedReceiptEvent = orphanedStockReceipt.copy(payloadJson = relinkedReceiptJson.toString())
        assertNotNull(SyncQueuePolicy.parseStockMovementRpcRequest(repairedReceiptEvent))
    }

    @Test
    fun workManagerOutcomeMapperEnforcesAuthHoldAndBoundedRetryLimits() {
        val authBlockedSummary = SyncPassSummary(
            attemptedCount = 1,
            syncedCount = 0,
            retryableCount = 0,
            conflictCount = 0,
            authBlockedCount = 1,
            deferredCount = 0,
        )
        assertEquals(WorkerAction.FAILURE_AUTH_HOLD, SyncWorkerOutcomeMapper.decideAction(authBlockedSummary, runAttemptCount = 0))

        val offlineSummary = SyncPassSummary(
            attemptedCount = 0,
            syncedCount = 0,
            retryableCount = 0,
            conflictCount = 0,
            authBlockedCount = 0,
            deferredCount = 0,
            skippedOffline = true,
        )
        assertEquals(WorkerAction.RETRY, SyncWorkerOutcomeMapper.decideAction(offlineSummary, runAttemptCount = 2))
        assertEquals(
            WorkerAction.FAILURE_MAX_ATTEMPTS,
            SyncWorkerOutcomeMapper.decideAction(offlineSummary, runAttemptCount = SyncWorkerOutcomeMapper.MAX_WORKMANAGER_ATTEMPTS),
        )

        val conflictOnlySummary = SyncPassSummary(
            attemptedCount = 2,
            syncedCount = 1,
            retryableCount = 0,
            conflictCount = 1,
            authBlockedCount = 0,
            deferredCount = 0,
        )
        // Conflicts require human review rather than WorkManager spinner retries
        assertEquals(WorkerAction.SUCCESS, SyncWorkerOutcomeMapper.decideAction(conflictOnlySummary, runAttemptCount = 1))
    }

    @Test
    fun offlineStatusSummaryAndConflictProtectionPrioritizeCriticalStates() {
        val mixedQueue = listOf(
            event("q1", SyncEventType.SALE, SyncQueueStatus.QUEUED, createdAt = 100L),
            event("q2", SyncEventType.STOCK_COUNT, SyncQueueStatus.IN_FLIGHT, createdAt = 200L),
            event("q3", SyncEventType.SALE, SyncQueueStatus.CONFLICT, createdAt = 300L, failureReason = "Insufficient stock"),
            event("q4", SyncEventType.FINANCIAL_EVENT, SyncQueueStatus.AUTH_REQUIRED, createdAt = 400L),
            event("q5", SyncEventType.ORDER_UPDATE, SyncQueueStatus.SYNCED, createdAt = 500L),
            event("q6", SyncEventType.SALE, SyncQueueStatus.RESOLVED_LOCALLY, createdAt = 600L),
        )

        val offlineSummary = OfflineStatusSummary.fromQueue(isOnline = false, events = mixedQueue)
        assertEquals(2, offlineSummary.pendingCount) // QUEUED + IN_FLIGHT
        assertEquals(1, offlineSummary.conflictCount)
        assertEquals(1, offlineSummary.authBlockedCount)
        assertEquals(2, offlineSummary.syncedCount) // SYNCED + RESOLVED_LOCALLY
        assertTrue(offlineSummary.statusHeadline.contains("re-authentication"))

        val cleanOfflineSummary = OfflineStatusSummary.fromQueue(
            isOnline = false,
            events = listOf(event("q1", SyncEventType.SALE, SyncQueueStatus.QUEUED)),
        )
        assertTrue(cleanOfflineSummary.statusHeadline.startsWith("Offline"))
        assertTrue(cleanOfflineSummary.statusHeadline.contains("1 item(s) queued"))

        val finEvent = mixedQueue[3].copy(status = SyncQueueStatus.CONFLICT)
        assertEquals(ConflictCategory.FINANCIAL_PROTECTED_RECORD, ConflictPolicy.classifyQueueConflict(finEvent))

        val confirmedFin = PendingFinancialEventEntity(
            eventId = "fin-1",
            source = "MPESA_SMS",
            amount = 500.0,
            transactionReference = "TJ99ABC111",
            counterparty = "ALICE",
            direction = "INFLOW",
            rawEvidence = "Confirmed",
            parsedJson = "{}",
            syncStatus = SyncState.SERVER_CONFIRMED,
            eventTimestampEpochMs = 1_000L,
        )
        val sameDecision = ConflictPolicy.protectFinancialRecord(confirmedFin, confirmedFin.copy(eventId = "fin-dup"))
        assertFalse(sameDecision.shouldOverwriteExisting)
        assertTrue(sameDecision.shouldFlagReview)
    }

    @Test
    fun storageHygieneHonorsGracePeriodByteCeilingsAndProtectedAssets() {
        val now = 1_763_400_000_000L
        val stale = now - (48L * 60L * 60L * 1000L)
        val withinGrace = now - (10L * 60L * 1000L)

        val protectedPaths = ReliabilityRecoveryPolicy.extractProtectedPhotoPaths(
            drafts = listOf(
                PendingProductDraftEntity(
                    draftId = "d-active",
                    name = "Test Pen",
                    category = "Stationery",
                    sellPrice = 50.0,
                    buyPrice = 25.0,
                    initialQty = 1,
                    localPhotoPath = "/cache/captured_scans/draft_keep.jpg",
                    ocrText = null,
                    syncStatus = SyncState.LOCAL_PENDING,
                    serverProductId = null,
                    createdAtEpochMs = stale,
                )
            ),
            queueEvents = emptyList(),
            referencePhotos = listOf(
                ProductReferencePhotoEntity(
                    id = "ref-1",
                    productId = 18L,
                    angle = "FRONT",
                    remoteUrl = null,
                    localCachePath = "/files/ref/prod_18.jpg",
                    widthPx = 800,
                    heightPx = 600,
                    visualSignatureHex = "ef567890",
                    isValid = true,
                    lastVerifiedAtEpochMs = stale,
                )
            ),
        )

        val plan = ReliabilityRecoveryPolicy.buildPrunePlan(
            candidates = listOf(
                CachedFileCandidate("/cache/captured_scans/draft_keep.jpg", "draft_keep.jpg", 3_000_000L, stale),
                CachedFileCandidate("/cache/captured_scans/fresh_scan.jpg", "fresh_scan.jpg", 2_000_000L, withinGrace),
                CachedFileCandidate("/cache/captured_scans/stale_unused.jpg", "stale_unused.jpg", 4_000_000L, stale),
            ),
            protectedPaths = protectedPaths,
            nowEpochMs = now,
            maxAgeMs = ReliabilityRecoveryPolicy.SCAN_CACHE_MAX_AGE_MS,
            maxTotalBytes = ReliabilityRecoveryPolicy.SCAN_CACHE_MAX_BYTES,
        )

        assertEquals(listOf("/cache/captured_scans/stale_unused.jpg"), plan.filesToDelete.map { it.absolutePath })
        assertEquals(4_000_000L, plan.bytesToFree)
        assertEquals(1, plan.protectedSkippedCount)
        assertEquals(2, plan.retainedCount)
    }
}
