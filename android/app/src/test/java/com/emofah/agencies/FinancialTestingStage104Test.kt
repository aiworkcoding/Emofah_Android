package com.emofah.agencies

import com.emofah.agencies.data.local.entities.PendingFinancialEventEntity
import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import com.emofah.agencies.data.sync.ConflictPolicy
import com.emofah.agencies.data.sync.SyncEventType
import com.emofah.agencies.data.sync.SyncQueuePolicy
import com.emofah.agencies.financial.AndroidFinancialEventRpcRequest
import com.emofah.agencies.financial.FinancialEventDraft
import com.emofah.agencies.financial.FinancialEventRpcOutcome
import com.emofah.agencies.financial.FinancialEventType
import com.emofah.agencies.financial.FinancialEventValidator
import com.emofah.agencies.financial.MpesaDetectionPolicy
import com.emofah.agencies.financial.MpesaDetectionResult
import com.emofah.agencies.financial.MpesaMessageParser
import com.emofah.agencies.financial.MpesaParseOutcome
import com.emofah.agencies.financial.MpesaParseStatus
import com.emofah.agencies.financial.MpesaTransactionChannel
import com.emofah.agencies.financial.SupabaseFinancialEventClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 10.4 — Financial monitoring testing end-to-end regression suite:
 * Verifies IEEE 754 NaN/Infinity amount validation, official M-Pesa sender & TX code gating,
 * multi-channel Safaricom SMS parsing, Desktop-managed accounting separation (PENDING_DESKTOP_RULES),
 * offline queue sanitization, RPC outcome/idempotency handling, and financial immutability.
 */
class FinancialTestingStage104Test {

    @Test
    fun financialDraftAndValidatorRejectZeroNegativeAndNonFiniteAmounts() {
        val valid = FinancialEventDraft(
            eventType = FinancialEventType.EXPENSE_OUTFLOW,
            amount = 450.0,
            referenceCode = "RCPT-001",
            notes = "Packaging bags",
        )
        assertEquals("PENDING_DESKTOP_RULES", valid.category)
        assertTrue(valid.isValid)
        assertTrue(FinancialEventValidator.validate(valid).isSuccess)

        val invalidAmounts = listOf(
            0.0,
            -120.0,
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
        )
        for (bad in invalidAmounts) {
            val draft = valid.copy(amount = bad)
            assertFalse("Expected isValid=false for $bad", draft.isValid)
            assertTrue("Expected validator failure for $bad", FinancialEventValidator.validate(draft).isFailure)
        }
    }

    @Test
    fun detectionPolicyBlocksUnauthorizedSendersPromosOtpsAndEnglishWordFalseCodes() {
        assertTrue(MpesaDetectionPolicy.isAuthorizedMpesaSender(" mpEsa "))
        assertTrue(MpesaDetectionPolicy.isAuthorizedMpesaSender("MPESA_KE"))
        assertTrue(MpesaDetectionPolicy.isAuthorizedMpesaSender("M PESA"))
        assertFalse(MpesaDetectionPolicy.isAuthorizedMpesaSender(""))
        assertFalse(MpesaDetectionPolicy.isAuthorizedMpesaSender("+254712345678"))
        assertFalse(MpesaDetectionPolicy.isAuthorizedMpesaSender("SAFARICOM-PROMO"))

        // 10-letter uppercase English words ("STATIONERY", "OPERATIONS") must not pass as M-Pesa TX codes
        val fakeWordOnly = MpesaDetectionPolicy.evaluateMessage(
            sender = "MPESA",
            body = "Confirmed. Ksh500.00 paid to STATIONERY OPERATIONS on 16/11/26 at 9:00 AM.",
        )
        assertTrue(fakeWordOnly is MpesaDetectionResult.IgnoredNonFinancial)

        // Personal SMS without manual paste override must be ignored
        val personal = MpesaDetectionPolicy.evaluateMessage(
            sender = "0712345678",
            body = "QG789XYZ12 Confirmed. You have received Ksh500.00 from JOHN DOE.",
            allowManualPasteOverride = false,
        )
        assertTrue(personal is MpesaDetectionResult.IgnoredNonFinancial)

        // Manual paste override allows explicit operator paste of valid M-Pesa text
        val pasted = MpesaDetectionPolicy.evaluateMessage(
            sender = "Pasted SMS",
            body = "QG789XYZ12 Confirmed. You have received Ksh500.00 from JOHN DOE on 16/11/26 at 9:00 AM.",
            allowManualPasteOverride = true,
        )
        assertTrue(pasted is MpesaDetectionResult.DetectedFinancialMessage)
        assertEquals("QG789XYZ12", (pasted as MpesaDetectionResult.DetectedFinancialMessage).transactionReference)

        // OTP & Promo excluded
        val otp = MpesaDetectionPolicy.evaluateMessage(
            sender = "MPESA",
            body = "Your M-PESA verification code is 883920. Do not share your PIN.",
        )
        assertTrue(otp is MpesaDetectionResult.IgnoredNonFinancial)
    }

