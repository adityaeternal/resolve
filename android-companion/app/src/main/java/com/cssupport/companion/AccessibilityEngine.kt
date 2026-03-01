package com.cssupport.companion

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Production accessibility automation engine.
 *
 * Parses the accessibility node tree from any foreground app into a structured
 * [ScreenState], and executes UI automation actions (click, type, scroll, etc.)
 * via the platform AccessibilityService APIs.
 *
 * All [AccessibilityNodeInfo] references obtained internally are recycled after use
 * to prevent memory leaks. Callers must NOT hold references to nodes returned by
 * finder methods beyond a single action -- capture what you need and let go.
 */
class AccessibilityEngine(private val service: AccessibilityService) {

    private val tag = "A11yEngine"

    /** Gesture executor for tap, swipe, and global actions. */
    private val gestures = GestureExecutor(service)

    // Signaled by the accessibility service when content changes.
    private val contentChanged = AtomicBoolean(false)

    // Last known foreground package, updated from accessibility events.
    private val currentPackageRef = AtomicReference<String?>(null)

    // Last known activity class, updated from TYPE_WINDOW_STATE_CHANGED.
    private val currentActivityRef = AtomicReference<String?>(null)

    // ── Event hooks (called from SupportAccessibilityService) ───────────────

    /** Notify that a content change event was received. */
    fun notifyContentChanged() {
        contentChanged.set(true)
    }

    /** Update the tracked foreground package name. */
    fun setCurrentPackage(packageName: String?) {
        currentPackageRef.set(packageName)
    }

    /** Update the tracked foreground activity class name. */
    fun setCurrentActivity(activityName: String?) {
        currentActivityRef.set(activityName)
    }

    // ── Screen capture ──────────────────────────────────────────────────────

    /**
     * Capture the full screen state from the current accessibility tree.
     * Returns a structured [ScreenState] with all visible UI elements.
     *
     * Uses the event-tracked [currentPackageRef] as the authoritative source for the
     * foreground package. When [rootInActiveWindow] returns a stale window (package
     * doesn't match the event-tracked one), searches [service.windows] for the correct
     * app window to get an accurate node tree.
     */
    fun captureScreenState(): ScreenState {
        // Event-tracked package is the authoritative source for which app is in foreground.
        val eventPackage = currentPackageRef.get()

        var rootNode = service.rootInActiveWindow

        // If rootInActiveWindow has a stale package, try to find the correct window.
        if (rootNode != null && eventPackage != null
            && rootNode.packageName?.toString() != eventPackage
        ) {
            Log.d(tag, "rootInActiveWindow is stale (has ${rootNode.packageName}, " +
                "expected $eventPackage) — searching windows")
            safeRecycle(rootNode)
            rootNode = null
            try {
                for (window in service.windows) {
                    val windowRoot = window.root ?: continue
                    if (windowRoot.packageName?.toString() == eventPackage) {
                        rootNode = windowRoot
                        break
                    } else {
                        safeRecycle(windowRoot)
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Failed to enumerate windows: ${e.message}")
            }
        }

        // Fallback: if rootInActiveWindow is null, search all windows for any root.
        // Prefer windows matching the target app package (popups, drawers, dialogs).
        // Avoid systemui unless it's the only option.
        if (rootNode == null) {
            try {
                var bestRoot: AccessibilityNodeInfo? = null
                var bestPkg: String? = null
                for (window in service.windows) {
                    val windowRoot = window.root ?: continue
                    val pkg = windowRoot.packageName?.toString() ?: ""
                    // Prefer target app package.
                    if (eventPackage != null && pkg == eventPackage) {
                        if (bestRoot != null) safeRecycle(bestRoot)
                        bestRoot = windowRoot
                        bestPkg = pkg
                        break // Exact match, stop searching.
                    }
                    // Skip our own overlay and systemui/launcher.
                    if (pkg == "com.cssupport.companion") {
                        safeRecycle(windowRoot)
                        continue
                    }
                    val isSystemWindow = pkg.contains("systemui") || pkg.contains("launcher")
                    if (bestRoot == null || (!isSystemWindow && (bestPkg?.contains("systemui") == true))) {
                        if (bestRoot != null) safeRecycle(bestRoot)
                        bestRoot = windowRoot
                        bestPkg = pkg
                    } else {
                        safeRecycle(windowRoot)
                    }
                }
                if (bestRoot != null) {
                    rootNode = bestRoot
                    Log.d(tag, "Found root via service.windows: $bestPkg")
                }
            } catch (e: Exception) {
                Log.w(tag, "Failed to enumerate windows for fallback: ${e.message}")
            }
        }

        if (rootNode == null) {
            Log.w(tag, "captureScreenState: no valid root node found")
            return ScreenState(
                packageName = eventPackage.orEmpty(),
                activityName = currentActivityRef.get(),
                elements = emptyList(),
                focusedElement = null,
                timestamp = System.currentTimeMillis(),
            )
        }

        return try {
            val elements = mutableListOf<UIElement>()
            var focusedElement: UIElement? = null

            parseNodeTree(rootNode, elements)

            // Find the focused/input-focused element.
            val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusedNode != null) {
                focusedElement = nodeToUIElement(focusedNode)
                safeRecycle(focusedNode)
            }

            // Prefer event-tracked package, fall back to root node's package.
            val packageName = eventPackage
                ?: rootNode.packageName?.toString().orEmpty()

            ScreenState(
                packageName = packageName,
                activityName = currentActivityRef.get(),
                elements = elements,
                focusedElement = focusedElement,
                timestamp = System.currentTimeMillis(),
            )
        } finally {
            safeRecycle(rootNode)
        }
    }

    /**
     * Recursively parse the node tree into a flat list of [UIElement].
     * Only includes nodes that have meaningful content (text, content description,
     * or are interactive). This avoids flooding the LLM with layout containers.
     */
    private fun parseNodeTree(
        node: AccessibilityNodeInfo,
        output: MutableList<UIElement>,
        depth: Int = 0,
    ) {
        // Safety: cap recursion at 30 levels to avoid pathological trees.
        if (depth > 30) return

        var element = nodeToUIElement(node)

        // Key fix: If a clickable container has no text/description but its children do,
        // inherit the text from children. This is EXTREMELY common in Android UIs where
        // a FrameLayout or LinearLayout wraps a TextView (e.g., Swiggy's LOGIN button).
        // Without this, the LLM sees "[N] btn: unlabeled" and can't understand the button.
        if (element.isClickable && element.text.isNullOrBlank() && element.contentDescription.isNullOrBlank()) {
            val childText = collectChildText(node, maxDepth = 5)
            if (childText.isNotBlank()) {
                element = element.copy(text = childText)
            }
        }

        // Include this node if it carries information the agent can use.
        val isInteresting = element.text?.isNotBlank() == true
            || element.contentDescription?.isNotBlank() == true
            || element.isClickable
            || element.isEditable
            || element.isScrollable
            || element.isCheckable

        if (isInteresting) {
            output.add(element)
        }

        // Recurse into children.
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            try {
                parseNodeTree(child, output, depth + 1)
            } finally {
                safeRecycle(child)
            }
        }
    }

