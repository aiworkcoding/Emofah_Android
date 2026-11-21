package com.emofah.agencies.data

import com.emofah.agencies.BuildConfig

/**
 * Shared Emofah Supabase backend configuration.
 * Reads publishable URL, anon key, and public product-images storage base URL from BuildConfig.
 * Never embed service_role credentials in the Android client.
 */
object SupabaseConfig {
    val url: String = BuildConfig.SUPABASE_URL
    val anonKey: String = BuildConfig.SUPABASE_ANON_KEY
    val publicStorageBaseUrl: String = BuildConfig.PUBLIC_STORAGE_BASE_URL
    val customCloudRecognitionUrl: String = BuildConfig.CLOUD_RECOGNITION_URL

    @Volatile
    private var accessTokenProvider: (() -> String?)? = null

    fun registerAccessTokenProvider(provider: (() -> String?)?) {
        accessTokenProvider = provider
    }

    fun bearerToken(fallbackKey: String = anonKey): String {
        val token = runCatching { accessTokenProvider?.invoke() }.getOrNull()?.trim()
        return if (!token.isNullOrBlank()) token else fallbackKey
    }

    val isConfigured: Boolean
        get() = url.startsWith("https://") && anonKey.isNotBlank()

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
