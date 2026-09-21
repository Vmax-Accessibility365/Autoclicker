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
 * Accessibility service responsible for:
 *
 * 1. Coordinate-based tapping through dispatchGesture()
 * 2. Accessibility-node text searching/clicking
 *
 * Sequence control is NOT handled here.
 * AutoClickService controls:
 *
 * Target 1 -> Target 2 -> Target 3 -> ...
 */
class ClickAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ClickA11yService"

        private const val GESTURE_START_DELAY_MS = 0L
        private const val GESTURE_DURATION_MS = 80L

        @Volatile
        var instance: ClickAccessibilityService? = null
            private set

        fun isReady(): Boolean {
            return instance != null
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        instance = this

        Log.d(
            TAG,
            "Accessibility service connected"
        )
    }

    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {
        // Accessibility events are not required for the
        // OCR-based sequence.
    }

    override fun onInterrupt() {
        Log.w(
            TAG,
            "Accessibility service interrupted"
        )
    }

    override fun onUnbind(
        intent: Intent?
    ): Boolean {

        instance = null

        return super.onUnbind(intent)
    }

    /**
     * Performs one coordinate-based tap.
     *
     * Returns true when dispatchGesture() successfully
     * accepts the gesture.
     *
     * The optional callback reports the final gesture result.
     */
    fun clickAt(
        x: Float,
        y: Float,
        onResult: ((Boolean) -> Unit)? = null
    ): Boolean {

        if (!isReady()) {
            Log.w(
                TAG,
                "clickAt() ignored: service is not ready"
            )

            onResult?.invoke(false)

            return false
        }

        val path =
            Path().apply {
                moveTo(x, y)
            }

        val stroke =
            GestureDescription.StrokeDescription(
                path,
                GESTURE_START_DELAY_MS,
                GESTURE_DURATION_MS
            )

        val gesture =
            GestureDescription.Builder()
                .addStroke(stroke)
                .build()

        val callback =
            object : GestureResultCallback() {

                override fun onCompleted(
                    gestureDescription: GestureDescription?
                ) {
                    super.onCompleted(
                        gestureDescription
                    )

                    Log.d(
                        TAG,
                        "Gesture completed at ($x, $y)"
                    )

                    onResult?.invoke(true)
                }

                override fun onCancelled(
                    gestureDescription: GestureDescription?
                ) {
                    super.onCancelled(
                        gestureDescription
                    )

                    Log.w(
                        TAG,
                        "Gesture cancelled at ($x, $y)"
                    )

                    onResult?.invoke(false)
                }
            }

        return try {

            val dispatched =
                dispatchGesture(
                    gesture,
                    callback,
                    null
                )

            if (!dispatched) {

                Log.w(
                    TAG,
                    "dispatchGesture() returned false for ($x, $y)"
                )

                onResult?.invoke(false)
            }

            dispatched

        } catch (e: Exception) {

            Log.e(
                TAG,
                "clickAt() failed",
                e
            )

            onResult?.invoke(false)

            false
        }
    }

    /**
     * Searches all available accessibility windows for
     * target text and performs ACTION_CLICK when possible.
     *
     * This method is independent from the OCR sequence.
     */
    fun findAndClickInAnyWindow(
        targetText: String
    ): Boolean {

        if (targetText.isBlank()) {
            return false
        }

        return try {

            val activeWindows:
                List<AccessibilityWindowInfo>? = windows

            if (activeWindows.isNullOrEmpty()) {

                Log.d(
                    TAG,
                    "No active windows available."
                )

                return false
            }

            for (window in activeWindows) {

                val root =
                    try {
                        window.root
                    } catch (e: Exception) {
                        null
                    } ?: continue

                try {

                    val target =
                        findNodeByText(
                            root,
                            targetText
                        )

                    if (target != null) {

                        try {

                            val clicked =
                                performSafeClick(target)

                            if (clicked) {

                                Log.d(
                                    TAG,
                                    "Accessibility node clicked: '$targetText'"
                                )

                                return true
                            }

                        } finally {
                            target.recycle()
                        }
                    }

                } finally {
                    root.recycle()
                }
            }

            false

        } catch (e: Exception) {

            Log.e(
                TAG,
                "findAndClickInAnyWindow failed",
                e
            )

            false
        }
    }

    /**
     * Depth-first search.
     *
     * Matches either:
     * - node.text
     * - node.contentDescription
     *
     * Matching is case-insensitive.
     */
    private fun findNodeByText(
        node: AccessibilityNodeInfo?,
        targetText: String
    ): AccessibilityNodeInfo? {

        if (node == null || targetText.isBlank()) {
            return null
        }

        val nodeText =
            node.text?.toString()

        val nodeDescription =
            node.contentDescription?.toString()

        if (
            nodeText?.contains(
                targetText,
                ignoreCase = true
            ) == true ||
            nodeDescription?.contains(
                targetText,
                ignoreCase = true
            ) == true
        ) {
            return AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {

            val child =
                try {
                    node.getChild(i)
                } catch (e: Exception) {
                    null
                } ?: continue

            val result =
                try {
                    findNodeByText(
                        child,
                        targetText
                    )
                } finally {
                    child.recycle()
                }

            if (result != null) {
                return result
            }
        }

        return null
    }

    /**
     * Performs ACTION_CLICK on the node itself or,
     * when necessary, on a clickable ancestor.
     */
    private fun performSafeClick(
        node: AccessibilityNodeInfo
    ): Boolean {

        var target: AccessibilityNodeInfo? = node
        var depth = 0

        return try {

            while (
                target != null &&
                !target.isClickable &&
                depth < 8
            ) {

                val parent =
                    try {
                        target.parent
                    } catch (e: Exception) {
                        null
                    }

                if (
                    target !== node
                ) {
                    target.recycle()
                }

                target = parent
                depth++
            }

            val success =
                target?.performAction(
                    AccessibilityNodeInfo.ACTION_CLICK
                ) ?: false

            success

        } catch (e: Exception) {

            Log.e(
                TAG,
                "performSafeClick failed",
                e
            )

            false

        } finally {

            if (
                target != null &&
                target !== node
            ) {
                target.recycle()
            }
        }
    }
}

लेकिन एक महत्वपूर्ण बात

"ClickAccessibilityService.kt" अब final है, लेकिन आपके "AutoClickService.kt" में अभी यह है:

clickService.clickAt(cx, cy)
sequence.advance()

"clickAt()" gesture को asynchronously execute करता है। इसलिए अगला Target बहुत जल्दी शुरू न हो, इसके लिए बाद में "AutoClickService.kt" में छोटा सा coordination सुधार करना उचित होगा—जिसमें click successfully complete होने के बाद ही "sequence.advance()" होगा।

अभी बाकी दो files भी verify करनी हैं:

ScreenCaptureManager.kt
ClickAccessibilityService.kt

"ClickAccessibilityService.kt" अभी आपने दे दिया और ऊपर final कर दिया है। अब अगली file "ScreenCaptureManager.kt" भेजिए।
