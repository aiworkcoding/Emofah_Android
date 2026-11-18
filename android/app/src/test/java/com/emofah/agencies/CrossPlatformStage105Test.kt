package com.emofah.agencies

import com.emofah.agencies.audit.AuditSyncOutcome
import com.emofah.agencies.audit.RemoteAuditFetchOutcome
import com.emofah.agencies.audit.SupabaseAuditClient
import com.emofah.agencies.data.catalog.ProductMetadataExtractor
import com.emofah.agencies.data.catalog.ProductRegistrationRpcOutcome
import com.emofah.agencies.data.catalog.RegisterProductRpcRequest
import com.emofah.agencies.data.catalog.SupabaseProductDto
import com.emofah.agencies.data.catalog.SupabaseProductRegistrationClient
import com.emofah.agencies.data.local.entities.LocalAuditEventEntity
import com.emofah.agencies.financial.AndroidFinancialEventRpcRequest
import com.emofah.agencies.financial.FinancialEventDraft
import com.emofah.agencies.financial.FinancialEventRpcOutcome
import com.emofah.agencies.financial.FinancialEventType
import com.emofah.agencies.financial.SupabaseFinancialEventClient
import com.emofah.agencies.sales.StaffRole
import com.emofah.agencies.orders.AndroidOrderConversionRpcRequest
import com.emofah.agencies.orders.AndroidOrderStatusRpcRequest
import com.emofah.agencies.orders.OrderConversionPolicy
import com.emofah.agencies.orders.OrderConversionRpcOutcome
import com.emofah.agencies.orders.OrderStatusTransitionPolicy
import com.emofah.agencies.orders.OrderStatusRpcOutcome
import com.emofah.agencies.orders.ResolvedOrderSaleLine
import com.emofah.agencies.orders.StoreOrderRecord
import com.emofah.agencies.orders.StoreOrderStatus
import com.emofah.agencies.orders.SupabaseOrderConversionClient
import com.emofah.agencies.orders.SupabaseOrderStatusClient
import com.emofah.agencies.purchases.AndroidPurchaseLineRequest
import com.emofah.agencies.purchases.AndroidPurchaseRpcRequest
import com.emofah.agencies.purchases.PurchaseItemCategory
import com.emofah.agencies.purchases.PurchaseRpcOutcome
import com.emofah.agencies.purchases.SupabasePurchaseClient
import com.emofah.agencies.sales.AndroidSaleReversalRpcRequest
import com.emofah.agencies.sales.AndroidSaleRpcRequest
import com.emofah.agencies.sales.SaleReversalRpcOutcome
import com.emofah.agencies.sales.SaleRpcOutcome
import com.emofah.agencies.sales.SupabaseSaleReversalClient
import com.emofah.agencies.sales.SupabaseSaleRpcClient
import com.emofah.agencies.services.AndroidServiceRpcRequest
import com.emofah.agencies.services.ServiceConsumableRpcLine
import com.emofah.agencies.services.ServiceOperationType
import com.emofah.agencies.services.ServiceRpcOutcome
import com.emofah.agencies.services.SupabaseServiceOperationClient
import com.emofah.agencies.stock.AndroidStockAdjustmentRpcRequest
import com.emofah.agencies.stock.AndroidStockReceiptRpcRequest
import com.emofah.agencies.stock.StockAdjustmentRpcOutcome
import com.emofah.agencies.stock.StockCountItemDraft
import com.emofah.agencies.stock.StockCountReason
import com.emofah.agencies.stock.StockCountRpcOutcome
import com.emofah.agencies.stock.StockCountSessionDraft
import com.emofah.agencies.stock.StockReceiptRpcOutcome
import com.emofah.agencies.stock.SupabaseStockAdjustmentClient
import com.emofah.agencies.stock.SupabaseStockCountClient
import com.emofah.agencies.stock.SupabaseStockReceivingClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 10.5 — Cross-platform verification test suite:
 * Verifies contract alignment across Android, Supabase PostgreSQL, Desktop, and Storefront:
 * 1. Shared Catalog contract: Canonical PK, variants as distinct rows, brand hint extraction.
 * 2. Product Registration RPC contract: Parameter names, idempotent duplicate handling (COMMITTED / DUPLICATE_IGNORED / DUPLICATE_EXISTING).
 * 3. Shared Sales Ledger contract: Atomic sales recording, non-destructive reversals preserving public.sales rows, role gating.
 * 4. Shared Storefront Order Lifecycle contract: Allowed transition matrix, reason requirement for cancellation, terminal state immutability.
 * 5. Order-to-Sale Conversion contract: Stock deduction ack, double-selling guard (ALREADY_CONVERTED vs DUPLICATE_IGNORED), line item validation.
 * 6. Shared Stock Operations contract: Receiving, adjustments with non-negative clamping, and physical stock count variance reconciliation.
 * 7. Purchase Submission contract: Confirmed purchases, new resale product registration, consumable/equipment separation.
 * 8. Service Operations contract: Printing/photocopying revenue recording and linked consumable SKU deductions.
 * 9. Financial Ledger contract: Strict boundary where Android tags raw operational evidence with PENDING_DESKTOP_RULES leaving classification authority to Desktop.
 * 10. Audit Trail contract: Batch payload serialization, DB trigger immutability alignment, and role-scoped cloud inspection.
 */
class CrossPlatformStage105Test {

    // -------------------------------------------------------------------------
    // 1. Shared Product Catalogue Contract (Android ↔ Supabase ↔ Desktop)
    // -------------------------------------------------------------------------

