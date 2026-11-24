package com.emofah.agencies

import com.emofah.agencies.data.local.EmofahDatabase
import com.emofah.agencies.data.local.EmofahDatabaseMigrations
import com.emofah.agencies.data.local.entities.LocalAuditEventEntity
import com.emofah.agencies.data.local.entities.LocalSaleEntity
import com.emofah.agencies.data.local.entities.LocalSaleItemEntity
import com.emofah.agencies.data.local.entities.LocalStockProjectionEntity
import com.emofah.agencies.data.local.entities.PendingFinancialEventEntity
import com.emofah.agencies.data.local.entities.PendingProductDraftEntity
import com.emofah.agencies.data.local.entities.ProductEntity
import com.emofah.agencies.data.local.entities.ProductReferencePhotoEntity
import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RoomDatabaseContractTest {

    @Test
    fun schemaVersionAndMigrationsAreExplicit() {
        assertEquals(2, EmofahDatabase.SCHEMA_VERSION)
        assertEquals(1, EmofahDatabaseMigrations.ALL_MIGRATIONS.size)
        assertEquals(1, EmofahDatabaseMigrations.ALL_MIGRATIONS[0].startVersion)
        assertEquals(2, EmofahDatabaseMigrations.ALL_MIGRATIONS[0].endVersion)
    }

    @Test
    fun saleAndLineItemEntitiesUseStableIdsAndDefaultOfflinePendingState() {
        val sale = LocalSaleEntity(
            clientSaleId = "sale-uuid-001",
            idempotencyKey = "idem-uuid-001",
            totalAmount = 300.0,
            paymentMethod = "CASH",
            actorId = "staff-1",
            deviceId = "android-dev-1",
            saleDateIso = "2026-11-14T09:00:00Z",
            createdAtEpochMs = 1_700_000_000_000L,
        )
        val item = LocalSaleItemEntity(
            id = "item-uuid-001",
            clientSaleId = sale.clientSaleId,
            productId = 18L,
            productNameSnapshot = "Masking tape (medium)",
            quantity = 2,
            unitSellPrice = 150.0,
            originalSellPrice = 150.0,
            lineTotal = 300.0,
        )

        assertEquals(SyncState.LOCAL_PENDING, sale.syncStatus)
        assertEquals("ANDROID", sale.source)
        assertNull(sale.serverSaleId)
        assertFalse(sale.isReversed)
        assertEquals(sale.clientSaleId, item.clientSaleId)
        assertEquals(18L, item.productId)
    }

    @Test
    fun productProjectionAndSyncQueueEntitiesDistinguishServerVsProvisionalState() {
        val product = ProductEntity(
            id = 18L,
            name = "Masking tape (medium)",
            baseName = "Masking tape",
            variantLabel = "medium",
            brandHint = null,
            category = "Stationery",
            sellPrice = 150.0,
            discountPrice = 0.0,
            buyPrice = 115.0,
            serverStockQty = 5,
            imageUrl = null,
            description = null,
            isFeatured = false,
            createdAt = "2026-01-05T14:40:33Z",
            cachedAtEpochMs = 1_700_000_000_000L,
        )
        val photo = ProductReferencePhotoEntity(
            id = "ref_18_front",
            productId = product.id,
            angle = "FRONT",
            remoteUrl = null,
            localCachePath = null,
            widthPx = null,
            heightPx = null,
            visualSignatureHex = null,
            isValid = false,
            lastVerifiedAtEpochMs = 1_700_000_000_000L,
        )
        val projection = LocalStockProjectionEntity(
            productId = product.id,
            serverQty = 5,
            serverUpdatedAtEpochMs = 1_700_000_000_000L,
            pendingDeltaQty = -2,
            provisionalAvailableQty = 3,
        )
        val queueEvent = SyncQueueEventEntity(
            eventId = "evt-001",
            eventType = "SALE",
            entityId = "sale-uuid-001",
            dependencyEventId = null,
            payloadJson = "{}",
            actorId = "staff-1",
            deviceId = "android-dev-1",
            createdAtEpochMs = 1_700_000_000_000L,
        )
        val financial = PendingFinancialEventEntity(
            eventId = "fin-001",
            source = "MPESA_SMS",
            amount = 300.0,
            transactionReference = "UAB123XYZ",
            counterparty = "JOHN DOE",
            direction = "INFLOW",
            rawEvidence = null,
            parsedJson = null,
            eventTimestampEpochMs = 1_700_000_000_000L,
        )
        val draft = PendingProductDraftEntity(
            draftId = "draft-001",
            name = "New Pen",
            category = "Stationery",
            sellPrice = 30.0,
            buyPrice = 20.0,
            initialQty = 10,
            createdAtEpochMs = 1_700_000_000_000L,
        )
        val audit = LocalAuditEventEntity(
            auditId = "aud-001",
            actorId = "staff-1",
            deviceId = "android-dev-1",
            operation = "CREATE_OFFLINE_SALE",
            entityType = "SALE",
            entityId = "sale-uuid-001",
            occurredAtEpochMs = 1_700_000_000_000L,
        )

        assertEquals(5, projection.serverQty)
        assertEquals(3, projection.provisionalAvailableQty)
        assertEquals("QUEUED", queueEvent.status)
        assertEquals("UNCLASSIFIED", financial.provisionalCategory)
        assertEquals(SyncState.LOCAL_PENDING, draft.syncStatus)
        assertEquals("FRONT", photo.angle)
        assertEquals("CREATE_OFFLINE_SALE", audit.operation)
    }
}
