package com.emofah.agencies

import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.data.local.dao.LocalSaleWithItems
import com.emofah.agencies.data.local.entities.LocalSaleEntity
import com.emofah.agencies.data.local.entities.LocalSaleItemEntity
import com.emofah.agencies.data.local.entities.LocalStockProjectionEntity
import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import com.emofah.agencies.data.sync.SyncEventType
import com.emofah.agencies.data.sync.SyncQueuePolicy
import com.emofah.agencies.data.sync.SyncQueueStatus
import com.emofah.agencies.sales.AndroidSaleReversalRpcRequest
import com.emofah.agencies.sales.PaymentMethod
import com.emofah.agencies.sales.PriceOverridePolicy
import com.emofah.agencies.sales.SaleCalculator
import com.emofah.agencies.sales.SaleHistoryMapper
import com.emofah.agencies.sales.SaleHistorySyncBadge
import com.emofah.agencies.sales.SaleReversalPolicy
import com.emofah.agencies.sales.SaleReversalRpcOutcome
import com.emofah.agencies.sales.SaleRpcOutcome
import com.emofah.agencies.sales.SaleTransactionRepository
import com.emofah.agencies.sales.StaffRole
import com.emofah.agencies.sales.StockPolicy
import com.emofah.agencies.sales.SupabaseSaleReversalClient
import com.emofah.agencies.sales.SupabaseSaleRpcClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Stage 10.2 — Sales testing end-to-end verification suite:
 * Verifies quantity/price/discount calculations, NaN/overflow input hardening,
 * RBAC-gated price exceptions, online/offline idempotency, dependency relinking,
 * and non-destructive sale history & reversal flows.
 */
class SalesTestingStage102Test {

    private fun product(
        id: Long = 18L,
        name: String = "Masking tape (medium)",
        sellPrice: Double = 150.0,
        discountPrice: Double = 0.0,
        stockQty: Int = 10,
    ) = ProductIdentity(
        id = id,
        name = name,
        baseName = name,
        variantLabel = "medium",
        brandHint = null,
        category = "Stationery",
        sellPrice = sellPrice,
        discountPrice = discountPrice,
        buyPrice = 95.0,
        stockQty = stockQty,
        imageUrl = null,
        description = null,
        isFeatured = false,
        createdAt = "2026-11-16T08:00:00Z",
    )

    @Test
    fun parseInputsAndValidateCalculateRejectNonFiniteOverflowAndZeroPrices() {
        assertNull(SaleCalculator.parsePriceInput("NaN"))
        assertNull(SaleCalculator.parsePriceInput("Infinity"))
        assertNull(SaleCalculator.parsePriceInput("-Infinity"))
        assertNull(SaleCalculator.parsePriceInput("   "))
        assertEquals(125.5, SaleCalculator.parsePriceInput(" 125.50 ")!!, 0.001)

        // Integer overflow digit strings safely map to 0 and get blocked cleanly
        assertEquals(0, SaleCalculator.parseQuantityInput("999999999999"))
        assertEquals(12, SaleCalculator.parseQuantityInput("12 pcs"))

        val base = product(sellPrice = 150.0, discountPrice = 120.0)
        // Uses discountPrice as effectiveSellPrice when discountPrice > 0
        val discounted = SaleCalculator.validateAndCalculate(
            product = base,
            stockProjection = null,
            rawQuantity = 3,
        )
        assertTrue(discounted.isValidToRecord)
        assertFalse(discounted.isPriceOverridden)
        assertEquals(120.0, discounted.unitSellPrice, 0.001)
        assertEquals(360.0, discounted.totalAmount, 0.001)

        // Direct NaN / Infinity price override rejected cleanly even for Supervisor
        val nanOverride = SaleCalculator.validateAndCalculate(
            product = base,
            stockProjection = null,
            rawQuantity = 2,
            overrideUnitPrice = Double.NaN,
            overrideReason = "Test",
            staffRole = StaffRole.SUPERVISOR,
        )
        assertFalse(nanOverride.isValidToRecord)
        assertTrue(nanOverride.blockingReason!!.contains("finite", ignoreCase = true))

        val infOverride = PriceOverridePolicy.validatePriceException(
            originalPrice = 120.0,
            requestedPrice = Double.POSITIVE_INFINITY,
            role = StaffRole.OWNER_MANAGER,
            overrideReason = "Bulk",
        )
        assertFalse(infOverride.isAllowed)

        // Draft builder ignores invalid negative/NaN overrides and falls back safely
        val cleanDraft = SaleCalculator.createDraft(
            product = base,
            quantity = 2,
            paymentMethod = PaymentMethod.CASH,
            overrideUnitPrice = Double.NaN,
            priceOverrideReason = "Ignored",
        )
        assertFalse(cleanDraft.isPriceOverridden)
        assertEquals(120.0, cleanDraft.unitSellPrice, 0.001)
        assertNull(cleanDraft.priceOverrideReason)
    }

