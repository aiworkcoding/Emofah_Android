package com.emofah.agencies.auth

import com.emofah.agencies.sales.DeviceIdentityProvider
import com.emofah.agencies.sales.StaffRole
import org.json.JSONObject

enum class AuthStatus(val label: String) {
    UNAUTHENTICATED("Not Signed In"),
    AUTHENTICATED("Signed In"),
    OFFLINE_CACHED("Offline Session"),
    SESSION_EXPIRED("Session Expired"),
}

data class AuthUserSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long,
    val userId: String,
    val email: String,
    val displayName: String,
    val staffRole: StaffRole,
    val rawRoleClaim: String?,
    val lastAuthenticatedAtEpochMs: Long,
    val lastRefreshedAtEpochMs: Long? = null,
) {
    fun isExpiredAt(nowEpochMs: Long): Boolean = nowEpochMs >= expiresAtEpochMs

    fun isExpiringSoon(
        nowEpochMs: Long,
        windowMs: Long = AuthSessionPolicy.REFRESH_WINDOW_MS,
    ): Boolean = nowEpochMs + windowMs >= expiresAtEpochMs

    fun toJsonString(): String {
        return JSONObject().apply {
            put("access_token", accessToken)
            put("refresh_token", refreshToken)
            put("expires_at_epoch_ms", expiresAtEpochMs)
            put("user_id", userId)
            put("email", email)
            put("display_name", displayName)
            put("staff_role", staffRole.name)
            put("raw_role_claim", rawRoleClaim ?: JSONObject.NULL)
            put("last_authenticated_at_epoch_ms", lastAuthenticatedAtEpochMs)
            put("last_refreshed_at_epoch_ms", lastRefreshedAtEpochMs ?: JSONObject.NULL)
        }.toString()
    }

    companion object {
        fun fromJsonString(raw: String?): AuthUserSession? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val obj = JSONObject(raw)
                val access = obj.optString("access_token", "").trim()
                val refresh = obj.optString("refresh_token", "").trim()
                val userId = obj.optString("user_id", "").trim()
                val email = obj.optString("email", "").trim()
                if (access.isBlank() || refresh.isBlank() || userId.isBlank()) {
                    return@runCatching null
                }
                val roleName = obj.optString("staff_role", StaffRole.CASHIER.name)
                val role = StaffRole.entries.firstOrNull { it.name == roleName } ?: StaffRole.CASHIER
                val rawClaim = obj.optString("raw_role_claim", "").takeIf {
                    it.isNotBlank() && it != "null"
                }
                val lastRefreshed = if (obj.isNull("last_refreshed_at_epoch_ms")) {
                    null
                } else {
                    obj.optLong("last_refreshed_at_epoch_ms", 0L).takeIf { it > 0L }
                }
                AuthUserSession(
                    accessToken = access,
                    refreshToken = refresh,
                    expiresAtEpochMs = obj.optLong("expires_at_epoch_ms", 0L),
                    userId = userId,
                    email = email,
                    displayName = obj.optString("display_name", email).ifBlank { email },
                    staffRole = role,
                    rawRoleClaim = rawClaim,
                    lastAuthenticatedAtEpochMs = obj.optLong("last_authenticated_at_epoch_ms", 0L),
                    lastRefreshedAtEpochMs = lastRefreshed,
                )
            }.getOrNull()
        }
    }
}

data class AuthUiState(
    val status: AuthStatus = AuthStatus.UNAUTHENTICATED,
    val session: AuthUserSession? = null,
    val lastError: String? = null,
    val isBusy: Boolean = false,
    val needsReauthPrompt: Boolean = false,
    val bootstrapNeeded: Boolean = false,
) {
    val hasActiveBearerToken: Boolean
        get() = session != null && status != AuthStatus.SESSION_EXPIRED && status != AuthStatus.UNAUTHENTICATED

    val activeEmail: String?
        get() = session?.email?.takeIf { it.isNotBlank() }

    val activeActorId: String
        get() = session?.let {
            AuthSessionPolicy.buildActorId(it.userId, it.email, it.displayName)
        } ?: DeviceIdentityProvider.DEFAULT_ACTOR_ID

    val activeRole: StaffRole
        get() = session?.staffRole ?: StaffRole.CASHIER

    val grantedPermissions: Set<EmofahPermission>
        get() = RolePermissionPolicy.grantedPermissions(activeRole)
}

sealed class AuthActionOutcome {
    data class SignedIn(val session: AuthUserSession) : AuthActionOutcome()
    data class Refreshed(val session: AuthUserSession) : AuthActionOutcome()
    data class SignedOut(val hadPendingSyncItems: Boolean) : AuthActionOutcome()
    data class Rejected(val message: String, val sessionExpired: Boolean = false) : AuthActionOutcome()
    data class TransientFailure(val message: String, val keptOfflineSession: Boolean = false) : AuthActionOutcome()
}

object AuthSessionPolicy {
    const val REFRESH_WINDOW_MS = 5 * 60 * 1000L

    fun mapRoleFromClaims(rawRole: String?, @Suppress("UNUSED_PARAMETER") email: String? = null): StaffRole {
        val normalized = rawRole?.trim()?.lowercase().orEmpty()
        return when (normalized) {
            "owner", "manager", "owner_manager", "admin", "super_admin" -> StaffRole.OWNER_MANAGER
            "supervisor", "shift_lead", "lead" -> StaffRole.SUPERVISOR
            else -> StaffRole.CASHIER
        }
    }

    fun evaluateStatus(
        session: AuthUserSession?,
        isOnline: Boolean,
        hasSessionExpiredRejection: Boolean,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): AuthStatus {
        if (session == null) return AuthStatus.UNAUTHENTICATED
        if (hasSessionExpiredRejection) return AuthStatus.SESSION_EXPIRED
        if (!session.isExpiredAt(nowEpochMs)) return AuthStatus.AUTHENTICATED
        return AuthStatus.OFFLINE_CACHED
    }

    fun shouldProactivelyRefresh(
        session: AuthUserSession?,
        isOnline: Boolean,
        hasSessionExpiredRejection: Boolean,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (session == null || !isOnline || hasSessionExpiredRejection) return false
        return session.isExpiringSoon(nowEpochMs, REFRESH_WINDOW_MS)
    }

    fun buildActorId(userId: String, email: String, displayName: String): String {
        val cleanEmail = email.trim()
        if (cleanEmail.isNotBlank()) return cleanEmail
        val cleanName = displayName.trim()
        if (cleanName.isNotBlank()) return cleanName
        return userId.trim().ifBlank { DeviceIdentityProvider.DEFAULT_ACTOR_ID }
    }
}
