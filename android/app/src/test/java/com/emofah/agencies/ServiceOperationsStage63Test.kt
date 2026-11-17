package com.emofah.agencies

import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.sync.SyncEventType
import com.emofah.agencies.data.sync.SyncQueuePolicy
import com.emofah.agencies.sales.PaymentMethod
import com.emofah.agencies.services.AndroidServiceRpcRequest
import com.emofah.agencies.services.ServiceConsumableDraft
import com.emofah.agencies.services.ServiceConsumableRpcLine
import com.emofah.agencies.services.ServiceOperationDraft
import com.emofah.agencies.services.ServiceOperationType
import com.emofah.agencies.services.ServiceOperationValidator
import com.emofah.agencies.services.ServiceRpcOutcome
import com.emofah.agencies.services.SupabaseServiceOperationClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceOperationsStage63Test {

    @Test
    fun validatorEnforcesPositiveRevenueAndRealCatalogueSkuBeforeStockDeduction() {
        val zeroRevenueDraft = ServiceOperationDraft(
            serviceType = ServiceOperationType.PRINTING,
            quantityOrDuration = 5,
            unitPrice = 0.0,
        )
        val zeroValidation = ServiceOperationValidator.validate(zeroRevenueDraft)
        assertFalse(zeroValidation.isValid)

        val unlinkedDeductionDraft = ServiceOperationDraft(
            serviceType = ServiceOperationType.PHOTOCOPYING,
            quantityOrDuration = 10,
            unitPrice = 5.0,
            consumables = listOf(
                ServiceConsumableDraft(
                    consumableName = "A4 Paper",
                    usageQty = 10.0,
                    usageUnit = "sheets",
                    linkedProductId = null,
                    stockDeductQty = 1,
                )
            ),
        )
        val unlinkedValidation = ServiceOperationValidator.validate(unlinkedDeductionDraft)
        assertFalse(unlinkedValidation.isValid)

        val validDraft = ServiceOperationDraft(
            serviceType = ServiceOperationType.EGOV_ASSISTANCE,
            quantityOrDuration = 1,
            unitPrice = 300.0,
            paymentMethod = PaymentMethod.MPESA,
            customerNote = "KRA NIL Return",
            consumables = listOf(
                ServiceConsumableDraft(
                    consumableName = "A4 Paper Ream",
                    usageQty = 2.0,
                    usageUnit = "sheets",
                    linkedProductId = 88L,
                    linkedProductName = "A4 Paper 80gsm",
                    stockDeductQty = 1,
                )
            ),
        )
        val validResult = ServiceOperationValidator.validate(validDraft)
        assertTrue(validResult.isValid)
        assertEquals(300.0, validDraft.effectiveRevenue, 0.01)
    }

    @Test
    fun buildsServiceRpcPayloadAndParsesCommittedAndDuplicateAndRejectionResponses() {
        val req = AndroidServiceRpcRequest(
            clientServiceId = "svc-101",
            idempotencyKey = "idem-svc-101",
            serviceType = ServiceOperationType.INTERNET_BROWSING,
            serviceLabel = "Internet Browsing",
            quantityOrDuration = 30,
            unitLabel = "minutes",
            unitPrice = 2.0,
            totalRevenue = 60.0,
            paymentMethod = "CASH",
            customerNote = "Station 2",
            consumables = listOf(
                ServiceConsumableRpcLine(
                    consumableName = "A4 Paper",
                    usageQty = 2.0,
                    usageUnit = "sheets",
                    linkedProductId = 55L,
                    stockDeductQty = 1,
                ),
                ServiceConsumableRpcLine(
                    consumableName = "Printer Ink",
                    usageQty = 1.0,
                    usageUnit = "ml",
                    linkedProductId = null,
                    stockDeductQty = 0,
                ),
            ),
            actorId = "cashier-1",
            deviceId = "dev-1",
            performedAtIso = "2026-11-16T10:15:00Z",
        )

        val payloadJson = JSONObject(SupabaseServiceOperationClient.buildRpcPayloadJson(req))
        assertEquals("svc-101", payloadJson.getString("p_client_service_id"))
        assertEquals("INTERNET_BROWSING", payloadJson.getString("p_service_type"))
        assertEquals(60.0, payloadJson.getDouble("p_total_revenue"), 0.01)

        val consArr = payloadJson.getJSONArray("p_consumables")
        assertEquals(2, consArr.length())
        assertEquals(55L, consArr.getJSONObject(0).getLong("linked_product_id"))
        assertEquals(1, consArr.getJSONObject(0).getInt("stock_deduct_qty"))
        assertTrue(consArr.getJSONObject(1).isNull("linked_product_id"))

        val committedBody = """
            {
              "status": "COMMITTED",
              "server_service_id": "srv-uuid-1",
              "total_revenue": 60,
              "already_committed": false,
              "updated_products": [
                {
                  "product_id": 55,
                  "qty_deducted": 1,
                  "server_qty_after": 19
                }
              ]
            }
        """.trimIndent()

        val committed = SupabaseServiceOperationClient.parseRpcResponse(200, committedBody)
        assertTrue(committed is ServiceRpcOutcome.CommittedOnline)
        val ok = committed as ServiceRpcOutcome.CommittedOnline
        assertFalse(ok.alreadyCommitted)
        assertEquals(1, ok.updatedProducts.size)
        assertEquals(55L, ok.updatedProducts[0].productId)
        assertEquals(19, ok.updatedProducts[0].serverQtyAfter)

        val dupBody = """
            {
              "status": "DUPLICATE_IGNORED",
              "server_service_id": "srv-uuid-1",
              "total_revenue": 60,
              "already_committed": true,
              "updated_products": []
            }
        """.trimIndent()
        val dup = SupabaseServiceOperationClient.parseRpcResponse(200, dupBody)
        assertTrue(dup is ServiceRpcOutcome.CommittedOnline)
        assertTrue((dup as ServiceRpcOutcome.CommittedOnline).alreadyCommitted)

        val notFound = SupabaseServiceOperationClient.parseRpcResponse(
            200,
            """{"status":"PRODUCT_NOT_FOUND","message":"Product 999 not found"}""",
        )
        assertTrue(notFound is ServiceRpcOutcome.Rejected)
        assertEquals("PRODUCT_NOT_FOUND", (notFound as ServiceRpcOutcome.Rejected).code)
    }

    @Test
    fun roundTripsServiceOperationSyncQueueSerialization() {
        val req = AndroidServiceRpcRequest(
            clientServiceId = "svc-202",
            idempotencyKey = "idem-svc-202",
            serviceType = ServiceOperationType.SCANNING,
            serviceLabel = "Scanning",
            quantityOrDuration = 4,
            unitLabel = "pages",
            unitPrice = 20.0,
            totalRevenue = 80.0,
            paymentMethod = "MPESA",
            customerNote = "Passport scan",
            consumables = emptyList(),
            actorId = "cashier-2",
            deviceId = "dev-2",
            performedAtIso = "2026-11-16T11:00:00Z",
        )

        val json = SyncQueuePolicy.serializeServiceOperationRpcRequest(req)
        val queueEvt = SyncQueueEventEntity(
            eventId = "sync-svc-svc-202",
            eventType = SyncEventType.SERVICE_OPERATION,
            entityId = "svc-202",
            payloadJson = json,
            actorId = "cashier-2",
            deviceId = "dev-2",
            createdAtEpochMs = 4567L,
        )

        val parsed = SyncQueuePolicy.parseServiceOperationRpcRequest(queueEvt)
        assertNotNull(parsed)
        assertEquals("svc-202", parsed!!.clientServiceId)
        assertEquals(ServiceOperationType.SCANNING, parsed.serviceType)
        assertEquals(80.0, parsed.totalRevenue, 0.01)

        val badJson = JSONObject(json).apply { put("total_revenue", -5.0) }.toString()
        assertNull(SyncQueuePolicy.parseServiceOperationRpcRequest(queueEvt.copy(payloadJson = badJson)))
    }
}
