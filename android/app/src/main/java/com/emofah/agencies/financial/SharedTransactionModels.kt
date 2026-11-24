package com.emofah.agencies.financial

import com.emofah.agencies.data.local.entities.PendingFinancialEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import org.json.JSONObject
import kotlin.math.round

enum class ClassificationState(val code: String, val label: String) {
    PENDING_CLASSIFICATION("PENDING_CLASSIFICATION", "Awaiting Classification"),
    BUSINESS_PRODUCT("BUSINESS_PRODUCT", "Product Sale"),
    BUSINESS_SERVICE("BUSINESS_SERVICE", "Service"),
    NON_BUSINESS("NON_BUSINESS", "Non-Business (Excluded)");

    companion object {
        fun fromRaw(raw: String?, excludesFromBalance: Boolean = false): ClassificationState {
            if (excludesFromBalance) return NON_BUSINESS
            return when (raw?.trim()?.uppercase()) {
                "BUSINESS_PRODUCT" -> BUSINESS_PRODUCT
                "BUSINESS_SERVICE" -> BUSINESS_SERVICE
                "NON_BUSINESS", "REMOVED", "PERSONAL" -> NON_BUSINESS
                "PENDING_CLASSIFICATION", "PENDING_DESKTOP_RULES", "UNCLASSIFIED", "", null ->
                    PENDING_CLASSIFICATION
                else -> PENDING_CLASSIFICATION
            }
        }
    }
}

data class RemoteRecentSale(
    val saleId: Long,
    val productId: Long,
    val productName: String,
    val qtySold: Int,
    val totalPrice: Double,
    val saleDateIso: String,
    val alreadyLinkedEventId: String?,
)

data class RemoteFinancialTransaction(
    val serverEventId: String,
    val clientEventId: String?,
    val eventType: String,
    val direction: String,
    val amount: Double,
    val currency: String,
    val classificationStatus: ClassificationState,
    val referenceCode: String?,
    val channel: String?,
    val counterparty: String?,
    val counterpartyAccount: String?,
    val transactionCost: Double?,
    val newBalance: Double?,
    val occurredText: String?,
    val rawEvidence: String?,
    val parseStatus: String?,
    val source: String,
    val actorId: String?,
    val deviceId: String?,
    val notes: String?,
    val occurredAtIso: String,
    val occurredAtEpochMs: Long,
    val linkedSaleId: Long?,
    val linkedClientSaleId: String?,
    val linkedServiceId: String?,
    val linkedClientServiceId: String?,
    val linkedProductId: Long?,
    val linkedProductName: String?,
    val linkedQuantity: Int?,
    val classificationNote: String?,
    val classifiedAtEpochMs: Long?,
    val classifiedBy: String?,
    val classifiedDevice: String?,
    val excludesFromBalance: Boolean,
    val serviceTotalRevenue: Double?,
    val saleTotalPrice: Double?,
    val serviceLabel: String? = null,
)

data class BusinessBalanceReport(
    val businessBalance: Double,
    val businessProductNet: Double,
    val businessProductCount: Int,
    val businessServiceNet: Double,
    val businessServiceCount: Int,
    val pendingCount: Int,
    val pendingExcludedAmount: Double,
    val nonBusinessCount: Int,
    val nonBusinessExcludedAmount: Double,
    val localWaitingUploadCount: Int,
)

data class QueuedClassificationRequest(
    val clientEventId: String,
    val serverEventId: String?,
    val action: String, // PRODUCT, SERVICE, NON_BUSINESS
    val idempotencyKey: String,
    val productId: Long? = null,
    val quantity: Int = 1,
    val existingSaleId: Long? = null,
    val existingClientSaleId: String? = null,
    val unitSellPrice: Double? = null,
    val paymentMethod: String = "MPESA",
    val serviceType: String? = null,
    val serviceLabel: String? = null,
    val unitLabel: String? = null,
    val unitPrice: Double? = null,
    val discountAmount: Double = 0.0,
    val note: String? = null,
    val actorId: String = "android_staff",
    val deviceId: String = "android",
)

sealed class SharedClassificationOutcome {
    data class ClassifiedOnline(
        val event: PendingFinancialEventEntity,
        val message: String,
        val alreadyClassifiedByOtherDevice: Boolean = false,
    ) : SharedClassificationOutcome()

