package com.emofah.agencies.financial

import com.emofah.agencies.data.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

sealed class SharedTransactionsFetchOutcome {
    data class Success(
        val transactions: List<RemoteFinancialTransaction>,
        val recentSales: List<RemoteRecentSale>,
    ) : SharedTransactionsFetchOutcome()

    data class Failure(val message: String, val isRpcMissing: Boolean = false) :
        SharedTransactionsFetchOutcome()
}

sealed class ClassificationRpcOutcome {
    data class Committed(
        val updatedEvent: RemoteFinancialTransaction?,
        val alreadyClassifiedBySameKey: Boolean = false,
        val saleId: Long? = null,
        val remainingQty: Int? = null,
        val serviceId: String? = null,
        val totalRevenue: Double? = null,
    ) : ClassificationRpcOutcome()

    data class AlreadyClassified(
        val updatedEvent: RemoteFinancialTransaction?,
        val message: String,
    ) : ClassificationRpcOutcome()

    data class Rejected(val code: String, val message: String) : ClassificationRpcOutcome()
    data class AuthRequired(val message: String) : ClassificationRpcOutcome()
    data class RpcMissing(val message: String) : ClassificationRpcOutcome()
    data class NetworkOrTransientError(val message: String) : ClassificationRpcOutcome()
}

