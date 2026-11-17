package com.emofah.agencies

import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.sync.SyncEventType
import com.emofah.agencies.data.sync.SyncQueuePolicy
import com.emofah.agencies.purchases.AndroidPurchaseLineRequest
import com.emofah.agencies.purchases.AndroidPurchaseRpcRequest
import com.emofah.agencies.purchases.PurchaseItemCategory
import com.emofah.agencies.purchases.PurchaseRpcOutcome
import com.emofah.agencies.purchases.SupabasePurchaseClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseSubmissionStage62Test {

    @Test
    fun buildsRpcPayloadWithResaleNewProductConsumableAndEquipment() {
        val req = AndroidPurchaseRpcRequest(
            clientPurchaseId = "purch-101",
            idempotencyKey = "idem-purch-101",
            supplierName = "Text Book Centre",
            invoiceRef = "TBC-902",
            receiptDateText = "16/11/2026",
            totalAmount = 18500.0,
            extractionSource = "CLOUD_AI_AND_OCR",
            isHumanConfirmed = true,
            items = listOf(
                AndroidPurchaseLineRequest(
                    rawName = "Bic Ball Pen Blue Box",
                    itemCategory = PurchaseItemCategory.RESALE_PRODUCT,
                    matchedProductId = 45L,
                    quantity = 5,
                    unitCost = 300.0,
                    lineTotal = 1500.0,
                ),
                AndroidPurchaseLineRequest(
                    rawName = "Casio FX-82MS Scientific Calculator",
                    itemCategory = PurchaseItemCategory.RESALE_PRODUCT,
                    localTempProductId = -1L,
                    registerAsNewProduct = true,
                    newProductSellPrice = 1950.0,
                    newProductCategory = "Stationery",
                    quantity = 4,
                    unitCost = 1400.0,
                    lineTotal = 5600.0,
                ),
                AndroidPurchaseLineRequest(
                    rawName = "Epson 003 Ink Bottle Black",
                    itemCategory = PurchaseItemCategory.CONSUMABLE,
                    quantity = 3,
                    unitCost = 800.0,
                    lineTotal = 2400.0,
                ),
                AndroidPurchaseLineRequest(
                    rawName = "A3 Heavy Duty Guillotine Trimmer",
                    itemCategory = PurchaseItemCategory.EQUIPMENT,
                    quantity = 1,
                    unitCost = 9000.0,
                    lineTotal = 9000.0,
                ),
            ),
            actorId = "cashier-1",
            deviceId = "dev-1",
            recordedAtIso = "2026-11-16T08:30:00Z",
            recordFinancialOutflow = true,
        )

        val rpcJson = JSONObject(SupabasePurchaseClient.buildRpcPayloadJson(req))
        assertEquals("purch-101", rpcJson.getString("p_client_purchase_id"))
        assertTrue(rpcJson.getBoolean("p_is_human_confirmed"))
        assertEquals(18500.0, rpcJson.getDouble("p_total_amount"), 0.01)

        val items = rpcJson.getJSONArray("p_items")
        assertEquals(4, items.length())
        assertEquals(45L, items.getJSONObject(0).getLong("matched_product_id"))
        assertTrue(items.getJSONObject(1).getBoolean("register_as_new_product"))
        assertEquals(1950.0, items.getJSONObject(1).getDouble("new_product_sell_price"), 0.01)
        assertEquals("CONSUMABLE", items.getJSONObject(2).getString("item_category"))
        assertTrue(items.getJSONObject(2).isNull("matched_product_id"))
        assertEquals("EQUIPMENT", items.getJSONObject(3).getString("item_category"))
    }

    @Test
    fun parsesResolvedProductsAndIdempotentDuplicateFromRpcResponse() {
        val body = """
            {
              "status": "COMMITTED",
              "server_purchase_id": "uuid-purch-1",
              "total_amount": 7100,
              "already_committed": false,
              "resolved_products": [
                {
                  "line_index": 1,
                  "item_category": "RESALE_PRODUCT",
                  "local_temp_product_id": -1,
                  "product_id": 212,
                  "product_name": "Casio FX-82MS Scientific Calculator",
                  "category": "Stationery",
                  "sell_price": 1950,
                  "buy_price": 1400,
                  "qty": 4,
                  "server_qty_after": 4,
                  "registered_new_product": true
                },
                {
                  "line_index": 2,
                  "item_category": "CONSUMABLE",
                  "product_id": null,
                  "product_name": "Epson 003 Ink Bottle",
                  "qty": 3,
                  "server_qty_after": null,
                  "registered_new_product": false
                }
              ]
            }
        """.trimIndent()

        val outcome = SupabasePurchaseClient.parseRpcResponse(200, body)
        assertTrue(outcome is PurchaseRpcOutcome.CommittedOnline)
        val committed = outcome as PurchaseRpcOutcome.CommittedOnline
        assertFalse(committed.alreadyCommitted)
        assertEquals(2, committed.resolvedProducts.size)
        assertEquals(212L, committed.resolvedProducts[0].serverProductId)
        assertEquals(-1L, committed.resolvedProducts[0].localTempProductId)
        assertTrue(committed.resolvedProducts[0].registeredNewProduct)
        assertNull(committed.resolvedProducts[1].serverProductId)
        assertEquals(PurchaseItemCategory.CONSUMABLE, committed.resolvedProducts[1].itemCategory)
    }

    @Test
    fun roundTripsPurchaseSyncQueueSerializationAndRejectsUnconfirmedPayloads() {
        val req = AndroidPurchaseRpcRequest(
            clientPurchaseId = "purch-202",
            idempotencyKey = "idem-purch-202",
            supplierName = "Supplier X",
            invoiceRef = "SX-1",
            receiptDateText = "16/11/2026",
            totalAmount = 600.0,
            extractionSource = "OFFLINE_OCR",
            isHumanConfirmed = true,
            items = listOf(
                AndroidPurchaseLineRequest(
                    rawName = "Marker Pen Black",
                    itemCategory = PurchaseItemCategory.RESALE_PRODUCT,
                    localTempProductId = -2L,
                    registerAsNewProduct = true,
                    newProductSellPrice = 100.0,
                    newProductCategory = "Stationery",
                    quantity = 10,
                    unitCost = 60.0,
                    lineTotal = 600.0,
                )
            ),
            actorId = "cashier-1",
            deviceId = "dev-1",
            recordedAtIso = "2026-11-16T09:00:00Z",
            recordFinancialOutflow = true,
        )

        val serialized = SyncQueuePolicy.serializePurchaseRpcRequest(req)
        val queueEvt = SyncQueueEventEntity(
            eventId = "sync-purch-purch-202",
            eventType = SyncEventType.PURCHASE_RECEIPT,
            entityId = "purch-202",
            payloadJson = serialized,
            actorId = "cashier-1",
            deviceId = "dev-1",
            createdAtEpochMs = 1234L,
        )

        val parsed = SyncQueuePolicy.parsePurchaseRpcRequest(queueEvt)
        assertNotNull(parsed)
        assertEquals("purch-202", parsed!!.clientPurchaseId)
        assertEquals(-2L, parsed.items.first().localTempProductId)

        val unconfirmedPayload = JSONObject(serialized).apply { put("is_human_confirmed", false) }.toString()
        val rejected = SyncQueuePolicy.parsePurchaseRpcRequest(queueEvt.copy(payloadJson = unconfirmedPayload))
        assertNull(rejected)
    }
}