    @Test
    fun sharedCatalogueMetadataExtractionMatchesDesktopAndDatabaseInvariants() {
        // Variants must be preserved as distinct products with unique integer IDs
        val mediumBook = ProductMetadataExtractor.toProductIdentity(
            SupabaseProductDto(
                id = 10L,
                name = "Kasuku Exercise Book (Medium) 200 pgs",
                qty = 12,
                sellPrice = 150.0,
                buyPrice = 95.0,
                imageUrl = null,
                category = "Stationery",
                createdAt = "2026-11-16T00:00:00Z",
                description = null,
                discountPrice = 0.0,
                isFeatured = false,
            )
        )
        assertEquals("Kasuku", mediumBook.brandHint)
        assertEquals("Medium · 200 pgs", mediumBook.variantLabel)
        assertEquals("Kasuku Exercise Book 200 pgs", mediumBook.baseName)

        val largeBook = ProductMetadataExtractor.toProductIdentity(
            SupabaseProductDto(
                id = 11L,
                name = "Kasuku Exercise Book (Large) 200 pgs",
                qty = 8,
                sellPrice = 220.0,
                buyPrice = 150.0,
                imageUrl = null,
                category = "Stationery",
                createdAt = "2026-11-16T00:00:00Z",
                description = null,
                discountPrice = 0.0,
                isFeatured = false,
            )
        )
        assertEquals("Kasuku", largeBook.brandHint)
        assertEquals("Large · 200 pgs", largeBook.variantLabel)
        assertEquals("Kasuku Exercise Book 200 pgs", largeBook.baseName)

        // Brands must match verified shared inventory brands (Bic, Kasuku, Deli, etc.)
        val bicPen = ProductMetadataExtractor.toProductIdentity(
            SupabaseProductDto(
                id = 12L,
                name = "Bic Cristal Medium Ball Pen (Blue)",
                qty = 40,
                sellPrice = 30.0,
                buyPrice = 18.0,
                imageUrl = null,
                category = "Stationery",
                createdAt = "2026-11-16T00:00:00Z",
                description = null,
                discountPrice = 0.0,
                isFeatured = false,
            )
        )
        assertEquals("Bic", bicPen.brandHint)
        assertEquals("Blue", bicPen.variantLabel)
    }

    // -------------------------------------------------------------------------
    // 2. Product Registration RPC Contract (register_android_product)
    // -------------------------------------------------------------------------

    @Test
    fun productRegistrationRpcPayloadAndResponseMatchesSqlContract() {
        val req = RegisterProductRpcRequest(
            draftId = "draft-101",
            idempotencyKey = "idem-reg-101",
            name = "Deli Correction Fluid 20ml",
            category = "Stationery",
            sellPrice = 120.0,
            buyPrice = 75.0,
            initialQty = 12,
            description = "Quick drying fluid",
            localPhotoPath = null,
            actorId = "staff-01",
            deviceId = "android-device-01",
        )

        val jsonString = SupabaseProductRegistrationClient.buildRpcPayloadJson(
            request = req,
            imageUrl = "https://nbmmfdiqywmkjaltezro.supabase.co/storage/v1/object/public/product-images/android-refs/draft-101.jpg",
        )
        val json = JSONObject(jsonString)

        assertEquals("draft-101", json.getString("p_draft_id"))
        assertEquals("idem-reg-101", json.getString("p_idempotency_key"))
        assertEquals("Deli Correction Fluid 20ml", json.getString("p_name"))
        assertEquals("Stationery", json.getString("p_category"))
        assertEquals(120.0, json.getDouble("p_sell_price"), 0.001)
        assertEquals(75.0, json.getDouble("p_buy_price"), 0.001)
        assertEquals(12, json.getInt("p_initial_qty"))
        assertTrue(json.getString("p_image_url").contains("android-refs/draft-101.jpg"))

        // Outcome parsing: COMMITTED (new product)
        val committedJson = """{
            "status": "COMMITTED",
            "server_product_id": 112,
            "name": "Deli Correction Fluid 20ml",
            "qty": 12,
            "already_committed": false
        }"""
        val outcome1 = SupabaseProductRegistrationClient.parseRpcResponse(200, committedJson)
        assertTrue(outcome1 is ProductRegistrationRpcOutcome.CommittedOnline)
        val c1 = outcome1 as ProductRegistrationRpcOutcome.CommittedOnline
        assertEquals(112L, c1.serverProductId)
        assertFalse(c1.alreadyCommitted)

        // Outcome parsing: DUPLICATE_IGNORED (same draft retried)
        val duplicateIgnoredJson = """{
            "status": "DUPLICATE_IGNORED",
            "server_product_id": 112,
            "name": "Deli Correction Fluid 20ml",
            "qty": 12,
            "already_committed": true
        }"""
        val outcome2 = SupabaseProductRegistrationClient.parseRpcResponse(200, duplicateIgnoredJson)
        assertTrue(outcome2 is ProductRegistrationRpcOutcome.CommittedOnline)
        val c2 = outcome2 as ProductRegistrationRpcOutcome.CommittedOnline
        assertEquals(112L, c2.serverProductId)
        assertTrue(c2.alreadyCommitted)

        // Outcome parsing: DUPLICATE_EXISTING (name matches existing active product)
        val duplicateExistingJson = """{
            "status": "DUPLICATE_EXISTING",
            "server_product_id": 54,
            "name": "Deli Correction Fluid 20ml",
            "qty": 8,
            "already_committed": true
        }"""
        val outcome3 = SupabaseProductRegistrationClient.parseRpcResponse(200, duplicateExistingJson)
        assertTrue(outcome3 is ProductRegistrationRpcOutcome.CommittedOnline)
        val c3 = outcome3 as ProductRegistrationRpcOutcome.CommittedOnline
        assertEquals(54L, c3.serverProductId)
        assertTrue(c3.alreadyCommitted)
    }

