package com.emofah.agencies.data.sync

import com.emofah.agencies.data.local.entities.SyncQueueEventEntity

data class OfflineStatusSummary(
    val isOnline: Boolean,
    val pendingCount: Int,
    val conflictCount: Int,
    val authBlockedCount: Int,
    val syncedCount: Int,
    val statusHeadline: String,
    val actionableEvents: List<SyncQueueEventEntity>,
    val conflictEvents: List<SyncQueueEventEntity>,
    val recentSyncedEvents: List<SyncQueueEventEntity>,
) {
    companion object {
        fun fromQueue(
            isOnline: Boolean,
            events: List<SyncQueueEventEntity>,
        ): OfflineStatusSummary {
            val conflicts = events.filter { it.status == SyncQueueStatus.CONFLICT }
            val authBlocked = events.filter { it.status == SyncQueueStatus.AUTH_REQUIRED }
            val pending = events.filter {
                it.status == SyncQueueStatus.QUEUED ||
                    it.status == SyncQueueStatus.FAILED_RETRYABLE ||
                    it.status == SyncQueueStatus.IN_FLIGHT
            }
            val synced = events.filter {
                it.status == SyncQueueStatus.SYNCED ||
                    it.status == SyncQueueStatus.RESOLVED_LOCALLY
            }

            val headline = when {
                authBlocked.isNotEmpty() ->
                    "${authBlocked.size} event(s) waiting for re-authentication"
                conflicts.isNotEmpty() ->
                    "${conflicts.size} conflict(s) require staff review"
                !isOnline && pending.isNotEmpty() ->
                    "Offline · ${pending.size} item(s) queued safely on device"
                !isOnline ->
                    "Offline · New sales save locally to Room"
                pending.isNotEmpty() ->
                    "Online · ${pending.size} item(s) ready to sync"
                else ->
                    "Online · All queued items synced"
            }

            return OfflineStatusSummary(
                isOnline = isOnline,
                pendingCount = pending.size,
                conflictCount = conflicts.size,
                authBlockedCount = authBlocked.size,
                syncedCount = synced.size,
                statusHeadline = headline,
                actionableEvents = (authBlocked + pending).sortedByDescending { it.createdAtEpochMs },
                conflictEvents = conflicts.sortedByDescending { it.createdAtEpochMs },
                recentSyncedEvents = synced.sortedByDescending { it.createdAtEpochMs }.take(10),
            )
        }
    }
}
