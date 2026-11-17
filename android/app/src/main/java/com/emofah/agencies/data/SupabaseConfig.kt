package com.emofah.agencies.data

import com.emofah.agencies.BuildConfig

/**
 * Shared Emofah Supabase backend configuration.
 * Reads publishable URL and anon key from BuildConfig (populated via local.properties or CI vars).
 * Never embed service_role credentials in the Android client (Blueprint Part K & N5).
 */
object SupabaseConfig {
    val url: String = BuildConfig.SUPABASE_URL
    val anonKey: String = BuildConfig.SUPABASE_ANON_KEY
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

    val cloudRecognitionEndpoint: String
        get() = when {
            customCloudRecognitionUrl.startsWith("https://") -> customCloudRecognitionUrl.trim()
            isConfigured -> "${url.trimEnd('/')}/functions/v1/recognize-product"
            else -> ""
        }

    val receiptExtractionEndpoint: String
        get() = if (isConfigured) "${url.trimEnd('/')}/functions/v1/extract-receipt" else ""
}
