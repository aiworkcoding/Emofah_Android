package com.emofah.agencies.recognition

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

data class CachedCloudLookup(
    val imageKey: String,
    val productIds: List<Long>,
    val extractedLabel: String?,
    val cachedAtEpochMs: Long,
)

/**
 * Stage 1.4 (Action 1.4.2):
 * Persists deduplicated cloud recognition cache (keyed by image hash) and tracks
 * daily cloud call count, cache hits, failures, and rejected unknown IDs.
 */
class RecognitionTelemetryStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun recordLocalOutcome(status: MatchConfidenceStatus) {
        val editor = prefs.edit()
        when (status) {
            MatchConfidenceStatus.STRONG_MATCH ->
                editor.putInt(KEY_LOCAL_STRONG, prefs.getInt(KEY_LOCAL_STRONG, 0) + 1)
            MatchConfidenceStatus.AMBIGUOUS_ALTERNATIVES ->
                editor.putInt(KEY_LOCAL_AMBIGUOUS, prefs.getInt(KEY_LOCAL_AMBIGUOUS, 0) + 1)
            MatchConfidenceStatus.UNIDENTIFIED ->
                editor.putInt(KEY_LOCAL_UNIDENTIFIED, prefs.getInt(KEY_LOCAL_UNIDENTIFIED, 0) + 1)
        }
        editor.apply()
    }

    @Synchronized
    fun getCloudCallsToday(todayKey: String = LocalDate.now().toString()): Int {
        val savedDate = prefs.getString(KEY_CALL_DATE, null)
        return if (savedDate == todayKey) prefs.getInt(KEY_CALLS_TODAY, 0) else 0
    }

    @Synchronized
    fun recordCloudAttempt(todayKey: String = LocalDate.now().toString()) {
        val currentToday = getCloudCallsToday(todayKey)
        prefs.edit()
            .putString(KEY_CALL_DATE, todayKey)
            .putInt(KEY_CALLS_TODAY, currentToday + 1)
            .putInt(KEY_CALLS_TOTAL, prefs.getInt(KEY_CALLS_TOTAL, 0) + 1)
            .apply()
    }

    @Synchronized
    fun recordCloudCacheHit() {
        prefs.edit()
            .putInt(KEY_CACHE_HITS, prefs.getInt(KEY_CACHE_HITS, 0) + 1)
            .apply()
    }

    @Synchronized
    fun recordCloudValidationResult(
        validatedCount: Int,
        rejectedUnknownCount: Int,
    ) {
        val editor = prefs.edit()
        if (validatedCount > 0) {
            editor.putInt(KEY_CLOUD_MATCHES, prefs.getInt(KEY_CLOUD_MATCHES, 0) + 1)
        }
        if (rejectedUnknownCount > 0) {
            editor.putInt(
                KEY_REJECTED_UNKNOWN_IDS,
                prefs.getInt(KEY_REJECTED_UNKNOWN_IDS, 0) + rejectedUnknownCount,
            )
        }
        editor.apply()
    }

    @Synchronized
    fun recordCloudFailure(reason: String) {
        prefs.edit()
            .putInt(KEY_CLOUD_FAILURES, prefs.getInt(KEY_CLOUD_FAILURES, 0) + 1)
            .putString(KEY_LAST_CLOUD_ERROR, reason.take(180))
            .apply()
    }

    @Synchronized
    fun getCachedLookup(
        imageKey: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): CachedCloudLookup? {
        if (imageKey.isBlank()) return null
        val map = loadCacheMap()
        val entry = map[imageKey] ?: return null
        if (nowEpochMs - entry.cachedAtEpochMs > CloudCostControlPolicy.CACHE_TTL_MS) {
            map.remove(imageKey)
            saveCacheMap(map)
            return null
        }
        return entry
    }

    @Synchronized
    fun putCachedLookup(
        imageKey: String,
        productIds: List<Long>,
        extractedLabel: String?,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) {
        if (imageKey.isBlank() || productIds.isEmpty()) return
        val map = loadCacheMap()
        map[imageKey] = CachedCloudLookup(
            imageKey = imageKey,
            productIds = productIds.distinct(),
            extractedLabel = extractedLabel,
            cachedAtEpochMs = nowEpochMs,
        )
        // Bound cache to 40 most recent entries to keep SharedPreferences lightweight
        if (map.size > MAX_CACHED_LOOKUPS) {
            val oldest = map.values.sortedBy { it.cachedAtEpochMs }
                .take(map.size - MAX_CACHED_LOOKUPS)
            for (item in oldest) {
                map.remove(item.imageKey)
            }
        }
        saveCacheMap(map)
    }

    @Synchronized
    fun snapshot(todayKey: String = LocalDate.now().toString()): RecognitionTelemetrySnapshot {
        return RecognitionTelemetrySnapshot(
            localStrongCount = prefs.getInt(KEY_LOCAL_STRONG, 0),
            localAmbiguousCount = prefs.getInt(KEY_LOCAL_AMBIGUOUS, 0),
            localUnidentifiedCount = prefs.getInt(KEY_LOCAL_UNIDENTIFIED, 0),
            cloudCallsToday = getCloudCallsToday(todayKey),
            cloudCallsTotal = prefs.getInt(KEY_CALLS_TOTAL, 0),
            cloudCacheHits = prefs.getInt(KEY_CACHE_HITS, 0),
            cloudMatchesFound = prefs.getInt(KEY_CLOUD_MATCHES, 0),
            cloudFailures = prefs.getInt(KEY_CLOUD_FAILURES, 0),
            rejectedUnknownIdsTotal = prefs.getInt(KEY_REJECTED_UNKNOWN_IDS, 0),
            lastCloudError = prefs.getString(KEY_LAST_CLOUD_ERROR, null),
        )
    }

    private fun loadCacheMap(): LinkedHashMap<String, CachedCloudLookup> {
        val raw = prefs.getString(KEY_RESULT_CACHE_JSON, null) ?: return linkedMapOf()
        return try {
            val arr = JSONArray(raw)
            val result = linkedMapOf<String, CachedCloudLookup>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val key = obj.optString("imageKey", "")
                if (key.isBlank()) continue
                val idsArr = obj.optJSONArray("productIds") ?: JSONArray()
                val ids = List(idsArr.length()) { idx -> idsArr.getLong(idx) }
                result[key] = CachedCloudLookup(
                    imageKey = key,
                    productIds = ids,
                    extractedLabel = obj.optString("extractedLabel")
                        .takeIf { it.isNotEmpty() && it != "null" },
                    cachedAtEpochMs = obj.optLong("cachedAtEpochMs", 0L),
                )
            }
            result
        } catch (_: Exception) {
            linkedMapOf()
        }
    }

    private fun saveCacheMap(map: Map<String, CachedCloudLookup>) {
        val arr = JSONArray()
        for (item in map.values) {
            arr.put(
                JSONObject().apply {
                    put("imageKey", item.imageKey)
                    put("productIds", JSONArray(item.productIds))
                    put("extractedLabel", item.extractedLabel ?: JSONObject.NULL)
                    put("cachedAtEpochMs", item.cachedAtEpochMs)
                }
            )
        }
        prefs.edit().putString(KEY_RESULT_CACHE_JSON, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "emofah_recognition_telemetry"
        private const val MAX_CACHED_LOOKUPS = 40
        private const val KEY_LOCAL_STRONG = "local_strong"
        private const val KEY_LOCAL_AMBIGUOUS = "local_ambiguous"
        private const val KEY_LOCAL_UNIDENTIFIED = "local_unidentified"
        private const val KEY_CALL_DATE = "call_date"
        private const val KEY_CALLS_TODAY = "calls_today"
        private const val KEY_CALLS_TOTAL = "calls_total"
        private const val KEY_CACHE_HITS = "cache_hits"
        private const val KEY_CLOUD_MATCHES = "cloud_matches"
        private const val KEY_CLOUD_FAILURES = "cloud_failures"
        private const val KEY_REJECTED_UNKNOWN_IDS = "rejected_unknown_ids"
        private const val KEY_LAST_CLOUD_ERROR = "last_cloud_error"
        private const val KEY_RESULT_CACHE_JSON = "result_cache_json"
    }
}
