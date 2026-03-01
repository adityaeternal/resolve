package com.cssupport.companion

/**
 * Manages the multi-turn conversation history sent to the LLM.
 *
 * Responsibilities:
 * - Maintaining the append-only conversation message list
 * - Estimating token usage
 * - Applying observation masking to older turns (JetBrains/Manus pattern)
 * - Recording complete turns (observation -> decision -> result)
 *
 * Extracted from AgentLoop to keep conversation management concerns
 * separate from orchestration logic.
 */
class ConversationManager {

    /** Full conversation history sent to the LLM for multi-turn context. */
    private val conversationHistory = mutableListOf<ConversationMessage>()

    /** Estimated token count of the conversation history. */
    private var estimatedTokens = 0

    /**
     * Index of the conversation turn boundary below which observations are masked.
     * Everything below this index has already been masked.
     */
    private var maskedUpToIndex = 0

    /** Reset all state for a new run. */
    fun reset() {
        conversationHistory.clear()
        estimatedTokens = 0
        maskedUpToIndex = 0
    }

    /** Returns a snapshot of the current conversation history. */
    fun historySnapshot(): List<ConversationMessage> = conversationHistory.toList()

    /** Current estimated token count. */
    fun estimatedTokenCount(): Int = estimatedTokens

    /**
     * Add a user observation message (e.g., a stagnation hint or wrong-app warning).
     */
    fun addObservation(content: String) {
        conversationHistory.add(ConversationMessage.UserObservation(content = content))
        estimatedTokens += content.length / AgentConfig.CHARS_PER_TOKEN
    }

    /**
     * Record a complete turn (observation -> decision -> result) in conversation history.
     */
    fun recordTurn(
        userMessage: String,
        decision: AgentDecision,
        result: String,
        toolNameFallback: (AgentAction) -> String,
        toolArgsFallback: (AgentAction) -> String,
    ) {
        // User observation.
        conversationHistory.add(ConversationMessage.UserObservation(content = userMessage))

        // Assistant's tool call.
        conversationHistory.add(
            ConversationMessage.AssistantToolCall(
                toolCallId = decision.toolCallId,
                toolName = decision.toolName.ifBlank { toolNameFallback(decision.action) },
                toolArguments = decision.toolArguments.let {
                    if (it == "{}") toolArgsFallback(decision.action) else it
                },
                reasoning = decision.reasoning,
            ),
        )

        // Tool result.
        conversationHistory.add(
            ConversationMessage.ToolResult(
                toolCallId = decision.toolCallId,
                result = result,
            ),
        )

        // Update estimated token count (rough: 1 token ~ CHARS_PER_TOKEN chars).
        val turnChars = userMessage.length + decision.reasoning.length + result.length + 200
        estimatedTokens += turnChars / AgentConfig.CHARS_PER_TOKEN
    }

    /**
     * Apply observation masking to older conversation turns.
     *
     * Strategy (from JetBrains Dec 2025 + Manus production):
     * - NEVER remove messages from history (append-only preserves KV-cache prefix)
     * - For turns older than the last [AgentConfig.OBSERVATION_MASK_KEEP_RECENT], replace
     *   the UserObservation content with a one-line summary
     * - Keep ALL AssistantToolCall and ToolResult messages intact
     *   (the LLM needs the full action trace)
     */
    fun applyObservationMasking(currentPhaseDisplayName: String) {
        // Count actual turns by AssistantToolCall (injected hints break the 3-per-turn assumption).
        val turnCount = conversationHistory.count { it is ConversationMessage.AssistantToolCall }
        if (turnCount <= AgentConfig.OBSERVATION_MASK_KEEP_RECENT) return

        // Find the index of the Nth-from-last AssistantToolCall to determine the mask boundary.
        var toolCallsSeen = 0
        var maskBoundary = conversationHistory.size
        for (i in conversationHistory.lastIndex downTo 0) {
            if (conversationHistory[i] is ConversationMessage.AssistantToolCall) {
                toolCallsSeen++
                if (toolCallsSeen >= AgentConfig.OBSERVATION_MASK_KEEP_RECENT) {
                    maskBoundary = i
                    break
                }
            }
        }

        for (i in maskedUpToIndex until maskBoundary.coerceAtMost(conversationHistory.size)) {
            val msg = conversationHistory[i]
            if (msg is ConversationMessage.UserObservation && !msg.content.startsWith("[Screen:")) {
                // Replace verbose observation with a compact summary.
                val summary = extractObservationSummary(msg.content, currentPhaseDisplayName)
                conversationHistory[i] = ConversationMessage.UserObservation(content = summary)
            }
        }

        maskedUpToIndex = maskBoundary

        // Re-estimate tokens after masking.
        estimatedTokens = conversationHistory.sumOf { msg ->
            when (msg) {
                is ConversationMessage.UserObservation -> msg.content.length
                is ConversationMessage.AssistantToolCall -> msg.reasoning.length + msg.toolArguments.length + 100
                is ConversationMessage.ToolResult -> msg.result.length + 50
            }
        } / AgentConfig.CHARS_PER_TOKEN
    }

    /**
     * Extract a compact summary from a verbose user observation message.
     */
    private fun extractObservationSummary(content: String, currentPhaseDisplayName: String): String {
        val packageLine = content.lineSequence().firstOrNull { it.startsWith("Package:") }?.trim()
            ?: ""
        val screenLine = content.lineSequence().firstOrNull { it.startsWith("Screen:") }?.trim()
            ?: ""
        val phaseLine = content.lineSequence().firstOrNull { it.startsWith("Phase:") }?.trim()
            ?: "Phase: $currentPhaseDisplayName"
        val turnLine = content.lineSequence().firstOrNull { it.startsWith("Turn:") }?.trim()
            ?: ""

        return "[Screen: ${packageLine.removePrefix("Package: ")}/${screenLine.removePrefix("Screen: ")}, $phaseLine, $turnLine]"
    }
}
