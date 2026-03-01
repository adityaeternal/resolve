package com.cssupport.companion

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * On-device agent loop that drives the observe-think-act cycle.
 *
 * ## Architecture (v3 -- Gold-Standard Agent Intelligence)
 *
 * Implements six research-backed improvements over the v2 agent:
 *
 * 1. **Numbered element references**: Every interactive element gets a [N] index.
 *    The LLM says `click_element(elementId=7)` instead of fuzzy text matching.
 *
 * 2. **Post-action verification**: After every action, capture the screen and verify
 *    the action actually worked.
 *
 * 3. **Sub-goal decomposition + progress tracking**: Break the task into numbered
 *    sub-goals and track completion.
 *
 * 4. **Observation masking**: Replace verbose screen dumps in older conversation turns
 *    with one-line summaries.
 *
 * 5. **Screen stabilization**: Poll-compare-repeat until screen stops changing.
 *
 * 6. **Differential state tracking**: Tell the LLM WHAT changed between screens.
 *
 * ## Delegation
 *
 * This class orchestrates the loop, delegating to:
 * - [ConversationManager] — conversation history, token tracking, observation masking
 * - [PhaseDetector] — navigation phase detection, screen change tracking, sub-goals
 * - [PromptBuilder] — system and user prompt construction
 * - [AgentConfig] — centralized constants (timeouts, limits, thresholds)
 */
