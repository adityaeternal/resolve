package com.cssupport.companion

import android.util.Log

/**
 * Detects the current navigation phase of the agent based on screen content.
 *
 * Responsibilities:
 * - Determining which [NavigationPhase] the agent is in
 * - Tracking navigation attempts for re-planning
 * - Detecting screen changes, oscillation, stagnation
 * - Detecting login walls and wrong screens
 * - Managing sub-goal progress tracking (Manus's dynamic todo.md pattern)
 *
 * Extracted from AgentLoop to keep detection/heuristic logic separate
 * from orchestration.
 */
class PhaseDetector(private val caseContext: CaseContext) {

    private val tag = "PhaseDetector"

    // -- Phase state ---------------------------------------------------------------

    /** Current navigation phase, detected from screen content. */
    var currentPhase = NavigationPhase.NAVIGATING_TO_SUPPORT
        private set

    /** Count of consecutive iterations in NAVIGATING_TO_SUPPORT (for re-planning). */
    var navigationAttemptCount = 0
        private set

    // -- Screen tracking -----------------------------------------------------------

    /** The previous screen state for differential tracking. */
    var previousScreenState: ScreenState? = null
    var previousScreenFingerprint = ""

    /** Recent screen fingerprints for oscillation detection. */
    private val recentFingerprints = mutableListOf<String>()

    /** Count of consecutive screens with no meaningful change. */
    var stagnationCount = 0
        private set

    /** Count of total scroll actions, to limit aimless scrolling. */
    var totalScrollCount = 0

    /** Recent action descriptions for oscillation detection. */
    private val recentActionDescs = mutableListOf<String>()

    /** Labels of elements that have been proven to lead to wrong screens. */
    val bannedElementLabels = mutableSetOf<String>()

    // -- Sub-goal tracking ---------------------------------------------------------

    private val subGoals = mutableListOf<SubGoal>()
    private var subGoalsInitialized = false

    /** App-specific navigation profile, resolved once at startup. */
    var appNavProfile: AppNavProfile? = null
        private set

    /** Reset all state for a new run. */
    fun reset() {
        currentPhase = NavigationPhase.NAVIGATING_TO_SUPPORT
        navigationAttemptCount = 0
        previousScreenState = null
        previousScreenFingerprint = ""
        recentFingerprints.clear()
        stagnationCount = 0
        totalScrollCount = 0
        recentActionDescs.clear()
        bannedElementLabels.clear()
        subGoals.clear()
        subGoalsInitialized = false
        appNavProfile = AppNavigationKnowledge.lookup(caseContext.targetPlatform)
            ?: AppNavigationKnowledge.lookupByName(caseContext.targetPlatform)
    }

    // == Navigation phase detection ===============================================

