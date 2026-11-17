package com.emofah.agencies.orders

import com.emofah.agencies.data.catalog.ProductIdentity
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

enum class StoreOrderStatus(
    val code: String,
    val label: String,
    val supabaseValue: String,
) {
    PENDING("PENDING", "Pending", "pending"),
    CONFIRMED("CONFIRMED", "Confirmed", "confirmed"),
    READY("READY", "Ready", "ready"),
    FULFILLED("FULFILLED", "Fulfilled", "fulfilled"),
    CANCELLED("CANCELLED", "Cancelled", "cancelled"),
    UNKNOWN("UNKNOWN", "Other", "pending"),
    ;

    companion object {
        fun fromRaw(raw: String?): StoreOrderStatus {
            val clean = raw?.trim()?.lowercase().orEmpty()
            return when {
                clean in setOf("pending", "new", "placed", "awaiting_payment", "awaiting_confirmation", "unpaid") -> PENDING
                clean in setOf("confirmed", "paid", "processing", "accepted", "preparing") -> CONFIRMED
                clean in setOf("ready", "ready_for_pickup", "packed", "dispatched") -> READY
                clean in setOf("fulfilled", "completed", "delivered", "converted", "closed") -> FULFILLED
                clean in setOf("cancelled", "canceled", "rejected", "void") -> CANCELLED
                else -> UNKNOWN
            }
        }
    }
}

data class StoreOrderItem(
    val productId: Long?,
    val productName: String,
    val quantity: Int,
    val unitPrice: Double,
    val lineTotal: Double,
)

data class StoreOrderRecord(
    val id: String,
    val displayNumber: String,
    val rawStatus: String,
    val status: StoreOrderStatus,
    val totalPrice: Double,
    val createdAtIso: String,
    val createdAtEpochMs: Long,
    val items: List<StoreOrderItem>,
    val customerName: String?,
    val customerPhone: String?,
    val deliveryNote: String?,
    val isPendingSync: Boolean = false,
    val isConvertedToSale: Boolean = false,
) {
    val totalUnits: Int
        get() = items.sumOf { it.quantity }
}

data class OrderFeedSnapshot(
    val orders: List<StoreOrderRecord> = emptyList(),
    val lastFetchedEpochMs: Long? = null,
    val isFromCache: Boolean = false,
    val statusMessage: String? = null,
)

data class PendingOrderStatusOverlay(
    val orderId: String,
    val targetRawStatus: String,
    val updatedAtEpochMs: Long,
)

data class OrderTransitionValidation(
    val isValid: Boolean,
    val errorMessage: String? = null,
)

/**
 * Stage 6.2 — Operational order lifecycle rules.
 * Keeps order state management clean while leaving sale/stock conversion for Stage 6.3.
 */
object OrderStatusTransitionPolicy {
    fun allowedNextStatuses(current: StoreOrderStatus): List<StoreOrderStatus> {
        return when (current) {
            StoreOrderStatus.PENDING, StoreOrderStatus.UNKNOWN -> listOf(
                StoreOrderStatus.CONFIRMED,
                StoreOrderStatus.READY,
                StoreOrderStatus.CANCELLED,
            )
            StoreOrderStatus.CONFIRMED -> listOf(
                StoreOrderStatus.READY,
                StoreOrderStatus.CANCELLED,
            )
            StoreOrderStatus.READY -> listOf(
                StoreOrderStatus.FULFILLED,
                StoreOrderStatus.CANCELLED,
            )
            StoreOrderStatus.FULFILLED, StoreOrderStatus.CANCELLED -> emptyList()
        }
    }

    fun actionLabel(target: StoreOrderStatus): String {
        return when (target) {
            StoreOrderStatus.CONFIRMED -> "Confirm Order"
            StoreOrderStatus.READY -> "Mark Ready"
            StoreOrderStatus.FULFILLED -> "Mark Fulfilled"
            StoreOrderStatus.CANCELLED -> "Cancel Order"
            else -> target.label
        }
    }

    fun validateTransition(
        current: StoreOrderStatus,
        target: StoreOrderStatus,
        reason: String?,
    ): OrderTransitionValidation {
        if (target == StoreOrderStatus.PENDING || target == StoreOrderStatus.UNKNOWN) {
            return OrderTransitionValidation(false, "Cannot transition order back to ${target.label}.")
        }
        val allowed = allowedNextStatuses(current)
        if (target !in allowed) {
            return OrderTransitionValidation(
                false,
                "Cannot change order from ${current.label} to ${target.label}."
            )
        }
        if (target == StoreOrderStatus.CANCELLED && (reason?.trim()?.length ?: 0) < 3) {
            return OrderTransitionValidation(
                false,
                "Please enter a short reason (min 3 characters) to cancel an order."
            )
        }
        return OrderTransitionValidation(true, null)
    }
}

