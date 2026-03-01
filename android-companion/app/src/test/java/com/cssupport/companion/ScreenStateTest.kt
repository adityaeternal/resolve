package com.cssupport.companion

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for ScreenState and UIElement pure logic methods.
 *
 * NOTE: android.graphics.Rect is a stub in unit tests (isReturnDefaultValues = true).
 * All Rect fields and methods return 0. Therefore, tests that depend on spatial
 * properties (element filtering by bounds, positioning in top/bottom bars) cannot
 * be validated here. Those would require Robolectric or instrumented tests.
 *
 * We test the behaviors that DON'T depend on Rect internals:
 * - fingerprint: depends on text labels + Rect.centerX() (always 0, but consistent)
 * - newElementLabels / removedElementLabels: depends only on text/contentDescription
 * - elementLabels: depends only on text/contentDescription
 * - toMaskedSummary: depends on element counts and boolean properties
 * - formatForLLM: package name, activity name, empty screen handling
 * - getElementById: returns null for invalid indices
 */
class ScreenStateTest {

    // ── fingerprint ──────────────────────────────────────────────────────

    @Test
    fun `fingerprint should be consistent for identical screens`() {
        val screen1 = makeScreen(
            packageName = "com.example.app",
            activityName = "MainActivity",
            elements = listOf(
                makeElement(text = "Home"),
                makeElement(text = "Orders"),
            ),
        )
        val screen2 = makeScreen(
            packageName = "com.example.app",
            activityName = "MainActivity",
            elements = listOf(
                makeElement(text = "Home"),
                makeElement(text = "Orders"),
            ),
        )

        assertEquals(screen1.fingerprint(), screen2.fingerprint())
    }

    @Test
    fun `fingerprint should differ when package changes`() {
        val screen1 = makeScreen(packageName = "com.app.one", elements = listOf(makeElement(text = "Hello")))
        val screen2 = makeScreen(packageName = "com.app.two", elements = listOf(makeElement(text = "Hello")))

        assertNotEquals(screen1.fingerprint(), screen2.fingerprint())
    }

    @Test
    fun `fingerprint should differ when element text changes`() {
        val screen1 = makeScreen(elements = listOf(makeElement(text = "Submit")))
        val screen2 = makeScreen(elements = listOf(makeElement(text = "Cancel")))

        assertNotEquals(screen1.fingerprint(), screen2.fingerprint())
    }

    @Test
    fun `fingerprint should differ when activity changes`() {
        val screen1 = makeScreen(activityName = "HomeActivity", elements = listOf(makeElement(text = "A")))
        val screen2 = makeScreen(activityName = "OrdersActivity", elements = listOf(makeElement(text = "A")))

        assertNotEquals(screen1.fingerprint(), screen2.fingerprint())
    }

    @Test
    fun `fingerprint should handle empty element list`() {
        val screen = makeScreen(elements = emptyList())
        val fp = screen.fingerprint()
        assertTrue(fp.isNotBlank())
    }

    @Test
    fun `fingerprint should include contentDescription in hash`() {
        val screen1 = makeScreen(elements = listOf(makeElement(contentDescription = "Profile")))
        val screen2 = makeScreen(elements = listOf(makeElement(contentDescription = "Settings")))

        assertNotEquals(screen1.fingerprint(), screen2.fingerprint())
    }

    @Test
    fun `fingerprint should produce same result on repeated calls`() {
        val screen = makeScreen(
            packageName = "com.test",
            elements = listOf(makeElement(text = "Button A"), makeElement(text = "Button B")),
        )
        // Fingerprint should be deterministic
        assertEquals(screen.fingerprint(), screen.fingerprint())
    }

    // ── getElementById ──────────────────────────────────────────────────

    @Test
    fun `getElementById should return null for out-of-range index`() {
        val screen = makeScreen(
            elements = listOf(makeElement(text = "Only one", isClickable = true)),
        )

        assertNull(screen.getElementById(999))
        assertNull(screen.getElementById(0))
        assertNull(screen.getElementById(-1))
    }

    // ── newElementLabels / removedElementLabels ─────────────────────────

    @Test
    fun `newElementLabels should return labels present in this screen but not in other`() {
        val previous = makeScreen(elements = listOf(
            makeElement(text = "Home"),
            makeElement(text = "Orders"),
        ))
        val current = makeScreen(elements = listOf(
            makeElement(text = "Home"),
            makeElement(text = "Orders"),
            makeElement(text = "Chat with us"),
        ))

        val newLabels = current.newElementLabels(previous)
        assertTrue(newLabels.contains("Chat with us"))
        assertFalse(newLabels.contains("Home"))
    }

    @Test
    fun `newElementLabels should return empty list when other is null`() {
        val screen = makeScreen(elements = listOf(makeElement(text = "Hello")))
        assertEquals(emptyList<String>(), screen.newElementLabels(null))
    }