    @Test
    fun parserCoversAllMajorMpesaChannelsAndEdgeFormatsCleanly() {
        // 1. Paybill using "paid to ... for account" phrasing
        val paybillPaid = MpesaMessageParser.parseAndValidate(
            sender = "MPESA",
            body = "RJ12ABC345 Confirmed. Ksh1,450.00 paid to NAIROBI WATER for account 99887766 on 16/11/26 at 8:30 AM. New M-PESA balance is Ksh18,550.00. Transaction cost, Ksh25.00.",
        )
        assertTrue(paybillPaid is MpesaParseOutcome.Parsed)
        val paybillData = (paybillPaid as MpesaParseOutcome.Parsed).data
        assertEquals(MpesaTransactionChannel.PAYBILL, paybillData.channel)
        assertEquals("OUTFLOW", paybillData.direction)
        assertEquals("NAIROBI WATER", paybillData.counterparty)
        assertEquals("99887766", paybillData.counterpartyAccount)
        assertEquals(25.0, paybillData.transactionCost ?: -1.0, 0.001)
        assertEquals(18550.0, paybillData.newBalance ?: -1.0, 0.001)
        assertEquals(MpesaParseStatus.VALID_PARSED, paybillData.parseStatus)

        // 2. Send Money
        val sendMoney = MpesaMessageParser.parseAndValidate(
            sender = "MPESA",
            body = "QM771SND10 Confirmed. Ksh500.00 sent to MARY AKINYI 0722000111 on 16/11/26 at 2:10 PM. New M-PESA balance is Ksh11,627.00. Transaction cost, Ksh7.00.",
        )
        assertTrue(sendMoney is MpesaParseOutcome.Parsed)
        val sendData = (sendMoney as MpesaParseOutcome.Parsed).data
        assertEquals(MpesaTransactionChannel.SEND_MONEY, sendData.channel)
        assertEquals("OUTFLOW", sendData.direction)
        assertEquals("0722000111", sendData.counterpartyAccount)
        assertEquals(MpesaParseStatus.VALID_PARSED, sendData.parseStatus)

        // 3. Cash Deposit at Agent
        val deposit = MpesaMessageParser.parseAndValidate(
            sender = "MPESA",
            body = "QP445DEP88 Confirmed. On 16/11/26 at 9:30 AM Give Ksh5,000.00 cash to 123456 - JUMIA AGENT NAIROBI New M-PESA balance is Ksh16,577.00.",
        )
        assertTrue(deposit is MpesaParseOutcome.Parsed)
        val depositData = (deposit as MpesaParseOutcome.Parsed).data
        assertEquals(MpesaTransactionChannel.DEPOSIT, depositData.channel)
        assertEquals("INFLOW", depositData.direction)
        assertEquals("123456 - JUMIA AGENT NAIROBI", depositData.counterparty)
        assertEquals(MpesaParseStatus.VALID_PARSED, depositData.parseStatus)

        // 4. Data Bundle / Airtime purchase parses cleanly without false missing-counterparty warning
        val bundle = MpesaMessageParser.parseAndValidate(
            sender = "MPESA",
            body = "QN112BND55 Confirmed. You bought Ksh100.00 of data bundle on 16/11/26 at 5:00 PM. New M-PESA balance is Ksh11,577.00.",
        )
        assertTrue(bundle is MpesaParseOutcome.Parsed)
        val bundleData = (bundle as MpesaParseOutcome.Parsed).data
        assertEquals(MpesaTransactionChannel.AIRTIME_BUNDLE, bundleData.channel)
        assertEquals("OUTFLOW", bundleData.direction)
        assertEquals("Safaricom Airtime", bundleData.counterparty)
        assertEquals(MpesaParseStatus.VALID_PARSED, bundleData.parseStatus)

        // 5. Fuliza Repayment
        val fuliza = MpesaMessageParser.parseAndValidate(
            sender = "MPESA",
            body = "QR882FLZ19 Confirmed. Ksh320.00 from your M-PESA has been used to pay your Fuliza M-PESA on 16/11/26 at 5:15 PM. New M-PESA balance is Ksh0.00.",
        )
        assertTrue(fuliza is MpesaParseOutcome.Parsed)
        val fulizaData = (fuliza as MpesaParseOutcome.Parsed).data
        assertEquals(MpesaTransactionChannel.FULIZA_REPAYMENT, fulizaData.channel)
        assertEquals("OUTFLOW", fulizaData.direction)
        assertEquals("Safaricom Fuliza", fulizaData.counterparty)
        assertEquals(0.0, fulizaData.newBalance ?: -1.0, 0.001)
        assertEquals(MpesaParseStatus.VALID_PARSED, fulizaData.parseStatus)

        // 6. Reversal extracts reversal TX code (not original TX code) as primary reference
        val reversal = MpesaMessageParser.parseAndValidate(
            sender = "MPESA",
            body = "RL89REV123 Confirmed. Reversal of transaction QG789XYZ12 has been completed. Ksh1,500.00 has been credited to your M-PESA account on 16/11/26 at 6:00 PM. New M-PESA balance is Ksh15,700.00.",
        )
        assertTrue(reversal is MpesaParseOutcome.Parsed)
        val reversalData = (reversal as MpesaParseOutcome.Parsed).data
        assertEquals("RL89REV123", reversalData.transactionCode)
        assertEquals(MpesaTransactionChannel.REVERSAL, reversalData.channel)
        assertEquals("INFLOW", reversalData.direction)
    }

