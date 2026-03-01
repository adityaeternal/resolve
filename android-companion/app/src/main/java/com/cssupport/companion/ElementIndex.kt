package com.cssupport.companion

/**
 * Builds and manages the numbered element index for a [ScreenState].
 *
 * Extracted from the monolithic AccessibilityEngine.kt to isolate the element
 * indexing, filtering, and deduplication logic.
 *
 * The element index assigns 1-based numeric IDs to all meaningful UI elements,
 * allowing the LLM to reference elements by number instead of by text label.
 * IDs reset with each new screen capture -- they are per-turn, not persistent.
 */
object ElementIndex {

    /**
     * Build the element index, assigning 1-based IDs to all meaningful elements.
     * Elements are ordered: top-bar left-to-right, then content top-to-bottom,
     * then bottom-bar left-to-right.
     */
    fun buildElementIndex(elements: List<UIElement>): Map<Int, UIElement> {
        val screenWidth = elements.maxOfOrNull { it.bounds.right } ?: 1080
        val screenHeight = elements.maxOfOrNull { it.bounds.bottom } ?: 2400

        val meaningful = filterMeaningful(elements, screenWidth, screenHeight)
        val deduped = deduplicateElements(meaningful)

        val topBarThreshold = screenHeight / 8
        val bottomBarThreshold = screenHeight * 7 / 8

        val topBar = deduped.filter { it.bounds.centerY() < topBarThreshold }
            .sortedBy { it.bounds.centerX() }
        val content = deduped.filter {
            it.bounds.centerY() in topBarThreshold..bottomBarThreshold
        }.sortedBy { it.bounds.centerY() }
        val bottomBar = deduped.filter { it.bounds.centerY() > bottomBarThreshold }
            .sortedBy { it.bounds.centerX() }

        val ordered = topBar + content + bottomBar
        val map = mutableMapOf<Int, UIElement>()
        ordered.forEachIndexed { idx, el ->
            map[idx + 1] = el
        }
        return map
    }

    /**
     * Filter elements to only include those with meaningful content
     * that are on-screen and have a reasonable size.
     */
    fun filterMeaningful(
        elements: List<UIElement>,
        screenWidth: Int,
        screenHeight: Int,
    ): List<UIElement> {
        return elements.filter { el ->
            val hasContent = !el.text.isNullOrBlank()
                || !el.contentDescription.isNullOrBlank()
                || el.isClickable || el.isEditable || el.isScrollable || el.isCheckable
            val hasSize = el.bounds.width() > 2 && el.bounds.height() > 2
            val isOnScreen = el.bounds.right > 0 && el.bounds.bottom > 0
                && el.bounds.left < screenWidth && el.bounds.top < screenHeight
            hasContent && hasSize && isOnScreen
        }
    }

    /**
     * Remove duplicate elements at similar positions with the same label.
     * Uses position bucketing (20px) to detect visual duplicates.
     */
    fun deduplicateElements(elements: List<UIElement>): List<UIElement> {
        return elements.distinctBy { el ->
            val label = el.text ?: el.contentDescription ?: ""
            val posKey = "${el.bounds.centerX() / 20},${el.bounds.centerY() / 20}"
            "$label|$posKey|${el.isClickable}|${el.isEditable}"
        }
    }
}
