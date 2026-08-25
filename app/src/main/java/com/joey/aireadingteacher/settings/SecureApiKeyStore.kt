package com.joey.aireadingteacher.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SecureApiKeyStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    suspend fun save(apiKey: String) = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "API key must not be blank" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val encrypted = cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8))
        val payload = listOf(cipher.iv, encrypted).joinToString(SEPARATOR) {
            Base64.encodeToString(it, Base64.NO_WRAP)
        }
        preferences.edit(commit = true) {
            putString(ENCRYPTED_API_KEY, payload)
        }
    }

    suspend fun read(): String? = withContext(Dispatchers.IO) {
        val payload = preferences.getString(ENCRYPTED_API_KEY, null) ?: return@withContext null
        val parts = payload.split(SEPARATOR, limit = 2)
        require(parts.size == 2) { "Stored API key is corrupted" }
        val iv = Base64.decode(parts[0], Base64.DEFAULT)
        val encrypted = Base64.decode(parts[1], Base64.DEFAULT)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    suspend fun isConfigured(): Boolean = !read().isNullOrBlank()

    suspend fun clear() = withContext(Dispatchers.IO) {
        preferences.edit(commit = true) {
            remove(ENCRYPTED_API_KEY)
        }
    }

    private fun getOrCreateKey(): SecretKey = existingKeyOrNull() ?: KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES,
        ANDROID_KEY_STORE,
    ).run {
        init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        generateKey()
    }

    private fun existingKey(): SecretKey = existingKeyOrNull()
        ?: error("The Android Keystore key for the API credential is missing")

    private fun existingKeyOrNull(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "ai_reading_teacher_api_key"
        private const val PREFERENCES_NAME = "secure_credentials"
        private const val ENCRYPTED_API_KEY = "encrypted_openai_api_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val SEPARATOR = ":"
    }
}