    @Test
    fun stockPolicyAndPriceEpsilonBehaveDeterministically() {
        val item = product(sellPrice = 200.0, stockQty = 8)
        val projection = LocalStockProjectionEntity(
            productId = 18L,
            serverQty = 8,
            serverUpdatedAtEpochMs = 1_000_000L,
            pendingDeltaQty = -6,
            provisionalAvailableQty = 2,
        )

        // Epsilon check treats 200.004 as normal non-overridden price even for Cashier
        val withinEpsilon = SaleCalculator.validateAndCalculate(
            product = item,
            stockProjection = projection,
            rawQuantity = 2,
            overrideUnitPrice = 200.004,
            overrideReason = null,
            staffRole = StaffRole.CASHIER,
        )
        assertTrue(withinEpsilon.isValidToRecord)
        assertFalse(withinEpsilon.isPriceOverridden)
        assertNull(withinEpsilon.stockWarning)

        // Provisional stock = 2, qty = 4 triggers warning under ALLOW_WITH_WARNING and block under STRICT_BLOCK
        val warned = SaleCalculator.validateAndCalculate(
            product = item,
            stockProjection = projection,
            rawQuantity = 4,
            stockPolicy = StockPolicy.ALLOW_WITH_WARNING,
        )
        assertTrue(warned.isValidToRecord)
        assertTrue(warned.stockWarning!!.contains("2"))

        val blocked = SaleCalculator.validateAndCalculate(
            product = item,
            stockProjection = projection,
            rawQuantity = 4,
            stockPolicy = StockPolicy.STRICT_BLOCK,
        )
        assertFalse(blocked.isValidToRecord)
        assertTrue(blocked.blockingReason!!.contains("Only 2"))
    }

    @Test
    fun offlineTempProductSaleLinksDependencyAndRelinksPayloadOnSync() {
        val tempDraftProduct = product(id = -7L, name = "Deli Stapler (No. 10)", sellPrice = 350.0, stockQty = 5)
        val draft = SaleCalculator.createDraft(
            product = tempDraftProduct,
            quantity = 2,
            paymentMethod = PaymentMethod.MPESA,
            existingDraftId = "sale-temp-7",
            existingIdempotencyKey = "idem-temp-7",
            nowEpochMs = 1_763_300_000_000L,
        )

        val artifacts = SaleTransactionRepository.buildTransactionArtifacts(
            draft = draft,
            originalSellPrice = 350.0,
            actorId = "cashier_anne",
            deviceId = "android-dev10",
            dependencyEventId = "sync-draft-7",
            actorRole = StaffRole.CASHIER,
        )

        assertEquals("sync-draft-7", artifacts.syncEvent.dependencyEventId)
        assertEquals(SyncEventType.SALE, artifacts.syncEvent.eventType)
        assertEquals(700.0, artifacts.sale.totalAmount, 0.001)

        val relinkedPayload = SyncQueuePolicy.relinkSalePayloadProductId(
            payloadJson = artifacts.syncEvent.payloadJson,
            newProductId = 245L,
        )
        val parsedQueueRequest = SyncQueuePolicy.parseSaleRpcRequest(
            SyncQueueEventEntity(
                eventId = artifacts.syncEvent.eventId,
                eventType = SyncEventType.SALE,
                entityId = artifacts.syncEvent.entityId,
                dependencyEventId = artifacts.syncEvent.dependencyEventId,
                payloadJson = relinkedPayload,
                actorId = artifacts.syncEvent.actorId,
                deviceId = artifacts.syncEvent.deviceId,
                status = SyncQueueStatus.QUEUED,
                retryCount = 0,
                nextRetryAtEpochMs = artifacts.syncEvent.nextRetryAtEpochMs,
                createdAtEpochMs = artifacts.syncEvent.createdAtEpochMs,
            )
        )

        assertNotNull(parsedQueueRequest)
        assertEquals(245L, parsedQueueRequest!!.productId)
        assertEquals(2, parsedQueueRequest.quantity)
        assertEquals(700.0, parsedQueueRequest.totalAmount, 0.001)
        assertEquals("MPESA", parsedQueueRequest.paymentMethod)
        assertEquals("idem-temp-7", parsedQueueRequest.idempotencyKey)
    }

