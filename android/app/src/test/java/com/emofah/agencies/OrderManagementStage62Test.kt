package com.emofah.agencies

import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.sync.SyncEventType
import com.emofah.agencies.data.sync.SyncQueuePolicy
import com.emofah.agencies.orders.AndroidOrderStatusRpcRequest
import com.emofah.agencies.orders.OrderStatusRpcOutcome
import com.emofah.agencies.orders.OrderStatusTransitionPolicy
import com.emofah.agencies.orders.PendingOrderStatusOverlay
import com.emofah.agencies.orders.StoreOrderParser
import com.emofah.agencies.orders.StoreOrderStatus
import com.emofah.agencies.orders.SupabaseOrderStatusClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderManagementStage62Test {

    @Test
    fun `transition policy allows operational lifecycle and blocks terminal transitions`() {
        assertEquals(
            listOf(StoreOrderStatus.CONFIRMED, StoreOrderStatus.READY, StoreOrderStatus.CANCELLED),
            OrderStatusTransitionPolicy.allowedNextStatuses(StoreOrderStatus.PENDING),
        )
        assertEquals(
            listOf(StoreOrderStatus.READY, StoreOrderStatus.CANCELLED),
            OrderStatusTransitionPolicy.allowedNextStatuses(StoreOrderStatus.CONFIRMED),
        )
        assertEquals(
            listOf(StoreOrderStatus.FULFILLED, StoreOrderStatus.CANCELLED),
            OrderStatusTransitionPolicy.allowedNextStatuses(StoreOrderStatus.READY),
        )
        assertTrue(OrderStatusTransitionPolicy.allowedNextStatuses(StoreOrderStatus.FULFILLED).isEmpty())
        assertTrue(OrderStatusTransitionPolicy.allowedNextStatuses(StoreOrderStatus.CANCELLED).isEmpty())
    }

    @Test
    fun `transition validation requires cancellation reason and rejects illegal jumps`() {
        val noReasonCancel = OrderStatusTransitionPolicy.validateTransition(
            current = StoreOrderStatus.PENDING,
            target = StoreOrderStatus.CANCELLED,
            reason = "no",
        )
        assertFalse(noReasonCancel.isValid)

        val validCancel = OrderStatusTransitionPolicy.validateTransition(
            current = StoreOrderStatus.PENDING,
            target = StoreOrderStatus.CANCELLED,
            reason = "Customer requested cancellation",
        )
        assertTrue(validCancel.isValid)

        val illegalJump = OrderStatusTransitionPolicy.validateTransition(
            current = StoreOrderStatus.PENDING,
            target = StoreOrderStatus.FULFILLED,
            reason = null,
        )
        assertFalse(illegalJump.isValid)

        val terminalChange = OrderStatusTransitionPolicy.validateTransition(
            current = StoreOrderStatus.FULFILLED,
            target = StoreOrderStatus.CANCELLED,
            reason = "Late cancel attempt",
        )
        assertFalse(terminalChange.isValid)
    }

    @Test
    fun `order parser applies local cache status update and pending sync overlay cleanly`() {
        val rawJson = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("id", "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")
                    put("status", "pending")
                    put("total_price", 450.0)
                    put("created_at", "2026-11-16T08:30:00Z")
                    put("items", JSONArray())
                }
            )
        }.toString()

        val updatedJson = StoreOrderParser.updateOrderStatusInRawJson(
            rawJson = rawJson,
            orderId = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
            newRawStatus = "confirmed",
        )
        assertNotNull(updatedJson)

        val parsed = StoreOrderParser.parseOrdersJson(
            rawJson = updatedJson!!,
            pendingOverlays = mapOf(
                "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d" to PendingOrderStatusOverlay(
                    orderId = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
                    targetRawStatus = "ready",
                    updatedAtEpochMs = 1_700_000_000_000L,
                )
            ),
        )
        assertEquals(1, parsed.size)
        assertEquals(StoreOrderStatus.READY, parsed.first().status)
        assertTrue(parsed.first().isPendingSync)
    }

    @Test
    fun `supabase order status rpc client serializes payload and parses outcomes accurately`() {
        val req = AndroidOrderStatusRpcRequest(
            clientUpdateId = "ordupd-1",
            idempotencyKey = "idem-ordupd-1",
            orderId = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
            targetStatus = StoreOrderStatus.CONFIRMED,
            reason = null,
            actorId = "cashier-1",
            deviceId = "dev-1",
            updatedAtIso = "2026-11-16T09:00:00Z",
        )
        val payload = JSONObject(SupabaseOrderStatusClient.buildRpcPayloadJson(req))
        assertEquals("ordupd-1", payload.getString("p_client_update_id"))
        assertEquals("confirmed", payload.getString("p_target_status"))

        val committed = SupabaseOrderStatusClient.parseRpcResponse(
            200,
            """{"status":"COMMITTED","server_event_id":"evt-9","order_id":"9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d","new_status":"confirmed","already_committed":false}""",
        )
        assertTrue(committed is OrderStatusRpcOutcome.CommittedOnline)
        assertEquals("confirmed", (committed as OrderStatusRpcOutcome.CommittedOnline).newRawStatus)

        val rejected = SupabaseOrderStatusClient.parseRpcResponse(
            200,
            """{"status":"INVALID_TRANSITION","current_status":"fulfilled","message":"Cannot change order"}""",
        )
        assertTrue(rejected is OrderStatusRpcOutcome.Rejected)
        assertEquals("fulfilled", (rejected as OrderStatusRpcOutcome.Rejected).currentServerRawStatus)

        val missing = SupabaseOrderStatusClient.parseRpcResponse(
            404,
            """{"code":"PGRST202","message":"Could not find the function"}""",
        )
        assertTrue(missing is OrderStatusRpcOutcome.RpcMissing)
    }

    @Test
    fun `sync queue codec round-trips ORDER_UPDATE payload and rejects invalid cancel`() {
        val req = AndroidOrderStatusRpcRequest(
            clientUpdateId = "ordupd-42",
            idempotencyKey = "idem-ordupd-42",
            orderId = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
            targetStatus = StoreOrderStatus.CANCELLED,
            reason = "Out of stock for pickup",
            actorId = "staff-2",
            deviceId = "android-2",
            updatedAtIso = "2026-11-16T09:15:00Z",
        )
        val json = SyncQueuePolicy.serializeOrderStatusRpcRequest(req)
        val event = SyncQueueEventEntity(
            eventId = "sync-ord-ordupd-42",
            eventType = SyncEventType.ORDER_UPDATE,
            entityId = "ordupd-42",
            dependencyEventId = null,
            payloadJson = json,
            actorId = "staff-2",
            deviceId = "android-2",
            status = "QUEUED",
            createdAtEpochMs = 1_700_000_000_000L,
        )
        val parsed = SyncQueuePolicy.parseOrderStatusRpcRequest(event)
        assertNotNull(parsed)
        assertEquals(StoreOrderStatus.CANCELLED, parsed!!.targetStatus)
        assertEquals("Out of stock for pickup", parsed.reason)

        val brokenCancel = event.copy(
            payloadJson = JSONObject(json).put("reason", "").toString(),
        )
        assertNull(SyncQueuePolicy.parseOrderStatusRpcRequest(brokenCancel))
    }
}
