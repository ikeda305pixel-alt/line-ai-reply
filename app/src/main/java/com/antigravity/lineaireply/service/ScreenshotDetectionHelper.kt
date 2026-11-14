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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Helper for OS-level standard screenshot detection and decoding via MediaStore.
 * Avoids MediaProjection (bypassing Android 15 screen-sharing protection)
 * and avoids AccessibilityService (bypassing PayPay/Banking app RASP restrictions).
 *
 * Includes prefetch caching, debounce, optimized queries, and OOM-protected bitmap handling.
 */
object ScreenshotDetectionHelper {

    private const val TAG = "ScreenshotHelper"

    data class ScreenshotInfo(
        val uri: Uri,
        val displayName: String,
        val dateAddedSeconds: Long,
        val base64Jpeg: String
    )

    data class CachedScreenshot(
        val cachedAtMs: Long,
        val info: ScreenshotInfo
    )

    @Volatile
    private var cachedScreenshot: CachedScreenshot? = null

    /**
     * Returns valid cached screenshot if still within maxAgeSeconds window, or null otherwise.
     * Completes in 0ms without performing any MediaStore query or Bitmap decode.
     */
    fun getCachedScreenshot(maxAgeSeconds: Long = 20): ScreenshotInfo? {
        val cached = cachedScreenshot ?: return null
        val nowMs = System.currentTimeMillis()
        val cacheAgeMs = nowMs - cached.cachedAtMs
        val photoAgeSec = (nowMs / 1000) - cached.info.dateAddedSeconds
        if (cacheAgeMs in 0..(maxAgeSeconds * 1000L) && photoAgeSec in -5..maxAgeSeconds) {
            return cached.info
        }
        cachedScreenshot = null
        return null
    }

    /**
     * Invalidates the in-memory prefetch cache (e.g. after deletion or explicit dismiss).
     */
    fun invalidateCache(uri: Uri? = null) {
        if (uri == null || cachedScreenshot?.info?.uri == uri) {
            cachedScreenshot = null
        }
    }

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
     * Retrieves screenshot by URI, using memory cache if available, or loading on demand.
     */
    suspend fun getScreenshotByUri(
        context: Context,
        uri: Uri
    ): ScreenshotInfo? = withContext(Dispatchers.IO) {
        val cached = cachedScreenshot?.info
        if (cached != null && cached.uri == uri) {
            return@withContext cached
        }
        val base64 = loadAndEncodeScreenshot(context, uri, maxDimension = 768, quality = 70) ?: return@withContext null
        val info = ScreenshotInfo(uri, uri.lastPathSegment ?: "screenshot", System.currentTimeMillis() / 1000, base64)
        cachedScreenshot = CachedScreenshot(System.currentTimeMillis(), info)
        info
    }