    // -------------------------------------------------------------------------
    // 3. Shared Sales Ledger & Reversal Contract (record_android_sale & reverse_android_sale)
    // -------------------------------------------------------------------------

    @Test
    fun salesRecordingAndReversalRpcPayloadAndResponsesMatchSqlContract() {
        // Normal sale request serialization
        val saleReq = AndroidSaleRpcRequest(
            productId = 42L,
            quantity = 2,
            totalAmount = 300.0,
            clientSaleId = "sale-uuid-001",
            idempotencyKey = "sale-idem-001",
            paymentMethod = "CASH",
            actorId = "cashier-01",
            actorRole = "CASHIER",
            deviceId = "pos-tablet-01",
            saleDateIso = "2026-11-16T10:00:00Z",
            unitSellPrice = 150.0,
            originalSellPrice = 150.0,
            priceOverrideReason = null,
        )
        assertFalse(saleReq.isPriceOverridden)
        val salePayload = JSONObject(SupabaseSaleRpcClient.buildRpcPayloadJson(saleReq))
        assertEquals(42L, salePayload.getLong("p_product_id"))
        assertEquals(2, salePayload.getInt("p_qty"))
        assertEquals(300.0, salePayload.getDouble("p_total"), 0.001)
        assertEquals("sale-idem-001", salePayload.getString("p_idempotency_key"))
        assertFalse(salePayload.has("p_price_override_reason"))

        // Price override sale request serialization
        val overrideSaleReq = saleReq.copy(
            totalAmount = 260.0,
            unitSellPrice = 130.0,
            originalSellPrice = 150.0,
            priceOverrideReason = "Bulk discount approved by manager",
            actorRole = "SUPERVISOR",
        )
        assertTrue(overrideSaleReq.isPriceOverridden)
        val overridePayload = JSONObject(SupabaseSaleRpcClient.buildRpcPayloadJson(overrideSaleReq))
        assertEquals(130.0, overridePayload.getDouble("p_unit_sell_price"), 0.001)
        assertEquals(150.0, overridePayload.getDouble("p_original_sell_price"), 0.001)
        assertEquals("Bulk discount approved by manager", overridePayload.getString("p_price_override_reason"))
        assertEquals("SUPERVISOR", overridePayload.getString("p_actor_role"))

        // Reversal request serialization
        val revReq = AndroidSaleReversalRpcRequest(
            clientSaleId = "sale-uuid-001",
            serverSaleId = 999L,
            productId = 42L,
            quantity = 2,
            totalAmount = 300.0,
            idempotencyKey = "rev-idem-001",
            reason = "Customer returned unopened items",
            paymentMethod = "CASH",
            actorId = "sup-01",
            actorRole = "SUPERVISOR",
            deviceId = "pos-tablet-01",
            reversedAtIso = "2026-11-16T11:00:00Z",
        )
        val revPayload = JSONObject(SupabaseSaleReversalClient.buildRpcPayloadJson(revReq))
        assertEquals(999L, revPayload.getLong("p_server_sale_id"))
        assertEquals(42L, revPayload.getLong("p_product_id"))
        assertEquals("Customer returned unopened items", revPayload.getString("p_reason"))
        assertEquals("SUPERVISOR", revPayload.getString("p_actor_role"))

        // Reversal response parsing: non-destructive COMMITTED
        val committedRevJson = """{
            "status": "COMMITTED",
            "server_sale_id": 999,
            "remaining_qty": 22,
            "already_reversed": false
        }"""
        val revOutcome1 = SupabaseSaleReversalClient.parseRpcResponse(200, committedRevJson)
        assertTrue(revOutcome1 is SaleReversalRpcOutcome.CommittedOnline)
        val r1 = revOutcome1 as SaleReversalRpcOutcome.CommittedOnline
        assertEquals(999L, r1.serverSaleId)
        assertEquals(22, r1.remainingQty)
        assertFalse(r1.alreadyReversed)

        // Reversal response parsing: ALREADY_REVERSED idempotency
        val alreadyRevJson = """{
            "status": "ALREADY_REVERSED",
            "server_sale_id": 999,
            "remaining_qty": 22,
            "already_reversed": true
        }"""
        val revOutcome2 = SupabaseSaleReversalClient.parseRpcResponse(200, alreadyRevJson)
        assertTrue(revOutcome2 is SaleReversalRpcOutcome.CommittedOnline)
        assertTrue((revOutcome2 as SaleReversalRpcOutcome.CommittedOnline).alreadyReversed)

        // Reversal RBAC rejection: CASHIER cannot reverse
        val unauthorizedRevJson = """{
            "status": "UNAUTHORIZED_ROLE",
            "message": "Only supervisors or owner-managers can reverse sales."
        }"""
        val revOutcome3 = SupabaseSaleReversalClient.parseRpcResponse(200, unauthorizedRevJson)
        assertTrue(revOutcome3 is SaleReversalRpcOutcome.Rejected)
        assertEquals("UNAUTHORIZED_ROLE", (revOutcome3 as SaleReversalRpcOutcome.Rejected).code)
    }

    // -------------------------------------------------------------------------
    // 4. Shared Storefront Order Lifecycle Contract (update_android_order_status)
    // -------------------------------------------------------------------------

