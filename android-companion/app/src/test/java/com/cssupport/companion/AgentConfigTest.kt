package com.cssupport.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for AgentConfig centralized constants.
 *
 * AgentConfig is an object of constants. These tests verify:
 * - Values are within sane ranges (no accidental 0 or negative)
 * - Related constants maintain expected invariants
 * - System packages set contains expected entries
 */
class AgentConfigTest {

    // -- Screen stabilization & loading ------------------------------------------

    @Test
    fun `SCREEN_STABILIZE_MAX_WAIT_MS should be positive and reasonable`() {
        assertTrue(AgentConfig.SCREEN_STABILIZE_MAX_WAIT_MS > 0)
        assertTrue(AgentConfig.SCREEN_STABILIZE_MAX_WAIT_MS <= 30_000L)
    }

    @Test
    fun `SCREEN_STABILIZE_POLL_INTERVAL_MS should be less than max wait`() {
        assertTrue(AgentConfig.SCREEN_STABILIZE_POLL_INTERVAL_MS > 0)
        assertTrue(
            AgentConfig.SCREEN_STABILIZE_POLL_INTERVAL_MS <
                AgentConfig.SCREEN_STABILIZE_MAX_WAIT_MS,
        )
    }

    @Test
    fun `INITIAL_LOAD_WAIT_MS should be positive`() {
        assertTrue(AgentConfig.INITIAL_LOAD_WAIT_MS > 0)
    }

    @Test
    fun `INITIAL_LOAD_POLL_INTERVAL_MS should be less than initial load wait`() {
        assertTrue(AgentConfig.INITIAL_LOAD_POLL_INTERVAL_MS > 0)
        assertTrue(
            AgentConfig.INITIAL_LOAD_POLL_INTERVAL_MS <
                AgentConfig.INITIAL_LOAD_WAIT_MS,
        )
    }

    @Test
    fun `MIN_ELEMENTS_FOR_LOADED_SCREEN should be at least 1`() {
        assertTrue(AgentConfig.MIN_ELEMENTS_FOR_LOADED_SCREEN >= 1)
    }

    @Test
    fun `POST_LOAD_SETTLE_MS should be positive`() {
        assertTrue(AgentConfig.POST_LOAD_SETTLE_MS > 0)
    }

    // -- Action timing -----------------------------------------------------------

    @Test
    fun `POST_ACTION_VERIFY_DELAY_MS should be positive`() {
        assertTrue(AgentConfig.POST_ACTION_VERIFY_DELAY_MS > 0)
    }

    @Test
    fun `POST_PLAN_DELAY_MS should be positive`() {
        assertTrue(AgentConfig.POST_PLAN_DELAY_MS > 0)
    }

    @Test
    fun `WRONG_APP_BACK_DELAY_MS should be positive`() {
        assertTrue(AgentConfig.WRONG_APP_BACK_DELAY_MS > 0)
    }

    @Test
    fun `WRONG_APP_RELAUNCH_DELAY_MS should be greater than WRONG_APP_BACK_DELAY`() {
        assertTrue(
            AgentConfig.WRONG_APP_RELAUNCH_DELAY_MS >=
                AgentConfig.WRONG_APP_BACK_DELAY_MS,
        )
    }

    @Test
    fun `WAIT_FOR_CONTENT_CHANGE_TIMEOUT_MS should be positive`() {
        assertTrue(AgentConfig.WAIT_FOR_CONTENT_CHANGE_TIMEOUT_MS > 0)
    }

    // -- Iteration & retry limits ------------------------------------------------

    @Test
    fun `MAX_TOTAL_LOOP_ITERATIONS should be positive`() {
        assertTrue(AgentConfig.MAX_TOTAL_LOOP_ITERATIONS > 0)
    }

    @Test
    fun `MAX_CONSECUTIVE_DUPLICATES should be at least 2`() {
        assertTrue(AgentConfig.MAX_CONSECUTIVE_DUPLICATES >= 2)
    }

    @Test
    fun `MAX_NO_TOOL_CALL_RETRIES should be positive`() {
        assertTrue(AgentConfig.MAX_NO_TOOL_CALL_RETRIES > 0)
    }

