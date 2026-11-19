package com.emofah.agencies.audit

import com.emofah.agencies.data.local.EmofahDatabase
import com.emofah.agencies.sales.ConnectivityChecker
import com.emofah.agencies.sales.DeviceIdentityProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AuditFlushSummary(
    val attemptedCount: Int,
    val confirmedCount: Int,
    val duplicateCount: Int,
    val skippedOffline: Boolean = false,
    val authRequired: Boolean = false,
    val rpcMissing: Boolean = false,
    val message: String,
)

/**
 * Stage 9.3 — Single-flight Audit Synchronisation Repository.
 * Flushes locally appended `LOCAL_PENDING` audit events in bounded batches to Supabase
 * without ever mutating or deleting local audit payloads.
 */
class AuditSyncRepository(
    private val database: EmofahDatabase,
    private val identityProvider: DeviceIdentityProvider,
    private val connectivityChecker: ConnectivityChecker,
    private val auditGateway: AuditGateway = SupabaseAuditClient(),
) {
    private val flushMutex = Mutex()

    suspend fun flushPendingAudits(
        maxBatches: Int = 5,
        batchSize: Int = 50,
    ): AuditFlushSummary = withContext(Dispatchers.IO) {
        flushMutex.withLock {
            if (!connectivityChecker.isOnline()) {
                return@withLock AuditFlushSummary(
                attemptedCount = 0,
                confirmedCount = 0,
                duplicateCount = 0,
                skippedOffline = true,
                message = "Offline — audit events remain preserved locally.",
            )
        }

        val auditDao = database.auditDao()
        var totalAttempted = 0
        var totalConfirmed = 0
        var totalDuplicates = 0
        val currentRole = identityProvider.getStaffRole()

        for (batchIndex in 0 until maxBatches) {
            val batch = auditDao.getPendingAudits(limit = batchSize)
            if (batch.isEmpty()) {
                break
            }
            totalAttempted += batch.size

            when (val outcome = auditGateway.uploadAuditBatch(batch, currentRole)) {
                is AuditSyncOutcome.CommittedOnline -> {
                    val confirmedIds = outcome.confirmedAuditIds
                    if (confirmedIds.isNotEmpty()) {
                        auditDao.markAuditsServerConfirmed(confirmedIds)
                    }
                    totalConfirmed += outcome.acceptedCount
                    totalDuplicates += outcome.duplicateCount
                    if (confirmedIds.isEmpty() || batch.size < batchSize) {
                        break
                    }
                }
                is AuditSyncOutcome.AuthRequired -> {
                    return@withLock AuditFlushSummary(
                        attemptedCount = totalAttempted,
                        confirmedCount = totalConfirmed,
                        duplicateCount = totalDuplicates,
                        authRequired = true,
                        message = outcome.message,
                    )
                }
                is AuditSyncOutcome.RpcMissing -> {
                    return@withLock AuditFlushSummary(
                        attemptedCount = totalAttempted,
                        confirmedCount = totalConfirmed,
                        duplicateCount = totalDuplicates,
                        rpcMissing = true,
                        message = outcome.message,
                    )
                }
                is AuditSyncOutcome.Rejected -> {
                    return@withLock AuditFlushSummary(
                        attemptedCount = totalAttempted,
                        confirmedCount = totalConfirmed,
                        duplicateCount = totalDuplicates,
                        message = outcome.message,
                    )
                }
                is AuditSyncOutcome.NetworkOrTransientError -> {
                    return@withLock AuditFlushSummary(
                        attemptedCount = totalAttempted,
                        confirmedCount = totalConfirmed,
                        duplicateCount = totalDuplicates,
                        message = outcome.message,
                    )
                }
            }
        }

        val message = when {
            totalAttempted == 0 -> "All local audit events are synced."
            else -> "Audit trail synced: $totalConfirmed new, $totalDuplicates already confirmed."
        }

        AuditFlushSummary(
            attemptedCount = totalAttempted,
            confirmedCount = totalConfirmed,
            duplicateCount = totalDuplicates,
            message = message,
        )
        }
    }

    suspend fun fetchRemoteAudits(limit: Int = 100): RemoteAuditFetchOutcome = withContext(Dispatchers.IO) {
        if (!connectivityChecker.isOnline()) {
            return@withContext RemoteAuditFetchOutcome.Error("Offline — showing local device audit trail.")
        }
        auditGateway.fetchRemoteAudits(limit)
    }
}