data class AndroidOrderStatusRpcRequest(
    val clientUpdateId: String,
    val idempotencyKey: String,
    val orderId: String,
    val targetStatus: StoreOrderStatus,
    val reason: String?,
    val actorId: String,
    val deviceId: String,
    val updatedAtIso: String,
)

sealed interface OrderStatusRpcOutcome {
    data class CommittedOnline(
        val serverEventId: String,
        val orderId: String,
        val newRawStatus: String,
        val alreadyCommitted: Boolean,
    ) : OrderStatusRpcOutcome

    data class Rejected(
        val code: String,
        val message: String,
        val currentServerRawStatus: String? = null,
    ) : OrderStatusRpcOutcome

    data class AuthRequired(val message: String) : OrderStatusRpcOutcome
    data class RpcMissing(val message: String) : OrderStatusRpcOutcome
    data class NetworkOrTransientError(val message: String) : OrderStatusRpcOutcome
}

sealed interface OrderStatusUpdateResult {
    data class SuccessOnline(
        val orderId: String,
        val newStatus: StoreOrderStatus,
        val alreadyCommitted: Boolean,
    ) : OrderStatusUpdateResult

    data class SavedOffline(
        val orderId: String,
        val newStatus: StoreOrderStatus,
        val reason: String,
    ) : OrderStatusUpdateResult

    data class DuplicateIgnored(
        val clientUpdateId: String,
        val syncStatus: String,
    ) : OrderStatusUpdateResult

    data class Rejected(
        val message: String,
        val reconciledServerStatus: StoreOrderStatus? = null,
    ) : OrderStatusUpdateResult
}

/**
 * Stage 6.3 — Order-to-Sale Conversion Domain & Validation.
 * Never guesses/invents missing product IDs; enforces non-empty resolved lines
 * and single conversion per order.
 */
data class ResolvedOrderSaleLine(
    val productId: Long,
    val productName: String,
    val quantity: Int,
    val unitPrice: Double,
    val lineTotal: Double,
)

data class AndroidOrderConversionRpcRequest(
    val clientConversionId: String,
    val idempotencyKey: String,
    val orderId: String,
    val items: List<ResolvedOrderSaleLine>,
    val paymentMethod: String,
    val actorId: String,
    val deviceId: String,
    val convertedAtIso: String,
)

data class ConvertedOrderProductAck(
    val productId: Long,
    val qtyDeducted: Int,
    val serverQtyAfter: Int,
    val serverSaleId: Long?,
)

sealed interface OrderConversionRpcOutcome {
    data class CommittedOnline(
        val serverConversionId: String,
        val orderId: String,
        val totalAmount: Double,
        val updatedProducts: List<ConvertedOrderProductAck>,
        val alreadyCommitted: Boolean,
    ) : OrderConversionRpcOutcome

    data class Rejected(
        val code: String,
        val message: String,
        val alreadyConverted: Boolean = false,
    ) : OrderConversionRpcOutcome

    data class AuthRequired(val message: String) : OrderConversionRpcOutcome
    data class RpcMissing(val message: String) : OrderConversionRpcOutcome
    data class NetworkOrTransientError(val message: String) : OrderConversionRpcOutcome
}

sealed interface OrderConversionResult {
    data class SuccessOnline(
        val orderId: String,
        val totalAmount: Double,
        val itemsCount: Int,
        val alreadyCommitted: Boolean,
    ) : OrderConversionResult

    data class SavedOffline(
        val orderId: String,
        val totalAmount: Double,
        val itemsCount: Int,
        val reason: String,
    ) : OrderConversionResult

    data class DuplicateIgnored(
        val orderId: String,
        val message: String,
    ) : OrderConversionResult

    data class Rejected(val message: String) : OrderConversionResult
}

data class OrderConversionValidation(
    val isValid: Boolean,
    val errorMessage: String? = null,
)

object OrderConversionPolicy {
    fun canConvertStatus(status: StoreOrderStatus, alreadyConverted: Boolean): Boolean {
        if (alreadyConverted) return false
        return status == StoreOrderStatus.CONFIRMED ||
            status == StoreOrderStatus.READY ||
            status == StoreOrderStatus.FULFILLED
    }