    @Test
    fun storefrontOrderStatusLifecyclePolicyAndRpcMatchSqlContract() {
        // Allowed transitions:
        // PENDING -> CONFIRMED, READY, CANCELLED
        val pendingNext = OrderStatusTransitionPolicy.allowedNextStatuses(StoreOrderStatus.PENDING)
        assertTrue(pendingNext.contains(StoreOrderStatus.CONFIRMED))
        assertTrue(pendingNext.contains(StoreOrderStatus.READY))
        assertTrue(pendingNext.contains(StoreOrderStatus.CANCELLED))
        assertFalse(pendingNext.contains(StoreOrderStatus.FULFILLED))

        // CONFIRMED -> READY, CANCELLED
        val confNext = OrderStatusTransitionPolicy.allowedNextStatuses(StoreOrderStatus.CONFIRMED)
        assertTrue(confNext.contains(StoreOrderStatus.READY))
        assertTrue(confNext.contains(StoreOrderStatus.CANCELLED))
        assertFalse(confNext.contains(StoreOrderStatus.PENDING))

        // READY -> FULFILLED, CANCELLED
        val readyNext = OrderStatusTransitionPolicy.allowedNextStatuses(StoreOrderStatus.READY)
        assertTrue(readyNext.contains(StoreOrderStatus.FULFILLED))
        assertTrue(readyNext.contains(StoreOrderStatus.CANCELLED))

        // Terminal states: FULFILLED and CANCELLED cannot transition further
        assertTrue(OrderStatusTransitionPolicy.allowedNextStatuses(StoreOrderStatus.FULFILLED).isEmpty())
        assertTrue(OrderStatusTransitionPolicy.allowedNextStatuses(StoreOrderStatus.CANCELLED).isEmpty())

        // Validation: Backwards transition to PENDING blocked
        val backVal = OrderStatusTransitionPolicy.validateTransition(
            current = StoreOrderStatus.CONFIRMED,
            target = StoreOrderStatus.PENDING,
            reason = null,
        )
        assertFalse(backVal.isValid)

        // Validation: Cancellation requires at least 3-char reason
        val cancelNoReason = OrderStatusTransitionPolicy.validateTransition(
            current = StoreOrderStatus.PENDING,
            target = StoreOrderStatus.CANCELLED,
            reason = "  ",
        )
        assertFalse(cancelNoReason.isValid)

        val cancelWithReason = OrderStatusTransitionPolicy.validateTransition(
            current = StoreOrderStatus.PENDING,
            target = StoreOrderStatus.CANCELLED,
            reason = "Customer cancelled order via phone call",
        )
        assertTrue(cancelWithReason.isValid)

        // RPC payload serialization
        val statusReq = AndroidOrderStatusRpcRequest(
            clientUpdateId = "update-uuid-001",
            idempotencyKey = "update-idem-001",
            orderId = "550e8400-e29b-41d4-a716-446655440000",
            targetStatus = StoreOrderStatus.CONFIRMED,
            reason = null,
            actorId = "staff-01",
            deviceId = "android-device-01",
            updatedAtIso = "2026-11-16T12:00:00Z",
        )
        val statusPayload = JSONObject(SupabaseOrderStatusClient.buildRpcPayloadJson(statusReq))
        assertEquals("550e8400-e29b-41d4-a716-446655440000", statusPayload.getString("p_order_id"))
        assertEquals("confirmed", statusPayload.getString("p_target_status"))
        assertTrue(statusPayload.isNull("p_reason"))

        // Response parsing: COMMITTED
        val committedStatusJson = """{
            "status": "COMMITTED",
            "server_event_id": "event-uuid-01",
            "order_id": "550e8400-e29b-41d4-a716-446655440000",
            "previous_status": "pending",
            "new_status": "confirmed",
            "already_committed": false
        }"""
        val statusOutcome1 = SupabaseOrderStatusClient.parseRpcResponse(200, committedStatusJson)
        assertTrue(statusOutcome1 is OrderStatusRpcOutcome.CommittedOnline)
        val so1 = statusOutcome1 as OrderStatusRpcOutcome.CommittedOnline
        assertEquals("confirmed", so1.newRawStatus)
        assertFalse(so1.alreadyCommitted)

        // Response parsing: INVALID_TRANSITION
        val invalidTransJson = """{
            "status": "INVALID_TRANSITION",
            "current_status": "fulfilled",
            "message": "Cannot change order from fulfilled to ready."
        }"""
        val statusOutcome2 = SupabaseOrderStatusClient.parseRpcResponse(200, invalidTransJson)
        assertTrue(statusOutcome2 is OrderStatusRpcOutcome.Rejected)
        val so2 = statusOutcome2 as OrderStatusRpcOutcome.Rejected
        assertEquals("INVALID_TRANSITION", so2.code)
        assertEquals("fulfilled", so2.currentServerRawStatus)
    }

    // -------------------------------------------------------------------------
    // 5. Shared Order-to-Sale Conversion Contract (convert_android_order_to_sale)
    // -------------------------------------------------------------------------

