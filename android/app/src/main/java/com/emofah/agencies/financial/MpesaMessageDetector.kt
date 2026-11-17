package com.emofah.agencies.financial

sealed class MpesaDetectionResult {
    data class DetectedFinancialMessage(
        val sender: String,
        val transactionReference: String,
        val amount: Double,
        val direction: String, // INFLOW or OUTFLOW
        val counterparty: String?,
        val confidence: Float,
        val rawBody: String,
    ) : MpesaDetectionResult()

    data class IgnoredNonFinancial(val reason: String) : MpesaDetectionResult()
}

/**
 * Stage 5.2 — Pure M-Pesa sender & financial message detection policy.
 * Filters strictly for M-Pesa financial alerts while discarding personal SMS, OTPs, and promos.
 */
object MpesaDetectionPolicy {
    private val ALLOWED_MPESA_SENDERS = setOf(
        "MPESA",
        "M-PESA",
        "MPESA_KE",
        "SAFARICOM",
        "M PESA",
    )

    private val NON_FINANCIAL_MARKERS = listOf(
        "otp",
        "one time password",
        "verification code",
        "pin has been",
        "bonga points",
        "data bundle",
        "free minutes",
        "skiza",
        "fuliza limit",
        "enjoy 50%",
        "dial *",
        "unlock your",
        "do not share your pin",
    )

    private val TX_CODE_REGEX = Regex("""\b([A-Z](?=[A-Z0-9]*\d)[A-Z0-9]{9})\b""")
    private val AMOUNT_REGEX = Regex("""(?:Ksh|KES)\.?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)
    private val INFLOW_VERBS = listOf("you have received", "received ksh", "deposited", "reversed")
    private val OUTFLOW_VERBS = listOf("paid to", "sent to", "you bought", "withdraw", "transferred to", "paybill")

    fun isAuthorizedMpesaSender(sender: String): Boolean {
        val normalized = sender.trim().uppercase()
        if (normalized.isEmpty()) return false
        return normalized in ALLOWED_MPESA_SENDERS
    }

    fun evaluateMessage(
        sender: String,
        body: String,
        allowManualPasteOverride: Boolean = false,
    ): MpesaDetectionResult {
        val cleanSender = sender.trim()
        val cleanBody = body.trim()

        if (cleanBody.isBlank()) {
            return MpesaDetectionResult.IgnoredNonFinancial("Empty message body.")
        }

        if (!allowManualPasteOverride && !isAuthorizedMpesaSender(cleanSender)) {
            return MpesaDetectionResult.IgnoredNonFinancial("Sender '$cleanSender' is not an authorized M-Pesa sender ID.")
        }

        val lowerBody = cleanBody.lowercase()
        if (NON_FINANCIAL_MARKERS.any { lowerBody.contains(it) } && !lowerBody.contains("confirmed.")) {
            return MpesaDetectionResult.IgnoredNonFinancial("Promotional, OTP, or service alert excluded from financial ledger.")
        }

        val hasConfirmedMarker = lowerBody.contains("confirmed")
        val txMatch = TX_CODE_REGEX.find(cleanBody.uppercase())
        val amountMatch = AMOUNT_REGEX.find(cleanBody)

        if (!hasConfirmedMarker || txMatch == null || amountMatch == null) {
            return MpesaDetectionResult.IgnoredNonFinancial(
                "Message lacks required M-Pesa confirmation marker, reference code, or Ksh amount."
            )
        }

        val amount = amountMatch.groupValues[1].replace(",", "").toDoubleOrNull()
        if (amount == null || !amount.isFinite() || amount <= 0.0) {
            return MpesaDetectionResult.IgnoredNonFinancial("Parsed amount was invalid or zero.")
        }

        val isInflow = INFLOW_VERBS.any { lowerBody.contains(it) }
        val isOutflow = OUTFLOW_VERBS.any { lowerBody.contains(it) }

        if (!isInflow && !isOutflow) {
            return MpesaDetectionResult.IgnoredNonFinancial("Unrecognized M-Pesa movement direction.")
        }

        val direction = if (isInflow && !isOutflow) "INFLOW" else if (isOutflow && !isInflow) "OUTFLOW" else {
            if (lowerBody.indexOf("received") in 0..80) "INFLOW" else "OUTFLOW"
        }

        val counterparty = extractProvisionalCounterparty(cleanBody, direction)
        return MpesaDetectionResult.DetectedFinancialMessage(
            sender = cleanSender.ifBlank { "MPESA" },
            transactionReference = txMatch.groupValues[1],
            amount = amount,
            direction = direction,
            counterparty = counterparty,
            confidence = if (isAuthorizedMpesaSender(cleanSender)) 0.92f else 0.80f,
            rawBody = cleanBody,
        )
    }

    private fun extractProvisionalCounterparty(body: String, direction: String): String? {
        val pattern = if (direction == "INFLOW") {
            Regex("""from\s+([A-Za-z0-9\s.&'-]{3,48}?)(?:\s+on|\s+\d{1,2}/|\.)""", RegexOption.IGNORE_CASE)
        } else {
            Regex("""(?:to|for)\s+([A-Za-z0-9\s.&'-]{3,48}?)(?:\s+on|\s+account|\s+\d{1,2}/|\.)""", RegexOption.IGNORE_CASE)
        }
        return pattern.find(body)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }
}
