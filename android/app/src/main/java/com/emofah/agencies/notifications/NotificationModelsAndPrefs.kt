package com.emofah.agencies.notifications

import android.content.Context
import com.emofah.agencies.data.sync.SyncPassSummary
import com.emofah.agencies.orders.StoreOrderRecord
import com.emofah.agencies.orders.StoreOrderStatus
import com.emofah.agencies.stock.StockAlertSummary
import com.emofah.agencies.ui.navigation.EmofahDestination

data class NotificationPreferencesState(
    val ordersEnabled: Boolean = true,
    val syncAlertsEnabled: Boolean = true,
    val stockAlertsEnabled: Boolean = true,
    val mpesaInflowAlertsEnabled: Boolean = true,
)

data class NewOrderAlert(
    val newPendingOrderIds: Set<String>,
    val displayNumbers: List<String>,
    val totalPendingCount: Int,
)

data class SyncAttentionAlert(
    val signature: String,
    val conflictCount: Int,
    val authBlockedCount: Int,
)

data class StockAttentionAlert(
    val activeAlertKeys: Set<String>,
    val newlyAlertedCount: Int,
    val outOfStockCount: Int,
    val lowStockCount: Int,
    val sampleProductName: String?,
)

/**
 * Stage 7.3 — Pure JVM Notification Alert Evaluator & Route Guard.
 * Deduplicates notifications deterministically from real operational state
 * without fabricating orders, conflicts, or stock alerts.
 */
object NotificationEvaluator {

    fun findNewPendingOrders(
        orders: List<StoreOrderRecord>,
        previouslySeenOrderIds: Set<String>,
    ): NewOrderAlert? {
        val pendingOrders = orders.filter { it.status == StoreOrderStatus.PENDING }
        if (pendingOrders.isEmpty()) return null

        val unseen = pendingOrders.filterNot { it.id in previouslySeenOrderIds }
        if (unseen.isEmpty()) return null

        return NewOrderAlert(
            newPendingOrderIds = unseen.map { it.id }.toSet(),
            displayNumbers = unseen.map { it.displayNumber }.take(3),
            totalPendingCount = pendingOrders.size,
        )
    }

    fun evaluateSyncAttention(
        summary: SyncPassSummary,
        lastSignature: String?,
    ): Pair<SyncAttentionAlert?, String?> {
        if (summary.conflictCount <= 0 && summary.authBlockedCount <= 0) {
            return null to null
        }
        val signature = "c=${summary.conflictCount}:a=${summary.authBlockedCount}"
        if (signature == lastSignature) {
            return null to signature
        }
        val alert = SyncAttentionAlert(
            signature = signature,
            conflictCount = summary.conflictCount,
            authBlockedCount = summary.authBlockedCount,
        )
        return alert to signature
    }

    fun evaluateStockAlertDiff(
        alertSummary: StockAlertSummary,
        previouslyAlertedKeys: Set<String>,
    ): Pair<StockAttentionAlert?, Set<String>> {
        val outKeys = alertSummary.outOfStockItems.map { "OUT:${it.product.id}" }
        val lowKeys = alertSummary.lowStockItems.map { "LOW:${it.product.id}" }
        val activeKeys = (outKeys + lowKeys).toSet()

        if (activeKeys.isEmpty()) {
            return null to emptySet()
        }

        val newlyAdded = activeKeys - previouslyAlertedKeys
        if (newlyAdded.isEmpty()) {
            return null to activeKeys
        }

        val sampleName = alertSummary.urgentItems.firstOrNull { item ->
            val key = if (item.availableQty <= 0) "OUT:${item.product.id}" else "LOW:${item.product.id}"
            key in newlyAdded
        }?.product?.name ?: alertSummary.urgentItems.firstOrNull()?.product?.name

        val alert = StockAttentionAlert(
            activeAlertKeys = activeKeys,
            newlyAlertedCount = newlyAdded.size,
            outOfStockCount = alertSummary.outOfStockItems.size,
            lowStockCount = alertSummary.lowStockItems.size,
            sampleProductName = sampleName,
        )
        return alert to activeKeys
    }