    @Test
    fun saleRpcResponsesCoverAuthUnauthorizedRoleDuplicateAndIncompleteAck() {
        val unauthorized = SupabaseSaleRpcClient.parseRpcResponse(
            200,
            """{"status":"UNAUTHORIZED_ROLE","message":"Price override requires supervisor role"}""",
        )
        assertTrue(unauthorized is SaleRpcOutcome.Rejected)
        assertEquals("UNAUTHORIZED_ROLE", (unauthorized as SaleRpcOutcome.Rejected).code)

        val auth401 = SupabaseSaleRpcClient.parseRpcResponse(401, """{"message":"JWT expired"}""")
        assertTrue(auth401 is SaleRpcOutcome.AuthRequired)

        val incompleteAck = SupabaseSaleRpcClient.parseRpcResponse(
            200,
            """{"status":"COMMITTED","server_sale_id":0,"remaining_qty":4}""",
        )
        assertTrue(incompleteAck is SaleRpcOutcome.NetworkOrTransientError)

        val replay = SupabaseSaleRpcClient.parseRpcResponse(
            200,
            """{"status":"DUPLICATE_IGNORED","server_sale_id":999,"remaining_qty":4}""",
        )
        assertTrue(replay is SaleRpcOutcome.CommittedOnline)
        assertTrue((replay as SaleRpcOutcome.CommittedOnline).alreadyCommitted)
        assertEquals(999L, replay.serverSaleId)
    }

    @Test
    fun saleHistoryBadgesAndReversalAuthorizationCoverAllStates() {
        val startOfToday = 1_763_300_000_000L
        fun makeRecord(
            id: String,
            syncStatus: String,
            isReversed: Boolean = false,
            overrideUnitPrice: Double = 150.0,
        ): LocalSaleWithItems {
            val sale = LocalSaleEntity(
                clientSaleId = id,
                serverSaleId = if (syncStatus == SyncState.SERVER_CONFIRMED) 101L else null,
                idempotencyKey = "idem-$id",
                totalAmount = overrideUnitPrice * 2,
                paymentMethod = "OTHER",
                actorId = "sup_john",
                deviceId = "android-dev1",
                source = "ANDROID",
                saleDateIso = "2026-11-16T10:00:00Z",
                createdAtEpochMs = startOfToday + 60_000L,
                syncStatus = syncStatus,
                isReversed = isReversed,
                reversalReason = if (isReversed) "Customer returned item" else null,
            )
            val items = listOf(
                LocalSaleItemEntity(
                    id = "$id-item-1",
                    clientSaleId = id,
                    productId = 18L,
                    productNameSnapshot = "Masking tape (medium)",
                    quantity = 2,
                    unitSellPrice = overrideUnitPrice,
                    originalSellPrice = 150.0,
                    priceOverrideReason = if (overrideUnitPrice != 150.0) "Damaged edge" else null,
                    lineTotal = overrideUnitPrice * 2,
                )
            )
            return LocalSaleWithItems(sale = sale, items = items)
        }

        val conflictUi = SaleHistoryMapper.toUiModel(
            makeRecord("s-conflict", SyncState.CONFLICT),
            startOfToday,
            Locale.US,
        )
        assertEquals(SaleHistorySyncBadge.CONFLICT, conflictUi.syncBadge)
        assertEquals("Other", conflictUi.paymentLabel)
        assertTrue(conflictUi.canReverse)

        val failedUi = SaleHistoryMapper.toUiModel(
            makeRecord("s-failed", SyncState.FAILED, overrideUnitPrice = 135.0),
            startOfToday,
            Locale.US,
        )
        assertEquals(SaleHistorySyncBadge.FAILED, failedUi.syncBadge)
        assertTrue(failedUi.hasPriceOverride)

        val reversedUi = SaleHistoryMapper.toUiModel(
            makeRecord("s-rev", SyncState.SERVER_CONFIRMED, isReversed = true),
            startOfToday,
            Locale.US,
        )
        assertEquals(SaleHistorySyncBadge.REVERSED, reversedUi.syncBadge)
        assertFalse(reversedUi.canReverse)

        // Reason shorter than 3 chars must be blocked even for Owner/Manager
        val activeSale = makeRecord("s-active", SyncState.SERVER_CONFIRMED).sale
        val shortReason = SaleReversalPolicy.validateReversalRequest(
            sale = activeSale,
            role = StaffRole.OWNER_MANAGER,
            reason = "no",
        )
        assertFalse(shortReason.isAllowed)

        // Reversal client handles auth expiry & payload formatting deterministically
        val authErr = SupabaseSaleReversalClient.parseRpcResponse(403, """{"message":"Forbidden"}""")
        assertTrue(authErr is SaleReversalRpcOutcome.AuthRequired)

        val reqJson = JSONObject(
            SupabaseSaleReversalClient.buildRpcPayloadJson(
                AndroidSaleReversalRpcRequest(
                    clientSaleId = "s-active",
                    serverSaleId = 101L,
                    productId = 18L,
                    quantity = 2,
                    totalAmount = 300.0,
                    idempotencyKey = SaleReversalPolicy.buildReversalIdempotencyKey("s-active"),
                    reason = "Wrong SKU scanned",
                    paymentMethod = "OTHER",
                    actorId = "sup_john",
                    actorRole = StaffRole.SUPERVISOR.name,
                    deviceId = "android-dev1",
                    reversedAtIso = "2026-11-16T11:00:00Z",
                )
            )
        )
        assertEquals("reversal-s-active", reqJson.getString("p_idempotency_key"))
        assertEquals("Wrong SKU scanned", reqJson.getString("p_reason"))
    }
}
