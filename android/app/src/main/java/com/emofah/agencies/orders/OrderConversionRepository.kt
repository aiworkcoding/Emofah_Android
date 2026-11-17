package com.emofah.agencies.orders

import com.emofah.agencies.data.SupabaseConfig
import com.emofah.agencies.data.local.EmofahDatabase
import com.emofah.agencies.data.local.entities.LocalAuditEventEntity
import com.emofah.agencies.data.local.entities.LocalSaleEntity
import com.emofah.agencies.data.local.entities.LocalSaleItemEntity
import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import com.emofah.agencies.data.sync.SyncEventType
import com.emofah.agencies.data.sync.SyncQueuePolicy
import com.emofah.agencies.data.sync.SyncQueueStatus
import com.emofah.agencies.sales.ConnectivityChecker
import com.emofah.agencies.sales.DeviceIdentityProvider
import com.emofah.agencies.sales.PaymentMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/**
 * Stage 6.3 — Order-to-Sale Conversion Repository.
 * Converts an approved storefront order into shared `public.sales` + `public.products.qty`
 * stock deductions (online atomic RPC or offline queued `ORDER_TO_SALE` + Room sale mirror),
 * preventing double conversion and never fabricating missing product IDs.
 */
class OrderConversionRepository(
    private val database: EmofahDatabase,
    private val orderRepository: SupabaseOrderRepository,
    private val identityProvider: DeviceIdentityProvider,
    private val connectivityChecker: ConnectivityChecker,
    private val conversionGateway: OrderConversionGateway = SupabaseOrderConversionClient(),
    private val isSupabaseConfigured: Boolean = SupabaseConfig.isConfigured,
    private val onOfflineEventQueued: () -> Unit = {},
) {
    private val productDao = database.productDao()
    private val saleDao = database.saleDao()
    private val syncQueueDao = database.syncQueueDao()
    private val auditDao = database.auditDao()

    suspend fun convertOrderToSale(
        order: StoreOrderRecord,
        resolvedItems: List<ResolvedOrderSaleLine>,
        paymentMethod: PaymentMethod,
    ): OrderConversionResult = withContext(Dispatchers.IO) {
        if (orderRepository.isOrderConverted(order.id)) {
            return@withContext OrderConversionResult.DuplicateIgnored(
                orderId = order.id,
                message = "Order ${order.displayNumber} was already converted into a sale.",
            )
        }

        val validation = OrderConversionPolicy.validateConversion(order, resolvedItems)
        if (!validation.isValid) {
            return@withContext OrderConversionResult.Rejected(
                validation.errorMessage ?: "Order cannot be converted to a sale."
            )
        }

        val clientConversionId = "ordconv-${order.id}"
        val existingQueue = syncQueueDao.findOrderConversionEventByClientId(clientConversionId)
        val existingSale = saleDao.getSaleByIdempotencyKey("idem-$clientConversionId")
        if (existingQueue != null || existingSale != null) {
            orderRepository.markOrderConverted(
                orderId = order.id,
                markPendingOverlay = existingQueue != null && existingQueue.status != SyncQueueStatus.SYNCED,
            )
            return@withContext OrderConversionResult.DuplicateIgnored(
                orderId = order.id,
                message = "Order ${order.displayNumber} already has a recorded conversion.",
            )
        }

        val nowMs = System.currentTimeMillis()
        val nowIso = Instant.ofEpochMilli(nowMs).toString()
        val actorId = identityProvider.getActorId()
        val deviceId = identityProvider.getDeviceId()
        val idempotencyKey = "idem-$clientConversionId"

        val rpcRequest = AndroidOrderConversionRpcRequest(
            clientConversionId = clientConversionId,
            idempotencyKey = idempotencyKey,
            orderId = order.id,
            items = resolvedItems,
            paymentMethod = paymentMethod.name,
            actorId = actorId,
            deviceId = deviceId,
            convertedAtIso = nowIso,
        )

        if (isSupabaseConfigured && connectivityChecker.isOnline()) {
            when (val outcome = conversionGateway.convertOrderToSale(rpcRequest)) {
                is OrderConversionRpcOutcome.CommittedOnline -> {
                    val totalAmount = outcome.totalAmount.takeIf { it > 0.0 }
                        ?: resolvedItems.sumOf { it.lineTotal }
                    val localSale = buildLocalSaleEntity(
                        clientSaleId = clientConversionId,
                        idempotencyKey = idempotencyKey,
                        serverSaleId = outcome.updatedProducts.firstOrNull()?.serverSaleId,
                        totalAmount = totalAmount,
                        paymentMethod = paymentMethod.name,
                        actorId = actorId,
                        deviceId = deviceId,
                        saleDateIso = nowIso,
                        createdAtEpochMs = nowMs,
                        syncStatus = SyncState.SERVER_CONFIRMED,
                    )
                    val localItems = buildLocalSaleItemEntities(clientConversionId, resolvedItems)
                    val serverStockUpdates = outcome.updatedProducts.map { it.productId to it.serverQtyAfter }
                    val audit = LocalAuditEventEntity(
                        auditId = "audit-${UUID.randomUUID()}",
                        actorId = actorId,
                        deviceId = deviceId,
                        operation = "ORDER_CONVERTED_TO_SALE_ONLINE",
                        entityType = "ORDER",
                        entityId = order.id,
                        beforeJson = JSONObject().put("status", order.rawStatus).toString(),
                        afterJson = JSONObject().apply {
                            put("server_conversion_id", outcome.serverConversionId)
                            put("total_amount", totalAmount)
                            put("items_count", resolvedItems.size)
                            put("already_committed", outcome.alreadyCommitted)
                        }.toString(),
                        reason = "Converted order ${order.displayNumber} to shared sale via convert_android_order_to_sale",
                        syncStatus = SyncState.SERVER_CONFIRMED,
                        occurredAtEpochMs = nowMs,
                    )

                    syncQueueDao.commitOrderConversionOnlineAtomically(
                        sale = localSale,
                        items = localItems,
                        updatedServerStock = serverStockUpdates,
                        auditEvent = audit,
                        updatedAtEpochMs = nowMs,
                        saleDao = saleDao,
                        productDao = productDao,
                        auditDao = auditDao,
                    )
                    orderRepository.markOrderConverted(
                        orderId = order.id,
                        markPendingOverlay = false,
                        updatedAtEpochMs = nowMs,
                    )
                    return@withContext OrderConversionResult.SuccessOnline(
                        orderId = order.id,
                        totalAmount = totalAmount,
                        itemsCount = resolvedItems.size,
                        alreadyCommitted = outcome.alreadyCommitted,
                    )
                }

                is OrderConversionRpcOutcome.Rejected -> {
                    if (outcome.alreadyConverted) {
                        orderRepository.markOrderConverted(
                            orderId = order.id,
                            markPendingOverlay = false,
                            updatedAtEpochMs = nowMs,
                        )
                        return@withContext OrderConversionResult.DuplicateIgnored(
                            orderId = order.id,
                            message = outcome.message,
                        )
                    }
                    return@withContext OrderConversionResult.Rejected(outcome.message)
                }

                is OrderConversionRpcOutcome.AuthRequired -> {
                    return@withContext OrderConversionResult.Rejected(outcome.message)
                }

                is OrderConversionRpcOutcome.RpcMissing -> {
                    return@withContext queueOfflineOrderConversion(
                        order = order,
                        rpcRequest = rpcRequest,
                        nowMs = nowMs,
                        fallbackReason = outcome.message,
                    )
                }

                is OrderConversionRpcOutcome.NetworkOrTransientError -> {
                    return@withContext queueOfflineOrderConversion(
                        order = order,
                        rpcRequest = rpcRequest,
                        nowMs = nowMs,
                        fallbackReason = "Offline fallback: ${outcome.message}",
                    )
                }
            }
        }

        queueOfflineOrderConversion(
            order = order,
            rpcRequest = rpcRequest,
            nowMs = nowMs,
            fallbackReason = if (isSupabaseConfigured) {
                "Device offline; order conversion queued for automatic sync."
            } else {
                "Supabase not configured; order conversion saved locally."
            },
        )
    }

    private suspend fun queueOfflineOrderConversion(
        order: StoreOrderRecord,
        rpcRequest: AndroidOrderConversionRpcRequest,
        nowMs: Long,
        fallbackReason: String,
    ): OrderConversionResult.SavedOffline {
        val totalAmount = rpcRequest.items.sumOf { it.lineTotal }
        val localSale = buildLocalSaleEntity(
            clientSaleId = rpcRequest.clientConversionId,
            idempotencyKey = rpcRequest.idempotencyKey,
            serverSaleId = null,
            totalAmount = totalAmount,
            paymentMethod = rpcRequest.paymentMethod,
            actorId = rpcRequest.actorId,
            deviceId = rpcRequest.deviceId,
            saleDateIso = rpcRequest.convertedAtIso,
            createdAtEpochMs = nowMs,
            syncStatus = SyncState.LOCAL_PENDING,
        )
        val localItems = buildLocalSaleItemEntities(rpcRequest.clientConversionId, rpcRequest.items)
        val stockDeductions = rpcRequest.items.map { it.productId to it.quantity }
        val payloadJson = SyncQueuePolicy.serializeOrderConversionRpcRequest(rpcRequest)
        val syncEvent = SyncQueueEventEntity(
            eventId = "sync-ordconv-${rpcRequest.clientConversionId}",
            eventType = SyncEventType.ORDER_TO_SALE,
            entityId = rpcRequest.clientConversionId,
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
            operation = "ORDER_CONVERTED_TO_SALE_QUEUED_OFFLINE",
            entityType = "ORDER",
            entityId = order.id,
            beforeJson = JSONObject().put("status", order.rawStatus).toString(),
            afterJson = payloadJson,
            reason = fallbackReason,
            syncStatus = SyncState.LOCAL_PENDING,
            occurredAtEpochMs = nowMs,
        )

        syncQueueDao.commitOrderConversionOfflineAtomically(
            sale = localSale,
            items = localItems,
            stockDeductions = stockDeductions,
            syncEvent = syncEvent,
            auditEvent = audit,
            updatedAtEpochMs = nowMs,
            saleDao = saleDao,
            productDao = productDao,
            auditDao = auditDao,
        )
        orderRepository.markOrderConverted(
            orderId = order.id,
            markPendingOverlay = true,
            updatedAtEpochMs = nowMs,
        )
        onOfflineEventQueued()

        return OrderConversionResult.SavedOffline(
            orderId = order.id,
            totalAmount = totalAmount,
            itemsCount = rpcRequest.items.size,
            reason = fallbackReason,
        )
    }

    private fun buildLocalSaleEntity(
        clientSaleId: String,
        idempotencyKey: String,
        serverSaleId: Long?,
        totalAmount: Double,
        paymentMethod: String,
        actorId: String,
        deviceId: String,
        saleDateIso: String,
        createdAtEpochMs: Long,
        syncStatus: String,
    ): LocalSaleEntity {
        return LocalSaleEntity(
            clientSaleId = clientSaleId,
            serverSaleId = serverSaleId,
            idempotencyKey = idempotencyKey,
            totalAmount = totalAmount,
            paymentMethod = paymentMethod,
            actorId = actorId,
            deviceId = deviceId,
            source = "ANDROID_ORDER",
            saleDateIso = saleDateIso,
            createdAtEpochMs = createdAtEpochMs,
            syncStatus = syncStatus,
        )
    }

    private fun buildLocalSaleItemEntities(
        clientSaleId: String,
        items: List<ResolvedOrderSaleLine>,
    ): List<LocalSaleItemEntity> {
        return items.mapIndexed { idx, line ->
            LocalSaleItemEntity(
                id = "$clientSaleId-item-$idx",
                clientSaleId = clientSaleId,
                productId = line.productId,
                productNameSnapshot = line.productName,
                quantity = line.quantity,
                unitSellPrice = line.unitPrice,
                originalSellPrice = line.unitPrice,
                priceOverrideReason = null,
                lineTotal = line.lineTotal,
            )
        }
    }
}
