package com.emofah.agencies.sales

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.emofah.agencies.data.SupabaseConfig
import com.emofah.agencies.data.local.EmofahDatabase
import com.emofah.agencies.data.local.entities.LocalAuditEventEntity
import com.emofah.agencies.data.local.entities.LocalSaleEntity
import com.emofah.agencies.data.local.entities.LocalSaleItemEntity
import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import com.emofah.agencies.data.sync.SyncEventType
import com.emofah.agencies.data.sync.SyncQueuePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

interface ConnectivityChecker {
    fun isOnline(): Boolean
}

class AndroidConnectivityChecker(context: Context) : ConnectivityChecker {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override fun isOnline(): Boolean {
        val cm = connectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

sealed class RecordSaleOutcome {
    data class SuccessOnline(
        val clientSaleId: String,
        val serverSaleId: Long,
        val remainingServerQty: Int,
        val alreadyCommitted: Boolean,
        val totalAmount: Double,
        val paymentMethod: PaymentMethod,
    ) : RecordSaleOutcome()

    data class SavedOffline(
        val clientSaleId: String,
        val queuedEventId: String,
        val provisionalRemainingQty: Int,
        val totalAmount: Double,
        val paymentMethod: PaymentMethod,
        val reason: String,
    ) : RecordSaleOutcome()

    data class DuplicateIgnored(
        val clientSaleId: String,
        val syncStatus: String,
        val serverSaleId: Long?,
    ) : RecordSaleOutcome()

