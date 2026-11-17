package com.emofah.agencies

import com.emofah.agencies.data.sync.SyncPassSummary
import com.emofah.agencies.data.sync.SyncWorkConstants
import com.emofah.agencies.data.sync.SyncWorkerOutcomeMapper
import com.emofah.agencies.data.sync.WorkerAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundSyncStage34Test {

    @Test
    fun authBlockedSyncStopsWorkManagerRetriesImmediately() {
        val summary = SyncPassSummary(
            attemptedCount = 1,
            syncedCount = 0,
            retryableCount = 0,
            conflictCount = 0,
            authBlockedCount = 1,
            deferredCount = 0,
        )
        assertEquals(
            WorkerAction.FAILURE_AUTH_HOLD,
            SyncWorkerOutcomeMapper.decideAction(summary, runAttemptCount = 0),
        )
    }

    @Test
    fun transientFailuresAndOfflineRetryUpToBoundedLimit() {
        val transientSummary = SyncPassSummary(
            attemptedCount = 1,
            syncedCount = 0,
            retryableCount = 1,
            conflictCount = 0,
            authBlockedCount = 0,
            deferredCount = 0,
        )
        assertEquals(
            WorkerAction.RETRY,
            SyncWorkerOutcomeMapper.decideAction(transientSummary, runAttemptCount = 0),
        )
        assertEquals(
            WorkerAction.RETRY,
            SyncWorkerOutcomeMapper.decideAction(transientSummary, runAttemptCount = 5),
        )
        assertEquals(
            WorkerAction.FAILURE_MAX_ATTEMPTS,
            SyncWorkerOutcomeMapper.decideAction(
                transientSummary,
                runAttemptCount = SyncWorkerOutcomeMapper.MAX_WORKMANAGER_ATTEMPTS,
            ),
        )

        val offlineSummary = SyncPassSummary(
            attemptedCount = 0,
            syncedCount = 0,
            retryableCount = 0,
            conflictCount = 0,
            authBlockedCount = 0,
            deferredCount = 0,
            skippedOffline = true,
        )
        assertEquals(
            WorkerAction.RETRY,
            SyncWorkerOutcomeMapper.decideAction(offlineSummary, runAttemptCount = 1),
        )
    }

    @Test
    fun syncedAndConflictOnlyPassesReturnWorkerSuccess() {
        val synced = SyncPassSummary(
            attemptedCount = 2,
            syncedCount = 2,
            retryableCount = 0,
            conflictCount = 0,
            authBlockedCount = 0,
            deferredCount = 0,
        )
        assertEquals(WorkerAction.SUCCESS, SyncWorkerOutcomeMapper.decideAction(synced, 0))

        // Conflicts require human review rather than endless WorkManager retries
        val conflictOnly = SyncPassSummary(
            attemptedCount = 1,
            syncedCount = 0,
            retryableCount = 0,
            conflictCount = 1,
            authBlockedCount = 0,
            deferredCount = 0,
        )
        assertEquals(WorkerAction.SUCCESS, SyncWorkerOutcomeMapper.decideAction(conflictOnly, 0))
    }

    @Test
    fun workConstantsAreBoundedAndDistinct() {
        assertTrue(SyncWorkConstants.INITIAL_BACKOFF_SECONDS >= 30L)
        assertTrue(SyncWorkConstants.PERIODIC_INTERVAL_HOURS >= 1L)
        assertTrue(
            SyncWorkConstants.UNIQUE_IMMEDIATE_WORK_NAME != SyncWorkConstants.UNIQUE_PERIODIC_WORK_NAME
        )
    }
}