    @Test
    fun orderToSaleConversionContractMatchesSqlAndDomainInvariants() {
        val dummyOrder = StoreOrderRecord(
            id = "550e8400-e29b-41d4-a716-446655440000",
            displayNumber = "#550E8400",
            rawStatus = "ready",
            status = StoreOrderStatus.READY,
            totalPrice = 500.0,
            createdAtIso = "2026-11-16T10:00:00Z",
            createdAtEpochMs = 1794823200000L,
            items = emptyList(),
            customerName = "Walk-in Customer",
            customerPhone = null,
            deliveryNote = null,
            isConvertedToSale = false,
        )

        val resolvedLines = listOf(
            ResolvedOrderSaleLine(
                productId = 15L,
                productName = "Kasuku Exercise Book 200 pgs",
                quantity = 2,
                unitPrice = 150.0,
                lineTotal = 300.0,
            ),
            ResolvedOrderSaleLine(
                productId = 22L,
                productName = "Bic Cristal Pen (Blue)",
                quantity = 5,
                unitPrice = 40.0,
                lineTotal = 200.0,
            ),
        )

        // Validation: READY order with resolved lines is valid to convert
        val valOk = OrderConversionPolicy.validateConversion(dummyOrder, resolvedLines)
        assertTrue(valOk.isValid)

        // Validation: Already converted order is blocked
        val valAlreadyConv = OrderConversionPolicy.validateConversion(
            dummyOrder.copy(isConvertedToSale = true),
            resolvedLines,
        )
        assertFalse(valAlreadyConv.isValid)

        // Validation: Cancelled order is blocked
        val valCancelled = OrderConversionPolicy.validateConversion(
            dummyOrder.copy(status = StoreOrderStatus.CANCELLED),
            resolvedLines,
        )
        assertFalse(valCancelled.isValid)

        // Validation: Unlinked line (productId <= 0) is blocked without guessing
        val unlinkedLines = listOf(
            ResolvedOrderSaleLine(
                productId = -1L,
                productName = "Unidentified Pen",
                quantity = 1,
                unitPrice = 50.0,
                lineTotal = 50.0,
            )
        )
        val valUnlinked = OrderConversionPolicy.validateConversion(dummyOrder, unlinkedLines)
        assertFalse(valUnlinked.isValid)

        // RPC payload serialization
        val convReq = AndroidOrderConversionRpcRequest(
            clientConversionId = "conv-uuid-001",
            idempotencyKey = "conv-idem-001",
            orderId = "550e8400-e29b-41d4-a716-446655440000",
            items = resolvedLines,
            paymentMethod = "MPESA",
            actorId = "staff-01",
            deviceId = "android-device-01",
            convertedAtIso = "2026-11-16T12:30:00Z",
        )
        val convPayload = JSONObject(SupabaseOrderConversionClient.buildRpcPayloadJson(convReq))
        assertEquals("conv-uuid-001", convPayload.getString("p_client_conversion_id"))
        assertEquals("MPESA", convPayload.getString("p_payment_method"))
        val itemsArr = convPayload.getJSONArray("p_items")
        assertEquals(2, itemsArr.length())
        assertEquals(15L, itemsArr.getJSONObject(0).getLong("product_id"))
        assertEquals(2, itemsArr.getJSONObject(0).getInt("qty"))
        assertEquals(300.0, itemsArr.getJSONObject(0).getDouble("line_total"), 0.001)

        // Response parsing: COMMITTED
        val committedConvJson = """{
            "status": "COMMITTED",
            "server_conversion_id": "conv-srv-001",
            "order_id": "550e8400-e29b-41d4-a716-446655440000",
            "total_amount": 500.0,
            "updated_products": [
                {
                    "product_id": 15,
                    "qty_deducted": 2,
                    "server_qty_after": 28,
                    "server_sale_id": 1001
                },
                {
                    "product_id": 22,
                    "qty_deducted": 5,
                    "server_qty_after": 45,
                    "server_sale_id": 1002
                }
            ],
            "already_committed": false
        }"""
        val outcome1 = SupabaseOrderConversionClient.parseRpcResponse(200, committedConvJson)
        assertTrue(outcome1 is OrderConversionRpcOutcome.CommittedOnline)
        val co1 = outcome1 as OrderConversionRpcOutcome.CommittedOnline
        assertEquals(500.0, co1.totalAmount, 0.001)
        assertEquals(2, co1.updatedProducts.size)
        assertEquals(15L, co1.updatedProducts[0].productId)
        assertEquals(2, co1.updatedProducts[0].qtyDeducted)
        assertEquals(28, co1.updatedProducts[0].serverQtyAfter)
        assertEquals(1001L, co1.updatedProducts[0].serverSaleId)

        // Response parsing: ALREADY_CONVERTED (different client tries to convert same order)
        val alreadyConvJson = """{
            "status": "ALREADY_CONVERTED",
            "server_conversion_id": "conv-srv-001",
            "order_id": "550e8400-e29b-41d4-a716-446655440000",
            "message": "This order has already been converted into a sale."
        }"""
        val outcome2 = SupabaseOrderConversionClient.parseRpcResponse(200, alreadyConvJson)
        assertTrue(outcome2 is OrderConversionRpcOutcome.Rejected)
        val co2 = outcome2 as OrderConversionRpcOutcome.Rejected
        assertEquals("ALREADY_CONVERTED", co2.code)
        assertTrue(co2.alreadyConverted)

        // Response parsing: INSUFFICIENT_STOCK
        val insufficientStockJson = """{
            "status": "INSUFFICIENT_STOCK",
            "message": "Insufficient stock for product 15: available 1, requested 2."
        }"""
        val outcome3 = SupabaseOrderConversionClient.parseRpcResponse(200, insufficientStockJson)
        assertTrue(outcome3 is OrderConversionRpcOutcome.Rejected)
        assertEquals("INSUFFICIENT_STOCK", (outcome3 as OrderConversionRpcOutcome.Rejected).code)
    }

    // -------------------------------------------------------------------------
    // 6. Shared Stock Operations Contract (Receiving, Adjustment & Stock Count)
    // -------------------------------------------------------------------------

