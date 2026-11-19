package com.emofah.agencies.auth

import com.emofah.agencies.data.SupabaseConfig
import com.emofah.agencies.sales.StaffRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed class AuthRpcResult {
    data class Success(val session: AuthUserSession) : AuthRpcResult()
    data class AuthRejected(val code: String, val message: String) : AuthRpcResult()
    data class TransientError(val message: String) : AuthRpcResult()
}

sealed class AuthOtpResult {
    data class OtpSent(val message: String) : AuthOtpResult()
    data class AuthRejected(val code: String, val message: String) : AuthOtpResult()
    data class TransientError(val message: String) : AuthOtpResult()
}

interface SupabaseAuthGateway {
    suspend fun requestOtp(email: String): AuthOtpResult
    suspend fun verifyOtp(email: String, token: String): AuthRpcResult
    suspend fun refreshSession(refreshToken: String): AuthRpcResult
    suspend fun fetchStaffProfile(accessToken: String): ServerStaffProfile? = null
    suspend fun bootstrapInitialAdmin(
        accessToken: String,
        email: String?,
        fullName: String?,
    ): AuthBootstrapOutcome = AuthBootstrapOutcome.Rejected("Initial owner setup is not available.")
    suspend fun listStaffRoles(accessToken: String): StaffDirectoryOutcome =
        StaffDirectoryOutcome.Rejected("Staff list is not available.")
    suspend fun assignStaffRole(
        accessToken: String,
        email: String,
        staffRole: com.emofah.agencies.sales.StaffRole,
        fullName: String? = null,
        isActive: Boolean = true,
    ): RoleUpdateOutcome = RoleUpdateOutcome.Rejected("Staff role update is not available.")
    suspend fun signOut(accessToken: String): Boolean
}

/**
 * Stage 9.1 — Direct GoTrue REST client (`/auth/v1/<endpoint>`) using java.net.HttpURLConnection + org.json.
 * Never embeds service_role keys. Email-only OTP authentication.
 */
