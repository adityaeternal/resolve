package com.cssupport.companion

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for AccessibilityEngine's pure-logic portions.
 *
 * AccessibilityEngine itself requires an AccessibilityService and can't be directly
 * unit tested. However, the ScreenState class (defined in AccessibilityEngine.kt)
 * contains substantial pure logic that IS testable:
 * - elementIndex: builds a numbered map of elements with spatial sorting
 * - getElementById: lookup by numeric index
 * - formatForLLM: formats the screen for the LLM with navigation-first presentation
 * - isNavigationElement: classifies elements as nav vs content
 * - filterMeaningful: removes offscreen/tiny/empty elements
 * - deduplicateElements: removes duplicate elements at same position
 * - detectScreenPattern: high-level screen classification
 * - positionHint: spatial position descriptions
 * - buttonTypeHint: element type classification
 * - fingerprint: screen identity hash
 *
 * Using Robolectric so that android.graphics.Rect works properly with real
 * coordinate math (centerX, centerY, width, height).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AccessibilityEngineTest {

    // == elementIndex ==========================================================

    @Test
    fun `elementIndex should assign 1-based indices to elements`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "Home", isClickable = true, bounds = Rect(0, 1200, 200, 1300)),
                makeElement(text = "Orders", isClickable = true, bounds = Rect(200, 1200, 400, 1300)),
            ),
        )
        val index = screen.elementIndex
        assertTrue(index.containsKey(1))
        assertTrue(index.containsKey(2))
        assertFalse(index.containsKey(0))
        assertFalse(index.containsKey(3))
    }

    @Test
    fun `elementIndex should sort top bar elements left to right`() {
        // screenHeight is derived from max bottom value (2400).
        // topBarThreshold = 2400/8 = 300. Top bar = centerY < 300.
        // Elements at y=10..80, centerY=45 -> in top bar.
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "Right Icon", isClickable = true, bounds = Rect(900, 10, 1000, 80)),
                makeElement(text = "Left Icon", isClickable = true, bounds = Rect(10, 10, 100, 80)),
                // Anchor element to set screenHeight to a realistic value
                makeElement(text = "Anchor", bounds = Rect(0, 2300, 100, 2400)),
            ),
        )
        val index = screen.elementIndex
        // Left icon should come first (lower X) in top bar
        val firstLabel = index[1]?.text
        val secondLabel = index[2]?.text
        assertEquals("Left Icon", firstLabel)
        assertEquals("Right Icon", secondLabel)
    }

    @Test
    fun `elementIndex should sort content elements top to bottom`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "Lower Item", isClickable = true, bounds = Rect(100, 800, 400, 900)),
                makeElement(text = "Upper Item", isClickable = true, bounds = Rect(100, 400, 400, 500)),
            ),
        )
        val screenHeight = 2400
        val index = screen.elementIndex
        // Upper item (lower Y) should come first
        val values = index.values.toList()
        assertEquals("Upper Item", values[0].text)
        assertEquals("Lower Item", values[1].text)
    }

    @Test
    fun `elementIndex should exclude non-meaningful elements`() {
        val screen = makeScreen(
            elements = listOf(
                // Meaningful: has text and is clickable
                makeElement(text = "Help", isClickable = true, bounds = Rect(100, 500, 300, 600)),
                // Not meaningful: no text, not interactive, no content description
                UIElement(
                    id = null,
                    className = "android.view.View",
                    text = null,
                    contentDescription = null,
                    isClickable = false,
                    isEditable = false,
                    isScrollable = false,
                    isCheckable = false,
                    isChecked = null,
                    isFocused = false,
                    isEnabled = true,
                    bounds = Rect(0, 0, 1080, 2400),
                    childCount = 5,
                ),
            ),
        )
        // Only the "Help" element should be indexed
        val index = screen.elementIndex
        assertEquals(1, index.size)
        assertEquals("Help", index[1]?.text)
    }

    @Test
    fun `elementIndex should exclude offscreen elements with negative coordinates`() {
        // An element with right <= 0 is offscreen (to the left of the screen).
        // An element with bottom <= 0 is offscreen (above the screen).
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "Visible", isClickable = true, bounds = Rect(100, 500, 300, 600)),
                // Off-screen: right = 0 (all to the left of visible area)
                makeElement(text = "OffScreenLeft", isClickable = true, bounds = Rect(-200, 500, 0, 600)),
                // Off-screen: bottom = 0 (all above visible area)
                makeElement(text = "OffScreenAbove", isClickable = true, bounds = Rect(100, -100, 300, 0)),
            ),
        )
        val index = screen.elementIndex
        assertEquals(
            "Only the visible element should be in the index",
            1,
            index.size,
        )
        assertEquals("Visible", index[1]?.text)
    }

    @Test
    fun `elementIndex should exclude tiny elements`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "Normal", isClickable = true, bounds = Rect(100, 500, 300, 600)),
                // 1x1 pixel element
                makeElement(text = "Tiny", isClickable = true, bounds = Rect(100, 700, 101, 701)),
            ),
        )
        val index = screen.elementIndex
        assertEquals(1, index.size)
        assertEquals("Normal", index[1]?.text)
    }

    @Test
    fun `elementIndex should deduplicate elements at similar positions`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "Button", isClickable = true, bounds = Rect(100, 500, 300, 600)),
                // Same label, very similar position (within 20px bucket)
                makeElement(text = "Button", isClickable = true, bounds = Rect(105, 505, 305, 605)),
            ),
        )
        val index = screen.elementIndex
        assertEquals(1, index.size)
    }

    // == getElementById ========================================================

    @Test
    fun `getElementById should return element for valid index`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "Help", isClickable = true, bounds = Rect(100, 500, 300, 600)),
            ),
        )
        val element = screen.getElementById(1)
        assertNotNull(element)
        assertEquals("Help", element?.text)
    }

    @Test
    fun `getElementById should return null for invalid index`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "Help", isClickable = true, bounds = Rect(100, 500, 300, 600)),
            ),
        )
        assertNull(screen.getElementById(999))
        assertNull(screen.getElementById(0))
        assertNull(screen.getElementById(-1))
    }

    // == formatForLLM =========================================================

    @Test
    fun `formatForLLM should include package name`() {
        val screen = makeScreen(
            packageName = "in.swiggy.android",
            elements = listOf(
                makeElement(text = "Home", isClickable = true, bounds = Rect(100, 500, 300, 600)),
            ),
        )
        val output = screen.formatForLLM()
        assertTrue(output.contains("Package: in.swiggy.android"))
    }

    @Test
    fun `formatForLLM should include activity simple name`() {
        val screen = makeScreen(
            activityName = "com.swiggy.app.HomeActivity",
            elements = listOf(
                makeElement(text = "Home", isClickable = true, bounds = Rect(100, 500, 300, 600)),
            ),
        )
        val output = screen.formatForLLM()
        assertTrue(output.contains("Screen: HomeActivity"))
    }

    @Test
    fun `formatForLLM should show empty screen for no elements`() {
        val screen = makeScreen(elements = emptyList())
        val output = screen.formatForLLM()
        assertTrue(output.contains("empty screen"))
    }

    @Test
    fun `formatForLLM should show NAVIGATION section for bottom bar elements`() {
        val screen = makeScreen(
            elements = listOf(
                // Bottom bar element (Y > 7/8 of max height)
                makeElement(text = "Account", isClickable = true, bounds = Rect(800, 2200, 1000, 2350)),
                // Content element
                makeElement(text = "Pizza", isClickable = true, bounds = Rect(100, 600, 300, 700)),
            ),
        )
        val output = screen.formatForLLM()
        assertTrue(output.contains("NAVIGATION"))
        assertTrue(output.contains(">>>"))
        assertTrue(output.contains("Account"))
    }

    @Test
    fun `formatForLLM should show TOP BAR section for top elements`() {
        val screen = makeScreen(
            elements = listOf(
                // Top bar element (Y < 1/8 of screen height)
                makeElement(text = "Back", isClickable = true, bounds = Rect(10, 10, 80, 80)),
                // Content element in middle
                makeElement(text = "Item", isClickable = true, bounds = Rect(100, 600, 300, 700)),
            ),
        )
        val output = screen.formatForLLM()
        assertTrue(output.contains("TOP BAR"))
    }

    @Test
    fun `formatForLLM should mark navigation elements with arrows`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "Account", isClickable = true, bounds = Rect(100, 600, 300, 700)),
                makeElement(text = "Orders", isClickable = true, bounds = Rect(100, 800, 300, 900)),
                makeElement(text = "Help", isClickable = true, bounds = Rect(100, 1000, 300, 1100)),
            ),
        )
        val output = screen.formatForLLM()
        assertTrue(output.contains(">>>"))
    }

    @Test
    fun `formatForLLM should collapse content when collapseContent is true`() {
        val elements = (1..10).map { i ->
            makeElement(
                text = "Product $i",
                isClickable = true,
                bounds = Rect(100, 400 + i * 100, 300, 500 + i * 100),
            )
        }
        val screen = makeScreen(elements = elements)
        val output = screen.formatForLLM(collapseContent = true)
        assertTrue(output.contains("other items"))
        assertTrue(output.contains("NOT paths to support"))
    }

    @Test
    fun `formatForLLM should include element IDs in brackets`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "Help", isClickable = true, bounds = Rect(100, 600, 300, 700)),
            ),
        )
        val output = screen.formatForLLM()
        assertTrue(output.contains("[1]"))
    }

    @Test
    fun `formatForLLM should mark NEW elements when previous screen provided`() {
        val prev = makeScreen(
            elements = listOf(
                makeElement(text = "Home", isClickable = true, bounds = Rect(100, 600, 300, 700)),
            ),
        )
        val curr = makeScreen(
            elements = listOf(
                makeElement(text = "Home", isClickable = true, bounds = Rect(100, 600, 300, 700)),
                makeElement(text = "Chat with us", isClickable = true, bounds = Rect(100, 800, 300, 900)),
            ),
        )
        val output = curr.formatForLLM(previousScreen = prev)
        assertTrue(output.contains("NEW"))
    }

    @Test
    fun `formatForLLM should show focused element`() {
        val focused = makeElement(text = "Input field", isEditable = true, bounds = Rect(100, 600, 800, 700))
        val screen = ScreenState(
            packageName = "com.test",
            activityName = null,
            elements = listOf(focused),
            focusedElement = focused,
            timestamp = System.currentTimeMillis(),
        )
        val output = screen.formatForLLM()
        assertTrue(output.contains("Focused:"))
    }

    @Test
    fun `formatForLLM should include type hints for elements`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "Username", isEditable = true, bounds = Rect(100, 600, 800, 700)),
                makeElement(text = "Submit", isClickable = true, bounds = Rect(100, 800, 300, 900)),
            ),
        )
        val output = screen.formatForLLM()
        assertTrue(output.contains("INPUT"))
        assertTrue(output.contains("btn"))
    }

    @Test
    fun `formatForLLM should show scrollable hint`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "Content", isScrollable = true, bounds = Rect(0, 400, 1080, 2000)),
            ),
        )
        val output = screen.formatForLLM()
        assertTrue(output.contains("scrollable"))
    }

    // == detectScreenPattern ==================================================

    @Test
    fun `formatForLLM should detect chat messaging interface pattern`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "Type a message", isEditable = true, bounds = Rect(100, 2100, 800, 2200)),
                makeElement(text = "Send", isClickable = true, bounds = Rect(850, 2100, 1000, 2200)),
                makeElement(text = "Agent: How can I help?", bounds = Rect(100, 600, 800, 700)),
                makeElement(text = "You: Hi", bounds = Rect(100, 800, 800, 900)),
            ),
        )
        val output = screen.formatForLLM()
        assertTrue(output.contains("Chat/messaging"))
    }

    @Test
    fun `formatForLLM should detect order list pattern`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "My Orders", bounds = Rect(100, 200, 500, 300)),
                makeElement(text = "Order #12345 - Delivered", isClickable = true, bounds = Rect(100, 400, 900, 500)),
                makeElement(text = "Track your order", isClickable = true, bounds = Rect(100, 600, 500, 700)),
            ),
        )
        val output = screen.formatForLLM()
        assertTrue(output.contains("Order list"))
    }

    @Test
    fun `formatForLLM should detect help support page pattern`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "Help Center", bounds = Rect(100, 200, 500, 300)),
                makeElement(text = "Contact Us", isClickable = true, bounds = Rect(100, 400, 500, 500)),
                makeElement(text = "FAQ", isClickable = true, bounds = Rect(100, 600, 500, 700)),
            ),
        )
        val output = screen.formatForLLM()
        assertTrue(output.contains("Help/support"))
    }

    @Test
    fun `formatForLLM should detect profile account page pattern`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "My Account", bounds = Rect(100, 200, 500, 300)),
                makeElement(text = "Edit Profile", isClickable = true, bounds = Rect(100, 400, 500, 500)),
                makeElement(text = "Settings", isClickable = true, bounds = Rect(100, 600, 500, 700)),
            ),
        )
        val output = screen.formatForLLM()
        assertTrue(output.contains("Profile/account"))
    }

    // == isNavigationElement (tested indirectly through formatForLLM) ==========

    @Test
    fun `navigation elements should include account and profile keywords`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "Account", isClickable = true, bounds = Rect(100, 600, 300, 700)),
                makeElement(text = "Profile", isClickable = true, bounds = Rect(400, 600, 600, 700)),
                makeElement(text = "Pizza Special", isClickable = true, bounds = Rect(100, 900, 500, 1000)),
            ),
        )
        val output = screen.formatForLLM(collapseContent = true)
        // Account and Profile should have >>> markers
        assertTrue(output.contains(">>> ") && output.contains("Account"))
        assertTrue(output.contains(">>> ") && output.contains("Profile"))
    }

    @Test
    fun `navigation elements should include help and support keywords`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "Help", isClickable = true, bounds = Rect(100, 600, 300, 700)),
                makeElement(text = "Support", isClickable = true, bounds = Rect(100, 800, 300, 900)),
            ),
        )
        val output = screen.formatForLLM()
        // Both should appear with navigation markers
        val lines = output.lines()
        val helpLines = lines.filter { it.contains("Help") && it.contains(">>>") }
        val supportLines = lines.filter { it.contains("Support") && it.contains(">>>") }
        assertTrue("Help should be marked as navigation", helpLines.isNotEmpty())
        assertTrue("Support should be marked as navigation", supportLines.isNotEmpty())
    }

    @Test
    fun `navigation elements should include tab widgets`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(
                    text = "Home",
                    isClickable = true,
                    bounds = Rect(0, 2250, 270, 2400),
                    className = "android.widget.TabWidget",
                ),
            ),
        )
        val output = screen.formatForLLM()
        assertTrue(output.contains("tab"))
    }

    @Test
    fun `icon buttons should get icon-btn type hint`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(
                    text = "unlabeled",
                    isClickable = true,
                    bounds = Rect(10, 10, 80, 80),
                    className = "android.widget.ImageButton",
                ),
            ),
        )
        val output = screen.formatForLLM()
        assertTrue(output.contains("icon-btn"))
    }

    // == positionHint (tested indirectly) =====================================

    @Test
    fun `unlabeled elements should get fine-grained position hints`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(
                    contentDescription = null,
                    text = null,
                    isClickable = true,
                    bounds = Rect(10, 10, 80, 80),
                    className = "android.widget.ImageButton",
                ),
                makeElement(
                    contentDescription = null,
                    text = null,
                    isClickable = true,
                    bounds = Rect(950, 10, 1050, 80),
                    className = "android.widget.ImageButton",
                ),
            ),
        )
        val output = screen.formatForLLM()
        assertTrue(
            "Should have left position hint for far-left element",
            output.contains("far-left") || output.contains("(left)"),
        )
        assertTrue(
            "Should have right position hint for far-right element",
            output.contains("far-right") || output.contains("(right)"),
        )
    }

    // == buttonTypeHint (tested indirectly) ===================================

    @Test
    fun `checkbox elements should get checkbox type hint`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(
                    text = "Remember me",
                    isClickable = true,
                    isCheckable = true,
                    bounds = Rect(100, 600, 400, 700),
                    className = "android.widget.CheckBox",
                ),
            ),
        )
        val output = screen.formatForLLM()
        assertTrue(output.contains("checkbox"))
    }

    @Test
    fun `checked checkbox should show CHECKED annotation`() {
        val screen = makeScreen(
            elements = listOf(
                UIElement(
                    id = null,
                    className = "android.widget.CheckBox",
                    text = "Accept terms",
                    contentDescription = null,
                    isClickable = true,
                    isEditable = false,
                    isScrollable = false,
                    isCheckable = true,
                    isChecked = true,
                    isFocused = false,
                    isEnabled = true,
                    bounds = Rect(100, 600, 400, 700),
                    childCount = 0,
                ),
            ),
        )
        val output = screen.formatForLLM()
        assertTrue(output.contains("CHECKED"))
    }

    @Test
    fun `disabled button should show DISABLED annotation`() {
        val screen = makeScreen(
            elements = listOf(
                UIElement(
                    id = null,
                    className = "android.widget.Button",
                    text = "Submit",
                    contentDescription = null,
                    isClickable = true,
                    isEditable = false,
                    isScrollable = false,
                    isCheckable = false,
                    isChecked = null,
                    isFocused = false,
                    isEnabled = false,
                    bounds = Rect(100, 600, 300, 700),
                    childCount = 0,
                ),
            ),
        )
        val output = screen.formatForLLM()
        assertTrue(output.contains("DISABLED"))
    }

    // == fingerprint with real Rect ==========================================

    @Test
    fun `fingerprint should differ when element positions change significantly`() {
        val screen1 = makeScreen(
            elements = listOf(
                makeElement(text = "Button", bounds = Rect(100, 100, 300, 200)),
            ),
        )
        val screen2 = makeScreen(
            elements = listOf(
                makeElement(text = "Button", bounds = Rect(800, 800, 1000, 900)),
            ),
        )
        // Positions are bucketed by centerX / 50, so large position changes produce different fingerprints
        // screen1 center = (200,150), bucket = 200/50 = 4
        // screen2 center = (900,850), bucket = 900/50 = 18
        val fp1 = screen1.fingerprint()
        val fp2 = screen2.fingerprint()
        assertTrue("Fingerprints should differ for different positions", fp1 != fp2)
    }

    // == toMaskedSummary with real Rect =======================================

    @Test
    fun `toMaskedSummary should correctly count interactive elements`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "Button", isClickable = true, bounds = Rect(100, 500, 300, 600)),
                makeElement(text = "Input", isEditable = true, bounds = Rect(100, 700, 800, 800)),
                makeElement(text = "Label", bounds = Rect(100, 900, 300, 1000)),
                makeElement(text = "Scroller", isScrollable = true, bounds = Rect(0, 400, 1080, 2000)),
            ),
        )
        val summary = screen.toMaskedSummary("Navigating")
        assertTrue(summary.contains("4 elements"))
        assertTrue(summary.contains("2 interactive"))
    }

    // == Bottom navigation detection with real coordinates ====================

    @Test
    fun `formatForLLM should detect bottom navigation tabs with real coordinates`() {
        val screenHeight = 2400
        val bottomY = (screenHeight * 7 / 8) + 10  // Just past the bottom bar threshold
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "Home", isClickable = true, bounds = Rect(0, bottomY, 270, screenHeight)),
                makeElement(text = "Search", isClickable = true, bounds = Rect(270, bottomY, 540, screenHeight)),
                makeElement(text = "Cart", isClickable = true, bounds = Rect(540, bottomY, 810, screenHeight)),
                makeElement(text = "Account", isClickable = true, bounds = Rect(810, bottomY, 1080, screenHeight)),
                // Content item above
                makeElement(text = "Welcome", bounds = Rect(100, 400, 500, 500)),
            ),
        )
        val output = screen.formatForLLM()
        assertTrue("Should have NAVIGATION section", output.contains("NAVIGATION"))
        assertTrue("Account should be in navigation", output.contains("Account"))
    }

    // == Content area with scrollable indicator ===============================

    @Test
    fun `formatForLLM should show scrollable indicator for scrollable content`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "List item 1", isClickable = true, bounds = Rect(0, 400, 1080, 500)),
                makeElement(text = "List container", isScrollable = true, bounds = Rect(0, 400, 1080, 2000)),
            ),
        )
        val output = screen.formatForLLM()
        assertTrue(output.contains("scrollable"))
    }

    // == Long label truncation ================================================

    @Test
    fun `formatForLLM should truncate long element labels`() {
        val longText = "A".repeat(200)
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = longText, isClickable = true, bounds = Rect(100, 600, 800, 700)),
            ),
        )
        val output = screen.formatForLLM()
        // Label should be truncated to 120 chars (117 + "...")
        assertFalse(output.contains("A".repeat(200)))
        assertTrue(output.contains("A".repeat(117)))
        assertTrue(output.contains("..."))
    }

    // == Screen with bottom navigation tab count detection ====================

    @Test
    fun `formatForLLM should detect screen with bottom navigation pattern`() {
        val bottomY = 2200
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "Tab1", isClickable = true, bounds = Rect(0, bottomY, 360, 2400)),
                makeElement(text = "Tab2", isClickable = true, bounds = Rect(360, bottomY, 720, 2400)),
                makeElement(text = "Tab3", isClickable = true, bounds = Rect(720, bottomY, 1080, 2400)),
                makeElement(text = "Content text", bounds = Rect(100, 600, 500, 700)),
            ),
        )
        val output = screen.formatForLLM()
        assertTrue(output.contains("NAVIGATION"))
    }

    // -- Helpers ---------------------------------------------------------------

    private fun makeScreen(
        packageName: String = "com.example.app",
        activityName: String? = "com.example.app.TestActivity",
        elements: List<UIElement> = emptyList(),
    ): ScreenState {
        return ScreenState(
            packageName = packageName,
            activityName = activityName,
            elements = elements,
            focusedElement = null,
            timestamp = System.currentTimeMillis(),
        )
    }

    private fun makeElement(
        text: String? = null,
        contentDescription: String? = null,
        isClickable: Boolean = false,
        isEditable: Boolean = false,
        isScrollable: Boolean = false,
        isCheckable: Boolean = false,
        bounds: Rect = Rect(0, 0, 100, 100),
        className: String = "android.widget.TextView",
    ): UIElement {
        return UIElement(
            id = null,
            className = className,
            text = text,
            contentDescription = contentDescription,
            isClickable = isClickable,
            isEditable = isEditable,
            isScrollable = isScrollable,
            isCheckable = isCheckable,
            isChecked = null,
            isFocused = false,
            isEnabled = true,
            bounds = bounds,
            childCount = 0,
        )
    }
}
