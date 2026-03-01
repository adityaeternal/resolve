package com.cssupport.companion

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log

/**
 * Executes gesture-based actions (tap, swipe) via the AccessibilityService API.
 *
 * Extracted from AccessibilityEngine to keep the engine focused on screen
 * parsing and high-level actions while this class handles low-level gestures.
 */
class GestureExecutor(private val service: AccessibilityService) {

    private val tag = "GestureExecutor"

    /**
     * Perform a tap gesture at the given screen coordinates.
     * Uses GestureDescription API (requires API 24+, canPerformGestures=true).
     */
    fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        var result = false
        val latch = java.util.concurrent.CountDownLatch(1)

        service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    result = true
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    result = false
                    latch.countDown()
                }
            },
            null,
        )

        latch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
        Log.d(tag, "tapAt($x, $y): result=$result")
        return result
    }

    /**
     * Perform a swipe gesture from (startX, startY) to (endX, endY).
     */
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        var result = false
        val latch = java.util.concurrent.CountDownLatch(1)

        service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    result = true
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    result = false
                    latch.countDown()
                }
            },
            null,
        )

        latch.await(durationMs + 2000, java.util.concurrent.TimeUnit.MILLISECONDS)
        return result
    }

    /** Press the global Back button. */
    fun pressBack(): Boolean =
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    /** Press the global Home button. */
    fun pressHome(): Boolean =
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

    /** Open the Recents / app switcher. */
    fun pressRecents(): Boolean =
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)

    /** Open the notification shade. */
    fun openNotifications(): Boolean =
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
}
