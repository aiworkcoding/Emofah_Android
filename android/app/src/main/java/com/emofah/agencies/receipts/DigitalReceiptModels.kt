package com.emofah.agencies.receipts

import com.emofah.agencies.data.local.dao.LocalSaleWithItems
import com.emofah.agencies.data.local.entities.PendingFinancialEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import com.emofah.agencies.orders.StoreOrderRecord
import com.emofah.agencies.orders.StoreOrderStatus
import com.emofah.agencies.sales.PreparedSaleDraft
import com.emofah.agencies.sales.PriceOverridePolicy
import com.emofah.agencies.sales.RecordSaleOutcome
import com.emofah.agencies.sales.SaleHistoryMapper
import com.emofah.agencies.services.ServiceOperationDraft
import com.emofah.agencies.services.ServiceOperationResult
import com.emofah.agencies.services.ServiceOperationType
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ReceiptTransactionKind(val label: String) {
    PRODUCT_SALE("Product Sale"),
    ORDER_CONVERSION("Online Order Sale"),
    SERVICE_OPERATION("Service Operation"),
}

data class ReceiptLineItem(
    val title: String,
    val quantityLabel: String,
    val unitPrice: Double?,
    val lineTotal: Double,
    val note: String? = null,
)

data class DigitalReceiptData(
    val receiptNumber: String,
    val kind: ReceiptTransactionKind,
    val issuedAtEpochMs: Long,
    val paymentMethodLabel: String,
    val totalAmount: Double,
    val lines: List<ReceiptLineItem>,
    val syncStatusLabel: String,
    val isReversed: Boolean = false,
    val reversalReason: String? = null,
    val referenceOrderNumber: String? = null,
    val customerName: String? = null,
    val customerPhone: String? = null,
    val servedByLabel: String? = null,
    val notes: List<String> = emptyList(),
)

/**
 * Stage 7.1 — Digital Receipts Formatter & Domain Builder.
 * Strictly builds receipts from real committed transactions (sales, order conversions,
 * or service operations). Never fabricates IDs, prices, or missing customer records.
 */
object DigitalReceiptFormatter {

    const val BUSINESS_NAME = "EMOFAH AGENCIES"
    const val CURRENCY = "Ksh"

    fun formatSaleReceiptNumber(clientSaleId: String, serverSaleId: Long?): String {
        return if (serverSaleId != null && serverSaleId > 0L) {
            "EMO-S-$serverSaleId"
        } else {
            val clean = clientSaleId.replace("-", "").take(8).uppercase(Locale.US).ifBlank { "LOCAL" }
            "EMO-S-$clean"
        }
    }

    fun formatServiceReceiptNumber(serviceId: String): String {
        val clean = serviceId
            .removePrefix("fin-svc-")
            .removePrefix("svc-")
            .replace("-", "")
            .take(8)
            .uppercase(Locale.US)
            .ifBlank { "SERVICE" }
        return "EMO-SVC-$clean"
    }

    fun formatOrderReceiptNumber(order: StoreOrderRecord): String {
        val clean = order.displayNumber
            .removePrefix("#")
            .trim()
            .replace(" ", "")
            .uppercase(Locale.US)
            .ifBlank { order.id.replace("-", "").take(8).uppercase(Locale.US) }
        return "EMO-ORD-$clean"
    }

