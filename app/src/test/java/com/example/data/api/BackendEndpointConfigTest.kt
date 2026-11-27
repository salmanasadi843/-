package com.example.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BackendEndpointConfigTest {
    @Test
    fun blankEndpointKeepsBackendDisabled() {
        assertNull(normalizeBackendBaseUrl("  "))
    }

    @Test
    fun endpointIsNormalizedWithTrailingSlash() {
        assertEquals(
            "https://api.example.test/platform/",
            normalizeBackendBaseUrl("https://api.example.test/platform")
        )
    }

    @Test
    fun existingTrailingSlashIsPreserved() {
        assertEquals(
            "https://api.example.test/",
            normalizeBackendBaseUrl("https://api.example.test/")
        )
    }

    @Test
    fun nonHttpsEndpointIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            normalizeBackendBaseUrl("http://api.example.test")
        }
    }

    @Test
    fun endpointWithCredentialsOrQueryIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            normalizeBackendBaseUrl("https://user:pass@api.example.test")
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeBackendBaseUrl("https://api.example.test?token=secret")
        }
    }
}