    /**
     * Collect text from immediate children of a node (up to [maxDepth] levels deep).
     * Used to give clickable containers meaningful labels from their child TextViews.
     * Returns concatenated text from all text-bearing children, limited to a reasonable length.
     */
    private fun collectChildText(node: AccessibilityNodeInfo, maxDepth: Int, currentDepth: Int = 0): String {
        if (currentDepth > maxDepth) return ""
        val parts = mutableListOf<String>()
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            try {
                val text = child.text?.toString()?.trim()
                if (!text.isNullOrBlank() && text.length <= 100) {
                    parts.add(text)
                }
                // Recurse deeper if this child also has no text.
                if (text.isNullOrBlank() && child.childCount > 0) {
                    val deeper = collectChildText(child, maxDepth, currentDepth + 1)
                    if (deeper.isNotBlank()) parts.add(deeper)
                }
            } finally {
                safeRecycle(child)
            }
            // Limit to avoid getting too much text from large containers.
            if (parts.size >= 3) break
        }
        return parts.joinToString(" ").take(120)
    }

    private fun nodeToUIElement(node: AccessibilityNodeInfo): UIElement {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        return UIElement(
            id = node.viewIdResourceName,
            className = node.className?.toString().orEmpty(),
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            isClickable = node.isClickable,
            isEditable = node.isEditable,
            isScrollable = node.isScrollable,
            isCheckable = node.isCheckable,
            isChecked = if (node.isCheckable) node.isChecked else null,
            isFocused = node.isFocused,
            isEnabled = node.isEnabled,
            bounds = bounds,
            childCount = node.childCount,
        )
    }

    // ── Finders ─────────────────────────────────────────────────────────────

    /**
     * Find all nodes whose text or content description contains [text] (case-insensitive).
     * Caller MUST recycle returned nodes when done.
     */
    fun findNodesByText(text: String): List<AccessibilityNodeInfo> {
        val root = service.rootInActiveWindow ?: return emptyList()
        return try {
            val found = root.findAccessibilityNodeInfosByText(text)
            found?.toList() ?: emptyList()
        } finally {
            safeRecycle(root)
        }
    }

    /**
     * Find a single node by text. Returns the first clickable match, or the first match.
     * Caller MUST recycle returned node.
     */
    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val nodes = findNodesByText(text)
        if (nodes.isEmpty()) return null

        // Prefer clickable nodes (buttons) over static text.
        val clickable = nodes.firstOrNull { it.isClickable }
        if (clickable != null) {
            // Recycle the others.
            nodes.filter { it !== clickable }.forEach { safeRecycle(it) }
            return clickable
        }

        // Return first, recycle rest.
        nodes.drop(1).forEach { safeRecycle(it) }
        return nodes.first()
    }

    /**
     * Find a node by its view ID resource name (e.g., "com.app:id/send_button").
     * Caller MUST recycle returned node.
     */
    fun findNodeById(viewId: String): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        return try {
            val found = root.findAccessibilityNodeInfosByViewId(viewId)
            val result = found?.firstOrNull()
            // Recycle any extra results to prevent binder leaks.
            found?.drop(1)?.forEach { safeRecycle(it) }
            result
        } finally {
            safeRecycle(root)
        }
    }

    /**
     * Find all editable input fields currently on screen.
     * Caller MUST recycle returned nodes.
     */
    fun findInputFields(): List<AccessibilityNodeInfo> {
        val root = service.rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        try {
            collectNodes(root, result, predicate = { it.isEditable })
        } finally {
            safeRecycle(root)
        }
        return result
    }

    /**
     * Find all clickable elements on screen.
     */
    fun findClickableElements(): List<ClickableElement> {
        val root = service.rootInActiveWindow ?: return emptyList()
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        try {
            collectNodes(root, nodes, predicate = { it.isClickable && it.isEnabled })
            return nodes.map { node ->
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                ClickableElement(
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    className = node.className?.toString().orEmpty(),
                    viewId = node.viewIdResourceName,
                    bounds = bounds,
                )
            }
        } finally {
            nodes.forEach { safeRecycle(it) }
            safeRecycle(root)
        }
    }

    /**
     * Find all scrollable containers on screen.
     * Caller MUST recycle returned nodes.
     */
    fun findScrollableNodes(): List<AccessibilityNodeInfo> {
        val root = service.rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        try {
            collectNodes(root, result, predicate = { it.isScrollable })
        } finally {
            safeRecycle(root)
        }
        return result
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        output: MutableList<AccessibilityNodeInfo>,
        predicate: (AccessibilityNodeInfo) -> Boolean,
        depth: Int = 0,
    ) {
        if (depth > 30) return

        if (predicate(node)) {
            // Do NOT recycle these -- caller owns them.
            output.add(AccessibilityNodeInfo.obtain(node))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectNodes(child, output, predicate, depth + 1)
            } finally {
                safeRecycle(child)
            }
        }
    }

    // ── Index-based actions (delegated to ActionExecutor) ──────────────────

    /** Lazily initialized action executor for composite actions. */
    private val actions by lazy { ActionExecutor(this) }

    /** Click an element by its numeric index. Delegates to [ActionExecutor]. */
    fun clickByIndex(index: Int, screenState: ScreenState): Boolean =
        actions.clickByIndex(index, screenState)

    /** Set text on an element by index. Delegates to [ActionExecutor]. */
    fun setTextByIndex(index: Int, text: String, screenState: ScreenState): Boolean =
        actions.setTextByIndex(index, text, screenState)

    /** Read back field text by index. Delegates to [ActionExecutor]. */
    fun readFieldTextByIndex(index: Int, screenState: ScreenState): String? =
        actions.readFieldTextByIndex(index, screenState)

    /** Read text of the first input field. Delegates to [ActionExecutor]. */
    fun readFirstFieldText(): String? = actions.readFirstFieldText()

    /** Wait until the screen stabilizes. Delegates to [ActionExecutor]. */
    suspend fun waitForStableScreen(
        maxWaitMs: Long = 3000,
        pollIntervalMs: Long = 300,
    ): ScreenState = actions.waitForStableScreen(maxWaitMs, pollIntervalMs)

    // ── Actions ─────────────────────────────────────────────────────────────

    /**
     * Click a node. Tries performAction(ACTION_CLICK) first, then walks up to
     * find a clickable ancestor (common pattern in complex UIs). Falls back to
     * tap gesture at the node's center coordinates.
     */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        // Direct click.
        if (node.isClickable) {
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (result) {
                Log.d(tag, "clickNode: direct ACTION_CLICK succeeded")
                return true
            }
        }

        // Walk up to clickable parent.
        var current = node.parent
        var depth = 0
        while (current != null && depth < 8) {
            if (current.isClickable) {
                val result = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                safeRecycle(current)
                if (result) {
                    Log.d(tag, "clickNode: parent click succeeded at depth=$depth")
                    return true
                }
                return false
            }
            val next = current.parent
            safeRecycle(current)
            current = next
            depth++
        }
        current?.let { safeRecycle(it) }

        // Gesture fallback: tap at center of bounds.
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return tapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    /** Click a node matched by text label. Delegates to [ActionExecutor]. */
    fun clickByText(text: String): Boolean = actions.clickByText(text)

    /**
     * Set text on an editable node. Clears existing text first, then inserts.
     */
    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.isEditable) {
            Log.w(tag, "setText: node is not editable")
            return false
        }

        // Focus the node first.
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        // Clear existing text.
        val selectAll = Bundle().apply {
            putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                0,
            )
            putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                node.text?.length ?: 0,
            )
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectAll)

        // Set new text.
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.d(tag, "setText: result=$result for '${text.take(40)}...'")
        return result
    }

    /** Find the first input field and type text into it. Delegates to [ActionExecutor]. */
    fun typeIntoFirstField(text: String): Boolean = actions.typeIntoFirstField(text)

    /** Scroll forward in a scrollable container. */
    fun scrollForward(node: AccessibilityNodeInfo): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)

    /** Scroll backward in a scrollable container. */
    fun scrollBackward(node: AccessibilityNodeInfo): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)

    /** Scroll forward on the first scrollable container. Delegates to [ActionExecutor]. */
    fun scrollScreenForward(): Boolean = actions.scrollScreenForward()

    /** Scroll backward on the first scrollable container. Delegates to [ActionExecutor]. */
    fun scrollScreenBackward(): Boolean = actions.scrollScreenBackward()

    /** Press the global Back button. Delegates to [GestureExecutor]. */
    fun pressBack(): Boolean = gestures.pressBack()

    /** Press the global Home button. Delegates to [GestureExecutor]. */
    fun pressHome(): Boolean = gestures.pressHome()

    /** Open the Recents / app switcher. Delegates to [GestureExecutor]. */
    fun pressRecents(): Boolean = gestures.pressRecents()

    /** Open the notification shade. Delegates to [GestureExecutor]. */
    fun openNotifications(): Boolean = gestures.openNotifications()

    /** Perform a tap gesture at the given screen coordinates. Delegates to [GestureExecutor]. */
    fun tapAt(x: Float, y: Float): Boolean = gestures.tapAt(x, y)

    /** Perform a swipe gesture. Delegates to [GestureExecutor]. */
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean =
        gestures.swipe(startX, startY, endX, endY, durationMs)

    // ── Waiting / observation ───────────────────────────────────────────────

    /** Suspend until a content change is observed or timeout expires. */
    suspend fun waitForContentChange(timeoutMs: Long = 5000): Boolean {
        contentChanged.set(false)
        return withTimeoutOrNull(timeoutMs) {
            while (!contentChanged.get()) { delay(100) }
            true
        } ?: false
    }

    /** Wait for a specific text to appear on screen, polling periodically. */
    suspend fun waitForText(text: String, timeoutMs: Long = 10000, pollIntervalMs: Long = 500): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            while (true) {
                val nodes = findNodesByText(text)
                if (nodes.isNotEmpty()) { nodes.forEach { safeRecycle(it) }; return@withTimeoutOrNull true }
                delay(pollIntervalMs)
            }
            @Suppress("UNREACHABLE_CODE") false
        } ?: false
    }

    // ── Info ────────────────────────────────────────────────────────────────

    /** Get the current foreground package name. */
    fun getCurrentPackage(): String? = currentPackageRef.get()

    /** Get the current foreground activity class name. */
    fun getCurrentActivity(): String? = currentActivityRef.get()

    // ── Helpers ─────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    internal fun safeRecycle(node: AccessibilityNodeInfo?) {
        try { node?.recycle() } catch (_: Exception) { }
    }
}