    fun buildFromSale(saleWithItems: LocalSaleWithItems): DigitalReceiptData {
        val sale = saleWithItems.sale
        val isOrderConversion = sale.source.equals("ANDROID_ORDER", ignoreCase = true)
        val syncLabel = when {
            sale.isReversed -> "Reversed"
            sale.syncStatus == SyncState.SERVER_CONFIRMED -> "Server Confirmed"
            sale.syncStatus == SyncState.CONFLICT -> "Conflict · Needs Review"
            sale.syncStatus == SyncState.FAILED -> "Sync Attention"
            else -> "Pending Sync"
        }

        val lines = if (saleWithItems.items.isNotEmpty()) {
            saleWithItems.items.map { item ->
                val isOverridden = item.originalSellPrice > 0.0 &&
                    abs(item.unitSellPrice - item.originalSellPrice) > PriceOverridePolicy.PRICE_EPSILON
                val overrideNote = if (isOverridden) {
                    item.priceOverrideReason?.takeIf { it.isNotBlank() }?.let { "Price adjusted ($it)" }
                        ?: "Authorized price adjustment"
                } else {
                    null
                }
                ReceiptLineItem(
                    title = item.productNameSnapshot,
                    quantityLabel = "${item.quantity}",
                    unitPrice = item.unitSellPrice,
                    lineTotal = item.lineTotal,
                    note = overrideNote,
                )
            }
        } else {
            listOf(
                ReceiptLineItem(
                    title = if (isOrderConversion) "Online Order Sale" else "Recorded Sale",
                    quantityLabel = "1",
                    unitPrice = sale.totalAmount,
                    lineTotal = sale.totalAmount,
                )
            )
        }

        return DigitalReceiptData(
            receiptNumber = formatSaleReceiptNumber(sale.clientSaleId, sale.serverSaleId),
            kind = if (isOrderConversion) ReceiptTransactionKind.ORDER_CONVERSION else ReceiptTransactionKind.PRODUCT_SALE,
            issuedAtEpochMs = sale.createdAtEpochMs,
            paymentMethodLabel = SaleHistoryMapper.formatPaymentMethod(sale.paymentMethod),
            totalAmount = sale.totalAmount,
            lines = lines,
            syncStatusLabel = syncLabel,
            isReversed = sale.isReversed,
            reversalReason = sale.reversalReason,
            servedByLabel = sale.actorId.takeIf { it.isNotBlank() },
            notes = buildList {
                if (sale.syncStatus != SyncState.SERVER_CONFIRMED && !sale.isReversed) {
                    add("Recorded offline; awaiting cloud sync.")
                }
            },
        )
    }

    fun buildFromSaleOutcome(
        outcome: RecordSaleOutcome,
        draft: PreparedSaleDraft,
        actorId: String,
    ): DigitalReceiptData? {
        return when (outcome) {
            is RecordSaleOutcome.SuccessOnline -> DigitalReceiptData(
                receiptNumber = formatSaleReceiptNumber(outcome.clientSaleId, outcome.serverSaleId),
                kind = ReceiptTransactionKind.PRODUCT_SALE,
                issuedAtEpochMs = draft.createdAtEpochMs,
                paymentMethodLabel = outcome.paymentMethod.label,
                totalAmount = outcome.totalAmount,
                lines = listOf(
                    ReceiptLineItem(
                        title = draft.productName,
                        quantityLabel = "${draft.quantity}",
                        unitPrice = draft.unitSellPrice,
                        lineTotal = outcome.totalAmount,
                        note = draft.priceOverrideReason?.takeIf { it.isNotBlank() }
                            ?.let { "Price adjusted ($it)" },
                    )
                ),
                syncStatusLabel = "Server Confirmed",
                servedByLabel = actorId.takeIf { it.isNotBlank() },
            )

            is RecordSaleOutcome.SavedOffline -> DigitalReceiptData(
                receiptNumber = formatSaleReceiptNumber(outcome.clientSaleId, null),
                kind = ReceiptTransactionKind.PRODUCT_SALE,
                issuedAtEpochMs = draft.createdAtEpochMs,
                paymentMethodLabel = outcome.paymentMethod.label,
                totalAmount = outcome.totalAmount,
                lines = listOf(
                    ReceiptLineItem(
                        title = draft.productName,
                        quantityLabel = "${draft.quantity}",
                        unitPrice = draft.unitSellPrice,
                        lineTotal = outcome.totalAmount,
                        note = draft.priceOverrideReason?.takeIf { it.isNotBlank() }
                            ?.let { "Price adjusted ($it)" },
                    )
                ),
                syncStatusLabel = "Pending Sync",
                servedByLabel = actorId.takeIf { it.isNotBlank() },
                notes = listOf("Recorded offline; queued for cloud sync."),
            )

            is RecordSaleOutcome.DuplicateIgnored,
            is RecordSaleOutcome.Rejected,
            -> null
        }
    }

