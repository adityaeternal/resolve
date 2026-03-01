package com.cssupport.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for LLMClient's response parsing across all three API formats:
 * - OpenAI Chat Completions API (parseOpenAIResponse)
 * - OpenAI Responses API (parseResponsesAPIResponse)
 * - Anthropic Messages API (parseAnthropicResponse)
 *
 * These methods are private but we can test them indirectly by verifying the
 * behavior we care about: given a JSON response shape, the client produces
 * the correct AgentDecision. Since the parsing methods are private, we test
 * them through reflection to avoid making them internal just for testing.
 *
 * We also test buildOpenAIUrl which constructs provider-specific URLs.
 */
class LLMClientResponseParsingTest {

    // ── parseOpenAIResponse ──────────────────────────────────────────────

    @Test
    fun `parseOpenAIResponse should extract tool call from valid response`() {
        val client = LLMClient(LLMConfig.openAI(apiKey = "test-key"))
        val json = JSONObject().apply {
            put("choices", JSONArray().apply {
                put(JSONObject().apply {
                    put("message", JSONObject().apply {
                        put("content", "I should click the help button")
                        put("tool_calls", JSONArray().apply {
                            put(JSONObject().apply {
                                put("id", "call_abc123")
                                put("type", "function")
                                put("function", JSONObject().apply {
                                    put("name", "click_element")
                                    put("arguments", """{"elementId": 5, "expectedOutcome": "Opens help page"}""")
                                })
                            })
                        })
                    })
                })
            })
        }

        val decision = invokeParseOpenAIResponse(client, json)
        assertTrue(decision.action is AgentAction.ClickElement)
        val click = decision.action as AgentAction.ClickElement
        assertEquals(5, click.elementId)
        assertEquals("Opens help page", click.expectedOutcome)
        assertEquals("I should click the help button", decision.reasoning)
        assertEquals("call_abc123", decision.toolCallId)
        assertEquals("click_element", decision.toolName)
    }

    @Test
    fun `parseOpenAIResponse should return Wait when choices array is empty`() {
        val client = LLMClient(LLMConfig.openAI(apiKey = "test-key"))
        val json = JSONObject().apply {
            put("choices", JSONArray())
        }

        val decision = invokeParseOpenAIResponse(client, json)
        assertTrue(decision.action is AgentAction.Wait)
        assertTrue((decision.action as AgentAction.Wait).reason.contains("No response"))
    }

    @Test
    fun `parseOpenAIResponse should return Wait when choices is missing`() {
        val client = LLMClient(LLMConfig.openAI(apiKey = "test-key"))
        val json = JSONObject().apply {
            put("id", "chatcmpl-123")
        }

        val decision = invokeParseOpenAIResponse(client, json)
        assertTrue(decision.action is AgentAction.Wait)
    }

    @Test
    fun `parseOpenAIResponse should return Wait when no tool calls in message`() {
        val client = LLMClient(LLMConfig.openAI(apiKey = "test-key"))
        val json = JSONObject().apply {
            put("choices", JSONArray().apply {
                put(JSONObject().apply {
                    put("message", JSONObject().apply {
                        put("content", "I'm thinking about what to do next")
                    })
                })
            })
        }

        val decision = invokeParseOpenAIResponse(client, json)
        assertTrue(decision.action is AgentAction.Wait)
        assertTrue(decision.reasoning.contains("thinking"))
    }

    @Test
    fun `parseOpenAIResponse should handle type_message tool call`() {
        val client = LLMClient(LLMConfig.openAI(apiKey = "test-key"))
        val json = buildOpenAIToolCallResponse(
            toolName = "type_message",
            arguments = """{"text": "I need help with my order", "elementId": 3}""",
            reasoning = "Typing the issue description",
        )

        val decision = invokeParseOpenAIResponse(client, json)
        assertTrue(decision.action is AgentAction.TypeMessage)
        val typed = decision.action as AgentAction.TypeMessage
        assertEquals("I need help with my order", typed.text)
        assertEquals(3, typed.elementId)
    }

    @Test
    fun `parseOpenAIResponse should handle mark_resolved tool call`() {
        val client = LLMClient(LLMConfig.openAI(apiKey = "test-key"))
        val json = buildOpenAIToolCallResponse(
            toolName = "mark_resolved",
            arguments = """{"summary": "Refund of Rs 450 processed, confirmation #RF-789"}""",
        )

        val decision = invokeParseOpenAIResponse(client, json)
        assertTrue(decision.action is AgentAction.MarkResolved)
        assertEquals(
            "Refund of Rs 450 processed, confirmation #RF-789",
            (decision.action as AgentAction.MarkResolved).summary,
        )
    }

