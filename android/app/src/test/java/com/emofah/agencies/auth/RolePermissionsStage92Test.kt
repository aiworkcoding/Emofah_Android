package com.emofah.agencies.auth

import com.emofah.agencies.sales.PriceOverridePolicy
import com.emofah.agencies.sales.SaleReversalPolicy
import com.emofah.agencies.sales.SaleReversalRpcOutcome
import com.emofah.agencies.sales.SaleRpcOutcome
import com.emofah.agencies.sales.StaffRole
import com.emofah.agencies.sales.SupabaseSaleReversalClient
import com.emofah.agencies.sales.SupabaseSaleRpcClient
import com.emofah.agencies.stock.StockAdjustmentRpcOutcome
import com.emofah.agencies.stock.StockCountRpcOutcome
import com.emofah.agencies.stock.SupabaseStockAdjustmentClient
import com.emofah.agencies.stock.SupabaseStockCountClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RolePermissionsStage92Test {

    @Test
    fun rolePermissionPolicy_grantsFullCapabilitiesToAllStaffAndRemovesDeactivationLockout() {
        // Every staff role has full operational access without artificial hierarchy gates
        for (role in StaffRole.entries) {
            assertEquals(
                EmofahPermission.entries.toSet(),
                RolePermissionPolicy.grantedPermissions(role),
            )
            for (perm in EmofahPermission.entries) {
                assertTrue(RolePermissionPolicy.hasPermission(role, perm))
                assertTrue(RolePermissionPolicy.authorize(role, perm) is PermissionCheckResult.Allowed)
            }
            assertTrue(PriceOverridePolicy.canOverridePrice(role))
            assertTrue(SaleReversalPolicy.canReverseSales(role))
        }
    }

    @Test
    fun parseAuthResponse_ignoresClientWritableUserMetadataRoleEscalation() {
        val now = 1_700_000_000_000L
        val spoofedJson = """
            {
              "access_token": "jwt.cashier.token",
              "refresh_token": "refresh.cashier.token",
              "expires_in": 3600,
              "user": {
                "id": "user-cashier-01",
                "email": "cashier@emofah.co.ke",
                "app_metadata": {},
                "user_metadata": {
                  "full_name": "James Cashier",
                  "role": "owner_manager",
                  "staff_role": "OWNER_MANAGER"
                }
              }
            }
        """.trimIndent()

        val result = SupabaseAuthClient.parseAuthResponse(
            httpStatus = 200,
            responseBody = spoofedJson,
            nowEpochMs = now,
        )
        assertTrue(result is AuthRpcResult.Success)
        val session = (result as AuthRpcResult.Success).session
        assertEquals(StaffRole.CASHIER, session.staffRole)
        assertNull(session.rawRoleClaim)
        assertEquals("James Cashier", session.displayName)
    }

    @Test
    fun serverStaffProfile_parsesAndKeepsSessionActiveWithoutDeactivationLockout() {
        val activeSupervisor = ServerStaffProfile.fromJson(
            """
                {
                  "authenticated": true,
                  "user_id": "u-sup-1",
                  "email": "grace@emofah.co.ke",
                  "full_name": "Grace Mutua",
                  "staff_role": "SUPERVISOR",
                  "is_active": true
                }
            """.trimIndent()
        )
        assertNotNull(activeSupervisor)
        assertEquals(StaffRole.SUPERVISOR, activeSupervisor!!.staffRole)
        assertTrue(activeSupervisor.isActive)

        val baseSession = AuthUserSession(
            accessToken = "tok-1",
            refreshToken = "ref-1",
            expiresAtEpochMs = 1_800_000_000_000L,
            userId = "u-sup-1",
            email = "grace@emofah.co.ke",
            displayName = "grace@emofah.co.ke",
            staffRole = StaffRole.CASHIER,
            rawRoleClaim = null,
            lastAuthenticatedAtEpochMs = 1_700_000_000_000L,
        )

        val elevated = RolePermissionPolicy.reconcileSessionWithServerProfile(baseSession, activeSupervisor)
        assertTrue(elevated is ServerProfileReconciliation.Active)
        val updatedSession = (elevated as ServerProfileReconciliation.Active).session
        assertEquals(StaffRole.OWNER_MANAGER, updatedSession.staffRole)
        assertEquals("Grace Mutua", updatedSession.displayName)

        // Even if an old profile row has is_active=false, normal app usage is never blocked
        val disabledProfile = ServerStaffProfile.fromJson(
            """
                {
                  "authenticated": true,
                  "user_id": "u-sup-1",
                  "email": "grace@emofah.co.ke",
                  "full_name": "Grace Mutua",
                  "staff_role": "CASHIER",
                  "is_active": false
                }
            """.trimIndent()
        )
        val reconciled = RolePermissionPolicy.reconcileSessionWithServerProfile(baseSession, disabledProfile)
        assertTrue(reconciled is ServerProfileReconciliation.Active)

        // Graceful null fallback when migration profile RPC isn't present yet
        val unchanged = RolePermissionPolicy.reconcileSessionWithServerProfile(baseSession, null)
        assertTrue(unchanged is ServerProfileReconciliation.Active)
        assertEquals(StaffRole.CASHIER, (unchanged as ServerProfileReconciliation.Active).session.staffRole)
    }

    @Test
    fun privilegedRpcClients_rejectServerUnauthorizedRoleAndAuthErrors() {
        val saleUnauthorized = SupabaseSaleRpcClient.parseRpcResponse(
            httpStatus = 200,
            responseBody = """{"status":"UNAUTHORIZED_ROLE","message":"Price override requires Supervisor."}""",
        )
        assertTrue(saleUnauthorized is SaleRpcOutcome.Rejected)
        assertEquals("UNAUTHORIZED_ROLE", (saleUnauthorized as SaleRpcOutcome.Rejected).code)

        val reversalUnauthorized = SupabaseSaleReversalClient.parseRpcResponse(
            httpStatus = 200,
            responseBody = """{"status":"UNAUTHORIZED_ROLE","message":"Supervisor session required."}""",
        )
        assertTrue(reversalUnauthorized is SaleReversalRpcOutcome.Rejected)
        assertEquals("UNAUTHORIZED_ROLE", (reversalUnauthorized as SaleReversalRpcOutcome.Rejected).code)

        val adjustmentUnauthorized = SupabaseStockAdjustmentClient.parseRpcResponse(
            httpStatus = 200,
            responseBody = """{"status":"UNAUTHORIZED_ROLE","message":"Supervisor session required."}""",
        )
        assertTrue(adjustmentUnauthorized is StockAdjustmentRpcOutcome.Rejected)
        assertEquals("UNAUTHORIZED_ROLE", (adjustmentUnauthorized as StockAdjustmentRpcOutcome.Rejected).code)

        val countUnauthorized = SupabaseStockCountClient.parseRpcResponse(
            httpStatus = 200,
            responseBody = """{"success":false,"status":"UNAUTHORIZED_ROLE","message":"Supervisor session required."}""",
        )
        assertTrue(countUnauthorized is StockCountRpcOutcome.Rejected)

        val countForbidden = SupabaseStockCountClient.parseRpcResponse(
            httpStatus = 403,
            responseBody = """{"message":"Forbidden"}""",
        )
        assertTrue(countForbidden is StockCountRpcOutcome.AuthRequired)
    }

    @Test
    fun serverStaffProfile_parsesBootstrapNeededFlag() {
        val bootstrapTrue = ServerStaffProfile.fromJson(
            """
                {
                  "authenticated": true,
                  "user_id": "u-first-1",
                  "email": "founder@emofah.co.ke",
                  "full_name": "Founder",
                  "staff_role": "CASHIER",
                  "is_active": true,
                  "bootstrap_needed": true
                }
            """.trimIndent()
        )
        assertNotNull(bootstrapTrue)
        assertTrue(bootstrapTrue!!.bootstrapNeeded)
        assertEquals(StaffRole.CASHIER, bootstrapTrue.staffRole)

        val bootstrapFalse = ServerStaffProfile.fromJson(
            """
                {
                  "authenticated": true,
                  "user_id": "u-owner-1",
                  "email": "owner@emofah.co.ke",
                  "full_name": "Owner",
                  "staff_role": "OWNER_MANAGER",
                  "is_active": true,
                  "bootstrap_needed": false
                }
            """.trimIndent()
        )
        assertNotNull(bootstrapFalse)
        assertFalse(bootstrapFalse!!.bootstrapNeeded)
        assertEquals(StaffRole.OWNER_MANAGER, bootstrapFalse.staffRole)
    }
}
