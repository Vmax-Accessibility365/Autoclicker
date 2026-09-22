package com.example.autoclicker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handles MediaProjection screen capture.
 *
 * This class only captures screen frames.
 * Target 1 -> Target 2 -> Target 3 sequence control
 * remains inside AutoClickService / TextSequence.
 */
class ScreenCaptureManager(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val onProjectionStopped: () -> Unit
) {

    companion object {
        private const val TAG = "ScreenCaptureManager"
        private const val VIRTUAL_DISPLAY_NAME =
            "AutoClickScreenCapture"

        private const val IMAGE_READER_MAX_IMAGES = 2
    }

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private val handlerThread =
        HandlerThread("ScreenCaptureThread").apply {
            start()
        }

    private val backgroundHandler =
        Handler(handlerThread.looper)

    private val width: Int
    private val height: Int
    private val density: Int

    private val stopped =
        AtomicBoolean(false)

    private val projectionCallback =
        object : MediaProjection.Callback() {

            override fun onStop() {

                Log.w(
                    TAG,
                    "MediaProjection stopped by system or user"
                )

                if (
                    stopped.compareAndSet(
                        false,
                        true
                    )
                ) {

                    releaseResources()

                    onProjectionStopped()
                }
            }
        }

    init {

        val metrics =
            context.resources.displayMetrics

        width =
            metrics.widthPixels

        height =
            metrics.heightPixels

        density =
            metrics.densityDpi

        /*
         * Android 14+ requires the callback to be
         * registered before createVirtualDisplay().
         */
        mediaProjection.registerCallback(
            projectionCallback,
            backgroundHandler
        )
    }

    fun start() {

        if (stopped.get()) {

            Log.w(
                TAG,
                "start() ignored: manager already stopped"
            )

            return
        }

        if (virtualDisplay != null) {
            return
        }

        try {

            if (
                width <= 0 ||
                height <= 0 ||
                density <= 0
            ) {

                Log.e(
                    TAG,
                    "Invalid display metrics: " +
                        "${width}x${height}, density=$density"
                )

                releaseResources()
                return
            }

            val reader =
                ImageReader.newInstance(
                    width,
                    height,
                    PixelFormat.RGBA_8888,
                    IMAGE_READER_MAX_IMAGES
                )

            imageReader = reader

            virtualDisplay =
                mediaProjection.createVirtualDisplay(
                    VIRTUAL_DISPLAY_NAME,
                    width,
                    height,
                    density,
                    DisplayManager
                        .VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    backgroundHandler
                )

            Log.d(
                TAG,
                "VirtualDisplay created successfully " +
                    "($width x $height)"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to start ScreenCaptureManager",
                e
            )

            stop()
        }
    }

    /**
     * Returns the newest available screen frame.
     *
     * Returns null when a new frame is not available yet.
     */
    fun captureBitmap(): Bitmap? {

        if (stopped.get()) {
            return null
        }

        val reader =
            imageReader ?: return null

        val image =
            try {

                /*
                 * acquireLatestImage() intentionally used here.
                 *
                 * Old frames are discarded so the OCR loop
                 * works with the newest available screen.
                 */
                reader.acquireLatestImage()

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "acquireLatestImage() failed",
                    e
                )

                null
            } ?: return null

        return convertImageToBitmap(image)
    }

    private fun convertImageToBitmap(
        image: Image
    ): Bitmap? {

        return try {

            if (image.planes.isEmpty()) {
                return null
            }

            val plane =
                image.planes[0]

            val buffer =
                plane.buffer

            val pixelStride =
                plane.pixelStride

            val rowStride =
                plane.rowStride

            if (pixelStride <= 0) {

                Log.w(
                    TAG,
                    "Invalid pixelStride: $pixelStride"
                )

                return null
            }

            val rowPadding =
                rowStride -
                    pixelStride * width

            if (rowPadding < 0) {

                Log.w(
                    TAG,
                    "Invalid rowPadding: $rowPadding"
                )

                return null
            }

            val paddedWidth =
                width +
                    rowPadding / pixelStride

            val bitmap =
                Bitmap.createBitmap(
                    paddedWidth,
                    height,
                    Bitmap.Config.ARGB_8888
                )

            buffer.rewind()

            bitmap.copyPixelsFromBuffer(
                buffer
            )

            if (paddedWidth == width) {

                bitmap

            } else {

                val cropped =
                    Bitmap.createBitmap(
                        bitmap,
                        0,
                        0,
                        width,
                        height
                    )

                bitmap.recycle()

                cropped
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Bitmap conversion failed",
                e
            )

            null

        } finally {

            /*
             * Image must always be closed, including
             * conversion failures.
             */
            image.close()
        }
    }

    fun stop() {

        if (
            !stopped.compareAndSet(
                false,
                true
            )
        ) {
            return
        }

        releaseResources()
    }

    private fun releaseResources() {

        try {

            virtualDisplay?.release()

        } catch (e: Exception) {

            Log.w(
                TAG,
                "VirtualDisplay release failed",
                e
            )

        } finally {

            virtualDisplay = null
        }

        try {

            imageReader?.close()

        } catch (e: Exception) {

            Log.w(
                TAG,
                "ImageReader close failed",
                e
            )

        } finally {

            imageReader = null
        }

        try {

            mediaProjection.unregisterCallback(
                projectionCallback
            )

        } catch (_: Exception) {
            // Already unregistered or projection already stopped.
        }

        handlerThread.quitSafely()
    }
}