    @Test
    fun `newElementLabels should return empty list when screens are identical`() {
        val elements = listOf(
            makeElement(text = "Home"),
            makeElement(text = "Orders"),
        )
        val screen1 = makeScreen(elements = elements)
        val screen2 = makeScreen(elements = elements)

        assertTrue(screen1.newElementLabels(screen2).isEmpty())
    }

    @Test
    fun `newElementLabels should return at most 10 labels`() {
        val previous = makeScreen(elements = emptyList())
        val current = makeScreen(elements = (1..20).map { makeElement(text = "Item $it") })

        val newLabels = current.newElementLabels(previous)
        assertTrue(newLabels.size <= 10)
    }

    @Test
    fun `removedElementLabels should return labels present in other but missing from this`() {
        val previous = makeScreen(elements = listOf(
            makeElement(text = "Home"),
            makeElement(text = "Loading..."),
            makeElement(text = "Orders"),
        ))
        val current = makeScreen(elements = listOf(
            makeElement(text = "Home"),
            makeElement(text = "Orders"),
        ))

        val removed = current.removedElementLabels(previous)
        assertTrue(removed.contains("Loading..."))
        assertFalse(removed.contains("Home"))
    }

    @Test
    fun `removedElementLabels should return empty list when other is null`() {
        val screen = makeScreen(elements = listOf(makeElement(text = "Hello")))
        assertEquals(emptyList<String>(), screen.removedElementLabels(null))
    }

    @Test
    fun `removedElementLabels should return at most 10 labels`() {
        val previous = makeScreen(elements = (1..20).map { makeElement(text = "Old $it") })
        val current = makeScreen(elements = emptyList())

        val removed = current.removedElementLabels(previous)
        assertTrue(removed.size <= 10)
    }

    @Test
    fun `newElementLabels should truncate long labels to 40 chars`() {
        val longLabel = "A".repeat(80)
        val previous = makeScreen(elements = emptyList())
        val current = makeScreen(elements = listOf(makeElement(text = longLabel)))

        val newLabels = current.newElementLabels(previous)
        assertTrue(newLabels.isNotEmpty())
        assertTrue(newLabels[0].length <= 40)
    }

    @Test
    fun `newElementLabels should consider contentDescription as fallback`() {
        val previous = makeScreen(elements = listOf(makeElement(contentDescription = "Back button")))
        val current = makeScreen(elements = listOf(
            makeElement(contentDescription = "Back button"),
            makeElement(contentDescription = "Help icon"),
        ))

        val newLabels = current.newElementLabels(previous)
        assertTrue(newLabels.contains("Help icon"))
    }

    @Test
    fun `newElementLabels should skip elements with null text and null contentDescription`() {
        val previous = makeScreen(elements = emptyList())
        val current = makeScreen(elements = listOf(
            makeElement(text = null, contentDescription = null),
            makeElement(text = "Visible"),
        ))

        val newLabels = current.newElementLabels(previous)
        assertEquals(1, newLabels.size)
        assertEquals("Visible", newLabels[0])
    }

    // ── elementLabels ───────────────────────────────────────────────────

    @Test
    fun `elementLabels should return set of all element labels`() {
        val screen = makeScreen(elements = listOf(
            makeElement(text = "Home"),
            makeElement(text = "Orders"),
            makeElement(contentDescription = "Profile icon"),
            makeElement(text = null, contentDescription = null),
        ))

        val labels = screen.elementLabels()
        assertTrue(labels.contains("Home"))
        assertTrue(labels.contains("Orders"))
        assertTrue(labels.contains("Profile icon"))
        assertEquals(3, labels.size)
    }

    @Test
    fun `elementLabels should return empty set for empty screen`() {
        val screen = makeScreen(elements = emptyList())
        assertTrue(screen.elementLabels().isEmpty())
    }

    @Test
    fun `elementLabels should deduplicate identical labels`() {
        val screen = makeScreen(elements = listOf(
            makeElement(text = "Button"),
            makeElement(text = "Button"), // duplicate
        ))

        val labels = screen.elementLabels()
        assertEquals(1, labels.size)
    }

    @Test
    fun `elementLabels should truncate labels to 40 chars`() {
        val longLabel = "X".repeat(100)
        val screen = makeScreen(elements = listOf(makeElement(text = longLabel)))

        val labels = screen.elementLabels()
        assertTrue(labels.first().length <= 40)
    }

    // ── toMaskedSummary ─────────────────────────────────────────────────

    @Test
    fun `toMaskedSummary should include package name and element counts`() {
        val screen = makeScreen(
            packageName = "com.example.app",
            activityName = "com.example.app.HomeActivity",
            elements = listOf(
                makeElement(text = "Home", isClickable = true),
                makeElement(text = "Info", isClickable = false),
                makeElement(text = "Input", isEditable = true),
            ),
        )

        val summary = screen.toMaskedSummary("Navigating to support")
        assertTrue(summary.contains("com.example.app"))
        assertTrue(summary.contains("HomeActivity"))
        assertTrue(summary.contains("3 elements"))
        assertTrue(summary.contains("2 interactive"))
        assertTrue(summary.contains("Navigating to support"))
    }

