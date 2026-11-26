package com.emofah.agencies.data

import com.emofah.agencies.BuildConfig

enum class SupabaseKeyType {
    PUBLISHABLE,
    LEGACY_JWT,
    INVALID,
}

data class SupabaseKeyValidation(
    val isValid: Boolean,
    val keyType: SupabaseKeyType,
    val errorMessage: String? = null,
    val isPrivilegedSecret: Boolean = false,
)

/**
 * Shared Emofah Supabase backend configuration.
 *
 * Implements modern Supabase Publishable Key (sb_publishable_...) as primary client credential,
 * with optional legacy JWT (eyJ...) compatibility fallback.
 * Strictly prevents privileged keys (service_role / sb_secret_) from being configured.
 *
 * Authoritative Project ID: nbmmfdiqywmkjaltezro
 * Authoritative Project URL: https://nbmmfdiqywmkjaltezro.supabase.co
 */
object SupabaseConfig {
    const val CANONICAL_PROJECT_ID = "nbmmfdiqywmkjaltezro"
    const val CANONICAL_SUPABASE_URL = "https://nbmmfdiqywmkjaltezro.supabase.co"
    const val PRIMARY_PUBLISHABLE_KEY = "sb_publishable_IYNhHXjZ1lPPdRFIpKuonA_ydMHV4t3"
    const val DEFAULT_STORAGE_PATH = "storage/v1/object/public/product-images/"

    val url: String = runCatching { BuildConfig.SUPABASE_URL }.getOrNull()?.trim()
        ?.takeIf { it.startsWith("https://") } ?: CANONICAL_SUPABASE_URL

    val projectId: String = CANONICAL_PROJECT_ID

    /**
     * Active client API key resolved according to precedence:
     * 1. Modern publishable key (sb_publishable_...) — Primary
     * 2. Legacy JWT anon key (eyJ...) — Optional compatibility
     * 3. Fallback to supplied PRIMARY_PUBLISHABLE_KEY
     */
    val apiKey: String = resolveActiveKey()

    /**
     * Backward-compatible alias for all existing client classes and unit tests.
     */
    val anonKey: String
        get() = apiKey

    val activeKeyType: SupabaseKeyType = validateKey(apiKey).keyType

    val redactedKey: String
        get() = redactKey(apiKey)

    val publicStorageBaseUrl: String = runCatching { BuildConfig.PUBLIC_STORAGE_BASE_URL }.getOrNull()?.trim()
        ?.takeIf { it.startsWith("https://") } ?: "$CANONICAL_SUPABASE_URL/$DEFAULT_STORAGE_PATH"

    val customCloudRecognitionUrl: String = runCatching { BuildConfig.CLOUD_RECOGNITION_URL }.getOrNull()?.trim().orEmpty()

    @Volatile
    private var accessTokenProvider: (() -> String?)? = null

    fun registerAccessTokenProvider(provider: (() -> String?)?) {
        accessTokenProvider = provider
    }

    fun bearerToken(fallbackKey: String = apiKey): String {
        val token = runCatching { accessTokenProvider?.invoke() }.getOrNull()?.trim()
        return if (!token.isNullOrBlank()) token else fallbackKey
    }

    val isConfigured: Boolean
        get() = url.startsWith("https://") && apiKey.isNotBlank() && activeKeyType != SupabaseKeyType.INVALID

    /**
     * Validates a candidate Supabase client credential.
     * Rejects privileged secret keys immediately and classifies public keys into
     * PUBLISHABLE or LEGACY_JWT.
     */
    fun validateKey(candidate: String?): SupabaseKeyValidation {
        val trimmed = candidate?.trim().orEmpty()
        if (trimmed.isBlank()) {
            return SupabaseKeyValidation(
                isValid = false,
                keyType = SupabaseKeyType.INVALID,
                errorMessage = "API key is blank or missing.",
            )
        }

        // Security Invariant: Detect and reject privileged / service_role keys
        if (trimmed.startsWith("sb_secret_", ignoreCase = true) ||
            trimmed.contains("service_role", ignoreCase = true)
        ) {
            return SupabaseKeyValidation(
                isValid = false,
                keyType = SupabaseKeyType.INVALID,
                isPrivilegedSecret = true,
                errorMessage = "CRITICAL SECURITY VIOLATION: Privileged secret/service_role key detected. Never configure secret credentials in client applications.",
            )
        }

        // Modern Publishable Key format (sb_publishable_...)
        if (trimmed.startsWith("sb_publishable_") && trimmed.length >= 25) {
            return SupabaseKeyValidation(
                isValid = true,
                keyType = SupabaseKeyType.PUBLISHABLE,
            )
        }

        // Legacy JWT anon key format (eyJ...)
        if (trimmed.startsWith("eyJ")) {
            val parts = trimmed.split(".")
            if (parts.size == 3) {
                return SupabaseKeyValidation(
                    isValid = true,
                    keyType = SupabaseKeyType.LEGACY_JWT,
                )
            }
        }

        return SupabaseKeyValidation(
            isValid = false,
            keyType = SupabaseKeyType.INVALID,
            errorMessage = "Unsupported key format: ${redactKey(trimmed)}. Expected modern publishable key (sb_publishable_...) or legacy JWT (eyJ...).",
        )
    }

    /**
     * Safely redacts an API key for logs and diagnostics without exposing credentials.
     */
    fun redactKey(rawKey: String?): String {
        val trimmed = rawKey?.trim().orEmpty()
        if (trimmed.isBlank()) return "(none)"
        if (trimmed.length <= 10) return "${trimmed.take(3)}***"
        return if (trimmed.startsWith("sb_publishable_")) {
            "sb_publishable_...${trimmed.takeLast(4)}"
        } else if (trimmed.startsWith("eyJ")) {
            "eyJ...${trimmed.takeLast(4)}"
        } else {
            "${trimmed.take(4)}...${trimmed.takeLast(4)}"
        }
    }

    /**
     * Resolves the active key from BuildConfig with precedence and security checks.
     */
    private fun resolveActiveKey(): String {
        val rawFromBuildConfig = runCatching { BuildConfig.SUPABASE_ANON_KEY }.getOrNull()?.trim().orEmpty()
        val validation = validateKey(rawFromBuildConfig)
        if (validation.isValid) {
            return rawFromBuildConfig
        }
        if (validation.isPrivilegedSecret) {
            throw SecurityException(validation.errorMessage)
        }
        return PRIMARY_PUBLISHABLE_KEY
    }

    /**
     * Resolves relative filenames stored in `public.products.image_url` against
     * `PUBLIC_STORAGE_BASE_URL`, while keeping valid http(s) URLs intact.
     */
    fun normalizeProductImageUrl(rawImageUrl: String?): String? {
        val trimmed = rawImageUrl?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
            ?: return null
        if (trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)) {
            return trimmed
        }
        val base = publicStorageBaseUrl.trimEnd('/')
        if (base.isBlank()) return trimmed
        val cleanPath = trimmed.trimStart('/')
            .removePrefix("product-images/")
        return "$base/$cleanPath"
    }

    val cloudRecognitionEndpoint: String
        get() = when {
            customCloudRecognitionUrl.startsWith("https://") -> customCloudRecognitionUrl.trim()
            isConfigured -> "${url.trimEnd('/')}/functions/v1/recognize-product"
            else -> ""
        }

    val receiptExtractionEndpoint: String
        get() = if (isConfigured) "${url.trimEnd('/')}/functions/v1/extract-receipt" else ""
}
