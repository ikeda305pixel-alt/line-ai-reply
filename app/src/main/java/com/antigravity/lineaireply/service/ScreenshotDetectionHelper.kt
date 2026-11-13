package com.antigravity.lineaireply.service

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Helper for OS-level standard screenshot detection and decoding via MediaStore.
 * Avoids MediaProjection (bypassing Android 15 screen-sharing protection)
 * and avoids AccessibilityService (bypassing PayPay/Banking app RASP restrictions).
 */
object ScreenshotDetectionHelper {

    private const val TAG = "ScreenshotHelper"

    data class ScreenshotInfo(
        val uri: Uri,
        val displayName: String,
        val dateAddedSeconds: Long,
        val base64Jpeg: String
    )

    /**
     * Checks if the file name or relative path matches standard screenshot naming conventions.
     */
    fun isScreenshot(displayName: String, relativePath: String?): Boolean {
        val nameLower = displayName.lowercase()
        val pathLower = (relativePath ?: "").lowercase()
        return nameLower.startsWith("screenshot") ||
               nameLower.contains("screenshot") ||
               nameLower.contains("スクリーンショット") ||
               pathLower.contains("screenshots") ||
               pathLower.contains("スクリーンショット")
    }

    /**
     * Finds the most recent screenshot taken within [maxAgeSeconds] (default: 20s).
     * Returns ScreenshotInfo with Base64 JPEG if found, or null if none found within the time window.
     */
    suspend fun getRecentScreenshot(
        context: Context,
        maxAgeSeconds: Long = 20, // default maxAgeSeconds = 20
        isRetry: Boolean = false
    ): ScreenshotInfo? = withContext(Dispatchers.IO) {
        val nowSeconds = System.currentTimeMillis() / 1000

        val projectionList = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projectionList.add(MediaStore.Images.Media.RELATIVE_PATH)
            projectionList.add(MediaStore.Images.Media.IS_PENDING)
        }

        val projection = projectionList.toTypedArray()
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        try {
            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )

            cursor?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    c.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                } else -1
                val pendingCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    c.getColumnIndex(MediaStore.Images.Media.IS_PENDING)
                } else -1

                var checkedCount = 0
                while (c.moveToNext() && checkedCount < 10) {
                    checkedCount++
                    val id = c.getLong(idCol)
                    val name = c.getString(nameCol) ?: ""
                    val dateAdded = c.getLong(dateCol)
                    val relPath = if (pathCol != -1) c.getString(pathCol) else null
                    val isPending = if (pendingCol != -1) c.getInt(pendingCol) else 0

                    // 1. If the latest screenshot is still being written by OS, wait briefly and retry once
                    if (isPending != 0) {
                        if (checkedCount == 1 && !isRetry) {
                            delay(300)
                            return@withContext getRecentScreenshot(context, maxAgeSeconds, isRetry = true)
                        }
                        continue
                    }

                    // 2. Must match screenshot naming/folder conventions
                    if (!isScreenshot(name, relPath)) continue

                    // 3. Verify screenshot age is within window (tolerant of clock skew up to -5s)
                    val age = nowSeconds - dateAdded
                    if (age >= -5 && age <= maxAgeSeconds) {
                        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        val base64 = loadAndEncodeScreenshot(context, uri, maxDimension = 768, quality = 75)
                        if (base64 != null) {
                            Log.i(TAG, "Recent screenshot detected: $name in $relPath (age: ${age}s)")
                            return@withContext ScreenshotInfo(uri, name, dateAdded, base64)
                        }
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying MediaStore for recent screenshot: ${e.message}")
            null
        }
    }

    /**
     * Downscales the screenshot image to at most 768px (optimizing for Gemini Vision single 258-token tile)
     * and compresses to fast Base64 JPEG. Includes OOM protection and guaranteed bitmap recycling.
     * Supports maxDimension = 1280
     */
    suspend fun loadAndEncodeScreenshot(
        context: Context,
        uri: Uri,
        maxDimension: Int = 768, // maxDimension = 1280
        quality: Int = 75
    ): String? = withContext(Dispatchers.IO) {
        var rawBitmap: Bitmap? = null
        var scaledBitmap: Bitmap? = null
        try {
            // 1. Decode bounds
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            if (options.outWidth <= 0 || options.outHeight <= 0) {
                return@withContext null
            }

            var inSampleSize = 1
            val maxOriginal = Math.max(options.outWidth, options.outHeight)
            while (maxOriginal / (inSampleSize * 2) >= maxDimension) {
                inSampleSize *= 2
            }

            // 2. Decode sampled bitmap
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            rawBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return@withContext null

            // 3. Exact scaling to long-edge 768px
            val width = rawBitmap.width
            val height = rawBitmap.height
            val scale = maxDimension.toFloat() / Math.max(width, height).toFloat()
            scaledBitmap = if (scale < 1.0f) {
                val sw = (width * scale).toInt().coerceAtLeast(1)
                val sh = (height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(rawBitmap, sw, sh, true)
            } else {
                rawBitmap
            }

            // 4. Compress to JPEG Base64
            val baos = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load/encode screenshot: ${t.message}", t)
            null
        } finally {
            try {
                if (scaledBitmap != null && scaledBitmap !== rawBitmap && !scaledBitmap.isRecycled) {
                    scaledBitmap.recycle()
                }
            } catch (_: Exception) {}
            try {
                if (rawBitmap != null && !rawBitmap.isRecycled) {
                    rawBitmap.recycle()
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Optionally deletes the temporary screenshot file from MediaStore to save device storage.
     * Safely handles Scoped Storage (SecurityException / RecoverableSecurityException) on Android 10+.
     */
    suspend fun deleteScreenshot(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val rows = context.contentResolver.delete(uri, null, null)
            Log.i(TAG, "Deleted temp screenshot $uri (result: $rows)")
            rows > 0
        } catch (e: SecurityException) {
            // Android 10+ Scoped Storage restriction on deleting non-owned media files
            Log.w(TAG, "Scoped storage restriction prevents auto-deletion of $uri: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete temp screenshot $uri: ${e.message}")
            false
        }
    }

    /**
     * ContentObserver to detect newly saved screenshots in real time.
     */
    class ScreenshotObserver(
        private val context: Context,
        handler: Handler = Handler(Looper.getMainLooper()),
        private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        private val onNewScreenshot: (ScreenshotInfo) -> Unit
    ) : ContentObserver(handler) {

        private var lastDetectedUri: Uri? = null

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            // Query latest screenshot (within last 20s) // maxAgeSeconds = 20
            scope.launch {
                val info = getRecentScreenshot(context, maxAgeSeconds = 20)
                if (info != null && info.uri != lastDetectedUri) {
                    lastDetectedUri = info.uri
                    withContext(Dispatchers.Main) {
                        onNewScreenshot(info)
                    }
                }
            }
        }

        fun cleanup() {
            try {
                scope.cancel()
            } catch (_: Exception) {}
        }
    }
}