    data class Rejected(
        val message: String,
        val serverAvailableQty: Int? = null,
    ) : RecordSaleOutcome()
}

data class ConstructedSaleArtifacts(
    val sale: LocalSaleEntity,
    val items: List<LocalSaleItemEntity>,
    val syncEvent: SyncQueueEventEntity,
    val auditEvent: LocalAuditEventEntity,
    val rpcRequest: AndroidSaleRpcRequest,
)

/**
 * Stage 2.2 (Actions 2.2.1 – 2.2.4):
 * Executes one-touch sale recording:
 * - Constructs transaction with stable UUID, idempotency key, actor/device, source=ANDROID.
 * - Online: invokes atomic Supabase RPC `record_android_sale` and persists SERVER_CONFIRMED state.
 * - Offline / transient error / missing RPC: commits atomically in Room (`LOCAL_PENDING`) with
 *   `SyncQueueEventEntity` + provisional stock deduction + `LocalAuditEventEntity`.
 * - Rejections (e.g., INSUFFICIENT_STOCK on server): fails honestly without faking success.
 */
class SaleTransactionRepository(
    private val database: EmofahDatabase,
    private val identityProvider: DeviceIdentityProvider,
    private val connectivityChecker: ConnectivityChecker,
    private val rpcGateway: SaleRpcGateway = SupabaseSaleRpcClient(),
    private val isSupabaseConfigured: Boolean = SupabaseConfig.isConfigured,
    private val onOfflineEventQueued: () -> Unit = {},
) {
    private val saleDao = database.saleDao()
    private val productDao = database.productDao()

    suspend fun recordSale(
        draft: PreparedSaleDraft,
        originalSellPrice: Double,
    ): RecordSaleOutcome = withContext(Dispatchers.IO) {
        // Action 2.2.4: Fast local duplicate protection before hitting network or SQLite inserts
        val existingLocal = saleDao.getSaleByIdempotencyKey(draft.idempotencyKey)
        if (existingLocal != null) {
            return@withContext RecordSaleOutcome.DuplicateIgnored(
                clientSaleId = existingLocal.clientSaleId,
                syncStatus = existingLocal.syncStatus,
                serverSaleId = existingLocal.serverSaleId,
            )
        }

        // Domain guard: block non-positive quantities or invalid prices before Room or network commit
        val effectiveOriginalPrice = if (draft.originalSellPrice > 0.0) draft.originalSellPrice else originalSellPrice
        if (draft.quantity <= 0) {
            return@withContext RecordSaleOutcome.Rejected("Quantity must be at least 1.")
        }
        if (!effectiveOriginalPrice.isFinite() || effectiveOriginalPrice <= 0.0 ||
            !draft.unitSellPrice.isFinite() || draft.unitSellPrice <= 0.0 ||
            !draft.totalAmount.isFinite() || draft.totalAmount <= 0.0
        ) {
            return@withContext RecordSaleOutcome.Rejected("Selling price and total amount must be greater than zero.")
        }

        // Action 2.3.2: Enforce role-based price override policy before DB or network calls
        val actorRole = identityProvider.getStaffRole()
        val priceCheck = PriceOverridePolicy.validatePriceException(
            originalPrice = effectiveOriginalPrice,
            requestedPrice = draft.unitSellPrice,
            role = actorRole,
            overrideReason = draft.priceOverrideReason,
            requireReason = true,
        )
        if (!priceCheck.isAllowed) {
            return@withContext RecordSaleOutcome.Rejected(
                message = priceCheck.errorMessage ?: "Unauthorized price override.",
            )
        }

        val actorId = identityProvider.getActorId()
        val deviceId = identityProvider.getDeviceId()
        val parentDraftEventId = if (draft.productId < 0L) {
            database.syncQueueDao()
                .getEventsByType(SyncEventType.PRODUCT_DRAFT)
                .firstOrNull { evt ->
                    SyncQueuePolicy.parseProductDraftPayload(evt)?.localTempProductId == draft.productId
                }
                ?.eventId
        } else {
            null
        }
        val artifacts = buildTransactionArtifacts(
            draft = draft,
            originalSellPrice = effectiveOriginalPrice,
            actorId = actorId,
            deviceId = deviceId,
            dependencyEventId = parentDraftEventId,
            actorRole = actorRole,
        )

        if (draft.productId < 0L) {
            return@withContext commitOfflineFallback(
                artifacts = artifacts,
                draft = draft,
                reason = "Product is an offline draft — sale queued to sync immediately after the new product syncs.",
            )
        }

        val shouldTryOnline = isSupabaseConfigured && connectivityChecker.isOnline()
        if (shouldTryOnline) {
            when (val rpcResult = rpcGateway.submitSale(artifacts.rpcRequest)) {
                is SaleRpcOutcome.CommittedOnline -> {
                    val confirmedSale = artifacts.sale.copy(
                        serverSaleId = rpcResult.serverSaleId,
                        syncStatus = SyncState.SERVER_CONFIRMED,
                    )
                    val confirmedAudit = artifacts.auditEvent.copy(
                        syncStatus = SyncState.SERVER_CONFIRMED,
                        afterJson = JSONObject().apply {
                            put("client_sale_id", confirmedSale.clientSaleId)
                            put("server_sale_id", rpcResult.serverSaleId)
                            put("sync_status", SyncState.SERVER_CONFIRMED)
                            put("remaining_qty", rpcResult.remainingQty)
                        }.toString(),
                    )

                    saleDao.commitOnlineSaleAtomically(
                        sale = confirmedSale,
                        items = artifacts.items,
                        auditEvent = confirmedAudit,
                        productId = draft.productId,
                        remainingServerQty = rpcResult.remainingQty,
                        serverUpdatedAtEpochMs = System.currentTimeMillis(),
                        productDao = productDao,
                    )

                    return@withContext RecordSaleOutcome.SuccessOnline(
                        clientSaleId = confirmedSale.clientSaleId,
                        serverSaleId = rpcResult.serverSaleId,
                        remainingServerQty = rpcResult.remainingQty,
                        alreadyCommitted = rpcResult.alreadyCommitted,
                        totalAmount = confirmedSale.totalAmount,
                        paymentMethod = draft.paymentMethod,
                    )
                }

                is SaleRpcOutcome.Rejected -> {
                    // Server rejected sale (e.g. stock already sold elsewhere): fail honestly
                    return@withContext RecordSaleOutcome.Rejected(
                        message = rpcResult.message,
                        serverAvailableQty = rpcResult.serverAvailableQty,
                    )
                }

                is SaleRpcOutcome.RpcMissing -> {
                    return@withContext commitOfflineFallback(
                        artifacts = artifacts,
                        draft = draft,
                        reason = "Saved locally offline — deploy Supabase record_android_sale SQL migration to sync to cloud.",
                    )
                }

                is SaleRpcOutcome.AuthRequired -> {
                    return@withContext commitOfflineFallback(
                        artifacts = artifacts,
                        draft = draft,
                        reason = "Sign-in required to sync (${rpcResult.message}) — saved locally offline in Room queue.",
                    )
                }

                is SaleRpcOutcome.NetworkOrTransientError -> {
                    return@withContext commitOfflineFallback(
                        artifacts = artifacts,
                        draft = draft,
                        reason = "Network unavailable (${rpcResult.message}) — saved locally offline and queued for sync.",
                    )
                }
            }
        }

        val offlineReason = if (!isSupabaseConfigured) {
            "Supabase not configured — saved locally in Room queue."
        } else {
            "Device is offline — saved locally and queued for background sync."
        }
        commitOfflineFallback(
            artifacts = artifacts,
            draft = draft,
            reason = offlineReason,
        )
    }

    private suspend fun commitOfflineFallback(
        artifacts: ConstructedSaleArtifacts,
        draft: PreparedSaleDraft,
        reason: String,
    ): RecordSaleOutcome.SavedOffline {
        saleDao.commitOfflineSaleAtomically(
            sale = artifacts.sale,
            items = artifacts.items,
            syncEvent = artifacts.syncEvent,
            auditEvent = artifacts.auditEvent,
            productDao = productDao,
        )
        onOfflineEventQueued()

        val updatedProjection = productDao.getStockProjection(draft.productId)
        val provisionalQty = updatedProjection?.provisionalAvailableQty ?: 0

        return RecordSaleOutcome.SavedOffline(
            clientSaleId = artifacts.sale.clientSaleId,
            queuedEventId = artifacts.syncEvent.eventId,
            provisionalRemainingQty = provisionalQty,
            totalAmount = draft.totalAmount,
            paymentMethod = draft.paymentMethod,
            reason = reason,
        )
    }

    companion object {
        fun formatIsoUtc(epochMs: Long): String {
            return DateTimeFormatter.ISO_INSTANT.format(
                Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.UTC)
            )
        }

        fun buildTransactionArtifacts(
            draft: PreparedSaleDraft,
            originalSellPrice: Double,
            actorId: String,
            deviceId: String,
            dependencyEventId: String? = null,
            actorRole: StaffRole = StaffRole.CASHIER,
        ): ConstructedSaleArtifacts {
            val saleDateIso = formatIsoUtc(draft.createdAtEpochMs)
            val saleEntity = LocalSaleEntity(
                clientSaleId = draft.clientSaleId,
                serverSaleId = null,
                idempotencyKey = draft.idempotencyKey,
                totalAmount = draft.totalAmount,
                paymentMethod = draft.paymentMethod.name,
                actorId = actorId,
                deviceId = deviceId,
                source = "ANDROID",
                saleDateIso = saleDateIso,
                createdAtEpochMs = draft.createdAtEpochMs,
                syncStatus = SyncState.LOCAL_PENDING,
            )

            val isOverridden = kotlin.math.abs(draft.unitSellPrice - originalSellPrice) > PriceOverridePolicy.PRICE_EPSILON
            val cleanOverrideReason = if (isOverridden) {
                draft.priceOverrideReason?.trim()?.takeIf { it.isNotEmpty() }
            } else {
                null
            }

            val itemId = "${draft.clientSaleId}-item-1"
            val itemEntity = LocalSaleItemEntity(
                id = itemId,
                clientSaleId = draft.clientSaleId,
                productId = draft.productId,
                productNameSnapshot = draft.productName,
                quantity = draft.quantity,
                unitSellPrice = draft.unitSellPrice,
                originalSellPrice = originalSellPrice,
                priceOverrideReason = cleanOverrideReason,
                lineTotal = draft.totalAmount,
            )

            val syncPayload = JSONObject().apply {
                put("client_sale_id", draft.clientSaleId)
                put("idempotency_key", draft.idempotencyKey)
                put("product_id", draft.productId)
                put("qty_sold", draft.quantity)
                put("total_price", draft.totalAmount)
                put("unit_sell_price", draft.unitSellPrice)
                put("original_sell_price", originalSellPrice)
                put("price_override_reason", cleanOverrideReason ?: JSONObject.NULL)
                put("actor_role", actorRole.name)
                put("payment_method", draft.paymentMethod.name)
                put("actor_id", actorId)
                put("device_id", deviceId)
                put("source", "ANDROID")
                put("sale_date", saleDateIso)
                put(
                    "items",
                    JSONArray().put(
                        JSONObject().apply {
                            put("id", itemId)
                            put("product_id", draft.productId)
                            put("quantity", draft.quantity)
                            put("unit_sell_price", draft.unitSellPrice)
                            put("original_sell_price", originalSellPrice)
                            put("price_override_reason", cleanOverrideReason ?: JSONObject.NULL)
                            put("line_total", draft.totalAmount)
                        }
                    ),
                )
            }.toString()

            val syncEvent = SyncQueueEventEntity(
                eventId = "sync-sale-${draft.clientSaleId}",
                eventType = "SALE",
                entityId = draft.clientSaleId,
                dependencyEventId = dependencyEventId,
                payloadJson = syncPayload,
                actorId = actorId,
                deviceId = deviceId,
                status = "QUEUED",
                retryCount = 0,
                nextRetryAtEpochMs = draft.createdAtEpochMs,
                createdAtEpochMs = draft.createdAtEpochMs,
            )

            val beforeJson = if (isOverridden) {
                JSONObject().apply {
                    put("original_sell_price", originalSellPrice)
                }.toString()
            } else {
                null
            }

            val auditEvent = LocalAuditEventEntity(
                auditId = "audit-${UUID.randomUUID()}",
                actorId = actorId,
                deviceId = deviceId,
                operation = if (isOverridden) "SALE_RECORDED_PRICE_OVERRIDE" else "SALE_RECORDED",
                entityType = "SALE",
                entityId = draft.clientSaleId,
                beforeJson = beforeJson,
                afterJson = syncPayload,
                reason = if (isOverridden) {
                    "Price override (${actorRole.name}): ${cleanOverrideReason ?: "Authorized exception"}"
                } else {
                    "One-touch sale capture (${draft.paymentMethod.label})"
                },
                syncStatus = SyncState.LOCAL_PENDING,
                occurredAtEpochMs = draft.createdAtEpochMs,
            )

            val rpcRequest = AndroidSaleRpcRequest(
                productId = draft.productId,
                quantity = draft.quantity,
                totalAmount = draft.totalAmount,
                clientSaleId = draft.clientSaleId,
                idempotencyKey = draft.idempotencyKey,
                paymentMethod = draft.paymentMethod.name,
                actorId = actorId,
                deviceId = deviceId,
                saleDateIso = saleDateIso,
                unitSellPrice = draft.unitSellPrice,
                originalSellPrice = originalSellPrice,
                priceOverrideReason = cleanOverrideReason,
                actorRole = actorRole.name,
            )

            return ConstructedSaleArtifacts(
                sale = saleEntity,
                items = listOf(itemEntity),
                syncEvent = syncEvent,
                auditEvent = auditEvent,
                rpcRequest = rpcRequest,
            )
        }
    }
}