    /**
     * Detect what navigation phase the agent is currently in.
     */
    fun updateNavigationPhase(screenState: ScreenState) {
        val previousPhase = currentPhase
        val allText = screenState.elements.mapNotNull {
            it.text?.lowercase() ?: it.contentDescription?.lowercase()
        }.joinToString(" ")
        val hasInputField = screenState.elements.any { it.isEditable }

        val chatIndicators = listOf(
            "send", "type a message", "type here", "write a message",
            "enter message", "message...", "type your message",
            "type your query", "type your issue", "write here",
            "ask a question", "enter your message", "compose",
        )
        val chatbotIndicators = listOf(
            "virtual assistant", "how can i help", "select an option",
            "choose from below", "hi! how can", "what can i help", "chat with us",
            "queries & feedback", "we're here to help",
            "chat with agent", "talk to agent", "live chat",
            "what do you need help with",
            "select a topic", "choose a topic", "pick a topic",
            "what went wrong", "what is the issue",
            "describe your issue", "raise a complaint",
            "how may i assist", "how can i assist",
            "please select", "select your issue", "select your concern",
            "i understand", "sorry to hear", "sorry for the inconvenience",
            "let me help", "happy to help",
            "please choose", "choose an option",
            "tap on an option", "click on an option",
        )
        val supportIndicators = listOf(
            "support", "contact", "faq", "get help", "contact us", "queries",
            "help center", "help centre", "customer care", "grievance",
        )
        val orderPageIndicators = listOf(
            "order details", "order summary", "order status", "order #",
            "order id", "help with this order", "support for this order", "report issue",
            "order history", "my orders", "your orders", "past orders",
        )

        val hasBottomInput = screenState.elements.any { el ->
            el.isEditable && el.bounds.centerY() > (screenState.elements.maxOfOrNull { it.bounds.bottom }
                ?: AgentConfig.DEFAULT_SCREEN_HEIGHT) * 3 / 4
        }

        currentPhase = when {
            hasInputField && chatIndicators.any { allText.contains(it) } -> NavigationPhase.IN_CHAT
            chatbotIndicators.any { allText.contains(it) } -> NavigationPhase.IN_CHAT
            hasBottomInput && screenState.elements.count { !it.text.isNullOrBlank() } > 5 -> NavigationPhase.IN_CHAT

            allText.contains("help") && supportIndicators.any { allText.contains(it) } ->
                NavigationPhase.ON_SUPPORT_PAGE

            orderPageIndicators.any { allText.contains(it) } -> NavigationPhase.ON_ORDER_PAGE

            else -> NavigationPhase.NAVIGATING_TO_SUPPORT
        }

        if (currentPhase != previousPhase) {
            DebugLogger.logPhaseChange(previousPhase.displayName, currentPhase.displayName)
        }

        if (currentPhase == NavigationPhase.NAVIGATING_TO_SUPPORT) {
            navigationAttemptCount++
        } else {
            navigationAttemptCount = 0
        }
    }

    // == Differential state detection =============================================

