package com.cssupport.companion

import android.graphics.Rect
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for AgentLoop orchestration behavior:
 * - Terminal conditions (resolved, needs human review, failure)
 * - State transitions (idle, running, paused, completed)
 * - Event emission
 * - Safety policy integration
 * - Error classification integration
 *
 * These tests mock LLMClient to return scripted responses and verify
 * the agent loop's behavioral outcomes (not internal state).
 *
 * Note: Because MockK + coroutines-test consumes significant heap,
 * these tests are deliberately minimal: one runTest per terminal condition.
 */
class AgentLoopOrchestrationTest {

    private lateinit var engine: AccessibilityEngine
    private lateinit var llmClient: LLMClient
    private lateinit var caseContext: CaseContext
    private val collectedEvents = mutableListOf<AgentEvent>()

    @Before
    fun setUp() {
        engine = mockk(relaxed = true)
        llmClient = mockk(relaxed = true)
        caseContext = CaseContext(
            caseId = "CASE-2024-001",
            customerName = "Aditya Sharma",
            issue = "Order #ORD-5678 arrived with wrong items",
            desiredOutcome = "Full refund",
            orderId = "ORD-5678",
            hasAttachments = false,
            targetPlatform = "in.swiggy.android",
        )
        collectedEvents.clear()
        AgentLogStore.clear()
    }

    // ── mark_resolved produces Resolved result ──────────────────────────

    @Test
    fun `agent should return Resolved when LLM calls mark_resolved`() = runTest {
        val targetScreen = makeScreen("in.swiggy.android")

        coEvery { engine.captureScreenState() } returns targetScreen
        coEvery { engine.waitForStableScreen(any(), any()) } returns targetScreen

        coEvery { llmClient.chatCompletion(any(), any(), any()) } returns AgentDecision(
            action = AgentAction.MarkResolved(summary = "Refund of Rs 450 processed, ref #RF-789"),
            reasoning = "Support confirmed the refund",
            toolCallId = "call_001",
            toolName = "mark_resolved",
            toolArguments = """{"summary": "Refund of Rs 450 processed, ref #RF-789"}""",
        )

        val loop = AgentLoop(
            engine = engine,
            llmClient = llmClient,
            caseContext = caseContext,
            onEvent = { collectedEvents.add(it) },
        )

        val result = loop.run()

        assertTrue("Expected Resolved, got $result", result is AgentResult.Resolved)
        val resolved = result as AgentResult.Resolved
        assertTrue(resolved.summary.contains("Rs 450"))
        assertTrue(resolved.summary.contains("RF-789"))
        assertTrue(resolved.iterationsCompleted >= 1)

        // Verify events were emitted
        assertTrue(collectedEvents.any { it is AgentEvent.Started })
        assertTrue(collectedEvents.any { it is AgentEvent.Resolved })
        assertEquals("CASE-2024-001", (collectedEvents.first { it is AgentEvent.Started } as AgentEvent.Started).caseId)
    }

    // ── request_human_review produces NeedsHumanReview result ───────────

    @Test
    fun `agent should return NeedsHumanReview when LLM requests human review`() = runTest {
        val targetScreen = makeScreen("in.swiggy.android")

        coEvery { engine.captureScreenState() } returns targetScreen
        coEvery { engine.waitForStableScreen(any(), any()) } returns targetScreen

        coEvery { llmClient.chatCompletion(any(), any(), any()) } returns AgentDecision(
            action = AgentAction.RequestHumanReview(
                reason = "OTP verification required",
                needsInput = true,
                inputPrompt = "Enter the 6-digit OTP",
            ),
            reasoning = "The app is asking for an OTP",
            toolCallId = "call_002",
            toolName = "request_human_review",
            toolArguments = """{"reason": "OTP verification required", "needsInput": true}""",
        )

        val loop = AgentLoop(
            engine = engine,
            llmClient = llmClient,
            caseContext = caseContext,
            onEvent = { collectedEvents.add(it) },
        )

        val result = loop.run()

        assertTrue("Expected NeedsHumanReview, got $result", result is AgentResult.NeedsHumanReview)
        assertTrue((result as AgentResult.NeedsHumanReview).reason.contains("OTP"))
    }

    // ── Safety policy blocks financial actions ──────────────────────────