    fun buildFromServiceOutcome(
        result: ServiceOperationResult,
        draft: ServiceOperationDraft,
        actorId: String,
        issuedAtEpochMs: Long = System.currentTimeMillis(),
    ): DigitalReceiptData? {
        val (receiptIdSeed, syncStatus, revenue) = when (result) {
            is ServiceOperationResult.SuccessOnline -> Triple(
                result.serverServiceId.ifBlank { draft.clientServiceId },
                "Server Confirmed",
                result.totalRevenue,
            )
            is ServiceOperationResult.SavedOffline -> Triple(
                result.clientServiceId,
                "Pending Sync",
                result.totalRevenue,
            )
            is ServiceOperationResult.DuplicateIgnored,
            is ServiceOperationResult.Rejected,
            -> return null
        }

        val unitPriceToShow = if (draft.unitPrice > 0.0 && draft.customTotalRevenue == null) {
            draft.unitPrice
        } else {
            null
        }
        val cleanNote = draft.customerNote.trim().takeIf { it.isNotBlank() }

        return DigitalReceiptData(
            receiptNumber = formatServiceReceiptNumber(receiptIdSeed),
            kind = ReceiptTransactionKind.SERVICE_OPERATION,
            issuedAtEpochMs = issuedAtEpochMs,
            paymentMethodLabel = draft.paymentMethod.label,
            totalAmount = revenue,
            lines = listOf(
                ReceiptLineItem(
                    title = draft.serviceType.label,
                    quantityLabel = "${draft.quantityOrDuration} ${draft.serviceType.unitLabel}",
                    unitPrice = unitPriceToShow,
                    lineTotal = revenue,
                    note = cleanNote,
                )
            ),
            syncStatusLabel = syncStatus,
            servedByLabel = actorId.takeIf { it.isNotBlank() },
            notes = buildList {
                if (result is ServiceOperationResult.SavedOffline) {
                    add("Recorded offline; queued for cloud sync.")
                }
            },
        )
    }

    fun buildFromServiceEntity(event: PendingFinancialEventEntity): DigitalReceiptData {
        val parsed = runCatching {
            event.parsedJson?.takeIf { it.isNotBlank() }?.let { JSONObject(it) }
        }.getOrNull()

        val serviceType = ServiceOperationType.fromCode(parsed?.optString("service_type"))
        val title = event.counterparty?.takeIf { it.isNotBlank() } ?: serviceType.label
        val qty = parsed?.optInt("quantity_or_duration", 0)?.takeIf { it > 0 }
        val unitLabel = parsed?.optString("unit_label").orEmpty().ifBlank { serviceType.unitLabel }
        val unitPrice = parsed?.optDouble("unit_price", 0.0)?.takeIf { !it.isNaN() && it > 0.0 }
        val paymentRaw = parsed?.optString("payment_method").orEmpty()
        val paymentLabel = if (paymentRaw.isNotBlank()) {
            SaleHistoryMapper.formatPaymentMethod(paymentRaw)
        } else {
            extractPaymentLabelFromEvidence(event.rawEvidence) ?: "Cash"
        }
        val customerNote = parsed?.optString("customer_note")?.takeIf { it.isNotBlank() }
            ?: event.rawEvidence?.takeIf { it.isNotBlank() }

        val qtyLabel = if (qty != null) "$qty $unitLabel" else "1 service"
        val syncLabel = when (event.syncStatus) {
            SyncState.SERVER_CONFIRMED -> "Server Confirmed"
            SyncState.CONFLICT -> "Conflict · Needs Review"
            SyncState.FAILED -> "Sync Attention"
            else -> "Pending Sync"
        }

        return DigitalReceiptData(
            receiptNumber = formatServiceReceiptNumber(event.eventId),
            kind = ReceiptTransactionKind.SERVICE_OPERATION,
            issuedAtEpochMs = event.eventTimestampEpochMs,
            paymentMethodLabel = paymentLabel,
            totalAmount = event.amount,
            lines = listOf(
                ReceiptLineItem(
                    title = title,
                    quantityLabel = qtyLabel,
                    unitPrice = unitPrice,
                    lineTotal = event.amount,
                    note = customerNote,
                )
            ),
            syncStatusLabel = syncLabel,
            notes = buildList {
                if (event.syncStatus != SyncState.SERVER_CONFIRMED) {
                    add("Recorded offline; awaiting cloud sync.")
                }
            },
        )
    }

