package com.cssupport.companion

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for AuthManager credential storage.
 *
 * AuthManager's constructor tries to create EncryptedSharedPreferences which requires
 * Android Keystore — that fails in unit tests. The fallback behavior uses plain
 * SharedPreferences (backupPrefs). We use MockK to mock Context.getSharedPreferences
 * and inject a FakeSharedPreferences so the constructor falls through to the
 * non-encrypted path, letting us test all public credential management methods.
 */
class AuthManagerTest {

    private lateinit var authManager: AuthManager
    private lateinit var backupPrefs: FakeSharedPreferences

    @Before
    fun setUp() {
        backupPrefs = FakeSharedPreferences()
        val context = mockk<Context>(relaxed = true)

        // AuthManager's constructor calls getSharedPreferences for backup prefs,
        // then tries EncryptedSharedPreferences (which will throw in unit tests).
        // The catch block falls through to using backupPrefs as the primary store.
        every { context.getSharedPreferences(any(), any()) } returns backupPrefs

        authManager = AuthManager(context)
    }

    // ── saveLLMCredentials / loadLLMConfig ────────────────────────────────

    @Test
    fun `should save and load LLM credentials for OpenAI provider`() {
        authManager.saveLLMCredentials(
            provider = LLMProvider.OPENAI,
            apiKey = "sk-proj-abc123",
            model = "gpt-5-mini",
        )

        val config = authManager.loadLLMConfig()
        assertNotNull(config)
        assertEquals(LLMProvider.OPENAI, config!!.provider)
        assertEquals("sk-proj-abc123", config.apiKey)
        assertEquals("gpt-5-mini", config.model)
    }

    @Test
    fun `should save and load LLM credentials for Azure provider with endpoint and version`() {
        authManager.saveLLMCredentials(
            provider = LLMProvider.AZURE_OPENAI,
            apiKey = "azure-key-456",
            model = "gpt-5-nano",
            endpoint = "https://myresource.openai.azure.com",
            apiVersion = "2024-10-21",
        )

        val config = authManager.loadLLMConfig()
        assertNotNull(config)
        assertEquals(LLMProvider.AZURE_OPENAI, config!!.provider)
        assertEquals("azure-key-456", config.apiKey)
        assertEquals("gpt-5-nano", config.model)
        assertEquals("https://myresource.openai.azure.com", config.endpoint)
        assertEquals("2024-10-21", config.apiVersion)
    }

    @Test
    fun `should save and load LLM credentials for Anthropic provider`() {
        authManager.saveLLMCredentials(
            provider = LLMProvider.ANTHROPIC,
            apiKey = "sk-ant-api03-xyz",
            model = "claude-sonnet-4-20250514",
        )

        val config = authManager.loadLLMConfig()
        assertNotNull(config)
        assertEquals(LLMProvider.ANTHROPIC, config!!.provider)
        assertEquals("sk-ant-api03-xyz", config.apiKey)
    }

    @Test
    fun `should save and load LLM credentials for Custom provider with endpoint`() {
        authManager.saveLLMCredentials(
            provider = LLMProvider.CUSTOM,
            apiKey = "custom-key",
            model = "local-model",
            endpoint = "http://localhost:8080",
        )

        val config = authManager.loadLLMConfig()
        assertNotNull(config)
        assertEquals(LLMProvider.CUSTOM, config!!.provider)
        assertEquals("http://localhost:8080", config.endpoint)
    }

    @Test
    fun `loadLLMConfig should return null when no credentials are stored`() {
        val config = authManager.loadLLMConfig()
        assertNull(config)
    }

    @Test
    fun `loadLLMConfig should return null when provider name is invalid`() {
        backupPrefs.data["llm_provider"] = "NONEXISTENT_PROVIDER"
        backupPrefs.data["llm_api_key"] = "key"
        backupPrefs.data["llm_model"] = "model"

        val config = authManager.loadLLMConfig()
        assertNull(config)
    }

    @Test
    fun `loadLLMConfig should return null when api key is missing`() {
        backupPrefs.data["llm_provider"] = "OPENAI"
        backupPrefs.data["llm_model"] = "gpt-5-mini"
        // No api key stored

        val config = authManager.loadLLMConfig()
        assertNull(config)
    }

