package com.emofah.agencies.data.sync

import android.content.Context
import com.emofah.agencies.data.catalog.ReferenceImageCache
import com.emofah.agencies.data.local.EmofahDatabase
import com.emofah.agencies.data.local.entities.LocalAuditEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import com.emofah.agencies.sales.DeviceIdentityProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Stage 9.4 — Coordinates crash recovery, dependency/payload self-healing,
 * and non-destructive local storage hygiene across startup, WorkManager, and manual UI triggers.
 */
class ReliabilityRecoveryManager(
    context: Context,
    private val database: EmofahDatabase,
    private val identityProvider: DeviceIdentityProvider = DeviceIdentityProvider(context),
    private val clockEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    private val appContext = context.applicationContext
    private val scansDir = File(appContext.cacheDir, "captured_scans")
    private val receiptsDir = File(appContext.cacheDir, "receipts")
    private val refsDir = File(appContext.filesDir, "reference_images")
    private val referenceImageCache = ReferenceImageCache(appContext)
    private val recoveryMutex = Mutex()

    suspend fun inspectHealthSnapshot(): ReliabilityHealthSnapshot = withContext(Dispatchers.IO) {
        val syncQueueDao = database.syncQueueDao()
        val productDao = database.productDao()
        val draftDao = database.financialAndDraftDao()

        val events = syncQueueDao.getAllEventsSnapshot()
        val drafts = draftDao.getAllProductDraftsSnapshot()
        val refPhotos = productDao.getAllReferencePhotos()
        val protectedPaths = ReliabilityRecoveryPolicy.extractProtectedPhotoPaths(drafts, events, refPhotos)
        val integrity = ReliabilityRecoveryPolicy.analyzeQueueIntegrity(events, drafts)

        val scanBytes = directorySizeBytes(scansDir)
        val receiptBytes = directorySizeBytes(receiptsDir)
        val refBytes = directorySizeBytes(refsDir)

        ReliabilityHealthSnapshot(
            totalCacheBytes = scanBytes + receiptBytes + refBytes,
            scanCacheBytes = scanBytes,
            receiptCacheBytes = receiptBytes,
            referenceImagesBytes = refBytes,
            protectedAssetCount = protectedPaths.size,
            inFlightCount = events.count { it.status == SyncQueueStatus.IN_FLIGHT },
            dependencyBlockedCount = integrity.dependencyBlocked.size,
            malformedCount = integrity.malformedEvents.size,
        )
    }

    /**
     * Runs once on cold OS process start (recovering any interrupted `IN_FLIGHT` rows immediately),
     * repairs orphaned dependencies/relinks, and prunes stale unreferenced cache files.
     */
    suspend fun runStartupRecovery(): RecoveryRunReport = recoveryMutex.withLock {
        val isColdStart = !coldStartRecovered
        if (isColdStart) {
            coldStartRecovered = true
        }
        executeRecoveryPassLocked(
            recoverAllInFlight = isColdStart,
            reasonLabel = "Startup crash recovery & cache hygiene check",
        )
    }

    /**
     * Lightweight storage cleanup safe to invoke periodically inside WorkManager.
     */
    suspend fun pruneStorageSafely(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        recoveryMutex.withLock {
            pruneCachesLocked(clockEpochMs())
        }
    }

    /**
     * User-initiated recovery & cleanup from Activity -> Queue -> Storage & Reliability Guard.
     */
    suspend fun runManualRecoveryAndCleanup(): RecoveryRunReport = recoveryMutex.withLock {
        executeRecoveryPassLocked(
            recoverAllInFlight = false,
            reasonLabel = "Manual recovery & cache hygiene run",
        )
    }

    private suspend fun executeRecoveryPassLocked(
        recoverAllInFlight: Boolean,
        reasonLabel: String,
    ): RecoveryRunReport = withContext(Dispatchers.IO) {
        val now = clockEpochMs()
        val syncQueueDao = database.syncQueueDao()
        val draftDao = database.financialAndDraftDao()
        val auditDao = database.auditDao()

        val inFlightRecovered = if (recoverAllInFlight) {
            syncQueueDao.recoverAllInFlightOnColdStart(now)
        } else {
            syncQueueDao.resetStaleInFlightEvents(
                staleBeforeEpochMs = now - SyncQueuePolicy.STALE_IN_FLIGHT_MS,
                nextRetryEpochMs = now,
            )
        }

        val events = syncQueueDao.getAllEventsSnapshot()
        val drafts = draftDao.getAllProductDraftsSnapshot()
        val plan = ReliabilityRecoveryPolicy.analyzeQueueIntegrity(events, drafts)

        for (repair in plan.relinkRepairs) {
            syncQueueDao.updateEventPayload(repair.eventId, repair.updatedPayloadJson)
        }

        for (blocked in plan.dependencyBlocked) {
            val current = syncQueueDao.getEventById(blocked.eventId)
            syncQueueDao.updateEventProgress(
                eventId = blocked.eventId,
                status = SyncQueueStatus.CONFLICT,
                retryCount = (current?.retryCount ?: 0) + 1,
                attemptEpochMs = now,
                nextRetryEpochMs = now,
                failureReason = blocked.reason,
                serverAckId = null,
            )
        }

        for (malformed in plan.malformedEvents) {
            val current = syncQueueDao.getEventById(malformed.eventId)
            syncQueueDao.updateEventProgress(
                eventId = malformed.eventId,
                status = SyncQueueStatus.CONFLICT,
                retryCount = (current?.retryCount ?: 0) + 1,
                attemptEpochMs = now,
                nextRetryEpochMs = now,
                failureReason = malformed.reason,
                serverAckId = null,
            )
        }

        val (filesDeleted, bytesFreed) = pruneCachesLocked(now)

        if (inFlightRecovered > 0 || plan.totalRepairsCount > 0 || filesDeleted > 0) {
            runCatching {
                auditDao.appendAudit(
                    LocalAuditEventEntity(
                        auditId = "audit-${UUID.randomUUID()}",
                        actorId = identityProvider.getActorId(),
                        deviceId = identityProvider.getDeviceId(),
                        operation = "SYSTEM_RECOVERY_CLEANUP",
                        entityType = "SYNC_QUEUE",
                        entityId = "device-health",
                        beforeJson = null,
                        afterJson = JSONObject().apply {
                            put("in_flight_recovered", inFlightRecovered)
                            put("relinked_children", plan.relinkRepairs.size)
                            put("dependency_blocked", plan.dependencyBlocked.size)
                            put("malformed_quarantined", plan.malformedEvents.size)
                            put("cache_files_deleted", filesDeleted)
                            put("bytes_freed", bytesFreed)
                        }.toString(),
                        reason = reasonLabel,
                        syncStatus = SyncState.LOCAL_PENDING,
                        occurredAtEpochMs = now,
                    )
                )
            }
        }

        val updatedEvents = syncQueueDao.getAllEventsSnapshot()
        val updatedDrafts = draftDao.getAllProductDraftsSnapshot()
        val updatedPhotos = database.productDao().getAllReferencePhotos()
        val updatedProtected = ReliabilityRecoveryPolicy.extractProtectedPhotoPaths(
            updatedDrafts,
            updatedEvents,
            updatedPhotos,
        )
        val updatedPlan = ReliabilityRecoveryPolicy.analyzeQueueIntegrity(updatedEvents, updatedDrafts)

        val scanBytes = directorySizeBytes(scansDir)
        val receiptBytes = directorySizeBytes(receiptsDir)
        val refBytes = directorySizeBytes(refsDir)

        val snapshotAfter = ReliabilityHealthSnapshot(
            totalCacheBytes = scanBytes + receiptBytes + refBytes,
            scanCacheBytes = scanBytes,
            receiptCacheBytes = receiptBytes,
            referenceImagesBytes = refBytes,
            protectedAssetCount = updatedProtected.size,
            inFlightCount = updatedEvents.count { it.status == SyncQueueStatus.IN_FLIGHT },
            dependencyBlockedCount = updatedPlan.dependencyBlocked.size,
            malformedCount = updatedPlan.malformedEvents.size,
        )

        val summary = buildString {
            val actions = mutableListOf<String>()
            if (inFlightRecovered > 0) actions += "$inFlightRecovered interrupted item(s) recovered"
            if (plan.relinkRepairs.isNotEmpty()) actions += "${plan.relinkRepairs.size} child event(s) relinked"
            if (plan.dependencyBlocked.isNotEmpty()) actions += "${plan.dependencyBlocked.size} blocked dependency item(s) flagged"
            if (plan.malformedEvents.isNotEmpty()) actions += "${plan.malformedEvents.size} malformed item(s) quarantined"
            if (filesDeleted > 0) actions += "$filesDeleted stale cache file(s) freed (${bytesFreed / 1024} KB)"
            if (actions.isEmpty()) {
                append("All queues healthy (${snapshotAfter.protectedAssetCount} active asset(s) protected).")
            } else {
                append(actions.joinToString(" · "))
            }
        }

        RecoveryRunReport(
            inFlightRecovered = inFlightRecovered,
            relinkedChildren = plan.relinkRepairs.size,
            blockedChildrenFlagged = plan.dependencyBlocked.size,
            malformedQuarantined = plan.malformedEvents.size,
            filesDeleted = filesDeleted,
            bytesFreed = bytesFreed,
            snapshotAfter = snapshotAfter,
            summaryMessage = summary,
        )
    }

    private suspend fun pruneCachesLocked(nowEpochMs: Long): Pair<Int, Long> {
        val events = database.syncQueueDao().getAllEventsSnapshot()
        val drafts = database.financialAndDraftDao().getAllProductDraftsSnapshot()
        val refPhotos = database.productDao().getAllReferencePhotos()
        val protectedPaths = ReliabilityRecoveryPolicy.extractProtectedPhotoPaths(drafts, events, refPhotos)

        var deletedCount = 0
        var freedBytes = 0L

        val scanCandidates = listCandidates(scansDir)
        val scanPlan = ReliabilityRecoveryPolicy.buildPrunePlan(
            candidates = scanCandidates,
            protectedPaths = protectedPaths,
            nowEpochMs = nowEpochMs,
            maxAgeMs = ReliabilityRecoveryPolicy.SCAN_CACHE_MAX_AGE_MS,
            maxTotalBytes = ReliabilityRecoveryPolicy.SCAN_CACHE_MAX_BYTES,
        )
        scanPlan.filesToDelete.forEach { candidate ->
            val f = File(candidate.absolutePath)
            if (f.exists() && f.delete()) {
                deletedCount++
                freedBytes += candidate.sizeBytes
            }
        }

        val receiptCandidates = listCandidates(receiptsDir)
        val receiptPlan = ReliabilityRecoveryPolicy.buildPrunePlan(
            candidates = receiptCandidates,
            protectedPaths = protectedPaths,
            nowEpochMs = nowEpochMs,
            maxAgeMs = ReliabilityRecoveryPolicy.RECEIPT_CACHE_MAX_AGE_MS,
            maxTotalBytes = ReliabilityRecoveryPolicy.RECEIPT_CACHE_MAX_BYTES,
        )
        receiptPlan.filesToDelete.forEach { candidate ->
            val f = File(candidate.absolutePath)
            if (f.exists() && f.delete()) {
                deletedCount++
                freedBytes += candidate.sizeBytes
            }
        }

        val refBefore = directorySizeBytes(refsDir)
        referenceImageCache.enforceStorageBudget()
        val refAfter = directorySizeBytes(refsDir)
        if (refBefore > refAfter) {
            freedBytes += (refBefore - refAfter)
        }

        return deletedCount to freedBytes
    }

    private fun listCandidates(dir: File): List<CachedFileCandidate> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile }
            ?.map { f ->
                CachedFileCandidate(
                    absolutePath = f.absolutePath,
                    name = f.name,
                    sizeBytes = f.length(),
                    lastModifiedEpochMs = f.lastModified(),
                )
            }
            .orEmpty()
    }

    private fun directorySizeBytes(dir: File): Long {
        if (!dir.exists() || !dir.isDirectory) return 0L
        return dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    }

    companion object {
        @Volatile
        private var coldStartRecovered = false
    }
}
