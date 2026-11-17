package com.emofah.agencies

import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.sync.OfflineStatusSummary
import com.emofah.agencies.data.sync.SyncEventType
import com.emofah.agencies.data.sync.SyncQueueStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineStatusStage36Test {

    private fun evt(id: String, status: String, createdAt: Long = 1000L) = SyncQueueEventEntity(
        eventId = id,
        eventType = SyncEventType.SALE,
        entityId = "sale-$id",
        payloadJson = "{}",
        actorId = "staff",
        deviceId = "dev",
        status = status,
        createdAtEpochMs = createdAt,
    )

    @Test
    fun summaryComputesCountsAndHeadlinesAcrossOnlineOfflineConflictAndAuth() {
        val allSynced = OfflineStatusSummary.fromQueue(
            isOnline = true,
            events = listOf(evt("1", SyncQueueStatus.SYNCED), evt("2", SyncQueueStatus.RESOLVED_LOCALLY)),
        )
        assertEquals(0, allSynced.pendingCount)
        assertEquals(0, allSynced.conflictCount)
        assertEquals(2, allSynced.syncedCount)
        assertTrue(allSynced.statusHeadline.contains("All queued items synced"))

        val offlinePending = OfflineStatusSummary.fromQueue(
            isOnline = false,
            events = listOf(evt("1", SyncQueueStatus.QUEUED), evt("2", SyncQueueStatus.FAILED_RETRYABLE)),
        )
        assertEquals(2, offlinePending.pendingCount)
        assertTrue(offlinePending.statusHeadline.contains("Offline"))

        val conflicted = OfflineStatusSummary.fromQueue(
            isOnline = true,
            events = listOf(evt("1", SyncQueueStatus.CONFLICT), evt("2", SyncQueueStatus.QUEUED)),
        )
        assertEquals(1, conflicted.conflictCount)
        assertEquals(1, conflicted.pendingCount)
        assertTrue(conflicted.statusHeadline.contains("conflict"))

        val authBlocked = OfflineStatusSummary.fromQueue(
            isOnline = true,
            events = listOf(evt("1", SyncQueueStatus.AUTH_REQUIRED), evt("2", SyncQueueStatus.CONFLICT)),
        )
        assertEquals(1, authBlocked.authBlockedCount)
        assertTrue(authBlocked.statusHeadline.contains("re-authentication"))
    }
}