    @Test
    fun `loadLLMConfig should return null when model is missing`() {
        backupPrefs.data["llm_provider"] = "OPENAI"
        backupPrefs.data["llm_api_key"] = "key"
        // No model stored

        val config = authManager.loadLLMConfig()
        assertNull(config)
    }

    @Test
    fun `should overwrite credentials when saved again`() {
        authManager.saveLLMCredentials(
            provider = LLMProvider.OPENAI,
            apiKey = "old-key",
            model = "gpt-5-mini",
        )
        authManager.saveLLMCredentials(
            provider = LLMProvider.ANTHROPIC,
            apiKey = "new-key",
            model = "claude-sonnet-4-20250514",
        )

        val config = authManager.loadLLMConfig()
        assertEquals(LLMProvider.ANTHROPIC, config!!.provider)
        assertEquals("new-key", config.apiKey)
    }

    // ── getSavedApiKey ───────────────────────────────────────────────────

    @Test
    fun `getSavedApiKey should return stored key`() {
        authManager.saveLLMCredentials(
            provider = LLMProvider.OPENAI,
            apiKey = "sk-test-key-123",
            model = "gpt-5-mini",
        )

        assertEquals("sk-test-key-123", authManager.getSavedApiKey())
    }

    @Test
    fun `getSavedApiKey should return null when no key is stored`() {
        assertNull(authManager.getSavedApiKey())
    }

    // ── hasLLMCredentials ───────────────────────────────────────────────

    @Test
    fun `hasLLMCredentials should return false when no credentials stored`() {
        assertFalse(authManager.hasLLMCredentials())
    }

    @Test
    fun `hasLLMCredentials should return true after saving credentials`() {
        authManager.saveLLMCredentials(
            provider = LLMProvider.OPENAI,
            apiKey = "sk-key",
            model = "model",
        )

        assertTrue(authManager.hasLLMCredentials())
    }

    // ── clearLLMCredentials ─────────────────────────────────────────────

    @Test
    fun `clearLLMCredentials should remove all LLM credential keys`() {
        authManager.saveLLMCredentials(
            provider = LLMProvider.OPENAI,
            apiKey = "sk-key",
            model = "gpt-5-mini",
            endpoint = "https://endpoint.com",
            apiVersion = "2024-01-01",
        )

        authManager.clearLLMCredentials()

        assertNull(authManager.loadLLMConfig())
        assertNull(authManager.getSavedApiKey())
        assertFalse(authManager.hasLLMCredentials())
    }

    @Test
    fun `clearLLMCredentials should not affect OAuth tokens`() {
        authManager.saveLLMCredentials(
            provider = LLMProvider.OPENAI,
            apiKey = "sk-key",
            model = "model",
        )
        authManager.saveOAuthToken(
            accessToken = "oauth-token",
            refreshToken = "refresh-token",
            expiresAtMs = System.currentTimeMillis() + 3600_000,
        )

        authManager.clearLLMCredentials()

        // OAuth tokens should still be present
        val oauthToken = authManager.loadOAuthToken()
        assertNotNull(oauthToken)
        assertEquals("oauth-token", oauthToken!!.accessToken)
    }

    // ── OAuth token storage ─────────────────────────────────────────────

    @Test
    fun `should save and load OAuth token with all fields`() {
        val futureExpiry = System.currentTimeMillis() + 3600_000
        authManager.saveOAuthToken(
            accessToken = "access_abc",
            refreshToken = "refresh_xyz",
            expiresAtMs = futureExpiry,
        )

        val token = authManager.loadOAuthToken()
        assertNotNull(token)
        assertEquals("access_abc", token!!.accessToken)
        assertEquals("refresh_xyz", token.refreshToken)
        assertEquals(futureExpiry, token.expiresAtMs)
    }

    @Test
    fun `should save and load OAuth token with null refresh token`() {
        authManager.saveOAuthToken(
            accessToken = "access_only",
            refreshToken = null,
            expiresAtMs = System.currentTimeMillis() + 3600_000,
        )

        val token = authManager.loadOAuthToken()
        assertNotNull(token)
        assertEquals("access_only", token!!.accessToken)
        assertNull(token.refreshToken)
    }