class AgentLoop(
    private val engine: AccessibilityEngine,
    private val llmClient: LLMClient,
    private val caseContext: CaseContext,
    private val safetyPolicy: SafetyPolicy = SafetyPolicy(),
    private val onEvent: suspend (AgentEvent) -> Unit = {},
    private val launchTargetApp: (() -> Boolean)? = null,
) {

    private val tag = "AgentLoop"

    private val _state = MutableStateFlow(AgentLoopState.IDLE)
    val state = _state.asStateFlow()

    // -- Delegates ----------------------------------------------------------------

    private val conversation = ConversationManager()
    private val phaseDetector = PhaseDetector(caseContext)
    private val promptBuilder = PromptBuilder(caseContext)

    // -- State tracking -----------------------------------------------------------

    private var iterationCount = 0
    private var totalLoopIterations = 0
    private var consecutiveDuplicates = 0
    private var lastActionSignature = ""
    private var llmRetryCount = 0
    private var consecutiveNoToolCalls = 0
    private var wrongAppTurnCount = 0

    /** Post-action screen reused by the next iteration (pipelining optimization). */
    private var lastVerifiedScreen: ScreenState? = null

    @Volatile
    private var paused = false

    /** Context for debug logging -- set by CompanionAgentService before run(). */
    var debugContext: android.content.Context? = null

    /**
     * Run the main agent loop. Suspends until the loop completes (resolved/failed/cancelled).
     */
    suspend fun run(): AgentResult {
        _state.value = AgentLoopState.RUNNING
        resetState()

        debugContext?.let { DebugLogger.startNewRun(it, caseContext.targetPlatform) }
        DebugLogger.logEvent("Case: ${caseContext.caseId}, Issue: ${caseContext.issue}, Goal: ${caseContext.desiredOutcome}")

        emit(AgentEvent.Started(caseId = caseContext.caseId))
        AgentLogStore.log("Agent loop started for case ${caseContext.caseId}", LogCategory.STATUS_UPDATE, "Starting...")

        return try {
            val result = executeLoop()
            _state.value = AgentLoopState.COMPLETED
            result
        } catch (e: CancellationException) {
            _state.value = AgentLoopState.CANCELLED
            emit(AgentEvent.Cancelled)
            AgentLogStore.log("Agent loop cancelled", LogCategory.STATUS_UPDATE, "Cancelled")
            AgentResult.Cancelled
        } catch (e: Exception) {
            _state.value = AgentLoopState.FAILED
            val errorDetail = e.message?.take(120) ?: e.javaClass.simpleName
            val displayMessage = when {
                e.message?.contains("401") == true ->
                    "Your API key appears to be invalid. Check Settings."
                e.message?.contains("429") == true ->
                    "Too many requests. Try again in a moment."
                e is java.net.UnknownHostException || e is java.net.ConnectException ->
                    "No internet connection. Please check your network."
                e is java.net.SocketTimeoutException ->
                    "The request timed out. Try again."
                e.message?.contains("HTTP") == true ->
                    "Could not reach the AI service: $errorDetail"
                else ->
                    "Error: $errorDetail"
            }
            emit(AgentEvent.Failed(reason = displayMessage))
            AgentLogStore.log("Agent loop failed: ${e.javaClass.simpleName}: ${e.message}", LogCategory.TERMINAL_FAILED, displayMessage)
            Log.e(tag, "Agent loop failed: ${e.javaClass.simpleName}: ${e.message}", e)
            AgentResult.Failed(reason = displayMessage)
        }
    }

    fun pause() {
        paused = true
        _state.value = AgentLoopState.PAUSED
        AgentLogStore.log("Agent loop paused", LogCategory.STATUS_UPDATE, "Paused")
    }

    fun resume() {
        paused = false
        _state.value = AgentLoopState.RUNNING
        AgentLogStore.log("Agent loop resumed", LogCategory.STATUS_UPDATE, "Resuming...")
    }

    private fun resetState() {
        iterationCount = 0
        totalLoopIterations = 0
        consecutiveDuplicates = 0
        lastActionSignature = ""
        llmRetryCount = 0
        consecutiveNoToolCalls = 0
        wrongAppTurnCount = 0
        lastVerifiedScreen = null
        conversation.reset()
        phaseDetector.reset()
    }

    // == Main loop =================================================================

    private suspend fun executeLoop(): AgentResult {
        phaseDetector.initializeSubGoals()

        while (iterationCount < SafetyPolicy.MAX_ITERATIONS) {
            currentCoroutineContext().ensureActive()
            totalLoopIterations++

            if (totalLoopIterations > AgentConfig.MAX_TOTAL_LOOP_ITERATIONS) {
                return AgentResult.Failed(
                    reason = "Agent exceeded maximum total iterations (${AgentConfig.MAX_TOTAL_LOOP_ITERATIONS}). Possible loop detected.",
                )
            }

            // Handle pause state.
            while (paused) {
                currentCoroutineContext().ensureActive()
                delay(AgentConfig.PAUSE_POLL_INTERVAL_MS)
            }

            iterationCount++
            Log.d(tag, "--- Iteration $iterationCount ---")

            // Step 1: Observe -- capture stable screen state.
            val screenState = captureScreen()

            emit(AgentEvent.ScreenCaptured(
                packageName = screenState.packageName,
                elementCount = screenState.elements.size,
            ))

            // Skip if we're looking at our own app.
            if (screenState.packageName == AgentConfig.OWN_PACKAGE) {
                val ownAppResult = handleOwnAppVisible()
                if (ownAppResult != null) return ownAppResult
                continue
            }

            // Step 1b: App-scope restriction.
            val wrongAppResult = handleWrongApp(screenState)
            if (wrongAppResult == WrongAppOutcome.CONTINUE) continue
            if (wrongAppResult == WrongAppOutcome.RESET_COUNT) { /* proceed */ }

            // Step 2: Detect changes and update phase.
            val currentFingerprint = screenState.fingerprint()
            val changeDescription = phaseDetector.detectChanges(currentFingerprint, screenState)
            phaseDetector.updateNavigationPhase(screenState)
            phaseDetector.updateSubGoalProgress(screenState)
            phaseDetector.trackFingerprint(currentFingerprint)

            // Step 2a: Login wall detection.
            if (phaseDetector.detectLoginWall(screenState, iterationCount)) {
                AgentLogStore.log("App requires login", LogCategory.APPROVAL_NEEDED, "Please log in to the app first")
                return AgentResult.NeedsHumanReview(
                    reason = "The app requires you to log in before support can be accessed. Please log in, then try again.",
                    iterationsCompleted = iterationCount,
                )
            }

            // Step 2b: Stagnation check.
            if (phaseDetector.updateStagnation(changeDescription)) {
                Log.w(tag, "Screen has not changed for ${phaseDetector.stagnationCount} turns")
                conversation.addObservation(phaseDetector.buildStagnationHint())
            }

            // Step 2c: Oscillation check.
            val oscillationWarning = phaseDetector.detectOscillation()

            // Step 2d: Wrong screen detection.
            val wrongScreenWarning = phaseDetector.detectWrongScreen(screenState)

            // Step 2e: Ban elements that led to wrong screens.
            phaseDetector.banElementFromWrongScreen(wrongScreenWarning)

            // Step 3: Build prompts.
            val collapseContent = phaseDetector.currentPhase == NavigationPhase.NAVIGATING_TO_SUPPORT
            val formattedScreen = screenState.formatForLLM(
                previousScreen = phaseDetector.previousScreenState,
                collapseContent = collapseContent,
            )
            val userMessage = promptBuilder.buildUserMessage(
                formattedScreen = formattedScreen,
                changeDescription = changeDescription,
                oscillationWarning = oscillationWarning,
                wrongScreenWarning = wrongScreenWarning,
                screenState = screenState,
                currentPhase = phaseDetector.currentPhase,
                iterationCount = iterationCount,
                progressTracker = phaseDetector.formatProgressTracker(),
                bannedElementLabels = phaseDetector.bannedElementLabels,
                replanningHint = phaseDetector.generateReplanningHint(),
                totalScrollCount = phaseDetector.totalScrollCount,
                appNavProfile = phaseDetector.appNavProfile,
            )

            // Save state for next iteration's differential tracking.
            phaseDetector.previousScreenState = screenState
            phaseDetector.previousScreenFingerprint = currentFingerprint

            emit(AgentEvent.ThinkingStarted)
            AgentLogStore.log("[$iterationCount] Thinking...", LogCategory.STATUS_UPDATE, "Thinking...")

            // Step 3b: Apply observation masking.
            conversation.applyObservationMasking(phaseDetector.currentPhase.displayName)

            val decision: AgentDecision
            val systemPrompt = promptBuilder.buildSystemPrompt(
                currentPhase = phaseDetector.currentPhase,
                appNavProfile = phaseDetector.appNavProfile,
            )
            try {
                if (iterationCount == 1) DebugLogger.logSystemPrompt(systemPrompt)
                DebugLogger.logUserMessage(iterationCount, phaseDetector.currentPhase.displayName, userMessage)

                decision = llmClient.chatCompletion(
                    systemPrompt = systemPrompt,
                    userMessage = userMessage,
                    conversationMessages = conversation.historySnapshot(),
                )
                llmRetryCount = 0

                DebugLogger.logLLMResponse(decision.toolName, decision.toolArguments, decision.reasoning)
            } catch (e: Exception) {
                val retryResult = handleLLMError(e)
                if (retryResult != null) return retryResult
                continue
            }

            Log.d(tag, "Decision: action=${decision.action}, reasoning=${decision.reasoning.take(100)}")
            emit(AgentEvent.DecisionMade(
                action = describeAction(decision.action),
                reasoning = decision.reasoning,
            ))

            // Step 3c: Handle "no tool call" responses.
            if (handleNoToolCall(decision)) continue

            // Step 4: Validate against safety policy.
            val policyOutcome = handleSafetyPolicy(decision)
            if (policyOutcome is SafetyOutcome.Terminal) return policyOutcome.result
            if (policyOutcome is SafetyOutcome.Continue) continue

            // Step 4a-bis: Handle UpdatePlan.
            if (decision.action is AgentAction.UpdatePlan) {
                handlePlanUpdate(decision, userMessage)
                continue
            }

            // Step 4b: Detect repeated identical actions.
            val actionSignature = describeAction(decision.action)
            if (handleDuplicateAction(actionSignature, decision, userMessage)) continue

            // Step 4c: Track scroll actions.
            if (decision.action is AgentAction.ScrollDown || decision.action is AgentAction.ScrollUp) {
                phaseDetector.totalScrollCount++
            }

            // Step 4d: Track actions for pattern detection + enforce bans.
            phaseDetector.trackAction(actionSignature)

            // Check banned elements.
            if (handleBannedElement(decision, screenState, userMessage)) continue

            // Step 5: Execute the action.
            val preActionFingerprint = currentFingerprint
            logAction(decision.action, actionSignature)

            val actionResult: ActionResult
            var verificationResult: VerificationResult? = null
            try {
                actionResult = executeAction(decision.action, screenState)
                verificationResult = if (actionResult is ActionResult.Success) {
                    verifyAction(decision.action, preActionFingerprint, screenState)
                } else {
                    null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "[$iterationCount] Action/verify crashed: ${e.javaClass.simpleName}: ${e.message}", e)
                val errorMsg = "Action failed: ${e.message?.take(80) ?: e.javaClass.simpleName}"
                conversation.recordTurn(userMessage, decision, "FAILED: $errorMsg. Try a different approach.", ::toolNameFromAction, ::toolArgsFromAction)
                AgentLogStore.log("[$iterationCount] $errorMsg", LogCategory.ERROR, "Action error, retrying...")
                emit(AgentEvent.ActionExecuted(description = actionSignature, success = false))
                lastVerifiedScreen = null
                delay(SafetyPolicy.MIN_ACTION_DELAY_MS)
                continue
            }

            // Step 7: Record the turn.
            val resultDescription = buildResultDescription(actionResult, verificationResult)
            DebugLogger.logActionResult(actionSignature, resultDescription)
            conversation.recordTurn(userMessage, decision, resultDescription, ::toolNameFromAction, ::toolArgsFromAction)

            // Step 8: Handle action outcome.
            val terminalResult = handleActionResult(actionResult, actionSignature)
            if (terminalResult != null) return terminalResult

            delay(SafetyPolicy.MIN_ACTION_DELAY_MS)
        }

        return AgentResult.Failed(
            reason = "Reached maximum of $iterationCount iterations without resolving the issue",
        )
    }

    // == Screen capture ============================================================

    private suspend fun captureScreen(): ScreenState {
        return if (iterationCount == 1) {
            lastVerifiedScreen = null
            waitForMeaningfulScreen()
        } else if (lastVerifiedScreen != null) {
            val reused = lastVerifiedScreen
                ?: engine.waitForStableScreen(
                    maxWaitMs = AgentConfig.SCREEN_STABILIZE_MAX_WAIT_MS,
                    pollIntervalMs = AgentConfig.SCREEN_STABILIZE_POLL_INTERVAL_MS,
                )
            lastVerifiedScreen = null
            reused
        } else {
            engine.waitForStableScreen(
                maxWaitMs = AgentConfig.SCREEN_STABILIZE_MAX_WAIT_MS,
                pollIntervalMs = AgentConfig.SCREEN_STABILIZE_POLL_INTERVAL_MS,
            )
        }
    }

    /**
     * Wait for the first screen to have meaningful content before the agent starts acting.
     */
    private suspend fun waitForMeaningfulScreen(): ScreenState {
        val deadline = System.currentTimeMillis() + AgentConfig.INITIAL_LOAD_WAIT_MS
        var bestScreen = engine.captureScreenState()
        var bestInteractiveCount = bestScreen.elements.count { it.isClickable || it.isEditable }

        if (bestInteractiveCount >= AgentConfig.MIN_ELEMENTS_FOR_LOADED_SCREEN) {
            Log.d(tag, "First screen has $bestInteractiveCount interactive elements -- ready")
            return bestScreen
        }

        Log.d(tag, "First screen has only $bestInteractiveCount interactive elements -- waiting for content to load...")
        AgentLogStore.log("Waiting for app to load...", LogCategory.STATUS_UPDATE, "Loading...")

        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            delay(AgentConfig.INITIAL_LOAD_POLL_INTERVAL_MS)

            val screen = try {
                engine.captureScreenState()
            } catch (_: Exception) {
                continue
            }

            val interactiveCount = screen.elements.count { it.isClickable || it.isEditable }

            if (interactiveCount > bestInteractiveCount) {
                bestScreen = screen
                bestInteractiveCount = interactiveCount
            }

            if (interactiveCount >= AgentConfig.MIN_ELEMENTS_FOR_LOADED_SCREEN) {
                Log.d(tag, "Screen now has $interactiveCount interactive elements -- ready (waited ${AgentConfig.INITIAL_LOAD_WAIT_MS - (deadline - System.currentTimeMillis())}ms)")
                delay(AgentConfig.POST_LOAD_SETTLE_MS)
                return engine.captureScreenState()
            }
        }

        Log.w(tag, "Timed out waiting for meaningful screen (best: $bestInteractiveCount elements)")
        return bestScreen
    }

    // == Own-app handling ==========================================================

    private suspend fun handleOwnAppVisible(): AgentResult? {
        Log.d(tag, "Own app is in foreground, trying to launch target app...")
        iterationCount = maxOf(iterationCount - 1, 0)

        if (launchTargetApp != null) {
            launchTargetApp.invoke()
        }
        AgentLogStore.log("Opening target app", LogCategory.STATUS_UPDATE, "Opening app...")

        val waitStart = System.currentTimeMillis()
        var retryLaunchCount = 0
        while (engine.captureScreenState().packageName == AgentConfig.OWN_PACKAGE) {
            currentCoroutineContext().ensureActive()
            val elapsed = System.currentTimeMillis() - waitStart

            if (launchTargetApp != null && retryLaunchCount < AgentConfig.MAX_APP_LAUNCH_RETRIES && elapsed > (retryLaunchCount + 1) * AgentConfig.APP_LAUNCH_RETRY_INTERVAL_MS) {
                retryLaunchCount++
                Log.d(tag, "Retrying app launch (attempt $retryLaunchCount)")
                launchTargetApp.invoke()
            }

            if (elapsed > AgentConfig.MAX_FOREGROUND_WAIT_MS) {
                AgentLogStore.log("Could not open the app automatically", LogCategory.ERROR, "Please open the app manually and try again")
                return AgentResult.Failed(
                    reason = "Could not open the app. Please open it manually and try again.",
                )
            }
            delay(AgentConfig.OWN_APP_POLL_DELAY_MS)
        }
        AgentLogStore.log("App opened", LogCategory.STATUS_UPDATE, "Navigating...")
        return null // null means continue the loop
    }

    // == Wrong-app handling ========================================================

    private enum class WrongAppOutcome { CONTINUE, RESET_COUNT, NOT_WRONG }

    private suspend fun handleWrongApp(screenState: ScreenState): WrongAppOutcome {
        val targetPkg = caseContext.targetPlatform.lowercase()
        val currentPkg = screenState.packageName.lowercase()
        val isSystemDialog = AgentConfig.SYSTEM_DIALOG_PACKAGES.any { currentPkg.contains(it) }
        if (currentPkg.isNotEmpty()
            && currentPkg != AgentConfig.OWN_PACKAGE
            && !currentPkg.contains(targetPkg)
            && !targetPkg.contains(currentPkg)
            && !isSystemDialog
        ) {
            wrongAppTurnCount++
            Log.w(tag, "[$iterationCount] Wrong app detected: $currentPkg (target: $targetPkg), count=$wrongAppTurnCount")

            if (wrongAppTurnCount >= AgentConfig.MAX_WRONG_APP_TURNS) {
                AgentLogStore.log("[$iterationCount] Wrong app ($currentPkg), pressing back", LogCategory.AGENT_ACTION, "Returning to app...")
                try { engine.pressBack() } catch (_: Exception) {}
                delay(AgentConfig.WRONG_APP_BACK_DELAY_MS)
                val checkScreen = engine.captureScreenState()
                if (!checkScreen.packageName.lowercase().contains(targetPkg)) {
                    launchTargetApp?.invoke()
                    AgentLogStore.log("Re-launching target app", LogCategory.STATUS_UPDATE, "Opening app...")
                    delay(AgentConfig.WRONG_APP_RELAUNCH_DELAY_MS)
                }
                wrongAppTurnCount = 0
            } else {
                val hint = "\u26a0 WRONG APP: You are in $currentPkg, NOT the target app (${caseContext.targetPlatform}). " +
                    "press_back immediately to return to the correct app."
                conversation.addObservation(hint)
                try { engine.pressBack() } catch (_: Exception) {}
                delay(AgentConfig.WRONG_APP_BACK_DELAY_MS)
            }
            iterationCount = maxOf(iterationCount - 1, 0)
            return WrongAppOutcome.CONTINUE
        } else {
            wrongAppTurnCount = 0
            return WrongAppOutcome.NOT_WRONG
        }
    }

    // == LLM error handling ========================================================

    private suspend fun handleLLMError(e: Exception): AgentResult? {
        llmRetryCount++
        Log.e(tag, "LLM call failed (attempt $llmRetryCount): ${e.javaClass.simpleName}: ${e.message}", e)
        emit(AgentEvent.Error(message = "LLM error: ${e.javaClass.simpleName}: ${e.message}"))

        val errorClass = classifyLLMError(e)

        AgentLogStore.log(
            "[$iterationCount] LLM error: ${e.javaClass.simpleName}: ${e.message}",
            LogCategory.ERROR,
            errorClass.userMessage,
        )

        if (!errorClass.retryable || llmRetryCount >= AgentConfig.MAX_LLM_RETRIES) {
            return AgentResult.Failed(reason = errorClass.userMessage)
        }

        val exponential = AgentConfig.LLM_RETRY_BASE_DELAY_MS * (1L shl llmRetryCount.coerceAtMost(AgentConfig.LLM_RETRY_MAX_EXPONENT))
        val jitter = AgentConfig.LLM_RETRY_JITTER_MIN + Math.random() * AgentConfig.LLM_RETRY_JITTER_RANGE
        val backoffMs = (exponential * jitter).toLong().coerceAtMost(AgentConfig.LLM_RETRY_MAX_DELAY_MS)
        Log.d(tag, "Retrying in ${backoffMs}ms (attempt $llmRetryCount, jitter=${"%.2f".format(jitter)})")

        delay(backoffMs)
        return null // null means continue the loop (retry)
    }

    // == No-tool-call handling =====================================================

    private suspend fun handleNoToolCall(decision: AgentDecision): Boolean {
        val waitReason = (decision.action as? AgentAction.Wait)?.reason ?: ""
        val isNoToolCallWait = decision.action is AgentAction.Wait && (
            waitReason.contains("no tool call", ignoreCase = true) ||
                waitReason.contains("Failed to parse", ignoreCase = true) ||
                waitReason.contains("No response from LLM", ignoreCase = true) ||
                waitReason.contains("no tool use", ignoreCase = true)
            )
        if (isNoToolCallWait) {
            consecutiveNoToolCalls++
            if (consecutiveNoToolCalls in 2..AgentConfig.MAX_NO_TOOL_CALL_RETRIES) {
                Log.w(tag, "[$iterationCount] $consecutiveNoToolCalls consecutive no-tool-call responses, injecting force-action directive")
                val forceHint = "SYSTEM: You MUST call a tool NOW. Look at the screen and pick the most promising element. " +
                    "If you see >>> elements, click one. If stuck, use press_back. Do NOT skip your turn."
                conversation.addObservation(forceHint)
                iterationCount = maxOf(iterationCount - 1, 0)
                delay(AgentConfig.NO_TOOL_CALL_RETRY_DELAY_MS)
                return true // continue
            }
        } else {
            consecutiveNoToolCalls = 0
        }
        return false
    }

    // == Safety policy handling =====================================================

    private sealed class SafetyOutcome {
        data object Proceed : SafetyOutcome()
        data class Terminal(val result: AgentResult) : SafetyOutcome()
        data object Continue : SafetyOutcome()
    }

    private suspend fun handleSafetyPolicy(decision: AgentDecision): SafetyOutcome {
        val policyResult = safetyPolicy.validate(decision.action, iterationCount)
        return when (policyResult) {
            is PolicyResult.Allowed -> SafetyOutcome.Proceed
            is PolicyResult.NeedsApproval -> {
                emit(AgentEvent.ApprovalNeeded(reason = policyResult.reason))
                AgentLogStore.log("[$iterationCount] NEEDS APPROVAL: ${policyResult.reason}", LogCategory.APPROVAL_NEEDED, "Needs your approval")
                SafetyOutcome.Terminal(
                    AgentResult.NeedsHumanReview(
                        reason = policyResult.reason,
                        iterationsCompleted = iterationCount,
                    ),
                )
            }
            is PolicyResult.Blocked -> {
                emit(AgentEvent.ActionBlocked(reason = policyResult.reason))
                AgentLogStore.log("[$iterationCount] BLOCKED: ${policyResult.reason}", LogCategory.ERROR, "Action blocked: ${policyResult.reason}")
                if (iterationCount >= SafetyPolicy.MAX_ITERATIONS) {
                    SafetyOutcome.Terminal(
                        AgentResult.Failed(reason = "Maximum iterations reached without resolution"),
                    )
                } else {
                    delay(SafetyPolicy.MIN_ACTION_DELAY_MS)
                    SafetyOutcome.Continue
                }
            }
        }
    }

    // == Plan update handling =======================================================

    private suspend fun handlePlanUpdate(decision: AgentDecision, userMessage: String) {
        val plan = decision.action as AgentAction.UpdatePlan
        logAction(plan, describeAction(plan))
        conversation.recordTurn(
            userMessage, decision,
            "Plan recorded. Now execute the next step -- call an action tool.",
            ::toolNameFromAction, ::toolArgsFromAction,
        )
        iterationCount = maxOf(iterationCount - 1, 0)
        delay(AgentConfig.POST_PLAN_DELAY_MS)
    }

    // == Duplicate action handling ==================================================

    private suspend fun handleDuplicateAction(
        actionSignature: String,
        decision: AgentDecision,
        userMessage: String,
    ): Boolean {
        if (actionSignature == lastActionSignature) {
            consecutiveDuplicates++
            if (consecutiveDuplicates >= AgentConfig.MAX_CONSECUTIVE_DUPLICATES) {
                Log.w(tag, "Action repeated $consecutiveDuplicates times: $actionSignature")
                AgentLogStore.log("[$iterationCount] Skipping repeated action (tried $consecutiveDuplicates times)", LogCategory.DEBUG)
                consecutiveDuplicates = 0
                lastActionSignature = ""
                conversation.recordTurn(
                    userMessage, decision,
                    "FAILED: Action '$actionSignature' was repeated ${AgentConfig.MAX_CONSECUTIVE_DUPLICATES} times and skipped. You MUST try a different approach.",
                    ::toolNameFromAction, ::toolArgsFromAction,
                )
                delay(SafetyPolicy.MIN_ACTION_DELAY_MS)
                return true
            }
        } else {
            consecutiveDuplicates = 0
            lastActionSignature = actionSignature
        }
        return false
    }

    // == Banned element handling ====================================================

    private suspend fun handleBannedElement(
        decision: AgentDecision,
        screenState: ScreenState,
        userMessage: String,
    ): Boolean {
        val clickAction = decision.action as? AgentAction.ClickElement ?: return false
        val clickLabel = if (clickAction.elementId != null) {
            screenState.getElementById(clickAction.elementId)?.let { el ->
                (el.text ?: el.contentDescription)?.lowercase()
            }
        } else {
            clickAction.label?.lowercase()
        } ?: clickAction.label?.lowercase()

        val isBanned = clickLabel != null && phaseDetector.bannedElementLabels.any {
            clickLabel.contains(it) || it.contains(clickLabel)
        }
        if (isBanned) {
            Log.w(tag, "[$iterationCount] Skipping click on banned element '$clickLabel'")
            conversation.recordTurn(
                userMessage, decision,
                "BLOCKED: Element '$clickLabel' was already tried and led to a WRONG screen. " +
                    "This element is banned. Choose a DIFFERENT element.",
                ::toolNameFromAction, ::toolArgsFromAction,
            )
            AgentLogStore.log("[$iterationCount] Blocked banned element '$clickLabel'", LogCategory.DEBUG, "Trying different path...")
            delay(SafetyPolicy.MIN_ACTION_DELAY_MS)
            return true
        }
        return false
    }

    // == Action result handling =====================================================

    private suspend fun handleActionResult(actionResult: ActionResult, actionDescription: String): AgentResult? {
        return when (actionResult) {
            is ActionResult.Success -> {
                emit(AgentEvent.ActionExecuted(description = actionDescription, success = true))
                null
            }
            is ActionResult.Failed -> {
                emit(AgentEvent.ActionExecuted(description = actionDescription, success = false))
                AgentLogStore.log("[$iterationCount] Action failed: ${actionResult.reason}", LogCategory.ERROR, "Action failed")
                null
            }
            is ActionResult.Resolved -> {
                emit(AgentEvent.Resolved(summary = actionResult.summary))
                AgentLogStore.log("Case resolved: ${actionResult.summary}", LogCategory.TERMINAL_RESOLVED, "Issue resolved!")
                AgentResult.Resolved(
                    summary = actionResult.summary,
                    iterationsCompleted = iterationCount,
                )
            }
            is ActionResult.HumanReviewNeeded -> {
                emit(AgentEvent.HumanReviewRequested(
                    reason = actionResult.reason,
                    inputPrompt = actionResult.inputPrompt,
                ))
                AgentLogStore.log("[$iterationCount] Human review requested: ${actionResult.reason}", LogCategory.APPROVAL_NEEDED, "Needs your input")
                AgentResult.NeedsHumanReview(
                    reason = actionResult.reason,
                    iterationsCompleted = iterationCount,
                )
            }
        }
    }

    // == Post-action verification ==================================================

    private suspend fun verifyAction(
        action: AgentAction,
        preActionFingerprint: String,
        preActionState: ScreenState,
    ): VerificationResult {
        delay(AgentConfig.POST_ACTION_VERIFY_DELAY_MS)
        val postState = try {
            engine.captureScreenState()
        } catch (e: Exception) {
            Log.w(tag, "verifyAction: screen capture failed: ${e.message}")
            lastVerifiedScreen = null
            return VerificationResult.Success("Action executed (could not verify screen state).")
        }
        lastVerifiedScreen = postState
        val postFingerprint = postState.fingerprint()

        return when (action) {
            is AgentAction.ClickElement -> {
                if (postFingerprint != preActionFingerprint) {
                    val newActivity = postState.activityName?.substringAfterLast(".")
                    val prevActivity = preActionState.activityName?.substringAfterLast(".")
                    if (postState.packageName != preActionState.packageName) {
                        VerificationResult.Success("Screen changed to different app: ${postState.packageName}")
                    } else if (newActivity != prevActivity && newActivity != null) {
                        VerificationResult.Success("Screen changed to: $newActivity")
                    } else {
                        val newElements = postState.newElementLabels(preActionState)
                        if (newElements.isNotEmpty()) {
                            VerificationResult.Success("Screen updated. New elements: ${newElements.take(3).joinToString(", ") { "\"$it\"" }}")
                        } else {
                            VerificationResult.Success("Screen content changed.")
                        }
                    }
                } else {
                    VerificationResult.Warning("Click executed but screen did not change. The element may not be interactive or a popup may need dismissal.")
                }
            }

            is AgentAction.TypeMessage -> {
                val fieldText = engine.readFirstFieldText()
                if (fieldText != null && fieldText.contains(action.text.take(20))) {
                    VerificationResult.Success("Text entered successfully.")
                } else if (fieldText.isNullOrBlank()) {
                    if (postFingerprint != preActionFingerprint) {
                        VerificationResult.Success("Message sent (field cleared, screen updated).")
                    } else {
                        VerificationResult.Warning("Text was typed but field appears empty and screen unchanged. The message may not have been sent.")
                    }
                } else {
                    VerificationResult.Warning("Text typed but field content does not match expected text. Field contains: \"${fieldText.take(50)}\"")
                }
            }

            is AgentAction.ScrollDown, is AgentAction.ScrollUp -> {
                if (postFingerprint != preActionFingerprint) {
                    VerificationResult.Success("Content scrolled successfully.")
                } else {
                    VerificationResult.Warning("Scroll executed but content did not change. You may have reached the end of the scrollable area.")
                }
            }

            is AgentAction.PressBack -> {
                if (postFingerprint != preActionFingerprint) {
                    val newActivity = postState.activityName?.substringAfterLast(".")
                    VerificationResult.Success("Back pressed. Now on: ${newActivity ?: postState.packageName}")
                } else {
                    VerificationResult.Warning("Back pressed but screen did not change. The back action may have been consumed by a non-visible component.")
                }
            }

            is AgentAction.Wait,
            is AgentAction.UploadFile,
            is AgentAction.RequestHumanReview,
            is AgentAction.MarkResolved,
            is AgentAction.UpdatePlan -> null
        } ?: VerificationResult.Success("Action completed.")
    }

    private fun buildResultDescription(
        actionResult: ActionResult,
        verification: VerificationResult?,
    ): String {
        return when (actionResult) {
            is ActionResult.Success -> {
                when (verification) {
                    is VerificationResult.Success -> "OK: ${verification.observation}"
                    is VerificationResult.Warning -> "WARNING: ${verification.observation}"
                    null -> "Success."
                }
            }
            is ActionResult.Failed -> "FAILED: ${actionResult.reason}. Try a different approach."
            is ActionResult.Resolved -> "RESOLVED: ${actionResult.summary}"
            is ActionResult.HumanReviewNeeded -> "Pausing for human input: ${actionResult.reason}"
        }
    }

    // == Action execution ==========================================================

    private suspend fun executeAction(action: AgentAction, currentScreenState: ScreenState): ActionResult {
        return when (action) {
            is AgentAction.TypeMessage -> {
                val success = if (action.elementId != null) {
                    engine.setTextByIndex(action.elementId, action.text, currentScreenState)
                } else {
                    val fields = engine.findInputFields()
                    if (fields.isEmpty()) {
                        return ActionResult.Failed("No input field found on screen")
                    }
                    try {
                        engine.setText(fields.first(), action.text)
                    } finally {
                        @Suppress("DEPRECATION")
                        fields.forEach { try { it.recycle() } catch (_: Exception) {} }
                    }
                }

                if (!success) {
                    return ActionResult.Failed("Could not set text in input field")
                }

                delay(AgentConfig.POST_ACTION_VERIFY_DELAY_MS)
                val sent = trySendMessage()
                if (!sent) {
                    Log.w(tag, "trySendMessage: send button not found, text was typed but may not be sent")
                }
                ActionResult.Success
            }

            is AgentAction.ClickElement -> {
                val success = if (action.elementId != null) {
                    engine.clickByIndex(action.elementId, currentScreenState)
                } else if (!action.label.isNullOrBlank()) {
                    engine.clickByText(action.label)
                } else {
                    false
                }

                if (success) {
                    ActionResult.Success
                } else {
                    val fallbackLabel = action.label ?: action.expectedOutcome
                    if (fallbackLabel.isNotBlank()) {
                        val fallback = tryClickByContentDescription(fallbackLabel)
                        if (fallback) ActionResult.Success
                        else ActionResult.Failed("Could not find or click element${if (action.elementId != null) " [${action.elementId}]" else ""}: '${action.label ?: action.expectedOutcome}'")
                    } else {
                        ActionResult.Failed("Could not click element: no elementId or label provided")
                    }
                }
            }

            is AgentAction.ScrollDown -> {
                val success = engine.scrollScreenForward()
                if (success) ActionResult.Success
                else ActionResult.Failed("No scrollable container found")
            }

            is AgentAction.ScrollUp -> {
                val success = engine.scrollScreenBackward()
                if (success) ActionResult.Success
                else ActionResult.Failed("No scrollable container found")
            }

            is AgentAction.Wait -> {
                engine.waitForContentChange(timeoutMs = AgentConfig.WAIT_FOR_CONTENT_CHANGE_TIMEOUT_MS)
                ActionResult.Success
            }

            is AgentAction.UploadFile -> {
                val clicked = tryClickUploadButton()
                if (clicked) ActionResult.Success
                else ActionResult.Failed("Could not find upload/attachment button")
            }

            is AgentAction.PressBack -> {
                val success = engine.pressBack()
                if (success) ActionResult.Success
                else ActionResult.Failed("Could not press back")
            }

            is AgentAction.RequestHumanReview -> {
                ActionResult.HumanReviewNeeded(
                    reason = action.reason,
                    inputPrompt = action.inputPrompt,
                )
            }

            is AgentAction.MarkResolved -> {
                ActionResult.Resolved(summary = action.summary)
            }

            is AgentAction.UpdatePlan -> {
                ActionResult.Success
            }
        }
    }

    // == UI interaction helpers =====================================================

    private fun tryClickByContentDescription(description: String): Boolean {
        val root = try {
            val service = SupportAccessibilityService.instance ?: return false
            service.rootInActiveWindow ?: return false
        } catch (e: Exception) {
            return false
        }

        return try {
            val found = root.findAccessibilityNodeInfosByText(description)
            if (found.isNullOrEmpty()) return false

            val target = found.firstOrNull { it.isClickable }
                ?: found.firstOrNull { it.contentDescription?.toString()?.contains(description, ignoreCase = true) == true }
                ?: found.firstOrNull()

            if (target != null) {
                val result = engine.clickNode(target)
                @Suppress("DEPRECATION")
                found.forEach { try { it.recycle() } catch (_: Exception) {} }
                result
            } else {
                @Suppress("DEPRECATION")
                found.forEach { try { it.recycle() } catch (_: Exception) {} }
                false
            }
        } finally {
            @Suppress("DEPRECATION")
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    private fun trySendMessage(): Boolean {
        // Strategy 1: Try IME Enter action on the focused input field.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val inputFields = engine.findInputFields()
            if (inputFields.isNotEmpty()) {
                val focused = inputFields.firstOrNull { it.isFocused } ?: inputFields.first()
                @Suppress("NewApi")
                val imeResult = focused.performAction(0x01000000) // ACTION_IME_ENTER
                if (imeResult) {
                    Log.d(tag, "trySendMessage: IME_ENTER succeeded")
                    @Suppress("DEPRECATION")
                    inputFields.forEach { try { it.recycle() } catch (_: Exception) {} }
                    return true
                }
                @Suppress("DEPRECATION")
                inputFields.forEach { try { it.recycle() } catch (_: Exception) {} }
            }
        }

        // Strategy 2: Find a clickable icon button near the input field.
        try {
            val root = SupportAccessibilityService.instance?.rootInActiveWindow
            if (root != null) {
                val candidates = mutableListOf<AccessibilityNodeInfo>()
                findSendButtonCandidates(root, candidates)
                val best = candidates.maxByOrNull { node ->
                    val bounds = android.graphics.Rect()
                    node.getBoundsInScreen(bounds)
                    bounds.centerX() + bounds.centerY()
                }
                if (best != null) {
                    val result = engine.clickNode(best)
                    @Suppress("DEPRECATION")
                    candidates.forEach { try { it.recycle() } catch (_: Exception) {} }
                    try { root.recycle() } catch (_: Exception) {}
                    if (result) {
                        Log.d(tag, "trySendMessage: icon button click succeeded")
                        return true
                    }
                } else {
                    @Suppress("DEPRECATION")
                    candidates.forEach { try { it.recycle() } catch (_: Exception) {} }
                    try { root.recycle() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "trySendMessage: icon button search failed: ${e.message}")
        }

        // Strategy 3: Search by text/contentDescription with a broad set of labels.
        val sendLabels = listOf(
            "Send", "Send message", "Submit", "send", "SEND",
            "Send a message", "Go", "Post", "Reply", "Done",
        )
        for (label in sendLabels) {
            val nodes = engine.findNodesByText(label)
            val exact = nodes.firstOrNull { n ->
                n.isClickable && (
                    n.text?.toString().equals(label, ignoreCase = true) == true ||
                        n.contentDescription?.toString().equals(label, ignoreCase = true) == true
                    ) && n.text?.toString()?.contains("Feedback", ignoreCase = true) != true
                    && n.text?.toString()?.startsWith("Re", ignoreCase = true) != true
            }
            if (exact != null) {
                val result = engine.clickNode(exact)
                @Suppress("DEPRECATION")
                nodes.forEach { try { it.recycle() } catch (_: Exception) {} }
                if (result) return true
            } else {
                @Suppress("DEPRECATION")
                nodes.forEach { try { it.recycle() } catch (_: Exception) {} }
            }
        }

        // Strategy 4: Try by resource ID.
        val sendIds = listOf(
            "send_button", "btn_send", "send", "submit",
            "send_btn", "send_btn_img", "iv_send", "chat_send",
            "btn_chat_send", "sendButton", "fab_send",
            "com.android.mms:id/send_button_sms",
        )
        for (id in sendIds) {
            val node = engine.findNodeById(id)
            if (node != null) {
                val result = engine.clickNode(node)
                @Suppress("DEPRECATION")
                try { node.recycle() } catch (_: Exception) {}
                if (result) return true
            }
        }

        return false
    }

    private fun findSendButtonCandidates(
        node: AccessibilityNodeInfo,
        output: MutableList<AccessibilityNodeInfo>,
        depth: Int = 0,
    ) {
        if (depth > AgentConfig.MAX_NODE_TRAVERSAL_DEPTH) return
        val className = node.className?.toString()?.lowercase() ?: ""
        val isImageButton = className.contains("imagebutton") || className.contains("imageview")
        val isClickable = node.isClickable && node.isEnabled

        if (isClickable && isImageButton) {
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            val width = bounds.width()
            val height = bounds.height()
            if (width in AgentConfig.SEND_BUTTON_MIN_SIZE..AgentConfig.SEND_BUTTON_MAX_SIZE
                && height in AgentConfig.SEND_BUTTON_MIN_SIZE..AgentConfig.SEND_BUTTON_MAX_SIZE
                && bounds.centerY() > AgentConfig.SEND_BUTTON_MIN_Y
                && bounds.centerX() > AgentConfig.SEND_BUTTON_MIN_X
            ) {
                val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                val text = node.text?.toString()?.lowercase() ?: ""
                val sendHints = listOf("send", "submit", "go", "post", "reply", "enter")
                val antiHints = listOf("attach", "photo", "camera", "mic", "voice", "emoji", "sticker")
                val hasSendHint = sendHints.any { desc.contains(it) || text.contains(it) }
                val hasAntiHint = antiHints.any { desc.contains(it) || text.contains(it) }
                if (!hasAntiHint && (hasSendHint || (desc.isEmpty() && text.isEmpty()))) {
                    output.add(AccessibilityNodeInfo.obtain(node))
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            try {
                findSendButtonCandidates(child, output, depth + 1)
            } finally {
                @Suppress("DEPRECATION")
                try { child.recycle() } catch (_: Exception) {}
            }
        }
    }

    private fun tryClickUploadButton(): Boolean {
        val labels = listOf(
            "Attach", "attach", "Upload", "upload",
            "Add file", "add file", "Choose file",
            "Photo", "Image", "File",
        )
        for (label in labels) {
            val node = engine.findNodeByText(label)
            if (node != null) {
                val result = engine.clickNode(node)
                @Suppress("DEPRECATION")
                try { node.recycle() } catch (_: Exception) {}
                if (result) return true
            }
        }
        return false
    }

    // == Helpers ====================================================================

    internal fun toolNameFromAction(action: AgentAction): String = when (action) {
        is AgentAction.TypeMessage -> "type_message"
        is AgentAction.ClickElement -> "click_element"
        is AgentAction.ScrollDown -> "scroll_down"
        is AgentAction.ScrollUp -> "scroll_up"
        is AgentAction.Wait -> "wait_for_response"
        is AgentAction.UploadFile -> "upload_file"
        is AgentAction.PressBack -> "press_back"
        is AgentAction.RequestHumanReview -> "request_human_review"
        is AgentAction.MarkResolved -> "mark_resolved"
        is AgentAction.UpdatePlan -> "update_plan"
    }

    internal fun toolArgsFromAction(action: AgentAction): String = when (action) {
        is AgentAction.TypeMessage -> {
            val escapedText = action.text.replace("\"", "\\\"")
            if (action.elementId != null) {
                """{"text":"$escapedText","elementId":${action.elementId}}"""
            } else {
                """{"text":"$escapedText"}"""
            }
        }
        is AgentAction.ClickElement -> {
            val parts = mutableListOf<String>()
            if (action.elementId != null) parts.add("\"elementId\":${action.elementId}")
            if (!action.label.isNullOrBlank()) parts.add("\"label\":\"${action.label.replace("\"", "\\\"")}\"")
            parts.add("\"expectedOutcome\":\"${action.expectedOutcome.replace("\"", "\\\"")}\"")
            "{${parts.joinToString(",")}}"
        }
        is AgentAction.ScrollDown -> """{"reason":"${action.reason.replace("\"", "\\\"")}"}"""
        is AgentAction.ScrollUp -> """{"reason":"${action.reason.replace("\"", "\\\"")}"}"""
        is AgentAction.Wait -> """{"reason":"${action.reason.replace("\"", "\\\"")}"}"""
        is AgentAction.UploadFile -> """{"fileDescription":"${action.fileDescription.replace("\"", "\\\"")}"}"""
        is AgentAction.PressBack -> """{"reason":"${action.reason.replace("\"", "\\\"")}"}"""
        is AgentAction.RequestHumanReview -> """{"reason":"${action.reason.replace("\"", "\\\"")}"}"""
        is AgentAction.MarkResolved -> """{"summary":"${action.summary.replace("\"", "\\\"")}"}"""
        is AgentAction.UpdatePlan -> {
            val stepsJson = action.steps.joinToString(",") { s ->
                """{"step":"${s.step.replace("\"", "\\\"")}","status":"${s.status}"}"""
            }
            """{"explanation":"${action.explanation.replace("\"", "\\\"")}","steps":[$stepsJson]}"""
        }
    }

    private fun logAction(action: AgentAction, description: String) {
        when (action) {
            is AgentAction.TypeMessage -> {
                val preview = action.text.take(200)
                AgentLogStore.log(description, LogCategory.AGENT_MESSAGE, preview)
            }
            is AgentAction.ClickElement -> {
                val target = if (action.elementId != null) "[${action.elementId}]" else action.label ?: "unknown"
                AgentLogStore.log(description, LogCategory.AGENT_ACTION, "Tapping $target")
            }
            is AgentAction.ScrollDown -> {
                AgentLogStore.log(description, LogCategory.AGENT_ACTION, "Scrolling down")
            }
            is AgentAction.ScrollUp -> {
                AgentLogStore.log(description, LogCategory.AGENT_ACTION, "Scrolling up")
            }
            is AgentAction.Wait -> {
                AgentLogStore.log(description, LogCategory.STATUS_UPDATE, "Waiting for response...")
            }
            is AgentAction.UploadFile -> {
                AgentLogStore.log(description, LogCategory.AGENT_ACTION, "Uploading file")
            }
            is AgentAction.PressBack -> {
                AgentLogStore.log(description, LogCategory.AGENT_ACTION, "Going back")
            }
            is AgentAction.RequestHumanReview -> {
                AgentLogStore.log(description, LogCategory.STATUS_UPDATE, "Needs your input: ${action.reason}")
            }
            is AgentAction.MarkResolved -> {
                AgentLogStore.log(description, LogCategory.STATUS_UPDATE, "Issue resolved!")
            }
            is AgentAction.UpdatePlan -> {
                val stepsSummary = action.steps.joinToString(", ") { s ->
                    val icon = when (s.status) {
                        "completed" -> "[x]"
                        "in_progress" -> "[~]"
                        else -> "[ ]"
                    }
                    "$icon ${s.step.take(30)}"
                }
                AgentLogStore.log(description, LogCategory.STATUS_UPDATE, "Plan: $stepsSummary")
            }
        }
    }

    internal fun describeAction(action: AgentAction): String {
        return when (action) {
            is AgentAction.TypeMessage -> "Type message: \"${action.text.take(60)}${if (action.text.length > 60) "..." else ""}\""
            is AgentAction.ClickElement -> {
                val target = when {
                    action.elementId != null && !action.label.isNullOrBlank() -> "[${action.elementId}] \"${action.label}\""
                    action.elementId != null -> "[${action.elementId}]"
                    !action.label.isNullOrBlank() -> "\"${action.label}\""
                    else -> "unknown"
                }
                "Click: $target"
            }
            is AgentAction.ScrollDown -> "Scroll down: ${action.reason}"
            is AgentAction.ScrollUp -> "Scroll up: ${action.reason}"
            is AgentAction.Wait -> "Wait: ${action.reason}"
            is AgentAction.UploadFile -> "Upload file: ${action.fileDescription}"
            is AgentAction.PressBack -> "Press back: ${action.reason}"
            is AgentAction.RequestHumanReview -> "Request human review: ${action.reason}"
            is AgentAction.MarkResolved -> "Mark resolved: ${action.summary.take(80)}"
            is AgentAction.UpdatePlan -> "Plan: ${action.explanation.take(60)}${if (action.explanation.length > 60) "..." else ""} (${action.steps.size} steps)"
        }
    }

    // == LLM error classification ==================================================

    internal fun classifyLLMError(e: Exception): LLMErrorClass {
        val msg = e.message ?: ""

        return when {
            msg.contains("401") -> LLMErrorClass(
                retryable = false,
                userMessage = "API key invalid or expired. Check Settings.",
            )
            msg.contains("403") -> LLMErrorClass(
                retryable = false,
                userMessage = "Access denied -- check your API key permissions.",
            )
            msg.contains("404") -> LLMErrorClass(
                retryable = false,
                userMessage = "Model not found -- check Settings.",
            )
            msg.contains("quota", ignoreCase = true) || msg.contains("billing", ignoreCase = true) -> LLMErrorClass(
                retryable = false,
                userMessage = "API quota exceeded. Check your billing.",
            )
            msg.contains("429") -> {
                val retryAfter = Regex("""try again in (\d+)""", RegexOption.IGNORE_CASE)
                    .find(msg)?.groupValues?.get(1)?.toLongOrNull()
                LLMErrorClass(
                    retryable = true,
                    userMessage = "Rate limited, retrying${if (retryAfter != null) " in ${retryAfter}s" else ""}...",
                )
            }
            msg.contains("500") || msg.contains("502") || msg.contains("503") || msg.contains("504") -> LLMErrorClass(
                retryable = true,
                userMessage = "AI service temporarily unavailable, retrying...",
            )
            e is java.net.SocketTimeoutException -> LLMErrorClass(
                retryable = true,
                userMessage = "Request timed out, retrying...",
            )
            e is java.net.UnknownHostException -> LLMErrorClass(
                retryable = true,
                userMessage = "No internet connection. Retrying...",
            )
            e is java.net.ConnectException -> LLMErrorClass(
                retryable = true,
                userMessage = "Cannot reach AI service. Retrying...",
            )
            else -> LLMErrorClass(
                retryable = true,
                userMessage = "AI error: ${msg.take(80).ifBlank { e.javaClass.simpleName }}",
            )
        }
    }

    private suspend fun emit(event: AgentEvent) {
        try {
            onEvent(event)
        } catch (e: Exception) {
            Log.w(tag, "Failed to emit event: $event", e)
        }
    }
}

