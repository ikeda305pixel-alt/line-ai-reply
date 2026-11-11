package com.antigravity.lineaireply.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.antigravity.lineaireply.MainActivity
import com.antigravity.lineaireply.R
import com.antigravity.lineaireply.ai.ConversationMemoryManager
import com.antigravity.lineaireply.ai.FreeTierGuard
import com.antigravity.lineaireply.ai.SmartChatAssistant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground Service that manages:
 * 1. Screen capture using MediaProjection (zero AccessibilityService dependencies).
 * 2. Draggable floating trigger bubble with edge snapping.
 * 3. Gemini Vision multimodal analysis of the captured LINE talk screen.
 * 4. Tap-to-copy reply cards popup overlay.
 */
class FloatingBubbleService : Service() {

    companion object {
        const val TAG = "FloatingBubbleService"
        const val ACTION_START = "com.antigravity.lineaireply.ACTION_START"
        const val ACTION_STOP = "com.antigravity.lineaireply.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        const val CHANNEL_ID = "floating_bubble_fgs"
        const val NOTIFICATION_ID = 8801

        @Volatile
        var isRunning = false
            private set

        var mediaProjection: MediaProjection? = null
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var prefs: SharedPreferences
    private lateinit var guard: FreeTierGuard
    private lateinit var memoryManager: ConversationMemoryManager
    private lateinit var assistant: SmartChatAssistant
    private lateinit var overlayManager: SmartOverlayManager

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("line_ai_prefs", Context.MODE_PRIVATE)
        guard = FreeTierGuard(this)
        memoryManager = ConversationMemoryManager(this)
        overlayManager = SmartOverlayManager(this)

        val key = prefs.getString("gemini_key", "") ?: ""
        assistant = SmartChatAssistant(key, guard, memoryManager)

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        if (action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildForegroundNotification()

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

        isRunning = true

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != 0 && resultData != null) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            try {
                mediaProjection?.stop()
                val proj = mpManager.getMediaProjection(resultCode, resultData)
                mediaProjection = proj

                proj.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.i(TAG, "MediaProjection was stopped by system")
                        mediaProjection = null
                    }
                }, Handler(Looper.getMainLooper()))
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing MediaProjection: ${e.message}", e)
            }
        }

        // Show the draggable floating bubble
        overlayManager.showBubble(
            platform = "LINE",
            onClick = { handleBubbleClicked() },
            onClose = {
                Toast.makeText(this, "バブルを閉じました（通知バーから停止・再開可能）", Toast.LENGTH_SHORT).show()
            }
        )

        return START_STICKY
    }

    private fun handleBubbleClicked() {
        val proj = mediaProjection
        if (proj == null) {
            Toast.makeText(
                this,
                "⚠️ 画面キャプチャの許可が必要です。アプリから再起動してください",
                Toast.LENGTH_LONG
            ).show()
            val appIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(appIntent)
            return
        }

        // 1. Hide bubble momentarily so it does not get captured in the screenshot
        overlayManager.hideBubble()

        serviceScope.launch {
            // Short delay to ensure the window compositor updates without the bubble
            delay(150)

            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            // 2. Capture screenshot via MediaProjection
            val bitmap = ScreenCaptureHelper.captureScreenshot(proj, width, height, density)

            if (bitmap == null) {
                Toast.makeText(this@FloatingBubbleService, "⚠️ 画面取得に失敗しました。もう一度お試しください", Toast.LENGTH_SHORT).show()
                overlayManager.showBubble("LINE", onClick = { handleBubbleClicked() })
                return@launch
            }

            Toast.makeText(this@FloatingBubbleService, "🧠 LINEトークを解析中...", Toast.LENGTH_SHORT).show()

            // 3. Compress to Base64 JPEG for Gemini Vision
            val base64Jpeg = ScreenCaptureHelper.bitmapToBase64Jpeg(bitmap, maxDimension = 1080, quality = 80)
            bitmap.recycle()

            val key = prefs.getString("gemini_key", "") ?: ""
            assistant.updateApiKey(key)

            // 4. Send to Gemini Vision API
            val result = assistant.generateRepliesFromScreenshot(base64Jpeg)

            // 5. Present high-EQ reply suggestion cards
            overlayManager.showReplies(
                platform = "LINE",
                summaryText = "LINE AI [${result.engine}]",
                partnerMessage = "📸 画面キャプチャから文脈を解析完了",
                profilePreview = "",
                options = result.options,
                onSelect = { selectedText ->
                    // Text copied to clipboard automatically inside SmartOverlayManager
                },
                onRegenerate = {
                    handleBubbleClicked()
                },
                onDismiss = {
                    overlayManager.showBubble("LINE", onClick = { handleBubbleClicked() })
                }
            )
        }
    }

    private fun buildForegroundNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pOpenApp = PendingIntent.getActivity(this, 0, openAppIntent, flags)

        val stopIntent = Intent(this, FloatingBubbleService::class.java).apply {
            action = ACTION_STOP
        }
        val pStop = PendingIntent.getService(this, 1, stopIntent, flags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("LINE AI返信アシスタント稼働中")
            .setContentText("画面上の『💬』バブルをタップして返信候補を生成")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pOpenApp)
            .addAction(R.drawable.ic_launcher, "バブルを停止", pStop)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LINE AI 返信バブル",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "画面上のフローティングバブルおよび画面キャプチャ稼働通知"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        overlayManager.hideAll()
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
