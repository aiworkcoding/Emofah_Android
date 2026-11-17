package com.emofah.agencies

import com.emofah.agencies.data.local.dao.LocalSaleWithItems
import com.emofah.agencies.data.local.entities.LocalSaleEntity
import com.emofah.agencies.data.local.entities.LocalSaleItemEntity
import com.emofah.agencies.data.local.entities.PendingFinancialEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import com.emofah.agencies.orders.StoreOrderItem
import com.emofah.agencies.orders.StoreOrderRecord
import com.emofah.agencies.orders.StoreOrderStatus
import com.emofah.agencies.receipts.DigitalReceiptExporter
import com.emofah.agencies.receipts.DigitalReceiptFormatter
import com.emofah.agencies.receipts.ReceiptTransactionKind
import com.emofah.agencies.sales.PaymentMethod
import com.emofah.agencies.sales.PreparedSaleDraft
import com.emofah.agencies.sales.RecordSaleOutcome
import com.emofah.agencies.services.ServiceOperationDraft
import com.emofah.agencies.services.ServiceOperationResult
import com.emofah.agencies.services.ServiceOperationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DigitalReceiptStage71Test {

    @Test
    fun buildFromSale_formatsServerIdOverrideAndReversedWatermark() {
        val sale = LocalSaleEntity(
            clientSaleId = "abc12345-6789-0000-1111-222233334444",
            serverSaleId = 482L,
            idempotencyKey = "idem-1",
            totalAmount = 700.0,
            paymentMethod = "MPESA",
            actorId = "cashier-1",
            deviceId = "dev-1",
            source = "ANDROID",
            saleDateIso = "2026-11-16T08:00:00Z",
            createdAtEpochMs = 1794816000000L,
            syncStatus = SyncState.SERVER_CONFIRMED,
            isReversed = true,
            reversalReason = "Customer returned item",
        )
        val items = listOf(
            LocalSaleItemEntity(
                id = "item-1",
                clientSaleId = sale.clientSaleId,
                productId = 12L,
                productNameSnapshot = "A4 Exercise Book 200pg",
                quantity = 2,
                unitSellPrice = 350.0,
                originalSellPrice = 400.0,
                priceOverrideReason = "Bulk discount",
                lineTotal = 700.0,
            )
        )

        val receipt = DigitalReceiptFormatter.buildFromSale(LocalSaleWithItems(sale, items))
        assertEquals("EMO-S-482", receipt.receiptNumber)
        assertEquals(ReceiptTransactionKind.PRODUCT_SALE, receipt.kind)
        assertEquals("M-Pesa", receipt.paymentMethodLabel)
        assertTrue(receipt.isReversed)
        assertEquals("Reversed", receipt.syncStatusLabel)
        assertEquals("Price adjusted (Bulk discount)", receipt.lines.first().note)

        val text = DigitalReceiptFormatter.formatPlainText(receipt)
        assertTrue(text.contains("REVERSED / VOID"))
        assertTrue(text.contains("Customer returned item"))
        assertTrue(text.contains("EMO-S-482"))
        assertTrue(text.contains("A4 Exercise Book 200pg"))
        assertTrue(text.contains("TOTAL: Ksh 700"))
    }

    @Test
    fun buildFromSaleOutcome_rejectsFailuresAndFormatsOfflineReceipt() {
        val draft = PreparedSaleDraft(
            clientSaleId = "deadbeef-1234-5678-9000-aaaaaaaaaaaa",
            idempotencyKey = "idem-offline",
            productId = 55L,
            productName = "BIC Cristal Pen Blue",
            quantity = 3,
            unitSellPrice = 30.0,
            originalSellPrice = 30.0,
            totalAmount = 90.0,
            paymentMethod = PaymentMethod.CASH,
            priceOverrideReason = null,
            createdAtEpochMs = 1794816000000L,
        )

        val rejected = DigitalReceiptFormatter.buildFromSaleOutcome(
            outcome = RecordSaleOutcome.Rejected("Insufficient stock"),
            draft = draft,
            actorId = "cashier-1",
        )
        assertNull(rejected)

        val offline = DigitalReceiptFormatter.buildFromSaleOutcome(
            outcome = RecordSaleOutcome.SavedOffline(
                clientSaleId = draft.clientSaleId,
                queuedEventId = "sync-1",
                provisionalRemainingQty = 7,
                totalAmount = 90.0,
                paymentMethod = PaymentMethod.CASH,
                reason = "Offline",
            ),
            draft = draft,
            actorId = "cashier-1",
        )
        assertNotNull(offline)
        assertEquals("EMO-S-DEADBEEF", offline!!.receiptNumber)
        assertEquals("Pending Sync", offline.syncStatusLabel)
        assertFalse(offline.isReversed)
        assertEquals("Ksh 90", DigitalReceiptFormatter.formatMoney(offline.totalAmount))
    }

    @Test
    fun buildFromServiceOutcomeAndEntity_extractStructuredDetails() {
        val draft = ServiceOperationDraft(
            clientServiceId = "svc-99887766-1122",
            serviceType = ServiceOperationType.PRINTING,
            quantityOrDuration = 12,
            unitPrice = 10.0,
            paymentMethod = PaymentMethod.MPESA,
            customerNote = "Color certificate copies",
        )
        val receipt = DigitalReceiptFormatter.buildFromServiceOutcome(
            result = ServiceOperationResult.SuccessOnline(
                serverServiceId = "44556677-8899",
                serviceLabel = "Printing",
                totalRevenue = 120.0,
                stockDeductedCount = 12,
                alreadyCommitted = false,
            ),
            draft = draft,
            actorId = "operator-1",
        )
        assertNotNull(receipt)
        assertEquals(ReceiptTransactionKind.SERVICE_OPERATION, receipt!!.kind)
        assertEquals("EMO-SVC-44556677", receipt.receiptNumber)
        assertEquals("12 pages", receipt.lines.first().quantityLabel)
        assertEquals("Color certificate copies", receipt.lines.first().note)

        val entity = PendingFinancialEventEntity(
            eventId = "fin-svc-12345678-abcd",
            source = "ANDROID_SERVICE",
            amount = 150.0,
            currency = "KES",
            transactionReference = null,
            counterparty = "E-Gov Assistance",
            direction = "INFLOW",
            rawEvidence = "E-Gov Assistance: 1 tasks · M-Pesa",
            parsedJson = """{"service_type":"EGOV_ASSISTANCE","quantity_or_duration":1,"unit_label":"tasks","unit_price":150.0,"payment_method":"MPESA","customer_note":"KRA PIN retrieval"}""",
            provisionalCategory = "PENDING_DESKTOP_RULES",
            classificationConfidence = 0f,
            reviewStatus = "PENDING_DESKTOP_RULES",
            syncStatus = SyncState.SERVER_CONFIRMED,
            eventTimestampEpochMs = 1794816000000L,
        )
        val fromEntity = DigitalReceiptFormatter.buildFromServiceEntity(entity)
        assertEquals("EMO-SVC-12345678", fromEntity.receiptNumber)
        assertEquals("M-Pesa", fromEntity.paymentMethodLabel)
        assertEquals("1 tasks", fromEntity.lines.first().quantityLabel)
        assertEquals("KRA PIN retrieval", fromEntity.lines.first().note)
    }

    @Test
    fun buildFromOrderAndSafeFileName_preserveOrderAndSanitizeFileName() {
        val order = StoreOrderRecord(
            id = "a1b2c3d4-0000-0000-0000-111122223333",
            displayNumber = "#A1B2C3",
            rawStatus = "fulfilled",
            status = StoreOrderStatus.FULFILLED,
            totalPrice = 1250.5,
            createdAtIso = "2026-11-16T08:00:00Z",
            createdAtEpochMs = 1794816000000L,
            items = listOf(
                StoreOrderItem(
                    productId = 10L,
                    productName = "Casio Scientific Calculator",
                    quantity = 1,
                    unitPrice = 1250.5,
                    lineTotal = 1250.5,
                )
            ),
            customerName = "Grace W.",
            customerPhone = "0712000111",
            deliveryNote = "Pickup counter",
            isPendingSync = false,
            isConvertedToSale = true,
        )
        val orderReceipt = DigitalReceiptFormatter.buildFromOrder(order, paymentMethodLabel = "M-Pesa")
        assertEquals("EMO-ORD-A1B2C3", orderReceipt.receiptNumber)
        assertEquals(ReceiptTransactionKind.ORDER_CONVERSION, orderReceipt.kind)
        assertEquals("Grace W.", orderReceipt.customerName)
        assertEquals("Ksh 1250.50", DigitalReceiptFormatter.formatMoney(orderReceipt.totalAmount))

        val fileName = DigitalReceiptExporter.buildSafeReceiptFileName("EMO/S: 104#")
        assertEquals("receipt_EMO_S__104.pdf", fileName)
    }
}
