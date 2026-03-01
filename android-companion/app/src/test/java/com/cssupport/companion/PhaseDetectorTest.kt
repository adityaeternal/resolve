package com.cssupport.companion

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for PhaseDetector.
 *
 * NOTE: android.graphics.Rect is a stub in unit tests (isReturnDefaultValues = true).
 * All Rect fields return 0 and centerX/centerY return 0. This means spatial tests
 * (e.g., hasBottomInput detection based on bounds.centerY) cannot be fully validated
 * in pure JUnit. However, we can test all text-based logic which is the primary
 * detection mechanism.
 *
 * Tested behaviors:
 * - Phase detection from screen text indicators
 * - Navigation attempt counting
 * - Screen change detection (detectChanges)
 * - Fingerprint tracking and oscillation detection
 * - Stagnation tracking
 * - Login wall detection
 * - Wrong screen detection
 * - Sub-goal initialization and progress
 * - Re-planning hints
 * - Reset behavior
 */
class PhaseDetectorTest {

    private lateinit var detector: PhaseDetector
    private val testCaseContext = CaseContext(
        caseId = "TEST-001",
        customerName = "John Doe",
        issue = "Missing item in my order",
        desiredOutcome = "Full refund",
        orderId = "ORD-12345",
        hasAttachments = false,
        targetPlatform = "in.swiggy.android",
    )

    @Before
    fun setUp() {
        detector = PhaseDetector(testCaseContext)
    }

    // -- Initial state --------------------------------------------------------

    @Test
    fun `initial phase should be NAVIGATING_TO_SUPPORT`() {
        assertEquals(NavigationPhase.NAVIGATING_TO_SUPPORT, detector.currentPhase)
    }

    @Test
    fun `initial navigation attempt count should be 0`() {
        assertEquals(0, detector.navigationAttemptCount)
    }

    @Test
    fun `initial stagnation count should be 0`() {
        assertEquals(0, detector.stagnationCount)
    }

    @Test
    fun `initial previous screen state should be null`() {
        assertNull(detector.previousScreenState)
    }

    @Test
    fun `initial previous screen fingerprint should be empty`() {
        assertEquals("", detector.previousScreenFingerprint)
    }

    @Test
    fun `initial banned element labels should be empty`() {
        assertTrue(detector.bannedElementLabels.isEmpty())
    }

    // -- reset ----------------------------------------------------------------

    @Test
    fun `reset should restore all state to initial values`() {
        // Mutate some state
        detector.updateNavigationPhase(makeChatScreen())
        detector.stagnationCount  // read only
        detector.previousScreenState = makeScreen()
        detector.previousScreenFingerprint = "abc123"
        detector.bannedElementLabels.add("bad label")
        detector.totalScrollCount = 5

        detector.reset()

        assertEquals(NavigationPhase.NAVIGATING_TO_SUPPORT, detector.currentPhase)
        assertEquals(0, detector.navigationAttemptCount)
        assertNull(detector.previousScreenState)
        assertEquals("", detector.previousScreenFingerprint)
        assertEquals(0, detector.stagnationCount)
        assertEquals(0, detector.totalScrollCount)
        assertTrue(detector.bannedElementLabels.isEmpty())
    }

    @Test
    fun `reset should resolve app nav profile from target platform`() {
        detector.reset()
        // Swiggy is in the AppNavigationKnowledge profiles
        val profile = detector.appNavProfile
        assertNotNull(profile)
        assertEquals("Swiggy", profile!!.appName)
    }

    // -- updateNavigationPhase ------------------------------------------------

