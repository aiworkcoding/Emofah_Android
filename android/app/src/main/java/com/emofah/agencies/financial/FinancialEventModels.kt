package com.emofah.agencies.financial

import java.util.UUID

enum class FinancialEventType(val code: String, val label: String) {
    CASH_SALE("CASH_SALE", "Cash Sale Inflow"),
    M_PESA_RECEIVED("M_PESA_RECEIVED", "M-Pesa Payment Inflow"),
    EXPENSE_OUTFLOW("EXPENSE_OUTFLOW", "Operating Expense Outflow"),
    SUPPLIER_PAYMENT("SUPPLIER_PAYMENT", "Supplier Stock Payment"),
    REVERSAL_ADJUSTMENT("REVERSAL_ADJUSTMENT", "Sale Reversal Adjustment"),
    MPESA_SMS_EVENT("MPESA_SMS_EVENT", "M-Pesa Transaction Event"),
}

data class FinancialEventDraft(
    val eventType: FinancialEventType,
    val amount: Double,
    val category: String = "PENDING_DESKTOP_RULES",
    val referenceCode: String? = null,
    val notes: String = "",
    val actorId: String = "Android Operator",
    val deviceId: String = "Android Handheld",
    val idempotencyKey: String = UUID.randomUUID().toString(),
) {
    val isValid: Boolean
        get() = amount.isFinite() && amount > 0.0
}

object FinancialEventValidator {
    fun validate(draft: FinancialEventDraft): Result<Unit> {
        if (!draft.amount.isFinite() || draft.amount <= 0.0) {
            return Result.failure(IllegalArgumentException("Financial amount must be a finite number greater than zero."))
        }
        return Result.success(Unit)
    }
}

data class AndroidFinancialEventRpcRequest(
    val clientEventId: String,
    val idempotencyKey: String,
    val eventType: String,
    val direction: String, // INFLOW, OUTFLOW, TRANSFER
    val amount: Double,
    val currency: String = "KES",
    val referenceCode: String? = null,
    val channel: String? = null,
    val counterparty: String? = null,
    val counterpartyAccount: String? = null,
    val transactionCost: Double? = null,
    val newBalance: Double? = null,
    val occurredText: String? = null,
    val rawEvidence: String? = null,
    val parsedMetadataJson: String? = null,
    val parseStatus: String = "VALID_PARSED",
    val source: String = "ANDROID",
    val actorId: String,
    val deviceId: String,
    val notes: String? = null,
    val occurredAtIso: String,
)

sealed class FinancialEventRpcOutcome {
    data class CommittedOnline(
        val serverEventId: String,
        val alreadyCommitted: Boolean = false,
    ) : FinancialEventRpcOutcome()

    data class Rejected(val code: String, val message: String) : FinancialEventRpcOutcome()
    data class AuthRequired(val message: String) : FinancialEventRpcOutcome()
    data class RpcMissing(val message: String) : FinancialEventRpcOutcome()
    data class NetworkOrTransientError(val message: String) : FinancialEventRpcOutcome()
}

interface FinancialEventGateway {
    suspend fun submitFinancialEvent(request: AndroidFinancialEventRpcRequest): FinancialEventRpcOutcome
}
