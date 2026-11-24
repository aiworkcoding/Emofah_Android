package com.emofah.agencies.financial

import com.emofah.agencies.data.local.EmofahDatabase
import com.emofah.agencies.data.local.entities.LocalAuditEventEntity
import com.emofah.agencies.data.local.entities.PendingFinancialEventEntity
import com.emofah.agencies.data.local.entities.SyncQueueEventEntity
import com.emofah.agencies.data.local.entities.SyncState
import com.emofah.agencies.data.sync.SyncEventType
import com.emofah.agencies.data.sync.SyncQueueStatus
import com.emofah.agencies.sales.SaleTransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

sealed class MpesaCaptureOutcome {
    data class Captured(val event: PendingFinancialEventEntity) : MpesaCaptureOutcome()
    data class DuplicateIgnored(val referenceCode: String) : MpesaCaptureOutcome()
    data class IgnoredNonFinancial(val reason: String) : MpesaCaptureOutcome()
    data object ConsentNotGranted : MpesaCaptureOutcome()
}

sealed class ManualFinancialOutcome {
    data class SavedLocally(val event: PendingFinancialEventEntity) : ManualFinancialOutcome()
    data class Rejected(val message: String) : ManualFinancialOutcome()
}

/**
 * Stage 5.2 – 5.5 — Consent-gated M-Pesa message monitoring, local capture,
 * duplicate protection, and queue-backed Supabase synchronisation.
 * Android deliberately does NOT finalize business accounting classifications;
 * Desktop rules classify captured events after synchronisation.
 */
