package com.emofah.agencies.data.sync

import com.emofah.agencies.data.local.entities.PendingProductDraftEntity
import com.emofah.agencies.data.local.entities.ProductReferencePhotoEntity
import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReliabilityRecoveryStage94Test {

    @Test
    fun storageHygienePolicy_protectsActiveOfflinePhotosWhilePruningStaleUnreferencedFiles() {
        val now = 1_700_200_000_000L
        val oldTime = now - (48L * 60L * 60L * 1000L) // 48h old
        val recentTime = now - (10L * 60L * 1000L) // 10m old (within grace)

        val drafts = listOf(
            PendingProductDraftEntity(
                draftId = "draft-pending",
                localTempProductId = -10L,
                name = "A4 Glossy Paper",
                category = "Stationery",
                sellPrice = 500.0,
                buyPrice = 350.0,
                initialQty = 5,
                localPhotoPath = "/cache/captured_scans/orig_pending_draft.jpg",
                ocrText = "A4 Glossy",
                syncStatus = SyncState.LOCAL_PENDING,
                serverProductId = null,
                createdAtEpochMs = oldTime,
            ),
            PendingProductDraftEntity(
                draftId = "draft-confirmed",
                localTempProductId = -11L,
                name = "Confirmed Pen",
                category = "Stationery",
                sellPrice = 20.0,
                buyPrice = 10.0,
                initialQty = 10,
                localPhotoPath = "/cache/captured_scans/orig_old_confirmed.jpg",
                ocrText = "Pen",
                syncStatus = SyncState.SERVER_CONFIRMED,
                serverProductId = 55L,
                createdAtEpochMs = oldTime,
            ),
        )

        val queueEvents = listOf(
            SyncQueueEventEntity(
                eventId = "evt-rec-active",
                eventType = SyncEventType.RECOGNITION_CORRECTION,
                entityId = "corr-1",
                payloadJson = """{"correction_id":"corr-1","idempotency_key":"k1","confirmed_product_id":12,"local_photo_path":"/cache/captured_scans/orig_active_queue.jpg"}""",
                actorId = "cashier",
                deviceId = "DEV-1",
                status = SyncQueueStatus.FAILED_RETRYABLE,
                createdAtEpochMs = oldTime,
            ),
            SyncQueueEventEntity(
                eventId = "evt-synced",
                eventType = SyncEventType.PRODUCT_DRAFT,
                entityId = "draft-confirmed",
                payloadJson = """{"draft_id":"draft-confirmed","local_photo_path":"/cache/captured_scans/orig_old_confirmed.jpg"}""",
                actorId = "cashier",
                deviceId = "DEV-1",
                status = SyncQueueStatus.SYNCED,
                createdAtEpochMs = oldTime,
            ),
        )

        val refPhotos = listOf(
            ProductReferencePhotoEntity(
                id = "ref_12_front",
                productId = 12L,
                angle = "FRONT",
                remoteUrl = "https://example.com/12.jpg",
                localCachePath = "/files/reference_images/prod_12.img",
                lastVerifiedAtEpochMs = oldTime,
            ),
        )

        val protected = ReliabilityRecoveryPolicy.extractProtectedPhotoPaths(drafts, queueEvents, refPhotos)
        assertTrue("/cache/captured_scans/orig_pending_draft.jpg" in protected)
        assertTrue("/cache/captured_scans/orig_active_queue.jpg" in protected)
        assertTrue("/files/reference_images/prod_12.img" in protected)
        assertFalse("/cache/captured_scans/orig_old_confirmed.jpg" in protected)

        val candidates = listOf(
            CachedFileCandidate("/cache/captured_scans/orig_pending_draft.jpg", "orig_pending_draft.jpg", 4_000_000L, oldTime),
            CachedFileCandidate("/cache/captured_scans/orig_active_queue.jpg", "orig_active_queue.jpg", 4_000_000L, oldTime),
            CachedFileCandidate("/cache/captured_scans/orig_old_confirmed.jpg", "orig_old_confirmed.jpg", 2_500_000L, oldTime),
            CachedFileCandidate("/cache/captured_scans/raw_fresh.jpg", "raw_fresh.jpg", 1_000_000L, recentTime),
        )

        val plan = ReliabilityRecoveryPolicy.buildPrunePlan(
            candidates = candidates,
            protectedPaths = protected,
            nowEpochMs = now,
            maxAgeMs = ReliabilityRecoveryPolicy.SCAN_CACHE_MAX_AGE_MS,
            maxTotalBytes = ReliabilityRecoveryPolicy.SCAN_CACHE_MAX_BYTES,
        )

        assertEquals(listOf("/cache/captured_scans/orig_old_confirmed.jpg"), plan.filesToDelete.map { it.absolutePath })
        assertEquals(2_500_000L, plan.bytesToFree)
        assertEquals(2, plan.protectedSkippedCount)
        assertEquals(3, plan.retainedCount)
    }

    @Test
    fun queueIntegrityAnalyzer_relinksOrphanedChildrenAndQuarantinesBlockedOrMalformedEvents() {
        val now = 1_700_200_000_000L

        val drafts = listOf(
            PendingProductDraftEntity(
                draftId = "draft-synced-1",
                localTempProductId = -101L,
                name = "Ruler 30cm",
                category = "Stationery",
                sellPrice = 50.0,
                buyPrice = 25.0,
                initialQty = 10,
                localPhotoPath = null,
                ocrText = null,
                syncStatus = SyncState.SERVER_CONFIRMED,
                serverProductId = 808L,
                createdAtEpochMs = now - 50_000L,
            ),
        )

        val parentSyncedDraft = SyncQueueEventEntity(
            eventId = "evt-parent-synced",
            eventType = SyncEventType.PRODUCT_DRAFT,
            entityId = "draft-synced-1",
            payloadJson = """{"draft_id":"draft-synced-1","idempotency_key":"k1","local_temp_product_id":-101,"name":"Ruler 30cm","sell_price":50}""",
            actorId = "cashier",
            deviceId = "DEV-1",
            status = SyncQueueStatus.SYNCED,
            serverAckId = "808",
            createdAtEpochMs = now - 50_000L,
        )

        val childSaleNeedingRelink = SyncQueueEventEntity(
            eventId = "evt-child-sale-relink",
            eventType = SyncEventType.SALE,
            entityId = "sale-901",
            dependencyEventId = "evt-parent-synced",
            payloadJson = """{"product_id":-101,"qty_sold":2,"total_price":100,"client_sale_id":"sale-901","idempotency_key":"sk-901","sale_date":"2026-11-16T10:00:00Z"}""",
            actorId = "cashier",
            deviceId = "DEV-1",
            status = SyncQueueStatus.QUEUED,
            createdAtEpochMs = now - 40_000L,
        )

        val parentConflict = SyncQueueEventEntity(
            eventId = "evt-parent-conflict",
            eventType = SyncEventType.PRODUCT_DRAFT,
            entityId = "draft-bad",
            payloadJson = """{"draft_id":"draft-bad"}""",
            actorId = "cashier",
            deviceId = "DEV-1",
            status = SyncQueueStatus.CONFLICT,
            failureReason = "Duplicate product",
            createdAtEpochMs = now - 30_000L,
        )

        val childBlockedByConflictParent = SyncQueueEventEntity(
            eventId = "evt-child-blocked",
            eventType = SyncEventType.SALE,
            entityId = "sale-902",
            dependencyEventId = "evt-parent-conflict",
            payloadJson = """{"product_id":-102,"qty_sold":1,"total_price":50,"client_sale_id":"sale-902","idempotency_key":"sk-902","sale_date":"2026-11-16T10:01:00Z"}""",
            actorId = "cashier",
            deviceId = "DEV-1",
            status = SyncQueueStatus.QUEUED,
            createdAtEpochMs = now - 20_000L,
        )

        val corruptedSale = SyncQueueEventEntity(
            eventId = "evt-corrupted-sale",
            eventType = SyncEventType.SALE,
            entityId = "sale-corrupt",
            payloadJson = """{"broken_json":true}""",
            actorId = "cashier",
            deviceId = "DEV-1",
            status = SyncQueueStatus.FAILED_RETRYABLE,
            createdAtEpochMs = now - 10_000L,
        )

        val pendingParent = SyncQueueEventEntity(
            eventId = "evt-parent-pending",
            eventType = SyncEventType.PRODUCT_DRAFT,
            entityId = "draft-pending-2",
            payloadJson = """{"draft_id":"draft-pending-2","idempotency_key":"k2","local_temp_product_id":-103,"name":"Ink Pad","sell_price":120}""",
            actorId = "cashier",
            deviceId = "DEV-1",
            status = SyncQueueStatus.QUEUED,
            createdAtEpochMs = now - 5_000L,
        )

        val validWaitingChild = SyncQueueEventEntity(
            eventId = "evt-valid-waiting-child",
            eventType = SyncEventType.SALE,
            entityId = "sale-903",
            dependencyEventId = "evt-parent-pending",
            payloadJson = """{"product_id":-103,"qty_sold":1,"total_price":120,"client_sale_id":"sale-903","idempotency_key":"sk-903","sale_date":"2026-11-16T10:02:00Z"}""",
            actorId = "cashier",
            deviceId = "DEV-1",
            status = SyncQueueStatus.QUEUED,
            createdAtEpochMs = now - 4_000L,
        )

        val plan = ReliabilityRecoveryPolicy.analyzeQueueIntegrity(
            events = listOf(
                parentSyncedDraft,
                childSaleNeedingRelink,
                parentConflict,
                childBlockedByConflictParent,
                corruptedSale,
                pendingParent,
                validWaitingChild,
            ),
            drafts = drafts,
        )

        assertEquals(1, plan.relinkRepairs.size)
        assertEquals("evt-child-sale-relink", plan.relinkRepairs.first().eventId)
        assertEquals(808L, plan.relinkRepairs.first().resolvedProductId)
        assertTrue(plan.relinkRepairs.first().updatedPayloadJson.contains("\"product_id\":808"))

        assertEquals(1, plan.dependencyBlocked.size)
        assertEquals("evt-child-blocked", plan.dependencyBlocked.first().eventId)

        assertEquals(1, plan.malformedEvents.size)
        assertEquals("evt-corrupted-sale", plan.malformedEvents.first().eventId)
    }
}