    @Test
    fun `toMaskedSummary should handle null activity name`() {
        val screen = makeScreen(activityName = null, elements = emptyList())
        val summary = screen.toMaskedSummary("In chat")
        assertTrue(summary.contains("unknown"))
    }

    @Test
    fun `toMaskedSummary should count only clickable and editable as interactive`() {
        val screen = makeScreen(
            elements = listOf(
                makeElement(text = "Clickable", isClickable = true),
                makeElement(text = "Editable", isEditable = true),
                makeElement(text = "Scrollable", isScrollable = true), // NOT interactive
                makeElement(text = "Static text"),
            ),
        )

        val summary = screen.toMaskedSummary("phase")
        assertTrue(summary.contains("4 elements"))
        assertTrue(summary.contains("2 interactive"))
    }

    // ── formatForLLM ────────────────────────────────────────────────────

    @Test
    fun `formatForLLM should include package name in output`() {
        val screen = makeScreen(
            packageName = "in.swiggy.android",
            elements = listOf(makeElement(text = "Home", isClickable = true)),
        )

        val output = screen.formatForLLM()
        assertTrue(output.contains("Package: in.swiggy.android"))
    }

    @Test
    fun `formatForLLM should show empty screen when no elements`() {
        val screen = makeScreen(elements = emptyList())

        val output = screen.formatForLLM()
        assertTrue(output.contains("empty screen"))
    }

    @Test
    fun `formatForLLM should include activity simple name`() {
        val screen = makeScreen(
            activityName = "com.example.app.OrderDetailActivity",
            elements = listOf(makeElement(text = "Help", isClickable = true)),
        )

        val output = screen.formatForLLM()
        assertTrue(output.contains("Screen: OrderDetailActivity"))
    }

    @Test
    fun `formatForLLM should handle null activity name`() {
        val screen = makeScreen(
            activityName = null,
            elements = listOf(makeElement(text = "Hello", isClickable = true)),
        )

        val output = screen.formatForLLM()
        assertTrue(output.contains("Package:"))
        // Should not crash and should not contain "Screen:" line
        assertFalse(output.contains("Screen:"))
    }

    // ── UIElement data class ────────────────────────────────────────────

    @Test
    fun `UIElement should store all non-Rect properties correctly`() {
        val element = UIElement(
            id = "com.example:id/button_submit",
            className = "android.widget.Button",
            text = "Submit Order",
            contentDescription = "Submit your order",
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            isCheckable = false,
            isChecked = null,
            isFocused = false,
            isEnabled = true,
            bounds = Rect(),
            childCount = 0,
        )

        assertEquals("com.example:id/button_submit", element.id)
        assertEquals("android.widget.Button", element.className)
        assertEquals("Submit Order", element.text)
        assertEquals("Submit your order", element.contentDescription)
        assertTrue(element.isClickable)
        assertFalse(element.isEditable)
        assertFalse(element.isScrollable)
        assertFalse(element.isCheckable)
        assertNull(element.isChecked)
        assertFalse(element.isFocused)
        assertTrue(element.isEnabled)
        assertEquals(0, element.childCount)
    }

    @Test
    fun `UIElement copy should preserve all fields`() {
        val original = UIElement(
            id = "my_id",
            className = "Button",
            text = "OK",
            contentDescription = "Press OK",
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            isCheckable = true,
            isChecked = true,
            isFocused = false,
            isEnabled = true,
            bounds = Rect(),
            childCount = 2,
        )
        val copy = original.copy(text = "Cancel")
        assertEquals("Cancel", copy.text)
        assertEquals("my_id", copy.id)
        assertEquals("Press OK", copy.contentDescription)
        assertTrue(copy.isClickable)
        assertTrue(copy.isCheckable)
        assertEquals(true, copy.isChecked)
        assertEquals(2, copy.childCount)
    }

    @Test
    fun `ClickableElement should store all fields`() {
        val element = ClickableElement(
            text = "Next",
            contentDescription = "Go to next page",
            className = "android.widget.Button",
            viewId = "btn_next",
            bounds = Rect(),
        )

        assertEquals("Next", element.text)
        assertEquals("Go to next page", element.contentDescription)
        assertEquals("android.widget.Button", element.className)
        assertEquals("btn_next", element.viewId)
    }

    // ── Helpers ──────────────────────────────────────────────────────────

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
        isChecked: Boolean? = null,
        className: String = "android.widget.TextView",
        id: String? = null,
    ): UIElement {
        return UIElement(
            id = id,
            className = className,
            text = text,
            contentDescription = contentDescription,
            isClickable = isClickable,
            isEditable = isEditable,
            isScrollable = isScrollable,
            isCheckable = isCheckable,
            isChecked = isChecked,
            isFocused = false,
            isEnabled = true,
            bounds = Rect(),
            childCount = 0,
        )
    }
}
