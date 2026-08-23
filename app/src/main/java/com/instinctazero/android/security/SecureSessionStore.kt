package com.instinctazero.android.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class SessionCredentials(
    val baseUrl: String,
    val bearerToken: String,
    val deviceId: String,
)

internal interface SessionStorage {
    fun load(): SessionCredentials?
    fun save(baseUrl: String, bearerToken: String, deviceId: String)
    fun clear()
}

/** Stores only AES-GCM ciphertext for the bearer token; the key never leaves Keystore. */
internal class SecureSessionStore(context: Context) : SessionStorage {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(): SessionCredentials? {
        val baseUrl = preferences.getString(KEY_BASE_URL, null) ?: return null
        val deviceId = preferences.getString(KEY_DEVICE_ID, null) ?: return null
        val iv = preferences.getString(KEY_TOKEN_IV, null)?.decodeBase64() ?: return null
        val ciphertext = preferences.getString(KEY_TOKEN_CIPHERTEXT, null)?.decodeBase64() ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            SessionCredentials(baseUrl, cipher.doFinal(ciphertext).toString(Charsets.UTF_8), deviceId)
        } catch (_: Exception) {
            clear()
            null
        }
    }

    override fun save(baseUrl: String, bearerToken: String, deviceId: String) {
        require(bearerToken.isNotBlank()) { "Pairing returned an empty device token." }
        require(deviceId.isNotBlank()) { "Pairing returned an empty device id." }
        val normalizedUrl = normalizeSecureBaseUrl(baseUrl)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(bearerToken.toByteArray(Charsets.UTF_8))
        check(
            preferences.edit()
                .putString(KEY_BASE_URL, normalizedUrl)
                .putString(KEY_DEVICE_ID, deviceId)
                .putString(KEY_TOKEN_IV, cipher.iv.encodeBase64())
                .putString(KEY_TOKEN_CIPHERTEXT, ciphertext.encodeBase64())
                .commit(),
        ) { "Could not persist the paired device credential." }
    }

    fun isPaired(): Boolean = load() != null

    override fun clear() {
        preferences.edit()
            .remove(KEY_BASE_URL)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_TOKEN_IV)
            .remove(KEY_TOKEN_CIPHERTEXT)
            .apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFERENCES_NAME = "instinctazero_secure_session"
        private const val KEY_BASE_URL = "server_base_url"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_TOKEN_IV = "token_iv"
        private const val KEY_TOKEN_CIPHERTEXT = "token_ciphertext"
        private const val KEY_ALIAS = "instinctazero_device_bearer_v1"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128

        fun normalizeSecureBaseUrl(value: String): String {
            val normalized = value.trim().trimEnd('/')
            val uri = runCatching { URI(normalized) }.getOrNull()
                ?: throw IllegalArgumentException("Invalid InstinctaZero server URL.")
            require(uri.scheme.equals("https", ignoreCase = true)) {
                "InstinctaZero pairing requires HTTPS."
            }
            require(
                !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null && uri.rawQuery == null,
            ) {
                "Invalid InstinctaZero server URL."
            }
            return normalized
        }
    }
}

private fun ByteArray.encodeBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
private fun String.decodeBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
