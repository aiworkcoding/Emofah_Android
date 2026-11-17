package com.emofah.agencies

import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.sync.SyncEventType
import com.emofah.agencies.data.sync.SyncQueuePolicy
import com.emofah.agencies.orders.AndroidOrderConversionRpcRequest
import com.emofah.agencies.orders.OrderConversionPolicy
import com.emofah.agencies.orders.OrderConversionRpcOutcome
import com.emofah.agencies.orders.ResolvedOrderSaleLine
import com.emofah.agencies.orders.StoreOrderItem
import com.emofah.agencies.orders.StoreOrderRecord
import com.emofah.agencies.orders.StoreOrderStatus
import com.emofah.agencies.orders.SupabaseOrderConversionClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderToSaleConversionStage63Test {

    private fun sampleOrder(
        status: StoreOrderStatus = StoreOrderStatus.READY,
        isConverted: Boolean = false,
    ): StoreOrderRecord {
        return StoreOrderRecord(
            id = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
            displayNumber = "#9B1DEB4D",
            rawStatus = status.supabaseValue,
            status = status,
            totalPrice = 450.0,
            createdAtIso = "2026-11-16T08:00:00Z",
            createdAtEpochMs = 1_700_000_000_000L,
            items = listOf(
                StoreOrderItem(
                    productId = 12L,
                    productName = "A4 Exercise Book 200 pgs",
                    quantity = 3,
                    unitPrice = 150.0,
                    lineTotal = 450.0,
                )
            ),
            customerName = "Jane Doe",
            customerPhone = "0712345678",
            deliveryNote = null,
            isPendingSync = false,
            isConvertedToSale = isConverted,
        )
    }

    @Test
    fun `conversion policy allows confirmed ready fulfilled orders and blocks duplicates or pending or unlinked lines`() {
        assertTrue(OrderConversionPolicy.canConvertStatus(StoreOrderStatus.CONFIRMED, alreadyConverted = false))
        assertTrue(OrderConversionPolicy.canConvertStatus(StoreOrderStatus.READY, alreadyConverted = false))
        assertTrue(OrderConversionPolicy.canConvertStatus(StoreOrderStatus.FULFILLED, alreadyConverted = false))
        assertFalse(OrderConversionPolicy.canConvertStatus(StoreOrderStatus.PENDING, alreadyConverted = false))
        assertFalse(OrderConversionPolicy.canConvertStatus(StoreOrderStatus.CANCELLED, alreadyConverted = false))
        assertFalse(OrderConversionPolicy.canConvertStatus(StoreOrderStatus.FULFILLED, alreadyConverted = true))

        val validLines = listOf(
            ResolvedOrderSaleLine(
                productId = 12L,
                productName = "A4 Exercise Book 200 pgs",
                quantity = 3,
                unitPrice = 150.0,
                lineTotal = 450.0,
            )
        )
        assertTrue(OrderConversionPolicy.validateConversion(sampleOrder(StoreOrderStatus.READY), validLines).isValid)

        val unlinkedLine = listOf(
            ResolvedOrderSaleLine(
                productId = -1L,
                productName = "Unlinked Item",
                quantity = 1,
                unitPrice = 100.0,
                lineTotal = 100.0,
            )
        )
        assertFalse(OrderConversionPolicy.validateConversion(sampleOrder(StoreOrderStatus.READY), unlinkedLine).isValid)
        assertFalse(OrderConversionPolicy.validateConversion(sampleOrder(StoreOrderStatus.READY, isConverted = true), validLines).isValid)
        assertFalse(OrderConversionPolicy.validateConversion(sampleOrder(StoreOrderStatus.PENDING), validLines).isValid)
    }

    @Test
    fun `supabase order conversion rpc client serializes items and parses outcomes cleanly`() {
        val req = AndroidOrderConversionRpcRequest(
            clientConversionId = "ordconv-9b1deb4d",
            idempotencyKey = "idem-ordconv-9b1deb4d",
            orderId = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
            items = listOf(
                ResolvedOrderSaleLine(12L, "Book", 3, 150.0, 450.0)
            ),
            paymentMethod = "MPESA",
            actorId = "cashier-1",
            deviceId = "android-1",
            convertedAtIso = "2026-11-16T09:00:00Z",
        )
        val payload = JSONObject(SupabaseOrderConversionClient.buildRpcPayloadJson(req))
        assertEquals("ordconv-9b1deb4d", payload.getString("p_client_conversion_id"))
        assertEquals("MPESA", payload.getString("p_payment_method"))
        assertEquals(1, payload.getJSONArray("p_items").length())

        val committed = SupabaseOrderConversionClient.parseRpcResponse(
            200,
            """{"status":"COMMITTED","server_conversion_id":"conv-1","order_id":"9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d","total_amount":450,"updated_products":[{"product_id":12,"qty_deducted":3,"server_qty_after":17,"server_sale_id":501}],"already_committed":false}""",
        )
        assertTrue(committed is OrderConversionRpcOutcome.CommittedOnline)
        val c = committed as OrderConversionRpcOutcome.CommittedOnline
        assertEquals(1, c.updatedProducts.size)
        assertEquals(17, c.updatedProducts.first().serverQtyAfter)
        assertEquals(501L, c.updatedProducts.first().serverSaleId)

        val alreadyConverted = SupabaseOrderConversionClient.parseRpcResponse(
            200,
            """{"status":"ALREADY_CONVERTED","message":"Already converted"}""",
        )
        assertTrue(alreadyConverted is OrderConversionRpcOutcome.Rejected)
        assertTrue((alreadyConverted as OrderConversionRpcOutcome.Rejected).alreadyConverted)

        val insufficient = SupabaseOrderConversionClient.parseRpcResponse(
            200,
            """{"status":"INSUFFICIENT_STOCK","message":"Only 1 remaining"}""",
        )
        assertTrue(insufficient is OrderConversionRpcOutcome.Rejected)
        assertFalse((insufficient as OrderConversionRpcOutcome.Rejected).alreadyConverted)
    }

    @Test
    fun `sync queue codec round-trips ORDER_TO_SALE payload and rejects empty items`() {
        val req = AndroidOrderConversionRpcRequest(
            clientConversionId = "ordconv-1",
            idempotencyKey = "idem-ordconv-1",
            orderId = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
            items = listOf(
                ResolvedOrderSaleLine(12L, "Book", 2, 150.0, 300.0)
            ),
            paymentMethod = "CASH",
            actorId = "staff-1",
            deviceId = "android-1",
            convertedAtIso = "2026-11-16T10:00:00Z",
        )
        val json = SyncQueuePolicy.serializeOrderConversionRpcRequest(req)
        val event = SyncQueueEventEntity(
            eventId = "sync-ordconv-1",
            eventType = SyncEventType.ORDER_TO_SALE,
            entityId = "ordconv-1",
            dependencyEventId = null,
            payloadJson = json,
            actorId = "staff-1",
            deviceId = "android-1",
            status = "QUEUED",
            createdAtEpochMs = 1_700_000_000_000L,
        )
        val parsed = SyncQueuePolicy.parseOrderConversionRpcRequest(event)
        assertNotNull(parsed)
        assertEquals("CASH", parsed!!.paymentMethod)
        assertEquals(1, parsed.items.size)
        assertEquals(12L, parsed.items.first().productId)

        val broken = event.copy(
            payloadJson = JSONObject(json).put("items", org.json.JSONArray()).toString(),
        )
        assertNull(SyncQueuePolicy.parseOrderConversionRpcRequest(broken))
    }
}
