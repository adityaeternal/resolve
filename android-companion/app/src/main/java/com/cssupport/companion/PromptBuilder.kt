package com.cssupport.companion

/**
 * Builds system and user prompts for the LLM.
 *
 * Responsibilities:
 * - Building the system prompt (identity, case context, phase-specific instructions)
 * - Building the user message (screen state, change description, warnings, guidance)
 *
 * Extracted from AgentLoop to keep prompt engineering concerns separate
 * from orchestration logic.
 */
class PromptBuilder(private val caseContext: CaseContext) {

    /**
     * Build the system prompt with phase-specific instructions.
     */
    fun buildSystemPrompt(
        currentPhase: NavigationPhase,
        appNavProfile: AppNavProfile?,
    ): String {
        return buildString {
            // -- Core identity (always) --
            appendLine("You are Resolve, an AI agent on a real Android phone. You navigate apps to reach customer support and resolve issues.")
            appendLine("You ARE the customer. Speak in first person. Never reveal you are AI.")
            appendLine()

            // -- Case context (always) --
            appendLine("## Issue")
            if (caseContext.customerName.isNotBlank() && caseContext.customerName != "Customer") {
                appendLine("Customer: ${caseContext.customerName}")
            }
            appendLine(caseContext.issue)
            appendLine("Goal: ${caseContext.desiredOutcome}")
            if (!caseContext.orderId.isNullOrBlank()) appendLine("Order: ${caseContext.orderId}")
            if (caseContext.hasAttachments) appendLine("Evidence available to upload.")
            appendLine()

            // -- Screen reading rules (always, compact) --
            appendLine("## Screen Format")
            appendLine(">>> = navigation elements (tabs, profile, help). Click these.")
            appendLine("~ = products/content. IGNORE these during navigation.")
            appendLine("[N] = element ID. Use click_element(elementId=N).")
            appendLine("\"unlabeled\" icons: position hints (far-left/center/far-right) help identify them.")
            appendLine()

            // -- Phase-specific instructions --
            when (currentPhase) {
                NavigationPhase.NAVIGATING_TO_SUPPORT -> {
                    appendLine("## Navigation (your current task)")
                    appendLine("1. BOTTOM BAR: Click Account/Profile/More/Me tab")
                    appendLine("2. TOP BAR: Click person/avatar icon (top-right)")
                    appendLine("3. Account page \u2192 Orders / Order History")
                    appendLine("4. Select the order${if (!caseContext.orderId.isNullOrBlank()) " (${caseContext.orderId})" else ""}")
                    appendLine("5. Click Help/Support/Report Issue")
                    appendLine("6. Click Chat/Live Chat or select issue category")
                    appendLine()
                    appendLine("ONLY click >>> elements. NEVER click products/food/deals.")
                    appendLine("\u20b9/wallet/cash icons are NOT navigation. Ignore them.")
                    appendLine()

                    val profile = appNavProfile
                    if (profile != null) {
                        append(profile.toPromptBlock())
                        appendLine()
                    }
                }

                NavigationPhase.ON_ORDER_PAGE -> {
                    appendLine("## On Orders Page (your current task)")
                    appendLine("You've reached the orders section. Now:")
                    appendLine("1. Find the right order${if (!caseContext.orderId.isNullOrBlank()) " (${caseContext.orderId})" else ""}")
                    appendLine("2. Tap on it or click Help/Support/Report Issue for that order")
                    appendLine("3. Then click Chat/Live Chat or select the issue category")
                    appendLine()
                    appendLine("Look for: order cards, 'Help' buttons, 'Report Issue', 'Support' links.")
                    appendLine("If you don't see the right order, scroll_down to find it.")
                    appendLine()
                }

                NavigationPhase.ON_SUPPORT_PAGE -> {
                    appendLine("## On Support/Help Page")
                    appendLine("Find: Chat, Live Chat, Talk to Agent, or an issue category that matches.")
                    appendLine("Click the option closest to your issue. If no match, scroll down.")
                    appendLine()
                }

                NavigationPhase.IN_CHAT -> {
                    appendLine("## In Support Chat (your current task)")
                    appendLine("You ARE the customer. Be polite but firm.")
                    appendLine()
                    appendLine("PRIORITY ORDER for each screen:")
                    appendLine("1. CLICK option buttons if they match your issue (refund, missing item, wrong order, etc.)")
                    appendLine("2. CLICK \"Talk to agent\" / \"Chat with human\" if chatbot loops or can't help")
                    appendLine("3. TYPE only when there's an input field AND no matching option buttons")
                    appendLine("4. After EVERY click or send: call wait_for_response to see the response")
                    appendLine()
                    appendLine("Chatbot interaction tips:")
                    appendLine("- Read what the bot is asking, then pick the option that best answers its question")
                    appendLine("- If asked to select an issue: pick the category closest to your problem")
                    appendLine("- If asked yes/no questions: click the appropriate answer")
                    appendLine("- Never send a long message when buttons are available \u2014 bots ignore free text")
                    appendLine()
                    appendLine("When you need to TYPE (no buttons available):")
                    appendLine("\"Hi, I have an issue with my order${if (!caseContext.orderId.isNullOrBlank()) " #${caseContext.orderId}" else ""}. ${caseContext.issue.take(80)}. I'd like a ${caseContext.desiredOutcome.lowercase()}.\"")
                    appendLine()
                    appendLine("mark_resolved: ONLY when support confirms resolution (refund approved, ticket given).")
                    appendLine("request_human_review: If asked for OTP, card digits, CAPTCHA, or info you don't have.")
                    appendLine()
                }
            }

            // -- Rules (always, compact) --
            appendLine("## Rules")
            appendLine("- Dismiss popups: press_back or click X/Close")
            appendLine("- NEVER type in search bars")
            appendLine("- NEVER share SSN, credit card, passwords")
            appendLine("- Stuck 3+ turns? press_back, try different path")
            appendLine("- App requires login? request_human_review immediately")
            appendLine("- ${SafetyPolicy.MAX_ITERATIONS} actions max. Be efficient.")
            appendLine("- WARNING in tool results = your action failed. Try different element.")
        }
    }

    /**
     * Build the user message with screen state and contextual guidance.
     */
    fun buildUserMessage(
        formattedScreen: String,
        changeDescription: String,
        oscillationWarning: String,
        wrongScreenWarning: String,
        screenState: ScreenState?,
        currentPhase: NavigationPhase,
        iterationCount: Int,
        progressTracker: String,
        bannedElementLabels: Set<String>,
        replanningHint: String,
        totalScrollCount: Int,
        appNavProfile: AppNavProfile?,
    ): String {
        return buildString {
            // Target app context.
            appendLine("Target app: ${caseContext.targetPlatform}")
            appendLine("Phase: ${currentPhase.displayName}")
            appendLine("Turn: $iterationCount / ${SafetyPolicy.MAX_ITERATIONS}")
            appendLine()

            // Progress tracker (Manus's dynamic todo.md pattern).
            if (progressTracker.isNotBlank()) {
                append(progressTracker)
                appendLine()
            }

            // Screen change feedback (enhanced differential state).
            when {
                changeDescription == "FIRST_SCREEN" -> appendLine("(First screen observed)")
                changeDescription.startsWith("NO_CHANGE") -> appendLine("WARNING: $changeDescription")
                else -> appendLine("Change: $changeDescription")
            }

            // Oscillation warning.
            if (oscillationWarning.isNotBlank()) {
                appendLine()
                appendLine(oscillationWarning)
            }

            // Wrong screen detection.
            if (wrongScreenWarning.isNotBlank()) {
                appendLine()
                appendLine(wrongScreenWarning)
            }

            // Banned elements list.
            if (bannedElementLabels.isNotEmpty()) {
                appendLine()
                appendLine("BANNED ELEMENTS (led to wrong screens, do NOT click): ${bannedElementLabels.joinToString(", ") { "\"$it\"" }}")
            }

            // Re-planning after stuck navigation.
            if (replanningHint.isNotBlank()) {
                appendLine()
                appendLine(replanningHint)
            }

            // Scroll budget warning.
            if (totalScrollCount >= AgentConfig.MAX_SCROLL_ACTIONS) {
                appendLine()
                appendLine("WARNING: You have used $totalScrollCount scroll actions. Excessive scrolling suggests you are on the wrong screen. Navigate to Profile/Account instead.")
            }

            appendLine()
            appendLine("## Screen State")
            append(formattedScreen)
            appendLine()

            // Phase-specific guidance with app-specific hints.
            when (currentPhase) {
                NavigationPhase.NAVIGATING_TO_SUPPORT -> {
                    val profile = appNavProfile
                    if (profile != null) {
                        appendLine(">>> FOLLOW THESE STEPS:")
                        profile.supportPath.forEachIndexed { i, step ->
                            appendLine("  ${i + 1}. $step")
                        }
                    } else {
                        appendLine(">>> NEXT: Click Profile/Account/More tab in the BOTTOM bar. Do NOT click products.")
                    }
                }
                NavigationPhase.ON_ORDER_PAGE -> {
                    appendLine(">>> NEXT: Find the specific order and tap Help/Support on it.")
                }
                NavigationPhase.ON_SUPPORT_PAGE -> {
                    appendLine(">>> NEXT: Find Chat/Live Chat or select the issue category.")
                }
                NavigationPhase.IN_CHAT -> {
                    appendLine("GOAL: You are in the support chat.")
                    if (screenState != null) {
                        appendChatGuidance(screenState)
                    }
                }
            }

            appendLine()
            appendLine("Choose exactly one tool. Use elementId=[N] for clicks. Think step by step about the shortest path to your goal.")
        }
    }

