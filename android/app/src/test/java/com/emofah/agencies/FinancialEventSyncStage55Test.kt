package com.emofah.agencies

import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.sync.SyncEventType
import com.emofah.agencies.data.sync.SyncQueuePolicy
import com.emofah.agencies.financial.AndroidFinancialEventRpcRequest
import com.emofah.agencies.financial.FinancialEventRpcOutcome
import com.emofah.agencies.financial.SupabaseFinancialEventClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinancialEventSyncStage55Test {

    @Test
    fun buildsFinancialRpcPayloadWithoutFinalAccountingClassification() {
        val req = AndroidFinancialEventRpcRequest(
            clientEventId = "fin-mpesa-qg789xyz12",
            idempotencyKey = "idem-mpesa-QG789XYZ12",
            eventType = "MPESA_SMS_EVENT",
            direction = "INFLOW",
            amount = 2450.0,
            currency = "KES",
            referenceCode = "QG789XYZ12",
            channel = "RECEIVED_MONEY",
            counterparty = "JOHN KAMAU",
            counterpartyAccount = "0712345678",
            transactionCost = 0.0,
            newBalance = 14200.0,
            occurredText = "15/11/26 at 10:15 AM",
            rawEvidence = "QG789XYZ12 Confirmed...",
            parsedMetadataJson = """{"classification_authority":"DESKTOP_MANAGED"}""",
            parseStatus = "VALID_PARSED",
            source = "MPESA_SMS",
            actorId = "cashier-1",
            deviceId = "dev-1",
            notes = "Received Money",
            occurredAtIso = "2026-11-15T10:15:00Z",
        )

        val json = JSONObject(SupabaseFinancialEventClient.buildRpcPayloadJson(req))
        assertEquals("fin-mpesa-qg789xyz12", json.getString("p_client_event_id"))
        assertEquals("INFLOW", json.getString("p_direction"))
        assertEquals("QG789XYZ12", json.getString("p_reference_code"))
        assertEquals(2450.0, json.getDouble("p_amount"), 0.001)
        assertFalse(json.has("p_category"))
    }

    @Test
    fun parsesQueuedFinancialEventPayloadFromSyncQueue() {
        val payload = JSONObject().apply {
            put("client_event_id", "fin-mpesa-qh123abc99")
            put("idempotency_key", "idem-mpesa-QH123ABC99")
            put("event_type", "MPESA_SMS_EVENT")
            put("direction", "OUTFLOW")
            put("amount", 850.0)
            put("currency", "KES")
            put("reference_code", "QH123ABC99")
            put("channel", "PAYBILL")
            put("counterparty", "KPLC PREPAID")
            put("counterparty_account", "1422334455")
            put("transaction_cost", 23.0)
            put("new_balance", 13350.0)
            put("parse_status", "VALID_PARSED")
            put("source", "MPESA_SMS")
            put("occurred_at", "2026-11-15T11:00:00Z")
        }.toString()

        val evt = SyncQueueEventEntity(
            eventId = "sync-fin-mpesa-qh123abc99",
            eventType = SyncEventType.FINANCIAL_EVENT,
            entityId = "fin-mpesa-qh123abc99",
            payloadJson = payload,
            actorId = "cashier-1",
            deviceId = "dev-1",
            createdAtEpochMs = 1000L,
        )

        val parsed = SyncQueuePolicy.parseFinancialEventRpcRequest(evt)
        assertNotNull(parsed)
        assertEquals("OUTFLOW", parsed!!.direction)
        assertEquals("QH123ABC99", parsed.referenceCode)
        assertEquals(850.0, parsed.amount, 0.001)
        assertEquals(23.0, parsed.transactionCost ?: 0.0, 0.001)
    }

    @Test
    fun handlesCommittedDuplicateAndRpcMissingResponses() {
        val committed = SupabaseFinancialEventClient.parseRpcResponse(
            200,
            """{"status":"COMMITTED","server_event_id":"uuid-1","already_committed":false}""",
        )
        assertTrue(committed is FinancialEventRpcOutcome.CommittedOnline)
        assertFalse((committed as FinancialEventRpcOutcome.CommittedOnline).alreadyCommitted)

        val duplicate = SupabaseFinancialEventClient.parseRpcResponse(
            200,
            """{"status":"DUPLICATE_IGNORED","server_event_id":"uuid-1","already_committed":true}""",
        )
        assertTrue(duplicate is FinancialEventRpcOutcome.CommittedOnline)
        assertTrue((duplicate as FinancialEventRpcOutcome.CommittedOnline).alreadyCommitted)

        val missing = SupabaseFinancialEventClient.parseRpcResponse(
            404,
            """{"code":"PGRST202","message":"Could not find the function public.record_android_financial_event"}""",
        )
        assertTrue(missing is FinancialEventRpcOutcome.RpcMissing)
    }
}
