package com.emofah.agencies.auth

import com.emofah.agencies.data.SupabaseConfig
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

interface SupabaseAuthGateway {
    suspend fun signInWithPassword(email: String, password: String): AuthRpcResult
    suspend fun refreshSession(refreshToken: String): AuthRpcResult
    suspend fun fetchStaffProfile(accessToken: String): ServerStaffProfile? = null
    suspend fun signOut(accessToken: String): Boolean
}

/**
 * Stage 9.1 — Direct GoTrue REST client (`/auth/v1/<endpoint>`) using java.net.HttpURLConnection + org.json.
 * Never embeds service_role keys.
 */
class SupabaseAuthClient(
    private val supabaseUrl: String = SupabaseConfig.url,
    private val anonKey: String = SupabaseConfig.anonKey,
    private val clockEpochMs: () -> Long = { System.currentTimeMillis() },
) : SupabaseAuthGateway {

    override suspend fun signInWithPassword(
        email: String,
        password: String,
    ): AuthRpcResult = withContext(Dispatchers.IO) {
        if (!supabaseUrl.startsWith("https://") || anonKey.isBlank()) {
            return@withContext AuthRpcResult.TransientError("Supabase URL/anon key not configured.")
        }

        val endpoint = "${supabaseUrl.trimEnd('/')}/auth/v1/token?grant_type=password"
        val payload = JSONObject().apply {
            put("email", email.trim())
            put("password", password)
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
