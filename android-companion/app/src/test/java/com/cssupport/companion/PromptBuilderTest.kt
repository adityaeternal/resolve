package com.cssupport.companion

import android.graphics.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for PromptBuilder.
 *
 * PromptBuilder constructs system and user prompts for the LLM.
 * Tests verify:
 * - System prompt contains correct identity, case context, and phase-specific instructions
 * - User message includes screen state, change descriptions, warnings, and guidance
 * - Phase-specific behavior changes
 */
class PromptBuilderTest {

    private lateinit var builder: PromptBuilder
    private val testCaseContext = CaseContext(
        caseId = "CASE-001",
        customerName = "Jane Smith",
        issue = "Wrong item delivered in my order",
        desiredOutcome = "Full refund",
        orderId = "ORD-99999",
        hasAttachments = true,
        targetPlatform = "in.swiggy.android",
    )

    @Before
    fun setUp() {
        builder = PromptBuilder(testCaseContext)
    }

    // == buildSystemPrompt ====================================================

    // -- Core identity --------------------------------------------------------

    @Test
    fun `buildSystemPrompt should include Resolve identity`() {
        val prompt = builder.buildSystemPrompt(NavigationPhase.NAVIGATING_TO_SUPPORT, null)
        assertTrue(prompt.contains("Resolve"))
        assertTrue(prompt.contains("AI agent"))
    }

    @Test
    fun `buildSystemPrompt should instruct agent to speak in first person`() {
        val prompt = builder.buildSystemPrompt(NavigationPhase.NAVIGATING_TO_SUPPORT, null)
        assertTrue(prompt.contains("first person"))
    }

    @Test
    fun `buildSystemPrompt should instruct never reveal AI identity`() {
        val prompt = builder.buildSystemPrompt(NavigationPhase.NAVIGATING_TO_SUPPORT, null)
        assertTrue(prompt.contains("Never reveal you are AI"))
    }

    // -- Case context ---------------------------------------------------------

    @Test
    fun `buildSystemPrompt should include customer name when not default`() {
        val prompt = builder.buildSystemPrompt(NavigationPhase.NAVIGATING_TO_SUPPORT, null)
        assertTrue(prompt.contains("Jane Smith"))
    }

    @Test
    fun `buildSystemPrompt should omit customer name when it is Customer`() {
        val defaultNameBuilder = PromptBuilder(
            testCaseContext.copy(customerName = "Customer"),
        )
        val prompt = defaultNameBuilder.buildSystemPrompt(NavigationPhase.NAVIGATING_TO_SUPPORT, null)
        assertFalse(prompt.contains("Customer: Customer"))
    }

    @Test
    fun `buildSystemPrompt should omit customer name when blank`() {
        val blankNameBuilder = PromptBuilder(
            testCaseContext.copy(customerName = ""),
        )
        val prompt = blankNameBuilder.buildSystemPrompt(NavigationPhase.NAVIGATING_TO_SUPPORT, null)
        assertFalse(prompt.contains("Customer: "))
    }

    @Test
    fun `buildSystemPrompt should include issue description`() {
        val prompt = builder.buildSystemPrompt(NavigationPhase.NAVIGATING_TO_SUPPORT, null)
        assertTrue(prompt.contains("Wrong item delivered in my order"))
    }

    @Test
    fun `buildSystemPrompt should include desired outcome`() {
        val prompt = builder.buildSystemPrompt(NavigationPhase.NAVIGATING_TO_SUPPORT, null)
        assertTrue(prompt.contains("Full refund"))
    }

    @Test
    fun `buildSystemPrompt should include order ID when present`() {
        val prompt = builder.buildSystemPrompt(NavigationPhase.NAVIGATING_TO_SUPPORT, null)
        assertTrue(prompt.contains("ORD-99999"))
    }

    @Test
    fun `buildSystemPrompt should omit order ID when null`() {
        val noOrderBuilder = PromptBuilder(
            testCaseContext.copy(orderId = null),
        )
        val prompt = noOrderBuilder.buildSystemPrompt(NavigationPhase.NAVIGATING_TO_SUPPORT, null)
        assertFalse(prompt.contains("Order:"))
    }

    @Test
    fun `buildSystemPrompt should omit order ID when blank`() {
        val blankOrderBuilder = PromptBuilder(
            testCaseContext.copy(orderId = ""),
        )
        val prompt = blankOrderBuilder.buildSystemPrompt(NavigationPhase.NAVIGATING_TO_SUPPORT, null)
        assertFalse(prompt.contains("Order:"))
    }