    fun resolveDestinationRoute(rawRoute: String?): EmofahDestination? {
        val clean = rawRoute?.trim()?.lowercase() ?: return null
        return EmofahDestination.entries.firstOrNull { it.route == clean }
    }
}

/**
 * Stage 7.3 — Persists user notification toggles and deduplication markers.
 */
class NotificationPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readState(): NotificationPreferencesState {
        return NotificationPreferencesState(
            ordersEnabled = prefs.getBoolean(KEY_ORDERS_ENABLED, true),
            syncAlertsEnabled = prefs.getBoolean(KEY_SYNC_ENABLED, true),
            stockAlertsEnabled = prefs.getBoolean(KEY_STOCK_ENABLED, true),
            mpesaInflowAlertsEnabled = prefs.getBoolean(KEY_MPESA_INFLOW_ENABLED, true),
        )
    }

    fun setOrdersEnabled(enabled: Boolean): NotificationPreferencesState {
        prefs.edit().putBoolean(KEY_ORDERS_ENABLED, enabled).apply()
        return readState()
    }

    fun setSyncAlertsEnabled(enabled: Boolean): NotificationPreferencesState {
        prefs.edit().putBoolean(KEY_SYNC_ENABLED, enabled).apply()
        return readState()
    }

    fun setStockAlertsEnabled(enabled: Boolean): NotificationPreferencesState {
        prefs.edit().putBoolean(KEY_STOCK_ENABLED, enabled).apply()
        return readState()
    }

    fun setMpesaInflowAlertsEnabled(enabled: Boolean): NotificationPreferencesState {
        prefs.edit().putBoolean(KEY_MPESA_INFLOW_ENABLED, enabled).apply()
        return readState()
    }

    fun isOrdersBaselineInitialized(): Boolean {
        return prefs.getBoolean(KEY_ORDERS_BASELINE_INIT, false)
    }

    fun readSeenOrderIds(): Set<String> {
        return prefs.getStringSet(KEY_SEEN_ORDER_IDS, emptySet())?.toSet() ?: emptySet()
    }

    fun saveSeenOrderIds(orderIds: Set<String>) {
        prefs.edit()
            .putBoolean(KEY_ORDERS_BASELINE_INIT, true)
            .putStringSet(KEY_SEEN_ORDER_IDS, orderIds.take(200).toSet())
            .apply()
    }

    fun readLastSyncSignature(): String? {
        return prefs.getString(KEY_LAST_SYNC_SIG, null)
    }

    fun saveLastSyncSignature(signature: String?) {
        prefs.edit().apply {
            if (signature == null) remove(KEY_LAST_SYNC_SIG) else putString(KEY_LAST_SYNC_SIG, signature)
        }.apply()
    }

    fun isStockBaselineInitialized(): Boolean {
        return prefs.getBoolean(KEY_STOCK_BASELINE_INIT, false)
    }

    fun readAlertedStockKeys(): Set<String> {
        return prefs.getStringSet(KEY_ALERTED_STOCK_KEYS, emptySet())?.toSet() ?: emptySet()
    }

    fun saveAlertedStockKeys(keys: Set<String>) {
        prefs.edit()
            .putBoolean(KEY_STOCK_BASELINE_INIT, true)
            .putStringSet(KEY_ALERTED_STOCK_KEYS, keys.take(300).toSet())
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "emofah_notifications"
        private const val KEY_ORDERS_ENABLED = "orders_enabled"
        private const val KEY_SYNC_ENABLED = "sync_alerts_enabled"
        private const val KEY_STOCK_ENABLED = "stock_alerts_enabled"
        private const val KEY_MPESA_INFLOW_ENABLED = "mpesa_inflow_alerts_enabled"
        private const val KEY_ORDERS_BASELINE_INIT = "orders_baseline_init"
        private const val KEY_SEEN_ORDER_IDS = "seen_order_ids"
        private const val KEY_LAST_SYNC_SIG = "last_sync_signature"
        private const val KEY_STOCK_BASELINE_INIT = "stock_baseline_init"
        private const val KEY_ALERTED_STOCK_KEYS = "alerted_stock_keys"
    }
}