    @Test
    fun `agent should return NeedsHumanReview when safety policy flags financial action`() = runTest {
        val targetScreen = makeScreen("in.swiggy.android")

        coEvery { engine.captureScreenState() } returns targetScreen
        coEvery { engine.waitForStableScreen(any(), any()) } returns targetScreen

        coEvery { llmClient.chatCompletion(any(), any(), any()) } returns AgentDecision(
            action = AgentAction.ClickElement(elementId = 1, label = "Pay Now", expectedOutcome = "Complete payment"),
            reasoning = "Clicking pay button",
            toolCallId = "call_003",
            toolName = "click_element",
            toolArguments = """{"elementId": 1, "expectedOutcome": "Complete payment"}""",
        )

        val loop = AgentLoop(
            engine = engine,
            llmClient = llmClient,
            caseContext = caseContext,
            onEvent = { collectedEvents.add(it) },
        )

        val result = loop.run()

        assertTrue("Expected NeedsHumanReview for financial action, got $result",
            result is AgentResult.NeedsHumanReview)
    }

    // ── Terminal LLM error produces Failed result ───────────────────────

    @Test
    fun `agent should return Failed after terminal LLM error`() = runTest {
        val targetScreen = makeScreen("in.swiggy.android")

        coEvery { engine.captureScreenState() } returns targetScreen
        coEvery { engine.waitForStableScreen(any(), any()) } returns targetScreen

        coEvery { llmClient.chatCompletion(any(), any(), any()) } throws
            LLMException("HTTP 401: Invalid API key")

        val loop = AgentLoop(
            engine = engine,
            llmClient = llmClient,
            caseContext = caseContext,
            onEvent = { collectedEvents.add(it) },
        )

        val result = loop.run()

        assertTrue("Expected Failed for auth error, got $result", result is AgentResult.Failed)
        assertTrue((result as AgentResult.Failed).reason.contains("API key"))
    }

    // ── State transitions ───────────────────────────────────────────────

    @Test
    fun `state should be IDLE before run`() {
        val loop = AgentLoop(engine = engine, llmClient = llmClient, caseContext = caseContext)
        assertEquals(AgentLoopState.IDLE, loop.state.value)
    }

    @Test
    fun `state should be COMPLETED after successful run`() = runTest {
        val targetScreen = makeScreen("in.swiggy.android")
        coEvery { engine.captureScreenState() } returns targetScreen
        coEvery { engine.waitForStableScreen(any(), any()) } returns targetScreen
        coEvery { llmClient.chatCompletion(any(), any(), any()) } returns AgentDecision(
            action = AgentAction.MarkResolved("Done"),
            reasoning = "Done",
            toolCallId = "call_005",
            toolName = "mark_resolved",
            toolArguments = """{"summary": "Done"}""",
        )

        val loop = AgentLoop(engine = engine, llmClient = llmClient, caseContext = caseContext)
        loop.run()

        assertEquals(AgentLoopState.COMPLETED, loop.state.value)
    }

    @Test
    fun `pause should change state to PAUSED`() {
        val loop = AgentLoop(engine = engine, llmClient = llmClient, caseContext = caseContext)
        loop.pause()
        assertEquals(AgentLoopState.PAUSED, loop.state.value)
    }

    @Test
    fun `resume after pause should change state to RUNNING`() {
        val loop = AgentLoop(engine = engine, llmClient = llmClient, caseContext = caseContext)
        loop.pause()
        loop.resume()
        assertEquals(AgentLoopState.RUNNING, loop.state.value)
    }

    // ── User-friendly error messages ────────────────────────────────────

    @Test
    fun `agent should produce user-friendly message for timeout error`() = runTest {
        val targetScreen = makeScreen("in.swiggy.android")
        coEvery { engine.captureScreenState() } returns targetScreen
        coEvery { engine.waitForStableScreen(any(), any()) } returns targetScreen
        coEvery { llmClient.chatCompletion(any(), any(), any()) } throws
            java.net.SocketTimeoutException("Read timed out")

        val loop = AgentLoop(engine = engine, llmClient = llmClient, caseContext = caseContext)
        val result = loop.run()

        assertTrue(result is AgentResult.Failed)
        assertTrue((result as AgentResult.Failed).reason.contains("timed out"))
    }

    @Test
    fun `agent should produce user-friendly message for no internet`() = runTest {
        val targetScreen = makeScreen("in.swiggy.android")
        coEvery { engine.captureScreenState() } returns targetScreen
        coEvery { engine.waitForStableScreen(any(), any()) } returns targetScreen
        coEvery { llmClient.chatCompletion(any(), any(), any()) } throws
            java.net.UnknownHostException("api.openai.com")

        val loop = AgentLoop(engine = engine, llmClient = llmClient, caseContext = caseContext)
        val result = loop.run()

        assertTrue(result is AgentResult.Failed)
        val msg = (result as AgentResult.Failed).reason.lowercase()
        assertTrue(msg.contains("internet") || msg.contains("network"))
    }

