package com.emofah.agencies.sales

import android.content.Context
import java.util.UUID

/**
 * Provides a persistent per-install device identifier and current staff actor ID
 * for transaction attribution and audit records without fabricating PII.
 */
class DeviceIdentityProvider(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = "android-${UUID.randomUUID().toString().take(12)}"
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    fun getActorId(): String {
        return prefs.getString(KEY_ACTOR_ID, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_ACTOR_ID
    }

    fun setAuthenticatedActorId(actorId: String?) {
        if (actorId.isNullOrBlank()) {
            prefs.edit().remove(KEY_ACTOR_ID).apply()
        } else {
            prefs.edit().putString(KEY_ACTOR_ID, actorId.trim()).apply()
        }
    }

    fun getStaffRole(): StaffRole {
        val raw = prefs.getString(KEY_STAFF_ROLE, StaffRole.CASHIER.name)
        return StaffRole.entries.firstOrNull { it.name == raw } ?: StaffRole.CASHIER
    }

    fun setStaffRole(role: StaffRole) {
        prefs.edit().putString(KEY_STAFF_ROLE, role.name).apply()
    }

    companion object {
        private const val PREFS_NAME = "emofah_device_identity"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_ACTOR_ID = "actor_id"
        private const val KEY_STAFF_ROLE = "staff_role"
        const val DEFAULT_ACTOR_ID = "android_staff"
    }
}
