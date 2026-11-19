package com.emofah.agencies.auth

import com.emofah.agencies.data.SupabaseConfig
import com.emofah.agencies.sales.StaffRole
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthSessionStage91Test {

    @After
    fun tearDown() {
        SupabaseConfig.registerAccessTokenProvider(null)
    }

    @Test
    fun parseAuthResponse_parsesValidSignInAndRoleClaims() {
        val now = 1_700_000_000_000L
        val json = """
            {
              "access_token": "jwt.access.token.123",
              "refresh_token": "refresh.token.abc",
              "expires_in": 3600,
              "user": {
                "id": "user-uuid-01",
                "email": "mary@emofah.co.ke",
                "app_metadata": {
                  "role": "supervisor"
                },
                "user_metadata": {
                  "full_name": "Mary Wanjiku"
                }
              }
            }
        """.trimIndent()

        val res = SupabaseAuthClient.parseAuthResponse(
            httpStatus = 200,
            responseBody = json,
            nowEpochMs = now,
        )

        assertTrue(res is AuthRpcResult.Success)
        val session = (res as AuthRpcResult.Success).session
        assertEquals("jwt.access.token.123", session.accessToken)
        assertEquals("refresh.token.abc", session.refreshToken)
        assertEquals("mary@emofah.co.ke", session.email)
        assertEquals("Mary Wanjiku", session.displayName)
        assertEquals(StaffRole.SUPERVISOR, session.staffRole)
        assertEquals(now + 3_600_000L, session.expiresAtEpochMs)
    }

    @Test
    fun parseAuthResponse_mapsInvalidCredentialsAndServerErrors() {
        val rejected = SupabaseAuthClient.parseAuthResponse(
            httpStatus = 400,
            responseBody = """{"error":"invalid_grant","error_description":"Invalid login credentials"}""",
            nowEpochMs = 1_700_000_000_000L,
        )
        assertTrue(rejected is AuthRpcResult.AuthRejected)
        assertEquals("Invalid login credentials", (rejected as AuthRpcResult.AuthRejected).message)

        val transient = SupabaseAuthClient.parseAuthResponse(
            httpStatus = 503,
            responseBody = """{"message":"Upstream timeout"}""",
            nowEpochMs = 1_700_000_000_000L,
        )
        assertTrue(transient is AuthRpcResult.TransientError)
    }

    @Test
    fun sessionPolicy_handlesProactiveRefreshOfflineCacheAndExpiry() {
        val now = 1_700_000_000_000L
        val fresh = AuthUserSession(
            accessToken = "tok1",
            refreshToken = "ref1",
            expiresAtEpochMs = now + 20 * 60 * 1000L,
            userId = "u1",
            email = "cashier@emofah.co.ke",
            displayName = "Cashier One",
            staffRole = StaffRole.CASHIER,
            rawRoleClaim = "cashier",
            lastAuthenticatedAtEpochMs = now,
        )
        assertEquals(
            AuthStatus.AUTHENTICATED,
            AuthSessionPolicy.evaluateStatus(fresh, isOnline = true, hasSessionExpiredRejection = false, nowEpochMs = now),
        )
        assertFalse(
            AuthSessionPolicy.shouldProactivelyRefresh(fresh, isOnline = true, hasSessionExpiredRejection = false, nowEpochMs = now),
        )

        val expiringSoon = fresh.copy(expiresAtEpochMs = now + 2 * 60 * 1000L)
        assertTrue(
            AuthSessionPolicy.shouldProactivelyRefresh(expiringSoon, isOnline = true, hasSessionExpiredRejection = false, nowEpochMs = now),
        )
        assertFalse(
            AuthSessionPolicy.shouldProactivelyRefresh(expiringSoon, isOnline = false, hasSessionExpiredRejection = false, nowEpochMs = now),
        )

        val expiredOffline = fresh.copy(expiresAtEpochMs = now - 60_000L)
        assertEquals(
            AuthStatus.OFFLINE_CACHED,
            AuthSessionPolicy.evaluateStatus(expiredOffline, isOnline = false, hasSessionExpiredRejection = false, nowEpochMs = now),
        )
        assertEquals(
            AuthStatus.SESSION_EXPIRED,
            AuthSessionPolicy.evaluateStatus(expiredOffline, isOnline = true, hasSessionExpiredRejection = true, nowEpochMs = now),
        )
        assertEquals(
            AuthStatus.UNAUTHENTICATED,
            AuthSessionPolicy.evaluateStatus(null, isOnline = true, hasSessionExpiredRejection = false, nowEpochMs = now),
        )
    }

    @Test
    fun authUserSession_roundTripsJsonCleanly() {
        val original = AuthUserSession(
            accessToken = "access-xyz",
            refreshToken = "refresh-xyz",
            expiresAtEpochMs = 1_700_005_000_000L,
            userId = "usr-99",
            email = "owner@emofah.co.ke",
            displayName = "Emofah Owner",
            staffRole = StaffRole.OWNER_MANAGER,
            rawRoleClaim = "owner",
            lastAuthenticatedAtEpochMs = 1_700_000_000_000L,
            lastRefreshedAtEpochMs = 1_700_001_000_000L,
        )

        val decoded = AuthUserSession.fromJsonString(original.toJsonString())
        assertNotNull(decoded)
        assertEquals(original, decoded)
        assertNull(AuthUserSession.fromJsonString("{broken"))
    }

    @Test
    fun supabaseConfig_usesActiveUserTokenAndFallsBackToAnonKey() {
        var activeJwt: String? = null
        SupabaseConfig.registerAccessTokenProvider { activeJwt }

        assertEquals(SupabaseConfig.anonKey, SupabaseConfig.bearerToken())

        activeJwt = "user.jwt.token.999"
        assertEquals("user.jwt.token.999", SupabaseConfig.bearerToken())

        activeJwt = null
        assertEquals(SupabaseConfig.anonKey, SupabaseConfig.bearerToken())
    }

    @Test
    fun mapRoleFromClaims_ignoresEmailSubstringsAndRequiresServerClaims() {
        assertEquals(StaffRole.CASHIER, AuthSessionPolicy.mapRoleFromClaims(null, "admin@gmail.com"))
        assertEquals(StaffRole.CASHIER, AuthSessionPolicy.mapRoleFromClaims(null, "owner.manager@emofah.co.ke"))
        assertEquals(StaffRole.CASHIER, AuthSessionPolicy.mapRoleFromClaims("", "supervisor@emofah.co.ke"))
        assertEquals(StaffRole.SUPERVISOR, AuthSessionPolicy.mapRoleFromClaims("supervisor", "staff@emofah.co.ke"))
        assertEquals(StaffRole.OWNER_MANAGER, AuthSessionPolicy.mapRoleFromClaims("owner_manager", "staff@emofah.co.ke"))
    }
}
