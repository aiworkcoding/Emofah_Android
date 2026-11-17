package com.emofah.agencies.purchases

import com.emofah.agencies.data.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

interface PurchaseSubmissionGateway {
    suspend fun submitPurchase(request: AndroidPurchaseRpcRequest): PurchaseRpcOutcome
}

/**
 * Section 2 (Part 2):
 * Calls `POST /rest/v1/rpc/record_android_purchase` via HttpURLConnection to submit
 * human-verified purchase receipts, register new resale products, and update shared stock.
 */
class SupabasePurchaseClient(
    private val supabaseUrl: String = SupabaseConfig.url,
    private val anonKey: String = SupabaseConfig.anonKey,
) : PurchaseSubmissionGateway {

    override suspend fun submitPurchase(request: AndroidPurchaseRpcRequest): PurchaseRpcOutcome =
        withContext(Dispatchers.IO) {
            if (!SupabaseConfig.isConfigured || supabaseUrl.isBlank() || anonKey.isBlank()) {
                return@withContext PurchaseRpcOutcome.NetworkOrTransientError(
                    "Supabase credentials are not configured yet."
                )
            }

            val endpoint = "${supabaseUrl.trimEnd('/')}/rest/v1/rpc/record_android_purchase"
            val payload = buildRpcPayloadJson(request)
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 20_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("apikey", anonKey)
                    setRequestProperty("Authorization", "Bearer ${SupabaseConfig.bearerToken(anonKey)}")
                }

                conn.outputStream.use { out ->
                    out.write(payload.toByteArray(Charsets.UTF_8))
                }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                parseRpcResponse(code, body)
            } catch (e: IOException) {
                PurchaseRpcOutcome.NetworkOrTransientError(
                    e.message ?: "Network unreachable while submitting purchase."
                )
            } catch (e: Exception) {
                PurchaseRpcOutcome.NetworkOrTransientError(
                    e.message ?: "Unexpected error submitting purchase."
                )
            } finally {
                conn?.disconnect()
            }
        }

    companion object {
        internal fun buildRpcPayloadJson(req: AndroidPurchaseRpcRequest): String {
            val itemsArray = JSONArray()
            for (line in req.items) {
                itemsArray.put(
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
                        }
                        put("register_as_new_product", line.registerAsNewProduct)
                        if (line.registerAsNewProduct && line.newProductSellPrice != null) {
                            put("new_product_sell_price", line.newProductSellPrice)
                        }
                        put("new_product_category", line.newProductCategory)
                        put("qty", line.quantity)
                        put("unit_cost", line.unitCost)
                        put("line_total", line.lineTotal)
                    }
                )
            }

            return JSONObject().apply {
                put("p_client_purchase_id", req.clientPurchaseId)
                put("p_idempotency_key", req.idempotencyKey)
                put("p_supplier_name", req.supplierName ?: JSONObject.NULL)
                put("p_invoice_ref", req.invoiceRef ?: JSONObject.NULL)
                put("p_receipt_date_text", req.receiptDateText ?: JSONObject.NULL)
                put("p_total_amount", req.totalAmount)
                put("p_extraction_source", req.extractionSource)
                put("p_is_human_confirmed", req.isHumanConfirmed)
                put("p_items", itemsArray)
                put("p_actor_id", req.actorId)
                put("p_device_id", req.deviceId)
                put("p_recorded_at", req.recordedAtIso)
                put("p_record_financial_outflow", req.recordFinancialOutflow)
            }.toString()
        }

        internal fun parseRpcResponse(statusCode: Int, rawBody: String): PurchaseRpcOutcome {
            if (statusCode == 401 || statusCode == 403) {
                return PurchaseRpcOutcome.AuthRequired("Authentication required to submit purchase (HTTP $statusCode).")
            }
            if (statusCode == 404 || rawBody.contains("PGRST202")) {
                return PurchaseRpcOutcome.RpcMissing(
                    "Supabase RPC record_android_purchase is not deployed yet; queued locally."
                )
            }
            if (statusCode >= 500) {
                return PurchaseRpcOutcome.NetworkOrTransientError("Server unavailable (HTTP $statusCode).")
            }
            if (statusCode !in 200..299) {
                val msg = runCatching { JSONObject(rawBody).optString("message") }
                    .getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP $statusCode"
                return PurchaseRpcOutcome.Rejected("HTTP_$statusCode", msg)
            }

            return runCatching {
                val json = JSONObject(rawBody)
                val status = json.optString("status", "").uppercase()
                when (status) {
                    "COMMITTED", "DUPLICATE_IGNORED" -> {
                        val serverId = json.optString("server_purchase_id", "")
                        val totalAmount = json.optDouble("total_amount", 0.0)
                        val alreadyCommitted = json.optBoolean("already_committed", status == "DUPLICATE_IGNORED")
                        val resolvedArr = json.optJSONArray("resolved_products") ?: JSONArray()
                        val resolvedList = mutableListOf<ResolvedPurchaseProductAck>()
                        for (i in 0 until resolvedArr.length()) {
                            val obj = resolvedArr.optJSONObject(i) ?: continue
                            val serverPid = if (obj.has("product_id") && !obj.isNull("product_id")) {
                                obj.optLong("product_id", -1L).takeIf { it > 0L }
                            } else {
                                null
                            }
                            val localTempPid = if (obj.has("local_temp_product_id") && !obj.isNull("local_temp_product_id")) {
                                obj.optLong("local_temp_product_id", 0L).takeIf { it < 0L }
                            } else {
                                null
                            }
                            val serverQtyAfter = if (obj.has("server_qty_after") && !obj.isNull("server_qty_after")) {
                                obj.optInt("server_qty_after")
                            } else {
                                null
                            }
                            resolvedList += ResolvedPurchaseProductAck(
                                lineIndex = obj.optInt("line_index", i + 1),
                                itemCategory = PurchaseItemCategory.fromCode(obj.optString("item_category")),
                                localTempProductId = localTempPid,
                                serverProductId = serverPid,
                                productName = obj.optString("product_name", ""),
                                category = obj.optString("category", "General"),
                                sellPrice = obj.optDouble("sell_price", 0.0),
                                buyPrice = obj.optDouble("buy_price", 0.0),
                                qty = obj.optInt("qty", 1),
                                serverQtyAfter = serverQtyAfter,
                                registeredNewProduct = obj.optBoolean("registered_new_product", false),
                            )
                        }
                        PurchaseRpcOutcome.CommittedOnline(
                            serverPurchaseId = serverId,
                            totalAmount = totalAmount,
                            alreadyCommitted = alreadyCommitted,
                            resolvedProducts = resolvedList,
                        )
                    }

                    "INVALID_INPUT", "PRODUCT_NOT_FOUND" -> {
                        PurchaseRpcOutcome.Rejected(
                            code = status,
                            message = json.optString("message", "Purchase rejected by backend."),
                        )
                    }

                    else -> PurchaseRpcOutcome.NetworkOrTransientError("Unexpected RPC status: $status")
                }
            }.getOrElse {
                PurchaseRpcOutcome.NetworkOrTransientError("Invalid JSON response from record_android_purchase.")
            }
        }
    }
}
