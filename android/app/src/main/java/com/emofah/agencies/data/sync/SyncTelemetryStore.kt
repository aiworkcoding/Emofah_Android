package com.emofah.agencies.data.sync

import android.content.Context

data class SyncCheckpoint(
    val lastAttemptEpochMs: Long?,
    val lastSuccessEpochMs: Long?,
    val isAuthBlocked: Boolean,
    val lastMessage: String?,
)

/**
 * Lightweight persisted checkpoint for last background sync attempt/success
 * and authentication hold status (never exposes credentials or system logs).
 */
class SyncTelemetryStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun recordAttempt(
        nowEpochMs: Long,
        summary: SyncPassSummary,
    ) {
        val editor = prefs.edit()
            .putLong(KEY_LAST_ATTEMPT_MS, nowEpochMs)
            .putBoolean(KEY_AUTH_BLOCKED, summary.authBlockedCount > 0)

        if (!summary.skippedOffline && summary.authBlockedCount == 0 && summary.retryableCount == 0) {
            editor.putLong(KEY_LAST_SUCCESS_MS, nowEpochMs)
        }

        val message = when {
            summary.authBlockedCount > 0 -> "Sign-in required to resume cloud sync."
            summary.skippedOffline -> "Waiting for internet connection."
            summary.conflictCount > 0 -> "${summary.conflictCount} item(s) need conflict review."
            summary.retryableCount > 0 -> "Temporary network issue; retrying automatically."
            summary.syncedCount > 0 -> "Synced ${summary.syncedCount} offline record(s)."
            else -> "Up to date."
        }
        editor.putString(KEY_LAST_MESSAGE, message)
        editor.apply()
    }

    fun clearAuthBlock() {
        prefs.edit()
            .putBoolean(KEY_AUTH_BLOCKED, false)
            .putString(KEY_LAST_MESSAGE, "Authentication restored; sync queued.")
            .apply()
    }

    fun readCheckpoint(): SyncCheckpoint {
        val lastAttempt = prefs.getLong(KEY_LAST_ATTEMPT_MS, -1L).takeIf { it > 0L }
        val lastSuccess = prefs.getLong(KEY_LAST_SUCCESS_MS, -1L).takeIf { it > 0L }
        val authBlocked = prefs.getBoolean(KEY_AUTH_BLOCKED, false)
        val message = prefs.getString(KEY_LAST_MESSAGE, null)
        return SyncCheckpoint(
            lastAttemptEpochMs = lastAttempt,
            lastSuccessEpochMs = lastSuccess,
            isAuthBlocked = authBlocked,
            lastMessage = message,
        )
    }

    companion object {
        private const val PREFS_NAME = "emofah_sync_checkpoints"
        private const val KEY_LAST_ATTEMPT_MS = "last_attempt_ms"
        private const val KEY_LAST_SUCCESS_MS = "last_success_ms"
        private const val KEY_AUTH_BLOCKED = "auth_blocked"
        private const val KEY_LAST_MESSAGE = "last_message"
    }
}
