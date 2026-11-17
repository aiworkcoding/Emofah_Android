package com.emofah.agencies

import com.emofah.agencies.financial.MpesaDetectionPolicy
import com.emofah.agencies.financial.MpesaDetectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpesaMessageMonitoringStage52Test {

    @Test
    fun authorizedMpesaSenderAllowsOfficialSendersOnly() {
        assertTrue(MpesaDetectionPolicy.isAuthorizedMpesaSender("MPESA"))
        assertTrue(MpesaDetectionPolicy.isAuthorizedMpesaSender("M-PESA"))
        assertTrue(MpesaDetectionPolicy.isAuthorizedMpesaSender("Safaricom"))
        assertFalse(MpesaDetectionPolicy.isAuthorizedMpesaSender("+254712345678"))
        assertFalse(MpesaDetectionPolicy.isAuthorizedMpesaSender("PROMO-SMS"))
    }

    @Test
    fun detectsValidMpesaReceivedInflowMessage() {
        val result = MpesaDetectionPolicy.evaluateMessage(
            sender = "MPESA",
            body = "QG789XYZ12 Confirmed. You have received Ksh2,450.00 from JOHN KAMAU 0712345678 on 15/11/26 at 10:15 AM. New M-PESA balance is Ksh14,200.00.",
        )

        assertTrue(result is MpesaDetectionResult.DetectedFinancialMessage)
        val detected = result as MpesaDetectionResult.DetectedFinancialMessage
        assertEquals("QG789XYZ12", detected.transactionReference)
        assertEquals(2450.0, detected.amount, 0.001)
        assertEquals("INFLOW", detected.direction)
        assertEquals("JOHN KAMAU 0712345678", detected.counterparty)
    }

    @Test
    fun detectsValidMpesaOutflowPaymentMessage() {
        val result = MpesaDetectionPolicy.evaluateMessage(
            sender = "MPESA",
            body = "QH123ABC99 Confirmed. Ksh850.00 paid to KPLC PREPAID on 15/11/26 at 11:00 AM. New M-PESA balance is Ksh13,350.00.",
        )

        assertTrue(result is MpesaDetectionResult.DetectedFinancialMessage)
        val detected = result as MpesaDetectionResult.DetectedFinancialMessage
        assertEquals("QH123ABC99", detected.transactionReference)
        assertEquals(850.0, detected.amount, 0.001)
        assertEquals("OUTFLOW", detected.direction)
        assertEquals("KPLC PREPAID", detected.counterparty)
    }

    @Test
    fun ignoresPromosOtpAndPersonalChatMessages() {
        val personal = MpesaDetectionPolicy.evaluateMessage(
            sender = "+254712345678",
            body = "QG789XYZ12 Confirmed. You have received Ksh1,000.00 from fake sender",
            allowManualPasteOverride = false,
        )
        assertTrue(personal is MpesaDetectionResult.IgnoredNonFinancial)

        val promo = MpesaDetectionPolicy.evaluateMessage(
            sender = "SAFARICOM",
            body = "Enjoy 50% extra data bundle and Bonga points when you dial *544# today!",
        )
        assertTrue(promo is MpesaDetectionResult.IgnoredNonFinancial)

        val otp = MpesaDetectionPolicy.evaluateMessage(
            sender = "MPESA",
            body = "Your M-PESA OTP verification code is 492011. Do not share your PIN.",
        )
        assertTrue(otp is MpesaDetectionResult.IgnoredNonFinancial)
    }
}
