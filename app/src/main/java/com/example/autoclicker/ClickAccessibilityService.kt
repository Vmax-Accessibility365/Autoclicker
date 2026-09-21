package com.example.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Combined Accessibility Service:
 * 1. Coordinates/Gesture-based tapping (clickAt via dispatchGesture)
 * 2. Window and Node text searching/clicking (findAndClickInAnyWindow)
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
        // Optional: Yahan events handle kiye ja sakte hain agar zaroorat ho.
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    /**
     * 1. Dispatches a single tap gesture at the given screen coordinates.
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

    /**
     * 2. Saari active windows (foreground app + floating popups/dropdowns)
     * scan karta hai aur [targetText] wale node par ACTION_CLICK karta hai.
     */
    fun findAndClickInAnyWindow(targetText: String): Boolean {
        return try {
            val activeWindows: List<AccessibilityWindowInfo>? = windows
            if (activeWindows.isNullOrEmpty()) {
                Log.d(TAG, "No active windows available.")
                return false
            }

            for (window in activeWindows) {
                val root: AccessibilityNodeInfo? = window.root ?: continue
                try {
                    val target = findNodeByText(root, targetText)
                    if (target != null) {
                        val clicked = performSafeClick(target)
                        target.recycle()
                        if (clicked) {
                            Log.d(TAG, "Clicked '$targetText' (window type=${window.type})")
                            return true
                        }
                    }
                } finally {
                    root?.recycle()
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "findAndClickInAnyWindow failed", e)
            false
        }
    }

    /** Depth-first search: text ya contentDescription match (case-insensitive). */
    private fun findNodeByText(node: AccessibilityNodeInfo?, targetText: String): AccessibilityNodeInfo? {
        if (node == null) return null

        val nodeText = node.text?.toString()
        val nodeDesc = node.contentDescription?.toString()

        if (nodeText?.contains(targetText, ignoreCase = true) == true ||
            nodeDesc?.contains(targetText, ignoreCase = true) == true
        ) {
            return AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            } ?: continue

            val result = findNodeByText(child, targetText)
            if (result != null) {
                child.recycle()
                return result
            }
            child.recycle()
        }
        return null
    }

    /** ACTION_CLICK sirf clickable node (ya uske clickable ancestor) par perform karta hai. */
    private fun performSafeClick(node: AccessibilityNodeInfo): Boolean {
        return try {
            var target: AccessibilityNodeInfo? = node
            var depth = 0
            while (target != null && !target.isClickable && depth < 8) {
                val parent = target.parent
                if (target !== node) target.recycle()
                target = parent
                depth++
            }

            val success = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
            if (target != null && target !== node) target.recycle()
            success
        } catch (e: Exception) {
            Log.e(TAG, "performSafeClick failed", e)
            false
        }
    }
}