class SupabaseAuthClient(
    private val supabaseUrl: String = SupabaseConfig.url,
    private val anonKey: String = SupabaseConfig.anonKey,
    private val clockEpochMs: () -> Long = { System.currentTimeMillis() },
) : SupabaseAuthGateway {

    override suspend fun requestOtp(email: String): AuthOtpResult = withContext(Dispatchers.IO) {
        if (!supabaseUrl.startsWith("https://") || anonKey.isBlank()) {
            return@withContext AuthOtpResult.TransientError("Supabase URL/anon key not configured.")
        }

        val endpoint = "${supabaseUrl.trimEnd('/')}/auth/v1/otp"
        val payload = JSONObject().apply {
            put("email", email.trim())
            put("create_user", true)
        }.toString()

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("apikey", anonKey)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            conn.outputStream.use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (code in 200..299) {
                AuthOtpResult.OtpSent("Verification code sent to $email. Check your inbox.")
            } else {
                val errJson = runCatching { JSONObject(body) }.getOrNull()
                val errCode = errJson?.optString("error_code", "")?.ifBlank {
                    errJson.optString("error", "")
                }.orEmpty()
                val errMsg = listOfNotNull(
                    errJson?.optString("error_description", "")?.takeIf { it.isNotBlank() && it != "null" },
                    errJson?.optString("msg", "")?.takeIf { it.isNotBlank() && it != "null" },
                    errJson?.optString("message", "")?.takeIf { it.isNotBlank() && it != "null" },
                ).firstOrNull() ?: "Could not send verification code (HTTP $code)."

                AuthOtpResult.AuthRejected(code = errCode.ifBlank { "HTTP_$code" }, message = errMsg)
            }
        } catch (e: IOException) {
            AuthOtpResult.TransientError(e.message ?: "Network unreachable during OTP request.")
        } catch (e: Exception) {
            AuthOtpResult.TransientError(e.message ?: "Unexpected OTP request transport error.")
        } finally {
            conn?.disconnect()
        }
    }

    override suspend fun verifyOtp(email: String, token: String): AuthRpcResult = withContext(Dispatchers.IO) {
        if (!supabaseUrl.startsWith("https://") || anonKey.isBlank()) {
            return@withContext AuthRpcResult.TransientError("Supabase URL/anon key not configured.")
        }

        val endpoint = "${supabaseUrl.trimEnd('/')}/auth/v1/verify"
        val payload = JSONObject().apply {
            put("email", email.trim())
            put("token", token.trim())
            put("type", "email")
        }.toString()

        executeTokenRequest(
            endpoint = endpoint,
            payload = payload,
            existingAuthEpochMs = null,
            isRefresh = false,
        )
    }

    override suspend fun refreshSession(
        refreshToken: String,
    ): AuthRpcResult = withContext(Dispatchers.IO) {
        if (!supabaseUrl.startsWith("https://") || anonKey.isBlank()) {
            return@withContext AuthRpcResult.TransientError("Supabase URL/anon key not configured.")
        }

        val endpoint = "${supabaseUrl.trimEnd('/')}/auth/v1/token?grant_type=refresh_token"
        val payload = JSONObject().apply {
            put("refresh_token", refreshToken)
        }.toString()

        executeTokenRequest(
            endpoint = endpoint,
            payload = payload,
            existingAuthEpochMs = null,
            isRefresh = true,
        )
    }

    override suspend fun fetchStaffProfile(accessToken: String): ServerStaffProfile? =
        withContext(Dispatchers.IO) {
            if (!supabaseUrl.startsWith("https://") || anonKey.isBlank() || accessToken.isBlank()) {
                return@withContext null
            }
            val endpoint = "${supabaseUrl.trimEnd('/')}/rest/v1/rpc/get_my_android_staff_profile"
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 6_000
                    readTimeout = 8_000
                    doOutput = true
                    setRequestProperty("apikey", anonKey)
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                }
                conn.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    ServerStaffProfile.fromJson(body)
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            } finally {
                conn?.disconnect()
            }
        }

    override suspend fun bootstrapInitialAdmin(
        accessToken: String,
        email: String?,
        fullName: String?,
    ): AuthBootstrapOutcome = withContext(Dispatchers.IO) {
        if (!supabaseUrl.startsWith("https://") || anonKey.isBlank() || accessToken.isBlank()) {
            return@withContext AuthBootstrapOutcome.TransientError("Sign in online first to set up Owner access.")
        }
        val payload = JSONObject().apply {
            put("p_email", email ?: JSONObject.NULL)
            put("p_full_name", fullName ?: JSONObject.NULL)
        }.toString()
        val (code, body) = executeAuthedPost(
            endpoint = "${supabaseUrl.trimEnd('/')}/rest/v1/rpc/bootstrap_initial_admin",
            accessToken = accessToken,
            payload = payload,
        ) ?: return@withContext AuthBootstrapOutcome.TransientError("Could not reach server. Check your internet connection.")

        val json = runCatching { JSONObject(body) }.getOrNull()
        val ok = json?.optBoolean("success", false) == true
        val message = json?.optString("message", "")?.takeIf { it.isNotBlank() }
        if (code in 200..299 && ok) {
            AuthBootstrapOutcome.Activated(message ?: "Owner access activated for this account.")
        } else if (code in 400..499 || (json != null && !ok)) {
            AuthBootstrapOutcome.Rejected(message ?: "Could not activate owner access (HTTP $code).")
        } else {
            AuthBootstrapOutcome.TransientError(message ?: "Server error while activating owner access (HTTP $code).")
        }
    }

    override suspend fun listStaffRoles(accessToken: String): StaffDirectoryOutcome = withContext(Dispatchers.IO) {
        if (!supabaseUrl.startsWith("https://") || anonKey.isBlank() || accessToken.isBlank()) {
            return@withContext StaffDirectoryOutcome.TransientError("Sign in online to load staff list.")
        }
        val (code, body) = executeAuthedPost(
            endpoint = "${supabaseUrl.trimEnd('/')}/rest/v1/rpc/list_android_staff_roles",
            accessToken = accessToken,
            payload = "{}",
        ) ?: return@withContext StaffDirectoryOutcome.TransientError("Could not reach server. Check your internet connection.")

        val json = runCatching { JSONObject(body) }.getOrNull()
        val ok = json?.optBoolean("success", false) == true
        if (code in 200..299 && ok) {
            val arr = json?.optJSONArray("staff")
            val list = buildList {
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val roleStr = item.optString("staff_role", StaffRole.CASHIER.name)
                        val role = StaffRole.entries.firstOrNull { it.name == roleStr }
                            ?: AuthSessionPolicy.mapRoleFromClaims(roleStr)
                        val email = item.optString("email", "").trim()
                        if (email.isBlank()) continue
                        add(
                            StaffRoleDirectoryEntry(
                                userId = item.optString("user_id", ""),
                                email = email,
                                fullName = item.optString("full_name", email).ifBlank { email },
                                staffRole = role,
                                isActive = item.optBoolean("is_active", true),
                            )
                        )
                    }
                }
            }
            StaffDirectoryOutcome.Loaded(list)
        } else {
            val msg = json?.optString("message", "")?.takeIf { it.isNotBlank() }
                ?: "Could not load staff list (HTTP $code)."
            if (code in 400..499 || json != null) {
                StaffDirectoryOutcome.Rejected(msg)
            } else {
                StaffDirectoryOutcome.TransientError(msg)
            }
        }
    }

    override suspend fun assignStaffRole(
        accessToken: String,
        email: String,
        staffRole: StaffRole,
        fullName: String?,
        isActive: Boolean,
    ): RoleUpdateOutcome = withContext(Dispatchers.IO) {
        if (!supabaseUrl.startsWith("https://") || anonKey.isBlank() || accessToken.isBlank()) {
            return@withContext RoleUpdateOutcome.TransientError("Sign in online to change staff roles.")
        }
        val payload = JSONObject().apply {
            put("p_email", email.trim())
            put("p_staff_role", staffRole.name)
            put("p_full_name", fullName ?: JSONObject.NULL)
            put("p_is_active", isActive)
        }.toString()
        val (code, body) = executeAuthedPost(
            endpoint = "${supabaseUrl.trimEnd('/')}/rest/v1/rpc/assign_android_staff_role",
            accessToken = accessToken,
            payload = payload,
        ) ?: return@withContext RoleUpdateOutcome.TransientError("Could not reach server. Check your internet connection.")

        val json = runCatching { JSONObject(body) }.getOrNull()
        val ok = json?.optBoolean("success", false) == true
        val msg = json?.optString("message", "")?.takeIf { it.isNotBlank() }
        if (code in 200..299 && ok) {
            RoleUpdateOutcome.Updated(msg ?: "Updated $email to ${staffRole.label}.")
        } else if (code in 400..499 || json != null) {
            RoleUpdateOutcome.Rejected(msg ?: "Could not update staff role (HTTP $code).")
        } else {
            RoleUpdateOutcome.TransientError(msg ?: "Server error updating staff role (HTTP $code).")
        }
    }

    private fun executeAuthedPost(
        endpoint: String,
        accessToken: String,
        payload: String,
    ): Pair<Int, String>? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 7_000
                readTimeout = 9_000
                doOutput = true
                setRequestProperty("apikey", anonKey)
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to body
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    override suspend fun signOut(accessToken: String): Boolean = withContext(Dispatchers.IO) {
        if (!supabaseUrl.startsWith("https://") || anonKey.isBlank() || accessToken.isBlank()) {
            return@withContext false
        }
        val endpoint = "${supabaseUrl.trimEnd('/')}/auth/v1/logout"
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6_000
                readTimeout = 8_000
                doOutput = true
                setRequestProperty("apikey", anonKey)
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun executeTokenRequest(
        endpoint: String,
        payload: String,
        existingAuthEpochMs: Long?,
        isRefresh: Boolean,
    ): AuthRpcResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("apikey", anonKey)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            conn.outputStream.use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            parseAuthResponse(
                httpStatus = code,
                responseBody = body,
                nowEpochMs = clockEpochMs(),
                existingAuthEpochMs = existingAuthEpochMs,
                isRefresh = isRefresh,
            )
        } catch (e: IOException) {
            AuthRpcResult.TransientError(e.message ?: "Network unreachable during authentication.")
        } catch (e: Exception) {
            AuthRpcResult.TransientError(e.message ?: "Unexpected authentication transport error.")
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        fun parseAuthResponse(
            httpStatus: Int,
            responseBody: String,
            nowEpochMs: Long,
            existingAuthEpochMs: Long? = null,
            isRefresh: Boolean = false,
        ): AuthRpcResult {
            if (httpStatus in 200..299) {
                return try {
                    val json = JSONObject(responseBody)
                    val accessToken = json.optString("access_token", "").trim()
                    val refreshToken = json.optString("refresh_token", "").trim()
                    val expiresInSec = json.optLong("expires_in", 3600L).coerceAtLeast(60L)
                    val expiresAtSec = json.optLong("expires_at", 0L)
                    val expiresAtMs = if (expiresAtSec > 1_000_000_000L) {
                        expiresAtSec * 1000L
                    } else {
                        nowEpochMs + (expiresInSec * 1000L)
                    }

                    val userObj = json.optJSONObject("user")
                    val userId = userObj?.optString("id", "")?.trim().orEmpty()
                    val email = userObj?.optString("email", "")?.trim().orEmpty()
                    if (accessToken.isBlank() || refreshToken.isBlank() || userId.isBlank()) {
                        return AuthRpcResult.TransientError("Supabase Auth returned an incomplete session payload.")
                    }

                    val userMeta = userObj?.optJSONObject("user_metadata")
                    val appMeta = userObj?.optJSONObject("app_metadata")
                    val displayName = listOfNotNull(
                        userMeta?.optString("full_name", "")?.takeIf { it.isNotBlank() && it != "null" },
                        userMeta?.optString("name", "")?.takeIf { it.isNotBlank() && it != "null" },
                        email.takeIf { it.isNotBlank() },
                    ).firstOrNull() ?: userId

                    // Stage 9.2 security rule: only read server-managed app_metadata (never user_metadata)
                    val rawRoleClaim = listOfNotNull(
                        appMeta?.optString("staff_role", "")?.takeIf { it.isNotBlank() && it != "null" },
                        appMeta?.optString("role", "")?.takeIf { it.isNotBlank() && it != "null" },
                    ).firstOrNull()

                    val mappedRole = AuthSessionPolicy.mapRoleFromClaims(rawRoleClaim, email)

                    AuthRpcResult.Success(
                        AuthUserSession(
                            accessToken = accessToken,
                            refreshToken = refreshToken,
                            expiresAtEpochMs = expiresAtMs,
                            userId = userId,
                            email = email,
                            displayName = displayName,
                            staffRole = mappedRole,
                            rawRoleClaim = rawRoleClaim,
                            lastAuthenticatedAtEpochMs = existingAuthEpochMs ?: nowEpochMs,
                            lastRefreshedAtEpochMs = if (isRefresh) nowEpochMs else null,
                        )
                    )
                } catch (e: Exception) {
                    AuthRpcResult.TransientError("Failed to parse auth session: ${e.message}")
                }
            }

            val errJson = runCatching { JSONObject(responseBody) }.getOrNull()
            val errCode = errJson?.optString("error_code", "")?.ifBlank {
                errJson.optString("error", "")
            }.orEmpty()
            val errMsg = listOfNotNull(
                errJson?.optString("error_description", "")?.takeIf { it.isNotBlank() && it != "null" },
                errJson?.optString("msg", "")?.takeIf { it.isNotBlank() && it != "null" },
                errJson?.optString("message", "")?.takeIf { it.isNotBlank() && it != "null" },
            ).firstOrNull() ?: "Authentication error (HTTP $httpStatus)."

            if (httpStatus in listOf(400, 401, 403, 422)) {
                return AuthRpcResult.AuthRejected(
                    code = errCode.ifBlank { "HTTP_$httpStatus" },
                    message = errMsg,
                )
            }

            return AuthRpcResult.TransientError(errMsg)
        }
    }
}
