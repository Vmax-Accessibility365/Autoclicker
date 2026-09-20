package com.example.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Performs the actual on-screen tap using dispatchGesture().
 * Must be enabled manually by the user under
 * Settings -> Accessibility -> Text Auto Clicker -> ON
 * (Android does not allow an app to enable this for itself).
 */
class ClickAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ClickA11yService"

        @Volatile
        var instance: ClickAccessibilityService? = null
            private set

        fun isReady(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed for pure gesture dispatch; left empty on purpose.
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    /**
     * Dispatches a single tap gesture at the given screen coordinates.
     * @param onResult called with true/false depending on whether the
     * gesture was accepted and completed by the system.
     */
    fun clickAt(x: Float, y: Float, onResult: ((Boolean) -> Unit)? = null) {
        val path = Path().apply { moveTo(x, y) }

        val stroke = GestureDescription.StrokeDescription(path, 0L, 80L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                onResult?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.w(TAG, "Gesture cancelled at ($x, $y)")
                onResult?.invoke(false)
            }
        }

        val dispatched = dispatchGesture(gesture, callback, null)
        if (!dispatched) {
            Log.w(TAG, "dispatchGesture() returned false for ($x, $y)")
            onResult?.invoke(false)
        }
    }
}
