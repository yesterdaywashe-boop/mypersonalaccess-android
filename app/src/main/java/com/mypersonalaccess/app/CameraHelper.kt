package com.mypersonalaccess.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executor

/**
 * Headless camera capture for the foreground service.
 *
 * CameraX needs a LifecycleOwner, so the service owns a tiny one that is
 * moved to RESUMED while capturing. Only ImageCapture is bound (no preview),
 * so this works without any visible Activity.
 *
 * Frames are downscaled to ~480px wide and JPEG-compressed, then Base64
 * encoded — a frame is typically 20–40 KB, small enough for a Realtime
 * broadcast. Nothing is kept: the temp file is deleted after each frame.
 */
class CameraHelper(private val context: Context) {

    private val lifecycleOwner = ServiceLifecycleOwner()
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var running = false
    private var executor: Executor? = null
    private val handler = Handler(Looper.getMainLooper())
    private var captureRunnable: Runnable? = null
    private var onFrame: ((String) -> Unit)? = null

    private class ServiceLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)

        init {
            registry.currentState = Lifecycle.State.CREATED
        }

        fun start() {
            registry.currentState = Lifecycle.State.STARTED
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
        }

        override val lifecycle: Lifecycle get() = registry
    }

    fun isRunning(): Boolean = running

    fun start(front: Boolean, onFrame: (String) -> Unit) {
        if (running) return
        this.onFrame = onFrame
        lensFacing = if (front) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        running = true
        lifecycleOwner.start()
        executor = ContextCompat.getMainExecutor(context)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                cameraProvider = providerFuture.get()
                bind()
                scheduleNext()
            } catch (e: Exception) {
                running = false
            }
        }, executor)
    }

    private fun bind() {
        val provider = cameraProvider ?: return
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, imageCapture)
        } catch (e: Exception) {
            running = false
        }
    }

    fun flip() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        bind()
    }

    private fun scheduleNext() {
        captureRunnable = object : Runnable {
            override fun run() {
                if (!running) return
                captureOnce()
                handler.postDelayed(this, 2500)
            }
        }
        handler.postDelayed(captureRunnable!!, 300)
    }

    private fun captureOnce() {
        val capture = imageCapture ?: return
        val tmp = File(context.cacheDir, "mpa_frame_${System.currentTimeMillis()}.jpg")
        val output = ImageCapture.OutputFileOptions.Builder(tmp).build()
        capture.takePicture(
            output,
            executor ?: ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    try {
                        val bytes = downscale(tmp)
                        if (bytes != null) {
                            onFrame?.invoke(Base64.encodeToString(bytes, Base64.NO_WRAP))
                        }
                    } catch (e: Exception) {
                    } finally {
                        try {
                            tmp.delete()
                        } catch (e: Exception) {
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    // Next scheduled capture will retry.
                }
            }
        )
    }

    /** Downscale to ~480px wide, JPEG quality 55. Returns JPEG bytes. */
    private fun downscale(file: File): ByteArray? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        val w = opts.outWidth
        if (w <= 0) return null
        var sample = 1
        while (w / sample > 480) sample *= 2
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 55, out)
        bmp.recycle()
        return out.toByteArray()
    }

    fun captureNow(onFrame: (String) -> Unit) {
        if (!running) return
        val capture = imageCapture ?: return
        val tmp = File(context.cacheDir, "mpa_photo_${System.currentTimeMillis()}.jpg")
        val output = ImageCapture.OutputFileOptions.Builder(tmp).build()
        capture.takePicture(
            output,
            executor ?: ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                    try {
                        val bytes = downscale(tmp)
                        if (bytes != null) onFrame(Base64.encodeToString(bytes, Base64.NO_WRAP))
                    } catch (e: Exception) {
                    } finally {
                        try {
                            tmp.delete()
                        } catch (e: Exception) {
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {}
            }
        )
    }

    fun stop() {
        running = false
        captureRunnable?.let { handler.removeCallbacks(it) }
        captureRunnable = null
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
        }
        cameraProvider = null
        imageCapture = null
        lifecycleOwner.destroy()
    }
}