    @Test
    fun `updateNavigationPhase should detect IN_CHAT when chat indicators present with input field`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "Type a message", isEditable = true),
                makeElement(text = "Send", isClickable = true),
                makeElement(text = "Hi, how can I help?"),
            ),
        )

        detector.updateNavigationPhase(screen)
        assertEquals(NavigationPhase.IN_CHAT, detector.currentPhase)
    }

    @Test
    fun `updateNavigationPhase should detect IN_CHAT when chatbot indicators present`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "Virtual Assistant"),
                makeElement(text = "How can I help you today?"),
                makeElement(text = "Select an option", isClickable = true),
            ),
        )

        detector.updateNavigationPhase(screen)
        assertEquals(NavigationPhase.IN_CHAT, detector.currentPhase)
    }

    @Test
    fun `updateNavigationPhase should detect ON_SUPPORT_PAGE when help and support present`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "Help Center"),
                makeElement(text = "Get help with your order"),
                makeElement(text = "Contact support", isClickable = true),
                makeElement(text = "FAQ", isClickable = true),
            ),
        )

        detector.updateNavigationPhase(screen)
        assertEquals(NavigationPhase.ON_SUPPORT_PAGE, detector.currentPhase)
    }

    @Test
    fun `updateNavigationPhase should detect ON_ORDER_PAGE when order indicators present`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "Order Details"),
                makeElement(text = "Order #12345"),
                makeElement(text = "Delivered on Feb 20"),
            ),
        )

        detector.updateNavigationPhase(screen)
        assertEquals(NavigationPhase.ON_ORDER_PAGE, detector.currentPhase)
    }

    @Test
    fun `updateNavigationPhase should default to NAVIGATING_TO_SUPPORT when no indicators match`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "Pizza Margherita"),
                makeElement(text = "Burger"),
                makeElement(text = "Fries"),
            ),
        )

        detector.updateNavigationPhase(screen)
        assertEquals(NavigationPhase.NAVIGATING_TO_SUPPORT, detector.currentPhase)
    }

    @Test
    fun `updateNavigationPhase should increment navigationAttemptCount in NAVIGATING_TO_SUPPORT`() {
        val screen = makeScreen(
            elements = listOf(makeElement(text = "Home")),
        )

        detector.updateNavigationPhase(screen)
        assertEquals(1, detector.navigationAttemptCount)

        detector.updateNavigationPhase(screen)
        assertEquals(2, detector.navigationAttemptCount)
    }

    @Test
    fun `updateNavigationPhase should reset navigationAttemptCount when leaving NAVIGATING_TO_SUPPORT`() {
        // First, stay in NAVIGATING phase
        val navScreen = makeScreen(elements = listOf(makeElement(text = "Home")))
        detector.updateNavigationPhase(navScreen)
        detector.updateNavigationPhase(navScreen)
        assertEquals(2, detector.navigationAttemptCount)

        // Now transition to IN_CHAT
        detector.updateNavigationPhase(makeChatScreen())
        assertEquals(0, detector.navigationAttemptCount)
    }

    @Test
    fun `updateNavigationPhase should detect IN_CHAT with chatbot menus`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "What can I help you with?"),
                makeElement(text = "Missing item", isClickable = true),
                makeElement(text = "Wrong order", isClickable = true),
            ),
        )

        detector.updateNavigationPhase(screen)
        assertEquals(NavigationPhase.IN_CHAT, detector.currentPhase)
    }

    @Test
    fun `updateNavigationPhase should detect IN_CHAT for queries and feedback`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "Queries & Feedback"),
                makeElement(text = "Select a topic", isClickable = true),
            ),
        )

        detector.updateNavigationPhase(screen)
        assertEquals(NavigationPhase.IN_CHAT, detector.currentPhase)
    }

    @Test
    fun `updateNavigationPhase should use case-insensitive matching`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "ORDER DETAILS"),
                makeElement(text = "ORDER STATUS: Delivered"),
            ),
        )

        detector.updateNavigationPhase(screen)
        assertEquals(NavigationPhase.ON_ORDER_PAGE, detector.currentPhase)
    }

    @Test
    fun `updateNavigationPhase should include contentDescription in text matching`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(contentDescription = "virtual assistant"),
                makeElement(contentDescription = "how can i help"),
            ),
        )

        detector.updateNavigationPhase(screen)
        assertEquals(NavigationPhase.IN_CHAT, detector.currentPhase)
    }

    // -- detectChanges --------------------------------------------------------

    @Test
    fun `detectChanges should return FIRST_SCREEN when no previous fingerprint`() {
        val result = detector.detectChanges("abc123", makeScreen())
        assertEquals("FIRST_SCREEN", result)
    }

    @Test
    fun `detectChanges should return NO_CHANGE when fingerprint is identical`() {
        detector.previousScreenFingerprint = "same_fp"
        val result = detector.detectChanges("same_fp", makeScreen())
        assertTrue(result.startsWith("NO_CHANGE"))
    }

    @Test
    fun `detectChanges should return SCREEN_CHANGED when no previous state available`() {
        detector.previousScreenFingerprint = "old_fp"
        detector.previousScreenState = null
        val result = detector.detectChanges("new_fp", makeScreen())
        assertEquals("SCREEN_CHANGED", result)
    }

    @Test
    fun `detectChanges should detect NEW APP when package changes`() {
        detector.previousScreenFingerprint = "old"
        detector.previousScreenState = makeScreen(packageName = "com.old.app")

        val newScreen = makeScreen(packageName = "com.new.app")
        val result = detector.detectChanges("new", newScreen)
        assertTrue(result.contains("NEW APP"))
        assertTrue(result.contains("com.old.app"))
        assertTrue(result.contains("com.new.app"))
    }

    @Test
    fun `detectChanges should detect NEW SCREEN when activity changes`() {
        detector.previousScreenFingerprint = "old"
        detector.previousScreenState = makeScreen(
            packageName = "com.app",
            activityName = "com.app.HomeActivity",
        )

        val newScreen = makeScreen(
            packageName = "com.app",
            activityName = "com.app.OrdersActivity",
        )
        val result = detector.detectChanges("new", newScreen)
        assertTrue(result.contains("NEW SCREEN"))
        assertTrue(result.contains("OrdersActivity"))
    }

    @Test
    fun `detectChanges should detect CONTENT_UPDATED when elements change`() {
        detector.previousScreenFingerprint = "old"
        detector.previousScreenState = makeScreen(
            packageName = "com.app",
            activityName = "com.app.HomeActivity",
            elements = listOf(makeElement(text = "Home"), makeElement(text = "Orders")),
        )

        val newScreen = makeScreen(
            packageName = "com.app",
            activityName = "com.app.HomeActivity",
            elements = listOf(
                makeElement(text = "Home"),
                makeElement(text = "Orders"),
                makeElement(text = "Chat with us"),
            ),
        )
        val result = detector.detectChanges("new", newScreen)
        assertTrue(result.contains("CONTENT_UPDATED"))
        assertTrue(result.contains("Chat with us"))
    }

    @Test
    fun `detectChanges should detect SCREEN_CHANGED when same activity but no label diff`() {
        detector.previousScreenFingerprint = "old"
        detector.previousScreenState = makeScreen(
            packageName = "com.app",
            activityName = "com.app.HomeActivity",
            elements = listOf(makeElement(text = "Hello")),
        )

        val newScreen = makeScreen(
            packageName = "com.app",
            activityName = "com.app.HomeActivity",
            elements = listOf(makeElement(text = "Hello")),
        )
        val result = detector.detectChanges("different_fp", newScreen)
        assertTrue(result.contains("SCREEN_CHANGED"))
    }

    // -- trackFingerprint -----------------------------------------------------

    @Test
    fun `trackFingerprint should maintain window of FINGERPRINT_WINDOW size`() {
        // Add more than FINGERPRINT_WINDOW fingerprints
        repeat(AgentConfig.FINGERPRINT_WINDOW + 5) { i ->
            detector.trackFingerprint("fp_$i")
        }
        // Can't directly access recentFingerprints, but oscillation detection uses them
        // Just verify it doesn't crash
    }

    // -- trackAction ----------------------------------------------------------

    @Test
    fun `trackAction should record actions for pattern detection`() {
        detector.trackAction("click 'Help'")
        assertEquals("click 'Help'", detector.lastActionDesc())
    }

    @Test
    fun `lastActionDesc should return empty string when no actions tracked`() {
        assertEquals("", detector.lastActionDesc())
    }

    @Test
    fun `trackAction should maintain window of RECENT_ACTIONS_WINDOW size`() {
        repeat(AgentConfig.RECENT_ACTIONS_WINDOW + 5) { i ->
            detector.trackAction("action_$i")
        }
        // Should contain the most recent action
        val lastIdx = AgentConfig.RECENT_ACTIONS_WINDOW + 4
        assertEquals("action_$lastIdx", detector.lastActionDesc())
    }

    // -- updateStagnation -----------------------------------------------------

    @Test
    fun `updateStagnation should increment count on NO_CHANGE`() {
        detector.updateStagnation("NO_CHANGE: Screen is identical")
        assertEquals(1, detector.stagnationCount)
    }

    @Test
    fun `updateStagnation should reset count on actual change`() {
        detector.updateStagnation("NO_CHANGE: identical")
        detector.updateStagnation("NO_CHANGE: identical")
        assertEquals(2, detector.stagnationCount)

        detector.updateStagnation("CONTENT_UPDATED: New elements appeared")
        assertEquals(0, detector.stagnationCount)
    }

    @Test
    fun `updateStagnation should return false below threshold`() {
        val result = detector.updateStagnation("NO_CHANGE: identical")
        assertFalse(result)
    }

    @Test
    fun `updateStagnation should return true at threshold`() {
        repeat(AgentConfig.STAGNATION_THRESHOLD - 1) {
            detector.updateStagnation("NO_CHANGE: identical")
        }
        val result = detector.updateStagnation("NO_CHANGE: identical")
        assertTrue(result)
    }

    @Test
    fun `updateStagnation should return true above threshold`() {
        repeat(AgentConfig.STAGNATION_THRESHOLD + 1) {
            detector.updateStagnation("NO_CHANGE: identical")
        }
        val result = detector.updateStagnation("NO_CHANGE: identical")
        assertTrue(result)
    }

    // -- buildStagnationHint --------------------------------------------------

    @Test
    fun `buildStagnationHint should include stagnation count`() {
        repeat(5) { detector.updateStagnation("NO_CHANGE: identical") }
        val hint = detector.buildStagnationHint()
        assertTrue(hint.contains("5 turns"))
    }

    @Test
    fun `buildStagnationHint should suggest press_back`() {
        detector.updateStagnation("NO_CHANGE: identical")
        val hint = detector.buildStagnationHint()
        assertTrue(hint.contains("press_back"))
    }

    // -- detectOscillation ----------------------------------------------------

    @Test
    fun `detectOscillation should return empty when fewer than 4 fingerprints`() {
        detector.trackFingerprint("A")
        detector.trackFingerprint("B")
        detector.trackFingerprint("A")
        assertEquals("", detector.detectOscillation())
    }

    @Test
    fun `detectOscillation should detect A-B-A-B pattern`() {
        detector.trackFingerprint("A")
        detector.trackFingerprint("B")
        detector.trackFingerprint("A")
        detector.trackFingerprint("B")

        val result = detector.detectOscillation()
        assertTrue(result.contains("oscillating"))
    }

    @Test
    fun `detectOscillation should detect 3 identical fingerprints`() {
        detector.trackFingerprint("X")
        detector.trackFingerprint("X")
        detector.trackFingerprint("X")

        val result = detector.detectOscillation()
        assertTrue(result.contains("not changed"))
    }

    @Test
    fun `detectOscillation should return empty for non-oscillating pattern`() {
        detector.trackFingerprint("A")
        detector.trackFingerprint("B")
        detector.trackFingerprint("C")
        detector.trackFingerprint("D")

        assertEquals("", detector.detectOscillation())
    }

    // -- detectLoginWall ------------------------------------------------------

    @Test
    fun `detectLoginWall should return false when not in NAVIGATING phase`() {
        detector.updateNavigationPhase(makeChatScreen())
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "Login", isClickable = true),
                makeElement(text = "Sign in"),
                makeElement(text = "Create account"),
            ),
        )
        assertFalse(detector.detectLoginWall(screen, iterationCount = 10))
    }

    @Test
    fun `detectLoginWall should return false when iterationCount is too low`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "Login", isClickable = true),
                makeElement(text = "Sign in"),
                makeElement(text = "Create account"),
            ),
        )
        assertFalse(detector.detectLoginWall(screen, iterationCount = 1))
    }

    @Test
    fun `detectLoginWall should detect login screen with login indicators`() {
        // Phase must be NAVIGATING_TO_SUPPORT (default)
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "Login", isClickable = true),
                makeElement(text = "Sign in"),
                makeElement(text = "Create account"),
                makeElement(text = "Enter your phone number"),
            ),
        )
        // Iteration count must be > LOGIN_WALL_MIN_ITERATIONS
        assertTrue(detector.detectLoginWall(screen, iterationCount = AgentConfig.LOGIN_WALL_MIN_ITERATIONS + 1))
    }

    @Test
    fun `detectLoginWall should return false when logged-in indicators present`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "Login", isClickable = true),
                makeElement(text = "Sign in"),
                makeElement(text = "My Orders"),
                makeElement(text = "Order History"),
            ),
        )
        assertFalse(detector.detectLoginWall(screen, iterationCount = 10))
    }

    // -- detectWrongScreen ----------------------------------------------------

    @Test
    fun `detectWrongScreen should return empty when not navigating`() {
        detector.updateNavigationPhase(makeChatScreen())
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "Add to cart"),
                makeElement(text = "Buy now"),
                makeElement(text = "Quantity"),
                makeElement(text = "Extra cheese"),
                makeElement(text = "Toppings"),
            ),
        )
        assertEquals("", detector.detectWrongScreen(screen))
    }

    @Test
    fun `detectWrongScreen should detect product page with many product keywords`() {
        // Stay in NAVIGATING_TO_SUPPORT
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "Add to cart", isClickable = true),
                makeElement(text = "Buy now", isClickable = true),
                makeElement(text = "Quantity: 1"),
                makeElement(text = "Extra cheese"),
                makeElement(text = "Toppings"),
            ),
        )
        val result = detector.detectWrongScreen(screen)
        assertTrue(result.contains("WRONG SCREEN"))
    }

    @Test
    fun `detectWrongScreen should not trigger when navigation keywords present`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "Add to cart", isClickable = true),
                makeElement(text = "Account"),
                makeElement(text = "Orders"),
                makeElement(text = "Help"),
            ),
        )
        val result = detector.detectWrongScreen(screen)
        // nav keywords count (3) > threshold so should not trigger
        assertEquals("", result)
    }

    @Test
    fun `detectWrongScreen should detect wallet activity`() {
        val screen = makeScreen(
            activityName = "com.app.MyWalletActivity",
            elements = listOf(makeElement(text = "Balance: 500")),
        )
        val result = detector.detectWrongScreen(screen)
        assertTrue(result.contains("WRONG SCREEN"))
        assertTrue(result.contains("Wallet"))
    }

    // -- banElementFromWrongScreen --------------------------------------------

    @Test
    fun `banElementFromWrongScreen should ban quoted label from last action`() {
        detector.trackAction("""click "Pizza Menu" at [3]""")
        detector.banElementFromWrongScreen("WRONG SCREEN: product page detected")
        assertTrue(detector.bannedElementLabels.contains("pizza menu"))
    }

    @Test
    fun `banElementFromWrongScreen should not ban when warning is blank`() {
        detector.trackAction("""click "Pizza Menu" at [3]""")
        detector.banElementFromWrongScreen("")
        assertTrue(detector.bannedElementLabels.isEmpty())
    }

    @Test
    fun `banElementFromWrongScreen should not ban when no recent actions`() {
        detector.banElementFromWrongScreen("WRONG SCREEN: detected")
        assertTrue(detector.bannedElementLabels.isEmpty())
    }

    // -- generateReplanningHint -----------------------------------------------

    @Test
    fun `generateReplanningHint should return empty when below threshold`() {
        // navigationAttemptCount is 0 initially
        assertEquals("", detector.generateReplanningHint())
    }

    @Test
    fun `generateReplanningHint should return hint when at threshold`() {
        // Increment navigation attempts past REPLAN_THRESHOLD
        val navScreen = makeScreen(elements = listOf(makeElement(text = "Home")))
        repeat(AgentConfig.REPLAN_THRESHOLD + 1) {
            detector.updateNavigationPhase(navScreen)
        }
        val hint = detector.generateReplanningHint()
        assertTrue(hint.isNotBlank())
        assertTrue(hint.contains("STUCK"))
    }

    @Test
    fun `generateReplanningHint should include app profile info when available`() {
        detector.reset()  // resolves appNavProfile for Swiggy
        val navScreen = makeScreen(elements = listOf(makeElement(text = "Home")))
        repeat(AgentConfig.REPLAN_THRESHOLD + 1) {
            detector.updateNavigationPhase(navScreen)
        }
        val hint = detector.generateReplanningHint()
        assertTrue(hint.contains("Swiggy"))
    }

    // -- initializeSubGoals ---------------------------------------------------

    @Test
    fun `initializeSubGoals should create sub-goals`() {
        detector.initializeSubGoals()
        val tracker = detector.formatProgressTracker()
        assertTrue(tracker.contains("Progress Tracker"))
        assertTrue(tracker.contains("[ ]")) // pending goals
    }

    @Test
    fun `initializeSubGoals should only initialize once`() {
        detector.initializeSubGoals()
        val tracker1 = detector.formatProgressTracker()
        detector.initializeSubGoals() // second call should be no-op
        val tracker2 = detector.formatProgressTracker()
        assertEquals(tracker1, tracker2)
    }

    @Test
    fun `initializeSubGoals should include order ID when present`() {
        detector.initializeSubGoals()
        val tracker = detector.formatProgressTracker()
        assertTrue(tracker.contains("ORD-12345"))
    }

    // -- formatProgressTracker ------------------------------------------------

    @Test
    fun `formatProgressTracker should return empty string when no sub-goals`() {
        assertEquals("", detector.formatProgressTracker())
    }

    @Test
    fun `formatProgressTracker should show pending goals with bracket markers`() {
        detector.initializeSubGoals()
        val tracker = detector.formatProgressTracker()
        assertTrue(tracker.contains("[ ]"))
        assertTrue(tracker.contains("Progress Tracker"))
    }

    // -- updateSubGoalProgress ------------------------------------------------

    @Test
    fun `updateSubGoalProgress should mark app opened when target package matches`() {
        detector.initializeSubGoals()
        val screen = makeScreen(packageName = "in.swiggy.android")
        detector.updateSubGoalProgress(screen)
        val tracker = detector.formatProgressTracker()
        // First goal (Open app) should be done
        assertTrue(tracker.contains("[x]"))
    }

    @Test
    fun `updateSubGoalProgress should mark order goals when on order page`() {
        detector.initializeSubGoals()
        // First get to ON_ORDER_PAGE phase
        val orderScreen = makeScreen(
            elements = listOf(
                makeElement(text = "Order Details"),
                makeElement(text = "My Orders"),
            ),
        )
        detector.updateNavigationPhase(orderScreen)
        detector.updateSubGoalProgress(orderScreen)
        val tracker = detector.formatProgressTracker()
        // Multiple goals should be marked done
        val doneCount = tracker.split("[x]").size - 1
        assertTrue("Expected multiple done goals, got $doneCount", doneCount >= 2)
    }

    @Test
    fun `updateSubGoalProgress should mark chat goals when in chat`() {
        detector.initializeSubGoals()
        val chatScreen = makeChatScreen()
        detector.updateNavigationPhase(chatScreen)
        detector.updateSubGoalProgress(chatScreen)
        val tracker = detector.formatProgressTracker()
        // Many goals should be done when in chat
        val doneCount = tracker.split("[x]").size - 1
        assertTrue("Expected many done goals in chat, got $doneCount", doneCount >= 5)
    }

    @Test
    fun `updateSubGoalProgress should mark order found when orderId appears on screen`() {
        detector.initializeSubGoals()
        val screen = makeScreen(
            packageName = "in.swiggy.android",
            elements = listOf(
                makeElement(text = "Order ORD-12345"),
                makeElement(text = "Delivered"),
            ),
        )
        detector.updateSubGoalProgress(screen)
        val tracker = detector.formatProgressTracker()
        assertTrue(tracker.contains("[x]"))
    }

    // -- Helpers ---------------------------------------------------------------

    private fun makeScreen(
        packageName: String = "com.example.app",
        activityName: String? = "com.example.app.TestActivity",
        elements: List<UIElement> = emptyList(),
    ): ScreenState {
        return ScreenState(
            packageName = packageName,
            activityName = activityName,
            elements = elements,
            focusedElement = null,
            timestamp = System.currentTimeMillis(),
        )
    }

    private fun makeChatScreen(): ScreenState {
        return makeScreen(
            elements = listOf(
                makeElement(text = "Type a message", isEditable = true),
                makeElement(text = "Send", isClickable = true),
                makeElement(text = "Hi, how can I help you?"),
                makeElement(text = "Virtual Assistant"),
            ),
        )
    }

    private fun makeElement(
        text: String? = null,
        contentDescription: String? = null,
        isClickable: Boolean = false,
        isEditable: Boolean = false,
        isScrollable: Boolean = false,
    ): UIElement {
        return UIElement(
            id = null,
            className = "android.widget.TextView",
            text = text,
            contentDescription = contentDescription,
            isClickable = isClickable,
            isEditable = isEditable,
            isScrollable = isScrollable,
            isCheckable = false,
            isChecked = null,
            isFocused = false,
            isEnabled = true,
            bounds = Rect(),
            childCount = 0,
        )
    }
}