    @Test
    fun parserRejectsEnglishOnlyPseudoCodesUnconfirmedTextsAndOverflowAmounts() {
        val noDigitCode = MpesaMessageParser.parseAndValidate(
            sender = "MPESA",
            body = "Confirmed. You have received Ksh1,000.00 from MANAGEMENT on 16/11/26 at 9:00 AM.",
        )
        assertTrue(noDigitCode is MpesaParseOutcome.Rejected)

        val unconfirmed = MpesaMessageParser.parseAndValidate(
            sender = "MPESA",
            body = "QG789XYZ12 Failed. You do not have sufficient funds to send Ksh500.00.",
        )
        assertTrue(unconfirmed is MpesaParseOutcome.Rejected)

        val overflowAmount = MpesaMessageParser.parseAndValidate(
            sender = "MPESA",
            body = "QG789XYZ12 Confirmed. You have received Ksh${"9".repeat(400)}.00 from JOHN DOE.",
        )
        assertTrue(overflowAmount is MpesaParseOutcome.Rejected)
    }

    @Test
    fun offlineFinancialQueueSanitizesNonFiniteNumbersAndPreservesDesktopAccountingBoundary() {
        val validQueueEvent = SyncQueueEventEntity(
            eventId = "sync-fin-1",
            eventType = SyncEventType.FINANCIAL_EVENT,
            entityId = "fin-mpesa-rj12abc345",
            payloadJson = JSONObject().apply {
                put("client_event_id", "fin-mpesa-rj12abc345")
                put("idempotency_key", "idem-mpesa-RJ12ABC345")
                put("event_type", "MPESA_SMS_EVENT")
                put("direction", "TRANSFER")
                put("amount", 3000.0)
                put("currency", "KES")
                put("reference_code", "RJ12ABC345")
                put("channel", "WITHDRAW_AGENT")
                put("transaction_cost", 29.0)
                put("new_balance", 8594.0)
                put("parsed_metadata_json", """{"classification_authority":"DESKTOP_MANAGED"}""")
                put("occurred_at", "2026-11-16T15:10:00Z")
            }.toString(),
            actorId = "cashier-1",
            deviceId = "android-1",
            createdAtEpochMs = 1_000L,
        )

        val parsed = SyncQueuePolicy.parseFinancialEventRpcRequest(validQueueEvent)
        assertNotNull(parsed)
        assertEquals("TRANSFER", parsed!!.direction)
        assertEquals(3000.0, parsed.amount, 0.001)
        assertEquals(29.0, parsed.transactionCost ?: -1.0, 0.001)

        // Build RPC payload and verify Android does NOT attach final accounting category
        val rpcJson = JSONObject(SupabaseFinancialEventClient.buildRpcPayloadJson(parsed))
        assertFalse(rpcJson.has("p_category"))
        assertFalse(rpcJson.has("p_classification_status"))
        assertEquals("DESKTOP_MANAGED", rpcJson.getJSONObject("p_parsed_metadata").getString("classification_authority"))

        // Quarantine Infinity / NaN / invalid direction / negative fee edge cases
        val infiniteAmountEvent = validQueueEvent.copy(
            payloadJson = """{"client_event_id":"fin-bad","idempotency_key":"k1","direction":"INFLOW","amount":"Infinity","occurred_at":"2026-11-16T15:10:00Z"}""",
        )
        assertNull(SyncQueuePolicy.parseFinancialEventRpcRequest(infiniteAmountEvent))

        val nanAmountEvent = validQueueEvent.copy(
            payloadJson = """{"client_event_id":"fin-bad","idempotency_key":"k1","direction":"INFLOW","amount":"NaN","occurred_at":"2026-11-16T15:10:00Z"}""",
        )
        assertNull(SyncQueuePolicy.parseFinancialEventRpcRequest(nanAmountEvent))

        val badDirectionEvent = validQueueEvent.copy(
            payloadJson = """{"client_event_id":"fin-bad","idempotency_key":"k1","direction":"ADJUSTMENT","amount":500.0,"occurred_at":"2026-11-16T15:10:00Z"}""",
        )
        assertNull(SyncQueuePolicy.parseFinancialEventRpcRequest(badDirectionEvent))

        val negativeFeeEvent = validQueueEvent.copy(
            payloadJson = JSONObject(validQueueEvent.payloadJson).apply {
                put("transaction_cost", -5.0)
                put("new_balance", "Infinity")
            }.toString(),
        )
        val sanitized = SyncQueuePolicy.parseFinancialEventRpcRequest(negativeFeeEvent)
        assertNotNull(sanitized)
        assertNull(sanitized!!.transactionCost)
        assertNull(sanitized.newBalance)
    }

