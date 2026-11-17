package com.emofah.agencies.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.emofah.agencies.EmofahApplication
import com.emofah.agencies.audit.AuditSyncRepository
import com.emofah.agencies.data.catalog.CatalogueDeltaCalculator
import com.emofah.agencies.data.catalog.SupabaseProductRepository
import com.emofah.agencies.data.local.EmofahDatabase
import com.emofah.agencies.notifications.EmofahNotificationManager
import com.emofah.agencies.orders.SupabaseOrderRepository
import com.emofah.agencies.sales.AndroidConnectivityChecker
import com.emofah.agencies.sales.DeviceIdentityProvider
import com.emofah.agencies.stock.StockAlertPolicy
import com.emofah.agencies.stock.StockLookupPolicy

enum class WorkerAction {
    SUCCESS,
    RETRY,
    FAILURE_AUTH_HOLD,
    FAILURE_MAX_ATTEMPTS,
}

/**
 * Pure mapping logic between SyncPassSummary + runAttemptCount and WorkManager Result
 * so background retry bounds and auth-expiry pauses are fully unit-tested on JVM.
 */
object SyncWorkerOutcomeMapper {
    const val MAX_WORKMANAGER_ATTEMPTS = 6

    fun decideAction(
        summary: SyncPassSummary,
        runAttemptCount: Int,
        maxAttempts: Int = MAX_WORKMANAGER_ATTEMPTS,
    ): WorkerAction {
        if (summary.authBlockedCount > 0) {
            // Stop spinning WorkManager retries until user re-authenticates
            return WorkerAction.FAILURE_AUTH_HOLD
        }
        if (summary.skippedOffline || summary.retryableCount > 0) {
            return if (runAttemptCount < maxAttempts) {
                WorkerAction.RETRY
            } else {
                WorkerAction.FAILURE_MAX_ATTEMPTS
            }
        }
        return WorkerAction.SUCCESS
    }
}

/**
 * Stage 3.4 — WorkManager CoroutineWorker for connected background synchronisation.
 * Runs dependency-ordered queue flush first, then delta-refreshes local product catalogue.
 */
class EmofahSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val database = EmofahDatabase.getInstance(applicationContext)
        val connectivity = AndroidConnectivityChecker(applicationContext)
        val authRepo = (applicationContext as? EmofahApplication)?.authSessionRepository
        authRepo?.ensureFreshSessionIfOnline()

        val orderRepository = SupabaseOrderRepository(applicationContext, connectivity)
        val processor = SyncQueueProcessor(
            database = database,
            connectivityChecker = connectivity,
            onOrderUpdateReconciled = { orderId, confirmedRawStatus ->
                orderRepository.clearPendingStatusOverlay(orderId, confirmedRawStatus)
            },
            onOrderConversionReconciled = { orderId, synced ->
                if (synced) {
                    orderRepository.markOrderConverted(orderId, markPendingOverlay = false)
                } else {
                    orderRepository.unmarkOrderConverted(orderId)
                }
            },
        )
        val telemetry = SyncTelemetryStore(applicationContext)

        val summary = processor.processPendingQueue()
        if (summary.authBlockedCount > 0) {
            authRepo?.handleServerAuthRejected(
                "Server rejected session during background sync. Please sign in again."
            )
        }
        telemetry.recordAttempt(System.currentTimeMillis(), summary)
        EmofahNotificationManager.notifySyncAttentionIfNeeded(applicationContext, summary)

        // Refresh catalogue delta, online orders & flush audit trail if connected and not blocked on auth
        if (!summary.skippedOffline && summary.authBlockedCount == 0) {
            val auditSyncRepo = AuditSyncRepository(
                database = database,
                identityProvider = DeviceIdentityProvider(applicationContext),
                connectivityChecker = connectivity,
            )
            auditSyncRepo.flushPendingAudits()

            val catalogRepo = SupabaseProductRepository(applicationContext, database)
            catalogRepo.syncCatalogueAndRebuildIndex(forceNetwork = false)

            val activeIdentities = database.productDao()
                .getActiveProducts()
                .map(CatalogueDeltaCalculator::toProductIdentity)
            val projections = database.productDao().getAllStockProjections()
            val stockAlertSummary = StockAlertPolicy.buildSummary(
                products = activeIdentities,
                projections = projections,
                threshold = StockLookupPolicy.DEFAULT_LOW_STOCK_THRESHOLD,
            )
            EmofahNotificationManager.notifyStockAlertsIfNeeded(applicationContext, stockAlertSummary)

            val orderFeed = orderRepository.fetchOrders(
                catalogueProducts = activeIdentities,
                forceRefresh = true,
            )
            if (!orderFeed.isFromCache) {
                EmofahNotificationManager.notifyNewOrdersIfNeeded(applicationContext, orderFeed.orders)
            }
        }

        runCatching {
            ReliabilityRecoveryManager(applicationContext, database).pruneStorageSafely()
        }

        val outputData = workDataOf(
            "synced_count" to summary.syncedCount,
            "retryable_count" to summary.retryableCount,
            "conflict_count" to summary.conflictCount,
            "auth_blocked_count" to summary.authBlockedCount,
        )

        return when (SyncWorkerOutcomeMapper.decideAction(summary, runAttemptCount)) {
            WorkerAction.SUCCESS -> Result.success(outputData)
            WorkerAction.RETRY -> Result.retry()
            WorkerAction.FAILURE_AUTH_HOLD,
            WorkerAction.FAILURE_MAX_ATTEMPTS,
            -> Result.failure(outputData)
        }
    }
}
