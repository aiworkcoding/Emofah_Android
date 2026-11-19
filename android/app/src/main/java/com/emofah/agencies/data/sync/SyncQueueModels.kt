package com.emofah.agencies.data.sync

import com.emofah.agencies.data.catalog.RegisterProductRpcRequest
import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.financial.AndroidFinancialEventRpcRequest
import com.emofah.agencies.orders.AndroidOrderConversionRpcRequest
import com.emofah.agencies.orders.AndroidOrderStatusRpcRequest
import com.emofah.agencies.orders.ResolvedOrderSaleLine
import com.emofah.agencies.orders.StoreOrderStatus
import com.emofah.agencies.purchases.AndroidPurchaseLineRequest
import com.emofah.agencies.purchases.AndroidPurchaseRpcRequest
import com.emofah.agencies.purchases.PurchaseItemCategory
import com.emofah.agencies.recognition.RecognitionCorrectionRpcRequest
import com.emofah.agencies.sales.AndroidSaleReversalRpcRequest
import com.emofah.agencies.sales.AndroidSaleRpcRequest
import com.emofah.agencies.services.AndroidServiceRpcRequest
import com.emofah.agencies.services.ServiceConsumableRpcLine
import com.emofah.agencies.services.ServiceOperationType
import com.emofah.agencies.stock.AndroidStockAdjustmentRpcRequest
import com.emofah.agencies.stock.AndroidStockReceiptRpcRequest
import com.emofah.agencies.stock.StockCountItemDraft
import com.emofah.agencies.stock.StockCountReason
import com.emofah.agencies.stock.StockCountSessionDraft
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

object SyncQueueStatus {
    const val QUEUED = "QUEUED"
    const val IN_FLIGHT = "IN_FLIGHT"
    const val SYNCED = "SYNCED"
    const val FAILED_RETRYABLE = "FAILED_RETRYABLE"
    const val CONFLICT = "CONFLICT"
    const val AUTH_REQUIRED = "AUTH_REQUIRED"
    const val RESOLVED_LOCALLY = "RESOLVED_LOCALLY"
}

object SyncEventType {
    const val PRODUCT_DRAFT = "PRODUCT_DRAFT"
    const val PRODUCT_IMAGE = "PRODUCT_IMAGE"
    const val SALE = "SALE"
    const val SALE_REVERSAL = "SALE_REVERSAL"
    const val STOCK_MOVEMENT = "STOCK_MOVEMENT"
    const val STOCK_ADJUSTMENT = "STOCK_ADJUSTMENT"
    const val FINANCIAL_EVENT = "FINANCIAL_EVENT"
    const val ORDER_UPDATE = "ORDER_UPDATE"
    const val ORDER_TO_SALE = "ORDER_TO_SALE"
    const val RECOGNITION_CORRECTION = "RECOGNITION_CORRECTION"
    const val STOCK_COUNT = "STOCK_COUNT"
    const val PURCHASE_RECEIPT = "PURCHASE_RECEIPT"
    const val SERVICE_OPERATION = "SERVICE_OPERATION"
}

data class SyncPassSummary(
    val attemptedCount: Int,
    val syncedCount: Int,
    val retryableCount: Int,
    val conflictCount: Int,
    val authBlockedCount: Int,
    val deferredCount: Int,
    val skippedOffline: Boolean = false,
)

data class ParsedProductDraftQueuePayload(
    val localTempProductId: Long,
    val ocrText: String?,
    val rpcRequest: RegisterProductRpcRequest,
)

/**
 * Pure Stage 3.3 + Stage 1.5 policy rules for dependency ordering, bounded exponential backoff,
 * and deterministic sale/product-draft payload deserialization.
 */
object SyncQueuePolicy {
    const val BASE_BACKOFF_MS = 15_000L
    const val MAX_BACKOFF_MS = 15 * 60_000L
    const val STALE_IN_FLIGHT_MS = 2 * 60_000L

    fun computeBackoffDelayMs(retryCount: Int): Long {
        val clampedAttempt = retryCount.coerceIn(0, 6)
        val exponential = BASE_BACKOFF_MS * (1L shl clampedAttempt)
        return min(exponential, MAX_BACKOFF_MS)
    }

    fun computeNextRetryEpochMs(nowEpochMs: Long, nextRetryCount: Int): Long {
        return nowEpochMs + computeBackoffDelayMs(retryCount = (nextRetryCount - 1).coerceAtLeast(0))
    }

    fun isDependencySatisfied(
        event: SyncQueueEventEntity,
        eventsById: Map<String, SyncQueueEventEntity>,
    ): Boolean {
        val depId = event.dependencyEventId ?: return true
        val parent = eventsById[depId] ?: return false
        return parent.status == SyncQueueStatus.SYNCED
    }