    @Test
    fun `buildSystemPrompt should mention evidence when hasAttachments is true`() {
        val prompt = builder.buildSystemPrompt(NavigationPhase.NAVIGATING_TO_SUPPORT, null)
        assertTrue(prompt.contains("Evidence available"))
    }

    @Test
    fun `buildSystemPrompt should not mention evidence when hasAttachments is false`() {
        val noAttachBuilder = PromptBuilder(
            testCaseContext.copy(hasAttachments = false),
        )
        val prompt = noAttachBuilder.buildSystemPrompt(NavigationPhase.NAVIGATING_TO_SUPPORT, null)
        assertFalse(prompt.contains("Evidence available"))
    }

    // -- Screen format instructions -------------------------------------------

    @Test
    fun `buildSystemPrompt should include screen format instructions`() {
        val prompt = builder.buildSystemPrompt(NavigationPhase.NAVIGATING_TO_SUPPORT, null)
        assertTrue(prompt.contains("Screen Format"))
        assertTrue(prompt.contains(">>>"))
        assertTrue(prompt.contains("[N]"))
    }

    // -- Phase-specific: NAVIGATING_TO_SUPPORT --------------------------------

    @Test
    fun `buildSystemPrompt should include navigation instructions for NAVIGATING phase`() {
        val prompt = builder.buildSystemPrompt(NavigationPhase.NAVIGATING_TO_SUPPORT, null)
        assertTrue(prompt.contains("Navigation"))
        assertTrue(prompt.contains("BOTTOM BAR"))
        assertTrue(prompt.contains("Account"))
    }

    @Test
    fun `buildSystemPrompt should include app nav profile when provided for NAVIGATING phase`() {
        val profile = AppNavProfile(
            appName = "TestApp",
            supportPath = listOf("Step 1: Open profile", "Step 2: Tap help"),
            pitfalls = listOf("Don't tap products"),
            profileLocation = "Bottom bar: Account",
            orderHistoryLocation = "Account -> Orders",
        )
        val prompt = builder.buildSystemPrompt(NavigationPhase.NAVIGATING_TO_SUPPORT, profile)
        assertTrue(prompt.contains("TestApp"))
        assertTrue(prompt.contains("Step 1: Open profile"))
    }

    @Test
    fun `buildSystemPrompt should not include profile when null for NAVIGATING phase`() {
        val prompt = builder.buildSystemPrompt(NavigationPhase.NAVIGATING_TO_SUPPORT, null)
        assertFalse(prompt.contains("Navigation (app-specific)"))
    }

    // -- Phase-specific: ON_ORDER_PAGE ----------------------------------------

    @Test
    fun `buildSystemPrompt should include order page instructions`() {
        val prompt = builder.buildSystemPrompt(NavigationPhase.ON_ORDER_PAGE, null)
        assertTrue(prompt.contains("On Orders Page"))
        assertTrue(prompt.contains("Help"))
    }

    @Test
    fun `buildSystemPrompt should include order ID in order page instructions when present`() {
        val prompt = builder.buildSystemPrompt(NavigationPhase.ON_ORDER_PAGE, null)
        assertTrue(prompt.contains("ORD-99999"))
    }

    // -- Phase-specific: ON_SUPPORT_PAGE --------------------------------------

    @Test
    fun `buildSystemPrompt should include support page instructions`() {
        val prompt = builder.buildSystemPrompt(NavigationPhase.ON_SUPPORT_PAGE, null)
        assertTrue(prompt.contains("Support/Help Page"))
        assertTrue(prompt.contains("Chat"))
    }

    // -- Phase-specific: IN_CHAT ----------------------------------------------

    @Test
    fun `buildSystemPrompt should include chat instructions`() {
        val prompt = builder.buildSystemPrompt(NavigationPhase.IN_CHAT, null)
        assertTrue(prompt.contains("Support Chat"))
        assertTrue(prompt.contains("CLICK option buttons"))
    }

    @Test
    fun `buildSystemPrompt should include mark_resolved instruction in chat phase`() {
        val prompt = builder.buildSystemPrompt(NavigationPhase.IN_CHAT, null)
        assertTrue(prompt.contains("mark_resolved"))
    }

    @Test
    fun `buildSystemPrompt should include request_human_review instruction in chat phase`() {
        val prompt = builder.buildSystemPrompt(NavigationPhase.IN_CHAT, null)
        assertTrue(prompt.contains("request_human_review"))
    }

