package com.emofah.agencies

import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.data.local.entities.SyncState
import com.emofah.agencies.sales.PaymentMethod
import com.emofah.agencies.sales.SaleCalculator
import com.emofah.agencies.sales.SaleRpcOutcome
import com.emofah.agencies.sales.SaleTransactionRepository
import com.emofah.agencies.sales.SupabaseSaleRpcClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaleTransactionStage22Test {

    private fun sampleProduct() = ProductIdentity(
        id = 18L,
        name = "Masking tape (medium)",
        baseName = "Masking tape",
        variantLabel = "medium",
        brandHint = null,
        category = "Stationery",
        sellPrice = 150.0,
        discountPrice = 0.0,
        buyPrice = 95.0,
        stockQty = 10,
        imageUrl = null,
        description = null,
        isFeatured = false,
        createdAt = null,
    )

    @Test
    fun buildTransactionArtifactsPopulatesAndroidAttributionAndQueuePayload() {
        val draft = SaleCalculator.createDraft(
            product = sampleProduct(),
            quantity = 2,
            paymentMethod = PaymentMethod.MPESA,
            existingDraftId = "uuid-sale-1001",
            existingIdempotencyKey = "sale-uuid-sale-1001",
            nowEpochMs = 1_763_100_000_000L,
        )

        val artifacts = SaleTransactionRepository.buildTransactionArtifacts(
            draft = draft,
            originalSellPrice = 150.0,
            actorId = "cashier_anne",
            deviceId = "android-dev123",
        )

        assertEquals("uuid-sale-1001", artifacts.sale.clientSaleId)
        assertEquals("sale-uuid-sale-1001", artifacts.sale.idempotencyKey)
        assertEquals("ANDROID", artifacts.sale.source)
        assertEquals("MPESA", artifacts.sale.paymentMethod)
        assertEquals(300.0, artifacts.sale.totalAmount, 0.001)
        assertEquals(SyncState.LOCAL_PENDING, artifacts.sale.syncStatus)

        assertEquals(1, artifacts.items.size)
        assertEquals(18L, artifacts.items[0].productId)
        assertEquals(2, artifacts.items[0].quantity)
        assertEquals(300.0, artifacts.items[0].lineTotal, 0.001)

        assertEquals("SALE", artifacts.syncEvent.eventType)
        assertEquals("QUEUED", artifacts.syncEvent.status)
        val payload = JSONObject(artifacts.syncEvent.payloadJson)
        assertEquals("sale-uuid-sale-1001", payload.getString("idempotency_key"))
        assertEquals(18L, payload.getLong("product_id"))
        assertEquals(2, payload.getInt("qty_sold"))

        assertEquals("SALE_RECORDED", artifacts.auditEvent.operation)
        assertEquals("cashier_anne", artifacts.auditEvent.actorId)
    }

    @Test
    fun rpcPayloadAndCommittedResponseParseCorrectly() {
        val draft = SaleCalculator.createDraft(
            product = sampleProduct(),
            quantity = 3,
            paymentMethod = PaymentMethod.CASH,
            existingDraftId = "uuid-22",
            existingIdempotencyKey = "sale-uuid-22",
            nowEpochMs = 1_763_100_000_000L,
        )
        val artifacts = SaleTransactionRepository.buildTransactionArtifacts(
            draft = draft,
            originalSellPrice = 150.0,
            actorId = "android_staff",
            deviceId = "android-abc",
        )

        val rpcJson = JSONObject(SupabaseSaleRpcClient.buildRpcPayloadJson(artifacts.rpcRequest))
        assertEquals(18L, rpcJson.getLong("p_product_id"))
        assertEquals(3, rpcJson.getInt("p_qty"))
        assertEquals(450.0, rpcJson.getDouble("p_total"), 0.001)
        assertEquals("sale-uuid-22", rpcJson.getString("p_idempotency_key"))

        val committed = SupabaseSaleRpcClient.parseRpcResponse(
            200,
            """{"status":"COMMITTED","server_sale_id":512,"remaining_qty":7,"already_committed":false}""",
        )
        assertTrue(committed is SaleRpcOutcome.CommittedOnline)
        committed as SaleRpcOutcome.CommittedOnline
        assertEquals(512L, committed.serverSaleId)
        assertEquals(7, committed.remainingQty)
        assertFalse(committed.alreadyCommitted)
    }

    @Test
    fun rpcIdempotentReplayInsufficientStockAndMissingRpcHandledCorrectly() {
        val replay = SupabaseSaleRpcClient.parseRpcResponse(
            200,
            """{"status":"DUPLICATE_IGNORED","server_sale_id":512,"remaining_qty":7,"already_committed":true}""",
        )
        assertTrue(replay is SaleRpcOutcome.CommittedOnline)
        assertTrue((replay as SaleRpcOutcome.CommittedOnline).alreadyCommitted)

        val insufficient = SupabaseSaleRpcClient.parseRpcResponse(
            200,
            """{"status":"INSUFFICIENT_STOCK","available_qty":1,"message":"Only 1 remaining"}""",
        )
        assertTrue(insufficient is SaleRpcOutcome.Rejected)
        assertEquals(1, (insufficient as SaleRpcOutcome.Rejected).serverAvailableQty)

        val missingRpc = SupabaseSaleRpcClient.parseRpcResponse(
            404,
            """{"code":"PGRST202","message":"Could not find the function public.record_android_sale"}""",
        )
        assertTrue(missingRpc is SaleRpcOutcome.RpcMissing)

        val transientError = SupabaseSaleRpcClient.parseRpcResponse(503, "Service Unavailable")
        assertTrue(transientError is SaleRpcOutcome.NetworkOrTransientError)
    }
}