    fun buildFromOrder(
        order: StoreOrderRecord,
        paymentMethodLabel: String? = null,
    ): DigitalReceiptData {
        val statusLabel = when {
            order.isPendingSync -> "Pending Sync"
            order.isConvertedToSale -> "Sale Recorded"
            order.status == StoreOrderStatus.FULFILLED -> "Fulfilled"
            else -> order.status.label
        }
        val lines = if (order.items.isNotEmpty()) {
            order.items.map { item ->
                ReceiptLineItem(
                    title = item.productName,
                    quantityLabel = "${item.quantity}",
                    unitPrice = item.unitPrice.takeIf { it > 0.0 },
                    lineTotal = item.lineTotal,
                )
            }
        } else {
            listOf(
                ReceiptLineItem(
                    title = "Storefront Order ${order.displayNumber}",
                    quantityLabel = "${order.totalUnits.coerceAtLeast(1)}",
                    unitPrice = null,
                    lineTotal = order.totalPrice,
                )
            )
        }

        return DigitalReceiptData(
            receiptNumber = formatOrderReceiptNumber(order),
            kind = ReceiptTransactionKind.ORDER_CONVERSION,
            issuedAtEpochMs = order.createdAtEpochMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
            paymentMethodLabel = paymentMethodLabel?.takeIf { it.isNotBlank() } ?: "Recorded Order Payment",
            totalAmount = order.totalPrice,
            lines = lines,
            syncStatusLabel = statusLabel,
            referenceOrderNumber = order.displayNumber,
            customerName = order.customerName,
            customerPhone = order.customerPhone,
            notes = buildList {
                order.deliveryNote?.takeIf { it.isNotBlank() }?.let { add("Note: $it") }
                if (order.isPendingSync) add("Order sync pending.")
            },
        )
    }

    fun formatMoney(amount: Double): String {
        val rounded = (amount * 100.0).roundToInt() / 100.0
        return if (abs(rounded - rounded.toLong().toDouble()) < 0.005) {
            "$CURRENCY ${rounded.roundToInt()}"
        } else {
            String.format(Locale.US, "%s %.2f", CURRENCY, rounded)
        }
    }

    fun formatPlainText(
        receipt: DigitalReceiptData,
        businessName: String = BUSINESS_NAME,
        locale: Locale = Locale.getDefault(),
    ): String {
        val dateStr = SimpleDateFormat("dd MMM yyyy, HH:mm", locale)
            .format(Date(receipt.issuedAtEpochMs))
        val rule = "--------------------------------"
        return buildString {
            appendLine(businessName)
            appendLine("DIGITAL RECEIPT")
            appendLine(rule)
            if (receipt.isReversed) {
                appendLine("*** REVERSED / VOID ***")
                receipt.reversalReason?.takeIf { it.isNotBlank() }?.let {
                    appendLine("Reason: $it")
                }
                appendLine(rule)
            }
            appendLine("Receipt #: ${receipt.receiptNumber}")
            appendLine("Date: $dateStr")
            appendLine("Type: ${receipt.kind.label}")
            receipt.referenceOrderNumber?.takeIf { it.isNotBlank() }?.let {
                appendLine("Order Ref: $it")
            }
            receipt.customerName?.takeIf { it.isNotBlank() }?.let {
                appendLine("Customer: $it")
            }
            receipt.customerPhone?.takeIf { it.isNotBlank() }?.let {
                appendLine("Phone: $it")
            }
            appendLine(rule)
            receipt.lines.forEach { line ->
                appendLine("${line.quantityLabel} x ${line.title}")
                val priceLine = buildString {
                    line.unitPrice?.takeIf { it > 0.0 }?.let {
                        append("  @ ${formatMoney(it)}  ")
                    } ?: append("  ")
                    append(formatMoney(line.lineTotal))
                }
                appendLine(priceLine)
                line.note?.takeIf { it.isNotBlank() }?.let {
                    appendLine("  ($it)")
                }
            }
            appendLine(rule)
            appendLine("TOTAL: ${formatMoney(receipt.totalAmount)}")
            appendLine("Paid via: ${receipt.paymentMethodLabel}")
            appendLine("Status: ${receipt.syncStatusLabel}")
            receipt.servedByLabel?.takeIf { it.isNotBlank() }?.let {
                appendLine("Served by: $it")
            }
            receipt.notes.forEach { note ->
                appendLine("Note: $note")
            }
            appendLine(rule)
            append("Thank you for choosing $businessName")
        }
    }

    private fun extractPaymentLabelFromEvidence(rawEvidence: String?): String? {
        if (rawEvidence.isNullOrBlank()) return null
        return when {
            rawEvidence.contains("M-Pesa", ignoreCase = true) -> "M-Pesa"
            rawEvidence.contains("Cash", ignoreCase = true) -> "Cash"
            rawEvidence.contains("Other", ignoreCase = true) -> "Other"
            else -> null
        }
    }
}
