package com.cssupport.companion

/**
 * Analyzes [ScreenState] content for pattern detection, fingerprinting,
 * and differential state tracking.
 *
 * Extracted from the monolithic AccessibilityEngine.kt to isolate the
 * screen analysis logic from capture, indexing, and formatting.
 */
object StateAnalyzer {

    /**
     * Detect common screen patterns from the element composition.
     * Gives the LLM a fast high-level understanding of what kind of screen it is looking at.
     */
    fun detectScreenPattern(elements: List<UIElement>): String {
        val allText = elements.mapNotNull { it.text?.lowercase() ?: it.contentDescription?.lowercase() }
        val hasInputField = elements.any { it.isEditable }
        val clickables = elements.filter { it.isClickable }

        // Chat/messaging screen.
        if (hasInputField && allText.any { it.contains("send") || it.contains("type") || it.contains("message") }) {
            return "Chat/messaging interface"
        }

        // Order list.
        val orderKeywords = listOf("order", "orders", "#", "delivered", "cancelled", "in progress", "track")
        if (orderKeywords.count { kw -> allText.any { it.contains(kw) } } >= 2) {
            return "Order list/history"
        }

        // Support/help page.
        val helpKeywords = listOf("help", "support", "contact", "faq", "chat with us", "get help", "report")
        if (helpKeywords.count { kw -> allText.any { it.contains(kw) } } >= 2) {
            return "Help/support page"
        }

        // Profile/account page.
        val profileKeywords = listOf("profile", "account", "settings", "sign out", "log out", "edit profile", "my account")
        if (profileKeywords.count { kw -> allText.any { it.contains(kw) } } >= 2) {
            return "Profile/account page"
        }

        // Home/feed.
        if (clickables.size > 15 && !hasInputField) {
            return "Home/feed (many items)"
        }

        // Bottom navigation present.
        val screenHeight = elements.maxOfOrNull { it.bounds.bottom } ?: 2400
        val bottomNav = elements.filter {
            it.isClickable && it.bounds.centerY() > screenHeight * 7 / 8
        }
        if (bottomNav.size >= 3) {
            return "Screen with bottom navigation (${bottomNav.size} tabs)"
        }

        return ""
    }

    /**
     * Generate a compact fingerprint of the screen state for change detection.
     * Two screen states with the same fingerprint are considered identical.
     */
    fun fingerprint(screen: ScreenState): String {
        val sig = buildString {
            append(screen.packageName)
            append("|")
            append(screen.activityName ?: "")
            append("|")
            val elemSig = screen.elements
                .filter { it.text?.isNotBlank() == true || it.contentDescription?.isNotBlank() == true }
                .take(20)
                .joinToString(",") { el ->
                    val label = (el.text ?: el.contentDescription ?: "").take(20)
                    "$label@${el.bounds.centerX() / 50}"
                }
            append(elemSig)
        }
        return sig.hashCode().toString(16)
    }

    /**
     * Get a concise summary of a screen state for observation masking.
     * Used to replace verbose screen dumps in older conversation turns.
     */
    fun toMaskedSummary(screen: ScreenState, phase: String): String {
        val simpleName = screen.activityName?.substringAfterLast(".") ?: "unknown"
        val interactiveCount = screen.elements.count { it.isClickable || it.isEditable }
        return "[Screen: ${screen.packageName}/$simpleName, ${screen.elements.size} elements ($interactiveCount interactive), phase: $phase]"
    }

    /**
     * Get the labels of all elements that are present in [current] but not in [other].
     * Used for differential state tracking.
     */
    fun newElementLabels(current: ScreenState, other: ScreenState?): List<String> {
        if (other == null) return emptyList()
        val otherLabels = other.elements.mapNotNull { el ->
            (el.text ?: el.contentDescription)?.take(40)
        }.toSet()
        return current.elements.mapNotNull { el ->
            val label = (el.text ?: el.contentDescription)?.take(40) ?: return@mapNotNull null
            if (label !in otherLabels) label else null
        }.take(10)
    }

    /**
     * Get the labels of elements present in [other] but missing from [current].
     */
    fun removedElementLabels(current: ScreenState, other: ScreenState?): List<String> {
        if (other == null) return emptyList()
        val currentLabels = current.elements.mapNotNull { el ->
            (el.text ?: el.contentDescription)?.take(40)
        }.toSet()
        return other.elements.mapNotNull { el ->
            val label = (el.text ?: el.contentDescription)?.take(40) ?: return@mapNotNull null
            if (label !in currentLabels) label else null
        }.take(10)
    }

    /**
     * Get a list of all element labels on a screen, for comparison purposes.
     */
    fun elementLabels(screen: ScreenState): Set<String> {
        return screen.elements.mapNotNull { el ->
            (el.text ?: el.contentDescription)?.take(40)
        }.toSet()
    }
}
