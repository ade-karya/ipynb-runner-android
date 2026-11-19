package com.adekarya.ipynbrunner

import com.adekarya.ipynbrunner.data.kaggle.KaggleClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class KaggleAuthTest {
    @Test
    fun `new KGAT token is sent as bearer`() {
        val header = KaggleClient.buildAuthorizationHeader("ade", " KGAT_abc123\n")
        assertEquals("Bearer KGAT_abc123", header)
    }

    @Test
    fun `legacy key still uses basic auth`() {
        val header = KaggleClient.buildAuthorizationHeader("ade", "0123456789abcdef")
        assertTrue(header.startsWith("Basic "))
        val decoded = String(Base64.getDecoder().decode(header.removePrefix("Basic ")))
        assertEquals("ade:0123456789abcdef", decoded)
    }

    @Test
    fun `bearer prefix and surrounding quotes are removed`() {
        assertEquals("KGAT_xyz", KaggleClient.normalizeApiKey("\"Bearer KGAT_xyz\""))
        assertEquals("KGAT_xyz", KaggleClient.normalizeApiKey("Bearer KGAT_xyz"))
    }

    @Test
    fun `full kaggle json can be pasted as key`() {
        val json = """{"username":"ade","key":"abcdef0123456789"}"""
        val header = KaggleClient.buildAuthorizationHeader("ade", json)
        val decoded = String(Base64.getDecoder().decode(header.removePrefix("Basic ")))
        assertEquals("ade:abcdef0123456789", decoded)
    }

    @Test
    fun `username at prefix is normalized`() {
        val header = KaggleClient.buildAuthorizationHeader("@ade", "key")
        val decoded = String(Base64.getDecoder().decode(header.removePrefix("Basic ")))
        assertEquals("ade:key", decoded)
    }
}