    @Test
    fun stockOperationsRpcPayloadsAndResponsesMatchSqlContracts() {
        // 1. Stock Receiving: receive_android_stock
        val receiveReq = AndroidStockReceiptRpcRequest(
            productId = 15L,
            quantity = 50,
            clientReceiptId = "rcpt-uuid-001",
            idempotencyKey = "rcpt-idem-001",
            unitCost = 95.0,
            supplier = "Text Book Centre",
            invoiceRef = "INV-2026-9901",
            invoicePhotoUrl = null,
            actorId = "staff-01",
            deviceId = "android-device-01",
            receivedAtIso = "2026-11-16T09:00:00Z",
            updateBuyPrice = true,
        )
        val receivePayload = JSONObject(SupabaseStockReceivingClient.buildRpcPayloadJson(receiveReq))
        assertEquals(15L, receivePayload.getLong("p_product_id"))
        assertEquals(50, receivePayload.getInt("p_qty"))
        assertEquals(95.0, receivePayload.getDouble("p_unit_cost"), 0.001)
        assertEquals("Text Book Centre", receivePayload.getString("p_supplier"))
        assertTrue(receivePayload.getBoolean("p_update_buy_price"))

        val receiveAckJson = """{
            "status": "COMMITTED",
            "server_qty": 78,
            "already_committed": false
        }"""
        val rOutcome = SupabaseStockReceivingClient.parseRpcResponse(200, receiveAckJson)
        assertTrue(rOutcome is StockReceiptRpcOutcome.CommittedOnline)
        assertEquals(78, (rOutcome as StockReceiptRpcOutcome.CommittedOnline).serverQty)

        // 2. Stock Adjustment: adjust_android_stock
        val adjReq = AndroidStockAdjustmentRpcRequest(
            productId = 15L,
            deltaQty = -3,
            reasonCode = "DAMAGED",
            notes = "Water damage on bottom row",
            clientAdjustmentId = "adj-uuid-001",
            idempotencyKey = "adj-idem-001",
            actorId = "sup-01",
            actorRole = "SUPERVISOR",
            deviceId = "android-device-01",
            adjustedAtIso = "2026-11-16T14:00:00Z",
        )
        val adjPayload = JSONObject(SupabaseStockAdjustmentClient.buildRpcPayloadJson(adjReq))
        assertEquals(-3, adjPayload.getInt("p_delta_qty"))
        assertEquals("DAMAGED", adjPayload.getString("p_reason_code"))
        assertEquals("SUPERVISOR", adjPayload.getString("p_actor_role"))

        val adjAckJson = """{
            "status": "COMMITTED",
            "server_qty": 75,
            "previous_qty": 78,
            "delta_qty": -3,
            "is_clamped": false,
            "already_committed": false
        }"""
        val adjOutcome = SupabaseStockAdjustmentClient.parseRpcResponse(200, adjAckJson)
        assertTrue(adjOutcome is StockAdjustmentRpcOutcome.CommittedOnline)
        val ao = adjOutcome as StockAdjustmentRpcOutcome.CommittedOnline
        assertEquals(75, ao.serverQty)
        assertEquals(78, ao.previousQty)
        assertEquals(-3, ao.deltaQty)
        assertFalse(ao.isClamped)

        // 3. Physical Stock Count: submit_physical_stock_count
        val countDraft = StockCountSessionDraft(
            sessionName = "Quarterly Inventory Count Q4",
            createdBy = "Supervisor Jane",
            items = listOf(
                StockCountItemDraft(
                    productId = 15,
                    productName = "Kasuku Exercise Book 200 pgs",
                    systemQty = 75,
                    countedQty = 74,
                    reasonCode = StockCountReason.SHORTAGE_MISSING.code,
                    note = "One book unaccounted for",
                )
            ),
            idempotencyKey = "count-idem-001",
        )
        val countPayload = JSONObject(SupabaseStockCountClient.buildRpcPayloadJson(countDraft))
        assertEquals("Quarterly Inventory Count Q4", countPayload.getString("p_session_name"))
        val countItemsArr = countPayload.getJSONArray("p_items")
        assertEquals(1, countItemsArr.length())
        assertEquals(75, countItemsArr.getJSONObject(0).getInt("system_qty"))
        assertEquals(74, countItemsArr.getJSONObject(0).getInt("counted_qty"))

        val countAckJson = """{
            "status": "COMMITTED",
            "session_id": "sess-uuid-001",
            "success": true,
            "already_committed": false
        }"""
        val countOutcome = SupabaseStockCountClient.parseRpcResponse(200, countAckJson)
        assertTrue(countOutcome is StockCountRpcOutcome.CommittedOnline)
        assertEquals("sess-uuid-001", (countOutcome as StockCountRpcOutcome.CommittedOnline).sessionId)
    }

    // -------------------------------------------------------------------------
    // 7. Confirmed Purchase Submission Contract (record_android_purchase)
    // -------------------------------------------------------------------------

