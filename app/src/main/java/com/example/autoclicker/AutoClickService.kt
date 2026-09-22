package com.example.autoclicker

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.IBinder
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * OCR text block with its screen bounding box.
 */
data class OcrTextBlock(
    val text: String,
    val boundingBox: Rect?
)

/**
 * Holds MediaProjection so it can be passed from Activity
 * to AutoClickService.
 */
object MediaProjectionHolder {

    var mediaProjection:
        android.media.projection.MediaProjection? = null
}

/**
 * Main automation service.
 *
 * Sequence:
 *
 * Target 1
 *    ↓
 * OCR
 *    ↓
 * Not found → search Target 1 again
 *    ↓
 * Found
 *    ↓
 * Click
 *    ↓
 * Gesture completed successfully
 *    ↓
 * Target 2
 *    ↓
 * ...
 *    ↓
 * Last target completed
 *    ↓
 * FINISHED
 *
 * IMPORTANT:
 *
 * TextSequence controls target order.
 * TextMatcher only finds the current target.
 * ClickAccessibilityService performs the click.
 */
class AutoClickService : Service() {

    companion object {

        private const val TAG =
            "AutoClickService"

        const val ACTION_START =
            "com.example.autoclicker.START"

        const val ACTION_STOP =
            "com.example.autoclicker.STOP"

        const val EXTRA_TARGETS =
            "extra_targets"

        private const val OCR_RETRY_DELAY_MS =
            300L

        private const val SCAN_DELAY_MS =
            600L
    }

    private val serviceScope =
        CoroutineScope(
            Dispatchers.Default +
                SupervisorJob()
        )

    private var loopJob: Job? = null

    private var screenCaptureManager:
        ScreenCaptureManager? = null

    private var textSequence:
        TextSequence? = null

