package com.cssupport.companion

import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * High-level action executor for accessibility automation.
 *
 * Provides index-based actions (click/type by element number), text-based
 * actions, scrolling, text entry, and screen stabilization logic.
 *
 * Extracted from AccessibilityEngine to keep the engine focused on tree
 * parsing and node finding while this class handles composite actions.
 */
class ActionExecutor(private val engine: AccessibilityEngine) {

    private val tag = "ActionExecutor"

    // ── Index-based actions ─────────────────────────────────────────────────

    /**
     * Click an element by its numeric index from the current [ScreenState.elementIndex].
     */
    fun clickByIndex(index: Int, screenState: ScreenState): Boolean {
        val targetElement = screenState.getElementById(index)
        if (targetElement == null) {
            Log.w(tag, "clickByIndex: no element at index $index")
            return false
        }

        val label = targetElement.text ?: targetElement.contentDescription
        if (label != null) {
            val nodes = engine.findNodesByText(label)
            if (nodes.isNotEmpty()) {
                val targetCx = targetElement.bounds.centerX()
                val targetCy = targetElement.bounds.centerY()
                val best = nodes.minByOrNull { node ->
                    val b = Rect()
                    node.getBoundsInScreen(b)
                    val dx = b.centerX() - targetCx
                    val dy = b.centerY() - targetCy
                    dx * dx + dy * dy
                }
                if (best != null) {
                    val result = engine.clickNode(best)
                    nodes.forEach { engine.safeRecycle(it) }
                    return result
                }
                nodes.forEach { engine.safeRecycle(it) }
            }
        }

        return engine.tapAt(
            targetElement.bounds.centerX().toFloat(),
            targetElement.bounds.centerY().toFloat(),
        )
    }

    /**
     * Set text on an element referenced by its numeric index.
     */
    fun setTextByIndex(index: Int, text: String, screenState: ScreenState): Boolean {
        val targetElement = screenState.getElementById(index)
        if (targetElement == null) {
            Log.w(tag, "setTextByIndex: no element at index $index")
            return false
        }
        if (!targetElement.isEditable) {
            Log.w(tag, "setTextByIndex: element $index is not editable")
            return false
        }

        val fields = engine.findInputFields()
        if (fields.isEmpty()) {
            Log.w(tag, "setTextByIndex: no input fields on screen")
            return false
        }

        val targetCx = targetElement.bounds.centerX()
        val targetCy = targetElement.bounds.centerY()
        val closest = fields.minByOrNull { field ->
            val bounds = Rect()
            field.getBoundsInScreen(bounds)
            val dx = bounds.centerX() - targetCx
            val dy = bounds.centerY() - targetCy
            dx * dx + dy * dy
        }

        return try {
            if (closest != null) engine.setText(closest, text) else false
        } finally {
            fields.forEach { engine.safeRecycle(it) }
        }
    }

    /**
     * Read back the current text content of an input field by index.
     */
    fun readFieldTextByIndex(index: Int, screenState: ScreenState): String? {
        val targetElement = screenState.getElementById(index) ?: return null
        if (!targetElement.isEditable) return null

        val fields = engine.findInputFields()
        if (fields.isEmpty()) return null

        val targetCx = targetElement.bounds.centerX()
        val targetCy = targetElement.bounds.centerY()
        val closest = fields.minByOrNull { field ->
            val bounds = Rect()
            field.getBoundsInScreen(bounds)
            val dx = bounds.centerX() - targetCx
            val dy = bounds.centerY() - targetCy
            dx * dx + dy * dy
        }

        val result = closest?.text?.toString()
        fields.forEach { engine.safeRecycle(it) }
        return result
    }

    /**
     * Read back the text of the first input field on screen.
     */
    fun readFirstFieldText(): String? {
        val fields = engine.findInputFields()
        if (fields.isEmpty()) return null
        val text = fields.first().text?.toString()
        fields.forEach { engine.safeRecycle(it) }
        return text
    }

    // ── Screen stabilization ────────────────────────────────────────────────

    /**
     * Wait until the screen content stabilizes (stops changing).
     */
    suspend fun waitForStableScreen(
        maxWaitMs: Long = 3000,
        pollIntervalMs: Long = 300,
    ): ScreenState {
        delay(250)
        var previousFingerprint = ""
        var lastState = engine.captureScreenState()
        val deadline = System.currentTimeMillis() + maxWaitMs

        while (System.currentTimeMillis() < deadline) {
            val currentFingerprint = lastState.fingerprint()
            if (currentFingerprint == previousFingerprint && previousFingerprint.isNotEmpty()) {
                Log.d(tag, "Screen stabilized after ${maxWaitMs - (deadline - System.currentTimeMillis())}ms")
                return lastState
            }
            previousFingerprint = currentFingerprint
            delay(pollIntervalMs)
            lastState = engine.captureScreenState()
        }

        Log.d(tag, "Screen stabilization timed out after ${maxWaitMs}ms")
        return lastState
    }

    // ── Text and click actions ──────────────────────────────────────────────

    /** Click a node matched by text label. */
    fun clickByText(text: String): Boolean {
        val node = engine.findNodeByText(text) ?: run {
            Log.w(tag, "clickByText: no node found for '$text'")
            return false
        }
        return try {
            engine.clickNode(node)
        } finally {
            engine.safeRecycle(node)
        }
    }

    /** Set text on an editable node. Clears existing text first, then inserts. */
    fun setText(node: AccessibilityNodeInfo, text: String): Boolean =
        engine.setText(node, text)

    /** Find the first input field and type text into it. */
    fun typeIntoFirstField(text: String): Boolean {
        val fields = engine.findInputFields()
        if (fields.isEmpty()) {
            Log.w(tag, "typeIntoFirstField: no input fields found")
            return false
        }
        return try {
            engine.setText(fields.first(), text)
        } finally {
            fields.forEach { engine.safeRecycle(it) }
        }
    }

    /** Scroll forward on the first scrollable container. */
    fun scrollScreenForward(): Boolean {
        val scrollables = engine.findScrollableNodes()
        if (scrollables.isEmpty()) {
            Log.w(tag, "scrollScreenForward: no scrollable containers")
            return false
        }
        return try {
            engine.scrollForward(scrollables.first())
        } finally {
            scrollables.forEach { engine.safeRecycle(it) }
        }
    }

    /** Scroll backward on the first scrollable container. */
    fun scrollScreenBackward(): Boolean {
        val scrollables = engine.findScrollableNodes()
        if (scrollables.isEmpty()) return false
        return try {
            engine.scrollBackward(scrollables.first())
        } finally {
            scrollables.forEach { engine.safeRecycle(it) }
        }
    }

    // ── Waiting / observation ───────────────────────────────────────────────

    /** Suspend until a content change is observed or timeout expires. */
    suspend fun waitForContentChange(timeoutMs: Long = 5000): Boolean =
        engine.waitForContentChange(timeoutMs)

    /** Wait for a specific text to appear on screen, polling periodically. */
    suspend fun waitForText(text: String, timeoutMs: Long = 10000, pollIntervalMs: Long = 500): Boolean =
        engine.waitForText(text, timeoutMs, pollIntervalMs)
}
