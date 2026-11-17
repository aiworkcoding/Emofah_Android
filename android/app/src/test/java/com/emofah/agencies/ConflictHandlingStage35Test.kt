package com.emofah.agencies

import com.emofah.agencies.data.catalog.CatalogueDeltaCalculator
import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.data.local.entities.LocalStockProjectionEntity
import com.emofah.agencies.data.local.entities.PendingFinancialEventEntity
import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import com.emofah.agencies.data.sync.ConflictCategory
import com.emofah.agencies.data.sync.ConflictPolicy
import com.emofah.agencies.data.sync.SyncEventType
import com.emofah.agencies.data.sync.SyncQueueStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictHandlingStage35Test {

    @Test
    fun deltaRefreshDetectsOversellConflictWithoutDroppingPendingDelta() {
        val remote = ProductIdentity(
            id = 18L,
            name = "Masking tape (medium)",
            baseName = "Masking tape",
            variantLabel = "medium",
            brandHint = null,
            category = "Stationery",
            sellPrice = 150.0,
            discountPrice = 0.0,
            buyPrice = 95.0,
            stockQty = 1, // Server dropped to 1 due to desktop sale
            imageUrl = null,
            description = null,
            isFeatured = false,
            createdAt = null,
        )
        val existingProj = LocalStockProjectionEntity(
            productId = 18L,
            serverQty = 5,
            serverUpdatedAtEpochMs = 1000L,
            pendingDeltaQty = -3, // 3 units sold offline on Android
            provisionalAvailableQty = 2,
            hasConflict = false,
        )

        val plan = CatalogueDeltaCalculator.computeDeltaPlan(
            incoming = listOf(remote),
            existingProducts = emptyList(),
            existingProjections = listOf(existingProj),
            nowEpochMs = 2000L,
        )

        assertEquals(1, plan.updatedProjections.size)
        val updated = plan.updatedProjections.first()
        assertEquals(1, updated.serverQty)
        assertEquals(-3, updated.pendingDeltaQty)
        assertEquals(-2, updated.provisionalAvailableQty)
        assertTrue(updated.hasConflict)
        assertTrue(updated.conflictNote.orEmpty().contains("exceeds server stock"))
    }

    @Test
    fun conflictPolicyClassifiesStockProductAndFinancialConflicts() {
        val stockEvent = SyncQueueEventEntity(
            eventId = "e1",
            eventType = SyncEventType.SALE,
            entityId = "s1",
            payloadJson = "{}",
            actorId = "staff",
            deviceId = "dev",
            status = SyncQueueStatus.CONFLICT,
            failureReason = "Insufficient stock: requested 3 but only 1 remaining.",
            createdAtEpochMs = 1000L,
        )
        assertEquals(
            ConflictCategory.INSUFFICIENT_SERVER_STOCK,
            ConflictPolicy.classifyQueueConflict(stockEvent),
        )

        val missingProduct = stockEvent.copy(failureReason = "Product #999 does not exist.")
        assertEquals(
            ConflictCategory.PRODUCT_MISSING_OR_ARCHIVED,
            ConflictPolicy.classifyQueueConflict(missingProduct),
        )

        val financialEvent = stockEvent.copy(eventType = SyncEventType.FINANCIAL_EVENT)
        assertEquals(
            ConflictCategory.FINANCIAL_PROTECTED_RECORD,
            ConflictPolicy.classifyQueueConflict(financialEvent),
        )
    }

    @Test
    fun financialRecordProtectionNeverAllowsSilentOverwrite() {
        val existingConfirmed = PendingFinancialEventEntity(
            eventId = "fin-1",
            source = "MPESA_SMS",
            amount = 1500.0,
            transactionReference = "TJ81XYZ123",
            counterparty = "JOHN DOE",
            direction = "INFLOW",
            rawEvidence = "Confirmed SMS",
            parsedJson = "{}",
            syncStatus = SyncState.SERVER_CONFIRMED,
            eventTimestampEpochMs = 1000L,
        )
        val collidingAmount = existingConfirmed.copy(eventId = "fin-2", amount = 1800.0)

        val decision = ConflictPolicy.protectFinancialRecord(existingConfirmed, collidingAmount)
        assertFalse(decision.shouldOverwriteExisting)
        assertTrue(decision.shouldFlagReview)
    }
}