    private val textRecognizer =
        TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )

    /**
     * Prevents multiple click operations from
     * being started at the same time.
     */
    private val isProcessing =
        AtomicBoolean(false)

    override fun onBind(
        intent: Intent?
    ): IBinder? {
        return null
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_START -> {

                val rawTargets =
                    intent.getStringExtra(
                        EXTRA_TARGETS
                    ) ?: ""

                startAutomation(
                    rawTargets
                )
            }

            ACTION_STOP -> {

                stopAutomation()
            }
        }

        return START_NOT_STICKY
    }

    /**
     * Starts a completely new automation run.
     */
    private fun startAutomation(
        rawTargets: String
    ) {

        Log.d(
            TAG,
            "Starting automation: $rawTargets"
        )

        /*
         * Stop any previous run before starting
         * a new sequence.
         */
        loopJob?.cancel()

        screenCaptureManager?.stop()
        screenCaptureManager = null

        isProcessing.set(false)

        val sequence =
            TextSequence(
                rawTargets
            )

        textSequence =
            sequence

        if (sequence.isFinished) {

            Log.w(
                TAG,
                "No valid targets supplied."
            )

            stopAutomation()
            return
        }

        val projection =
            MediaProjectionHolder
                .mediaProjection

        if (projection == null) {

            Log.e(
                TAG,
                "MediaProjection is null."
            )

            stopAutomation()
            return
        }

        screenCaptureManager =
            ScreenCaptureManager(
                this,
                projection
            ) {

                Log.w(
                    TAG,
                    "MediaProjection stopped."
                )

                stopAutomation()
            }

        screenCaptureManager?.start()

        startLoop()
    }

    /**
     * Main OCR/search loop.
     *
     * IMPORTANT:
     *
     * currentTarget is read every cycle.
     *
     * Therefore:
     *
     * not found → same target
     *
     * successful click → sequence.advance()
     * → next cycle reads next target
     */
    private fun startLoop() {

        loopJob?.cancel()

        loopJob =
            serviceScope.launch {

                while (isActive) {

                    val sequence =
                        textSequence

                    val captureManager =
                        screenCaptureManager

                    /*
                     * Nothing to process.
                     */
                    if (
                        sequence == null ||
                        captureManager == null
                    ) {
                        break
                    }

                    /*
                     * Last target has already been
                     * completed.
                     */
                    if (sequence.isFinished) {

                        Log.d(
                            TAG,
                            "FINISHED: all targets completed."
                        )

                        stopAutomation()
                        break
                    }

                    /*
                     * A previous click is still being
                     * processed.
                     */
                    if (isProcessing.get()) {

                        delay(
                            OCR_RETRY_DELAY_MS
                        )

                        continue
                    }

                    /*
                     * Read the current target.
                     */
                    val currentTarget =
                        sequence.currentTarget

                    if (
                        currentTarget == null
                    ) {

                        delay(
                            OCR_RETRY_DELAY_MS
                        )

                        continue
                    }

                    Log.d(
                        TAG,
                        "Searching current target: '$currentTarget'"
                    )

                    /*
                     * Capture newest available frame.
                     */
                    val bitmap =
                        captureManager
                            .captureBitmap()

                    if (bitmap == null) {

                        delay(
                            OCR_RETRY_DELAY_MS
                        )

                        continue
                    }

                    /*
                     * OCR and click are awaited here.
                     *
                     * This prevents the loop from starting
                     * another OCR cycle while the current
                     * click callback is still pending.
                     */
                    processOcrAndMatch(
                        bitmap,
                        currentTarget,
                        sequence
                    )

                    /*
                     * Small delay before the next
                     * screen scan.
                     */
                    delay(
                        SCAN_DELAY_MS
                    )
                }
            }
    }

    /**
     * Runs OCR for the current target.
     *
     * No sequence advancement happens here merely because
     * the target was found.
     *
     * Sequence advances ONLY after clickAt() reports
     * successful gesture completion.
     */
    private suspend fun processOcrAndMatch(
        bitmap: Bitmap,
        currentTarget: String,
        sequence: TextSequence
    ) {

        try {

            val result =
                suspendCancellableCoroutine<
                    com.google.mlkit.vision.text.Text
                > { continuation ->

                    val image =
                        InputImage.fromBitmap(
                            bitmap,
                            0
                        )

                    textRecognizer
                        .process(image)
                        .addOnSuccessListener { result ->

                            if (
                                continuation.isActive
                            ) {

                                continuation.resume(
                                    result
                                )
                            }
                        }
                        .addOnFailureListener { error ->

                            Log.e(
                                TAG,
                                "OCR processing failed",
                                error
                            )

                            if (
                                continuation.isActive
                            ) {

                                continuation.cancel(
                                    error
                                )
                            }
                        }
                }

            /*
             * Convert ML Kit text into the format
             * expected by TextMatcher.
             */
            val blocks =
                mutableListOf<OcrTextBlock>()

            for (
                block in result.textBlocks
            ) {

                for (
                    line in block.lines
                ) {

                    blocks.add(
                        OcrTextBlock(
                            text =
                                line.text,
                            boundingBox =
                                line.boundingBox
                        )
                    )
                }
            }

            /*
             * Search ONLY for the current target.
             *
             * TextMatcher does not control sequence order.
             */
            val match =
                TextMatcher.findMatch(
                    blocks,
                    currentTarget
                )

            if (
                match == null ||
                match.boundingBox == null
            ) {

                /*
                 * Target not found.
                 *
                 * IMPORTANT:
                 * No sequence.advance().
                 *
                 * The next loop iteration searches
                 * the SAME target again.
                 */
                Log.d(
                    TAG,
                    "Target '$currentTarget' not found. Retrying."
                )

                return
            }

            val rect =
                match.boundingBox

            val centerX =
                rect.exactCenterX()

            val centerY =
                rect.exactCenterY()

            Log.d(
                TAG,
                "Target '$currentTarget' found " +
                    "at ($centerX, $centerY)"
            )

            val a11yService =
                ClickAccessibilityService
                    .instance

            if (a11yService == null) {

                Log.w(
                    TAG,
                    "Accessibility service is not ready. Retrying current target."
                )

                return
            }

            /*
             * Lock processing before starting the gesture.
             */
            isProcessing.set(true)

            try {

                /*
                 * WAIT for the actual gesture result.
                 */
                val clickSuccess =
                    suspendCancellableCoroutine<Boolean> {
                        continuation ->

                        a11yService.clickAt(
                            centerX,
                            centerY
                        ) { success ->

                            if (
                                continuation.isActive
                            ) {

                                continuation.resume(
                                    success
                                )
                            }
                        }

                        continuation.invokeOnCancellation {
                            /*
                             * The accessibility service owns
                             * the gesture lifecycle.
                             *
                             * No sequence advancement occurs
                             * when this coroutine is cancelled.
                             */
                        }
                    }

                if (clickSuccess) {

                    /*
                     * CRITICAL:
                     *
                     * clickAt() returns true here only after
                     * GestureResultCallback.onCompleted().
                     *
                     * Therefore advancing the sequence now is safe.
                     */
                    sequence.advance()

                    Log.d(
                        TAG,
                        "Click completed successfully. " +
                            "Advanced from '$currentTarget'. " +
                            "Next target='${sequence.currentTarget}'"
                    )

                    if (
                        sequence.isFinished
                    ) {

                        Log.d(
                            TAG,
                            "FINISHED: last target clicked successfully."
                        )
                    }

                } else {

                    /*
                     * Click failed/cancelled.
                     *
                     * DO NOT advance.
                     *
                     * The same target will be searched again.
                     */
                    Log.w(
                        TAG,
                        "Click failed/cancelled. " +
                            "Keeping current target '$currentTarget'."
                    )
                }

            } finally {

                isProcessing.set(false)
            }

        } catch (
            cancellation: CancellationException
        ) {

            /*
             * Coroutine cancellation is not treated as
             * successful target completion.
             */
            Log.d(
                TAG,
                "OCR/click operation cancelled."
            )

            isProcessing.set(false)

        } catch (e: Exception) {

            Log.e(
                TAG,
                "processOcrAndMatch failed",
                e
            )

            /*
             * Any unexpected error keeps the current
             * sequence target unchanged.
             */
            isProcessing.set(false)

        } finally {

            /*
             * This bitmap belongs to this OCR cycle.
             */
            try {
                bitmap.recycle()
            } catch (_: Exception) {
                // Bitmap already released.
            }
        }
    }

    /**
     * Stops the current automation run.
     */
    private fun stopAutomation() {

        Log.d(
            TAG,
            "Stopping automation service."
        )

        loopJob?.cancel()
        loopJob = null

        isProcessing.set(false)

        screenCaptureManager?.stop()
        screenCaptureManager = null

        textSequence = null

        stopSelf()
    }

    override fun onDestroy() {

        Log.d(
            TAG,
            "AutoClickService destroyed."
        )

        loopJob?.cancel()
        loopJob = null

        screenCaptureManager?.stop()
        screenCaptureManager = null

        textRecognizer.close()

        serviceScope.cancel()

        super.onDestroy()
    }
}
