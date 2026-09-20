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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that owns the MediaProjection and runs the
 * capture -> OCR -> match -> click loop described in the design doc.
 *
 * Intent extras expected on the FIRST start command:
 *   EXTRA_RESULT_CODE : Int    - from MediaProjectionManager permission result
 *   EXTRA_RESULT_DATA  : Intent - from MediaProjectionManager permission result
 *   EXTRA_TARGET_TEXT  : String - comma separated target text sequence
 *   EXTRA_INTERVAL_MS  : Long
 *   EXTRA_RETRY        : Boolean
 *
 * Action ACTION_STOP stops the loop and the service.
 */
class AutoClickService : Service() {

    companion object {
        private const val TAG = "AutoClickService"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_TARGET_TEXT = "extra_target_text"
        const val EXTRA_INTERVAL_MS = "extra_interval_ms"
        const val EXTRA_RETRY = "extra_retry"

        const val ACTION_STOP = "com.example.autoclicker.action.STOP"

        private const val NOTIFICATION_CHANNEL_ID = "auto_clicker_channel"
        private const val NOTIFICATION_ID = 1001

        var isRunning: Boolean = false
            private set
    }

    private var captureManager: ScreenCaptureManager? = null
    private val ocrHelper = OcrHelper()

    private var intervalMs: Long = 1000L
    private var retryIfNotFound: Boolean = true
    private lateinit var sequence: TextSequence

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var loopJob: Job? = null

    // Guards against stopSelfCleanly() running twice: once from an explicit
    // ACTION_STOP, and again from the loop's own normal-exit path if the
    // job happened to finish its isActive check right as cancel() landed.
    private val isStopping = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfCleanly()
            return START_NOT_STICKY
        }

        // A fresh (non-stop) start command means we're not stopping anymore.
        isStopping.set(false)

        // Foreground status must be established immediately, before we
        // touch MediaProjection (Android 12+ requirement).
        startForegroundCompat()

        if (loopJob?.isActive == true) {
            // Already running; ignore duplicate start.
            return START_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        val targetText = intent?.getStringExtra(EXTRA_TARGET_TEXT).orEmpty()
        intervalMs = intent?.getLongExtra(EXTRA_INTERVAL_MS, 1000L) ?: 1000L
        retryIfNotFound = intent?.getBooleanExtra(EXTRA_RETRY, true) ?: true
        sequence = TextSequence(targetText, loop = true)

        if (resultData == null || resultCode != android.app.Activity.RESULT_OK) {
            Log.e(TAG, "Missing/invalid MediaProjection result, stopping.")
            stopSelfCleanly()
            return START_NOT_STICKY
        }

        if (!ClickAccessibilityService.isReady()) {
            Log.e(TAG, "Accessibility service not enabled, stopping.")
            stopSelfCleanly()
            return START_NOT_STICKY
        }

        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection: MediaProjection =
            projectionManager.getMediaProjection(resultCode, resultData)

        captureManager = ScreenCaptureManager(
            context = this,
            mediaProjection = projection,
            onProjectionStopped = { stopSelfCleanly() }
        ).also { it.start() }

        isRunning = true
        startLoop()

        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startLoop() {
        loopJob = serviceScope.launch {
            while (isActive) {
                val target = sequence.currentTarget
                if (target == null) {
                    Log.w(TAG, "No target text configured, stopping.")
                    break
                }

                val frameStart = System.currentTimeMillis()

                val bitmap = captureManager?.captureBitmap()
                if (bitmap != null) {
                    val blocks = ocrHelper.recognizeTextSuspend(bitmap)
                    val match = TextMatcher.findMatch(blocks, target)

                    if (match != null) {
                        val cx = match.boundingBox.exactCenterX()
                        val cy = match.boundingBox.exactCenterY()
                        ClickAccessibilityService.instance?.clickAt(cx, cy)
                        Log.d(TAG, "Clicked '$target' at ($cx, $cy)")
                        sequence.advance()
                    } else {
                        Log.d(TAG, "'$target' not found this frame.")
                        if (!retryIfNotFound) {
                            break
                        }
                    }
                    bitmap.recycle()
                } else {
                    Log.d(TAG, "No frame available yet.")
                }

                val elapsed = System.currentTimeMillis() - frameStart
                val wait = intervalMs - elapsed
                if (wait > 0) delay(wait)
            }

            withContextMain { stopSelfCleanly() }
        }
    }

    /** Ensures stopSelfCleanly() (which touches system services) runs off Dispatchers.Default cleanly. */
    private suspend fun withContextMain(block: () -> Unit) {
        kotlinx.coroutines.withContext(Dispatchers.Main) { block() }
    }

    private fun stopSelfCleanly() {
        if (!isStopping.compareAndSet(false, true)) return // already stopping/stopped
        isRunning = false
        loopJob?.cancel()
        captureManager?.stop()
        captureManager = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, AutoClickService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.status_running))
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .addAction(0, getString(R.string.btn_stop), stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        loopJob?.cancel()
        captureManager?.stop()
        ocrHelper.close()
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