    /**
     * Append context-specific chat guidance based on what's on screen.
     */
    private fun StringBuilder.appendChatGuidance(screenState: ScreenState) {
        val hasInput = screenState.elements.any { it.isEditable }
        val sHeight = screenState.elements.maxOfOrNull { it.bounds.bottom } ?: AgentConfig.DEFAULT_SCREEN_HEIGHT
        val topThreshold = (sHeight * AgentConfig.TOP_AREA_FRACTION).toInt()
        val bottomThreshold = (sHeight * AgentConfig.BOTTOM_BAR_FRACTION).toInt()

        val indexedElements = screenState.elementIndex
        val contentButtonsWithIds = indexedElements.filter { (_, el) ->
            el.isClickable
                && ((el.text?.length ?: 0) > 2 || (el.contentDescription?.length ?: 0) > 2)
                && el.bounds.centerY() in topThreshold..bottomThreshold
        }
        val hasOptionButtons = contentButtonsWithIds.size >= AgentConfig.MIN_CONTENT_BUTTONS_FOR_OPTIONS
        if (hasOptionButtons) {
            appendLine(">>> CHATBOT OPTIONS DETECTED. Click the best match:")
            contentButtonsWithIds.entries
                .filter { (_, el) -> (el.text ?: el.contentDescription)?.length in AgentConfig.MIN_CHAT_BUTTON_LABEL_LENGTH..AgentConfig.MAX_CHAT_BUTTON_LABEL_LENGTH }
                .take(AgentConfig.MAX_CHAT_OPTIONS_SHOWN)
                .forEach { (id, el) ->
                    val label = el.text ?: el.contentDescription ?: ""
                    appendLine("  - [${id}] \"$label\"")
                }
            appendLine(">>> CLICK an option. Do NOT type when buttons are available.")
        }
        if (hasInput && !hasOptionButtons) {
            appendLine(">>> Text input available, no option buttons. Type your message, then wait_for_response.")
        } else if (hasInput && hasOptionButtons) {
            appendLine(">>> Text input also available. Use ONLY if no button matches your need.")
        }
        if (!hasInput && !hasOptionButtons) {
            appendLine(">>> Waiting for chatbot. Try scroll_down or wait_for_response.")
        }
    }
}