    @Test
    fun `parseOpenAIResponse should handle malformed message gracefully`() {
        val client = LLMClient(LLMConfig.openAI(apiKey = "test-key"))
        val json = JSONObject().apply {
            put("choices", JSONArray().apply {
                put(JSONObject()) // choice with no "message" key
            })
        }

        val decision = invokeParseOpenAIResponse(client, json)
        assertTrue(decision.action is AgentAction.Wait)
        assertTrue(decision.reasoning.contains("Malformed"))
    }

    // ── parseResponsesAPIResponse ────────────────────────────────────────

    @Test
    fun `parseResponsesAPIResponse should extract function call from output`() {
        val client = LLMClient(LLMConfig.openAI(apiKey = "test-key"))
        val json = JSONObject().apply {
            put("output", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "message")
                    put("role", "assistant")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "output_text")
                            put("text", "I see the support button")
                        })
                    })
                })
                put(JSONObject().apply {
                    put("type", "function_call")
                    put("call_id", "call_resp_001")
                    put("name", "click_element")
                    put("arguments", """{"elementId": 8, "expectedOutcome": "Open support"}""")
                })
            })
        }

        val decision = invokeParseResponsesAPIResponse(client, json)
        assertTrue(decision.action is AgentAction.ClickElement)
        assertEquals(8, (decision.action as AgentAction.ClickElement).elementId)
        assertEquals("I see the support button", decision.reasoning)
        assertEquals("call_resp_001", decision.toolCallId)
    }

    @Test
    fun `parseResponsesAPIResponse should return Wait when output is empty`() {
        val client = LLMClient(LLMConfig.openAI(apiKey = "test-key"))
        val json = JSONObject().apply {
            put("output", JSONArray())
        }

        val decision = invokeParseResponsesAPIResponse(client, json)
        assertTrue(decision.action is AgentAction.Wait)
    }

    @Test
    fun `parseResponsesAPIResponse should throw on error response`() {
        val client = LLMClient(LLMConfig.openAI(apiKey = "test-key"))
        val json = JSONObject().apply {
            put("error", JSONObject().apply {
                put("message", "Rate limit exceeded")
                put("type", "rate_limit_error")
            })
        }

        try {
            invokeParseResponsesAPIResponse(client, json)
            assertTrue("Should have thrown LLMException", false)
        } catch (e: Exception) {
            // The method throws LLMException wrapped in InvocationTargetException
            val cause = e.cause ?: e
            assertTrue(cause.message!!.contains("Rate limit"))
        }
    }

    @Test
    fun `parseResponsesAPIResponse should handle string error field`() {
        val client = LLMClient(LLMConfig.openAI(apiKey = "test-key"))
        val json = JSONObject().apply {
            put("error", "Service unavailable")
        }

        try {
            invokeParseResponsesAPIResponse(client, json)
            assertTrue("Should have thrown LLMException", false)
        } catch (e: Exception) {
            val cause = e.cause ?: e
            assertTrue(cause.message!!.contains("Service unavailable"))
        }
    }

    @Test
    fun `parseResponsesAPIResponse should fall back to output_text when no function call`() {
        val client = LLMClient(LLMConfig.openAI(apiKey = "test-key"))
        val json = JSONObject().apply {
            put("output", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "message")
                    put("role", "assistant")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "output_text")
                            put("text", "I need to analyze the screen")
                        })
                    })
                })
            })
            put("output_text", "Fallback text from top level")
        }

        val decision = invokeParseResponsesAPIResponse(client, json)
        assertTrue(decision.action is AgentAction.Wait)
        // Should use the reasoning from the output message block
        assertEquals("I need to analyze the screen", decision.reasoning)
    }

    // ── parseAnthropicResponse ───────────────────────────────────────────

    @Test
    fun `parseAnthropicResponse should extract tool use from content`() {
        val client = LLMClient(LLMConfig.anthropic(apiKey = "test-key"))
        val json = JSONObject().apply {
            put("content", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", "I'll scroll down to find more options")
                })
                put(JSONObject().apply {
                    put("type", "tool_use")
                    put("id", "toolu_01abc")
                    put("name", "scroll_down")
                    put("input", JSONObject().put("reason", "Looking for help section"))
                })
            })
        }

        val decision = invokeParseAnthropicResponse(client, json)
        assertTrue(decision.action is AgentAction.ScrollDown)
        assertEquals("Looking for help section", (decision.action as AgentAction.ScrollDown).reason)
        assertEquals("I'll scroll down to find more options", decision.reasoning)
        assertEquals("toolu_01abc", decision.toolCallId)
    }

    @Test
    fun `parseAnthropicResponse should return Wait when content is empty`() {
        val client = LLMClient(LLMConfig.anthropic(apiKey = "test-key"))
        val json = JSONObject().apply {
            put("content", JSONArray())
        }

        val decision = invokeParseAnthropicResponse(client, json)
        assertTrue(decision.action is AgentAction.Wait)
    }

    @Test
    fun `parseAnthropicResponse should throw on Anthropic error type`() {
        val client = LLMClient(LLMConfig.anthropic(apiKey = "test-key"))
        val json = JSONObject().apply {
            put("type", "error")
            put("error", JSONObject().apply {
                put("type", "overloaded_error")
                put("message", "Overloaded")
            })
        }

        try {
            invokeParseAnthropicResponse(client, json)
            assertTrue("Should have thrown LLMException", false)
        } catch (e: Exception) {
            val cause = e.cause ?: e
            assertTrue(cause.message!!.contains("Overloaded"))
        }
    }

    @Test
    fun `parseAnthropicResponse should handle request_human_review tool use`() {
        val client = LLMClient(LLMConfig.anthropic(apiKey = "test-key"))
        val json = JSONObject().apply {
            put("content", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "tool_use")
                    put("id", "toolu_02xyz")
                    put("name", "request_human_review")
                    put("input", JSONObject().apply {
                        put("reason", "OTP required for verification")
                        put("needsInput", true)
                        put("inputPrompt", "Please enter the 6-digit OTP")
                    })
                })
            })
        }

        val decision = invokeParseAnthropicResponse(client, json)
        assertTrue(decision.action is AgentAction.RequestHumanReview)
        val review = decision.action as AgentAction.RequestHumanReview
        assertEquals("OTP required for verification", review.reason)
        assertTrue(review.needsInput)
        assertEquals("Please enter the 6-digit OTP", review.inputPrompt)
    }

    @Test
    fun `parseAnthropicResponse should return Wait when only text content, no tool use`() {
        val client = LLMClient(LLMConfig.anthropic(apiKey = "test-key"))
        val json = JSONObject().apply {
            put("content", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", "Let me think about the next step")
                })
            })
        }

        val decision = invokeParseAnthropicResponse(client, json)
        assertTrue(decision.action is AgentAction.Wait)
        assertEquals("Let me think about the next step", decision.reasoning)
    }

    // ── buildOpenAIUrl ──────────────────────────────────────────────────

    @Test
    fun `buildOpenAIUrl should return standard OpenAI endpoint for OPENAI provider`() {
        val client = LLMClient(LLMConfig.openAI(apiKey = "key"))
        val url = invokeBuildOpenAIUrl(client)
        assertEquals("https://api.openai.com/v1/chat/completions", url)
    }

    @Test
    fun `buildOpenAIUrl should construct Azure endpoint with deployment and api version`() {
        val client = LLMClient(LLMConfig.azureDefault(
            apiKey = "key",
            endpoint = "https://myresource.openai.azure.com",
        ))
        val url = invokeBuildOpenAIUrl(client)
        assertTrue(url.contains("myresource.openai.azure.com"))
        assertTrue(url.contains("/openai/deployments/gpt-5-nano/chat/completions"))
        assertTrue(url.contains("api-version=2024-10-21"))
    }

    @Test
    fun `buildOpenAIUrl should strip Azure AI Foundry project path`() {
        val client = LLMClient(LLMConfig(
            provider = LLMProvider.AZURE_OPENAI,
            apiKey = "key",
            model = "gpt-5-nano",
            endpoint = "https://myresource.openai.azure.com/api/projects/my-project",
            apiVersion = "2024-10-21",
        ))
        val url = invokeBuildOpenAIUrl(client)
        assertFalse(url.contains("/api/projects/"))
        assertTrue(url.startsWith("https://myresource.openai.azure.com/openai/"))
    }

    @Test
    fun `buildOpenAIUrl should append v1 chat completions for CUSTOM provider`() {
        val client = LLMClient(LLMConfig.custom(
            apiKey = "key",
            endpoint = "http://localhost:8080",
            model = "local-model",
        ))
        val url = invokeBuildOpenAIUrl(client)
        assertEquals("http://localhost:8080/v1/chat/completions", url)
    }

    @Test
    fun `buildOpenAIUrl should strip trailing slash from custom endpoint`() {
        val client = LLMClient(LLMConfig.custom(
            apiKey = "key",
            endpoint = "http://localhost:8080/",
            model = "local-model",
        ))
        val url = invokeBuildOpenAIUrl(client)
        assertEquals("http://localhost:8080/v1/chat/completions", url)
    }

    @Test
    fun `buildOpenAIUrl should throw for AZURE without endpoint`() {
        val client = LLMClient(LLMConfig(
            provider = LLMProvider.AZURE_OPENAI,
            apiKey = "key",
            model = "gpt-5-nano",
            endpoint = null,
        ))
        try {
            invokeBuildOpenAIUrl(client)
            assertTrue("Should have thrown", false)
        } catch (e: Exception) {
            val cause = e.cause ?: e
            assertTrue(cause.message!!.contains("Azure endpoint is required"))
        }
    }

    @Test
    fun `buildOpenAIUrl should throw for CUSTOM without endpoint`() {
        val client = LLMClient(LLMConfig(
            provider = LLMProvider.CUSTOM,
            apiKey = "key",
            model = "model",
            endpoint = null,
        ))
        try {
            invokeBuildOpenAIUrl(client)
            assertTrue("Should have thrown", false)
        } catch (e: Exception) {
            val cause = e.cause ?: e
            assertTrue(cause.message!!.contains("Custom endpoint is required"))
        }
    }

    @Test
    fun `buildOpenAIUrl should throw for ANTHROPIC provider`() {
        val client = LLMClient(LLMConfig.anthropic(apiKey = "key"))
        try {
            invokeBuildOpenAIUrl(client)
            assertTrue("Should have thrown", false)
        } catch (e: Exception) {
            val cause = e.cause ?: e
            assertTrue(cause.message!!.contains("Anthropic"))
        }
    }

    // ── LLMException ────────────────────────────────────────────────────

    @Test
    fun `LLMException should carry message`() {
        val ex = LLMException("HTTP 429: Rate limited")
        assertEquals("HTTP 429: Rate limited", ex.message)
    }

    @Test
    fun `LLMException should carry optional cause`() {
        val cause = RuntimeException("connection reset")
        val ex = LLMException("HTTP error", cause)
        assertEquals(cause, ex.cause)
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun buildOpenAIToolCallResponse(
        toolName: String,
        arguments: String,
        reasoning: String = "",
        toolCallId: String = "call_test_001",
    ): JSONObject {
        return JSONObject().apply {
            put("choices", JSONArray().apply {
                put(JSONObject().apply {
                    put("message", JSONObject().apply {
                        put("content", reasoning)
                        put("tool_calls", JSONArray().apply {
                            put(JSONObject().apply {
                                put("id", toolCallId)
                                put("type", "function")
                                put("function", JSONObject().apply {
                                    put("name", toolName)
                                    put("arguments", arguments)
                                })
                            })
                        })
                    })
                })
            })
        }
    }

    private fun invokeParseOpenAIResponse(client: LLMClient, json: JSONObject): AgentDecision {
        val method = LLMClient::class.java.getDeclaredMethod("parseOpenAIResponse", JSONObject::class.java)
        method.isAccessible = true
        return method.invoke(client, json) as AgentDecision
    }

    private fun invokeParseResponsesAPIResponse(client: LLMClient, json: JSONObject): AgentDecision {
        val method = LLMClient::class.java.getDeclaredMethod("parseResponsesAPIResponse", JSONObject::class.java)
        method.isAccessible = true
        return method.invoke(client, json) as AgentDecision
    }

    private fun invokeParseAnthropicResponse(client: LLMClient, json: JSONObject): AgentDecision {
        val method = LLMClient::class.java.getDeclaredMethod("parseAnthropicResponse", JSONObject::class.java)
        method.isAccessible = true
        return method.invoke(client, json) as AgentDecision
    }

    private fun invokeBuildOpenAIUrl(client: LLMClient): String {
        val method = LLMClient::class.java.getDeclaredMethod("buildOpenAIUrl")
        method.isAccessible = true
        return method.invoke(client) as String
    }

    private fun assertFalse(condition: Boolean) {
        org.junit.Assert.assertFalse(condition)
    }
}