    @Test
    fun `loadOAuthToken should return null when no token is stored`() {
        assertNull(authManager.loadOAuthToken())
    }

    // ── isOAuthTokenValid ───────────────────────────────────────────────

    @Test
    fun `isOAuthTokenValid should return false when no token stored`() {
        assertFalse(authManager.isOAuthTokenValid())
    }

    @Test
    fun `isOAuthTokenValid should return true for token expiring in the future`() {
        authManager.saveOAuthToken(
            accessToken = "valid-token",
            refreshToken = null,
            expiresAtMs = System.currentTimeMillis() + 3600_000,
        )

        assertTrue(authManager.isOAuthTokenValid())
    }

    @Test
    fun `isOAuthTokenValid should return false for expired token`() {
        authManager.saveOAuthToken(
            accessToken = "expired-token",
            refreshToken = null,
            expiresAtMs = System.currentTimeMillis() - 1000,
        )

        assertFalse(authManager.isOAuthTokenValid())
    }

    // ── needsOAuthRefresh ───────────────────────────────────────────────

    @Test
    fun `needsOAuthRefresh should return false when no token stored`() {
        assertFalse(authManager.needsOAuthRefresh())
    }

    @Test
    fun `needsOAuthRefresh should return false when token has plenty of time left`() {
        authManager.saveOAuthToken(
            accessToken = "fresh-token",
            refreshToken = "refresh",
            expiresAtMs = System.currentTimeMillis() + 3600_000, // 1 hour from now
        )

        assertFalse(authManager.needsOAuthRefresh())
    }

    @Test
    fun `needsOAuthRefresh should return true when token expires within 5 minutes`() {
        authManager.saveOAuthToken(
            accessToken = "soon-to-expire",
            refreshToken = "refresh",
            expiresAtMs = System.currentTimeMillis() + 120_000, // 2 minutes from now
        )

        assertTrue(authManager.needsOAuthRefresh())
    }

    @Test
    fun `needsOAuthRefresh should return true when token is already expired`() {
        authManager.saveOAuthToken(
            accessToken = "already-expired",
            refreshToken = "refresh",
            expiresAtMs = System.currentTimeMillis() - 60_000,
        )

        assertTrue(authManager.needsOAuthRefresh())
    }

    // ── clearOAuthTokens ────────────────────────────────────────────────

    @Test
    fun `clearOAuthTokens should remove all OAuth keys`() {
        authManager.saveOAuthToken(
            accessToken = "access",
            refreshToken = "refresh",
            expiresAtMs = System.currentTimeMillis() + 3600_000,
        )

        authManager.clearOAuthTokens()

        assertNull(authManager.loadOAuthToken())
        assertFalse(authManager.isOAuthTokenValid())
    }

    @Test
    fun `clearOAuthTokens should not affect LLM credentials`() {
        authManager.saveLLMCredentials(
            provider = LLMProvider.OPENAI,
            apiKey = "sk-key",
            model = "gpt-5-mini",
        )
        authManager.saveOAuthToken(
            accessToken = "oauth",
            refreshToken = null,
            expiresAtMs = System.currentTimeMillis() + 3600_000,
        )

        authManager.clearOAuthTokens()

        val config = authManager.loadLLMConfig()
        assertNotNull(config)
        assertEquals("sk-key", config!!.apiKey)
    }

    // ── clearAll ────────────────────────────────────────────────────────

    @Test
    fun `clearAll should remove both LLM credentials and OAuth tokens`() {
        authManager.saveLLMCredentials(
            provider = LLMProvider.OPENAI,
            apiKey = "sk-key",
            model = "model",
        )
        authManager.saveOAuthToken(
            accessToken = "access",
            refreshToken = "refresh",
            expiresAtMs = System.currentTimeMillis() + 3600_000,
        )

        authManager.clearAll()

        assertNull(authManager.loadLLMConfig())
        assertNull(authManager.loadOAuthToken())
        assertFalse(authManager.hasLLMCredentials())
        assertFalse(authManager.isOAuthTokenValid())
    }

