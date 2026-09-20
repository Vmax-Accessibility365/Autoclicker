package com.example.autoclicker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * Wraps MediaProjection + ImageReader to grab screenshots on demand.
 *
 * IMPORTANT (Android 14 / API 34): MediaProjection now requires a
 * Callback to be registered BEFORE createVirtualDisplay() is called,
 * otherwise the system may tear down the projection. We register one
 * here and stop cleanly when it fires.
 */
class ScreenCaptureManager(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val onProjectionStopped: () -> Unit = {}
) {
    private val TAG = "ScreenCaptureManager"

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private val handlerThread = HandlerThread("ScreenCaptureThread").apply { start() }
    private val backgroundHandler = Handler(handlerThread.looper)

    private val width: Int
    private val height: Int
    private val density: Int

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection stopped by system/user")
            stop()
            onProjectionStopped()
        }
    }

    init {
        val metrics = context.resources.displayMetrics
        width = metrics.widthPixels
        height = metrics.heightPixels
        density = metrics.densityDpi

        // Required on modern Android before creating the virtual display.
        mediaProjection.registerCallback(projectionCallback, backgroundHandler)
    }

    fun start() {
        if (virtualDisplay != null) return

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "AutoClickerCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            backgroundHandler
        )
    }

    /**
     * Synchronously grabs the most recent frame. Returns null if no
     * frame is available yet (e.g. called immediately after start()).
     */
    fun captureBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            Log.e(TAG, "acquireLatestImage failed", e)
            null
        } ?: return null

        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width

            val paddedWidth = width + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            if (paddedWidth == width) {
                bitmap
            } else {
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                bitmap.recycle()
                cropped
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bitmap conversion failed", e)
            null
        } finally {
            image.close()
        }
    }

    fun stop() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        try {
            mediaProjection.unregisterCallback(projectionCallback)
        } catch (_: Exception) {
        }
        handlerThread.quitSafely()
    }
}