/** Classification of an LLM error for retry decisions. */
internal data class LLMErrorClass(
    val retryable: Boolean,
    val userMessage: String,
)

// == Sub-goal tracking types ======================================================

enum class SubGoalStatus {
    PENDING,
    IN_PROGRESS,
    DONE,
}

data class SubGoal(
    val description: String,
    val status: SubGoalStatus,
)

// == Verification result ==========================================================

sealed class VerificationResult {
    data class Success(val observation: String) : VerificationResult()
    data class Warning(val observation: String) : VerificationResult()
}

// == Navigation phases ============================================================

enum class NavigationPhase(val displayName: String) {
    NAVIGATING_TO_SUPPORT("Navigating to support"),
    ON_ORDER_PAGE("Viewing orders"),
    ON_SUPPORT_PAGE("On support page"),
    IN_CHAT("In support chat"),
}

// == Supporting types =============================================================

data class CaseContext(
    val caseId: String,
    val customerName: String,
    val issue: String,
    val desiredOutcome: String,
    val orderId: String?,
    val hasAttachments: Boolean,
    val targetPlatform: String,
)

sealed class AgentResult {
    data class Resolved(val summary: String, val iterationsCompleted: Int) : AgentResult()
    data class Failed(val reason: String) : AgentResult()
    data class NeedsHumanReview(val reason: String, val iterationsCompleted: Int) : AgentResult()
    data object Cancelled : AgentResult()
}

sealed class ActionResult {
    data object Success : ActionResult()
    data class Failed(val reason: String) : ActionResult()
    data class Resolved(val summary: String) : ActionResult()
    data class HumanReviewNeeded(val reason: String, val inputPrompt: String?) : ActionResult()
}

enum class AgentLoopState {
    IDLE,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

sealed class AgentEvent {
    data class Started(val caseId: String) : AgentEvent()
    data class ScreenCaptured(val packageName: String, val elementCount: Int) : AgentEvent()
    data object ThinkingStarted : AgentEvent()
    data class DecisionMade(val action: String, val reasoning: String) : AgentEvent()
    data class ApprovalNeeded(val reason: String) : AgentEvent()
    data class ActionBlocked(val reason: String) : AgentEvent()
    data class ActionExecuted(val description: String, val success: Boolean) : AgentEvent()
    data class HumanReviewRequested(val reason: String, val inputPrompt: String?) : AgentEvent()
    data class Resolved(val summary: String) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
    data class Failed(val reason: String) : AgentEvent()
    data object Cancelled : AgentEvent()
}
