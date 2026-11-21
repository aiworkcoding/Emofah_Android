package com.emofah.agencies

import com.emofah.agencies.data.local.dao.LocalSaleWithItems
import com.emofah.agencies.data.local.entities.LocalSaleEntity
import com.emofah.agencies.data.local.entities.LocalSaleItemEntity
import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import com.emofah.agencies.data.sync.SyncEventType
import com.emofah.agencies.data.sync.SyncQueuePolicy
import com.emofah.agencies.sales.AndroidSaleReversalRpcRequest
import com.emofah.agencies.sales.SaleHistoryMapper
import com.emofah.agencies.sales.SaleHistorySyncBadge
import com.emofah.agencies.sales.SaleReversalPolicy
import com.emofah.agencies.sales.SaleReversalRpcOutcome
import com.emofah.agencies.sales.StaffRole
import com.emofah.agencies.sales.SupabaseSaleReversalClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SaleHistoryCorrectionStage24Test {

    private fun sampleSale(
        clientSaleId: String = "sale-1001",
        serverSaleId: Long? = 77L,
        syncStatus: String = SyncState.SERVER_CONFIRMED,
        isReversed: Boolean = false,
        reversalReason: String? = null,
        createdAtEpochMs: Long = 1_763_120_000_000L,
        unitSellPrice: Double = 150.0,
        originalSellPrice: Double = 150.0,
        overrideReason: String? = null,
    ): LocalSaleWithItems {
        val sale = LocalSaleEntity(
            clientSaleId = clientSaleId,
            serverSaleId = serverSaleId,
            idempotencyKey = "idem-$clientSaleId",
            totalAmount = unitSellPrice * 2,
            paymentMethod = "MPESA",
            actorId = "staff_jane",
            deviceId = "android-dev1",
            source = "ANDROID",
            saleDateIso = "2026-11-14T10:15:00Z",
            createdAtEpochMs = createdAtEpochMs,
            syncStatus = syncStatus,
            isReversed = isReversed,
            reversalReason = reversalReason,
        )
        val item = LocalSaleItemEntity(
            id = "$clientSaleId-item-1",
            clientSaleId = clientSaleId,
            productId = 18L,
            productNameSnapshot = "Masking tape (medium)",
            quantity = 2,
            unitSellPrice = unitSellPrice,
            originalSellPrice = originalSellPrice,
            priceOverrideReason = overrideReason,
            lineTotal = unitSellPrice * 2,
        )
        return LocalSaleWithItems(sale = sale, items = listOf(item))
    }

    @Test
    fun partitionTodayAndEarlierMapsSyncBadgesAndOverridesCorrectly() {
        val startOfToday = 1_763_100_000_000L
        val todaySynced = sampleSale(
            clientSaleId = "today-synced",
            createdAtEpochMs = startOfToday + 3_600_000L,
            syncStatus = SyncState.SERVER_CONFIRMED,
            unitSellPrice = 135.0,
            originalSellPrice = 150.0,
            overrideReason = "Damaged box",
        )
        val todayPending = sampleSale(
            clientSaleId = "today-pending",
            serverSaleId = null,
            createdAtEpochMs = startOfToday + 1_800_000L,
            syncStatus = SyncState.LOCAL_PENDING,
        )
        val todayReversed = sampleSale(
            clientSaleId = "today-rev",
            createdAtEpochMs = startOfToday + 900_000L,
            isReversed = true,
            reversalReason = "Wrong variant selected",
        )
        val yesterdaySale = sampleSale(
            clientSaleId = "yesterday",
            createdAtEpochMs = startOfToday - 60_000L,
        )

        val (today, earlier) = SaleHistoryMapper.partitionTodayAndEarlier(
            records = listOf(todaySynced, todayPending, todayReversed, yesterdaySale),
            startOfTodayEpochMs = startOfToday,
            locale = Locale.US,
        )

        assertEquals(3, today.size)
        assertEquals(1, earlier.size)
        assertEquals("Masking tape (medium)", today[0].primaryProductName)
        assertEquals(2, today[0].totalQuantity)
        assertEquals("M-Pesa", today[0].paymentLabel)
        assertEquals(SaleHistorySyncBadge.SERVER_CONFIRMED, today[0].syncBadge)
        assertTrue(today[0].hasPriceOverride)
        assertTrue(today[0].canReverse)

        assertEquals(SaleHistorySyncBadge.LOCAL_PENDING, today[1].syncBadge)
        assertEquals(SaleHistorySyncBadge.REVERSED, today[2].syncBadge)
        assertFalse(today[2].canReverse)
        assertFalse(earlier[0].isToday)
    }

    @Test
    fun reversalPolicyEnforcesRoleReasonAndBlocksDoubleReversal() {
        val activeSale = sampleSale().sale

        val cashierAttempt = SaleReversalPolicy.validateReversalRequest(
            sale = activeSale,
            role = StaffRole.CASHIER,
            reason = "Customer changed mind",
        )
        assertTrue(cashierAttempt.isAllowed)
        assertEquals("Customer changed mind", cashierAttempt.cleanReason)

        val blankReasonAttempt = SaleReversalPolicy.validateReversalRequest(
            sale = activeSale,
            role = StaffRole.SUPERVISOR,
            reason = "  ",
        )
        assertFalse(blankReasonAttempt.isAllowed)

        val validSupervisor = SaleReversalPolicy.validateReversalRequest(
            sale = activeSale,
            role = StaffRole.SUPERVISOR,
            reason = "  Wrong size picked  ",
        )
        assertTrue(validSupervisor.isAllowed)
        assertEquals("Wrong size picked", validSupervisor.cleanReason)

        val alreadyReversedSale = sampleSale(isReversed = true, reversalReason = "Done").sale
        val doubleReversalAttempt = SaleReversalPolicy.validateReversalRequest(
            sale = alreadyReversedSale,
            role = StaffRole.OWNER_MANAGER,
            reason = "Try reversing twice",
        )
        assertFalse(doubleReversalAttempt.isAllowed)
        assertTrue(doubleReversalAttempt.errorMessage.orEmpty().contains("already been reversed"))
    }

    @Test
    fun rpcReversalPayloadAndResponsesParseCorrectly() {
        val req = AndroidSaleReversalRpcRequest(
            clientSaleId = "sale-uuid-24",
            serverSaleId = 90L,
            productId = 18L,
            quantity = 2,
            totalAmount = 300.0,
            idempotencyKey = SaleReversalPolicy.buildReversalIdempotencyKey("sale-uuid-24"),
            reason = "Wrong product scanned",
            paymentMethod = "CASH",
            actorId = "sup_mary",
            actorRole = StaffRole.SUPERVISOR.name,
            deviceId = "android-dev2",
            reversedAtIso = "2026-11-14T11:00:00Z",
        )

        val rpcJson = JSONObject(SupabaseSaleReversalClient.buildRpcPayloadJson(req))
        assertEquals("sale-uuid-24", rpcJson.getString("p_client_sale_id"))
        assertEquals(90L, rpcJson.getLong("p_server_sale_id"))
        assertEquals(18L, rpcJson.getLong("p_product_id"))
        assertEquals(2, rpcJson.getInt("p_qty"))
        assertEquals("reversal-sale-uuid-24", rpcJson.getString("p_idempotency_key"))
        assertEquals("SUPERVISOR", rpcJson.getString("p_actor_role"))

        val committed = SupabaseSaleReversalClient.parseRpcResponse(
            200,
            """{"status":"COMMITTED","server_sale_id":90,"restored_qty":2,"remaining_qty":12,"already_reversed":false}""",
        )
        assertTrue(committed is SaleReversalRpcOutcome.CommittedOnline)
        committed as SaleReversalRpcOutcome.CommittedOnline
        assertEquals(12, committed.remainingQty)
        assertFalse(committed.alreadyReversed)

        val idempotentReplay = SupabaseSaleReversalClient.parseRpcResponse(
            200,
            """{"status":"ALREADY_REVERSED","server_sale_id":90,"remaining_qty":12,"already_reversed":true}""",
        )
        assertTrue(idempotentReplay is SaleReversalRpcOutcome.CommittedOnline)
        assertTrue((idempotentReplay as SaleReversalRpcOutcome.CommittedOnline).alreadyReversed)

        val unauthorized = SupabaseSaleReversalClient.parseRpcResponse(
            200,
            """{"status":"UNAUTHORIZED_ROLE","message":"Supervisor required"}""",
        )
        assertTrue(unauthorized is SaleReversalRpcOutcome.Rejected)

        val missingRpc = SupabaseSaleReversalClient.parseRpcResponse(
            404,
            """{"code":"PGRST202","message":"Could not find function public.reverse_android_sale"}""",
        )
        assertTrue(missingRpc is SaleReversalRpcOutcome.RpcMissing)
    }

    @Test
    fun syncQueueParsesQueuedSaleReversalPayloadDeterministically() {
        val queueEvent = SyncQueueEventEntity(
            eventId = "sync-rev-sale-88",
            eventType = SyncEventType.SALE_REVERSAL,
            entityId = "sale-88",
            payloadJson = """
                {
                  "client_sale_id": "sale-88",
                  "server_sale_id": 144,
                  "product_id": 18,
                  "qty": 3,
                  "total": 450.0,
                  "idempotency_key": "reversal-sale-88",
                  "reason": "Customer return",
                  "payment_method": "MPESA",
                  "actor_id": "mgr_paul",
                  "actor_role": "OWNER_MANAGER",
                  "device_id": "android-dev9",
                  "reversed_at": "2026-11-14T12:30:00Z"
                }
            """.trimIndent(),
            actorId = "mgr_paul",
            deviceId = "android-dev9",
            createdAtEpochMs = 1_763_123_400_000L,
        )

        val parsed = SyncQueuePolicy.parseSaleReversalRpcRequest(queueEvent)
        assertNotNull(parsed)
        assertEquals("sale-88", parsed?.clientSaleId)
        assertEquals(144L, parsed?.serverSaleId)
        assertEquals(3, parsed?.quantity)
        assertEquals("OWNER_MANAGER", parsed?.actorRole)

        val invalid = queueEvent.copy(payloadJson = """{"client_sale_id":"sale-88","reason":""}""")
        assertNull(SyncQueuePolicy.parseSaleReversalRpcRequest(invalid))
    }
}