    fun validateConversion(
        order: StoreOrderRecord,
        resolvedItems: List<ResolvedOrderSaleLine>,
    ): OrderConversionValidation {
        if (order.isConvertedToSale) {
            return OrderConversionValidation(false, "Order ${order.displayNumber} has already been converted to a sale.")
        }
        if (order.status == StoreOrderStatus.CANCELLED) {
            return OrderConversionValidation(false, "Cancelled orders cannot be converted to sales.")
        }
        if (order.status == StoreOrderStatus.PENDING || order.status == StoreOrderStatus.UNKNOWN) {
            return OrderConversionValidation(false, "Confirm the order before converting it to a sale.")
        }
        if (resolvedItems.isEmpty()) {
            return OrderConversionValidation(false, "No line items found on this order to record as a sale.")
        }
        for ((idx, line) in resolvedItems.withIndex()) {
            if (line.productId <= 0L) {
                return OrderConversionValidation(
                    false,
                    "Line ${idx + 1} (${line.productName}) must be linked to a valid catalogue product first."
                )
            }
            if (line.quantity <= 0) {
                return OrderConversionValidation(false, "${line.productName} quantity must be at least 1.")
            }
            if (line.lineTotal <= 0.0) {
                return OrderConversionValidation(
                    false,
                    "${line.productName} must have a selling price above Ksh 0."
                )
            }
        }
        return OrderConversionValidation(true, null)
    }
}

/**
 * Stage 6.1 + Stage 6.2 + Stage 6.3 — Online Order Retrieval & Cache Overlay Parser.
 * Matches verified live `public.orders` schema (`id`, `created_at`, `status`, `total_price`, `items`)
 * while gracefully extracting optional metadata when present inside the JSONB `items` payload.
 * Never fabricates orders, customer info, or product IDs.
 */
object StoreOrderParser {

