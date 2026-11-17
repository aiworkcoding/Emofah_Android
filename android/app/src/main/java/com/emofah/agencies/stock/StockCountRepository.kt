package com.emofah.agencies.stock

import android.content.Context
import com.emofah.agencies.auth.EmofahPermission
import com.emofah.agencies.auth.PermissionCheckResult
import com.emofah.agencies.auth.RolePermissionPolicy
import com.emofah.agencies.data.SupabaseConfig
import com.emofah.agencies.data.local.EmofahDatabase
import com.emofah.agencies.data.local.entities.LocalAuditEventEntity
import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import com.emofah.agencies.data.sync.SyncEventType
import com.emofah.agencies.data.sync.SyncQueueStatus
import com.emofah.agencies.data.sync.SyncScheduler
import com.emofah.agencies.sales.AndroidConnectivityChecker
import com.emofah.agencies.sales.ConnectivityChecker
import com.emofah.agencies.sales.DeviceIdentityProvider
import com.emofah.agencies.sales.StaffRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class StockCountRepository(
    context: Context,
    private val database: EmofahDatabase = EmofahDatabase.getInstance(context.applicationContext),
    private val identityProvider: DeviceIdentityProvider = DeviceIdentityProvider(context.applicationContext),
    private val connectivityChecker: ConnectivityChecker = AndroidConnectivityChecker(context.applicationContext),
    private val stockCountGateway: StockCountGateway = SupabaseStockCountClient(),
    private val isSupabaseConfigured: Boolean = SupabaseConfig.isConfigured,
    private val onOfflineEventQueued: () -> Unit = {
        SyncScheduler.enqueueImmediateSync(context.applicationContext)
    },
    private val clockEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    private val productDao = database.productDao()
    private val syncQueueDao = database.syncQueueDao()
    private val auditDao = database.auditDao()

    suspend fun submitStockCount(
        draft: StockCountSessionDraft,
        staffRole: StaffRole = identityProvider.getStaffRole(),
    ): Result<String> = withContext(Dispatchers.IO) {
        val permCheck = RolePermissionPolicy.authorize(
            role = staffRole,
            permission = EmofahPermission.SUBMIT_PHYSICAL_STOCK_COUNT,
        )
        if (permCheck is PermissionCheckResult.Denied) {
            return@withContext Result.failure(SecurityException(permCheck.message))
        }

        val validation = StockCountValidator.validate(draft)
        if (validation.isFailure) {
            return@withContext Result.failure(validation.exceptionOrNull()!!)
        }

        val existingQueueEvent = syncQueueDao.getEventById("sync-count-${draft.idempotencyKey}")
        if (existingQueueEvent != null) {
            return@withContext Result.success("ALREADY_RECORDED:${draft.idempotencyKey}")
        }

        val nowMs = clockEpochMs()
        val actorId = identityProvider.getActorId()
        val deviceId = identityProvider.getDeviceId()

        if (isSupabaseConfigured && connectivityChecker.isOnline()) {
            when (val outcome = stockCountGateway.submitStockCount(draft)) {
                is StockCountRpcOutcome.CommittedOnline -> {
                    productDao.applyBatchStockCountOnline(
                        countsByProductId = draft.items.map { it.productId.toLong() to it.countedQty },
                        updatedAtEpochMs = nowMs,
                    )
                    auditDao.appendAudit(
                        LocalAuditEventEntity(
                            auditId = "audit-${UUID.randomUUID()}",
                            actorId = actorId,
                            deviceId = deviceId,
                            operation = "PHYSICAL_STOCK_COUNT_ONLINE",
                            entityType = "STOCK_COUNT",
                            entityId = outcome.sessionId.ifBlank { draft.idempotencyKey },
                            beforeJson = null,
                            afterJson = JSONObject().apply {
                                put("session_name", draft.sessionName)
                                put("items_counted", draft.totalItemsCounted)
                                put("discrepancies", draft.totalDiscrepancies)
                                put("net_variance", draft.netVariance)
                                put("already_committed", outcome.alreadyCommitted)
                            }.toString(),
                            reason = "Physical stock count '${draft.sessionName}' submitted (${draft.totalItemsCounted} items).",
                            syncStatus = SyncState.SERVER_CONFIRMED,
                            occurredAtEpochMs = nowMs,
                        )
                    )
                    return@withContext Result.success(outcome.sessionId.ifBlank { draft.idempotencyKey })
                }

                is StockCountRpcOutcome.Rejected -> {
                    return@withContext Result.failure(IllegalStateException(outcome.message))
                }

                is StockCountRpcOutcome.RpcMissing,
                is StockCountRpcOutcome.AuthRequired,
                is StockCountRpcOutcome.NetworkOrTransientError,
                -> {
                    val failureNote = when (outcome) {
                        is StockCountRpcOutcome.RpcMissing -> outcome.message
                        is StockCountRpcOutcome.AuthRequired -> outcome.message
                        is StockCountRpcOutcome.NetworkOrTransientError -> outcome.message
                        else -> "Queued offline"
                    }
                    return@withContext queueOfflineStockCount(
                        draft = draft,
                        actorId = actorId,
                        deviceId = deviceId,
                        nowMs = nowMs,
                        queueReason = failureNote,
                    )
                }
            }
        }

        return@withContext queueOfflineStockCount(
            draft = draft,
            actorId = actorId,
            deviceId = deviceId,
            nowMs = nowMs,
            queueReason = if (isSupabaseConfigured) {
                "Device is offline — physical count queued for sync."
            } else {
                "Supabase not configured — saved locally in Room queue."
            },
        )
    }

    private suspend fun queueOfflineStockCount(
        draft: StockCountSessionDraft,
        actorId: String,
        deviceId: String,
        nowMs: Long,
        queueReason: String,
    ): Result<String> {
        val jsonItems = JSONArray().apply {
            draft.items.forEach { item ->
                put(
                    JSONObject().apply {
                        put("product_id", item.productId)
                        put("product_name", item.productName)
                        put("system_qty", item.systemQty)
                        put("counted_qty", item.countedQty)
                        put("variance", item.variance)
                        put("reason_code", item.reasonCode)
                        put("note", item.note)
                        put("client_item_id", item.clientItemId)
                    }
                )
            }
        }

        val payloadJson = JSONObject().apply {
            put("p_session_name", draft.sessionName)
            put("p_created_by", draft.createdBy)
            put("p_items", jsonItems)
            put("p_idempotency_key", draft.idempotencyKey)
        }.toString()

        val syncEvent = SyncQueueEventEntity(
            eventId = "sync-count-${draft.idempotencyKey}",
            eventType = SyncEventType.STOCK_COUNT,
            entityId = draft.idempotencyKey,
            dependencyEventId = null,
            payloadJson = payloadJson,
            actorId = actorId,
            deviceId = deviceId,
            status = SyncQueueStatus.QUEUED,
            retryCount = 0,
            nextRetryAtEpochMs = nowMs,
            createdAtEpochMs = nowMs,
        )

        val auditEvent = LocalAuditEventEntity(
            auditId = "audit-${UUID.randomUUID()}",
            actorId = actorId,
            deviceId = deviceId,
            operation = "PHYSICAL_STOCK_COUNT_OFFLINE_QUEUED",
            entityType = "STOCK_COUNT",
            entityId = draft.idempotencyKey,
            beforeJson = null,
            afterJson = payloadJson,
            reason = "${draft.sessionName} queued offline: $queueReason",
            syncStatus = SyncState.LOCAL_PENDING,
            occurredAtEpochMs = nowMs,
        )

        syncQueueDao.commitOfflineStockCountAtomically(
            deltasByProductId = draft.items.map { it.productId.toLong() to it.variance },
            syncEvent = syncEvent,
            auditEvent = auditEvent,
            updatedAtEpochMs = nowMs,
            productDao = productDao,
            auditDao = auditDao,
        )

        onOfflineEventQueued()
        return Result.success("OFFLINE_QUEUED:${draft.idempotencyKey}")
    }
}
