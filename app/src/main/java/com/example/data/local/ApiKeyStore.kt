package com.example.data.local

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object ApiKeyStore {

    private const val PREFS = "ostadyar_secure_settings"
    private const val KEY_NAME = "gemini_api_key"

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "ostadyar_api_key_key"

    fun saveGeminiApiKey(
        context: Context,
        apiKey: String
    ) {
        val cleanKey = apiKey.trim()

        if (cleanKey.isBlank()) {
            removeGeminiApiKey(context)
            return
        }

        val encrypted = encrypt(cleanKey)

        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NAME, encrypted)
            .apply()
    }

    fun getGeminiApiKey(
        context: Context
    ): String? {
        val encoded = context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_NAME, null)
            ?: return null

        return try {
            decrypt(encoded)
        } catch (_: Exception) {
            null
        }
    }

    fun removeGeminiApiKey(
        context: Context
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_NAME)
            .apply()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore =
            KeyStore.getInstance(ANDROID_KEYSTORE).apply {
                load(null)
            }

        val existing = keyStore.getKey(KEY_ALIAS, null)

        if (existing is SecretKey) {
            return existing
        }

        val generator =
            KeyGenerator.getInstance("AES", ANDROID_KEYSTORE)

        generator.init(256)

        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding")

        cipher.init(
            Cipher.ENCRYPT_MODE,
            getOrCreateSecretKey()
        )

        val iv = cipher.iv

        val encrypted =
            cipher.doFinal(
                value.toByteArray(StandardCharsets.UTF_8)
            )

        val combined =
            ByteArray(iv.size + encrypted.size)

        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(
            encrypted,
            0,
            combined,
            iv.size,
            encrypted.size
        )

        return Base64.encodeToString(
            combined,
            Base64.NO_WRAP
        )
    }

    private fun decrypt(encoded: String): String {
        val combined =
            Base64.decode(encoded, Base64.NO_WRAP)

        require(combined.size > 12)

        val iv = combined.copyOfRange(0, 12)
        val encrypted =
            combined.copyOfRange(12, combined.size)

        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding")

        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(),
            GCMParameterSpec(128, iv)
        )

        return String(
            cipher.doFinal(encrypted),
            StandardCharsets.UTF_8
        )
    }
}