    @Test
    fun `MAX_LLM_RETRIES should be positive`() {
        assertTrue(AgentConfig.MAX_LLM_RETRIES > 0)
    }

    @Test
    fun `MAX_WRONG_APP_TURNS should be positive`() {
        assertTrue(AgentConfig.MAX_WRONG_APP_TURNS > 0)
    }

    @Test
    fun `MAX_SCROLL_ACTIONS should be positive`() {
        assertTrue(AgentConfig.MAX_SCROLL_ACTIONS > 0)
    }

    // -- Observation masking & token budget ---------------------------------------

    @Test
    fun `OBSERVATION_MASK_KEEP_RECENT should be at least 1`() {
        assertTrue(AgentConfig.OBSERVATION_MASK_KEEP_RECENT >= 1)
    }

    @Test
    fun `MAX_ESTIMATED_TOKENS should be positive`() {
        assertTrue(AgentConfig.MAX_ESTIMATED_TOKENS > 0)
    }

    @Test
    fun `CHARS_PER_TOKEN should be positive`() {
        assertTrue(AgentConfig.CHARS_PER_TOKEN > 0)
    }

    // -- Pattern detection -------------------------------------------------------

    @Test
    fun `FINGERPRINT_WINDOW should be at least 4 for oscillation detection`() {
        assertTrue(AgentConfig.FINGERPRINT_WINDOW >= 4)
    }

    @Test
    fun `STAGNATION_THRESHOLD should be positive`() {
        assertTrue(AgentConfig.STAGNATION_THRESHOLD > 0)
    }

    @Test
    fun `REPLAN_THRESHOLD should be greater than STAGNATION_THRESHOLD`() {
        assertTrue(AgentConfig.REPLAN_THRESHOLD >= AgentConfig.STAGNATION_THRESHOLD)
    }

    @Test
    fun `RECENT_ACTIONS_WINDOW should be positive`() {
        assertTrue(AgentConfig.RECENT_ACTIONS_WINDOW > 0)
    }

    // -- LLM retry backoff -------------------------------------------------------

    @Test
    fun `LLM_RETRY_BASE_DELAY_MS should be positive`() {
        assertTrue(AgentConfig.LLM_RETRY_BASE_DELAY_MS > 0)
    }

    @Test
    fun `LLM_RETRY_MAX_DELAY_MS should be greater than or equal to base delay`() {
        assertTrue(
            AgentConfig.LLM_RETRY_MAX_DELAY_MS >=
                AgentConfig.LLM_RETRY_BASE_DELAY_MS,
        )
    }

    @Test
    fun `LLM_RETRY_MAX_EXPONENT should be positive`() {
        assertTrue(AgentConfig.LLM_RETRY_MAX_EXPONENT > 0)
    }

    @Test
    fun `LLM_RETRY_JITTER_MIN should be between 0 and 1`() {
        assertTrue(AgentConfig.LLM_RETRY_JITTER_MIN > 0.0)
        assertTrue(AgentConfig.LLM_RETRY_JITTER_MIN < 1.0)
    }

    @Test
    fun `LLM_RETRY_JITTER_RANGE should be positive`() {
        assertTrue(AgentConfig.LLM_RETRY_JITTER_RANGE > 0.0)
    }

    @Test
    fun `jitter max should not exceed 2x`() {
        assertTrue(AgentConfig.LLM_RETRY_JITTER_MIN + AgentConfig.LLM_RETRY_JITTER_RANGE <= 2.0)
    }

    // -- Screen geometry heuristics ----------------------------------------------

    @Test
    fun `DEFAULT_SCREEN_HEIGHT should be a typical Android screen height`() {
        assertTrue(AgentConfig.DEFAULT_SCREEN_HEIGHT >= 1920)
        assertTrue(AgentConfig.DEFAULT_SCREEN_HEIGHT <= 3200)
    }

    @Test
    fun `BOTTOM_BAR_FRACTION should be in the lower portion of screen`() {
        assertTrue(AgentConfig.BOTTOM_BAR_FRACTION > 0.5)
        assertTrue(AgentConfig.BOTTOM_BAR_FRACTION < 1.0)
    }

