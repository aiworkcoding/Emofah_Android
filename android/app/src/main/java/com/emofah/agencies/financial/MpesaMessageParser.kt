package com.emofah.agencies.financial

enum class MpesaTransactionChannel(val code: String, val label: String) {
    RECEIVED_MONEY("RECEIVED_MONEY", "Received Money"),
    PAYBILL("PAYBILL", "Paybill"),
    BUY_GOODS_TILL("BUY_GOODS_TILL", "Buy Goods / Till"),
    SEND_MONEY("SEND_MONEY", "Send Money"),
    WITHDRAW_AGENT("WITHDRAW_AGENT", "Agent Withdrawal"),
    DEPOSIT("DEPOSIT", "Cash Deposit"),
    AIRTIME_BUNDLE("AIRTIME_BUNDLE", "Airtime / Bundle"),
    FULIZA_REPAYMENT("FULIZA_REPAYMENT", "Fuliza Repayment"),
    REVERSAL("REVERSAL", "Reversal"),
    UNKNOWN("UNKNOWN", "Unclassified M-Pesa"),
}

enum class MpesaParseStatus {
    VALID_PARSED,
    AMBIGUOUS_NEEDS_REVIEW,
    INVALID_MALFORMED,
}

data class ParsedMpesaMessage(
    val sender: String,
    val transactionCode: String,
    val channel: MpesaTransactionChannel,
    val direction: String, // INFLOW, OUTFLOW, TRANSFER
    val amount: Double,
    val currency: String = "KES",
    val counterparty: String?,
    val counterpartyAccount: String?,
    val transactionCost: Double?,
    val newBalance: Double?,
    val occurredText: String?,
    val confidence: Float,
    val parseStatus: MpesaParseStatus,
    val validationWarnings: List<String>,
    val rawBody: String,
)

sealed class MpesaParseOutcome {
    data class Parsed(val data: ParsedMpesaMessage) : MpesaParseOutcome()
    data class Rejected(val reason: String) : MpesaParseOutcome()
}

/**
 * Stage 5.3 — Deterministic Financial Message Parser & Validation.
 * Handles major Safaricom M-Pesa SMS formats, extracts code/amount/direction/channel/cost/balance,
 * flags ambiguous messages safely without inventing amounts or references, and keeps raw evidence intact.
 */
object MpesaMessageParser {
    private const val MAX_REASONABLE_MPESA_TX_KES = 500_000.0

    private val TX_CODE_REGEX = Regex("""\b([A-Z](?=[A-Z0-9]*\d)[A-Z0-9]{9})\b""")
    private val PRIMARY_AMOUNT_REGEX = Regex(
        """(?:Ksh|KES)\.?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE,
    )
    private val BALANCE_REGEX = Regex(
        """balance\s+is\s+(?:Ksh|KES)\.?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE,
    )
    private val COST_REGEX = Regex(
        """transaction\s+cost,?\s*(?:Ksh|KES)\.?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""",
        RegexOption.IGNORE_CASE,
    )
    private val DATE_TIME_REGEX = Regex(
        """on\s+(\d{1,2}/\d{1,2}/\d{2,4}\s+at\s+\d{1,2}:\d{2}\s*(?:AM|PM))""",
        RegexOption.IGNORE_CASE,
    )
    private val ACCOUNT_REGEX = Regex(
        """for\s+account\s+([A-Za-z0-9._-]{2,40})""",
        RegexOption.IGNORE_CASE,
    )
    private val PHONE_REGEX = Regex("""\b((?:07|01|2547|2541)\d{8})\b""")

    fun parseAndValidate(
        sender: String,
        body: String,
        allowManualPasteOverride: Boolean = false,
    ): MpesaParseOutcome {
        val cleanSender = sender.trim()
        val cleanBody = body.trim()

        if (cleanBody.isBlank()) {
            return MpesaParseOutcome.Rejected("Message body is empty.")
        }

        if (!allowManualPasteOverride && !MpesaDetectionPolicy.isAuthorizedMpesaSender(cleanSender)) {
            return MpesaParseOutcome.Rejected("Sender '$cleanSender' is not an authorized M-Pesa sender.")
        }

        val lower = cleanBody.lowercase()
        if (!lower.contains("confirmed")) {
            return MpesaParseOutcome.Rejected("Missing M-Pesa confirmation marker.")
        }

        val txMatch = TX_CODE_REGEX.find(cleanBody.uppercase())
            ?: return MpesaParseOutcome.Rejected("Missing valid 10-character M-Pesa reference code.")
        val txCode = txMatch.groupValues[1]

        val amountMatch = PRIMARY_AMOUNT_REGEX.find(cleanBody)
            ?: return MpesaParseOutcome.Rejected("Missing transaction amount in Ksh/KES.")
        val amount = amountMatch.groupValues[1].replace(",", "").toDoubleOrNull()
            ?: return MpesaParseOutcome.Rejected("Transaction amount could not be parsed.")

        if (!amount.isFinite() || amount <= 0.0) {
            return MpesaParseOutcome.Rejected("Transaction amount must be a finite value greater than zero.")
        }

        val warnings = mutableListOf<String>()
        if (amount > MAX_REASONABLE_MPESA_TX_KES) {
            warnings += "Amount exceeds typical M-Pesa single-transaction cap (${MAX_REASONABLE_MPESA_TX_KES.toInt()}); review required."
        }

        val channel = classifyChannel(lower)
        val direction = determineDirection(channel, lower)
        if (direction == null) {
            return MpesaParseOutcome.Rejected("Unable to determine financial movement direction.")
        }

        val counterpartyAccount = ACCOUNT_REGEX.find(cleanBody)?.groupValues?.getOrNull(1)?.trim()
            ?: PHONE_REGEX.find(cleanBody)?.groupValues?.getOrNull(1)?.trim()
        val counterparty = extractCounterparty(cleanBody, channel, direction)
        if (counterparty == null) {
            warnings += "Counterparty name not clearly identified; verify manually."
        }

        val transactionCost = COST_REGEX.find(cleanBody)?.groupValues?.getOrNull(1)
            ?.replace(",", "")?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
        val newBalance = BALANCE_REGEX.find(cleanBody)?.groupValues?.getOrNull(1)
            ?.replace(",", "")?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
        val occurredText = DATE_TIME_REGEX.find(cleanBody)?.groupValues?.getOrNull(1)?.trim()

        val parseStatus = when {
            channel == MpesaTransactionChannel.UNKNOWN -> MpesaParseStatus.AMBIGUOUS_NEEDS_REVIEW
            warnings.isNotEmpty() -> MpesaParseStatus.AMBIGUOUS_NEEDS_REVIEW
            else -> MpesaParseStatus.VALID_PARSED
        }

        var confidence = if (MpesaDetectionPolicy.isAuthorizedMpesaSender(cleanSender)) 0.96f else 0.85f
        if (parseStatus == MpesaParseStatus.AMBIGUOUS_NEEDS_REVIEW) {
            confidence = minOf(confidence, 0.65f)
        }

        return MpesaParseOutcome.Parsed(
            ParsedMpesaMessage(
                sender = cleanSender.ifBlank { "MPESA" },
                transactionCode = txCode,
                channel = channel,
                direction = direction,
                amount = amount,
                counterparty = counterparty,
                counterpartyAccount = counterpartyAccount,
                transactionCost = transactionCost,
                newBalance = newBalance,
                occurredText = occurredText,
                confidence = confidence,
                parseStatus = parseStatus,
                validationWarnings = warnings,
                rawBody = cleanBody,
            )
        )
    }