    // ── OAuthToken data class ───────────────────────────────────────────

    @Test
    fun `OAuthToken isExpired should return true for past expiry`() {
        val token = OAuthToken(
            accessToken = "token",
            refreshToken = null,
            expiresAtMs = System.currentTimeMillis() - 1000,
        )
        assertTrue(token.isExpired)
    }

    @Test
    fun `OAuthToken isExpired should return false for future expiry`() {
        val token = OAuthToken(
            accessToken = "token",
            refreshToken = null,
            expiresAtMs = System.currentTimeMillis() + 3600_000,
        )
        assertFalse(token.isExpired)
    }

    // ── LLMConfig withOAuthFlag behavior ────────────────────────────────

    @Test
    fun `loadLLMConfig should set useResponsesApi for OpenAI with valid OAuth token`() {
        // Save LLM creds as OpenAI
        authManager.saveLLMCredentials(
            provider = LLMProvider.OPENAI,
            apiKey = "oauth-access-token",
            model = "gpt-5.3-codex",
        )
        // Save a valid OAuth token (not expired)
        authManager.saveOAuthToken(
            accessToken = "oauth-access-token",
            refreshToken = "refresh",
            expiresAtMs = System.currentTimeMillis() + 3600_000,
        )

        val config = authManager.loadLLMConfig()
        assertNotNull(config)
        assertTrue(config!!.useResponsesApi)
    }

    @Test
    fun `loadLLMConfig should not set useResponsesApi for Anthropic provider`() {
        authManager.saveLLMCredentials(
            provider = LLMProvider.ANTHROPIC,
            apiKey = "sk-ant-key",
            model = "claude-sonnet-4-20250514",
        )
        // Save a valid OAuth token
        authManager.saveOAuthToken(
            accessToken = "oauth-token",
            refreshToken = "refresh",
            expiresAtMs = System.currentTimeMillis() + 3600_000,
        )

        val config = authManager.loadLLMConfig()
        assertNotNull(config)
        assertFalse(config!!.useResponsesApi)
    }

    @Test
    fun `loadLLMConfig should not set useResponsesApi when OAuth token is expired`() {
        authManager.saveLLMCredentials(
            provider = LLMProvider.OPENAI,
            apiKey = "key",
            model = "gpt-5-mini",
        )
        authManager.saveOAuthToken(
            accessToken = "expired-oauth",
            refreshToken = "refresh",
            expiresAtMs = System.currentTimeMillis() - 1000,
        )

        val config = authManager.loadLLMConfig()
        assertNotNull(config)
        assertFalse(config!!.useResponsesApi)
    }
}

// ── FakeSharedPreferences ───────────────────────────────────────────────────

/**
 * Minimal in-memory SharedPreferences for AuthManager unit tests.
 * Supports the string and long operations AuthManager actually uses.
 */
class FakeSharedPreferences : SharedPreferences {

    val data = mutableMapOf<String, Any?>()

    override fun getString(key: String?, defValue: String?): String? {
        return if (data.containsKey(key)) data[key] as? String else defValue
    }

    override fun getLong(key: String?, defValue: Long): Long {
        return if (data.containsKey(key)) (data[key] as? Long) ?: defValue else defValue
    }

    override fun edit(): SharedPreferences.Editor = FakeEditor(data)

    override fun getAll(): MutableMap<String, *> = data.toMutableMap()
    override fun getInt(key: String?, defValue: Int): Int = defValue
    override fun getFloat(key: String?, defValue: Float): Float = defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    class FakeEditor(private val data: MutableMap<String, Any?>) : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            key?.let { pending[it] = value }
            return this
        }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            key?.let { pending[it] = value }
            return this
        }
        override fun remove(key: String?): SharedPreferences.Editor {
            key?.let { removals.add(it) }
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }
        override fun apply() { commit() }
        override fun commit(): Boolean {
            if (clearAll) data.clear()
            removals.forEach { data.remove(it) }
            data.putAll(pending)
            return true
        }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
    }
}
