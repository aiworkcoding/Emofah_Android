package com.emofah.agencies.reporting

import org.junit.Assert.assertEquals
import org.junit.Test

class DataFreshnessStage83Test {

    @Test
    fun testDataFreshnessEvaluation() {
        val now = System.currentTimeMillis()

        // Fresh status (< 2 hours)
        val freshStatus = DataFreshnessStatus(
            lastSyncEpochMs = now - 1000L * 60L * 30L, // 30 mins ago
            lastCatalogueFetchEpochMs = now - 1000L * 60L * 45L, // 45 mins ago
            pendingCount = 0,
            conflictCount = 0,
            isOnline = true
        )
        assertEquals(FreshnessLevel.FRESH, freshStatus.freshnessLevel)

        // Aging status (2 to 6 hours)
        val agingStatus = DataFreshnessStatus(
            lastSyncEpochMs = now - 1000L * 60L * 60L * 4L, // 4 hours ago
            lastCatalogueFetchEpochMs = now - 1000L * 60L * 60L * 3L, // 3 hours ago
            pendingCount = 1,
            conflictCount = 0,
            isOnline = true
        )
        assertEquals(FreshnessLevel.AGING, agingStatus.freshnessLevel)

        // Stale status (> 6 hours)
        val staleStatus = DataFreshnessStatus(
            lastSyncEpochMs = now - 1000L * 60L * 60L * 10L, // 10 hours ago
            lastCatalogueFetchEpochMs = null,
            pendingCount = 3,
            conflictCount = 1,
            isOnline = false
        )
        assertEquals(FreshnessLevel.STALE, staleStatus.freshnessLevel)
    }
}
