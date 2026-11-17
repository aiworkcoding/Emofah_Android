package com.emofah.agencies.stock

import com.emofah.agencies.data.SupabaseConfig
import com.emofah.agencies.data.catalog.CatalogueDeltaCalculator
import com.emofah.agencies.data.local.EmofahDatabase
import com.emofah.agencies.data.local.entities.LocalStockProjectionEntity
import com.emofah.agencies.sales.ConnectivityChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Stage 4.1 — Stock Lookup Repository.
 * Combines Room cached stock projections & offline movements with live Supabase product
 * quantity, configured low-stock threshold (`public.settings`), and recent shared `public.sales`
 * movements without introducing a duplicate inventory ledger.
 */
class StockLookupRepository(
    private val database: EmofahDatabase,
    private val connectivityChecker: ConnectivityChecker,
    private val isSupabaseConfigured: Boolean = SupabaseConfig.isConfigured,
    private val supabaseUrl: String = SupabaseConfig.url,
    private val anonKey: String = SupabaseConfig.anonKey,
) {
    private val productDao = database.productDao()
    private val saleDao = database.saleDao()

    fun observeAllStockProjections(): Flow<List<LocalStockProjectionEntity>> =
        productDao.observeAllStockProjections()

    suspend fun fetchConfiguredThresholdOrNull(): Int? = withContext(Dispatchers.IO) {
        if (!isSupabaseConfigured || !connectivityChecker.isOnline()) return@withContext null
        fetchConfiguredLowStockThreshold()
    }

    suspend fun lookupProductStock(
        productId: Long,
        refreshFromServer: Boolean = true,
    ): StockLookupSnapshot? = withContext(Dispatchers.IO) {
        var productEntity = productDao.getProductById(productId) ?: return@withContext null
        var threshold = StockLookupPolicy.DEFAULT_LOW_STOCK_THRESHOLD
        var remoteSales = emptyList<RemoteServerSaleRow>()
        var statusNote: String? = null
        val nowMs = System.currentTimeMillis()

        if (refreshFromServer && productId > 0L && isSupabaseConfigured && connectivityChecker.isOnline()) {
            val remoteProduct = fetchRemoteProductStock(productId)
            if (remoteProduct != null) {
                val updatedServerQty = remoteProduct.first
                val updatedDescription = remoteProduct.second
                val updatedProduct = productEntity.copy(
                    serverStockQty = updatedServerQty,
                    description = updatedDescription ?: productEntity.description,
                    cachedAtEpochMs = nowMs,
                )
                productDao.upsertProducts(listOf(updatedProduct))

                val existingProj = productDao.getStockProjection(productId)
                if (existingProj == null) {
                    productDao.upsertStockProjections(
                        listOf(
                            LocalStockProjectionEntity(
                                productId = productId,
                                serverQty = updatedServerQty,
                                serverUpdatedAtEpochMs = nowMs,
                                pendingDeltaQty = 0,
                                provisionalAvailableQty = updatedServerQty,
                            )
                        )
                    )
                } else {
                    productDao.applyServerStockUpdate(
                        productId = productId,
                        serverQty = updatedServerQty,
                        updatedAtEpochMs = nowMs,
                    )
                }
                productEntity = updatedProduct
            } else {
                statusNote = "Could not reach server; showing cached local stock."
            }

            fetchConfiguredLowStockThreshold()?.let { threshold = it }
            remoteSales = fetchRemoteRecentSales(productId)
        } else if (productId < 0L) {
            statusNote = "Offline product draft — stock is provisional until synced."
        } else if (refreshFromServer && !connectivityChecker.isOnline()) {
            statusNote = "Offline mode — showing cached server stock and local movements."
        }

        val identity = CatalogueDeltaCalculator.toProductIdentity(productEntity)
        val projection = productDao.getStockProjection(productId)
        val serverQty = projection?.serverQty ?: identity.stockQty
        val pendingDelta = projection?.pendingDeltaQty ?: 0
        val provisionalQty = projection?.provisionalAvailableQty ?: identity.stockQty
        val updatedAt = projection?.serverUpdatedAtEpochMs ?: productEntity.cachedAtEpochMs
        val localMovements = saleDao.getRecentMovementsForProduct(productId, limit = 20)
        val cachedPhotoPath = productDao.getReferencePhotosForProduct(productId)
            .firstOrNull { it.isValid && !it.localCachePath.isNullOrBlank() }
            ?.localCachePath

        val movements = StockLookupPolicy.mergeMovements(
            localRows = localMovements,
            remoteSales = remoteSales,
        )

        StockLookupSnapshot(
            product = identity,
            serverQty = serverQty,
            provisionalQty = provisionalQty,
            pendingDeltaQty = pendingDelta,
            hasConflict = projection?.hasConflict == true,
            conflictNote = projection?.conflictNote,
            serverUpdatedAtEpochMs = updatedAt,
            freshnessLabel = StockLookupPolicy.formatFreshnessLabel(
                serverUpdatedAtEpochMs = updatedAt,
                nowEpochMs = nowMs,
                pendingDeltaQty = pendingDelta,
            ),
            lowStockLevel = StockLookupPolicy.evaluateLowStock(provisionalQty, threshold),
            lowStockThreshold = threshold,
            supportedLocation = StockLookupPolicy.extractSupportedLocation(identity.description),
            cachedPhotoPath = cachedPhotoPath,
            movements = movements,
            statusNote = statusNote,
        )
    }

    private fun fetchRemoteProductStock(productId: Long): Pair<Int, String?>? {
        if (!supabaseUrl.startsWith("https://") || anonKey.isBlank()) return null
        return runCatching {
            val endpoint =
                "${supabaseUrl.trimEnd('/')}/rest/v1/products" +
                    "?id=eq.$productId&select=id,qty,description&limit=1"
            val body = httpGetJson(endpoint) ?: return null
            val arr = JSONArray(body)
            if (arr.length() == 0) return null
            val obj = arr.getJSONObject(0)
            val qty = obj.optInt("qty", 0)
            val desc = obj.optString("description").takeIf { it.isNotEmpty() && it != "null" }
            qty to desc
        }.getOrNull()
    }

    private fun fetchRemoteRecentSales(productId: Long): List<RemoteServerSaleRow> {
        if (!supabaseUrl.startsWith("https://") || anonKey.isBlank()) return emptyList()
        return runCatching {
            val endpoint =
                "${supabaseUrl.trimEnd('/')}/rest/v1/sales" +
                    "?product_id=eq.$productId&select=id,qty_sold,total_price,sale_date&order=sale_date.desc&limit=10"
            val body = httpGetJson(endpoint) ?: return emptyList()
            parseRemoteSalesJson(body)
        }.getOrDefault(emptyList())
    }

    private fun fetchConfiguredLowStockThreshold(): Int? {
        if (!supabaseUrl.startsWith("https://") || anonKey.isBlank()) return null
        return runCatching {
            val endpoint =
                "${supabaseUrl.trimEnd('/')}/rest/v1/settings" +
                    "?key=in.(low_stock_threshold,stock_alert_threshold)&select=key,value&limit=2"
            val body = httpGetJson(endpoint) ?: return null
            val arr = JSONArray(body)
            if (arr.length() == 0) return null
            val rawVal = arr.getJSONObject(0).optString("value", "").trim()
            rawVal.toIntOrNull()?.takeIf { it in 1..500 }
        }.getOrNull()
    }

    private fun httpGetJson(endpoint: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 7000
                readTimeout = 8000
                setRequestProperty("apikey", anonKey)
                setRequestProperty("Authorization", "Bearer ${SupabaseConfig.bearerToken(anonKey)}")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        fun parseRemoteSalesJson(rawJson: String): List<RemoteServerSaleRow> {
            val arr = JSONArray(rawJson)
            val result = ArrayList<RemoteServerSaleRow>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optLong("id", -1L)
                val qty = obj.optInt("qty_sold", 0)
                if (id > 0L && qty > 0) {
                    result.add(
                        RemoteServerSaleRow(
                            serverSaleId = id,
                            qtySold = qty,
                            totalPrice = obj.optDouble("total_price", 0.0),
                            saleDateIso = obj.optString("sale_date", ""),
                        )
                    )
                }
            }
            return result
        }
    }
}