    @Test
    fun `buildSystemPrompt should include suggested message template in chat phase`() {
        val prompt = builder.buildSystemPrompt(NavigationPhase.IN_CHAT, null)
        assertTrue(prompt.contains("ORD-99999"))
        assertTrue(prompt.contains("refund"))
    }

    // -- Rules section --------------------------------------------------------

    @Test
    fun `buildSystemPrompt should include rules section`() {
        val prompt = builder.buildSystemPrompt(NavigationPhase.NAVIGATING_TO_SUPPORT, null)
        assertTrue(prompt.contains("Rules"))
        assertTrue(prompt.contains("press_back"))
        assertTrue(prompt.contains("NEVER type in search bars"))
        assertTrue(prompt.contains("NEVER share SSN"))
    }

    @Test
    fun `buildSystemPrompt should include max iterations in rules`() {
        val prompt = builder.buildSystemPrompt(NavigationPhase.NAVIGATING_TO_SUPPORT, null)
        assertTrue(prompt.contains("${SafetyPolicy.MAX_ITERATIONS}"))
    }

    // == buildUserMessage =====================================================

    @Test
    fun `buildUserMessage should include target app`() {
        val msg = buildBasicUserMessage()
        assertTrue(msg.contains("in.swiggy.android"))
    }

    @Test
    fun `buildUserMessage should include phase display name`() {
        val msg = buildBasicUserMessage(phase = NavigationPhase.IN_CHAT)
        assertTrue(msg.contains("In support chat"))
    }

    @Test
    fun `buildUserMessage should include iteration count and max`() {
        val msg = buildBasicUserMessage(iterationCount = 5)
        assertTrue(msg.contains("Turn: 5 / ${SafetyPolicy.MAX_ITERATIONS}"))
    }

    @Test
    fun `buildUserMessage should include progress tracker when provided`() {
        val msg = buildBasicUserMessage(progressTracker = "## Progress Tracker\n1. [x] Open app\n2. [ ] Navigate")
        assertTrue(msg.contains("Progress Tracker"))
        assertTrue(msg.contains("[x] Open app"))
    }

    @Test
    fun `buildUserMessage should omit progress tracker when blank`() {
        val msg = buildBasicUserMessage(progressTracker = "")
        assertFalse(msg.contains("Progress Tracker"))
    }

    @Test
    fun `buildUserMessage should show FIRST_SCREEN for first screen`() {
        val msg = buildBasicUserMessage(changeDescription = "FIRST_SCREEN")
        assertTrue(msg.contains("First screen observed"))
    }

    @Test
    fun `buildUserMessage should show WARNING for NO_CHANGE`() {
        val msg = buildBasicUserMessage(changeDescription = "NO_CHANGE: Screen is identical")
        assertTrue(msg.contains("WARNING"))
    }

    @Test
    fun `buildUserMessage should show Change for normal change`() {
        val msg = buildBasicUserMessage(changeDescription = "CONTENT_UPDATED: New elements appeared")
        assertTrue(msg.contains("Change: CONTENT_UPDATED"))
    }

    @Test
    fun `buildUserMessage should include oscillation warning when provided`() {
        val msg = buildBasicUserMessage(oscillationWarning = "WARNING: You are oscillating between two screens")
        assertTrue(msg.contains("oscillating"))
    }

    @Test
    fun `buildUserMessage should omit oscillation warning when blank`() {
        val msg = buildBasicUserMessage(oscillationWarning = "")
        assertFalse(msg.contains("oscillating"))
    }

    @Test
    fun `buildUserMessage should include wrong screen warning when provided`() {
        val msg = buildBasicUserMessage(wrongScreenWarning = "WRONG SCREEN: product page")
        assertTrue(msg.contains("WRONG SCREEN"))
    }

    @Test
    fun `buildUserMessage should include banned elements when present`() {
        val msg = buildBasicUserMessage(bannedElementLabels = setOf("pizza menu", "deals"))
        assertTrue(msg.contains("BANNED ELEMENTS"))
        assertTrue(msg.contains("pizza menu"))
        assertTrue(msg.contains("deals"))
    }

    @Test
    fun `buildUserMessage should omit banned elements when empty`() {
        val msg = buildBasicUserMessage(bannedElementLabels = emptySet())
        assertFalse(msg.contains("BANNED ELEMENTS"))
    }