    private fun classifyChannel(lower: String): MpesaTransactionChannel {
        return when {
            lower.contains("reversed") || lower.contains("reversal") -> MpesaTransactionChannel.REVERSAL
            lower.contains("you have received") || lower.contains("received ksh") -> MpesaTransactionChannel.RECEIVED_MONEY
            lower.contains("for account") || lower.contains("paybill") -> MpesaTransactionChannel.PAYBILL
            lower.contains("paid to") -> MpesaTransactionChannel.BUY_GOODS_TILL
            lower.contains("sent to") -> MpesaTransactionChannel.SEND_MONEY
            lower.contains("withdraw") -> MpesaTransactionChannel.WITHDRAW_AGENT
            lower.contains("deposited") || lower.contains("give ksh") -> MpesaTransactionChannel.DEPOSIT
            lower.contains("you bought") &&
                (lower.contains("airtime") || lower.contains("bundle") || lower.contains("data")) ->
                MpesaTransactionChannel.AIRTIME_BUNDLE
            lower.contains("fuliza") -> MpesaTransactionChannel.FULIZA_REPAYMENT
            else -> MpesaTransactionChannel.UNKNOWN
        }
    }

    private fun determineDirection(channel: MpesaTransactionChannel, lower: String): String? {
        return when (channel) {
            MpesaTransactionChannel.RECEIVED_MONEY,
            MpesaTransactionChannel.DEPOSIT,
            MpesaTransactionChannel.REVERSAL -> "INFLOW"

            MpesaTransactionChannel.PAYBILL,
            MpesaTransactionChannel.BUY_GOODS_TILL,
            MpesaTransactionChannel.SEND_MONEY,
            MpesaTransactionChannel.AIRTIME_BUNDLE,
            MpesaTransactionChannel.FULIZA_REPAYMENT -> "OUTFLOW"

            MpesaTransactionChannel.WITHDRAW_AGENT -> "TRANSFER"

            MpesaTransactionChannel.UNKNOWN -> when {
                lower.contains("received") -> "INFLOW"
                lower.contains("paid") || lower.contains("sent") || lower.contains("bought") -> "OUTFLOW"
                else -> null
            }
        }
    }

    private fun extractCounterparty(
        body: String,
        channel: MpesaTransactionChannel,
        direction: String,
    ): String? {
        if (channel == MpesaTransactionChannel.AIRTIME_BUNDLE) {
            return "Safaricom Airtime"
        }
        if (channel == MpesaTransactionChannel.FULIZA_REPAYMENT) {
            return "Safaricom Fuliza"
        }
        val regex = when {
            channel == MpesaTransactionChannel.WITHDRAW_AGENT ->
                Regex("""from\s+([A-Za-z0-9\s.&'-]{3,60}?)(?:\s+New\s+M-PESA|\s+on|\.)""", RegexOption.IGNORE_CASE)
            channel == MpesaTransactionChannel.DEPOSIT ->
                Regex("""(?:to|at)\s+([A-Za-z0-9\s.&'-]{3,60}?)(?:\s+New\s+M-PESA|\s+on|\.)""", RegexOption.IGNORE_CASE)
            channel == MpesaTransactionChannel.PAYBILL ->
                Regex("""(?:sent|paid)\s+to\s+([A-Za-z0-9\s.&'-]{2,48}?)\s+for\s+account""", RegexOption.IGNORE_CASE)
            direction == "INFLOW" ->
                Regex("""from\s+([A-Za-z0-9\s.&'-]{3,48}?)(?:\s+on|\s+\d{1,2}/|\.)""", RegexOption.IGNORE_CASE)
            else ->
                Regex("""(?:to|for)\s+([A-Za-z0-9\s.&'-]{3,48}?)(?:\.\s+on|\s+on|\s+for\s+account|\s+\d{1,2}/|\.)""", RegexOption.IGNORE_CASE)
        }
        return regex.find(body)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }
}
