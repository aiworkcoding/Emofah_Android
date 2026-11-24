package com.emofah.agencies.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Action 3.1.1 (7): Pending financial activity (M-Pesa SMS / manual money movement)
 * separating money movement from provisional classification and sync state.
 */
@Entity(
    tableName = "pending_financial_events",
    indices = [
        Index(value = ["transaction_reference"]),
        Index(value = ["sync_status"]),
        Index(value = ["review_status"]),
        Index(value = ["classification_status"]),
    ],
)
data class PendingFinancialEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "source")
    val source: String, // MPESA_SMS, MPESA_PASTE, MANUAL, ANDROID_SERVICE
    @ColumnInfo(name = "amount")
    val amount: Double,
    @ColumnInfo(name = "currency")
    val currency: String = "KES",
    @ColumnInfo(name = "transaction_reference")
    val transactionReference: String?,
    @ColumnInfo(name = "counterparty")
    val counterparty: String?,
    @ColumnInfo(name = "direction")
    val direction: String, // INFLOW, OUTFLOW, TRANSFER
    @ColumnInfo(name = "raw_evidence")
    val rawEvidence: String?,
    @ColumnInfo(name = "parsed_json")
    val parsedJson: String?,
    @ColumnInfo(name = "provisional_category")
    val provisionalCategory: String = "UNCLASSIFIED",
    @ColumnInfo(name = "classification_confidence")
    val classificationConfidence: Float = 0f,
    @ColumnInfo(name = "review_status")
    val reviewStatus: String = "NEEDS_REVIEW",
    @ColumnInfo(name = "sync_status")
    val syncStatus: String = SyncState.LOCAL_PENDING,
    @ColumnInfo(name = "event_timestamp_epoch_ms")
    val eventTimestampEpochMs: Long,
    @ColumnInfo(name = "classification_status")
    val classificationStatus: String = "PENDING_CLASSIFICATION",
    @ColumnInfo(name = "server_event_id")
    val serverEventId: String? = null,
    @ColumnInfo(name = "linked_sale_id")
    val linkedSaleId: Long? = null,
    @ColumnInfo(name = "linked_client_sale_id")
    val linkedClientSaleId: String? = null,
    @ColumnInfo(name = "linked_service_id")
    val linkedServiceId: String? = null,
    @ColumnInfo(name = "linked_client_service_id")
    val linkedClientServiceId: String? = null,
    @ColumnInfo(name = "linked_product_id")
    val linkedProductId: Long? = null,
    @ColumnInfo(name = "linked_product_name")
    val linkedProductName: String? = null,
    @ColumnInfo(name = "linked_quantity")
    val linkedQuantity: Int? = null,
    @ColumnInfo(name = "classification_note")
    val classificationNote: String? = null,
    @ColumnInfo(name = "classified_at_epoch_ms")
    val classifiedAtEpochMs: Long? = null,
    @ColumnInfo(name = "classified_by")
    val classifiedBy: String? = null,
    @ColumnInfo(name = "classified_device")
    val classifiedDevice: String? = null,
    @ColumnInfo(name = "excludes_from_balance")
    val excludesFromBalance: Boolean = false,
)

/**
 * Action 3.1.1 (9): Dependency-ordered offline synchronisation queue record.
 */
@Entity(
    tableName = "sync_queue_events",
    indices = [
        Index(value = ["status", "next_retry_at_epoch_ms"]),
        Index(value = ["dependency_event_id"]),
        Index(value = ["entity_id"]),
    ],
)
data class SyncQueueEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "event_type")
    val eventType: String, // PRODUCT_DRAFT, PRODUCT_IMAGE, SALE, STOCK_MOVEMENT, FINANCIAL_EVENT, ORDER_UPDATE, RECOGNITION_CORRECTION
    @ColumnInfo(name = "entity_id")
    val entityId: String,
    @ColumnInfo(name = "dependency_event_id")
    val dependencyEventId: String? = null,
    @ColumnInfo(name = "payload_json")
    val payloadJson: String,
    @ColumnInfo(name = "actor_id")
    val actorId: String,
    @ColumnInfo(name = "device_id")
    val deviceId: String,
    @ColumnInfo(name = "status")
    val status: String = "QUEUED", // QUEUED, IN_FLIGHT, SYNCED, FAILED_RETRYABLE, CONFLICT
    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,
    @ColumnInfo(name = "last_attempt_at_epoch_ms")
    val lastAttemptAtEpochMs: Long? = null,
    @ColumnInfo(name = "next_retry_at_epoch_ms")
    val nextRetryAtEpochMs: Long = 0L,
    @ColumnInfo(name = "failure_reason")
    val failureReason: String? = null,
    @ColumnInfo(name = "server_ack_id")
    val serverAckId: String? = null,
    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,
)

/**
 * Action 3.1.1 (10): Local audit trail record preserving before/after state and actor/device identity.
 */
@Entity(
    tableName = "local_audit_events",
    indices = [
        Index(value = ["entity_type", "entity_id"]),
        Index(value = ["occurred_at_epoch_ms"]),
    ],
)
data class LocalAuditEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "audit_id")
    val auditId: String,
    @ColumnInfo(name = "actor_id")
    val actorId: String,
    @ColumnInfo(name = "device_id")
    val deviceId: String,
    @ColumnInfo(name = "operation")
    val operation: String,
    @ColumnInfo(name = "entity_type")
    val entityType: String,
    @ColumnInfo(name = "entity_id")
    val entityId: String,
    @ColumnInfo(name = "before_json")
    val beforeJson: String? = null,
    @ColumnInfo(name = "after_json")
    val afterJson: String? = null,
    @ColumnInfo(name = "reason")
    val reason: String? = null,
    @ColumnInfo(name = "sync_status")
    val syncStatus: String = SyncState.LOCAL_PENDING,
    @ColumnInfo(name = "occurred_at_epoch_ms")
    val occurredAtEpochMs: Long,
)