    fun filterRunnable(
        events: List<SyncQueueEventEntity>,
        nowEpochMs: Long,
    ): List<SyncQueueEventEntity> {
        val byId = events.associateBy { it.eventId }
        return events
            .filter { evt ->
                (evt.status == SyncQueueStatus.QUEUED || evt.status == SyncQueueStatus.FAILED_RETRYABLE) &&
                    evt.nextRetryAtEpochMs <= nowEpochMs &&
                    isDependencySatisfied(evt, byId)
            }
            .sortedBy { it.createdAtEpochMs }
    }

    /**
     * Produces a deterministic parent-before-child ordering for inspection/tests
     * with cycle protection so broken chains terminate safely.
     */
    fun sortDependencySafe(events: List<SyncQueueEventEntity>): List<SyncQueueEventEntity> {
        val byId = events.associateBy { it.eventId }
        val visited = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()
        val ordered = mutableListOf<SyncQueueEventEntity>()

        fun visit(event: SyncQueueEventEntity) {
            if (event.eventId in visited || event.eventId in visiting) return
            visiting.add(event.eventId)
            val parentId = event.dependencyEventId
            if (parentId != null) {
                byId[parentId]?.let { visit(it) }
            }
            visiting.remove(event.eventId)
            if (visited.add(event.eventId)) {
                ordered.add(event)
            }
        }

        events.sortedBy { it.createdAtEpochMs }.forEach { visit(it) }
        return ordered
    }

    fun parseSaleRpcRequest(event: SyncQueueEventEntity): AndroidSaleRpcRequest? {
        return runCatching {
            val json = JSONObject(event.payloadJson)
            val productId = json.optLong("product_id", -1L)
            val qtySold = json.optInt("qty_sold", -1)
            val totalPrice = json.optDouble("total_price", -1.0)
            val clientSaleId = json.optString("client_sale_id", event.entityId)
            val idempotencyKey = json.optString("idempotency_key", "")
            val paymentMethod = json.optString("payment_method", "CASH")
            val actorId = json.optString("actor_id", event.actorId)
            val deviceId = json.optString("device_id", event.deviceId)
            val saleDate = json.optString("sale_date", "")

            if (productId <= 0L || qtySold <= 0 || !totalPrice.isFinite() || totalPrice <= 0.0 ||
                clientSaleId.isBlank() || idempotencyKey.isBlank() || saleDate.isBlank()
            ) {
                return null
            }

            val unitSellPrice = if (json.has("unit_sell_price") && !json.isNull("unit_sell_price")) {
                json.optDouble("unit_sell_price").takeIf { !it.isNaN() && it > 0.0 }
            } else {
                null
            }
            val originalSellPrice = if (json.has("original_sell_price") && !json.isNull("original_sell_price")) {
                json.optDouble("original_sell_price").takeIf { !it.isNaN() && it > 0.0 }
            } else {
                null
            }
            val overrideReason = json.optString("price_override_reason").takeIf { it.isNotEmpty() && it != "null" }
            val actorRole = json.optString("actor_role", "CASHIER").ifBlank { "CASHIER" }

            AndroidSaleRpcRequest(
                productId = productId,
                quantity = qtySold,
                totalAmount = totalPrice,
                clientSaleId = clientSaleId,
                idempotencyKey = idempotencyKey,
                paymentMethod = paymentMethod,
                actorId = actorId,
                deviceId = deviceId,
                saleDateIso = saleDate,
                unitSellPrice = unitSellPrice,
                originalSellPrice = originalSellPrice,
                priceOverrideReason = overrideReason,
                actorRole = actorRole,
            )
        }.getOrNull()
    }

    fun parseProductDraftPayload(event: SyncQueueEventEntity): ParsedProductDraftQueuePayload? {
        return runCatching {
            val json = JSONObject(event.payloadJson)
            val draftId = json.optString("draft_id", event.entityId)
            val idempotencyKey = json.optString("idempotency_key", "")
            val localTempId = json.optLong("local_temp_product_id", 0L)
            val name = json.optString("name", "").trim()
            val category = json.optString("category", "General").ifBlank { "General" }
            val sellPrice = json.optDouble("sell_price", 0.0)
            val buyPrice = json.optDouble("buy_price", 0.0)
            val initialQty = json.optInt("initial_qty", 0)
            val description = json.optString("description").takeIf { it.isNotEmpty() && it != "null" }
            val localPhotoPath = json.optString("local_photo_path").takeIf { it.isNotEmpty() && it != "null" }
            val ocrText = json.optString("ocr_text").takeIf { it.isNotEmpty() && it != "null" }
            val actorId = json.optString("actor_id", event.actorId)
            val deviceId = json.optString("device_id", event.deviceId)

            if (draftId.isBlank() || idempotencyKey.isBlank() || name.length < 2 ||
                !sellPrice.isFinite() || sellPrice <= 0.0 ||
                !buyPrice.isFinite() || buyPrice < 0.0 || initialQty < 0
            ) {
                return null
            }

            ParsedProductDraftQueuePayload(
                localTempProductId = localTempId,
                ocrText = ocrText,
                rpcRequest = RegisterProductRpcRequest(
                    draftId = draftId,
                    idempotencyKey = idempotencyKey,
                    name = name,
                    category = category,
                    sellPrice = sellPrice,
                    buyPrice = buyPrice,
                    initialQty = initialQty,
                    description = description,
                    localPhotoPath = localPhotoPath,
                    actorId = actorId,
                    deviceId = deviceId,
                ),
            )
        }.getOrNull()
    }