class SupabaseTransactionClassificationClient(
    private val supabaseUrl: String = SupabaseConfig.url,
    private val anonKey: String = SupabaseConfig.anonKey,
) {

    suspend fun fetchSharedTransactions(limit: Int = 120): SharedTransactionsFetchOutcome =
        withContext(Dispatchers.IO) {
            if (!supabaseUrl.startsWith("https://") || anonKey.isBlank()) {
                return@withContext SharedTransactionsFetchOutcome.Failure("Supabase not configured.")
            }

            val payload = JSONObject().apply {
                put("p_limit", limit)
                put("p_status_filter", JSONObject.NULL)
            }.toString()

            val response = postRpc("get_financial_transactions", payload)
            if (response.httpStatus !in 200..299) {
                val errJson = runCatching { JSONObject(response.body) }.getOrNull()
                val errCode = errJson?.optString("code", "").orEmpty()
                val rpcMissing = errCode == "PGRST202" || response.httpStatus == 404
                return@withContext SharedTransactionsFetchOutcome.Failure(
                    message = errJson?.optString("message", response.body)
                        ?.ifBlank { "HTTP ${response.httpStatus}" }
                        ?: "HTTP ${response.httpStatus}",
                    isRpcMissing = rpcMissing,
                )
            }

            runCatching {
                val root = JSONObject(response.body)
                val txArr = root.optJSONArray("transactions") ?: JSONArray()
                val txs = mutableListOf<RemoteFinancialTransaction>()
                for (i in 0 until txArr.length()) {
                    val obj = txArr.optJSONObject(i) ?: continue
                    parseRemoteTransaction(obj)?.let { txs.add(it) }
                }

                val salesArr = root.optJSONArray("recent_sales") ?: JSONArray()
                val recentSales = mutableListOf<RemoteRecentSale>()
                for (i in 0 until salesArr.length()) {
                    val obj = salesArr.optJSONObject(i) ?: continue
                    val saleId = obj.optLong("sale_id", -1L)
                    if (saleId <= 0L) continue
                    recentSales += RemoteRecentSale(
                        saleId = saleId,
                        productId = obj.optLong("product_id", -1L),
                        productName = obj.optString("product_name", "Product"),
                        qtySold = obj.optInt("qty_sold", 1),
                        totalPrice = obj.optDouble("total_price", 0.0),
                        saleDateIso = obj.optString("sale_date", ""),
                        alreadyLinkedEventId = obj.optString("already_linked_event_id", "")
                            .takeIf { !obj.isNull("already_linked_event_id") && it.isNotBlank() && it != "null" },
                    )
                }

                SharedTransactionsFetchOutcome.Success(
                    transactions = txs,
                    recentSales = recentSales,
                )
            }.getOrElse { err ->
                SharedTransactionsFetchOutcome.Failure(
                    message = err.message ?: "Failed to parse shared transactions."
                )
            }
        }

    suspend fun classifyAsProduct(
        serverEventId: String,
        idempotencyKey: String,
        productId: Long? = null,
        quantity: Int = 1,
        existingSaleId: Long? = null,
        unitSellPrice: Double? = null,
        paymentMethod: String = "MPESA",
        note: String? = null,
        actorId: String = "android_staff",
        deviceId: String = "android",
    ): ClassificationRpcOutcome = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("p_event_id", serverEventId)
            put("p_classification_idempotency_key", idempotencyKey)
            put("p_product_id", productId ?: JSONObject.NULL)
            put("p_quantity", quantity.coerceAtLeast(1))
            put("p_existing_sale_id", existingSaleId ?: JSONObject.NULL)
            put("p_unit_sell_price", unitSellPrice ?: JSONObject.NULL)
            put("p_payment_method", paymentMethod)
            put("p_note", note?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            put("p_actor_id", actorId)
            put("p_device_id", deviceId)
        }.toString()

        executeClassificationRpc("classify_financial_transaction_as_product", payload)
    }

    suspend fun classifyAsService(
        serverEventId: String,
        idempotencyKey: String,
        serviceType: String,
        serviceLabel: String? = null,
        quantityOrDuration: Int = 1,
        unitLabel: String = "units",
        unitPrice: Double? = null,
        discountAmount: Double = 0.0,
        note: String? = null,
        actorId: String = "android_staff",
        deviceId: String = "android",
    ): ClassificationRpcOutcome = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("p_event_id", serverEventId)
            put("p_classification_idempotency_key", idempotencyKey)
            put("p_service_type", serviceType)
            put("p_service_label", serviceLabel?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            put("p_quantity_or_duration", quantityOrDuration.coerceAtLeast(1))
            put("p_unit_label", unitLabel.ifBlank { "units" })
            put("p_unit_price", unitPrice ?: JSONObject.NULL)
            put("p_discount_amount", discountAmount.coerceAtLeast(0.0))
            put("p_consumables", JSONArray())
            put("p_note", note?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            put("p_actor_id", actorId)
            put("p_device_id", deviceId)
        }.toString()

        executeClassificationRpc("classify_financial_transaction_as_service", payload)
    }

    suspend fun classifyAsNonBusiness(
        serverEventId: String,
        idempotencyKey: String,
        reason: String? = null,
        actorId: String = "android_staff",
        deviceId: String = "android",
    ): ClassificationRpcOutcome = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("p_event_id", serverEventId)
            put("p_classification_idempotency_key", idempotencyKey)
            put("p_reason", reason?.takeIf { it.isNotBlank() } ?: "Marked non-business on Android")
            put("p_actor_id", actorId)
            put("p_device_id", deviceId)
        }.toString()

        executeClassificationRpc("classify_financial_transaction_as_non_business", payload)
    }

    private fun executeClassificationRpc(
        rpcName: String,
        payloadJson: String,
    ): ClassificationRpcOutcome {
        if (!supabaseUrl.startsWith("https://") || anonKey.isBlank()) {
            return ClassificationRpcOutcome.NetworkOrTransientError("Supabase not configured.")
        }
        val response = postRpc(rpcName, payloadJson)
        return parseClassificationResponse(response.httpStatus, response.body, rpcName)
    }

    private data class HttpCallResult(val httpStatus: Int, val body: String)

    private fun postRpc(rpcName: String, payloadJson: String): HttpCallResult {
        val endpoint = "${supabaseUrl.trimEnd('/')}/rest/v1/rpc/$rpcName"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 10000
                doOutput = true
                setRequestProperty("apikey", anonKey)
                setRequestProperty("Authorization", "Bearer ${SupabaseConfig.bearerToken(anonKey)}")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { it.write(payloadJson.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            HttpCallResult(code, body)
        } catch (e: IOException) {
            HttpCallResult(503, e.message ?: "Network unreachable.")
        } catch (e: Exception) {
            HttpCallResult(500, e.message ?: "Unexpected network failure.")
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        fun parseClassificationResponse(
            httpStatus: Int,
            responseBody: String,
            rpcName: String = "",
        ): ClassificationRpcOutcome {
            if (httpStatus in 200..299) {
                return try {
                    val root = JSONObject(responseBody)
                    val status = root.optString("status", "")
                    val eventObj = root.optJSONObject("event")?.let { parseRemoteTransaction(it) }
                    when (status) {
                        "COMMITTED", "DUPLICATE_IGNORED" -> ClassificationRpcOutcome.Committed(
                            updatedEvent = eventObj,
                            alreadyClassifiedBySameKey = status == "DUPLICATE_IGNORED",
                            saleId = root.optLong("sale_id", -1L).takeIf { it > 0L },
                            remainingQty = if (root.has("remaining_qty") && !root.isNull("remaining_qty")) {
                                root.optInt("remaining_qty")
                            } else null,
                            serviceId = root.optString("service_id", "").takeIf { it.isNotBlank() && it != "null" },
                            totalRevenue = if (root.has("total_revenue") && !root.isNull("total_revenue")) {
                                root.optDouble("total_revenue")
                            } else null,
                        )

                        "ALREADY_CLASSIFIED" -> ClassificationRpcOutcome.AlreadyClassified(
                            updatedEvent = eventObj,
                            message = root.optString(
                                "message",
                                "Transaction was already classified on another device.",
                            ),
                        )

                        "INSUFFICIENT_STOCK", "PRODUCT_NOT_FOUND", "SALE_NOT_FOUND",
                        "SALE_ALREADY_LINKED", "INVALID_INPUT", "NOT_FOUND",
                        -> ClassificationRpcOutcome.Rejected(
                            code = status,
                            message = root.optString("message", "Classification rejected ($status)."),
                        )

                        else -> ClassificationRpcOutcome.NetworkOrTransientError(
                            "Unexpected server response ($status)."
                        )
                    }
                } catch (e: Exception) {
                    ClassificationRpcOutcome.NetworkOrTransientError(
                        "Failed to parse classification response: ${e.message}"
                    )
                }
            }

            val errJson = runCatching { JSONObject(responseBody) }.getOrNull()
            val errCode = errJson?.optString("code", "").orEmpty()
            val errMsg = errJson?.optString("message", responseBody).orEmpty()

            if (errCode == "PGRST202" || (httpStatus == 404 && responseBody.contains(rpcName))) {
                return ClassificationRpcOutcome.RpcMissing(
                    "Deploy Supabase migration 20261122_shared_transaction_classification.sql first."
                )
            }
            if (httpStatus == 401 || httpStatus == 403) {
                return ClassificationRpcOutcome.AuthRequired(
                    errMsg.ifBlank { "Sign-in required (HTTP $httpStatus)." }
                )
            }
            if (httpStatus in 400..499 && httpStatus != 408 && httpStatus != 429) {
                return ClassificationRpcOutcome.Rejected(
                    code = errCode.ifBlank { "HTTP_$httpStatus" },
                    message = errMsg.ifBlank { "Server rejected classification (HTTP $httpStatus)." },
                )
            }
            return ClassificationRpcOutcome.NetworkOrTransientError(
                errMsg.ifBlank { "Transient error (HTTP $httpStatus)." }
            )
        }

        fun parseRemoteTransaction(obj: JSONObject): RemoteFinancialTransaction? {
            val id = obj.optString("id", "").trim()
            if (id.isBlank()) return null

            fun optStr(key: String): String? =
                obj.optString(key, "").takeIf { !obj.isNull(key) && it.isNotBlank() && it != "null" }

            fun optDbl(key: String): Double? =
                if (obj.has(key) && !obj.isNull(key)) {
                    obj.optDouble(key).takeIf { it.isFinite() }
                } else null

            fun optLng(key: String): Long? =
                if (obj.has(key) && !obj.isNull(key)) {
                    obj.optLong(key, -1L).takeIf { it > 0L }
                } else null

            fun optIntOrNull(key: String): Int? =
                if (obj.has(key) && !obj.isNull(key)) {
                    obj.optInt(key, -1).takeIf { it >= 0 }
                } else null

            val occurredIso = optStr("occurred_at") ?: optStr("created_at") ?: Instant.now().toString()
            val occurredMs = runCatching { Instant.parse(occurredIso).toEpochMilli() }
                .getOrElse { System.currentTimeMillis() }
            val classifiedAtIso = optStr("classified_at")
            val classifiedMs = classifiedAtIso?.let {
                runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
            }
            val excludesFromBalance = obj.optBoolean("excludes_from_balance", false)

            return RemoteFinancialTransaction(
                serverEventId = id,
                clientEventId = optStr("client_event_id"),
                eventType = optStr("event_type") ?: "MPESA_SMS_EVENT",
                direction = optStr("direction") ?: "INFLOW",
                amount = obj.optDouble("amount", 0.0),
                currency = optStr("currency") ?: "KES",
                classificationStatus = ClassificationState.fromRaw(
                    optStr("classification_status"),
                    excludesFromBalance,
                ),
                referenceCode = optStr("reference_code"),
                channel = optStr("channel"),
                counterparty = optStr("counterparty"),
                counterpartyAccount = optStr("counterparty_account"),
                transactionCost = optDbl("transaction_cost"),
                newBalance = optDbl("new_balance"),
                occurredText = optStr("occurred_text"),
                rawEvidence = optStr("raw_evidence"),
                parseStatus = optStr("parse_status"),
                source = optStr("source") ?: "ANDROID",
                actorId = optStr("actor_id"),
                deviceId = optStr("device_id"),
                notes = optStr("notes"),
                occurredAtIso = occurredIso,
                occurredAtEpochMs = occurredMs,
                linkedSaleId = optLng("linked_sale_id"),
                linkedClientSaleId = optStr("linked_client_sale_id"),
                linkedServiceId = optStr("linked_service_id"),
                linkedClientServiceId = optStr("linked_client_service_id"),
                linkedProductId = optLng("linked_product_id"),
                linkedProductName = optStr("linked_product_name"),
                linkedQuantity = optIntOrNull("linked_quantity"),
                classificationNote = optStr("classification_note"),
                classifiedAtEpochMs = classifiedMs,
                classifiedBy = optStr("classified_by"),
                classifiedDevice = optStr("classified_device"),
                excludesFromBalance = excludesFromBalance,
                serviceTotalRevenue = optDbl("service_total_revenue"),
                saleTotalPrice = optDbl("sale_total_price"),
            )
        }
    }
}