    /**
     * Compare current screen with previous screen and describe WHAT changed.
     */
    fun detectChanges(currentFingerprint: String, currentState: ScreenState): String {
        if (previousScreenFingerprint.isEmpty()) return "FIRST_SCREEN"
        if (currentFingerprint == previousScreenFingerprint) return "NO_CHANGE: Screen is identical to the previous turn."

        val prevState = previousScreenState ?: return "SCREEN_CHANGED"

        val prevPkg = prevState.packageName
        val curPkg = currentState.packageName
        val prevActivity = prevState.activityName?.substringAfterLast(".")
        val curActivity = currentState.activityName?.substringAfterLast(".")

        val sb = StringBuilder()

        if (prevPkg != curPkg) {
            sb.append("NEW APP: Changed from $prevPkg to $curPkg. ")
        } else if (prevActivity != curActivity && curActivity != null) {
            sb.append("NEW SCREEN: $curActivity (was: ${prevActivity ?: "unknown"}). ")
        } else {
            val newLabels = currentState.newElementLabels(prevState)
            val removedLabels = currentState.removedElementLabels(prevState)

            if (newLabels.isNotEmpty() || removedLabels.isNotEmpty()) {
                sb.append("CONTENT_UPDATED: ")
                if (newLabels.isNotEmpty()) {
                    sb.append("New elements: ${newLabels.take(5).joinToString(", ") { "\"$it\"" }}. ")
                }
                if (removedLabels.isNotEmpty()) {
                    sb.append("Removed: ${removedLabels.take(5).joinToString(", ") { "\"$it\"" }}. ")
                }
            } else {
                sb.append("SCREEN_CHANGED: Layout or positions shifted. ")
            }
        }

        return sb.toString().trim()
    }

    /**
     * Track screen fingerprint for oscillation detection.
     */
    fun trackFingerprint(fingerprint: String) {
        recentFingerprints.add(fingerprint)
        while (recentFingerprints.size > AgentConfig.FINGERPRINT_WINDOW) {
            recentFingerprints.removeAt(0)
        }
    }

    /**
     * Track an action description for pattern detection.
     */
    fun trackAction(actionSignature: String) {
        recentActionDescs.add(actionSignature)
        while (recentActionDescs.size > AgentConfig.RECENT_ACTIONS_WINDOW) {
            recentActionDescs.removeAt(0)
        }
    }

    /** Most recent action description, or empty string. */
    fun lastActionDesc(): String = recentActionDescs.lastOrNull() ?: ""

    /**
     * Update stagnation tracking based on the change description.
     * Returns true if a stagnation hint should be added.
     */
    fun updateStagnation(changeDescription: String): Boolean {
        if (changeDescription.startsWith("NO_CHANGE")) {
            stagnationCount++
            return stagnationCount >= AgentConfig.STAGNATION_THRESHOLD
        } else {
            stagnationCount = 0
            return false
        }
    }

    /**
     * Build a stagnation hint message.
     */
    fun buildStagnationHint(): String {
        return "SYSTEM: The screen has not changed for $stagnationCount turns. " +
            "Your recent actions had no visible effect. Possible reasons: " +
            "(1) You are clicking elements that are not actually interactive, " +
            "(2) A popup or overlay is blocking interaction, " +
            "(3) You need to scroll to find the right element. " +
            "Try: press_back to dismiss any overlay, or look for a different navigation path."
    }

    // == Oscillation detection ====================================================

    /**
     * Detect A->B->A->B oscillation pattern.
     */
    fun detectOscillation(): String {
        if (recentFingerprints.size < 4) return ""

        val last4 = recentFingerprints.takeLast(4)
        if (last4[0] == last4[2] && last4[1] == last4[3] && last4[0] != last4[1]) {
            return "WARNING: You are oscillating between two screens. Your last 4 actions brought you back and forth. STOP and try a completely different navigation path."
        }

        val last3 = recentFingerprints.takeLast(3)
        if (last3.distinct().size == 1) {
            return "WARNING: The screen has not changed after your last 3 actions. Your actions are not having any effect. Try a fundamentally different approach."
        }

        return ""
    }

    // == Login wall detection =====================================================

    /**
     * Detect if the app is showing a login/sign-in screen with no path to support.
     */
    fun detectLoginWall(screenState: ScreenState, iterationCount: Int): Boolean {
        if (currentPhase != NavigationPhase.NAVIGATING_TO_SUPPORT) return false
        if (iterationCount <= AgentConfig.LOGIN_WALL_MIN_ITERATIONS) return false

        val screenHeight = screenState.elements.maxOfOrNull { it.bounds.bottom } ?: AgentConfig.DEFAULT_SCREEN_HEIGHT
        val bottomBarThreshold = (screenHeight * AgentConfig.BOTTOM_BAR_FRACTION).toInt()

        val contentElements = screenState.elements.filter { it.bounds.centerY() < bottomBarThreshold }
        val contentText = contentElements.mapNotNull {
            (it.text ?: it.contentDescription)?.lowercase()
        }.joinToString(" ")

        val loginIndicators = listOf(
            "login", "sign in", "log in", "create account",
            "phone number", "enter your phone", "mobile number",
            "login/create account", "register", "sign up",
        )
        val loggedInIndicators = listOf(
            "my orders", "your orders", "order history",
            "past orders", "edit profile", "addresses",
            "saved cards", "payment", "wallet balance",
        )

        val loginHits = loginIndicators.count { contentText.contains(it) }
        val loggedInHits = loggedInIndicators.count { contentText.contains(it) }

        val hasLoginButton = contentElements.any { el ->
            el.isClickable && (
                el.text?.equals("LOGIN", ignoreCase = true) == true ||
                    el.text?.equals("Log in", ignoreCase = true) == true ||
                    el.text?.equals("Sign in", ignoreCase = true) == true
                )
        }

        return (loginHits >= AgentConfig.LOGIN_WALL_MIN_HITS || (loginHits >= 1 && hasLoginButton)) && loggedInHits == 0
    }

    // == Wrong screen detection ===================================================

    /**
     * Detect if the agent has strayed to a product/content page.
     */
    fun detectWrongScreen(screenState: ScreenState): String {
        if (currentPhase != NavigationPhase.NAVIGATING_TO_SUPPORT) return ""

        val allText = screenState.elements.mapNotNull {
            (it.text ?: it.contentDescription)?.lowercase()
        }
        val activityName = screenState.activityName?.lowercase() ?: ""

        if (activityName.contains("wallet") || activityName.contains("mywallet")) {
            return "\u26a0 WRONG SCREEN: This is the Wallet page, NOT navigation to support. " +
                "press_back immediately. Then find the RIGHTMOST unlabeled icon in the top bar (NOT the wallet/\u20b9 icon)."
        }

        val productKeywords = listOf(
            "add to cart", "add to bag", "buy now", "add item", "customize",
            "quantity", "qty",
            "toppings", "extra cheese", "crust",
            "veg", "non-veg", "bestseller", "popular",
            "ratings", "reviews", "stars",
        )
        val navKeywords = listOf(
            "account", "profile", "orders", "my orders", "order history",
            "help", "support", "contact", "settings",
        )

        val productHits = productKeywords.count { kw -> allText.any { it.contains(kw) } }
        val navHits = navKeywords.count { kw -> allText.any { it.contains(kw) } }

        if (productHits >= AgentConfig.WRONG_SCREEN_PRODUCT_THRESHOLD && navHits <= AgentConfig.WRONG_SCREEN_NAV_THRESHOLD) {
            val profile = appNavProfile
            val hint = if (profile != null) {
                "press_back and then: ${profile.supportPath.firstOrNull() ?: "find the sidebar/profile icon"}"
            } else {
                "press_back immediately and look for Profile/Account/More tab in the bottom bar."
            }
            return "\u26a0 WRONG SCREEN: This looks like a product/menu page (detected: price, add to cart, etc.). $hint"
        }

        if (productHits >= AgentConfig.WRONG_SCREEN_MODERATE_PRODUCT_THRESHOLD && navHits == 0 && navigationAttemptCount >= 3) {
            return "\u26a0 You may be on a product page. Consider pressing back and using the correct navigation path."
        }

        return ""
    }

    /**
     * Ban an element label that led to a wrong screen.
     */
    fun banElementFromWrongScreen(wrongScreenWarning: String) {
        if (wrongScreenWarning.isBlank()) return
        val lastAction = recentActionDescs.lastOrNull() ?: return
        val labelMatch = Regex("\"(.+?)\"").find(lastAction)
        if (labelMatch != null) {
            val badLabel = labelMatch.groupValues[1].lowercase()
            if (badLabel.isNotBlank()) {
                bannedElementLabels.add(badLabel)
                Log.w(tag, "Banned element label '$badLabel' -- led to wrong screen")
            }
        }
    }

    /**
     * Generate a re-planning directive when the agent has been stuck navigating too long.
     */
    fun generateReplanningHint(): String {
        if (navigationAttemptCount < AgentConfig.REPLAN_THRESHOLD) return ""

        val profile = appNavProfile
        return if (profile != null) {
            "\u26a0 STUCK: You've spent $navigationAttemptCount turns navigating without reaching support. " +
                "RESET your approach. For ${profile.appName}: ${profile.profileLocation}. " +
                "Try press_back to return to the home screen, then follow the app-specific steps."
        } else {
            "\u26a0 STUCK: You've spent $navigationAttemptCount turns navigating without progress. " +
                "RESET: press_back to the home screen, then look for Profile/Account/More in the BOTTOM navigation bar."
        }
    }

    // == Sub-goal tracking ========================================================

    /**
     * Initialize sub-goals based on the case context.
     */
    fun initializeSubGoals() {
        if (subGoalsInitialized) return
        subGoalsInitialized = true

        subGoals.addAll(
            listOf(
                SubGoal("Open ${caseContext.targetPlatform} app", SubGoalStatus.PENDING),
                SubGoal("Navigate to Account/Profile section", SubGoalStatus.PENDING),
                SubGoal("Find Orders or Order History", SubGoalStatus.PENDING),
                SubGoal(
                    if (!caseContext.orderId.isNullOrBlank())
                        "Locate order ${caseContext.orderId}"
                    else
                        "Find the relevant order",
                    SubGoalStatus.PENDING,
                ),
                SubGoal("Open Help/Support for that order", SubGoalStatus.PENDING),
                SubGoal("Reach support chat/chatbot interface", SubGoalStatus.PENDING),
                SubGoal("Navigate chatbot menus (click option buttons matching issue) and describe issue", SubGoalStatus.PENDING),
                SubGoal("Request ${caseContext.desiredOutcome} and wait for confirmation", SubGoalStatus.PENDING),
            ),
        )
    }

    /**
     * Update sub-goal completion based on navigation phase transitions and key events.
     */
    fun updateSubGoalProgress(screenState: ScreenState) {
        if (subGoals.isEmpty()) return

        val targetPkg = caseContext.targetPlatform.lowercase()
        val currentPkg = screenState.packageName.lowercase()
        if (currentPkg.isNotEmpty() && currentPkg != AgentConfig.OWN_PACKAGE
            && (currentPkg.contains(targetPkg) || targetPkg.contains(currentPkg))
        ) {
            markSubGoalDone(0)
        }

        val allText = screenState.elements.mapNotNull {
            it.text?.lowercase() ?: it.contentDescription?.lowercase()
        }.joinToString(" ")
        if (allText.contains("account") || allText.contains("profile") || allText.contains("my account")) {
            if (currentPhase != NavigationPhase.NAVIGATING_TO_SUPPORT || allText.contains("settings") || allText.contains("order")) {
                markSubGoalDone(1)
            }
        }

        if (currentPhase == NavigationPhase.ON_ORDER_PAGE || allText.contains("order history") || allText.contains("my orders") || allText.contains("your orders")) {
            markSubGoalDone(1)
            markSubGoalDone(2)
        }

        if (!caseContext.orderId.isNullOrBlank() && allText.contains(caseContext.orderId.lowercase())) {
            markSubGoalDone(2)
            markSubGoalDone(3)
        } else if (currentPhase == NavigationPhase.ON_ORDER_PAGE && (allText.contains("help") || allText.contains("support"))) {
            markSubGoalDone(3)
        }

        if (currentPhase == NavigationPhase.ON_SUPPORT_PAGE) {
            markSubGoalDone(0); markSubGoalDone(1); markSubGoalDone(2); markSubGoalDone(3)
            markSubGoalDone(4)
        }

        if (currentPhase == NavigationPhase.IN_CHAT) {
            markSubGoalDone(0); markSubGoalDone(1); markSubGoalDone(2); markSubGoalDone(3)
            markSubGoalDone(4); markSubGoalDone(5)
        }
    }

    private fun markSubGoalDone(index: Int) {
        if (index in subGoals.indices && subGoals[index].status != SubGoalStatus.DONE) {
            subGoals[index] = subGoals[index].copy(status = SubGoalStatus.DONE)
        }
    }

    /**
     * Format the progress tracker for inclusion in user messages.
     */
    fun formatProgressTracker(): String {
        if (subGoals.isEmpty()) return ""
        return buildString {
            appendLine("## Progress Tracker")
            for ((i, goal) in subGoals.withIndex()) {
                val marker = when (goal.status) {
                    SubGoalStatus.DONE -> "[x]"
                    SubGoalStatus.IN_PROGRESS -> "[~]"
                    SubGoalStatus.PENDING -> "[ ]"
                }
                appendLine("${i + 1}. $marker ${goal.description}")
            }
        }
    }
}
