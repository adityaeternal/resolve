package com.cssupport.companion

import android.graphics.Rect

/**
 * Formats [ScreenState] content for the LLM with navigation-first presentation.
 *
 * Extracted from the monolithic AccessibilityEngine.kt to isolate the LLM
 * presentation logic from screen capture, indexing, and analysis.
 *
 * ## Key design principles (research-backed):
 * 1. **Navigation elements shown FIRST** with `>>>` markers (primacy effect)
 * 2. **Product/content elements lose [N] indices** when navigating
 * 3. **`isNavigationElement()`** classifier separates nav from noise
 * 4. **Phase-aware**: During NAVIGATING_TO_SUPPORT, products are collapsed.
 *    During IN_CHAT, all elements are shown.
 */
object ScreenParser {

    /**
     * Format a [ScreenState] for the LLM with navigation-first presentation.
     *
     * @param screen the screen state to format
     * @param previousScreen optional previous screen for marking new elements
     * @param collapseContent true to hide product/content [N] indices (navigation phases)
     */
    fun formatForLLM(
        screen: ScreenState,
        previousScreen: ScreenState? = null,
        collapseContent: Boolean = false,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("Package: ${screen.packageName}")
        if (screen.activityName != null) {
            val simpleName = screen.activityName.substringAfterLast(".")
            if (simpleName.isNotBlank()) {
                sb.appendLine("Screen: $simpleName")
            }
        }

        val screenWidth = screen.elements.maxOfOrNull { it.bounds.right } ?: 1080
        val screenHeight = screen.elements.maxOfOrNull { it.bounds.bottom } ?: 2400

        val indexedElements = screen.elementIndex
        if (indexedElements.isEmpty()) {
            sb.appendLine("(empty screen)")
            return sb.toString()
        }

        val prevLabels = previousScreen?.elements?.mapNotNull { el ->
            (el.text ?: el.contentDescription)?.take(40)
        }?.toSet()

        // Partition into zones.
        val topBarThreshold = screenHeight / 8
        val bottomBarThreshold = screenHeight * 7 / 8

        val topBar = indexedElements.filter { (_, el) ->
            el.bounds.centerY() < topBarThreshold
        }.toSortedMap()
        val content = indexedElements.filter { (_, el) ->
            el.bounds.centerY() in topBarThreshold..bottomBarThreshold
        }.toSortedMap()
        val bottomBar = indexedElements.filter { (_, el) ->
            el.bounds.centerY() > bottomBarThreshold
        }.toSortedMap()

        val screenPattern = StateAnalyzer.detectScreenPattern(indexedElements.values.toList())
        if (screenPattern.isNotBlank()) {
            sb.appendLine("Layout: $screenPattern")
        }
        sb.appendLine()

        // === NAVIGATION FIRST (bottom bar tabs) ===
        if (bottomBar.isNotEmpty()) {
            sb.appendLine("[NAVIGATION -- click these to find support]")
            for ((id, el) in bottomBar) {
                val label = el.text ?: el.contentDescription
                if (label.isNullOrBlank() && !el.isClickable) continue
                val line = formatSingleElement(id, el, screenWidth, prevLabels)
                sb.appendLine("  >>> $line")
            }
            sb.appendLine()
        }

        // === TOP BAR ===
        if (topBar.isNotEmpty()) {
            sb.appendLine("[TOP BAR]")
            for ((id, el) in topBar) {
                val label = el.text ?: el.contentDescription
                if (label.isNullOrBlank() && !el.isClickable) continue
                val isNav = isNavigationElement(el)
                val prefix = if (isNav) ">>>" else "   "
                val line = formatSingleElement(id, el, screenWidth, prevLabels)
                sb.appendLine("  $prefix $line")
            }
            sb.appendLine()
        }

        // === CONTENT ===
        if (content.isNotEmpty()) {
            val navContent = content.filter { (_, el) -> isNavigationElement(el) }
            val inputContent = content.filter { (_, el) -> el.isEditable }
            val otherContent = content.filter { (_, el) ->
                !isNavigationElement(el) && !el.isEditable
            }

            if (navContent.isNotEmpty() || inputContent.isNotEmpty()) {
                sb.appendLine("[CONTENT]")
                for ((id, el) in navContent) {
                    val line = formatSingleElement(id, el, screenWidth, prevLabels)
                    sb.appendLine("  >>> $line")
                }
                for ((id, el) in inputContent) {
                    val line = formatSingleElement(id, el, screenWidth, prevLabels)
                    sb.appendLine("  >>> $line")
                }
                sb.appendLine()
            }

            if (otherContent.isNotEmpty()) {
                if (collapseContent) {
                    val labels = otherContent.values
                        .mapNotNull { it.text ?: it.contentDescription }
                        .filter { it.length in 2..60 }
                        .take(8)
                    sb.appendLine("[${otherContent.size} other items -- NOT paths to support]")
                    if (labels.isNotEmpty()) {
                        sb.appendLine("  ~ ${labels.joinToString(", ") { "\"$it\"" }}")
                    }
                } else {
                    if (navContent.isEmpty() && inputContent.isEmpty()) {
                        sb.appendLine("[CONTENT]")
                    }
                    var itemCount = 0
                    for ((id, el) in otherContent) {
                        itemCount++
                        if (itemCount > 30) {
                            sb.appendLine("  (${otherContent.size - 30} more items...)")
                            break
                        }
                        val line = formatSingleElement(id, el, screenWidth, prevLabels)
                        sb.appendLine("  $line")
                    }
                }
                sb.appendLine()
            }

            // Scroll hint.
            val scrollables = content.values.filter { it.isScrollable }
            if (scrollables.isNotEmpty()) {
                sb.appendLine("  (scrollable)")
            }
        }

