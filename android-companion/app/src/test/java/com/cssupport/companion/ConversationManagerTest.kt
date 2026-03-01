package com.cssupport.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for ConversationManager.
 *
 * ConversationManager handles:
 * - Maintaining the append-only conversation message list
 * - Estimating token usage
 * - Applying observation masking to older turns
 * - Recording complete turns (observation -> decision -> result)
 */
class ConversationManagerTest {

    private lateinit var manager: ConversationManager

    @Before
    fun setUp() {
        manager = ConversationManager()
    }

    // -- reset ----------------------------------------------------------------

    @Test
    fun `reset should clear all state`() {
        manager.addObservation("test")
        assertTrue(manager.historySnapshot().isNotEmpty())
        assertTrue(manager.estimatedTokenCount() > 0)

        manager.reset()
        assertTrue(manager.historySnapshot().isEmpty())
        assertEquals(0, manager.estimatedTokenCount())
    }

    // -- historySnapshot ------------------------------------------------------

    @Test
    fun `historySnapshot should return empty list initially`() {
        assertTrue(manager.historySnapshot().isEmpty())
    }

    @Test
    fun `historySnapshot should return a copy not the original list`() {
        manager.addObservation("test message")
        val snapshot1 = manager.historySnapshot()
        manager.addObservation("another message")
        val snapshot2 = manager.historySnapshot()

        assertEquals(1, snapshot1.size)
        assertEquals(2, snapshot2.size)
    }

    // -- addObservation -------------------------------------------------------

    @Test
    fun `addObservation should add a UserObservation message`() {
        manager.addObservation("Screen changed to HomeActivity")

        val history = manager.historySnapshot()
        assertEquals(1, history.size)
        assertTrue(history[0] is ConversationMessage.UserObservation)
        assertEquals(
            "Screen changed to HomeActivity",
            (history[0] as ConversationMessage.UserObservation).content,
        )
    }

    @Test
    fun `addObservation should increase estimated token count`() {
        assertEquals(0, manager.estimatedTokenCount())
        manager.addObservation("A".repeat(100))

        // 100 chars / CHARS_PER_TOKEN (4) = 25 tokens
        assertEquals(100 / AgentConfig.CHARS_PER_TOKEN, manager.estimatedTokenCount())
    }

    @Test
    fun `addObservation should accumulate token count across multiple calls`() {
        manager.addObservation("A".repeat(40))  // 10 tokens
        manager.addObservation("B".repeat(40))  // 10 tokens

        assertEquals(20, manager.estimatedTokenCount())
    }

    // -- recordTurn -----------------------------------------------------------

    @Test
    fun `recordTurn should add three messages to history`() {
        val decision = AgentDecision(
            action = AgentAction.ClickElement(elementId = 5, label = "Help"),
            reasoning = "Need to find support",
            toolCallId = "call_123",
            toolName = "click_element",
            toolArguments = """{"elementId": 5}""",
        )

        manager.recordTurn(
            userMessage = "Screen state...",
            decision = decision,
            result = "Clicked Help button",
            toolNameFallback = { "click_element" },
            toolArgsFallback = { """{"elementId": 5}""" },
        )

        val history = manager.historySnapshot()
        assertEquals(3, history.size)
        assertTrue(history[0] is ConversationMessage.UserObservation)
        assertTrue(history[1] is ConversationMessage.AssistantToolCall)
        assertTrue(history[2] is ConversationMessage.ToolResult)
    }

    @Test
    fun `recordTurn should store correct UserObservation content`() {
        val decision = makeDecision()

        manager.recordTurn(
            userMessage = "Turn 1 screen data",
            decision = decision,
            result = "OK",
            toolNameFallback = { "click_element" },
            toolArgsFallback = { "{}" },
        )

        val obs = manager.historySnapshot()[0] as ConversationMessage.UserObservation
        assertEquals("Turn 1 screen data", obs.content)
    }

    @Test
    fun `recordTurn should store correct AssistantToolCall fields`() {
        val decision = AgentDecision(
            action = AgentAction.ClickElement(elementId = 3),
            reasoning = "Clicking account tab",
            toolCallId = "call_abc",
            toolName = "click_element",
            toolArguments = """{"elementId": 3}""",
        )

        manager.recordTurn(
            userMessage = "screen",
            decision = decision,
            result = "OK",
            toolNameFallback = { "fallback" },
            toolArgsFallback = { "{}" },
        )

        val toolCall = manager.historySnapshot()[1] as ConversationMessage.AssistantToolCall
        assertEquals("call_abc", toolCall.toolCallId)
        assertEquals("click_element", toolCall.toolName)
        assertEquals("""{"elementId": 3}""", toolCall.toolArguments)
        assertEquals("Clicking account tab", toolCall.reasoning)
    }