    // ── Data class tests ────────────────────────────────────────────────

    @Test
    fun `CaseContext should store all fields`() {
        assertEquals("CASE-2024-001", caseContext.caseId)
        assertEquals("Aditya Sharma", caseContext.customerName)
        assertEquals("Order #ORD-5678 arrived with wrong items", caseContext.issue)
        assertEquals("Full refund", caseContext.desiredOutcome)
        assertEquals("ORD-5678", caseContext.orderId)
        assertFalse(caseContext.hasAttachments)
        assertEquals("in.swiggy.android", caseContext.targetPlatform)
    }

    @Test
    fun `CaseContext with null orderId should work`() {
        val noOrderContext = caseContext.copy(orderId = null)
        assertTrue(noOrderContext.orderId == null)
    }

    @Test
    fun `NavigationPhase should have correct display names`() {
        assertEquals("Navigating to support", NavigationPhase.NAVIGATING_TO_SUPPORT.displayName)
        assertEquals("Viewing orders", NavigationPhase.ON_ORDER_PAGE.displayName)
        assertEquals("On support page", NavigationPhase.ON_SUPPORT_PAGE.displayName)
        assertEquals("In support chat", NavigationPhase.IN_CHAT.displayName)
    }

    @Test
    fun `SubGoal should store description and status`() {
        val goal = SubGoal("Open the target app", SubGoalStatus.PENDING)
        assertEquals("Open the target app", goal.description)
        assertEquals(SubGoalStatus.PENDING, goal.status)

        val completed = goal.copy(status = SubGoalStatus.DONE)
        assertEquals(SubGoalStatus.DONE, completed.status)
    }

    @Test
    fun `AgentResult Resolved should carry summary and iteration count`() {
        val result = AgentResult.Resolved(summary = "Refund issued", iterationsCompleted = 7)
        assertEquals("Refund issued", result.summary)
        assertEquals(7, result.iterationsCompleted)
    }

    @Test
    fun `AgentResult Failed should carry reason`() {
        val result = AgentResult.Failed(reason = "API key invalid")
        assertEquals("API key invalid", result.reason)
    }

    @Test
    fun `AgentResult NeedsHumanReview should carry reason and iterations`() {
        val result = AgentResult.NeedsHumanReview(reason = "OTP required", iterationsCompleted = 3)
        assertEquals("OTP required", result.reason)
        assertEquals(3, result.iterationsCompleted)
    }

    @Test
    fun `ActionResult types should carry correct data`() {
        assertTrue(ActionResult.Success is ActionResult)

        val failed = ActionResult.Failed(reason = "Element not found")
        assertEquals("Element not found", failed.reason)

        val resolved = ActionResult.Resolved(summary = "Refund issued")
        assertEquals("Refund issued", resolved.summary)

        val humanReview = ActionResult.HumanReviewNeeded(reason = "Need OTP", inputPrompt = "Enter OTP")
        assertEquals("Need OTP", humanReview.reason)
        assertEquals("Enter OTP", humanReview.inputPrompt)
    }

    @Test
    fun `VerificationResult types should carry observations`() {
        val success = VerificationResult.Success("Screen changed to orders page")
        assertEquals("Screen changed to orders page", success.observation)

        val warning = VerificationResult.Warning("Screen did not change after click")
        assertEquals("Screen did not change after click", warning.observation)
    }

    @Test
    fun `AgentEvent types should carry correct data`() {
        val started = AgentEvent.Started("case-123")
        assertEquals("case-123", started.caseId)

        val captured = AgentEvent.ScreenCaptured("com.example", 15)
        assertEquals("com.example", captured.packageName)
        assertEquals(15, captured.elementCount)

        val resolved = AgentEvent.Resolved("Issue resolved")
        assertEquals("Issue resolved", resolved.summary)

        val error = AgentEvent.Error("Something went wrong")
        assertEquals("Something went wrong", error.message)

        val failed = AgentEvent.Failed("Timeout")
        assertEquals("Timeout", failed.reason)
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun makeScreen(packageName: String): ScreenState {
        return ScreenState(
            packageName = packageName,
            activityName = "$packageName.TestActivity",
            elements = listOf(
                UIElement(
                    id = null,
                    className = "android.widget.TextView",
                    text = "Home",
                    contentDescription = null,
                    isClickable = true,
                    isEditable = false,
                    isScrollable = false,
                    isCheckable = false,
                    isChecked = null,
                    isFocused = false,
                    isEnabled = true,
                    bounds = Rect(),
                    childCount = 0,
                ),
            ),
            focusedElement = null,
            timestamp = System.currentTimeMillis(),
        )
    }
}
