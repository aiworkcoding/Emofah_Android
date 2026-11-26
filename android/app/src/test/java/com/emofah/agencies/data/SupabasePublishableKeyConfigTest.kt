package com.emofah.agencies.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabasePublishableKeyConfigTest {

    @Test
    fun validateKey_recognizesModernPublishableKey() {
        val validation = SupabaseConfig.validateKey(SupabaseConfig.PRIMARY_PUBLISHABLE_KEY)
        assertTrue(validation.isValid)
        assertEquals(SupabaseKeyType.PUBLISHABLE, validation.keyType)
        assertFalse(validation.isPrivilegedSecret)
        assertNull(validation.errorMessage)
    }

    @Test
    fun validateKey_recognizesLegacyJwtAnonKey() {
        val mockJwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im5ibW1mZGlxeXdta2phbHRlenJvIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Njc1NTcwNzQsImV4cCI6MjA4MzEzMzA3NH0.LvAJ0xmBohmOKW-7Va8poTsbqJACNMY87SAnZnzqqTs"
        val validation = SupabaseConfig.validateKey(mockJwt)
        assertTrue(validation.isValid)
        assertEquals(SupabaseKeyType.LEGACY_JWT, validation.keyType)
        assertFalse(validation.isPrivilegedSecret)
    }

    @Test
    fun validateKey_strictlyRejectsPrivilegedSecretAndServiceRoleKeys() {
        val secretKey = "sb_secret_abcdef1234567890abcdef1234567890"
        val secretValidation = SupabaseConfig.validateKey(secretKey)
        assertFalse(secretValidation.isValid)
        assertEquals(SupabaseKeyType.INVALID, secretValidation.keyType)
        assertTrue(secretValidation.isPrivilegedSecret)
        assertTrue(secretValidation.errorMessage?.contains("CRITICAL SECURITY VIOLATION") == true)

        val serviceRoleKey = "service_role_key_should_never_be_in_client"
        val srValidation = SupabaseConfig.validateKey(serviceRoleKey)
        assertFalse(srValidation.isValid)
        assertEquals(SupabaseKeyType.INVALID, srValidation.keyType)
        assertTrue(srValidation.isPrivilegedSecret)
    }

    @Test
    fun validateKey_rejectsMalformedOrEmptyKeys() {
        assertFalse(SupabaseConfig.validateKey("").isValid)
        assertFalse(SupabaseConfig.validateKey(null).isValid)
        assertFalse(SupabaseConfig.validateKey("short").isValid)
    }

    @Test
    fun redactKey_safelyMasksCredentialsWithoutExposingFullValue() {
        val redactedPublishable = SupabaseConfig.redactKey(SupabaseConfig.PRIMARY_PUBLISHABLE_KEY)
        assertEquals("sb_publishable_...V4t3", redactedPublishable)

        val redactedJwt = SupabaseConfig.redactKey("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload.sig9999")
        assertEquals("eyJ...9999", redactedJwt)

        assertEquals("(none)", SupabaseConfig.redactKey(""))
        assertEquals("(none)", SupabaseConfig.redactKey(null))
    }

    @Test
    fun supabaseConfig_initializesWithPrimaryPublishableKeyDefaults() {
        assertEquals("https://nbmmfdiqywmkjaltezro.supabase.co", SupabaseConfig.url)
        assertEquals("nbmmfdiqywmkjaltezro", SupabaseConfig.projectId)
        assertTrue(SupabaseConfig.isConfigured)
        assertEquals(SupabaseConfig.PRIMARY_PUBLISHABLE_KEY, SupabaseConfig.apiKey)
        assertEquals(SupabaseConfig.apiKey, SupabaseConfig.anonKey)
        assertEquals(SupabaseKeyType.PUBLISHABLE, SupabaseConfig.activeKeyType)
    }

    @Test
    fun normalizeProductImageUrl_resolvesRelativeAndPreservesAbsoluteUrls() {
        val absoluteUrl = "https://example.com/item.png"
        assertEquals(absoluteUrl, SupabaseConfig.normalizeProductImageUrl(absoluteUrl))

        val relative = "product-images/pen.jpg"
        assertEquals(
            "https://nbmmfdiqywmkjaltezro.supabase.co/storage/v1/object/public/product-images/pen.jpg",
            SupabaseConfig.normalizeProductImageUrl(relative)
        )

        assertNull(SupabaseConfig.normalizeProductImageUrl(null))
        assertNull(SupabaseConfig.normalizeProductImageUrl(""))
        assertNull(SupabaseConfig.normalizeProductImageUrl("null"))
    }
}
