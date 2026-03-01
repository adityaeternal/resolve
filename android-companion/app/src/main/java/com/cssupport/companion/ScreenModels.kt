package com.cssupport.companion

import android.graphics.Rect

/**
 * Data models for screen state representation.
 *
 * Extracted from AccessibilityEngine.kt to keep the engine focused on
 * service interaction and the models in their own file.
 */

/**
 * Structured representation of all visible UI elements on screen.
 *
 * Captured by [AccessibilityEngine.captureScreenState] and consumed by the
 * agent loop for decision making. The [elementIndex] provides a numbered map
 * for the LLM to reference elements by ID.
 *
 * Delegates complex logic to focused classes:
 * - [ElementIndex] for building the numbered element map
 * - [ScreenParser] for formatting content for the LLM
 * - [StateAnalyzer] for fingerprinting and pattern detection
 */
data class ScreenState(
    val packageName: String,
    val activityName: String?,
    val elements: List<UIElement>,
    val focusedElement: UIElement?,
    val timestamp: Long,
) {
    /**
     * Indexed element map: maps numeric IDs (1-based) to UIElements.
     * Delegates to [ElementIndex.buildElementIndex].
     */
    val elementIndex: Map<Int, UIElement> by lazy { ElementIndex.buildElementIndex(elements) }

    /** Look up an element by its numeric index. */
    fun getElementById(index: Int): UIElement? = elementIndex[index]

    /** Delegates to [StateAnalyzer.toMaskedSummary]. */
    fun toMaskedSummary(phase: String): String = StateAnalyzer.toMaskedSummary(this, phase)

    /** Delegates to [StateAnalyzer.newElementLabels]. */
    fun newElementLabels(other: ScreenState?): List<String> = StateAnalyzer.newElementLabels(this, other)

    /** Delegates to [StateAnalyzer.removedElementLabels]. */
    fun removedElementLabels(other: ScreenState?): List<String> = StateAnalyzer.removedElementLabels(this, other)

    /** Delegates to [ScreenParser.formatForLLM]. */
    fun formatForLLM(previousScreen: ScreenState? = null, collapseContent: Boolean = false): String =
        ScreenParser.formatForLLM(this, previousScreen, collapseContent)

    /** Delegates to [StateAnalyzer.fingerprint]. */
    fun fingerprint(): String = StateAnalyzer.fingerprint(this)

    /** Delegates to [StateAnalyzer.elementLabels]. */
    fun elementLabels(): Set<String> = StateAnalyzer.elementLabels(this)
}

/**
 * A single UI element parsed from the accessibility node tree.
 */
data class UIElement(
    val id: String?,
    val className: String,
    val text: String?,
    val contentDescription: String?,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isCheckable: Boolean,
    val isChecked: Boolean?,
    val isFocused: Boolean,
    val isEnabled: Boolean,
    val bounds: Rect,
    val childCount: Int,
)

/**
 * Simplified representation of a clickable element, used by [AccessibilityEngine.findClickableElements].
 */
data class ClickableElement(
    val text: String?,
    val contentDescription: String?,
    val className: String,
    val viewId: String?,
    val bounds: Rect,
)
