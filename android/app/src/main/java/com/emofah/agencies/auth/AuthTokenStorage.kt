package com.emofah.agencies.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecureKeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun remove(key: String)
}

/**
 * Encrypts sensitive Supabase Auth tokens (access_token / refresh_token) using AndroidKeyStore AES-GCM
 * before persisting in private SharedPreferences.
 */
class AndroidKeyStoreTokenStorage(
    context: Context,
    prefsName: String = PREFS_NAME,
    private val keyAlias: String = KEY_ALIAS,
) : SecureKeyValueStore {

    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun getString(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        if (!stored.startsWith(PREFIX_ENC)) {
            return null
        }
        return decrypt(stored.removePrefix(PREFIX_ENC))
    }

    override fun putString(key: String, value: String?) {
        if (value == null) {
            remove(key)
            return
        }
        val encrypted = encrypt(value) ?: return
        prefs.edit().putString(key, PREFIX_ENC + encrypted).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    private fun getOrCreateKey(): SecretKey? {
        return runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
            if (existing != null) {
                return@runCatching existing.secretKey
            }
            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGen.init(spec)
            keyGen.generateKey()
        }.getOrNull()
    }

    private fun encrypt(plainText: String): String? {
        val secretKey = getOrCreateKey() ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(1 + iv.size + cipherBytes.size)
            combined[0] = iv.size.toByte()
            System.arraycopy(iv, 0, combined, 1, iv.size)
            System.arraycopy(cipherBytes, 0, combined, 1 + iv.size, cipherBytes.size)
            Base64.encodeToString(combined, Base64.NO_WRAP)
        }.getOrNull()
    }

    private fun decrypt(encoded: String): String? {
        val secretKey = getOrCreateKey() ?: return null
        return runCatching {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size < 2) return@runCatching null
            val ivLen = combined[0].toInt() and 0xFF
            if (combined.size <= 1 + ivLen) return@runCatching null
            val iv = combined.copyOfRange(1, 1 + ivLen)
            val cipherBytes = combined.copyOfRange(1 + ivLen, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        }.getOrNull()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFS_NAME = "emofah_auth_secure_store"
        private const val KEY_ALIAS = "emofah_auth_session_key_v1"
        private const val PREFIX_ENC = "enc_v1:"
    }
}

class AuthSessionStore(
    private val keyValueStore: SecureKeyValueStore,
) {
    fun readSession(): AuthUserSession? {
        return AuthUserSession.fromJsonString(keyValueStore.getString(KEY_SESSION_JSON))
    }

    fun saveSession(session: AuthUserSession) {
        keyValueStore.putString(KEY_SESSION_JSON, session.toJsonString())
        keyValueStore.putString(KEY_EXPIRED_FLAG, "false")
        keyValueStore.remove(KEY_LAST_ERROR)
    }

    fun markSessionExpired(reason: String) {
        keyValueStore.putString(KEY_EXPIRED_FLAG, "true")
        keyValueStore.putString(KEY_LAST_ERROR, reason)
    }

    fun isSessionMarkedExpired(): Boolean {
        return keyValueStore.getString(KEY_EXPIRED_FLAG) == "true"
    }

    fun readLastError(): String? {
        return keyValueStore.getString(KEY_LAST_ERROR)?.takeIf { it.isNotBlank() }
    }

    fun clearSession() {
        keyValueStore.remove(KEY_SESSION_JSON)
        keyValueStore.remove(KEY_EXPIRED_FLAG)
        keyValueStore.remove(KEY_LAST_ERROR)
    }

    companion object {
        private const val KEY_SESSION_JSON = "supabase_session_json"
        private const val KEY_EXPIRED_FLAG = "supabase_session_expired"
        private const val KEY_LAST_ERROR = "supabase_session_error"
    }
}
