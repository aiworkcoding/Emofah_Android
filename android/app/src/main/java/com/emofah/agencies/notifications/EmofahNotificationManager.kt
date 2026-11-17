package com.emofah.agencies.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.emofah.agencies.MainActivity
import com.emofah.agencies.R
import com.emofah.agencies.data.sync.SyncPassSummary
import com.emofah.agencies.orders.StoreOrderRecord
import com.emofah.agencies.stock.StockAlertSummary
import com.emofah.agencies.ui.navigation.EmofahDestination

/**
 * Stage 7.3 — Android Notification Channels & Operational Alert Dispatcher.
 * Creates channels cleanly on Android 8+ (minSdk=26), respects Android 13+
 * POST_NOTIFICATIONS runtime permission, and deep-links taps to Orders/Activity/Stock.
 */
object EmofahNotificationManager {

    const val EXTRA_TARGET_ROUTE = "emofah_target_route"

    const val CHANNEL_ORDERS = "emofah_orders_channel"
    const val CHANNEL_SYNC_ALERTS = "emofah_sync_alerts_channel"
    const val CHANNEL_STOCK_ALERTS = "emofah_stock_alerts_channel"

    private const val NOTIFICATION_ID_ORDERS = 7301
    private const val NOTIFICATION_ID_SYNC = 7302
    private const val NOTIFICATION_ID_STOCK = 7303

    fun requiresRuntimePermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    fun hasPostNotificationsPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun areSystemNotificationsEnabled(context: Context): Boolean {
        return hasPostNotificationsPermission(context) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun ensureChannelsCreated(context: Context) {
        val manager = context.applicationContext.getSystemService(NotificationManager::class.java)
            ?: return

        val ordersChannel = NotificationChannel(
            CHANNEL_ORDERS,
            "Online Orders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Alerts when new storefront orders arrive for fulfillment."
        }

        val syncChannel = NotificationChannel(
            CHANNEL_SYNC_ALERTS,
            "Sync & Conflict Alerts",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Alerts when offline records hit sync conflicts or require sign-in."
        }

        val stockChannel = NotificationChannel(
            CHANNEL_STOCK_ALERTS,
            "Stock Alerts",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Alerts when active products run low or out of stock."
        }

        manager.createNotificationChannels(listOf(ordersChannel, syncChannel, stockChannel))
    }

    fun notifyNewOrdersIfNeeded(
        context: Context,
        orders: List<StoreOrderRecord>,
    ): Boolean {
        val prefs = NotificationPrefs(context)
        val allOrderIds = orders.map { it.id }.toSet()

        if (!prefs.isOrdersBaselineInitialized()) {
            prefs.saveSeenOrderIds(allOrderIds)
            return false
        }

        val seen = prefs.readSeenOrderIds()
        val alert = NotificationEvaluator.findNewPendingOrders(orders, seen)
        prefs.saveSeenOrderIds(seen + allOrderIds)

        if (alert == null || !prefs.readState().ordersEnabled) return false

        val count = alert.newPendingOrderIds.size
        val title = if (count == 1) {
            "New Online Order ${alert.displayNumbers.firstOrNull().orEmpty()}"
        } else {
            "$count New Online Orders"
        }
        val body = buildString {
            append(alert.displayNumbers.joinToString(", "))
            if (count > alert.displayNumbers.size) {
                append(" +${count - alert.displayNumbers.size} more")
            }
            append(" · ${alert.totalPendingCount} pending total")
        }

        return postNotification(
            context = context,
            channelId = CHANNEL_ORDERS,
            notificationId = NOTIFICATION_ID_ORDERS,
            title = title.trim(),
            body = body,
            targetDestination = EmofahDestination.Orders,
            priority = NotificationCompat.PRIORITY_HIGH,
        )
    }

    fun notifySyncAttentionIfNeeded(
        context: Context,
        summary: SyncPassSummary,
    ): Boolean {
        val prefs = NotificationPrefs(context)
        val (alert, nextSignature) = NotificationEvaluator.evaluateSyncAttention(
            summary = summary,
            lastSignature = prefs.readLastSyncSignature(),
        )
        prefs.saveLastSyncSignature(nextSignature)

        if (alert == null || !prefs.readState().syncAlertsEnabled) return false

        val title = when {
            alert.conflictCount > 0 && alert.authBlockedCount > 0 ->
                "Sync Attention: ${alert.conflictCount} Conflict(s) & Sign-In Hold"
            alert.conflictCount > 0 ->
                "${alert.conflictCount} Sync Conflict(s) Need Review"
            else ->
                "Sync Paused · Sign-In Required (${alert.authBlockedCount})"
        }
        val body = "Open Activity & Sync Queue to inspect or resolve affected records."

        return postNotification(
            context = context,
            channelId = CHANNEL_SYNC_ALERTS,
            notificationId = NOTIFICATION_ID_SYNC,
            title = title,
            body = body,
            targetDestination = EmofahDestination.Activity,
            priority = NotificationCompat.PRIORITY_DEFAULT,
        )
    }

    fun notifyStockAlertsIfNeeded(
        context: Context,
        alertSummary: StockAlertSummary,
    ): Boolean {
        val prefs = NotificationPrefs(context)
        val prevKeys = prefs.readAlertedStockKeys()
        val (alert, nextKeys) = NotificationEvaluator.evaluateStockAlertDiff(
            alertSummary = alertSummary,
            previouslyAlertedKeys = prevKeys,
        )
        val initialized = prefs.isStockBaselineInitialized()
        prefs.saveAlertedStockKeys(nextKeys)

        if (!initialized || alert == null || !prefs.readState().stockAlertsEnabled) return false

        val title = if (alert.outOfStockCount > 0) {
            "Stock Alert · ${alert.outOfStockCount} Out of Stock, ${alert.lowStockCount} Low"
        } else {
            "Low Stock Alert · ${alert.lowStockCount} Item(s)"
        }
        val body = buildString {
            alert.sampleProductName?.let { append("Includes $it · ") }
            append("Tap to inspect Stock & Restock.")
        }

        return postNotification(
            context = context,
            channelId = CHANNEL_STOCK_ALERTS,
            notificationId = NOTIFICATION_ID_STOCK,
            title = title,
            body = body,
            targetDestination = EmofahDestination.Stock,
            priority = NotificationCompat.PRIORITY_LOW,
        )
    }

    private fun postNotification(
        context: Context,
        channelId: String,
        notificationId: Int,
        title: String,
        body: String,
        targetDestination: EmofahDestination,
        priority: Int,
    ): Boolean {
        if (!areSystemNotificationsEnabled(context)) return false
        ensureChannelsCreated(context)

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_TARGET_ROUTE, targetDestination.route)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_emofah_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(priority)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        return dispatchCompatNotify(context, notificationId, notification)
    }

    @SuppressLint("MissingPermission")
    private fun dispatchCompatNotify(
        context: Context,
        notificationId: Int,
        notification: android.app.Notification,
    ): Boolean {
        return try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }
}