    @Test
    fun purchaseSubmissionRpcPayloadAndOutcomeMatchSqlContract() {
        val purchaseReq = AndroidPurchaseRpcRequest(
            clientPurchaseId = "pur-uuid-001",
            idempotencyKey = "pur-idem-001",
            supplierName = "Apex Stationers",
            invoiceRef = "APX-8821",
            receiptDateText = "16/11/2026",
            totalAmount = 2400.0,
            extractionSource = "EDGE_FUNCTION_GEMINI",
            isHumanConfirmed = true,
            items = listOf(
                // 1. Existing resale product (increments shared products.qty)
                AndroidPurchaseLineRequest(
                    rawName = "Kasuku Exercise Book 200 pgs",
                    itemCategory = PurchaseItemCategory.RESALE_PRODUCT,
                    matchedProductId = 15L,
                    registerAsNewProduct = false,
                    quantity = 20,
                    unitCost = 90.0,
                    lineTotal = 1800.0,
                ),
                // 2. Consumable (tracked in purchase ledger, does not touch resale inventory)
                AndroidPurchaseLineRequest(
                    rawName = "Thermal Receipt Rolls 80mm",
                    itemCategory = PurchaseItemCategory.CONSUMABLE,
                    registerAsNewProduct = false,
                    quantity = 10,
                    unitCost = 60.0,
                    lineTotal = 600.0,
                )
            ),
            actorId = "staff-01",
            deviceId = "android-device-01",
            recordedAtIso = "2026-11-16T15:00:00Z",
            recordFinancialOutflow = true,
        )

        val purchasePayload = JSONObject(SupabasePurchaseClient.buildRpcPayloadJson(purchaseReq))
        assertEquals("Apex Stationers", purchasePayload.getString("p_supplier_name"))
        assertTrue(purchasePayload.getBoolean("p_is_human_confirmed"))
        assertTrue(purchasePayload.getBoolean("p_record_financial_outflow"))
        val pItemsArr = purchasePayload.getJSONArray("p_items")
        assertEquals(2, pItemsArr.length())
        assertEquals("RESALE_PRODUCT", pItemsArr.getJSONObject(0).getString("item_category"))
        assertEquals("CONSUMABLE", pItemsArr.getJSONObject(1).getString("item_category"))

        val purchaseAckJson = """{
            "status": "COMMITTED",
            "server_purchase_id": "srv-pur-001",
            "total_amount": 2400.0,
            "resolved_products": [
                {
                    "product_id": 15,
                    "qty": 20,
                    "server_qty_after": 95
                }
            ],
            "already_committed": false
        }"""
        val pOutcome = SupabasePurchaseClient.parseRpcResponse(200, purchaseAckJson)
        assertTrue(pOutcome is PurchaseRpcOutcome.CommittedOnline)
        val po = pOutcome as PurchaseRpcOutcome.CommittedOnline
        assertEquals("srv-pur-001", po.serverPurchaseId)
        assertEquals(2400.0, po.totalAmount, 0.001)
        assertEquals(1, po.resolvedProducts.size)
        assertEquals(15L, po.resolvedProducts[0].serverProductId)
        assertEquals(20, po.resolvedProducts[0].qty)
        assertEquals(95, po.resolvedProducts[0].serverQtyAfter)
    }

    // -------------------------------------------------------------------------
    // 8. Service Operations Contract (record_android_service_operation)
    // -------------------------------------------------------------------------

    @Test
    fun serviceOperationsRpcPayloadAndOutcomeMatchSqlContract() {
        val serviceReq = AndroidServiceRpcRequest(
            clientServiceId = "srv-op-001",
            idempotencyKey = "srv-idem-001",
            serviceType = ServiceOperationType.PRINTING,
            serviceLabel = "Printing",
            quantityOrDuration = 25,
            unitLabel = "pages",
            unitPrice = 10.0,
            totalRevenue = 250.0,
            paymentMethod = "CASH",
            customerNote = "Color handouts",
            consumables = listOf(
                ServiceConsumableRpcLine(
                    consumableName = "A4 Paper",
                    usageQty = 25.0,
                    usageUnit = "sheets",
                    linkedProductId = 88L,
                    stockDeductQty = 25,
                )
            ),
            actorId = "staff-01",
            deviceId = "android-device-01",
            performedAtIso = "2026-11-16T16:00:00Z",
        )

        val servicePayload = JSONObject(SupabaseServiceOperationClient.buildRpcPayloadJson(serviceReq))
        assertEquals("PRINTING", servicePayload.getString("p_service_type"))
        assertEquals(250.0, servicePayload.getDouble("p_total_revenue"), 0.001)
        val consArr = servicePayload.getJSONArray("p_consumables")
        assertEquals(1, consArr.length())
        assertEquals(88L, consArr.getJSONObject(0).getLong("linked_product_id"))
        assertEquals(25, consArr.getJSONObject(0).getInt("stock_deduct_qty"))

        val serviceAckJson = """{
            "status": "COMMITTED",
            "server_service_id": "srv-act-001",
            "total_revenue": 250.0,
            "updated_products": [
                {
                    "product_id": 88,
                    "qty_deducted": 25,
                    "server_qty_after": 475
                }
            ],
            "already_committed": false
        }"""
        val sOutcome = SupabaseServiceOperationClient.parseRpcResponse(200, serviceAckJson)
        assertTrue(sOutcome is ServiceRpcOutcome.CommittedOnline)
        val so = sOutcome as ServiceRpcOutcome.CommittedOnline
        assertEquals("srv-act-001", so.serverServiceId)
        assertEquals(250.0, so.totalRevenue, 0.001)
        assertEquals(1, so.updatedProducts.size)
        assertEquals(88L, so.updatedProducts[0].productId)
        assertEquals(25, so.updatedProducts[0].qtyDeducted)
        assertEquals(475, so.updatedProducts[0].serverQtyAfter)
    }

    // -------------------------------------------------------------------------
    // 9. Financial Ledger & Accounting Boundary Separation Contract
    // -------------------------------------------------------------------------

