package com.antigravity.lineaireply.service

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * Screen capture utility using Android's MediaProjection & ImageReader APIs.
 * Captures screen frames safely on background dispatcher with timeout protection.
 */
object ScreenCaptureHelper {

    private const val TAG = "ScreenCaptureHelper"

    suspend fun captureScreenshot(
        mediaProjection: MediaProjection,
        width: Int,
        height: Int,
        densityDpi: Int,
        timeoutMs: Long = 2500L
    ): Bitmap? = withContext(Dispatchers.Default) {
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Bitmap?> { cont ->
                val hasResumed = AtomicBoolean(false)
                var virtualDisplay: VirtualDisplay? = null
                var imageReader: ImageReader? = null

                fun cleanup() {
                    try {
                        virtualDisplay?.release()
                        imageReader?.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Cleanup exception: ${e.message}")
                    }
                }

                cont.invokeOnCancellation {
                    cleanup()
                }

                try {
                    imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                    val handler = Handler(Looper.getMainLooper())

                    imageReader.setOnImageAvailableListener({ reader ->
                        if (hasResumed.compareAndSet(false, true)) {
                            var bitmap: Bitmap? = null
                            try {
                                val image = reader.acquireLatestImage()
                                if (image != null) {
                                    val planes = image.planes
                                    val buffer = planes[0].buffer
                                    val pixelStride = planes[0].pixelStride
                                    val rowStride = planes[0].rowStride
                                    val rowPadding = rowStride - pixelStride * width

                                    val tempBitmap = Bitmap.createBitmap(
                                        width + rowPadding / pixelStride,
                                        height,
                                        Bitmap.Config.ARGB_8888
                                    )
                                    tempBitmap.copyPixelsFromBuffer(buffer)
                                    bitmap = Bitmap.createBitmap(tempBitmap, 0, 0, width, height)
                                    if (bitmap != tempBitmap) {
                                        tempBitmap.recycle()
                                    }
                                    image.close()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed reading image buffer: ${e.message}", e)
                            } finally {
                                cleanup()
                                if (cont.isActive) {
                                    cont.resume(bitmap)
                                }
                            }
                        }
                    }, handler)

                    virtualDisplay = mediaProjection.createVirtualDisplay(
                        "LineAiScreenCapture",
                        width,
                        height,
                        densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        imageReader.surface,
                        null,
                        handler
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting up VirtualDisplay: ${e.message}", e)
                    cleanup()
                    if (hasResumed.compareAndSet(false, true) && cont.isActive) {
                        cont.resume(null)
                    }
                }
            }
        }
    }

    /**
     * Resizes and encodes bitmap to Base64 JPEG for fast multimodal Gemini API transmission.
     */
    fun bitmapToBase64Jpeg(bitmap: Bitmap, maxDimension: Int = 1080, quality: Int = 80): String {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        val maxSide = max(originalWidth, originalHeight)

        val scaledBitmap = if (maxSide > maxDimension && maxSide > 0) {
            val scale = maxDimension.toFloat() / maxSide.toFloat()
            val targetW = (originalWidth * scale).toInt().coerceAtLeast(1)
            val targetH = (originalHeight * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        } else {
            bitmap
        }

        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }

        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }
}