    @Test
    fun `recordTurn should use toolNameFallback when toolName is blank`() {
        val decision = AgentDecision(
            action = AgentAction.ScrollDown(reason = "looking for button"),
            reasoning = "Need to scroll",
            toolCallId = "call_xyz",
            toolName = "",  // blank
            toolArguments = "{}",
        )

        manager.recordTurn(
            userMessage = "screen",
            decision = decision,
            result = "scrolled",
            toolNameFallback = { "scroll_down" },
            toolArgsFallback = { """{"reason": "looking for button"}""" },
        )

        val toolCall = manager.historySnapshot()[1] as ConversationMessage.AssistantToolCall
        assertEquals("scroll_down", toolCall.toolName)
    }

    @Test
    fun `recordTurn should use toolArgsFallback when toolArguments is empty object`() {
        val decision = AgentDecision(
            action = AgentAction.PressBack(reason = "dismiss popup"),
            reasoning = "Popup is blocking",
            toolCallId = "call_def",
            toolName = "press_back",
            toolArguments = "{}",  // empty
        )

        manager.recordTurn(
            userMessage = "screen",
            decision = decision,
            result = "backed",
            toolNameFallback = { "press_back" },
            toolArgsFallback = { """{"reason": "dismiss popup"}""" },
        )

        val toolCall = manager.historySnapshot()[1] as ConversationMessage.AssistantToolCall
        assertEquals("""{"reason": "dismiss popup"}""", toolCall.toolArguments)
    }

    @Test
    fun `recordTurn should store correct ToolResult fields`() {
        val decision = makeDecision(toolCallId = "call_999")

        manager.recordTurn(
            userMessage = "screen",
            decision = decision,
            result = "Element clicked successfully",
            toolNameFallback = { "click" },
            toolArgsFallback = { "{}" },
        )

        val toolResult = manager.historySnapshot()[2] as ConversationMessage.ToolResult
        assertEquals("call_999", toolResult.toolCallId)
        assertEquals("Element clicked successfully", toolResult.result)
    }

    @Test
    fun `recordTurn should increase estimated token count`() {
        val initialTokens = manager.estimatedTokenCount()
        val decision = makeDecision()

        manager.recordTurn(
            userMessage = "A".repeat(200),
            decision = decision,
            result = "B".repeat(100),
            toolNameFallback = { "click" },
            toolArgsFallback = { "{}" },
        )

        assertTrue(manager.estimatedTokenCount() > initialTokens)
    }

    @Test
    fun `multiple recordTurn calls should accumulate messages`() {
        repeat(3) { i ->
            manager.recordTurn(
                userMessage = "Turn $i",
                decision = makeDecision(toolCallId = "call_$i"),
                result = "Result $i",
                toolNameFallback = { "click" },
                toolArgsFallback = { "{}" },
            )
        }

        // 3 turns * 3 messages per turn = 9 messages
        assertEquals(9, manager.historySnapshot().size)
    }

    // -- applyObservationMasking ----------------------------------------------

    @Test
    fun `applyObservationMasking should not modify history when fewer turns than threshold`() {
        // Add fewer turns than OBSERVATION_MASK_KEEP_RECENT (4)
        repeat(3) { i ->
            manager.recordTurn(
                userMessage = "Package: com.app\nScreen: Home\nPhase: Navigating\nTurn: $i / 30\n\nLong observation content here...",
                decision = makeDecision(toolCallId = "call_$i"),
                result = "OK",
                toolNameFallback = { "click" },
                toolArgsFallback = { "{}" },
            )
        }

        val beforeMask = manager.historySnapshot().map {
            if (it is ConversationMessage.UserObservation) it.content else ""
        }

        manager.applyObservationMasking("Navigating to support")

        val afterMask = manager.historySnapshot().map {
            if (it is ConversationMessage.UserObservation) it.content else ""
        }

        assertEquals(beforeMask, afterMask)
    }

    @Test
    fun `applyObservationMasking should mask old observations when many turns exist`() {
        // Add more turns than OBSERVATION_MASK_KEEP_RECENT (4)
        repeat(6) { i ->
            manager.recordTurn(
                userMessage = "Package: com.app\nScreen: Activity$i\nPhase: Navigating\nTurn: $i / 30\n\nDetailed screen content for turn $i with lots of data...",
                decision = makeDecision(toolCallId = "call_$i"),
                result = "OK turn $i",
                toolNameFallback = { "click" },
                toolArgsFallback = { "{}" },
            )
        }

        manager.applyObservationMasking("Navigating to support")

        val history = manager.historySnapshot()
        // Check that some early observations were masked (start with "[Screen:")
        val observations = history.filterIsInstance<ConversationMessage.UserObservation>()
        val maskedObs = observations.filter { it.content.startsWith("[Screen:") }
        assertTrue("At least one observation should be masked", maskedObs.isNotEmpty())
    }