    /**
     * Finds the most recent screenshot taken within [maxAgeSeconds] (default: 20s).
     *
     * 1. Performs an ultra-fast MediaStore metadata-only query (ID, NAME, DATE, IS_PENDING).
     * 2. If the latest screenshot matches cached URI and IS_PENDING == 0, returns cached Base64 in 0ms
     *    WITHOUT bitmap decoding.
     * 3. If latest screenshot is pending (IS_PENDING != 0), retries up to 4 times (120ms each).
     *    NEVER falls through to older screenshots.
     * 4. If a newer screenshot exists, decodes it and updates cache.
     */
    suspend fun getRecentScreenshot(
        context: Context,
        maxAgeSeconds: Long = 20,
        forceRefresh: Boolean = false,
        retryCount: Int = 0
    ): ScreenshotInfo? = withContext(Dispatchers.IO) {
        val nowSeconds = System.currentTimeMillis() / 1000
        val minDateAdded = nowSeconds - maxAgeSeconds - 5

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
        val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
        val selectionArgs = arrayOf(minDateAdded.toString())
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        try {
            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
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

                var foundLatestScreenshot = false

                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val name = c.getString(nameCol) ?: ""
                    val dateAdded = c.getLong(dateCol)
                    val relPath = if (pathCol != -1) c.getString(pathCol) else null
                    val isPending = if (pendingCol != -1) c.getInt(pendingCol) else 0

                    if (!isScreenshot(name, relPath)) {
                        continue
                    }

                    // We encountered the single latest screenshot in the database
                    foundLatestScreenshot = true

                    // 1. If the latest screenshot is still pending write from the OS:
                    // Retry up to 4 times (480ms max). NEVER fall through to the previous image!
                    if (isPending != 0) {
                        if (retryCount < 4) {
                            delay(120)
                            return@withContext getRecentScreenshot(
                                context,
                                maxAgeSeconds = maxAgeSeconds,
                                forceRefresh = forceRefresh,
                                retryCount = retryCount + 1
                            )
                        } else {
                            Log.w(TAG, "Latest screenshot $name is still pending after max retries. Aborting without fallthrough.")
                            return@withContext null
                        }
                    }

                    // 2. Check time window
                    val age = nowSeconds - dateAdded
                    if (age < -5 || age > maxAgeSeconds) {
                        // Latest screenshot is outside maxAgeSeconds
                        return@withContext null
                    }

                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                    // 3. Ultra-fast metadata match with cache
                    val cached = cachedScreenshot
                    if (!forceRefresh && cached != null && cached.info.uri == uri) {
                        Log.i(TAG, "Instant cache hit with matching metadata: $name (age: ${age}s)")
                        return@withContext cached.info
                    }

                    // 4. New screenshot or forced refresh: decode and update cache
                    val base64 = loadAndEncodeScreenshot(context, uri, maxDimension = 768, quality = 70)
                    if (base64 != null) {
                        Log.i(TAG, "Recent screenshot loaded: $name in $relPath (age: ${age}s)")
                        val info = ScreenshotInfo(uri, name, dateAdded, base64)
                        cachedScreenshot = CachedScreenshot(System.currentTimeMillis(), info)
                        return@withContext info
                    } else {
                        // Decode failed
                        return@withContext null
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
     * and compresses to fast Base64 JPEG (quality: 70).
     * Includes RGB_565 configuration, strict OOM protection, and guaranteed bitmap recycling.
     */
    suspend fun loadAndEncodeScreenshot(
        context: Context,
        uri: Uri,
        maxDimension: Int = 768,
        quality: Int = 70
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

            // 2. Decode sampled bitmap with RGB_565 for OOM protection
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
            val maxEdge = Math.max(width, height)
            val scale = maxDimension.toFloat() / maxEdge.toFloat()
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
     * Invalidates in-memory cache upon successful deletion.
     */
    suspend fun deleteScreenshot(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val rows = context.contentResolver.delete(uri, null, null)
            Log.i(TAG, "Deleted temp screenshot $uri (result: $rows)")
            if (rows > 0) {
                invalidateCache(uri)
                true
            } else {
                false
            }
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
     * Uses cancel-and-delay debounce (200ms) to ensure write-completion events
     * (2nd and 3rd screenshots) are NEVER dropped.
     */
    class ScreenshotObserver(
        private val context: Context,
        handler: Handler = Handler(Looper.getMainLooper()),
        private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        private val onNewScreenshot: (ScreenshotInfo) -> Unit
    ) : ContentObserver(handler) {

        private var pendingObserverJob: kotlinx.coroutines.Job? = null
        private var lastNotifiedUri: Uri? = null

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            // Cancel previous in-flight debounce job so newest event is always processed
            pendingObserverJob?.cancel()
            pendingObserverJob = scope.launch {
                delay(200)
                try {
                    val info = getRecentScreenshot(context, maxAgeSeconds = 20, forceRefresh = true)
                    if (info != null && info.uri != lastNotifiedUri) {
                        lastNotifiedUri = info.uri
                        withContext(Dispatchers.Main) {
                            onNewScreenshot(info)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "ScreenshotObserver prefetch error: ${e.message}")
                }
            }
        }

        fun cleanup() {
            try {
                pendingObserverJob?.cancel()
                scope.cancel()
            } catch (_: Exception) {}
        }
    }
}