    @Test
    fun `buildUserMessage should include replanning hint when provided`() {
        val msg = buildBasicUserMessage(replanningHint = "STUCK: You've spent 6 turns navigating")
        assertTrue(msg.contains("STUCK"))
    }

    @Test
    fun `buildUserMessage should include scroll warning when over limit`() {
        val msg = buildBasicUserMessage(totalScrollCount = AgentConfig.MAX_SCROLL_ACTIONS + 1)
        assertTrue(msg.contains("scroll actions"))
        assertTrue(msg.contains("WARNING"))
    }

    @Test
    fun `buildUserMessage should not include scroll warning below limit`() {
        val msg = buildBasicUserMessage(totalScrollCount = 1)
        assertFalse(msg.contains("scroll actions"))
    }

    @Test
    fun `buildUserMessage should include screen state section`() {
        val msg = buildBasicUserMessage(formattedScreen = "Package: in.swiggy.android\nScreen: HomeActivity")
        assertTrue(msg.contains("## Screen State"))
        assertTrue(msg.contains("Package: in.swiggy.android"))
    }

    // -- Phase-specific user message guidance ---------------------------------

    @Test
    fun `buildUserMessage NAVIGATING should suggest profile or account tab`() {
        val msg = buildBasicUserMessage(phase = NavigationPhase.NAVIGATING_TO_SUPPORT)
        assertTrue(msg.contains("Profile") || msg.contains("Account") || msg.contains("FOLLOW THESE STEPS"))
    }

    @Test
    fun `buildUserMessage NAVIGATING with profile should include app steps`() {
        val profile = AppNavProfile(
            appName = "Swiggy",
            supportPath = listOf("Tap ACCOUNT in bottom bar", "Tap My Orders"),
            pitfalls = emptyList(),
            profileLocation = "Bottom bar",
            orderHistoryLocation = "Account -> Orders",
        )
        val msg = buildBasicUserMessage(
            phase = NavigationPhase.NAVIGATING_TO_SUPPORT,
            appNavProfile = profile,
        )
        assertTrue(msg.contains("FOLLOW THESE STEPS"))
        assertTrue(msg.contains("Tap ACCOUNT"))
    }

    @Test
    fun `buildUserMessage ON_ORDER_PAGE should suggest finding order`() {
        val msg = buildBasicUserMessage(phase = NavigationPhase.ON_ORDER_PAGE)
        assertTrue(msg.contains("order") || msg.contains("Help") || msg.contains("Support"))
    }

    @Test
    fun `buildUserMessage ON_SUPPORT_PAGE should suggest finding chat`() {
        val msg = buildBasicUserMessage(phase = NavigationPhase.ON_SUPPORT_PAGE)
        assertTrue(msg.contains("Chat") || msg.contains("Live Chat") || msg.contains("issue category"))
    }

    @Test
    fun `buildUserMessage IN_CHAT should indicate in support chat`() {
        val msg = buildBasicUserMessage(phase = NavigationPhase.IN_CHAT)
        assertTrue(msg.contains("GOAL") || msg.contains("support chat"))
    }

    @Test
    fun `buildUserMessage should end with tool selection instruction`() {
        val msg = buildBasicUserMessage()
        assertTrue(msg.contains("Choose exactly one tool"))
    }

    // -- Helpers ---------------------------------------------------------------

    private fun buildBasicUserMessage(
        formattedScreen: String = "Package: com.test\n(empty screen)",
        changeDescription: String = "FIRST_SCREEN",
        oscillationWarning: String = "",
        wrongScreenWarning: String = "",
        phase: NavigationPhase = NavigationPhase.NAVIGATING_TO_SUPPORT,
        iterationCount: Int = 1,
        progressTracker: String = "",
        bannedElementLabels: Set<String> = emptySet(),
        replanningHint: String = "",
        totalScrollCount: Int = 0,
        appNavProfile: AppNavProfile? = null,
    ): String {
        return builder.buildUserMessage(
            formattedScreen = formattedScreen,
            changeDescription = changeDescription,
            oscillationWarning = oscillationWarning,
            wrongScreenWarning = wrongScreenWarning,
            screenState = null,
            currentPhase = phase,
            iterationCount = iterationCount,
            progressTracker = progressTracker,
            bannedElementLabels = bannedElementLabels,
            replanningHint = replanningHint,
            totalScrollCount = totalScrollCount,
            appNavProfile = appNavProfile,
        )
    }
}
