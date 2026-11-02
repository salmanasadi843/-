package com.example.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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
    private const val GROK_KEY_NAME = "grok_api_key"
    private const val SPEECHMATICS_KEY_NAME = "speechmatics_api_key"

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "ostadyar_api_key_key"


    fun saveGrokApiKey(context: Context, apiKey: String) {
        val cleanKey = apiKey.trim()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (cleanKey.isBlank()) { prefs.edit().remove(GROK_KEY_NAME).apply(); return }
        prefs.edit().putString(GROK_KEY_NAME, encrypt(cleanKey)).apply()
    }

    fun getGrokApiKey(context: Context): String? {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(GROK_KEY_NAME, null) ?: return null
        return try { decrypt(encoded) } catch (_: Exception) { null }
    }

    fun removeGrokApiKey(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(GROK_KEY_NAME).apply()
    }

    fun saveSpeechmaticsApiKey(context: Context, apiKey: String) {
        val cleanKey = apiKey.trim()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (cleanKey.isBlank()) {
            prefs.edit().remove(SPEECHMATICS_KEY_NAME).apply()
            return
        }
        prefs.edit().putString(SPEECHMATICS_KEY_NAME, encrypt(cleanKey)).apply()
    }

    fun getSpeechmaticsApiKey(context: Context): String? {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(SPEECHMATICS_KEY_NAME, null) ?: return null
        return try { decrypt(encoded) } catch (_: Exception) { null }
    }

    fun removeSpeechmaticsApiKey(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(SPEECHMATICS_KEY_NAME).apply()
    }

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
            KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )

        val spec =
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or
                    KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE
                )
                .setRandomizedEncryptionRequired(true)
                .build()

        generator.init(spec)

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

        System.arraycopy(
            iv,
            0,
            combined,
            0,
            iv.size
        )

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
            Base64.decode(
                encoded,
                Base64.NO_WRAP
            )

        require(combined.size > 12)

        val iv =
            combined.copyOfRange(
                0,
                12
            )

        val encrypted =
            combined.copyOfRange(
                12,
                combined.size
            )

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