    fun parseOrdersJson(
        rawJson: String,
        catalogueById: Map<Long, ProductIdentity> = emptyMap(),
        pendingOverlays: Map<String, PendingOrderStatusOverlay> = emptyMap(),
        convertedOrderIds: Set<String> = emptySet(),
    ): List<StoreOrderRecord> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("[")) return emptyList()

        return runCatching {
            val arr = JSONArray(trimmed)
            val result = ArrayList<StoreOrderRecord>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                parseOrderObject(obj, catalogueById)?.let { record ->
                    val overlay = pendingOverlays[record.id]
                    val isConverted = record.id in convertedOrderIds
                    if (overlay != null) {
                        result.add(
                            record.copy(
                                rawStatus = overlay.targetRawStatus,
                                status = StoreOrderStatus.fromRaw(overlay.targetRawStatus),
                                isPendingSync = true,
                                isConvertedToSale = isConverted,
                            )
                        )
                    } else {
                        result.add(record.copy(isConvertedToSale = isConverted))
                    }
                }
            }
            result.sortedByDescending { it.createdAtEpochMs }
        }.getOrDefault(emptyList())
    }

    fun updateOrderStatusInRawJson(
        rawJson: String?,
        orderId: String,
        newRawStatus: String,
    ): String? {
        val trimmed = rawJson?.trim() ?: return null
        if (trimmed.isEmpty() || !trimmed.startsWith("[")) return rawJson
        return runCatching {
            val arr = JSONArray(trimmed)
            var modified = false
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.opt("id")?.toString()?.trim() == orderId) {
                    obj.put("status", newRawStatus)
                    modified = true
                    break
                }
            }
            if (modified) arr.toString() else rawJson
        }.getOrDefault(rawJson)
    }

    private fun parseOrderObject(
        obj: JSONObject,
        catalogueById: Map<Long, ProductIdentity>,
    ): StoreOrderRecord? {
        val rawId = obj.opt("id")?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        val displayNumber = if (rawId.all { it.isDigit() }) {
            "#$rawId"
        } else {
            "#${rawId.take(8).uppercase()}"
        }

        val rawStatus = optNonBlank(obj, "status") ?: "pending"
        val status = StoreOrderStatus.fromRaw(rawStatus)
        val totalPrice = obj.optDouble("total_price", 0.0).let { if (it.isNaN() || it < 0.0) 0.0 else it }
        val createdAtIso = optNonBlank(obj, "created_at").orEmpty()
        val createdAtEpochMs = runCatching {
            if (createdAtIso.isNotBlank()) Instant.parse(createdAtIso).toEpochMilli() else 0L
        }.getOrDefault(0L)

        var customerName = optNonBlank(obj, "customer_name") ?: optNonBlank(obj, "name")
        var customerPhone = optNonBlank(obj, "customer_phone") ?: optNonBlank(obj, "phone")
        var deliveryNote = optNonBlank(obj, "delivery_note") ?: optNonBlank(obj, "notes")

        val rawItems = obj.opt("items")
        val parsedItems = mutableListOf<StoreOrderItem>()

        fun extractItemsFromArray(itemsArr: JSONArray) {
            for (j in 0 until itemsArr.length()) {
                val lineObj = itemsArr.optJSONObject(j) ?: continue
                val pid = when {
                    lineObj.has("product_id") && !lineObj.isNull("product_id") ->
                        lineObj.optLong("product_id", -1L).takeIf { it > 0L }
                    lineObj.has("id") && !lineObj.isNull("id") ->
                        lineObj.optLong("id", -1L).takeIf { it > 0L }
                    else -> null
                }
                val qty = (lineObj.optInt("qty", 0).takeIf { it > 0 }
                    ?: lineObj.optInt("quantity", 0).takeIf { it > 0 }
                    ?: lineObj.optInt("qty_sold", 0).takeIf { it > 0 }
                    ?: 1)

                val catalogueName = pid?.let { catalogueById[it]?.name }
                val lineName = optNonBlank(lineObj, "name")
                    ?: optNonBlank(lineObj, "product_name")
                    ?: optNonBlank(lineObj, "title")
                    ?: catalogueName
                    ?: if (pid != null) "Product #$pid" else "Unspecified item"

                val unitPrice = sequenceOf(
                    optDouble(lineObj, "unit_price"),
                    optDouble(lineObj, "price"),
                    optDouble(lineObj, "sell_price"),
                    pid?.let { catalogueById[it]?.effectiveSellPrice },
                ).firstOrNull { it != null && it >= 0.0 } ?: 0.0

                val lineTotal = optDouble(lineObj, "line_total")
                    ?: optDouble(lineObj, "total")
                    ?: (unitPrice * qty)

                parsedItems.add(
                    StoreOrderItem(
                        productId = pid,
                        productName = lineName,
                        quantity = qty,
                        unitPrice = unitPrice,
                        lineTotal = lineTotal,
                    )
                )
            }
        }

        fun extractFromJsonObjectWrapper(wrapper: JSONObject) {
            if (customerName == null) {
                customerName = optNonBlank(wrapper, "customer_name") ?: optNonBlank(wrapper, "customer") ?: optNonBlank(wrapper, "name")
            }
            if (customerPhone == null) {
                customerPhone = optNonBlank(wrapper, "customer_phone") ?: optNonBlank(wrapper, "phone") ?: optNonBlank(wrapper, "mobile")
            }
            if (deliveryNote == null) {
                deliveryNote = optNonBlank(wrapper, "delivery_note") ?: optNonBlank(wrapper, "note") ?: optNonBlank(wrapper, "address")
            }
            val nestedArr = wrapper.optJSONArray("items")
                ?: wrapper.optJSONArray("lines")
                ?: wrapper.optJSONArray("cart")
            if (nestedArr != null) {
                extractItemsFromArray(nestedArr)
            }
        }

        when (rawItems) {
            is JSONArray -> extractItemsFromArray(rawItems)
            is JSONObject -> extractFromJsonObjectWrapper(rawItems)
            is String -> {
                val clean = rawItems.trim()
                if (clean.startsWith("[")) {
                    runCatching { JSONArray(clean) }.getOrNull()?.let(::extractItemsFromArray)
                } else if (clean.startsWith("{")) {
                    runCatching { JSONObject(clean) }.getOrNull()?.let(::extractFromJsonObjectWrapper)
                }
            }
        }

        return StoreOrderRecord(
            id = rawId,
            displayNumber = displayNumber,
            rawStatus = rawStatus,
            status = status,
            totalPrice = totalPrice,
            createdAtIso = createdAtIso,
            createdAtEpochMs = createdAtEpochMs,
            items = parsedItems,
            customerName = customerName,
            customerPhone = customerPhone,
            deliveryNote = deliveryNote,
        )
    }

    private fun optNonBlank(obj: JSONObject, key: String): String? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return obj.optString(key, "").trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    }

    private fun optDouble(obj: JSONObject, key: String): Double? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val v = obj.optDouble(key, Double.NaN)
        return if (v.isNaN() || v < 0.0) null else v
    }
}
