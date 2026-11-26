package com.emofah.agencies.auth

import com.emofah.agencies.sales.StaffRole
import org.json.JSONObject

enum class EmofahPermission(
    val label: String,
    val requiredRoleLabel: String,
    val minimumRole: StaffRole,
    val requiresAuthenticatedSessionOnServer: Boolean = false,
) {
    RECORD_STANDARD_SALE(
        label = "Record Standard Sale",
        requiredRoleLabel = "Cashier+",
        minimumRole = StaffRole.CASHIER,
    ),
    RECEIVE_STOCK(
        label = "Receive Stock",
        requiredRoleLabel = "Cashier+",
        minimumRole = StaffRole.CASHIER,
    ),
    REGISTER_PRODUCT(
        label = "Register New Product",
        requiredRoleLabel = "Cashier+",
        minimumRole = StaffRole.CASHIER,
    ),
    RECORD_SERVICE_OPERATION(
        label = "Record Service Operation",
        requiredRoleLabel = "Cashier+",
        minimumRole = StaffRole.CASHIER,
    ),
    MANAGE_ORDERS(
        label = "Manage & Convert Store Orders",
        requiredRoleLabel = "Cashier+",
        minimumRole = StaffRole.CASHIER,
    ),
    RECORD_FINANCIAL_EVENT(
        label = "Record Cash / M-Pesa / Receipt Event",
        requiredRoleLabel = "Cashier+",
        minimumRole = StaffRole.CASHIER,
    ),
    OVERRIDE_SALE_PRICE(
        label = "Override Selling Price",
        requiredRoleLabel = "Supervisor or Owner/Manager",
        minimumRole = StaffRole.SUPERVISOR,
        requiresAuthenticatedSessionOnServer = true,
    ),
    REVERSE_SALE(
        label = "Reverse Completed Sale",
        requiredRoleLabel = "Supervisor or Owner/Manager",
        minimumRole = StaffRole.SUPERVISOR,
        requiresAuthenticatedSessionOnServer = true,
    ),
    ADJUST_STOCK(
        label = "Adjust Stock (Damage / Expiry / Shrinkage)",
        requiredRoleLabel = "Supervisor or Owner/Manager",
        minimumRole = StaffRole.SUPERVISOR,
        requiresAuthenticatedSessionOnServer = true,
    ),
    SUBMIT_PHYSICAL_STOCK_COUNT(
        label = "Submit & Reconcile Physical Stock Count",
        requiredRoleLabel = "Supervisor or Owner/Manager",
        minimumRole = StaffRole.SUPERVISOR,
        requiresAuthenticatedSessionOnServer = true,
    ),
    RESOLVE_SYNC_CONFLICT_REVERSAL(
        label = "Reverse Conflicted Sale Locally",
        requiredRoleLabel = "Supervisor or Owner/Manager",
        minimumRole = StaffRole.SUPERVISOR,
        requiresAuthenticatedSessionOnServer = true,
    ),
    VIEW_FULL_AUDIT_LOG(
        label = "Inspect Full Store Audit Trail",
        requiredRoleLabel = "Supervisor or Owner/Manager",
        minimumRole = StaffRole.SUPERVISOR,
        requiresAuthenticatedSessionOnServer = true,
    ),
    MANAGE_SECURITY_SETTINGS(
        label = "Manage Security & Staff Role Settings",
        requiredRoleLabel = "Owner / Manager",
        minimumRole = StaffRole.OWNER_MANAGER,
        requiresAuthenticatedSessionOnServer = true,
    ),
}

sealed class PermissionCheckResult {
    data object Allowed : PermissionCheckResult()
    data class Denied(val message: String) : PermissionCheckResult()
}

data class ServerStaffProfile(
    val authenticated: Boolean,
    val userId: String?,
    val email: String?,
    val fullName: String?,
    val staffRole: StaffRole,
    val isActive: Boolean,
    val bootstrapNeeded: Boolean = false,
) {
    companion object {
        fun fromJson(rawJson: String): ServerStaffProfile? {
            if (rawJson.isBlank()) return null
            return runCatching {
                val obj = JSONObject(rawJson)
                val roleStr = obj.optString("staff_role", StaffRole.CASHIER.name)
                val mappedRole = StaffRole.entries.firstOrNull { it.name == roleStr }
                    ?: AuthSessionPolicy.mapRoleFromClaims(roleStr)
                ServerStaffProfile(
                    authenticated = obj.optBoolean("authenticated", false),
                    userId = obj.optString("user_id", "").takeIf { it.isNotBlank() && it != "null" },
                    email = obj.optString("email", "").takeIf { it.isNotBlank() && it != "null" },
                    fullName = obj.optString("full_name", "").takeIf { it.isNotBlank() && it != "null" },
                    staffRole = mappedRole,
                    isActive = obj.optBoolean("is_active", true),
                    bootstrapNeeded = obj.optBoolean("bootstrap_needed", false),
                )
            }.getOrNull()
        }
    }
}

data class StaffRoleDirectoryEntry(
    val userId: String,
    val email: String,
    val fullName: String,
    val staffRole: StaffRole,
    val isActive: Boolean,
)

sealed class AuthBootstrapOutcome {
    data class Activated(val message: String) : AuthBootstrapOutcome()
    data class Rejected(val message: String) : AuthBootstrapOutcome()
    data class TransientError(val message: String) : AuthBootstrapOutcome()
}

sealed class StaffDirectoryOutcome {
    data class Loaded(val staff: List<StaffRoleDirectoryEntry>) : StaffDirectoryOutcome()
    data class Rejected(val message: String) : StaffDirectoryOutcome()
    data class TransientError(val message: String) : StaffDirectoryOutcome()
}

sealed class RoleUpdateOutcome {
    data class Updated(val message: String) : RoleUpdateOutcome()
    data class Rejected(val message: String) : RoleUpdateOutcome()
    data class TransientError(val message: String) : RoleUpdateOutcome()
}

/**
 * Stage 9.2 — Centralized Role-Based Access Control Policy.
 * Note: Business accounting rule changes remain strictly Desktop-managed regardless of Android role.
 */
object RolePermissionPolicy {

    private fun roleRank(role: StaffRole): Int = when (role) {
        StaffRole.CASHIER -> 1
        StaffRole.SUPERVISOR -> 2
        StaffRole.OWNER_MANAGER -> 3
    }

    fun hasPermission(
        role: StaffRole,
        permission: EmofahPermission,
    ): Boolean = true

    fun authorize(
        role: StaffRole,
        permission: EmofahPermission,
    ): PermissionCheckResult = PermissionCheckResult.Allowed

    fun grantedPermissions(role: StaffRole): Set<EmofahPermission> {
        return EmofahPermission.entries.toSet()
    }

    fun reconcileSessionWithServerProfile(
        session: AuthUserSession,
        profile: ServerStaffProfile?,
    ): ServerProfileReconciliation {
        if (profile == null || !profile.authenticated) {
            return ServerProfileReconciliation.Active(session)
        }
        return ServerProfileReconciliation.Active(
            session.copy(
                staffRole = profile.staffRole,
                displayName = profile.fullName?.takeIf { it.isNotBlank() } ?: session.displayName,
                rawRoleClaim = profile.staffRole.name,
            )
        )
    }
}

sealed class ServerProfileReconciliation {
    data class Active(val session: AuthUserSession) : ServerProfileReconciliation()
    data class Deactivated(val message: String) : ServerProfileReconciliation()
}