class MpesaMonitoringRepository(
    private val database: EmofahDatabase,
    private val monitoringPrefs: FinancialMonitoringPrefs,
    private val onOfflineEventQueued: () -> Unit = {},
    private val clockEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    private val financialDao = database.financialAndDraftDao()
    private val syncQueueDao = database.syncQueueDao()
    private val auditDao = database.auditDao()

    fun observePendingFinancialEvents(): Flow<List<PendingFinancialEventEntity>> =
        financialDao.observeFinancialEvents()

    suspend fun processIncomingSms(
        sender: String,
        body: String,
        actorId: String,
        deviceId: String,
        requireConsentCheck: Boolean = true,
        allowManualPasteOverride: Boolean = false,
    ): MpesaCaptureOutcome = withContext(Dispatchers.IO) {
        if (requireConsentCheck && !monitoringPrefs.isConsentGranted()) {
            return@withContext MpesaCaptureOutcome.ConsentNotGranted
        }

        when (
            val parsedOutcome = MpesaMessageParser.parseAndValidate(
                sender = sender,
                body = body,
                allowManualPasteOverride = allowManualPasteOverride,
            )
        ) {
            is MpesaParseOutcome.Rejected -> {
                monitoringPrefs.recordIgnoredNonFinancial()
                MpesaCaptureOutcome.IgnoredNonFinancial(parsedOutcome.reason)
            }

            is MpesaParseOutcome.Parsed -> {
                val parsed = parsedOutcome.data
                val existing = financialDao.findByTransactionReference(parsed.transactionCode)
                if (existing != null) {
                    return@withContext MpesaCaptureOutcome.DuplicateIgnored(parsed.transactionCode)
                }

                val now = clockEpochMs()
                val eventId = "fin-mpesa-${parsed.transactionCode.lowercase()}"
                val idempotencyKey = "idem-mpesa-${parsed.transactionCode.uppercase()}"
                val source = if (allowManualPasteOverride) "MPESA_PASTE" else "MPESA_SMS"
                val occurredAtIso = SaleTransactionRepository.formatIsoUtc(now)

                val warningsArray = JSONArray().apply {
                    parsed.validationWarnings.forEach { put(it) }
                }
                val parsedJson = JSONObject().apply {
                    put("sender", parsed.sender)
                    put("reference", parsed.transactionCode)
                    put("channel", parsed.channel.code)
                    put("channel_label", parsed.channel.label)
                    put("amount", parsed.amount)
                    put("direction", parsed.direction)
                    put("counterparty", parsed.counterparty ?: JSONObject.NULL)
                    put("counterparty_account", parsed.counterpartyAccount ?: JSONObject.NULL)
                    put("transaction_cost", parsed.transactionCost ?: JSONObject.NULL)
                    put("new_balance", parsed.newBalance ?: JSONObject.NULL)
                    put("occurred_text", parsed.occurredText ?: JSONObject.NULL)
                    put("parse_status", parsed.parseStatus.name)
                    put("validation_warnings", warningsArray)
                    put("classification_authority", "DESKTOP_MANAGED")
                }.toString()

                val syncPayload = JSONObject().apply {
                    put("client_event_id", eventId)
                    put("idempotency_key", idempotencyKey)
                    put("event_type", FinancialEventType.MPESA_SMS_EVENT.code)
                    put("direction", parsed.direction)
                    put("amount", parsed.amount)
                    put("currency", parsed.currency)
                    put("reference_code", parsed.transactionCode)
                    put("channel", parsed.channel.code)
                    put("counterparty", parsed.counterparty ?: JSONObject.NULL)
                    put("counterparty_account", parsed.counterpartyAccount ?: JSONObject.NULL)
                    put("transaction_cost", parsed.transactionCost ?: JSONObject.NULL)
                    put("new_balance", parsed.newBalance ?: JSONObject.NULL)
                    put("occurred_text", parsed.occurredText ?: JSONObject.NULL)
                    put("raw_evidence", parsed.rawBody)
                    put("parsed_metadata_json", parsedJson)
                    put("parse_status", parsed.parseStatus.name)
                    put("source", source)
                    put("actor_id", actorId)
                    put("device_id", deviceId)
                    put("notes", parsed.channel.label)
                    put("occurred_at", occurredAtIso)
                }.toString()

                val reviewStatus = if (parsed.parseStatus == MpesaParseStatus.VALID_PARSED) {
                    "PENDING_DESKTOP_RULES"
                } else {
                    "NEEDS_MANUAL_FIX"
                }

                val entity = PendingFinancialEventEntity(
                    eventId = eventId,
                    source = source,
                    amount = parsed.amount,
                    currency = parsed.currency,
                    transactionReference = parsed.transactionCode,
                    counterparty = parsed.counterparty,
                    direction = parsed.direction,
                    rawEvidence = parsed.rawBody,
                    parsedJson = parsedJson,
                    provisionalCategory = "PENDING_DESKTOP_RULES",
                    classificationConfidence = parsed.confidence,
                    reviewStatus = reviewStatus,
                    syncStatus = SyncState.LOCAL_PENDING,
                    eventTimestampEpochMs = now,
                    classificationStatus = "PENDING_CLASSIFICATION",
                    excludesFromBalance = false,
                )

                val syncEvent = SyncQueueEventEntity(
                    eventId = "sync-$eventId",
                    eventType = SyncEventType.FINANCIAL_EVENT,
                    entityId = eventId,
                    dependencyEventId = null,
                    payloadJson = syncPayload,
                    actorId = actorId,
                    deviceId = deviceId,
                    status = SyncQueueStatus.QUEUED,
                    retryCount = 0,
                    nextRetryAtEpochMs = now,
                    createdAtEpochMs = now,
                )

                val auditEvent = LocalAuditEventEntity(
                    auditId = "audit-${UUID.randomUUID()}",
                    actorId = actorId,
                    deviceId = deviceId,
                    operation = if (allowManualPasteOverride) "MPESA_MESSAGE_PARSED" else "MPESA_SMS_PARSED",
                    entityType = "FINANCIAL_EVENT",
                    entityId = eventId,
                    beforeJson = null,
                    afterJson = parsedJson,
                    reason = "Parsed ${parsed.channel.label} (${parsed.parseStatus.name}) ${parsed.transactionCode}",
                    syncStatus = SyncState.LOCAL_PENDING,
                    occurredAtEpochMs = now,
                )

                syncQueueDao.commitFinancialEventOfflineAtomically(
                    financialEvent = entity,
                    syncEvent = syncEvent,
                    auditEvent = auditEvent,
                    financialAndDraftDao = financialDao,
                    auditDao = auditDao,
                )
                monitoringPrefs.recordCapturedMessage(now)
                onOfflineEventQueued()
                MpesaCaptureOutcome.Captured(entity)
            }
        }
    }

    suspend fun recordManualFinancialEvent(draft: FinancialEventDraft): ManualFinancialOutcome =
        withContext(Dispatchers.IO) {
            val validation = FinancialEventValidator.validate(draft)
            if (validation.isFailure) {
                return@withContext ManualFinancialOutcome.Rejected(
                    validation.exceptionOrNull()?.localizedMessage ?: "Invalid financial entry."
                )
            }

            val ref = draft.referenceCode?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
            if (ref != null) {
                val existing = financialDao.findByTransactionReference(ref)
                if (existing != null) {
                    return@withContext ManualFinancialOutcome.Rejected(
                        "Reference '$ref' is already recorded locally."
                    )
                }
            }

            val now = clockEpochMs()
            val eventId = "fin-man-${UUID.randomUUID()}"
            val occurredAtIso = SaleTransactionRepository.formatIsoUtc(now)
            val direction = when (draft.eventType) {
                FinancialEventType.CASH_SALE,
                FinancialEventType.M_PESA_RECEIVED,
                -> "INFLOW"
                else -> "OUTFLOW"
            }
            val cleanNote = draft.notes.trim().takeIf { it.isNotBlank() }
            val parsedMeta = JSONObject().apply {
                put("event_type", draft.eventType.code)
                put("amount", draft.amount)
                put("idempotency_key", draft.idempotencyKey)
                put("classification_authority", "DESKTOP_MANAGED")
                if (cleanNote != null) put("operational_note", cleanNote)
            }.toString()

            val syncPayload = JSONObject().apply {
                put("client_event_id", eventId)
                put("idempotency_key", draft.idempotencyKey)
                put("event_type", draft.eventType.code)
                put("direction", direction)
                put("amount", draft.amount)
                put("currency", "KES")
                put("reference_code", ref ?: JSONObject.NULL)
                put("channel", draft.eventType.code)
                put("counterparty", cleanNote ?: JSONObject.NULL)
                put("counterparty_account", JSONObject.NULL)
                put("transaction_cost", JSONObject.NULL)
                put("new_balance", JSONObject.NULL)
                put("occurred_text", JSONObject.NULL)
                put("raw_evidence", cleanNote ?: JSONObject.NULL)
                put("parsed_metadata_json", parsedMeta)
                put("parse_status", "MANUAL_ENTRY")
                put("source", "MANUAL")
                put("actor_id", draft.actorId)
                put("device_id", draft.deviceId)
                put("notes", cleanNote ?: draft.eventType.label)
                put("occurred_at", occurredAtIso)
            }.toString()

            val entity = PendingFinancialEventEntity(
                eventId = eventId,
                source = "MANUAL",
                amount = draft.amount,
                currency = "KES",
                transactionReference = ref,
                counterparty = cleanNote,
                direction = direction,
                rawEvidence = cleanNote,
                parsedJson = parsedMeta,
                provisionalCategory = "PENDING_DESKTOP_RULES",
                classificationConfidence = 1.0f,
                reviewStatus = "PENDING_DESKTOP_RULES",
                syncStatus = SyncState.LOCAL_PENDING,
                eventTimestampEpochMs = now,
                classificationStatus = "PENDING_CLASSIFICATION",
                excludesFromBalance = false,
            )

            val syncEvent = SyncQueueEventEntity(
                eventId = "sync-$eventId",
                eventType = SyncEventType.FINANCIAL_EVENT,
                entityId = eventId,
                dependencyEventId = null,
                payloadJson = syncPayload,
                actorId = draft.actorId,
                deviceId = draft.deviceId,
                status = SyncQueueStatus.QUEUED,
                retryCount = 0,
                nextRetryAtEpochMs = now,
                createdAtEpochMs = now,
            )

            val auditEvent = LocalAuditEventEntity(
                auditId = "audit-${UUID.randomUUID()}",
                actorId = draft.actorId,
                deviceId = draft.deviceId,
                operation = "MANUAL_FINANCIAL_EVENT_RECORDED",
                entityType = "FINANCIAL_EVENT",
                entityId = eventId,
                beforeJson = null,
                afterJson = parsedMeta,
                reason = "Manual ${draft.eventType.label} captured for Desktop classification",
                syncStatus = SyncState.LOCAL_PENDING,
                occurredAtEpochMs = now,
            )

            syncQueueDao.commitFinancialEventOfflineAtomically(
                financialEvent = entity,
                syncEvent = syncEvent,
                auditEvent = auditEvent,
                financialAndDraftDao = financialDao,
                auditDao = auditDao,
            )
            onOfflineEventQueued()
            ManualFinancialOutcome.SavedLocally(entity)
        }
}