    fun relinkSalePayloadProductId(payloadJson: String, newProductId: Long): String {
        return runCatching {
            val root = JSONObject(payloadJson)
            root.put("product_id", newProductId)
            val items = root.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                item.put("product_id", newProductId)
            }
            root.toString()
        }.getOrDefault(payloadJson)
    }

    fun parseRecognitionCorrectionPayload(event: SyncQueueEventEntity): RecognitionCorrectionRpcRequest? {
        return runCatching {
            val json = JSONObject(event.payloadJson)
            val correctionId = json.optString("correction_id", event.entityId)
            val idempotencyKey = json.optString("idempotency_key", "")
            val confirmedProductId = json.optLong("confirmed_product_id", -1L)
            if (correctionId.isBlank() || idempotencyKey.isBlank() || confirmedProductId <= 0L) {
                return null
            }

            val suggestedRaw = if (json.isNull("suggested_product_id")) -1L else json.optLong("suggested_product_id", -1L)
            val suggestedProductId = suggestedRaw.takeIf { it > 0L }

            RecognitionCorrectionRpcRequest(
                correctionId = correctionId,
                idempotencyKey = idempotencyKey,
                suggestedProductId = suggestedProductId,
                suggestedProductName = json.optString("suggested_product_name").takeIf { it.isNotEmpty() && it != "null" },
                confirmedProductId = confirmedProductId,
                matchStatus = json.optString("match_status", "UNIDENTIFIED").ifBlank { "UNIDENTIFIED" },
                selectionKind = json.optString("selection_kind", "MANUAL_SEARCH_OVERRIDE").ifBlank { "MANUAL_SEARCH_OVERRIDE" },
                usedCloudAssist = json.optBoolean("used_cloud_assist", false),
                learnedAlias = json.optString("learned_alias").takeIf { it.isNotEmpty() && it != "null" },
                ocrSnippet = json.optString("ocr_snippet").takeIf { it.isNotEmpty() && it != "null" },
                visualDHashHex = json.optString("visual_dhash_hex").takeIf { it.isNotEmpty() && it != "null" },
                localPhotoPath = json.optString("local_photo_path").takeIf { it.isNotEmpty() && it != "null" },
                actorId = json.optString("actor_id", event.actorId),
                deviceId = json.optString("device_id", event.deviceId),
            )
        }.getOrNull()
    }

    fun relinkRecognitionCorrectionPayloadProductId(
        payloadJson: String,
        oldTempProductId: Long,
        newProductId: Long,
    ): String {
        return runCatching {
            val root = JSONObject(payloadJson)
            if (root.optLong("confirmed_product_id", 0L) == oldTempProductId) {
                root.put("confirmed_product_id", newProductId)
            }
            if (!root.isNull("suggested_product_id") && root.optLong("suggested_product_id", 0L) == oldTempProductId) {
                root.put("suggested_product_id", newProductId)
            }
            root.toString()
        }.getOrDefault(payloadJson)
    }

    fun parseSaleReversalRpcRequest(event: SyncQueueEventEntity): AndroidSaleReversalRpcRequest? {
        return runCatching {
            val json = JSONObject(event.payloadJson)
            val clientSaleId = json.optString("client_sale_id", event.entityId)
            val serverSaleId = if (json.has("server_sale_id") && !json.isNull("server_sale_id")) {
                json.optLong("server_sale_id").takeIf { it > 0L }
            } else {
                null
            }
            val productId = json.optLong("product_id", -1L)
            val qty = json.optInt("qty", -1)
            val total = json.optDouble("total", -1.0)
            val idempotencyKey = json.optString("idempotency_key", "")
            val reason = json.optString("reason", "").trim()
            val paymentMethod = json.optString("payment_method", "CASH").ifBlank { "CASH" }
            val actorId = json.optString("actor_id", event.actorId)
            val actorRole = json.optString("actor_role", "SUPERVISOR").ifBlank { "SUPERVISOR" }
            val deviceId = json.optString("device_id", event.deviceId)
            val reversedAtIso = json.optString("reversed_at", "")

            if (clientSaleId.isBlank() || productId <= 0L || qty <= 0 ||
                !total.isFinite() || total <= 0.0 ||
                idempotencyKey.isBlank() || reason.isBlank() || reversedAtIso.isBlank()
            ) {
                return null
            }

            AndroidSaleReversalRpcRequest(
                clientSaleId = clientSaleId,
                serverSaleId = serverSaleId,
                productId = productId,
                quantity = qty,
                totalAmount = total,
                idempotencyKey = idempotencyKey,
                reason = reason,
                paymentMethod = paymentMethod,
                actorId = actorId,
                actorRole = actorRole,
                deviceId = deviceId,
                reversedAtIso = reversedAtIso,
            )
        }.getOrNull()
    }

    fun parseStockMovementRpcRequest(event: SyncQueueEventEntity): AndroidStockReceiptRpcRequest? {
        return runCatching {
            val json = JSONObject(event.payloadJson)
            val clientReceiptId = json.optString("client_receipt_id", event.entityId)
            val idempotencyKey = json.optString("idempotency_key", "")
            val productId = json.optLong("product_id", -1L)
            val qty = json.optInt("qty", -1)
            val unitCost = if (json.has("unit_cost") && !json.isNull("unit_cost")) {
                json.optDouble("unit_cost").takeIf { it.isFinite() && it >= 0.0 }
            } else {
                null
            }
            val supplier = json.optString("supplier").takeIf { it.isNotEmpty() && it != "null" }
            val invoiceRef = json.optString("invoice_ref").takeIf { it.isNotEmpty() && it != "null" }
            val invoicePhotoUrl = json.optString("invoice_photo_url").takeIf { it.isNotEmpty() && it != "null" }
            val actorId = json.optString("actor_id", event.actorId)
            val deviceId = json.optString("device_id", event.deviceId)
            val receivedAtIso = json.optString("received_at", "")
            val updateBuyPrice = json.optBoolean("update_buy_price", false)

            if (clientReceiptId.isBlank() || idempotencyKey.isBlank() || productId <= 0L || qty <= 0) {
                return null
            }

            AndroidStockReceiptRpcRequest(
                productId = productId,
                quantity = qty,
                clientReceiptId = clientReceiptId,
                idempotencyKey = idempotencyKey,
                unitCost = unitCost,
                supplier = supplier,
                invoiceRef = invoiceRef,
                invoicePhotoUrl = invoicePhotoUrl,
                actorId = actorId,
                deviceId = deviceId,
                receivedAtIso = receivedAtIso,
                updateBuyPrice = updateBuyPrice,
            )
        }.getOrNull()
    }

    fun relinkStockMovementPayloadProductId(payloadJson: String, newProductId: Long): String {
        return runCatching {
            val root = JSONObject(payloadJson)
            root.put("product_id", newProductId)
            root.toString()
        }.getOrDefault(payloadJson)
    }

    fun parseStockAdjustmentRpcRequest(event: SyncQueueEventEntity): AndroidStockAdjustmentRpcRequest? {
        return runCatching {
            val json = JSONObject(event.payloadJson)
            val clientAdjustmentId = json.optString(
                "client_adjustment_id",
                json.optString("p_client_adjustment_id", event.entityId),
            )
            val idempotencyKey = json.optString(
                "idempotency_key",
                json.optString("p_idempotency_key", ""),
            )
            val productId = when {
                json.has("product_id") -> json.optLong("product_id", -1L)
                json.has("p_product_id") -> json.optLong("p_product_id", -1L)
                else -> -1L
            }
            val deltaQty = when {
                json.has("delta_qty") -> json.optInt("delta_qty", 0)
                json.has("p_delta_qty") -> json.optInt("p_delta_qty", 0)
                else -> 0
            }
            val reasonCode = json.optString(
                "reason_code",
                json.optString("p_reason_code", ""),
            )
            val notes = json.optString(
                "notes",
                json.optString("p_notes", ""),
            )
            val actorId = json.optString(
                "actor_id",
                json.optString("p_actor_id", event.actorId),
            )
            val actorRole = json.optString(
                "actor_role",
                json.optString("p_actor_role", "CASHIER"),
            )
            val deviceId = json.optString(
                "device_id",
                json.optString("p_device_id", event.deviceId),
            )
            val adjustedAtIso = json.optString(
                "adjusted_at",
                json.optString("p_adjusted_at", ""),
            )

            if (clientAdjustmentId.isBlank() || idempotencyKey.isBlank() || productId <= 0L || deltaQty == 0) {
                return null
            }

            AndroidStockAdjustmentRpcRequest(
                productId = productId,
                deltaQty = deltaQty,
                reasonCode = reasonCode,
                notes = notes,
                clientAdjustmentId = clientAdjustmentId,
                idempotencyKey = idempotencyKey,
                actorId = actorId,
                actorRole = actorRole,
                deviceId = deviceId,
                adjustedAtIso = adjustedAtIso,
            )
        }.getOrNull()
    }

    fun relinkStockAdjustmentPayloadProductId(payloadJson: String, newProductId: Long): String {
        return runCatching {
            val root = JSONObject(payloadJson)
            if (root.has("p_product_id")) {
                root.put("p_product_id", newProductId)
            }
            if (root.has("product_id") || !root.has("p_product_id")) {
                root.put("product_id", newProductId)
            }
            root.toString()
        }.getOrDefault(payloadJson)
    }

    fun parseStockCountRpcRequest(event: SyncQueueEventEntity): StockCountSessionDraft? {
        return runCatching {
            val json = JSONObject(event.payloadJson)
            val sessionName = json.optString("p_session_name", "").trim()
            val createdBy = json.optString("p_created_by", event.actorId).ifBlank { event.actorId }
            val idempotencyKey = json.optString("p_idempotency_key", event.entityId).trim()
            val itemsArray = json.optJSONArray("p_items") ?: return null
            if (sessionName.isBlank() || idempotencyKey.isBlank() || itemsArray.length() == 0) {
                return null
            }

            val items = mutableListOf<StockCountItemDraft>()
            for (i in 0 until itemsArray.length()) {
                val obj = itemsArray.optJSONObject(i) ?: continue
                val productId = obj.optInt("product_id", -1)
                if (productId <= 0) continue
                val productName = obj.optString("product_name", "Product #$productId")
                val systemQty = obj.optInt("system_qty", 0)
                val countedQty = obj.optInt("counted_qty", -1)
                if (countedQty < 0) continue
                val reasonCode = obj.optString("reason_code", StockCountReason.CORRECT.code)
                    .ifBlank { StockCountReason.CORRECT.code }
                val note = obj.optString("note", "")
                val clientItemId = obj.optString("client_item_id", "${event.eventId}-$i")
                items += StockCountItemDraft(
                    productId = productId,
                    productName = productName,
                    systemQty = systemQty,
                    countedQty = countedQty,
                    reasonCode = reasonCode,
                    note = note,
                    clientItemId = clientItemId,
                )
            }

            if (items.isEmpty()) return null
            StockCountSessionDraft(
                sessionName = sessionName,
                createdBy = createdBy,
                items = items,
                idempotencyKey = idempotencyKey,
            )
        }.getOrNull()
    }

    fun parseFinancialEventRpcRequest(event: SyncQueueEventEntity): AndroidFinancialEventRpcRequest? {
        return runCatching {
            val json = JSONObject(event.payloadJson)
            val clientEventId = json.optString("client_event_id", event.entityId).trim()
            val idempotencyKey = json.optString("idempotency_key", "").trim()
            val eventType = json.optString("event_type", "MPESA_SMS_EVENT").ifBlank { "MPESA_SMS_EVENT" }
            val direction = json.optString("direction", "").trim()
            val amount = json.optDouble("amount", -1.0)
            val currency = json.optString("currency", "KES").ifBlank { "KES" }
            val occurredAtIso = json.optString("occurred_at", "").trim()

            if (clientEventId.isBlank() || idempotencyKey.isBlank() ||
                direction !in setOf("INFLOW", "OUTFLOW", "TRANSFER") ||
                !amount.isFinite() || amount <= 0.0 || occurredAtIso.isBlank()
            ) {
                return null
            }

            fun optNonBlank(key: String): String? =
                json.optString(key).takeIf { !json.isNull(key) && it.isNotBlank() && it != "null" }

            fun optDoubleOrNull(key: String): Double? =
                if (json.has(key) && !json.isNull(key)) {
                    json.optDouble(key).takeIf { it.isFinite() && it >= 0.0 }
                } else {
                    null
                }

            AndroidFinancialEventRpcRequest(
                clientEventId = clientEventId,
                idempotencyKey = idempotencyKey,
                eventType = eventType,
                direction = direction,
                amount = amount,
                currency = currency,
                referenceCode = optNonBlank("reference_code"),
                channel = optNonBlank("channel"),
                counterparty = optNonBlank("counterparty"),
                counterpartyAccount = optNonBlank("counterparty_account"),
                transactionCost = optDoubleOrNull("transaction_cost"),
                newBalance = optDoubleOrNull("new_balance"),
                occurredText = optNonBlank("occurred_text"),
                rawEvidence = optNonBlank("raw_evidence"),
                parsedMetadataJson = optNonBlank("parsed_metadata_json"),
                parseStatus = json.optString("parse_status", "VALID_PARSED").ifBlank { "VALID_PARSED" },
                source = json.optString("source", "ANDROID").ifBlank { "ANDROID" },
                actorId = json.optString("actor_id", event.actorId).ifBlank { event.actorId },
                deviceId = json.optString("device_id", event.deviceId).ifBlank { event.deviceId },
                notes = optNonBlank("notes"),
                occurredAtIso = occurredAtIso,
            )
        }.getOrNull()
    }

    fun serializePurchaseRpcRequest(req: AndroidPurchaseRpcRequest): String {
        val itemsArr = JSONArray()
        for (line in req.items) {
            itemsArr.put(
                JSONObject().apply {
                    put("raw_name", line.rawName)
                    put("item_category", line.itemCategory.code)
                    if (line.matchedProductId != null && line.matchedProductId > 0L) {
                        put("matched_product_id", line.matchedProductId)
                    } else {
                        put("matched_product_id", JSONObject.NULL)
                    }
                    if (line.localTempProductId != null && line.localTempProductId < 0L) {
                        put("local_temp_product_id", line.localTempProductId)
                    } else {
                        put("local_temp_product_id", JSONObject.NULL)
                    }
                    put("register_as_new_product", line.registerAsNewProduct)
                    if (line.registerAsNewProduct && line.newProductSellPrice != null) {
                        put("new_product_sell_price", line.newProductSellPrice)
                    } else {
                        put("new_product_sell_price", JSONObject.NULL)
                    }
                    put("new_product_category", line.newProductCategory)
                    put("qty", line.quantity)
                    put("unit_cost", line.unitCost)
                    put("line_total", line.lineTotal)
                }
            )
        }
        return JSONObject().apply {
            put("client_purchase_id", req.clientPurchaseId)
            put("idempotency_key", req.idempotencyKey)
            put("supplier_name", req.supplierName ?: JSONObject.NULL)
            put("invoice_ref", req.invoiceRef ?: JSONObject.NULL)
            put("receipt_date_text", req.receiptDateText ?: JSONObject.NULL)
            put("total_amount", req.totalAmount)
            put("extraction_source", req.extractionSource)
            put("is_human_confirmed", req.isHumanConfirmed)
            put("items", itemsArr)
            put("actor_id", req.actorId)
            put("device_id", req.deviceId)
            put("recorded_at", req.recordedAtIso)
            put("record_financial_outflow", req.recordFinancialOutflow)
        }.toString()
    }

    fun parsePurchaseRpcRequest(event: SyncQueueEventEntity): AndroidPurchaseRpcRequest? {
        return runCatching {
            val json = JSONObject(event.payloadJson)
            val clientPurchaseId = json.optString("client_purchase_id", event.entityId).trim()
            val idempotencyKey = json.optString("idempotency_key", "").trim()
            val isHumanConfirmed = json.optBoolean("is_human_confirmed", false)
            val itemsArr = json.optJSONArray("items") ?: return null
            if (clientPurchaseId.isBlank() || idempotencyKey.isBlank() || !isHumanConfirmed || itemsArr.length() == 0) {
                return null
            }

            fun optNonBlank(key: String): String? =
                json.optString(key).takeIf { !json.isNull(key) && it.isNotBlank() && it != "null" }

            val items = mutableListOf<AndroidPurchaseLineRequest>()
            for (i in 0 until itemsArr.length()) {
                val obj = itemsArr.optJSONObject(i) ?: continue
                val rawName = obj.optString("raw_name", "").trim()
                val qty = obj.optInt("qty", 0)
                val unitCost = obj.optDouble("unit_cost", 0.0)
                if (rawName.length < 2 || qty <= 0 || unitCost.isNaN() || unitCost < 0.0) continue

                val matchedPid = if (obj.has("matched_product_id") && !obj.isNull("matched_product_id")) {
                    obj.optLong("matched_product_id", -1L).takeIf { it > 0L }
                } else {
                    null
                }
                val tempPid = if (obj.has("local_temp_product_id") && !obj.isNull("local_temp_product_id")) {
                    obj.optLong("local_temp_product_id", 0L).takeIf { it < 0L }
                } else {
                    null
                }
                val newSellPrice = if (obj.has("new_product_sell_price") && !obj.isNull("new_product_sell_price")) {
                    obj.optDouble("new_product_sell_price").takeIf { !it.isNaN() && it > 0.0 }
                } else {
                    null
                }
                items += AndroidPurchaseLineRequest(
                    rawName = rawName,
                    itemCategory = PurchaseItemCategory.fromCode(obj.optString("item_category")),
                    matchedProductId = matchedPid,
                    localTempProductId = tempPid,
                    registerAsNewProduct = obj.optBoolean("register_as_new_product", false),
                    newProductSellPrice = newSellPrice,
                    newProductCategory = obj.optString("new_product_category", "General").ifBlank { "General" },
                    quantity = qty,
                    unitCost = unitCost,
                    lineTotal = obj.optDouble("line_total", qty * unitCost).let {
                        if (it.isNaN() || it < 0.0) qty * unitCost else it
                    },
                )
            }
            if (items.isEmpty()) return null

            AndroidPurchaseRpcRequest(
                clientPurchaseId = clientPurchaseId,
                idempotencyKey = idempotencyKey,
                supplierName = optNonBlank("supplier_name"),
                invoiceRef = optNonBlank("invoice_ref"),
                receiptDateText = optNonBlank("receipt_date_text"),
                totalAmount = json.optDouble("total_amount", 0.0).let { if (it.isNaN() || it < 0.0) 0.0 else it },
                extractionSource = json.optString("extraction_source", "MANUAL_ENTRY").ifBlank { "MANUAL_ENTRY" },
                isHumanConfirmed = true,
                items = items,
                actorId = json.optString("actor_id", event.actorId).ifBlank { event.actorId },
                deviceId = json.optString("device_id", event.deviceId).ifBlank { event.deviceId },
                recordedAtIso = json.optString("recorded_at", "").ifBlank { "1970-01-01T00:00:00Z" },
                recordFinancialOutflow = json.optBoolean("record_financial_outflow", true),
            )
        }.getOrNull()
    }

    fun serializeServiceOperationRpcRequest(req: AndroidServiceRpcRequest): String {
        val consumablesArr = JSONArray()
        for (c in req.consumables) {
            consumablesArr.put(
                JSONObject().apply {
                    put("consumable_name", c.consumableName)
                    put("usage_qty", c.usageQty)
                    put("usage_unit", c.usageUnit)
                    if (c.linkedProductId != null && c.linkedProductId > 0L) {
                        put("linked_product_id", c.linkedProductId)
                    } else {
                        put("linked_product_id", JSONObject.NULL)
                    }
                    put("stock_deduct_qty", c.stockDeductQty)
                }
            )
        }
        return JSONObject().apply {
            put("client_service_id", req.clientServiceId)
            put("idempotency_key", req.idempotencyKey)
            put("service_type", req.serviceType.code)
            put("service_label", req.serviceLabel)
            put("quantity_or_duration", req.quantityOrDuration)
            put("unit_label", req.unitLabel)
            put("unit_price", req.unitPrice)
            put("total_revenue", req.totalRevenue)
            put("payment_method", req.paymentMethod)
            put("customer_note", req.customerNote ?: JSONObject.NULL)
            put("consumables", consumablesArr)
            put("actor_id", req.actorId)
            put("device_id", req.deviceId)
            put("performed_at", req.performedAtIso)
            put("discount_amount", req.discountAmount)
        }.toString()
    }

    fun parseServiceOperationRpcRequest(event: SyncQueueEventEntity): AndroidServiceRpcRequest? {
        return runCatching {
            val json = JSONObject(event.payloadJson)
            val clientServiceId = json.optString("client_service_id", event.entityId).trim()
            val idempotencyKey = json.optString("idempotency_key", "").trim()
            val qtyOrDur = json.optInt("quantity_or_duration", 0)
            val unitPrice = json.optDouble("unit_price", 0.0)
            val totalRevenue = json.optDouble("total_revenue", 0.0)
            if (clientServiceId.isBlank() || idempotencyKey.isBlank() || qtyOrDur <= 0 ||
                unitPrice.isNaN() || unitPrice < 0.0 || totalRevenue.isNaN() || totalRevenue < 0.0
            ) {
                return null
            }

            fun optNonBlank(key: String): String? =
                json.optString(key).takeIf { !json.isNull(key) && it.isNotBlank() && it != "null" }

            val serviceType = ServiceOperationType.fromCode(json.optString("service_type"))
            val consumables = mutableListOf<ServiceConsumableRpcLine>()
            val consArr = json.optJSONArray("consumables") ?: JSONArray()
            for (i in 0 until consArr.length()) {
                val obj = consArr.optJSONObject(i) ?: continue
                val name = obj.optString("consumable_name", "").trim()
                if (name.length < 2) continue
                val linkedPid = if (obj.has("linked_product_id") && !obj.isNull("linked_product_id")) {
                    obj.optLong("linked_product_id", -1L).takeIf { it > 0L }
                } else {
                    null
                }
                consumables += ServiceConsumableRpcLine(
                    consumableName = name,
                    usageQty = obj.optDouble("usage_qty", 1.0).let { if (it.isNaN() || it < 0.0) 0.0 else it },
                    usageUnit = obj.optString("usage_unit", "units").ifBlank { "units" },
                    linkedProductId = linkedPid,
                    stockDeductQty = obj.optInt("stock_deduct_qty", 0).coerceAtLeast(0),
                )
            }

            AndroidServiceRpcRequest(
                clientServiceId = clientServiceId,
                idempotencyKey = idempotencyKey,
                serviceType = serviceType,
                serviceLabel = json.optString("service_label", serviceType.label).ifBlank { serviceType.label },
                quantityOrDuration = qtyOrDur,
                unitLabel = json.optString("unit_label", serviceType.unitLabel).ifBlank { serviceType.unitLabel },
                unitPrice = unitPrice,
                totalRevenue = totalRevenue,
                paymentMethod = json.optString("payment_method", "CASH").ifBlank { "CASH" },
                customerNote = optNonBlank("customer_note"),
                consumables = consumables,
                actorId = json.optString("actor_id", event.actorId).ifBlank { event.actorId },
                deviceId = json.optString("device_id", event.deviceId).ifBlank { event.deviceId },
                performedAtIso = json.optString("performed_at", "").ifBlank { "1970-01-01T00:00:00Z" },
                discountAmount = json.optDouble("discount_amount", 0.0).let {
                    if (it.isNaN() || it < 0.0) 0.0 else it
                },
            )
        }.getOrNull()
    }

    fun serializeOrderStatusRpcRequest(req: AndroidOrderStatusRpcRequest): String {
        return JSONObject().apply {
            put("client_update_id", req.clientUpdateId)
            put("idempotency_key", req.idempotencyKey)
            put("order_id", req.orderId)
            put("target_status", req.targetStatus.supabaseValue)
            put("reason", req.reason ?: JSONObject.NULL)
            put("actor_id", req.actorId)
            put("device_id", req.deviceId)
            put("updated_at", req.updatedAtIso)
        }.toString()
    }

    fun parseOrderStatusRpcRequest(event: SyncQueueEventEntity): AndroidOrderStatusRpcRequest? {
        return runCatching {
            val json = JSONObject(event.payloadJson)
            val clientUpdateId = json.optString("client_update_id", event.entityId).trim()
            val idempotencyKey = json.optString("idempotency_key", "").trim()
            val orderId = json.optString("order_id", "").trim()
            val targetRaw = json.optString("target_status", "").trim()
            val targetStatus = StoreOrderStatus.fromRaw(targetRaw)
            if (clientUpdateId.isBlank() || idempotencyKey.isBlank() || orderId.isBlank() ||
                targetStatus == StoreOrderStatus.UNKNOWN || targetStatus == StoreOrderStatus.PENDING
            ) {
                return null
            }
            val reason = json.optString("reason")
                .takeIf { !json.isNull("reason") && it.isNotBlank() && it != "null" }
            if (targetStatus == StoreOrderStatus.CANCELLED && (reason?.length ?: 0) < 3) {
                return null
            }
            AndroidOrderStatusRpcRequest(
                clientUpdateId = clientUpdateId,
                idempotencyKey = idempotencyKey,
                orderId = orderId,
                targetStatus = targetStatus,
                reason = reason,
                actorId = json.optString("actor_id", event.actorId).ifBlank { event.actorId },
                deviceId = json.optString("device_id", event.deviceId).ifBlank { event.deviceId },
                updatedAtIso = json.optString("updated_at", "").ifBlank { "1970-01-01T00:00:00Z" },
            )
        }.getOrNull()
    }

    fun serializeOrderConversionRpcRequest(req: AndroidOrderConversionRpcRequest): String {
        val itemsArr = JSONArray()
        for (line in req.items) {
            itemsArr.put(
                JSONObject().apply {
                    put("product_id", line.productId)
                    put("product_name", line.productName)
                    put("qty", line.quantity)
                    put("unit_price", line.unitPrice)
                    put("line_total", line.lineTotal)
                }
            )
        }
        return JSONObject().apply {
            put("client_conversion_id", req.clientConversionId)
            put("idempotency_key", req.idempotencyKey)
            put("order_id", req.orderId)
            put("items", itemsArr)
            put("payment_method", req.paymentMethod)
            put("actor_id", req.actorId)
            put("device_id", req.deviceId)
            put("converted_at", req.convertedAtIso)
        }.toString()
    }

    fun parseOrderConversionRpcRequest(event: SyncQueueEventEntity): AndroidOrderConversionRpcRequest? {
        return runCatching {
            val json = JSONObject(event.payloadJson)
            val clientConversionId = json.optString("client_conversion_id", event.entityId).trim()
            val idempotencyKey = json.optString("idempotency_key", "").trim()
            val orderId = json.optString("order_id", "").trim()
            val itemsArr = json.optJSONArray("items") ?: return null
            if (clientConversionId.isBlank() || idempotencyKey.isBlank() || orderId.isBlank() || itemsArr.length() == 0) {
                return null
            }

            val items = mutableListOf<ResolvedOrderSaleLine>()
            for (i in 0 until itemsArr.length()) {
                val obj = itemsArr.optJSONObject(i) ?: continue
                val pid = obj.optLong("product_id", -1L)
                val qty = obj.optInt("qty", 0)
                val lineTotal = obj.optDouble("line_total", 0.0)
                if (pid <= 0L || qty <= 0 || lineTotal.isNaN() || lineTotal <= 0.0) continue
                val unitPrice = obj.optDouble("unit_price", lineTotal / qty)
                    .let { if (it.isNaN() || it <= 0.0) lineTotal / qty else it }
                items += ResolvedOrderSaleLine(
                    productId = pid,
                    productName = obj.optString("product_name", "Product #$pid").ifBlank { "Product #$pid" },
                    quantity = qty,
                    unitPrice = unitPrice,
                    lineTotal = lineTotal,
                )
            }
            if (items.isEmpty()) return null

            AndroidOrderConversionRpcRequest(
                clientConversionId = clientConversionId,
                idempotencyKey = idempotencyKey,
                orderId = orderId,
                items = items,
                paymentMethod = json.optString("payment_method", "CASH").ifBlank { "CASH" },
                actorId = json.optString("actor_id", event.actorId).ifBlank { event.actorId },
                deviceId = json.optString("device_id", event.deviceId).ifBlank { event.deviceId },
                convertedAtIso = json.optString("converted_at", "").ifBlank { "1970-01-01T00:00:00Z" },
            )
        }.getOrNull()
    }
}
