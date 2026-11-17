package com.emofah.agencies.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.emofah.agencies.data.local.EmofahDatabase
import java.util.concurrent.TimeUnit

object SyncWorkConstants {
    const val UNIQUE_IMMEDIATE_WORK_NAME = "emofah_immediate_sync"
    const val UNIQUE_PERIODIC_WORK_NAME = "emofah_periodic_sync"
    const val PERIODIC_INTERVAL_HOURS = 1L
    const val INITIAL_BACKOFF_SECONDS = 30L
}

/**
 * Stage 3.4 — WorkManager Scheduler with connected-network constraints,
 * bounded exponential backoff, unique work deduplication, and auth recovery triggers.
 */
object SyncScheduler {

    fun connectedConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }

    fun buildImmediateSyncRequest(): OneTimeWorkRequest {
        return OneTimeWorkRequestBuilder<EmofahSyncWorker>()
            .setConstraints(connectedConstraints())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                SyncWorkConstants.INITIAL_BACKOFF_SECONDS,
                TimeUnit.SECONDS,
            )
            .build()
    }

    fun buildPeriodicSyncRequest(): PeriodicWorkRequest {
        return PeriodicWorkRequestBuilder<EmofahSyncWorker>(
            SyncWorkConstants.PERIODIC_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(connectedConstraints())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                SyncWorkConstants.INITIAL_BACKOFF_SECONDS,
                TimeUnit.SECONDS,
            )
            .build()
    }

    fun ensurePeriodicSyncScheduled(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            SyncWorkConstants.UNIQUE_PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            buildPeriodicSyncRequest(),
        )
    }

    fun enqueueImmediateSync(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            SyncWorkConstants.UNIQUE_IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            buildImmediateSyncRequest(),
        )
    }

    suspend fun onAuthenticationRestored(context: Context, database: EmofahDatabase) {
        database.syncQueueDao().requeueAuthBlockedEvents(System.currentTimeMillis())
        SyncTelemetryStore(context).clearAuthBlock()
        enqueueImmediateSync(context)
    }
}