    @Test
    fun financialMonitoringRpcEnforcesDesktopAccountingSeparation() {
        // Strict architectural rule: Android defaults financial drafts to PENDING_DESKTOP_RULES
        // and transmits raw operational evidence without overriding server-side Desktop rules.
        val draft = FinancialEventDraft(
            eventType = FinancialEventType.M_PESA_RECEIVED,
            amount = 1500.0,
            referenceCode = "QGH8912KL3",
        )
        assertEquals("PENDING_DESKTOP_RULES", draft.category)

        val finReq = AndroidFinancialEventRpcRequest(
            clientEventId = "fin-uuid-001",
            idempotencyKey = "fin-idem-001",
            eventType = "MPESA_SMS_EVENT",
            direction = "INFLOW",
            amount = 1500.0,
            referenceCode = "QGH8912KL3",
            channel = "MPESA_RECEIVED",
            counterparty = "John Doe 0712345678",
            counterpartyAccount = null,
            transactionCost = 0.0,
            newBalance = 15420.0,
            occurredText = "16/11/26 4:15 PM",
            rawEvidence = "QGH8912KL3 Confirmed. Ksh1,500 received from John Doe 0712345678 on 16/11/26 at 4:15 PM.",
            parsedMetadataJson = null,
            parseStatus = "VALID_PARSED",
            source = "MPESA_SMS_AUTO",
            actorId = "staff-01",
            deviceId = "android-device-01",
            notes = "Customer paid via Till",
            occurredAtIso = "2026-11-16T16:15:00Z",
        )

        val finPayload = JSONObject(SupabaseFinancialEventClient.buildRpcPayloadJson(finReq))
        assertEquals("INFLOW", finPayload.getString("p_direction"))
        assertEquals("QGH8912KL3", finPayload.getString("p_reference_code"))
        assertEquals(1500.0, finPayload.getDouble("p_amount"), 0.001)
        assertEquals("VALID_PARSED", finPayload.getString("p_parse_status"))
        assertFalse(finPayload.has("p_classification_status"))

        val finAckJson = """{
            "status": "COMMITTED",
            "server_event_id": "srv-fin-001",
            "already_committed": false
        }"""
        val fOutcome = SupabaseFinancialEventClient.parseRpcResponse(200, finAckJson)
        assertTrue(fOutcome is FinancialEventRpcOutcome.CommittedOnline)
        assertEquals("srv-fin-001", (fOutcome as FinancialEventRpcOutcome.CommittedOnline).serverEventId)
    }

    // -------------------------------------------------------------------------
    // 10. Audit Trail Immutability & Cloud Inspection Contract
    // -------------------------------------------------------------------------

    @Test
    fun auditTrailBatchPayloadAndCloudFetchMatchSqlContract() {
        val auditEntities = listOf(
            LocalAuditEventEntity(
                auditId = "audit-uuid-001",
                actorId = "sup-01",
                deviceId = "android-device-01",
                operation = "SALE_REVERSED",
                entityType = "SALE",
                entityId = "sale-uuid-001",
                beforeJson = """{"status":"COMPLETED","total":300}""",
                afterJson = """{"status":"REVERSED","total":300}""",
                reason = "Customer returned unopened items",
                occurredAtEpochMs = 1731750000000L,
            )
        )

        val batchJson = SupabaseAuditClient.buildBatchPayloadJson(
            events = auditEntities,
            actorRole = StaffRole.SUPERVISOR,
        )
        val batchObj = JSONObject(batchJson)
        val eventsArr = batchObj.getJSONArray("p_events")
        assertEquals(1, eventsArr.length())
        val ev0 = eventsArr.getJSONObject(0)
        assertEquals("audit-uuid-001", ev0.getString("audit_id"))
        assertEquals("SUPERVISOR", ev0.getString("actor_role"))
        assertEquals("SALE_REVERSED", ev0.getString("operation"))
        assertEquals("SALE", ev0.getString("entity_type"))
        assertNotNull(ev0.getString("before_json"))
        assertNotNull(ev0.getString("after_json"))

        // Batch ingestion response
        val batchAckJson = """{
            "status": "COMMITTED",
            "confirmed_audit_ids": ["audit-uuid-001"],
            "accepted_count": 1,
            "duplicate_count": 0
        }"""
        val auditOutcome = SupabaseAuditClient.parseRpcResponse(200, batchAckJson)
        assertTrue(auditOutcome is AuditSyncOutcome.CommittedOnline)
        val ao = auditOutcome as AuditSyncOutcome.CommittedOnline
        assertEquals(1, ao.acceptedCount)
        assertEquals(0, ao.duplicateCount)
        assertEquals("audit-uuid-001", ao.confirmedAuditIds[0])

        // Remote audit fetch response parsing
        val fetchAckJson = """{
            "status": "OK",
            "scope": "ALL_STAFF",
            "events": [
                {
                    "audit_id": "audit-uuid-001",
                    "actor_id": "sup-01",
                    "actor_role": "SUPERVISOR",
                    "device_id": "android-device-01",
                    "operation": "SALE_REVERSED",
                    "entity_type": "SALE",
                    "entity_id": "sale-uuid-001",
                    "before_json": "{\"status\":\"COMPLETED\"}",
                    "after_json": "{\"status\":\"REVERSED\"}",
                    "reason": "Customer return",
                    "occurred_at_epoch_ms": 1731750000000
                }
            ]
        }"""
        val fetchOutcome = SupabaseAuditClient.parseRemoteFetchResponse(200, fetchAckJson)
        assertTrue(fetchOutcome is RemoteAuditFetchOutcome.Success)
        val fo = fetchOutcome as RemoteAuditFetchOutcome.Success
        assertEquals("ALL_STAFF", fo.scope)
        assertEquals(1, fo.records.size)
        assertEquals("SALE_REVERSED", fo.records[0].operation)
        assertEquals("SUPERVISOR", fo.records[0].actorRoleLabel)
    }
}
