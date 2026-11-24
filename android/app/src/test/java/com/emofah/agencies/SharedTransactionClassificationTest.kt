package com.emofah.agencies

import com.emofah.agencies.data.local.dao.LocalSaleWithItems
import com.emofah.agencies.data.local.entities.LocalSaleEntity
import com.emofah.agencies.data.local.entities.PendingFinancialEventEntity
import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import com.emofah.agencies.data.sync.SyncEventType
import com.emofah.agencies.data.sync.SyncQueuePolicy
import com.emofah.agencies.data.sync.SyncQueueStatus
import com.emofah.agencies.financial.BusinessBalanceCalculator
import com.emofah.agencies.financial.ClassificationRpcOutcome
import com.emofah.agencies.financial.ClassificationState
import com.emofah.agencies.financial.MpesaInflowAttributionPolicy
import com.emofah.agencies.financial.QueuedClassificationRequest
import com.emofah.agencies.financial.SupabaseTransactionClassificationClient
import com.emofah.agencies.reporting.DailyActivitySummaryCalculator
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedTransactionClassificationTest {

    private fun event(
        eventId: String,
        amount: Double = 1000.0,
        direction: String = "INFLOW",
        source: String = "MPESA_SMS",
        ref: String? = "REF-$eventId",
        classificationStatus: String = "PENDING_CLASSIFICATION",
        excludesFromBalance: Boolean = false,
        syncStatus: String = SyncState.SERVER_CONFIRMED,
        serverEventId: String? = null,
        parsedJson: String? = null,
        timestampMs: Long = 1_763_900_000_000L,
    ): PendingFinancialEventEntity {
        return PendingFinancialEventEntity(
            eventId = eventId,
            source = source,
            amount = amount,
            currency = "KES",
            transactionReference = ref,
            counterparty = "JANE DOE",
            direction = direction,
            rawEvidence = "Confirmed...",
            parsedJson = parsedJson,
            provisionalCategory = classificationStatus,
            classificationConfidence = 1.0f,
            reviewStatus = classificationStatus,
            syncStatus = syncStatus,
            eventTimestampEpochMs = timestampMs,
            classificationStatus = classificationStatus,
            serverEventId = serverEventId,
            excludesFromBalance = excludesFromBalance,
        )
    }

    @Test
    fun businessBalance_excludesPendingAndNonBusiness_andSingleCountsClassifiedTransactions() {
        val pendingSms = event("e1", amount = 2500.0, classificationStatus = "PENDING_CLASSIFICATION")
        val legacyPending = event("e2", amount = 1800.0, classificationStatus = "PENDING_DESKTOP_RULES")
        val nonBusiness = event(
            eventId = "e3",
            amount = 4000.0,
            classificationStatus = "NON_BUSINESS",
            excludesFromBalance = true,
        )
        val productSale = event(
            eventId = "e4",
            amount = 1500.0,
            classificationStatus = "BUSINESS_PRODUCT",
            serverEventId = "srv-4",
            parsedJson = JSONObject().put("transaction_cost", 25.0).toString(),
        )
        val duplicateProductSale = event(
            eventId = "e4-copy",
            amount = 1500.0,
            classificationStatus = "BUSINESS_PRODUCT",
            serverEventId = "srv-4",
            ref = "REF-e4",
        )
        val serviceTx = event(
            eventId = "e5",
            amount = 600.0,
            classificationStatus = "BUSINESS_SERVICE",
            parsedJson = JSONObject().put("classified_net_amount", 520.0).toString(),
        )
        val businessOutflow = event(
            eventId = "e6",
            amount = 300.0,
            direction = "OUTFLOW",
            classificationStatus = "BUSINESS_PRODUCT",
            syncStatus = SyncState.LOCAL_PENDING,
        )

        val report = BusinessBalanceCalculator.compute(
            listOf(
                pendingSms,
                legacyPending,
                nonBusiness,
                productSale,
                duplicateProductSale,
                serviceTx,
                businessOutflow,
            )
        )

        assertEquals(2, report.pendingCount)
        assertEquals(4300.0, report.pendingExcludedAmount, 0.01)
        assertEquals(1, report.nonBusinessCount)
        assertEquals(4000.0, report.nonBusinessExcludedAmount, 0.01)
        assertEquals(2, report.businessProductCount)
        assertEquals(1175.0, report.businessProductNet, 0.01) // (1500 - 25) - 300
        assertEquals(1, report.businessServiceCount)
        assertEquals(520.0, report.businessServiceNet, 0.01)
        assertEquals(1695.0, report.businessBalance, 0.01)
        assertEquals(1, report.localWaitingUploadCount)
    }

    @Test
    fun dailyActivitySummary_populatesSharedBusinessBalanceAndExcludedCounts() {
        val startMs = 1_763_890_000_000L
        val dummySale = LocalSaleWithItems(
            sale = LocalSaleEntity(
                clientSaleId = "sale-1",
                serverSaleId = 10L,
                totalAmount = 1200.0,
                paymentMethod = "MPESA",
                saleDateIso = "2026-11-23T09:00:00Z",
                actorId = "staff",
                deviceId = "dev",
                syncStatus = SyncState.SERVER_CONFIRMED,
                createdAtEpochMs = startMs + 5000L,
            ),
            items = emptyList(),
        )

        val summary = DailyActivitySummaryCalculator.compute(
            startOfTodayEpochMs = startMs,
            salesWithItems = listOf(dummySale),
            financialEvents = listOf(
                event("p1", amount = 900.0, classificationStatus = "PENDING_CLASSIFICATION", timestampMs = startMs + 1000L),
                event("b1", amount = 1200.0, classificationStatus = "BUSINESS_PRODUCT", timestampMs = startMs + 2000L),
                event("n1", amount = 500.0, classificationStatus = "NON_BUSINESS", excludesFromBalance = true, timestampMs = startMs + 3000L),
            ),
            pendingSyncCount = 1,
            conflictCount = 0,
        )

        assertEquals(1200.0, summary.businessBalance, 0.01)
        assertEquals(1, summary.pendingClassificationCount)
        assertEquals(1, summary.nonBusinessExcludedCount)
    }

    @Test
    fun syncQueuePolicy_roundTripsFinancialClassificationRequests() {
        val prodReq = QueuedClassificationRequest(
            clientEventId = "fin-mpesa-abc",
            serverEventId = "uuid-srv-1",
            action = "PRODUCT",
            idempotencyKey = "cls-prod-1",
            productId = 99L,
            quantity = 3,
            existingSaleId = null,
            existingClientSaleId = "client-sale-42",
            unitSellPrice = 450.0,
            paymentMethod = "MPESA",
            note = "Walk-in sale",
            actorId = "cashier_1",
            deviceId = "phone_1",
        )
        val prodJson = SyncQueuePolicy.serializeFinancialClassificationRequest(prodReq)
        val parsedProd = SyncQueuePolicy.parseFinancialClassificationRequest(
            SyncQueueEventEntity(
                eventId = "sync-cls-fin-mpesa-abc",
                eventType = SyncEventType.FINANCIAL_CLASSIFICATION,
                entityId = "fin-mpesa-abc",
                payloadJson = prodJson,
                actorId = "cashier_1",
                deviceId = "phone_1",
                status = SyncQueueStatus.QUEUED,
                createdAtEpochMs = 1L,
            )
        )
        assertNotNull(parsedProd)
        assertEquals("PRODUCT", parsedProd?.action)
        assertEquals(99L, parsedProd?.productId)
        assertEquals(3, parsedProd?.quantity)
        assertEquals("client-sale-42", parsedProd?.existingClientSaleId)
        assertEquals(450.0, parsedProd?.unitSellPrice ?: 0.0, 0.01)

        val svcReq = QueuedClassificationRequest(
            clientEventId = "fin-mpesa-svc",
            serverEventId = "uuid-srv-2",
            action = "SERVICE",
            idempotencyKey = "cls-svc-1",
            quantity = 5,
            serviceType = "PRINTING",
            serviceLabel = "Printing",
            unitLabel = "pages",
            unitPrice = 20.0,
            discountAmount = 10.0,
            note = "Color print",
            actorId = "cashier_1",
            deviceId = "phone_1",
        )
        val svcJson = SyncQueuePolicy.serializeFinancialClassificationRequest(svcReq)
        val parsedSvc = SyncQueuePolicy.parseFinancialClassificationRequest(
            SyncQueueEventEntity(
                eventId = "sync-cls-fin-mpesa-svc",
                eventType = SyncEventType.FINANCIAL_CLASSIFICATION,
                entityId = "fin-mpesa-svc",
                payloadJson = svcJson,
                actorId = "cashier_1",
                deviceId = "phone_1",
                status = SyncQueueStatus.QUEUED,
                createdAtEpochMs = 1L,
            )
        )
        assertNotNull(parsedSvc)
        assertEquals("SERVICE", parsedSvc?.action)
        assertEquals("PRINTING", parsedSvc?.serviceType)
        assertEquals(10.0, parsedSvc?.discountAmount ?: 0.0, 0.01)
    }

    @Test
    fun parseClassificationResponse_handlesConcurrentWinAndRejectionsSafely() {
        val concurrentWinBody = JSONObject().apply {
            put("status", "ALREADY_CLASSIFIED")
            put("message", "Already classified on Desktop as Product Sale.")
            put(
                "event",
                JSONObject().apply {
                    put("id", "uuid-1")
                    put("client_event_id", "fin-mpesa-1")
                    put("direction", "INFLOW")
                    put("amount", 1500.0)
                    put("classification_status", "BUSINESS_PRODUCT")
                    put("linked_sale_id", 88L)
                    put("classified_device", "desktop")
                }
            )
        }.toString()

        val winOutcome = SupabaseTransactionClassificationClient.parseClassificationResponse(200, concurrentWinBody)
        assertTrue(winOutcome is ClassificationRpcOutcome.AlreadyClassified)
        val win = winOutcome as ClassificationRpcOutcome.AlreadyClassified
        assertEquals(ClassificationState.BUSINESS_PRODUCT, win.updatedEvent?.classificationStatus)
        assertEquals(88L, win.updatedEvent?.linkedSaleId)

        val saleLinkedBody = JSONObject().apply {
            put("status", "SALE_ALREADY_LINKED")
            put("message", "Sale #88 is already linked to another transaction.")
        }.toString()
        val rejected = SupabaseTransactionClassificationClient.parseClassificationResponse(200, saleLinkedBody)
        assertTrue(rejected is ClassificationRpcOutcome.Rejected)
        assertEquals("SALE_ALREADY_LINKED", (rejected as ClassificationRpcOutcome.Rejected).code)

        val missingRpc = SupabaseTransactionClassificationClient.parseClassificationResponse(
            404,
            """{"code":"PGRST202","message":"Could not find function"}""",
            "classify_financial_transaction_as_product",
        )
        assertTrue(missingRpc is ClassificationRpcOutcome.RpcMissing)
    }

    @Test
    fun pendingSharedTransactions_includesInflowOutflowAndManual_whileLegacyInflowPolicyRemainsStrict() {
        val outflow = event("out1", direction = "OUTFLOW", source = "MPESA_SMS")
        val manual = event("man1", direction = "OUTFLOW", source = "MANUAL")
        val classified = event("cls1", direction = "INFLOW", source = "MPESA_SMS", classificationStatus = "BUSINESS_PRODUCT")

        val sharedPending = MpesaInflowAttributionPolicy.findPendingSharedTransactions(
            listOf(outflow, manual, classified)
        )
        assertEquals(2, sharedPending.size)
        assertFalse(MpesaInflowAttributionPolicy.isMpesaInflowEvent(outflow))
        assertFalse(MpesaInflowAttributionPolicy.isMpesaInflowEvent(manual))
        assertNull(
            MpesaInflowAttributionPolicy.findLatestPendingSharedTransaction(listOf(classified))
        )
    }
}
