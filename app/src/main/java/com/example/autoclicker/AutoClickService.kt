package com.example.autoclicker

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.IBinder
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
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

        @Volatile
        var isRunning: Boolean = false
            private set

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

        isRunning = true

        startLoop()
    }

    /**
     * Main OCR/search loop.
     *
     * Not found:
     *     same target is searched again.
     *
     * Successful click:
     *     sequence.advance()
     *     next target becomes active.
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

                    if (
                        sequence == null ||
                        captureManager == null
                    ) {
                        break
                    }

                    if (sequence.isFinished) {

                        Log.d(
                            TAG,
                            "FINISHED: all targets completed."
                        )

                        stopAutomation()
                        break
                    }

                    if (isProcessing.get()) {

                        delay(
                            OCR_RETRY_DELAY_MS
                        )

                        continue
                    }

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

                    val bitmap =
                        captureManager
                            .captureBitmap()

                    if (bitmap == null) {

                        delay(
                            OCR_RETRY_DELAY_MS
                        )

                        continue
                    }

                    processOcrAndMatch(
                        bitmap,
                        currentTarget,
                        sequence
                    )

                    delay(
                        SCAN_DELAY_MS
                    )
                }
            }
    }

    /**
     * Runs OCR for the current target.
     *
     * Sequence advances ONLY after the click gesture
     * reports successful completion.
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
                            text = line.text,
                            boundingBox = line.boundingBox
                        )
                    )
                }
            }

            val match =
                TextMatcher.findMatch(
                    blocks,
                    currentTarget
                )

            if (
                match == null ||
                match.boundingBox == null
            ) {

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
                    "Accessibility service is not ready. " +
                        "Retrying current target."
                )

                return
            }

            isProcessing.set(true)

            try {

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
                            // Gesture lifecycle remains owned
                            // by ClickAccessibilityService.
                        }
                    }

                if (clickSuccess) {

                    /*
                     * Advance ONLY after successful gesture
                     * completion.
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
                     * Failed click:
                     * keep the same target.
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
             * Unexpected error:
             * current target remains unchanged.
             */
            isProcessing.set(false)

        } finally {

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

        isRunning = false

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

        isRunning = false

        loopJob?.cancel()
        loopJob = null

        screenCaptureManager?.stop()
        screenCaptureManager = null

        textRecognizer.close()

        serviceScope.cancel()

        super.onDestroy()
    }
}