    @Test
    fun `applyObservationMasking should not mask AssistantToolCall messages`() {
        repeat(6) { i ->
            manager.recordTurn(
                userMessage = "Package: com.app\nScreen: Act$i\nPhase: Nav\nTurn: $i / 30\n\nData...",
                decision = makeDecision(toolCallId = "call_$i", reasoning = "Reasoning for turn $i"),
                result = "OK $i",
                toolNameFallback = { "click" },
                toolArgsFallback = { "{}" },
            )
        }

        manager.applyObservationMasking("Navigating to support")

        val toolCalls = manager.historySnapshot().filterIsInstance<ConversationMessage.AssistantToolCall>()
        assertEquals(6, toolCalls.size)
        // Verify reasoning is preserved (not masked)
        toolCalls.forEachIndexed { i, tc ->
            assertEquals("Reasoning for turn $i", tc.reasoning)
        }
    }

    @Test
    fun `applyObservationMasking should not mask ToolResult messages`() {
        repeat(6) { i ->
            manager.recordTurn(
                userMessage = "Package: com.app\nScreen: Act$i\nPhase: Nav\nTurn: $i / 30\n\nData...",
                decision = makeDecision(toolCallId = "call_$i"),
                result = "Result for turn $i",
                toolNameFallback = { "click" },
                toolArgsFallback = { "{}" },
            )
        }

        manager.applyObservationMasking("Navigating to support")

        val results = manager.historySnapshot().filterIsInstance<ConversationMessage.ToolResult>()
        assertEquals(6, results.size)
        results.forEachIndexed { i, tr ->
            assertEquals("Result for turn $i", tr.result)
        }
    }

    @Test
    fun `applyObservationMasking should preserve recent observations`() {
        repeat(8) { i ->
            manager.recordTurn(
                userMessage = "Package: com.app\nScreen: Act$i\nPhase: Nav\nTurn: $i / 30\n\nVerbose data for turn $i...",
                decision = makeDecision(toolCallId = "call_$i"),
                result = "OK $i",
                toolNameFallback = { "click" },
                toolArgsFallback = { "{}" },
            )
        }

        manager.applyObservationMasking("Navigating to support")

        val observations = manager.historySnapshot().filterIsInstance<ConversationMessage.UserObservation>()
        // The last OBSERVATION_MASK_KEEP_RECENT turns should still have full observations
        val recentObs = observations.takeLast(AgentConfig.OBSERVATION_MASK_KEEP_RECENT)
        recentObs.forEach { obs ->
            assertTrue(
                "Recent observation should not be masked: ${obs.content.take(50)}",
                obs.content.contains("Package: com.app"),
            )
        }
    }

    @Test
    fun `applyObservationMasking should not mask observations already starting with Screen bracket`() {
        // Pre-masked observations (starting with "[Screen:") should be left alone
        manager.addObservation("[Screen: com.app/HomeActivity, Phase: Nav, Turn: 1]")
        repeat(6) { i ->
            manager.recordTurn(
                userMessage = "Package: com.app\nScreen: Act$i\nPhase: Nav\nTurn: $i / 30\n\nData...",
                decision = makeDecision(toolCallId = "call_$i"),
                result = "OK",
                toolNameFallback = { "click" },
                toolArgsFallback = { "{}" },
            )
        }

        manager.applyObservationMasking("In chat")

        // The first observation should remain as-is (already masked)
        val first = manager.historySnapshot()[0] as ConversationMessage.UserObservation
        assertEquals("[Screen: com.app/HomeActivity, Phase: Nav, Turn: 1]", first.content)
    }

    @Test
    fun `applyObservationMasking should recalculate estimated tokens`() {
        repeat(8) { i ->
            manager.recordTurn(
                userMessage = "Package: com.app\nScreen: Act$i\nPhase: Nav\nTurn: $i / 30\n\n${"X".repeat(500)}",
                decision = makeDecision(toolCallId = "call_$i"),
                result = "OK",
                toolNameFallback = { "click" },
                toolArgsFallback = { "{}" },
            )
        }

        val tokensBefore = manager.estimatedTokenCount()
        manager.applyObservationMasking("Navigating to support")
        val tokensAfter = manager.estimatedTokenCount()

        assertTrue(
            "Token count should decrease after masking (before=$tokensBefore, after=$tokensAfter)",
            tokensAfter <= tokensBefore,
        )
    }

    // -- Helpers ---------------------------------------------------------------

    private fun makeDecision(
        toolCallId: String = "call_test",
        reasoning: String = "test reasoning",
    ): AgentDecision {
        return AgentDecision(
            action = AgentAction.ClickElement(elementId = 1, label = "test"),
            reasoning = reasoning,
            toolCallId = toolCallId,
            toolName = "click_element",
            toolArguments = """{"elementId": 1}""",
        )
    }
}