    data class QueuedOffline(
        val event: PendingFinancialEventEntity,
        val message: String,
    ) : SharedClassificationOutcome()

    data class Rejected(val message: String) : SharedClassificationOutcome()
}

/**
 * Pure JVM calculator enforcing Emofah's accounting balance rules:
 * - PENDING_CLASSIFICATION transactions NEVER alter the business balance.
 * - NON_BUSINESS transactions are excluded from the business balance while retaining original records.
 * - BUSINESS_PRODUCT and BUSINESS_SERVICE contribute once (net after discounts and fees).
 */
object BusinessBalanceCalculator {

    fun effectiveStatus(event: PendingFinancialEventEntity): ClassificationState {
        return ClassificationState.fromRaw(event.classificationStatus, event.excludesFromBalance)
    }

    fun isPendingClassification(event: PendingFinancialEventEntity): Boolean {
        return effectiveStatus(event) == ClassificationState.PENDING_CLASSIFICATION
    }

    fun canClassify(event: PendingFinancialEventEntity): Boolean {
        return isPendingClassification(event)
    }

    fun computeEventContribution(event: PendingFinancialEventEntity): Double {
        val status = effectiveStatus(event)
        if (status != ClassificationState.BUSINESS_PRODUCT &&
            status != ClassificationState.BUSINESS_SERVICE
        ) {
            return 0.0
        }
        val parsedObj = event.parsedJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        val fee = parsedObj?.takeIf { !it.isNull("transaction_cost") }
            ?.optDouble("transaction_cost", 0.0)
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: 0.0

        return when (event.direction.uppercase()) {
            "TRANSFER" -> 0.0
            "OUTFLOW" -> -(event.amount + fee)
            else -> {
                val netAmount = parsedObj?.takeIf { !it.isNull("classified_net_amount") }
                    ?.optDouble("classified_net_amount", event.amount)
                    ?.takeIf { it.isFinite() && it >= 0.0 }
                    ?: event.amount
                netAmount - fee
            }
        }
    }

    fun compute(events: List<PendingFinancialEventEntity>): BusinessBalanceReport {
        val seenKeys = HashSet<String>()
        var productNet = 0.0
        var productCount = 0
        var serviceNet = 0.0
        var serviceCount = 0
        var pendingCount = 0
        var pendingAmount = 0.0
        var nonBizCount = 0
        var nonBizAmount = 0.0
        var localWaitingUploadCount = 0

        for (evt in events) {
            val dedupKey = evt.serverEventId?.takeIf { it.isNotBlank() }
                ?: evt.transactionReference?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
                ?: evt.eventId
            if (!seenKeys.add(dedupKey)) continue

            if (evt.syncStatus != SyncState.SERVER_CONFIRMED) {
                localWaitingUploadCount += 1
            }

            when (effectiveStatus(evt)) {
                ClassificationState.PENDING_CLASSIFICATION -> {
                    pendingCount += 1
                    pendingAmount += evt.amount
                }

                ClassificationState.NON_BUSINESS -> {
                    nonBizCount += 1
                    nonBizAmount += evt.amount
                }

                ClassificationState.BUSINESS_PRODUCT -> {
                    productCount += 1
                    productNet += computeEventContribution(evt)
                }

                ClassificationState.BUSINESS_SERVICE -> {
                    serviceCount += 1
                    serviceNet += computeEventContribution(evt)
                }
            }
        }

        val roundedProduct = round(productNet * 100.0) / 100.0
        val roundedService = round(serviceNet * 100.0) / 100.0
        val roundedTotal = round((roundedProduct + roundedService) * 100.0) / 100.0

        return BusinessBalanceReport(
            businessBalance = roundedTotal,
            businessProductNet = roundedProduct,
            businessProductCount = productCount,
            businessServiceNet = roundedService,
            businessServiceCount = serviceCount,
            pendingCount = pendingCount,
            pendingExcludedAmount = round(pendingAmount * 100.0) / 100.0,
            nonBusinessCount = nonBizCount,
            nonBusinessExcludedAmount = round(nonBizAmount * 100.0) / 100.0,
            localWaitingUploadCount = localWaitingUploadCount,
        )
    }
}
