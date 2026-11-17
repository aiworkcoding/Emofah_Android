package com.emofah.agencies.reporting

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class FreshnessLevel {
    FRESH,      // < 2 hours old
    AGING,      // 2 to 6 hours old
    STALE       // > 6 hours old or unknown
}

data class DataFreshnessStatus(
    val lastSyncEpochMs: Long?,
    val lastCatalogueFetchEpochMs: Long?,
    val pendingCount: Int,
    val conflictCount: Int,
    val isOnline: Boolean
) {
    val catalogueAgeHours: Double
        get() {
            if (lastCatalogueFetchEpochMs == null) return 999.0
            val diffMs = System.currentTimeMillis() - lastCatalogueFetchEpochMs
            return diffMs.toDouble() / (1000.0 * 60.0 * 60.0)
        }

    val syncAgeHours: Double
        get() {
            if (lastSyncEpochMs == null) return 999.0
            val diffMs = System.currentTimeMillis() - lastSyncEpochMs
            return diffMs.toDouble() / (1000.0 * 60.0 * 60.0)
        }

    val freshnessLevel: FreshnessLevel
        get() {
            val maxAge = maxOf(catalogueAgeHours, syncAgeHours)
            return when {
                maxAge <= 2.0 -> FreshnessLevel.FRESH
                maxAge <= 6.0 -> FreshnessLevel.AGING
                else -> FreshnessLevel.STALE
            }
        }

    val formattedCatalogueAge: String
        get() {
            if (lastCatalogueFetchEpochMs == null) return "Never synced"
            val diffMs = System.currentTimeMillis() - lastCatalogueFetchEpochMs
            val mins = diffMs / (1000 * 60)
            if (mins < 60) return "$mins mins ago"
            val hours = mins / 60
            if (hours < 24) return "$hours hours ago"
            val days = hours / 24
            return "$days days ago"
        }

    val formattedLastSync: String
        get() {
            if (lastSyncEpochMs == null) return "Never"
            return SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(lastSyncEpochMs))
        }
}
