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
 * Accessibility service responsible only for executing clicks
 * and providing accessibility-node based click support.
 *
 * Sequence control is handled by AutoClickService / TextSequence.
 *
 * Flow:
 *
 * Target found
 *      ↓
 * clickAt()
 *      ↓
 * Gesture completed
 *      ↓
 * callback(true)
 *      ↓
 * AutoClickService advances sequence
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
        // OCR-based sequence does not depend on
        // accessibility events.
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

        Log.d(
            TAG,
            "Accessibility service disconnected"
        )

        return super.onUnbind(intent)
    }

    /**
     * Performs one coordinate-based tap.
     *
     * IMPORTANT:
     *
     * The Boolean returned by this function tells whether
     * dispatchGesture() accepted the gesture.
     *
     * The callback Boolean tells whether the gesture actually
     * completed successfully.
     *
     * AutoClickService must advance TextSequence ONLY from:
     *
     * onResult(true)
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

        if (
            x < 0f ||
            y < 0f
        ) {

            Log.w(
                TAG,
                "clickAt() ignored: invalid coordinates ($x, $y)"
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

                    /*
                     * This is the ONLY place where the caller
                     * receives a successful click result.
                     */
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
                    "dispatchGesture() returned false at ($x, $y)"
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
     * Searches all available accessibility windows
     * for the supplied text.
     *
     * This is independent from the OCR sequence.
     */
    fun findAndClickInAnyWindow(
        targetText: String
    ): Boolean {

        if (targetText.isBlank()) {
            return false
        }

        return try {

            val activeWindows:
                List<AccessibilityWindowInfo>? =
                windows

            if (activeWindows.isNullOrEmpty()) {

                Log.d(
                    TAG,
                    "No active accessibility windows."
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

                            if (
                                performSafeClick(
                                    target
                                )
                            ) {

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
     * Recursively searches a node tree.
     *
     * Matches:
     * - node.text
     * - node.contentDescription
     */
    private fun findNodeByText(
        node: AccessibilityNodeInfo?,
        targetText: String
    ): AccessibilityNodeInfo? {

        if (
            node == null ||
            targetText.isBlank()
        ) {
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

            return AccessibilityNodeInfo.obtain(
                node
            )
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
     * Performs ACTION_CLICK on the node itself
     * or one of its clickable ancestors.
     */
    private fun performSafeClick(
        node: AccessibilityNodeInfo
    ): Boolean {

        var target:
            AccessibilityNodeInfo? = node

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

            target?.performAction(
                AccessibilityNodeInfo.ACTION_CLICK
            ) ?: false

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