    @Test
    fun `TOP_AREA_FRACTION should be in the upper portion of screen`() {
        assertTrue(AgentConfig.TOP_AREA_FRACTION > 0.0)
        assertTrue(AgentConfig.TOP_AREA_FRACTION < 0.5)
    }

    @Test
    fun `TOP_AREA_FRACTION should be less than BOTTOM_BAR_FRACTION`() {
        assertTrue(AgentConfig.TOP_AREA_FRACTION < AgentConfig.BOTTOM_BAR_FRACTION)
    }

    @Test
    fun `SEND_BUTTON_MIN_SIZE should be less than SEND_BUTTON_MAX_SIZE`() {
        assertTrue(AgentConfig.SEND_BUTTON_MIN_SIZE < AgentConfig.SEND_BUTTON_MAX_SIZE)
    }

    @Test
    fun `MIN_CHAT_BUTTON_LABEL_LENGTH should be less than MAX_CHAT_BUTTON_LABEL_LENGTH`() {
        assertTrue(
            AgentConfig.MIN_CHAT_BUTTON_LABEL_LENGTH <
                AgentConfig.MAX_CHAT_BUTTON_LABEL_LENGTH,
        )
    }

    @Test
    fun `MAX_CHAT_OPTIONS_SHOWN should be positive`() {
        assertTrue(AgentConfig.MAX_CHAT_OPTIONS_SHOWN > 0)
    }

    @Test
    fun `MIN_CONTENT_BUTTONS_FOR_OPTIONS should be at least 1`() {
        assertTrue(AgentConfig.MIN_CONTENT_BUTTONS_FOR_OPTIONS >= 1)
    }

    // -- Misc --------------------------------------------------------------------

    @Test
    fun `OWN_PACKAGE should be the app package name`() {
        assertEquals("com.cssupport.companion", AgentConfig.OWN_PACKAGE)
    }

    @Test
    fun `MAX_NODE_TRAVERSAL_DEPTH should be positive`() {
        assertTrue(AgentConfig.MAX_NODE_TRAVERSAL_DEPTH > 0)
    }

    @Test
    fun `WRONG_SCREEN_PRODUCT_THRESHOLD should be greater than MODERATE threshold`() {
        assertTrue(
            AgentConfig.WRONG_SCREEN_PRODUCT_THRESHOLD >=
                AgentConfig.WRONG_SCREEN_MODERATE_PRODUCT_THRESHOLD,
        )
    }

    @Test
    fun `LOGIN_WALL_MIN_HITS should be positive`() {
        assertTrue(AgentConfig.LOGIN_WALL_MIN_HITS > 0)
    }

    @Test
    fun `LOGIN_WALL_MIN_ITERATIONS should be positive`() {
        assertTrue(AgentConfig.LOGIN_WALL_MIN_ITERATIONS > 0)
    }

    @Test
    fun `SYSTEM_DIALOG_PACKAGES should contain expected packages`() {
        assertTrue(AgentConfig.SYSTEM_DIALOG_PACKAGES.contains("permissioncontroller"))
        assertTrue(AgentConfig.SYSTEM_DIALOG_PACKAGES.contains("packageinstaller"))
        assertTrue(AgentConfig.SYSTEM_DIALOG_PACKAGES.contains("com.android.systemui"))
    }

    @Test
    fun `SYSTEM_DIALOG_PACKAGES should not be empty`() {
        assertFalse(AgentConfig.SYSTEM_DIALOG_PACKAGES.isEmpty())
    }

    // -- Specific value checks for critical constants ----------------------------

    @Test
    fun `MAX_TOTAL_LOOP_ITERATIONS should be 100`() {
        assertEquals(100, AgentConfig.MAX_TOTAL_LOOP_ITERATIONS)
    }

    @Test
    fun `OBSERVATION_MASK_KEEP_RECENT should be 4`() {
        assertEquals(4, AgentConfig.OBSERVATION_MASK_KEEP_RECENT)
    }

    @Test
    fun `CHARS_PER_TOKEN should be 4`() {
        assertEquals(4, AgentConfig.CHARS_PER_TOKEN)
    }
}
