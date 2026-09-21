package com.example.autoclicker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that owns the MediaProjection and runs the
 * capture -> OCR -> match -> click sequence.
 *
 * Sequence:
 *
 * Target 1
 *    -> found -> click -> Target 2
 *    -> not found -> check Target 1 again
 *
 * Target 2
 *    -> found -> click -> Target 3
 *    -> not found -> check Target 2 again
 *
 * Continues until the final target.
 *
 * After the final target, the sequence stops.
 */
class AutoClickService : Service() {

    companion object {
        private const val TAG = "AutoClickService"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_TARGET_TEXT = "extra_target_text"
        const val EXTRA_INTERVAL_MS = "extra_interval_ms"
        const val EXTRA_RETRY = "extra_retry"

        const val ACTION_STOP =
            "com.example.autoclicker.action.STOP"

        private const val NOTIFICATION_CHANNEL_ID =
            "auto_clicker_channel"

        private const val NOTIFICATION_ID = 1001

        private const val MIN_INTERVAL_MS = 50L
        private const val DEFAULT_INTERVAL_MS = 50L

        var isRunning: Boolean = false
            private set
    }

    private var captureManager: ScreenCaptureManager? = null

    private val ocrHelper = OcrHelper()

    private var intervalMs: Long = DEFAULT_INTERVAL_MS

    // Kept for compatibility with the existing Intent/API.
    private var retryIfNotFound: Boolean = true

    private lateinit var sequence: TextSequence

    private val serviceJob = Job()

    private val serviceScope =
        CoroutineScope(
            Dispatchers.Default + serviceJob
        )

    private var loopJob: Job? = null

