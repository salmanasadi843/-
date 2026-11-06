package com.example.data.api

import com.example.BuildConfig
import java.net.URI

/**
 * Central source of truth for the future Darsyar backend endpoint.
 *
 * The endpoint is deliberately optional: current Firebase and Room flows do not
 * read this value and remain unchanged until the backend integration is approved.
 */
object BackendEndpointConfig {
    val baseUrl: String? by lazy {
        normalizeBackendBaseUrl(BuildConfig.DARSYAR_API_BASE_URL)
    }

    val isConfigured: Boolean
        get() = baseUrl != null

    fun requireBaseUrl(): String =
        baseUrl ?: error(
            "Darsyar backend is not configured. Set DARSYAR_API_BASE_URL when building the app."
        )
}

internal fun normalizeBackendBaseUrl(rawValue: String): String? {
    val value = rawValue.trim()
    if (value.isEmpty()) return null

    val uri = try {
        URI(value)
    } catch (exception: Exception) {
        throw IllegalArgumentException("DARSYAR_API_BASE_URL is not a valid URL.", exception)
    }

    require(uri.isAbsolute && uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
        "DARSYAR_API_BASE_URL must be an absolute HTTPS URL with a host."
    }
    require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
        "DARSYAR_API_BASE_URL must not contain credentials, a query, or a fragment."
    }

    return if (value.endsWith("/")) value else "$value/"
}
