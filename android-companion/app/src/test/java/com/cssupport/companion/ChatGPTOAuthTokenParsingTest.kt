package com.cssupport.companion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for ChatGPTOAuth's token response parsing and error handling.
 *
 * The parseTokenResponse method is private, so we test it via reflection.
 * This covers the critical OAuth flow logic: successful token parsing,
 * error responses, missing fields, and edge cases.
 */
class ChatGPTOAuthTokenParsingTest {

    // ── parseTokenResponse: successful cases ─────────────────────────────

    @Test
    fun `parseTokenResponse should extract all fields from valid response`() {
        val json = JSONObject().apply {
            put("access_token", "eyJhbGciOiJSUzI1NiJ9.access")
            put("refresh_token", "v1.MRTxyz123refresh")
            put("expires_in", 7200L)
            put("token_type", "Bearer")
        }

        val response = invokeParseTokenResponse(json)
        assertEquals("eyJhbGciOiJSUzI1NiJ9.access", response.accessToken)
        assertEquals("v1.MRTxyz123refresh", response.refreshToken)
        assertEquals(7200L, response.expiresIn)
        assertEquals("Bearer", response.tokenType)
    }

    @Test
    fun `parseTokenResponse should handle missing refresh token`() {
        val json = JSONObject().apply {
            put("access_token", "access_token_only")
            put("expires_in", 3600L)
            put("token_type", "Bearer")
        }

        val response = invokeParseTokenResponse(json)
        assertEquals("access_token_only", response.accessToken)
        assertNull(response.refreshToken)
    }

    @Test
    fun `parseTokenResponse should default expires_in to 3600 when missing`() {
        val json = JSONObject().apply {
            put("access_token", "token_no_expiry")
            put("token_type", "Bearer")
        }

        val response = invokeParseTokenResponse(json)
        assertEquals(3600L, response.expiresIn)
    }

    @Test
    fun `parseTokenResponse should default token_type to Bearer when missing`() {
        val json = JSONObject().apply {
            put("access_token", "token_no_type")
            put("expires_in", 3600L)
        }

        val response = invokeParseTokenResponse(json)
        assertEquals("Bearer", response.tokenType)
    }

    @Test
    fun `parseTokenResponse should handle large expires_in values`() {
        val json = JSONObject().apply {
            put("access_token", "long_lived_token")
            put("refresh_token", "refresh")
            put("expires_in", 2592000L) // 30 days
            put("token_type", "Bearer")
        }

        val response = invokeParseTokenResponse(json)
        assertEquals(2592000L, response.expiresIn)
    }

    // ── parseTokenResponse: error cases ──────────────────────────────────

    @Test
    fun `parseTokenResponse should throw OAuthException for error response`() {
        val json = JSONObject().apply {
            put("error", "invalid_grant")
            put("error_description", "The authorization code has expired")
        }

        try {
            invokeParseTokenResponse(json)
            assertTrue("Should have thrown OAuthException", false)
        } catch (e: Exception) {
            val cause = e.cause ?: e
            assertTrue(cause is OAuthException)
            assertTrue(cause.message!!.contains("invalid_grant"))
            assertTrue(cause.message!!.contains("authorization code has expired"))
        }
    }

    @Test
    fun `parseTokenResponse should throw OAuthException for error without description`() {
        val json = JSONObject().apply {
            put("error", "server_error")
        }

        try {
            invokeParseTokenResponse(json)
            assertTrue("Should have thrown OAuthException", false)
        } catch (e: Exception) {
            val cause = e.cause ?: e
            assertTrue(cause is OAuthException)
            assertTrue(cause.message!!.contains("server_error"))
        }
    }

    @Test
    fun `parseTokenResponse should throw OAuthException for invalid_client error`() {
        val json = JSONObject().apply {
            put("error", "invalid_client")
            put("error_description", "Unknown client")
        }

        try {
            invokeParseTokenResponse(json)
            assertTrue("Should have thrown OAuthException", false)
        } catch (e: Exception) {
            val cause = e.cause ?: e
            assertTrue(cause is OAuthException)
            assertTrue(cause.message!!.contains("invalid_client"))
        }
    }

    @Test
    fun `parseTokenResponse should throw for missing access_token`() {
        val json = JSONObject().apply {
            put("refresh_token", "refresh_only")
            put("expires_in", 3600L)
        }

        try {
            invokeParseTokenResponse(json)
            assertTrue("Should have thrown", false)
        } catch (_: Exception) {
            // Expected -- getString("access_token") throws when key is missing
        }
    }

    // ── parseQueryString (private) ──────────────────────────────────────

    @Test
    fun `parseQueryString should parse standard OAuth callback params`() {
        val result = invokeParseQueryString("code=abc123&state=csrf_token_456")
        assertEquals("abc123", result["code"])
        assertEquals("csrf_token_456", result["state"])
    }

    @Test
    fun `parseQueryString should handle URL-encoded values`() {
        val result = invokeParseQueryString("error_description=The+user+denied+access&error=access_denied")
        assertEquals("access_denied", result["error"])
        assertEquals("The user denied access", result["error_description"])
    }

    @Test
    fun `parseQueryString should return empty map for blank input`() {
        val result = invokeParseQueryString("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseQueryString should skip malformed params without equals sign`() {
        val result = invokeParseQueryString("code=abc123&badparam&state=xyz")
        assertEquals("abc123", result["code"])
        assertEquals("xyz", result["state"])
        assertNull(result["badparam"])
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun invokeParseTokenResponse(json: JSONObject): OAuthTokenResponse {
        val method = ChatGPTOAuth::class.java.getDeclaredMethod("parseTokenResponse", JSONObject::class.java)
        method.isAccessible = true
        return method.invoke(ChatGPTOAuth, json) as OAuthTokenResponse
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeParseQueryString(query: String): Map<String, String> {
        val method = ChatGPTOAuth::class.java.getDeclaredMethod("parseQueryString", String::class.java)
        method.isAccessible = true
        return method.invoke(ChatGPTOAuth, query) as Map<String, String>
    }
}