    private val isStopping =
        AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent?.action == ACTION_STOP) {
            stopSelfCleanly()
            return START_NOT_STICKY
        }

        isStopping.set(false)

        startForegroundCompat()

        /*
         * Ignore duplicate start commands while
         * the current sequence is running.
         */
        if (loopJob?.isActive == true) {
            return START_STICKY
        }

        val resultCode =
            intent?.getIntExtra(
                EXTRA_RESULT_CODE,
                -1
            ) ?: -1

        val resultData: Intent? =
            intent?.getParcelableExtra(
                EXTRA_RESULT_DATA
            )

        val targetText =
            intent?.getStringExtra(
                EXTRA_TARGET_TEXT
            ).orEmpty()

        val requestedInterval =
            intent?.getLongExtra(
                EXTRA_INTERVAL_MS,
                DEFAULT_INTERVAL_MS
            ) ?: DEFAULT_INTERVAL_MS

        intervalMs =
            requestedInterval.coerceAtLeast(
                MIN_INTERVAL_MS
            )

        retryIfNotFound =
            intent?.getBooleanExtra(
                EXTRA_RETRY,
                true
            ) ?: true

        /*
         * IMPORTANT:
         *
         * TextSequence controls:
         *
         * Target 1 -> Target 2 -> Target 3 -> ...
         *
         * No loop parameter is passed here because
         * the current TextSequence constructor does
         * not define a "loop" parameter.
         *
         * The sequence ends naturally after the
         * final target.
         */
        sequence = TextSequence(
            targetText
        )

        if (
            resultData == null ||
            resultCode != android.app.Activity.RESULT_OK
        ) {
            Log.e(
                TAG,
                "Missing/invalid MediaProjection result, stopping."
            )

            stopSelfCleanly()
            return START_NOT_STICKY
        }

        if (!ClickAccessibilityService.isReady()) {
            Log.e(
                TAG,
                "Accessibility service not enabled, stopping."
            )

            stopSelfCleanly()
            return START_NOT_STICKY
        }

        val projectionManager =
            getSystemService(
                MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

        val projection: MediaProjection =
            projectionManager.getMediaProjection(
                resultCode,
                resultData
            )

        captureManager =
            ScreenCaptureManager(
                context = this,
                mediaProjection = projection,
                onProjectionStopped = {
                    stopSelfCleanly()
                }
            ).also {
                it.start()
            }

        isRunning = true

        startLoop()

        return START_STICKY
    }

    private fun startForegroundCompat() {

        val notification =
            buildNotification()

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.Q
        ) {

            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )

        } else {

            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    private fun startLoop() {

        loopJob =
            serviceScope.launch {

                while (isActive) {

                    /*
                     * Only the current target is searched.
                     */
                    val target =
                        sequence.currentTarget

                    if (target == null) {

                        Log.d(
                            TAG,
                            "Sequence completed. No target remaining."
                        )

                        break
                    }

                    val frameStart =
                        System.currentTimeMillis()

                    val bitmap =
                        captureManager
                            ?.captureBitmap()

                    if (bitmap != null) {

                        try {

                            val blocks =
                                ocrHelper
                                    .recognizeTextSuspend(
                                        bitmap
                                    )

                            /*
                             * Search ONLY for current target.
                             */
                            val match =
                                TextMatcher.findMatch(
                                    blocks,
                                    target
                                )

                            if (match != null) {

                                val cx =
                                    match.boundingBox
                                        .exactCenterX()

                                val cy =
                                    match.boundingBox
                                        .exactCenterY()

                                val clickService =
                                    ClickAccessibilityService
                                        .instance

                                if (clickService != null) {

                                    /*
                                     * Click current target first.
                                     * Only then advance.
                                     */
                                    clickService.clickAt(
                                        cx,
                                        cy
                                    )

                                    Log.d(
                                        TAG,
                                        "Clicked '$target' at ($cx, $cy)"
                                    )

                                    sequence.advance()

                                } else {

                                    /*
                                     * Do NOT advance if the
                                     * accessibility service is gone.
                                     */
                                    Log.w(
                                        TAG,
                                        "Accessibility service unavailable; " +
                                            "keeping target '$target'."
                                    )
                                }

                            } else {

                                /*
                                 * Target not found.
                                 *
                                 * Stay on the SAME target.
                                 */
                                Log.d(
                                    TAG,
                                    "'$target' not found; checking same target again."
                                )
                            }

                        } finally {

                            bitmap.recycle()
                        }

                    } else {

                        /*
                         * No frame yet.
                         * Same target remains active.
                         */
                        Log.d(
                            TAG,
                            "No frame available; retrying '$target'."
                        )
                    }

                    val elapsed =
                        System.currentTimeMillis() -
                            frameStart

                    val wait =
                        (
                            intervalMs - elapsed
                        ).coerceAtLeast(0L)

                    if (wait > 0L) {
                        delay(wait)
                    }
                }

                withContext(Dispatchers.Main) {
                    stopSelfCleanly()
                }
            }
    }

    private fun stopSelfCleanly() {

        if (
            !isStopping.compareAndSet(
                false,
                true
            )
        ) {
            return
        }

        isRunning = false

        loopJob?.cancel()
        loopJob = null

        captureManager?.stop()
        captureManager = null

        stopForeground(
            STOP_FOREGROUND_REMOVE
        )

        stopSelf()
    }

    private fun createNotificationChannel() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getString(
                        R.string.notification_channel_name
                    ),
                    NotificationManager
                        .IMPORTANCE_LOW
                )

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager.createNotificationChannel(
                channel
            )
        }
    }

    private fun buildNotification(): Notification {

        val stopIntent =
            Intent(
                this,
                AutoClickService::class.java
            ).apply {
                action = ACTION_STOP
            }

        val stopPendingIntent =
            PendingIntent.getService(
                this,
                0,
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE
            )

        return NotificationCompat.Builder(
            this,
            NOTIFICATION_CHANNEL_ID
        )
            .setContentTitle(
                getString(R.string.app_name)
            )
            .setContentText(
                getString(R.string.status_running)
            )
            .setSmallIcon(
                android.R.drawable.ic_menu_search
            )
            .setOngoing(true)
            .addAction(
                0,
                getString(R.string.btn_stop),
                stopPendingIntent
            )
            .build()
    }

    override fun onDestroy() {

        isRunning = false

        loopJob?.cancel()
        loopJob = null

        captureManager?.stop()
        captureManager = null

        ocrHelper.close()

        serviceJob.cancel()

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null
}
