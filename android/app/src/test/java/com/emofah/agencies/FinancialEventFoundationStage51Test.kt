package com.emofah.agencies

import com.emofah.agencies.financial.FinancialEventDraft
import com.emofah.agencies.financial.FinancialEventType
import com.emofah.agencies.financial.FinancialEventValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinancialEventFoundationStage51Test {

    @Test
    fun testValidFinancialEventDraftPassesValidation() {
        val draft = FinancialEventDraft(
            eventType = FinancialEventType.M_PESA_RECEIVED,
            amount = 1500.0,
            category = "Sales Inflow",
            referenceCode = "QG789XYZ12",
            notes = "Customer payment for stationery"
        )

        val result = FinancialEventValidator.validate(draft)
        assertTrue(result.isSuccess)
        assertEquals(1500.0, draft.amount, 0.001)
        assertEquals(FinancialEventType.M_PESA_RECEIVED, draft.eventType)
    }

    @Test
    fun testInvalidZeroOrNegativeAmountFailsValidation() {
        val draftZero = FinancialEventDraft(
            eventType = FinancialEventType.EXPENSE_OUTFLOW,
            amount = 0.0,
            category = "Rent"
        )
        assertTrue(FinancialEventValidator.validate(draftZero).isFailure)

        val draftNegative = FinancialEventDraft(
            eventType = FinancialEventType.EXPENSE_OUTFLOW,
            amount = -250.0,
            category = "Rent"
        )
        assertTrue(FinancialEventValidator.validate(draftNegative).isFailure)
    }
}
