package com.emofah.agencies.financial

import android.content.Context
import com.emofah.agencies.data.local.entities.PendingFinancialEventEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class MpesaInflowLinkTarget(val label: String) {
    SALE("Sale"),
    SERVICE("Service"),
    DISMISSED("Other / Dismissed"),
}

data class MpesaInflowLinkRecord(
    val eventId: String,
    val transactionReference: String?,
    val target: MpesaInflowLinkTarget,
    val linkedRecordId: String,
    val summary: String,
    val linkedAtEpochMs: Long,
)

/**
 * Pure JVM policy for single-use attribution of incoming M-Pesa payments to a Sale or Service.
 * Prevents the same M-Pesa payment (matched by eventId OR transactionReference) from being linked twice.
 */
object MpesaInflowAttributionPolicy {

    fun isMpesaInflowEvent(event: PendingFinancialEventEntity): Boolean {
        if (!event.direction.equals("INFLOW", ignoreCase = true)) return false
        return event.source == "MPESA_SMS" || event.source == "MPESA_PASTE"
    }

    fun normalizeRef(ref: String?): String? {
        return ref?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
    }

    fun findExistingLink(
        event: PendingFinancialEventEntity,
        linksByEventId: Map<String, MpesaInflowLinkRecord>,
    ): MpesaInflowLinkRecord? {
        linksByEventId[event.eventId]?.let { return it }
        val normalizedRef = normalizeRef(event.transactionReference) ?: return null
        return linksByEventId.values.firstOrNull {
            normalizeRef(it.transactionReference) == normalizedRef
        }
    }

    fun findLatestUnlinkedInflow(
        events: List<PendingFinancialEventEntity>,
        linksByEventId: Map<String, MpesaInflowLinkRecord>,
    ): PendingFinancialEventEntity? {
        return events
            .filter { isMpesaInflowEvent(it) && findExistingLink(it, linksByEventId) == null }
            .maxByOrNull { it.eventTimestampEpochMs }
    }

    fun encodeLinksToJson(links: Collection<MpesaInflowLinkRecord>): String {
        val arr = JSONArray()
        for (link in links.sortedByDescending { it.linkedAtEpochMs }.take(300)) {
            arr.put(
                JSONObject().apply {
                    put("event_id", link.eventId)
                    put("transaction_reference", link.transactionReference ?: JSONObject.NULL)
                    put("target", link.target.name)
                    put("linked_record_id", link.linkedRecordId)
                    put("summary", link.summary)
                    put("linked_at_epoch_ms", link.linkedAtEpochMs)
                }
            )
        }
        return arr.toString()
    }

    fun decodeLinksFromJson(raw: String?): Map<String, MpesaInflowLinkRecord> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val arr = JSONArray(raw)
            val result = linkedMapOf<String, MpesaInflowLinkRecord>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val eventId = obj.optString("event_id", "").trim()
                if (eventId.isBlank()) continue
                val targetName = obj.optString("target", "")
                val target = MpesaInflowLinkTarget.entries.firstOrNull { it.name == targetName }
                    ?: continue
                val ref = obj.optString("transaction_reference", "")
                    .takeIf { !obj.isNull("transaction_reference") && it.isNotBlank() && it != "null" }
                result[eventId] = MpesaInflowLinkRecord(
                    eventId = eventId,
                    transactionReference = ref,
                    target = target,
                    linkedRecordId = obj.optString("linked_record_id", ""),
                    summary = obj.optString("summary", ""),
                    linkedAtEpochMs = obj.optLong("linked_at_epoch_ms", 0L),
                )
            }
            result.toMap()
        }.getOrDefault(emptyMap())
    }
}

/**
 * Persists M-Pesa inflow -> Sale/Service links locally without altering Room schema version.
 */
class MpesaInflowAttributionStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val _linksFlow = MutableStateFlow(readAllInternal())
    val linksFlow: StateFlow<Map<String, MpesaInflowLinkRecord>> = _linksFlow.asStateFlow()

    fun findLink(event: PendingFinancialEventEntity): MpesaInflowLinkRecord? {
        return MpesaInflowAttributionPolicy.findExistingLink(event, _linksFlow.value)
    }

    fun isLinked(event: PendingFinancialEventEntity): Boolean = findLink(event) != null

    /**
     * Atomically records that this M-Pesa inflow was used for a Sale, Service, or dismissed.
     * Returns false if this M-Pesa payment (or its reference code) is already linked.
     */
    fun tryRecordLink(
        event: PendingFinancialEventEntity,
        target: MpesaInflowLinkTarget,
        linkedRecordId: String,
        summary: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Boolean {
        synchronized(lock) {
            val current = readAllInternal().toMutableMap()
            if (MpesaInflowAttributionPolicy.findExistingLink(event, current) != null) {
                return false
            }
            val record = MpesaInflowLinkRecord(
                eventId = event.eventId,
                transactionReference = MpesaInflowAttributionPolicy.normalizeRef(event.transactionReference),
                target = target,
                linkedRecordId = linkedRecordId,
                summary = summary,
                linkedAtEpochMs = nowEpochMs,
            )
            current[event.eventId] = record
            val snapshot = current.toMap()
            prefs.edit()
                .putString(KEY_LINKS_JSON, MpesaInflowAttributionPolicy.encodeLinksToJson(snapshot.values))
                .apply()
            _linksFlow.value = snapshot
            return true
        }
    }

    private fun readAllInternal(): Map<String, MpesaInflowLinkRecord> {
        return MpesaInflowAttributionPolicy.decodeLinksFromJson(prefs.getString(KEY_LINKS_JSON, null))
    }

    companion object {
        private const val PREFS_NAME = "emofah_mpesa_inflow_links"
        private const val KEY_LINKS_JSON = "links_json_v1"
    }
}