    @Test
    fun rpcOutcomesAndConflictPolicyPreserveIdempotencyAndFinancialImmutability() {
        val req = AndroidFinancialEventRpcRequest(
            clientEventId = "fin-man-1",
            idempotencyKey = "idem-man-1",
            eventType = FinancialEventType.EXPENSE_OUTFLOW.code,
            direction = "OUTFLOW",
            amount = 750.0,
            referenceCode = "EXP-104",
            actorId = "cashier-1",
            deviceId = "android-1",
            occurredAtIso = "2026-11-16T12:00:00Z",
        )
        assertEquals("KES", req.currency)

        val dup = SupabaseFinancialEventClient.parseRpcResponse(
            200,
            """{"status":"DUPLICATE_IGNORED","server_event_id":"srv-uuid-9","already_committed":true}""",
        )
        assertTrue(dup is FinancialEventRpcOutcome.CommittedOnline)
        assertTrue((dup as FinancialEventRpcOutcome.CommittedOnline).alreadyCommitted)

        val rejected = SupabaseFinancialEventClient.parseRpcResponse(
            200,
            """{"status":"INVALID_INPUT","message":"Financial amount must be greater than zero."}""",
        )
        assertTrue(rejected is FinancialEventRpcOutcome.Rejected)

        val authErr = SupabaseFinancialEventClient.parseRpcResponse(
            401,
            """{"message":"JWT expired"}""",
        )
        assertTrue(authErr is FinancialEventRpcOutcome.AuthRequired)

        val serverRetry = SupabaseFinancialEventClient.parseRpcResponse(
            503,
            """{"message":"Database busy"}""",
        )
        assertTrue(serverRetry is FinancialEventRpcOutcome.NetworkOrTransientError)

        val existingConfirmed = PendingFinancialEventEntity(
            eventId = "fin-mpesa-rj12abc345",
            source = "MPESA_SMS",
            amount = 1450.0,
            transactionReference = "RJ12ABC345",
            counterparty = "NAIROBI WATER",
            direction = "OUTFLOW",
            rawEvidence = "RJ12ABC345 Confirmed...",
            parsedJson = "{}",
            provisionalCategory = "PENDING_DESKTOP_RULES",
            reviewStatus = "PENDING_DESKTOP_RULES",
            syncStatus = SyncState.SERVER_CONFIRMED,
            eventTimestampEpochMs = 1_000L,
        )
        val mutatedIncoming = existingConfirmed.copy(eventId = "fin-mpesa-dup", amount = 1999.0)
        val decision = ConflictPolicy.protectFinancialRecord(existingConfirmed, mutatedIncoming)
        assertFalse(decision.shouldOverwriteExisting)
        assertTrue(decision.shouldFlagReview)
    }
}
