package com.emofah.agencies

import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.sync.SyncEventType
import com.emofah.agencies.data.sync.SyncQueuePolicy
import com.emofah.agencies.data.sync.SyncQueueStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncQueueStage33Test {

    private fun makeEvent(
        id: String,
        type: String = SyncEventType.SALE,
        status: String = SyncQueueStatus.QUEUED,
        dependencyId: String? = null,
        nextRetryAt: Long = 1000L,
        createdAt: Long = 1000L,
        payload: String = """{"client_sale_id":"sale-1","idempotency_key":"idem-1","product_id":18,"qty_sold":2,"total_price":300.0,"payment_method":"CASH","actor_id":"staff1","device_id":"dev1","sale_date":"2026-11-14T10:00:00Z"}""",
    ) = SyncQueueEventEntity(
        eventId = id,
        eventType = type,
        entityId = "entity-$id",
        dependencyEventId = dependencyId,
        payloadJson = payload,
        actorId = "staff1",
        deviceId = "dev1",
        status = status,
        retryCount = 0,
        nextRetryAtEpochMs = nextRetryAt,
        createdAtEpochMs = createdAt,
    )

    @Test
    fun dependencyBlockingPreventsChildFromRunningBeforeParentSynced() {
        val parentDraft = makeEvent(
            id = "evt-product-draft",
            type = SyncEventType.PRODUCT_DRAFT,
            status = SyncQueueStatus.QUEUED,
            createdAt = 100L,
        )
        val childPhoto = makeEvent(
            id = "evt-product-photo",
            type = SyncEventType.PRODUCT_IMAGE,
            status = SyncQueueStatus.QUEUED,
            dependencyId = "evt-product-draft",
            createdAt = 200L,
        )
        val independentSale = makeEvent(
            id = "evt-sale-independent",
            type = SyncEventType.SALE,
            status = SyncQueueStatus.QUEUED,
            createdAt = 300L,
        )

        val runnableBeforeParent = SyncQueuePolicy.filterRunnable(
            listOf(parentDraft, childPhoto, independentSale),
            nowEpochMs = 5000L,
        )
        assertEquals(
            listOf("evt-product-draft", "evt-sale-independent"),
            runnableBeforeParent.map { it.eventId },
        )

        val syncedParent = parentDraft.copy(status = SyncQueueStatus.SYNCED)
        val runnableAfterParent = SyncQueuePolicy.filterRunnable(
            listOf(syncedParent, childPhoto, independentSale),
            nowEpochMs = 5000L,
        )
        assertEquals(
            listOf("evt-product-photo", "evt-sale-independent"),
            runnableAfterParent.map { it.eventId },
        )
    }

    @Test
    fun sortDependencySafePlacesParentsBeforeChildrenEvenOutOfTimestampOrder() {
        val child = makeEvent(
            id = "child",
            dependencyId = "parent",
            createdAt = 100L,
        )
        val parent = makeEvent(
            id = "parent",
            dependencyId = null,
            createdAt = 500L,
        )

        val ordered = SyncQueuePolicy.sortDependencySafe(listOf(child, parent))
        assertEquals(listOf("parent", "child"), ordered.map { it.eventId })
    }

    @Test
    fun boundedExponentialBackoffCapsAtFifteenMinutes() {
        assertEquals(15_000L, SyncQueuePolicy.computeBackoffDelayMs(0))
        assertEquals(30_000L, SyncQueuePolicy.computeBackoffDelayMs(1))
        assertEquals(60_000L, SyncQueuePolicy.computeBackoffDelayMs(2))
        assertEquals(120_000L, SyncQueuePolicy.computeBackoffDelayMs(3))
        assertEquals(900_000L, SyncQueuePolicy.computeBackoffDelayMs(6))
        assertEquals(900_000L, SyncQueuePolicy.computeBackoffDelayMs(12))

        val now = 1_000_000L
        assertEquals(1_015_000L, SyncQueuePolicy.computeNextRetryEpochMs(now, nextRetryCount = 1))
        assertEquals(1_030_000L, SyncQueuePolicy.computeNextRetryEpochMs(now, nextRetryCount = 2))
    }

    @Test
    fun retryTimestampAndStatusGateRunnableEvents() {
        val delayedRetry = makeEvent(
            id = "delayed",
            status = SyncQueueStatus.FAILED_RETRYABLE,
            nextRetryAt = 20_000L,
        )
        val conflictEvent = makeEvent(
            id = "conflict",
            status = SyncQueueStatus.CONFLICT,
            nextRetryAt = 500L,
        )
        val authBlocked = makeEvent(
            id = "auth",
            status = SyncQueueStatus.AUTH_REQUIRED,
            nextRetryAt = 500L,
        )
        val readyRetry = makeEvent(
            id = "ready",
            status = SyncQueueStatus.FAILED_RETRYABLE,
            nextRetryAt = 9_000L,
        )

        val runnable = SyncQueuePolicy.filterRunnable(
            listOf(delayedRetry, conflictEvent, authBlocked, readyRetry),
            nowEpochMs = 10_000L,
        )
        assertEquals(listOf("ready"), runnable.map { it.eventId })
    }

    @Test
    fun parseSaleRpcRequestValidatesPayloadCorrectly() {
        val valid = makeEvent(id = "ok")
        val parsed = SyncQueuePolicy.parseSaleRpcRequest(valid)
        assertNotNull(parsed)
        assertEquals(18L, parsed!!.productId)
        assertEquals(2, parsed.quantity)
        assertEquals(300.0, parsed.totalAmount, 0.001)
        assertEquals("idem-1", parsed.idempotencyKey)

        val malformed = makeEvent(id = "bad", payload = """{"product_id":0,"qty_sold":0}""")
        assertNull(SyncQueuePolicy.parseSaleRpcRequest(malformed))

        val orphanEvent = makeEvent(id = "orphan", dependencyId = "missing-parent")
        assertFalse(SyncQueuePolicy.isDependencySatisfied(orphanEvent, mapOf("orphan" to orphanEvent)))
        assertTrue(SyncQueuePolicy.isDependencySatisfied(valid, mapOf("ok" to valid)))
    }
}
