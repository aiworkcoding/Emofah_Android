package com.emofah.agencies.orders

import com.emofah.agencies.data.SupabaseConfig
import com.emofah.agencies.data.local.EmofahDatabase
import com.emofah.agencies.data.local.entities.LocalAuditEventEntity
import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import com.emofah.agencies.data.sync.SyncEventType
import com.emofah.agencies.data.sync.SyncQueuePolicy
import com.emofah.agencies.data.sync.SyncQueueStatus
import com.emofah.agencies.sales.ConnectivityChecker
import com.emofah.agencies.sales.DeviceIdentityProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/**
 * Stage 6.2 — Order Management Repository.
 * Coordinates operational order status transitions (`Pending -> Confirmed -> Ready -> Fulfilled / Cancelled`)
 * with transition validation, idempotent online RPC execution, Room offline queue persistence,
 * local cache overlay updates, and audit trail recording.
 */
class OrderManagementRepository(
    private val database: EmofahDatabase,
    private val orderRepository: SupabaseOrderRepository,
    private val identityProvider: DeviceIdentityProvider,
    private val connectivityChecker: ConnectivityChecker,
    private val orderStatusGateway: OrderStatusGateway = SupabaseOrderStatusClient(),
    private val isSupabaseConfigured: Boolean = SupabaseConfig.isConfigured,
    private val onOfflineEventQueued: () -> Unit = {},
) {
    private val syncQueueDao = database.syncQueueDao()
    private val auditDao = database.auditDao()

    suspend fun updateOrderStatus(
        order: StoreOrderRecord,
        targetStatus: StoreOrderStatus,
        reason: String? = null,
        clientUpdateId: String = "ordupd-${UUID.randomUUID()}",
    ): OrderStatusUpdateResult = withContext(Dispatchers.IO) {
        val validation = OrderStatusTransitionPolicy.validateTransition(
            current = order.status,
            target = targetStatus,
            reason = reason,
        )
        if (!validation.isValid) {
            return@withContext OrderStatusUpdateResult.Rejected(
                validation.errorMessage ?: "Invalid order status transition."
            )
        }

        val existingQueued = syncQueueDao.findOrderUpdateEventByClientId(clientUpdateId)
        if (existingQueued != null) {
            return@withContext OrderStatusUpdateResult.DuplicateIgnored(
                clientUpdateId = clientUpdateId,
                syncStatus = existingQueued.status,
            )
        }

        val cleanReason = reason?.trim()?.takeIf { it.isNotBlank() }
        val nowMs = System.currentTimeMillis()
        val nowIso = Instant.ofEpochMilli(nowMs).toString()
        val actorId = identityProvider.getActorId()
        val deviceId = identityProvider.getDeviceId()
        val idempotencyKey = "idem-$clientUpdateId"

        val rpcRequest = AndroidOrderStatusRpcRequest(
            clientUpdateId = clientUpdateId,
            idempotencyKey = idempotencyKey,
            orderId = order.id,
            targetStatus = targetStatus,
            reason = cleanReason,
            actorId = actorId,
            deviceId = deviceId,
            updatedAtIso = nowIso,
        )

        if (isSupabaseConfigured && connectivityChecker.isOnline()) {
            when (val outcome = orderStatusGateway.updateOrderStatus(rpcRequest)) {
                is OrderStatusRpcOutcome.CommittedOnline -> {
                    val resolvedStatus = StoreOrderStatus.fromRaw(outcome.newRawStatus)
                        .takeIf { it != StoreOrderStatus.UNKNOWN } ?: targetStatus
                    orderRepository.applyLocalOrderStatusChange(
                        orderId = order.id,
                        newRawStatus = resolvedStatus.supabaseValue,
                        markPendingOverlay = false,
                        updatedAtEpochMs = nowMs,
                    )
                    auditDao.appendAudit(
                        LocalAuditEventEntity(
                            auditId = "audit-${UUID.randomUUID()}",
                            actorId = actorId,
                            deviceId = deviceId,
                            operation = "ORDER_STATUS_UPDATED_ONLINE",
                            entityType = "ORDER",
                            entityId = order.id,
                            beforeJson = JSONObject().put("status", order.rawStatus).toString(),
                            afterJson = JSONObject().apply {
                                put("status", resolvedStatus.supabaseValue)
                                put("server_event_id", outcome.serverEventId)
                                put("reason", cleanReason ?: JSONObject.NULL)
                                put("already_committed", outcome.alreadyCommitted)
                            }.toString(),
                            reason = cleanReason ?: "Order ${order.displayNumber} moved to ${resolvedStatus.label}",
                            syncStatus = SyncState.SERVER_CONFIRMED,
                            occurredAtEpochMs = nowMs,
                        )
                    )
                    return@withContext OrderStatusUpdateResult.SuccessOnline(
                        orderId = order.id,
                        newStatus = resolvedStatus,
                        alreadyCommitted = outcome.alreadyCommitted,
                    )
                }

                is OrderStatusRpcOutcome.Rejected -> {
                    val reconciled = outcome.currentServerRawStatus?.let { raw ->
                        orderRepository.clearPendingStatusOverlay(order.id, confirmedRawStatus = raw)
                        StoreOrderStatus.fromRaw(raw)
                    }
                    return@withContext OrderStatusUpdateResult.Rejected(
                        message = outcome.message,
                        reconciledServerStatus = reconciled,
                    )
                }

                is OrderStatusRpcOutcome.AuthRequired -> {
                    return@withContext OrderStatusUpdateResult.Rejected(outcome.message)
                }

                is OrderStatusRpcOutcome.RpcMissing -> {
                    return@withContext queueOfflineOrderUpdate(
                        order = order,
                        rpcRequest = rpcRequest,
                        nowMs = nowMs,
                        fallbackReason = outcome.message,
                    )
                }

                is OrderStatusRpcOutcome.NetworkOrTransientError -> {
                    return@withContext queueOfflineOrderUpdate(
                        order = order,
                        rpcRequest = rpcRequest,
                        nowMs = nowMs,
                        fallbackReason = "Offline fallback: ${outcome.message}",
                    )
                }
            }
        }

        queueOfflineOrderUpdate(
            order = order,
            rpcRequest = rpcRequest,
            nowMs = nowMs,
            fallbackReason = if (isSupabaseConfigured) {
                "Device offline; order update queued for automatic sync."
            } else {
                "Supabase not configured; order update saved locally."
            },
        )
    }

    private suspend fun queueOfflineOrderUpdate(
        order: StoreOrderRecord,
        rpcRequest: AndroidOrderStatusRpcRequest,
        nowMs: Long,
        fallbackReason: String,
    ): OrderStatusUpdateResult.SavedOffline {
        val payloadJson = SyncQueuePolicy.serializeOrderStatusRpcRequest(rpcRequest)
        val syncEvent = SyncQueueEventEntity(
            eventId = "sync-ord-${rpcRequest.clientUpdateId}",
            eventType = SyncEventType.ORDER_UPDATE,
            entityId = rpcRequest.clientUpdateId,
            dependencyEventId = null,
            payloadJson = payloadJson,
            actorId = rpcRequest.actorId,
            deviceId = rpcRequest.deviceId,
            status = SyncQueueStatus.QUEUED,
            createdAtEpochMs = nowMs,
        )
        val audit = LocalAuditEventEntity(
            auditId = "audit-${UUID.randomUUID()}",
            actorId = rpcRequest.actorId,
            deviceId = rpcRequest.deviceId,
            operation = "ORDER_STATUS_QUEUED_OFFLINE",
            entityType = "ORDER",
            entityId = order.id,
            beforeJson = JSONObject().put("status", order.rawStatus).toString(),
            afterJson = payloadJson,
            reason = fallbackReason,
            syncStatus = SyncState.LOCAL_PENDING,
            occurredAtEpochMs = nowMs,
        )

        syncQueueDao.commitOrderUpdateOfflineAtomically(
            syncEvent = syncEvent,
            auditEvent = audit,
            auditDao = auditDao,
        )
        orderRepository.applyLocalOrderStatusChange(
            orderId = order.id,
            newRawStatus = rpcRequest.targetStatus.supabaseValue,
            markPendingOverlay = true,
            updatedAtEpochMs = nowMs,
        )
        onOfflineEventQueued()

        return OrderStatusUpdateResult.SavedOffline(
            orderId = order.id,
            newStatus = rpcRequest.targetStatus,
            reason = fallbackReason,
        )
    }
}
