package com.emofah.agencies.orders

import android.content.Context
import com.emofah.agencies.data.SupabaseConfig
import com.emofah.agencies.data.catalog.ProductIdentity
import com.emofah.agencies.sales.ConnectivityChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Stage 6.1 + Stage 6.2 — Online Order Retrieval & Local Cache Repository.
 * Queries shared `public.orders` via Supabase REST API (`select=*&order=created_at.desc&limit=100`),
 * caches the last valid JSON payload locally in SharedPreferences for offline viewing,
 * preserves pending offline status updates cleanly via overlays until synced,
 * and never invents sample/placeholder orders when empty.
 */
class SupabaseOrderRepository(
    context: Context,
    private val connectivityChecker: ConnectivityChecker,
    private val supabaseUrl: String = SupabaseConfig.url,
    private val anonKey: String = SupabaseConfig.anonKey,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun fetchOrders(
        catalogueProducts: List<ProductIdentity>,
        forceRefresh: Boolean = true,
    ): OrderFeedSnapshot = withContext(Dispatchers.IO) {
        val catalogueById = catalogueProducts.associateBy { it.id }

        if (!forceRefresh) {
            val cached = readCachedSnapshot(catalogueById, reason = null)
            if (cached.orders.isNotEmpty() || cached.lastFetchedEpochMs != null) {
                return@withContext cached
            }
        }

        if (!SupabaseConfig.isConfigured || supabaseUrl.isBlank() || anonKey.isBlank()) {
            return@withContext readCachedSnapshot(
                catalogueById = catalogueById,
                reason = "Supabase credentials not configured; showing cached orders.",
            )
        }

        if (!connectivityChecker.isOnline()) {
            return@withContext readCachedSnapshot(
                catalogueById = catalogueById,
                reason = "Offline mode — showing last cached storefront orders.",
            )
        }

        val endpoint = "${supabaseUrl.trimEnd('/')}/rest/v1/orders?select=*&order=created_at.desc&limit=100"
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 12_000
                setRequestProperty("apikey", anonKey)
                setRequestProperty("Authorization", "Bearer ${SupabaseConfig.bearerToken(anonKey)}")
                setRequestProperty("Accept", "application/json")
            }

            val statusCode = conn.responseCode
            if (statusCode in 200..299) {
                val rawBody = conn.inputStream.bufferedReader().use { it.readText() }
                val nowMs = System.currentTimeMillis()
                prefs.edit()
                    .putString(KEY_RAW_JSON, rawBody)
                    .putLong(KEY_LAST_FETCHED_MS, nowMs)
                    .apply()
                val orders = StoreOrderParser.parseOrdersJson(
                    rawJson = rawBody,
                    catalogueById = catalogueById,
                    pendingOverlays = readPendingOverlays(),
                    convertedOrderIds = readConvertedOrderIds(),
                )
                val msg = if (orders.isEmpty()) {
                    "Connected to Supabase · No storefront orders in public.orders yet."
                } else {
                    "Synced ${orders.size} online order(s) from Supabase."
                }
                return@withContext OrderFeedSnapshot(
                    orders = orders,
                    lastFetchedEpochMs = nowMs,
                    isFromCache = false,
                    statusMessage = msg,
                )
            }

            return@withContext readCachedSnapshot(
                catalogueById = catalogueById,
                reason = "Could not load orders (HTTP $statusCode); showing cached orders.",
            )
        } catch (e: Exception) {
            return@withContext readCachedSnapshot(
                catalogueById = catalogueById,
                reason = "Network unavailable (${e.message ?: "error"}); showing cached orders.",
            )
        } finally {
            conn?.disconnect()
        }
    }

    fun readCachedSnapshotImmediate(
        catalogueProducts: List<ProductIdentity>,
        reason: String? = null,
    ): OrderFeedSnapshot {
        return readCachedSnapshot(
            catalogueById = catalogueProducts.associateBy { it.id },
            reason = reason,
        )
    }

    fun applyLocalOrderStatusChange(
        orderId: String,
        newRawStatus: String,
        markPendingOverlay: Boolean,
        updatedAtEpochMs: Long = System.currentTimeMillis(),
    ) {
        val currentRaw = prefs.getString(KEY_RAW_JSON, null)
        val updatedRaw = StoreOrderParser.updateOrderStatusInRawJson(currentRaw, orderId, newRawStatus)
        val overlays = readPendingOverlays().toMutableMap()
        if (markPendingOverlay) {
            overlays[orderId] = PendingOrderStatusOverlay(
                orderId = orderId,
                targetRawStatus = newRawStatus,
                updatedAtEpochMs = updatedAtEpochMs,
            )
        } else {
            overlays.remove(orderId)
        }
        prefs.edit()
            .apply {
                if (updatedRaw != null) putString(KEY_RAW_JSON, updatedRaw)
                putString(KEY_PENDING_OVERLAYS, serializePendingOverlays(overlays))
            }
            .apply()
    }

    fun clearPendingStatusOverlay(
        orderId: String,
        confirmedRawStatus: String? = null,
    ) {
        val overlays = readPendingOverlays().toMutableMap()
        overlays.remove(orderId)
        val currentRaw = prefs.getString(KEY_RAW_JSON, null)
        val updatedRaw = if (confirmedRawStatus != null) {
            StoreOrderParser.updateOrderStatusInRawJson(currentRaw, orderId, confirmedRawStatus)
        } else {
            currentRaw
        }
        prefs.edit()
            .apply {
                if (updatedRaw != null) putString(KEY_RAW_JSON, updatedRaw)
                putString(KEY_PENDING_OVERLAYS, serializePendingOverlays(overlays))
            }
            .apply()
    }

    fun isOrderConverted(orderId: String): Boolean {
        return orderId in readConvertedOrderIds()
    }

    fun markOrderConverted(
        orderId: String,
        markPendingOverlay: Boolean,
        updatedAtEpochMs: Long = System.currentTimeMillis(),
    ) {
        val converted = readConvertedOrderIds().toMutableSet()
        converted.add(orderId)
        prefs.edit()
            .putString(KEY_CONVERTED_ORDER_IDS, org.json.JSONArray(converted.toList()).toString())
            .apply()
        applyLocalOrderStatusChange(
            orderId = orderId,
            newRawStatus = StoreOrderStatus.FULFILLED.supabaseValue,
            markPendingOverlay = markPendingOverlay,
            updatedAtEpochMs = updatedAtEpochMs,
        )
    }

    fun unmarkOrderConverted(orderId: String) {
        val converted = readConvertedOrderIds().toMutableSet()
        if (converted.remove(orderId)) {
            prefs.edit()
                .putString(KEY_CONVERTED_ORDER_IDS, org.json.JSONArray(converted.toList()).toString())
                .apply()
        }
        clearPendingStatusOverlay(orderId, confirmedRawStatus = null)
    }

    private fun readCachedSnapshot(
        catalogueById: Map<Long, ProductIdentity>,
        reason: String?,
    ): OrderFeedSnapshot {
        val raw = prefs.getString(KEY_RAW_JSON, null)
        val fetchedMs = prefs.getLong(KEY_LAST_FETCHED_MS, 0L).takeIf { it > 0L }
        val orders = raw?.let {
            StoreOrderParser.parseOrdersJson(
                rawJson = it,
                catalogueById = catalogueById,
                pendingOverlays = readPendingOverlays(),
                convertedOrderIds = readConvertedOrderIds(),
            )
        }.orEmpty()
        val msg = reason ?: when {
            fetchedMs == null -> "Tap Refresh to check storefront orders."
            orders.isEmpty() -> "No cached orders from last sync."
            else -> "Showing ${orders.size} cached order(s)."
        }
        return OrderFeedSnapshot(
            orders = orders,
            lastFetchedEpochMs = fetchedMs,
            isFromCache = true,
            statusMessage = msg,
        )
    }

    private fun readConvertedOrderIds(): Set<String> {
        val raw = prefs.getString(KEY_CONVERTED_ORDER_IDS, null) ?: return emptySet()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            val set = mutableSetOf<String>()
            for (i in 0 until arr.length()) {
                val oid = arr.optString(i, "").trim()
                if (oid.isNotEmpty()) set.add(oid)
            }
            set
        }.getOrDefault(emptySet())
    }

    private fun readPendingOverlays(): Map<String, PendingOrderStatusOverlay> {
        val raw = prefs.getString(KEY_PENDING_OVERLAYS, null) ?: return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            val result = mutableMapOf<String, PendingOrderStatusOverlay>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val oid = keys.next()
                val obj = root.optJSONObject(oid) ?: continue
                val status = obj.optString("status", "").trim().takeIf { it.isNotEmpty() } ?: continue
                val ts = obj.optLong("updated_at_ms", 0L)
                result[oid] = PendingOrderStatusOverlay(
                    orderId = oid,
                    targetRawStatus = status,
                    updatedAtEpochMs = ts,
                )
            }
            result
        }.getOrDefault(emptyMap())
    }

    private fun serializePendingOverlays(overlays: Map<String, PendingOrderStatusOverlay>): String {
        val root = JSONObject()
        for ((oid, overlay) in overlays) {
            root.put(
                oid,
                JSONObject().apply {
                    put("status", overlay.targetRawStatus)
                    put("updated_at_ms", overlay.updatedAtEpochMs)
                }
            )
        }
        return root.toString()
    }

    companion object {
        private const val PREFS_NAME = "emofah_orders_cache"
        private const val KEY_RAW_JSON = "orders_raw_json"
        private const val KEY_LAST_FETCHED_MS = "orders_last_fetched_epoch_ms"
        private const val KEY_PENDING_OVERLAYS = "pending_order_status_overlays_json"
        private const val KEY_CONVERTED_ORDER_IDS = "converted_order_ids_json"
    }
}
