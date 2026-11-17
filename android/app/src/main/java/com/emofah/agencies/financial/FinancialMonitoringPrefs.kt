package com.emofah.agencies.financial

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

data class FinancialMonitoringState(
    val isConsentGranted: Boolean,
    val hasSmsPermissions: Boolean,
    val consentGrantedAtEpochMs: Long?,
    val consentActorId: String?,
    val lastCapturedAtEpochMs: Long?,
    val capturedCount: Int,
    val ignoredNonFinancialCount: Int,
) {
    val isLiveSmsActive: Boolean
        get() = isConsentGranted && hasSmsPermissions
}

/**
 * Stage 5.2 — Explicit SMS monitoring consent & permission compliance store.
 * Never activates background SMS capture unless the operator explicitly opts in.
 */
class FinancialMonitoringPrefs(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readState(): FinancialMonitoringState {
        val receiveGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECEIVE_SMS,
        ) == PackageManager.PERMISSION_GRANTED
        val readGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_SMS,
        ) == PackageManager.PERMISSION_GRANTED

        val grantedAt = prefs.getLong(KEY_CONSENT_AT_MS, -1L).takeIf { it > 0L }
        val lastCaptured = prefs.getLong(KEY_LAST_CAPTURED_MS, -1L).takeIf { it > 0L }

        return FinancialMonitoringState(
            isConsentGranted = prefs.getBoolean(KEY_CONSENT_GRANTED, false),
            hasSmsPermissions = receiveGranted && readGranted,
            consentGrantedAtEpochMs = grantedAt,
            consentActorId = prefs.getString(KEY_CONSENT_ACTOR, null),
            lastCapturedAtEpochMs = lastCaptured,
            capturedCount = prefs.getInt(KEY_CAPTURED_COUNT, 0),
            ignoredNonFinancialCount = prefs.getInt(KEY_IGNORED_COUNT, 0),
        )
    }

    fun isConsentGranted(): Boolean = prefs.getBoolean(KEY_CONSENT_GRANTED, false)

    fun grantConsent(actorId: String, nowEpochMs: Long = System.currentTimeMillis()): FinancialMonitoringState {
        prefs.edit()
            .putBoolean(KEY_CONSENT_GRANTED, true)
            .putLong(KEY_CONSENT_AT_MS, nowEpochMs)
            .putString(KEY_CONSENT_ACTOR, actorId)
            .apply()
        return readState()
    }

    fun revokeConsent(): FinancialMonitoringState {
        prefs.edit()
            .putBoolean(KEY_CONSENT_GRANTED, false)
            .apply()
        return readState()
    }

    fun recordCapturedMessage(nowEpochMs: Long = System.currentTimeMillis()) {
        val current = prefs.getInt(KEY_CAPTURED_COUNT, 0)
        prefs.edit()
            .putInt(KEY_CAPTURED_COUNT, current + 1)
            .putLong(KEY_LAST_CAPTURED_MS, nowEpochMs)
            .apply()
    }

    fun recordIgnoredNonFinancial() {
        val current = prefs.getInt(KEY_IGNORED_COUNT, 0)
        prefs.edit()
            .putInt(KEY_IGNORED_COUNT, current + 1)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "emofah_financial_monitoring_prefs"
        private const val KEY_CONSENT_GRANTED = "sms_monitoring_consent_granted"
        private const val KEY_CONSENT_AT_MS = "sms_monitoring_consent_at_ms"
        private const val KEY_CONSENT_ACTOR = "sms_monitoring_consent_actor"
        private const val KEY_LAST_CAPTURED_MS = "last_captured_sms_at_ms"
        private const val KEY_CAPTURED_COUNT = "captured_mpesa_count"
        private const val KEY_IGNORED_COUNT = "ignored_non_financial_count"
    }
}