        if (screen.focusedElement != null) {
            val label = screen.focusedElement.text
                ?: screen.focusedElement.contentDescription
                ?: screen.focusedElement.className
            sb.appendLine("Focused: $label")
        }

        return sb.toString()
    }

    /**
     * Classify whether an element is a navigation element (leads to account/orders/help)
     * vs. a content/product element (food items, deals, listings).
     */
    fun isNavigationElement(el: UIElement): Boolean {
        val label = (el.text ?: el.contentDescription ?: "").lowercase()
        val className = el.className.lowercase()

        // Tabs are almost always navigation.
        if (className.contains("tab")) return true

        // Exact-match keywords.
        val exactNavKeywords = setOf("me", "more", "home")
        if (exactNavKeywords.contains(label.trim())) return true

        // Substring-match keywords.
        val navKeywords = listOf(
            "account", "profile", "settings", "my account",
            "orders", "order history", "my orders", "your orders", "past orders",
            "order details", "track order",
            "help", "support", "contact", "get help", "report", "issue",
            "contact us", "contact store", "queries",
            "chat", "live chat", "talk to us", "message us", "chat with",
            "back", "close", "cancel", "navigate up", "skip",
            "sign in", "log in", "sign out", "log out", "login",
            "refund", "return", "complaint", "grievance",
            "main menu", "sidebar", "hamburger",
            "missing item", "wrong item", "late delivery", "not delivered",
            "order issue", "food quality", "damaged", "wrong order",
            "talk to agent", "chat with human", "speak to",
            "need help", "get refund", "cancel order",
            "raise a complaint", "escalate", "customer care",
        )
        if (navKeywords.any { label.contains(it) }) return true

        // Icon buttons in bars are often navigation.
        if ((label == "unlabeled" || label.isBlank())
            && (className.contains("imagebutton") || className.contains("imageview"))
            && el.isClickable
        ) return true

        // Resource ID hints.
        val resId = (el.id ?: "").lowercase()
        val navResIds = listOf(
            "account", "profile", "orders", "help", "support", "more",
            "bottom_bar", "tab_", "navigation", "menu", "nav_",
        )
        if (resId.isNotBlank() && navResIds.any { resId.contains(it) }) return true

        return false
    }

    /**
     * Format a single element with its numeric ID, type hint, label, position, and state.
     */
    fun formatSingleElement(
        id: Int,
        el: UIElement,
        screenWidth: Int,
        prevLabels: Set<String>?,
    ): String {
        val sb = StringBuilder()
        sb.append("[$id] ")

        // Type hint.
        val typeHint = when {
            el.isEditable -> "INPUT"
            el.isScrollable -> "scrollable"
            el.isClickable -> buttonTypeHint(el)
            else -> "text"
        }
        sb.append("$typeHint: ")

        // Label.
        val label = el.text ?: el.contentDescription ?: "unlabeled"
        val isUnlabeled = label == "unlabeled"
        val truncated = if (label.length > 120) label.take(117) + "..." else label
        sb.append("\"$truncated\"")

        // Position hint.
        sb.append(positionHint(el.bounds, screenWidth, isUnlabeled))

        // State annotations.
        if (el.isCheckable && el.isChecked == true) sb.append(" [CHECKED]")
        if (el.isCheckable && el.isChecked == false) sb.append(" [UNCHECKED]")
        if (el.isClickable && !el.isEnabled) sb.append(" [DISABLED]")

        // Selected state for tabs.
        val className = el.className.lowercase()
        if (className.contains("tab") && el.isChecked == true) {
            sb.append(" [SELECTED]")
        }

        // NEW marker.
        if (prevLabels != null) {
            val elLabel = (el.text ?: el.contentDescription)?.take(40)
            if (elLabel != null && elLabel !in prevLabels) {
                sb.append(" <-- NEW")
            }
        }

        return sb.toString()
    }

    /**
     * Generate a concise position hint (e.g., "(left)", "(right)", "(center)").
     */
    fun positionHint(bounds: Rect, screenWidth: Int, isUnlabeled: Boolean = false): String {
        val centerX = bounds.centerX()
        return if (isUnlabeled) {
            when {
                centerX > screenWidth * 85 / 100 -> " (far-right)"
                centerX > screenWidth * 2 / 3 -> " (right)"
                centerX < screenWidth * 15 / 100 -> " (far-left)"
                centerX < screenWidth / 3 -> " (left)"
                else -> " (center)"
            }
        } else {
            when {
                centerX < screenWidth / 3 -> " (left)"
                centerX > screenWidth * 2 / 3 -> " (right)"
                else -> ""
            }
        }
    }

    /**
     * Classify a clickable element type for the LLM.
     */
    fun buttonTypeHint(el: UIElement): String {
        val className = el.className.lowercase()
        val label = (el.text ?: el.contentDescription ?: "").lowercase()

        return when {
            className.contains("imagebutton") || className.contains("imageview") -> "icon-btn"
            className.contains("checkbox") || el.isCheckable -> "checkbox"
            className.contains("switch") -> "switch"
            className.contains("tab") -> "tab"
            className.contains("chip") -> "chip"
            className.contains("radiobutton") -> "radio"
            label in listOf("back", "close", "cancel", "navigate up", "menu") -> "nav-btn"
            else -> "btn"
        }
    }
}
