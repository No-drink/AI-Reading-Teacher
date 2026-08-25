package com.joey.aireadingteacher.capture

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureManager(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val listener: Listener,
) {
    private val captureThread = HandlerThread("TeacherScreenCapture")
    private val stopping = AtomicBoolean(false)
    private val frameProcessor = FrameProcessor(File(context.filesDir, DEBUG_DIRECTORY))
    private val changeDetector = ScreenChangeDetector()

    private lateinit var captureHandler: Handler
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var captureWidth = 0
    private var captureHeight = 0
    private var lastProcessedAtElapsedMs: Long? = null
    private var pendingStableImage: android.media.Image? = null
    private val densityDpi = context.resources.configuration.densityDpi
    private val stabilityRunnable = Runnable { publishPendingStableFrame() }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MEDIA_PROJECTION_STOPPED")
            releaseCaptureResources()
            if (!stopping.get()) {
                listener.onProjectionStopped()
            }
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
            if (width <= 0 || height <= 0 || stopping.get()) return
            captureHandler.post { resize(width, height) }
        }
    }

    fun start() {
        check(!captureThread.isAlive) { "Screen capture is already started" }
        captureThread.start()
        captureHandler = Handler(captureThread.looper)

        val (width, height) = initialCaptureSize()
        captureWidth = width
        captureHeight = height
        mediaProjection.registerCallback(projectionCallback, captureHandler)
        createImageReader(width, height)
        virtualDisplay = mediaProjection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            captureHandler,
        ) ?: error("Android did not create a virtual display")

        Log.i(TAG, "SCREEN_CAPTURE_STARTED ${width}x$height density=$densityDpi")
        listener.onCaptureStarted()
    }

    fun stop() {
        if (!stopping.compareAndSet(false, true)) return
        Log.i(TAG, "SCREEN_CAPTURE_STOPPING")
        mediaProjection.unregisterCallback(projectionCallback)
        releaseCaptureResources()
        mediaProjection.stop()
        if (captureThread.isAlive) {
            captureThread.quitSafely()
        }
    }

    private fun createImageReader(width: Int, height: Int) {
        imageReader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            MAX_IMAGES,
        ).apply {
            setOnImageAvailableListener({ reader -> processAvailableFrame(reader) }, captureHandler)
        }
    }

    private fun processAvailableFrame(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (exception: IllegalStateException) {
            Log.w(TAG, "Unable to acquire a screen frame", exception)
            null
        } ?: return

        var retainedForStability = false
        try {
            val now = SystemClock.elapsedRealtime()
            val lastProcessed = lastProcessedAtElapsedMs
            if (
                lastProcessed != null &&
                now - lastProcessed < ScreenChangeDetector.CAPTURE_INTERVAL_MS
            ) {
                return
            }
            lastProcessedAtElapsedMs = now

            val signature = frameProcessor.sample(
                image = image,
                sampleWidth = ScreenChangeDetector.SAMPLE_WIDTH,
                sampleHeight = ScreenChangeDetector.SAMPLE_HEIGHT,
            )
            val result = changeDetector.observe(signature, now)
            when (result.event) {
                DetectionEvent.SCREEN_CHANGED -> {
                    retainStableCandidate(image)
                    retainedForStability = true
                    scheduleStabilityCheck(result.remainingStabilityMs)
                    Log.i(TAG, "SCREEN_CHANGED difference=${result.difference}")
                    Log.i(
                        TAG,
                        "WAITING_FOR_STABILITY delayMs=${result.remainingStabilityMs}",
                    )
                }

                DetectionEvent.WAITING_FOR_STABILITY -> {
                    retainStableCandidate(image)
                    retainedForStability = true
                    scheduleStabilityCheck(result.remainingStabilityMs)
                    Log.d(
                        TAG,
                        "WAITING_FOR_STABILITY remainingMs=${result.remainingStabilityMs}",
                    )
                }

                DetectionEvent.STABLE_SCREEN -> {
                    clearPendingStableImage()
                    captureHandler.removeCallbacks(stabilityRunnable)
                    saveAndNotify(image)
                }

                DetectionEvent.UNCHANGED -> Unit
            }
        } catch (exception: Exception) {
            listener.onCaptureError("Failed to process a screen frame.", exception)
        } finally {
            if (!retainedForStability) image.close()
        }
    }

    private fun retainStableCandidate(image: android.media.Image) {
        pendingStableImage?.close()
        pendingStableImage = image
    }

    private fun scheduleStabilityCheck(remainingMs: Long) {
        captureHandler.removeCallbacks(stabilityRunnable)
        captureHandler.postDelayed(stabilityRunnable, remainingMs.coerceAtLeast(1L))
    }

    private fun publishPendingStableFrame() {
        if (stopping.get()) return
        val result = try {
            changeDetector.poll(SystemClock.elapsedRealtime())
        } catch (exception: Exception) {
            listener.onCaptureError("Failed to evaluate screen stability.", exception)
            return
        }
        if (result.event == DetectionEvent.WAITING_FOR_STABILITY) {
            scheduleStabilityCheck(result.remainingStabilityMs)
            return
        }
        if (result.event != DetectionEvent.STABLE_SCREEN) return

        val image = pendingStableImage ?: return
        pendingStableImage = null
        try {
            saveAndNotify(image)
        } catch (exception: Exception) {
            listener.onCaptureError("Failed to save a stable screen frame.", exception)
        } finally {
            image.close()
        }
    }

    private fun saveAndNotify(image: android.media.Image) {
        val outputFile = frameProcessor.save(image)
        Log.i(TAG, "STABLE_SCREEN_CAPTURED ${outputFile.absolutePath}")
        listener.onFrameSaved(outputFile.absolutePath)
    }

    private fun clearPendingStableImage() {
        pendingStableImage?.close()
        pendingStableImage = null
    }

    private fun resize(width: Int, height: Int) {
        val display = virtualDisplay ?: return
        if (width == captureWidth && height == captureHeight) return

        captureWidth = width
        captureHeight = height
        Log.i(TAG, "CAPTURE_CONTENT_RESIZED ${width}x$height")

        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null

        createImageReader(width, height)
        display.resize(width, height, densityDpi)
        display.surface = imageReader?.surface
    }

    private fun releaseCaptureResources() {
        if (::captureHandler.isInitialized) captureHandler.removeCallbacks(stabilityRunnable)
        clearPendingStableImage()
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
    }

    private fun initialCaptureSize(): Pair<Int, Int> {
        val windowManager = context.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            bounds.width().coerceAtLeast(1) to bounds.height().coerceAtLeast(1)
        } else {
            @Suppress("DEPRECATION")
            val metrics = context.resources.displayMetrics
            metrics.widthPixels.coerceAtLeast(1) to metrics.heightPixels.coerceAtLeast(1)
        }
    }

    interface Listener {
        fun onCaptureStarted()
        fun onFrameSaved(path: String)
        fun onProjectionStopped()
        fun onCaptureError(message: String, cause: Throwable? = null)
    }

    companion object {
        private const val TAG = "AIReadingTeacher"
        private const val VIRTUAL_DISPLAY_NAME = "AIReadingTeacherCapture"
        private const val DEBUG_DIRECTORY = "debug_capture"
        // One image may be retained for the stability timer; two more slots let
        // acquireLatestImage discard stale queued frames instead of replaying them.
        private const val MAX_IMAGES = 3
    }
}
