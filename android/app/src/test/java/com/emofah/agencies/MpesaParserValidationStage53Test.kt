package com.emofah.agencies

import com.emofah.agencies.financial.MpesaMessageParser
import com.emofah.agencies.financial.MpesaParseOutcome
import com.emofah.agencies.financial.MpesaParseStatus
import com.emofah.agencies.financial.MpesaTransactionChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MpesaParserValidationStage53Test {

    @Test
    fun parsesReceivedMoneyWithBalanceAndPhone() {
        val outcome = MpesaMessageParser.parseAndValidate(
            sender = "MPESA",
            body = "QG789XYZ12 Confirmed. You have received Ksh2,450.00 from JOHN KAMAU 0712345678 on 15/11/26 at 10:15 AM. New M-PESA balance is Ksh14,200.00.",
        )

        assertTrue(outcome is MpesaParseOutcome.Parsed)
        val parsed = (outcome as MpesaParseOutcome.Parsed).data
        assertEquals("QG789XYZ12", parsed.transactionCode)
        assertEquals(MpesaTransactionChannel.RECEIVED_MONEY, parsed.channel)
        assertEquals("INFLOW", parsed.direction)
        assertEquals(2450.0, parsed.amount, 0.001)
        assertEquals("0712345678", parsed.counterpartyAccount)
        assertEquals(14200.0, parsed.newBalance ?: 0.0, 0.001)
        assertEquals(MpesaParseStatus.VALID_PARSED, parsed.parseStatus)
    }

    @Test
    fun parsesPaybillWithAccountFeeAndBalance() {
        val outcome = MpesaMessageParser.parseAndValidate(
            sender = "MPESA",
            body = "QH123ABC99 Confirmed. Ksh850.00 sent to KPLC PREPAID for account 1422334455 on 15/11/26 at 11:00 AM. New M-PESA balance is Ksh13,350.00. Transaction cost, Ksh23.00.",
        )

        assertTrue(outcome is MpesaParseOutcome.Parsed)
        val parsed = (outcome as MpesaParseOutcome.Parsed).data
        assertEquals(MpesaTransactionChannel.PAYBILL, parsed.channel)
        assertEquals("OUTFLOW", parsed.direction)
        assertEquals("KPLC PREPAID", parsed.counterparty)
        assertEquals("1422334455", parsed.counterpartyAccount)
        assertEquals(23.0, parsed.transactionCost ?: -1.0, 0.001)
        assertEquals(13350.0, parsed.newBalance ?: -1.0, 0.001)
        assertNotNull(parsed.occurredText)
        assertEquals(MpesaParseStatus.VALID_PARSED, parsed.parseStatus)
    }

    @Test
    fun parsesBuyGoodsTillAndAgentWithdrawal() {
        val tillOutcome = MpesaMessageParser.parseAndValidate(
            sender = "MPESA",
            body = "QI554DEF21 Confirmed. Ksh1,200.00 paid to NAIVAS SUPERMARKET. on 15/11/26 at 1:20 PM. New M-PESA balance is Ksh12,150.00. Transaction cost, Ksh0.00.",
        )
        assertTrue(tillOutcome is MpesaParseOutcome.Parsed)
        val till = (tillOutcome as MpesaParseOutcome.Parsed).data
        assertEquals(MpesaTransactionChannel.BUY_GOODS_TILL, till.channel)
        assertEquals("OUTFLOW", till.direction)
        assertEquals("NAIVAS SUPERMARKET", till.counterparty)

        val withdrawOutcome = MpesaMessageParser.parseAndValidate(
            sender = "MPESA",
            body = "QK229NOP44 Confirmed.on 15/11/26 at 3:10 PM Withdraw Ksh3,000.00 from 123456 - JUMIA AGENT NAIROBI New M-PESA balance is Ksh8,594.00. Transaction cost, Ksh29.00.",
        )
        assertTrue(withdrawOutcome is MpesaParseOutcome.Parsed)
        val withdraw = (withdrawOutcome as MpesaParseOutcome.Parsed).data
        assertEquals(MpesaTransactionChannel.WITHDRAW_AGENT, withdraw.channel)
        assertEquals("TRANSFER", withdraw.direction)
        assertEquals(3000.0, withdraw.amount, 0.001)
        assertEquals(29.0, withdraw.transactionCost ?: -1.0, 0.001)
    }

    @Test
    fun flagsAmbiguousPartialMessageWithoutFabricatingFields() {
        val outcome = MpesaMessageParser.parseAndValidate(
            sender = "MPESA",
            body = "QL998ZZZ11 Confirmed. You have received Ksh600,000.00.",
        )

        assertTrue(outcome is MpesaParseOutcome.Parsed)
        val parsed = (outcome as MpesaParseOutcome.Parsed).data
        assertEquals(MpesaParseStatus.AMBIGUOUS_NEEDS_REVIEW, parsed.parseStatus)
        assertTrue(parsed.validationWarnings.isNotEmpty())
        assertTrue(parsed.confidence <= 0.65f)
    }

    @Test
    fun rejectsMalformedOrZeroAmountMessages() {
        val missingCode = MpesaMessageParser.parseAndValidate(
            sender = "MPESA",
            body = "Confirmed. You have received Ksh500.00 from Someone.",
        )
        assertTrue(missingCode is MpesaParseOutcome.Rejected)

        val zeroAmount = MpesaMessageParser.parseAndValidate(
            sender = "MPESA",
            body = "QM112ABC33 Confirmed. You have received Ksh0.00 from Someone.",
        )
        assertTrue(zeroAmount is MpesaParseOutcome.Rejected)
    }
}
