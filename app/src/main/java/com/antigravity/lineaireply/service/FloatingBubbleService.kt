package com.antigravity.lineaireply.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.antigravity.lineaireply.MainActivity
import com.antigravity.lineaireply.R
import com.antigravity.lineaireply.ai.AssistantMode
import com.antigravity.lineaireply.ai.ConversationMemoryManager
import com.antigravity.lineaireply.ai.FreeTierGuard
import com.antigravity.lineaireply.ai.SmartChatAssistant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Service managing:
 * 1. Real-time OS standard screenshot detection via MediaStore ContentObserver.
 * 2. Draggable floating trigger bubble (💬) and floating bottom action bar dock.
 * 3. Priority routing:
 *    ① Recent screenshot (within 15s) -> Multimodal Gemini Vision
 *    ② Copied LINE message -> Window focus trick -> Gemini text generation
 *    ③ Neither -> Mini input prompt in overlay
 * 4. 4 Action Modes: 返信提案, 日程調整, 話題提案, ムード分析.
 *
 * Zero screen recording (avoids Android 15 screen-sharing protection).
 * Zero AccessibilityService (avoids PayPay and Banking app RASP restrictions).
 */
class FloatingBubbleService : Service() {

    companion object {
        const val TAG = "FloatingBubbleService"
        const val ACTION_START = "com.antigravity.lineaireply.ACTION_START"
        const val ACTION_STOP = "com.antigravity.lineaireply.ACTION_STOP"

        const val CHANNEL_ID = "floating_bubble_channel"
        const val NOTIFICATION_ID = 8802

        @Volatile
        var isRunning = false
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var prefs: SharedPreferences
    private lateinit var guard: FreeTierGuard
    private lateinit var memoryManager: ConversationMemoryManager
    private lateinit var assistant: SmartChatAssistant
    private lateinit var overlayManager: SmartOverlayManager

    private var screenshotObserver: ScreenshotDetectionHelper.ScreenshotObserver? = null
    private var currentMode: AssistantMode = AssistantMode.REPLY

    // Cached last context
    private var lastScreenshotBase64: String? = null
    private var lastScreenshotName: String? = null
    private var lastClipboardText: String? = null
    private var lastUserPromptNote: String? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("line_ai_prefs", Context.MODE_PRIVATE)
        guard = FreeTierGuard(this)
        memoryManager = ConversationMemoryManager(this)
        overlayManager = SmartOverlayManager(this)

        val key = prefs.getString("gemini_key", "") ?: ""
        assistant = SmartChatAssistant(key, guard, memoryManager)

        createNotificationChannel()
        registerScreenshotObserver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        if (action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildForegroundNotification()
        startForeground(NOTIFICATION_ID, notification)
        isRunning = true

        val enableBubble = prefs.getBoolean("enable_floating_bubble", true)
        val enableBottomBar = prefs.getBoolean("enable_bottom_bar", true)

        if (enableBubble) {
            overlayManager.showBubble(
                platform = "LINE",
                onClick = { handleActionTrigger(currentMode) },
                onClose = {
                    Toast.makeText(this, "バブルを非表示にしました（通知バーから停止・再開可能）", Toast.LENGTH_SHORT).show()
                }
            )
        }

        if (enableBottomBar) {
            overlayManager.showBottomBar(
                onModeClick = { mode ->
                    currentMode = mode
                    handleActionTrigger(mode)
                },
                onClose = {
                    Toast.makeText(this, "下部バーを閉じました", Toast.LENGTH_SHORT).show()
                }
            )
        }

        return START_STICKY
    }

    /**
     * Executes the 3-step priority logic:
     * ① Recent screenshot (within 15s) -> Multimodal Gemini Vision
     * ② Copied text via window focus trick -> Gemini text generation
     * ③ Neither -> Mini input prompt in overlay
     */
    private fun handleActionTrigger(mode: AssistantMode, customUserPrompt: String? = null) {
        serviceScope.launch {
            val key = prefs.getString("gemini_key", "") ?: ""
            assistant.updateApiKey(key)

            // Step 1: Check for recent screenshot within 15 seconds
            val recentScreenshot = ScreenshotDetectionHelper.getRecentScreenshot(this@FloatingBubbleService, maxAgeSeconds = 15)

            if (recentScreenshot != null) {
                lastScreenshotBase64 = recentScreenshot.base64Jpeg
                lastScreenshotName = recentScreenshot.displayName
                lastClipboardText = null

                Toast.makeText(
                    this@FloatingBubbleService,
                    "📸 スクショ（${recentScreenshot.displayName}）を検出！${mode.title}を解析中...",
                    Toast.LENGTH_SHORT
                ).show()

                val result = assistant.generateFromScreenshot(
                    imageBase64 = recentScreenshot.base64Jpeg,
                    mode = mode,
                    userPromptNote = customUserPrompt
                )

                // Optional auto-deletion of temporary screenshot
                if (prefs.getBoolean("auto_delete_screenshot", false)) {
                    ScreenshotDetectionHelper.deleteScreenshot(this@FloatingBubbleService, recentScreenshot.uri)
                }

                overlayManager.showReplies(
                    platform = "LINE",
                    summaryText = "LINE AI [${mode.title}] - ${result.engine}",
                    currentMode = mode,
                    screenshotName = recentScreenshot.displayName,
                    clipboardText = null,
                    options = result.options,
                    onModeSelected = { newMode ->
                        currentMode = newMode
                        handleActionTrigger(newMode, customUserPrompt)
                    },
                    onSelect = { /* Auto-copied inside SmartOverlayManager */ },
                    onRegenerate = { handleActionTrigger(mode, customUserPrompt) },
                    onMiniPromptSubmit = { note ->
                        handleActionTrigger(mode, note)
                    },
                    onDismiss = { restoreOverlays() }
                )
                return@launch
            }

            // Step 2: Read clipboard via Window Focus Trick
            overlayManager.readClipboardWithFocusTrick(null) { clipText ->
                serviceScope.launch {
                    if (!clipText.isNullOrBlank()) {
                        lastClipboardText = clipText
                        lastScreenshotBase64 = null
                        lastScreenshotName = null

                        Toast.makeText(
                            this@FloatingBubbleService,
                            "📋 コピー文を検出！${mode.title}を生成中...",
                            Toast.LENGTH_SHORT
                        ).show()

                        val result = assistant.generateFromClipboard(
                            clipboardText = clipText,
                            mode = mode,
                            userPromptNote = customUserPrompt
                        )

                        overlayManager.showReplies(
                            platform = "LINE",
                            summaryText = "LINE AI [${mode.title}] - ${result.engine}",
                            currentMode = mode,
                            screenshotName = null,
                            clipboardText = clipText,
                            options = result.options,
                            onModeSelected = { newMode ->
                                currentMode = newMode
                                handleActionTrigger(newMode, customUserPrompt)
                            },
                            onSelect = { /* Auto-copied */ },
                            onRegenerate = { handleActionTrigger(mode, customUserPrompt) },
                            onMiniPromptSubmit = { note ->
                                handleActionTrigger(mode, note)
                            },
                            onDismiss = { restoreOverlays() }
                        )
                    } else if (!customUserPrompt.isNullOrBlank()) {
                        // User typed directly into mini input
                        Toast.makeText(
                            this@FloatingBubbleService,
                            "✍️ メモから${mode.title}を生成中...",
                            Toast.LENGTH_SHORT
                        ).show()

                        val result = assistant.generateFromUserNote(
                            userPromptNote = customUserPrompt,
                            mode = mode
                        )

                        overlayManager.showReplies(
                            platform = "LINE",
                            summaryText = "LINE AI [${mode.title}] - ${result.engine}",
                            currentMode = mode,
                            screenshotName = null,
                            clipboardText = "メモ: $customUserPrompt",
                            options = result.options,
                            onModeSelected = { newMode ->
                                currentMode = newMode
                                handleActionTrigger(newMode, customUserPrompt)
                            },
                            onSelect = { /* Auto-copied */ },
                            onRegenerate = { handleActionTrigger(mode, customUserPrompt) },
                            onMiniPromptSubmit = { note ->
                                handleActionTrigger(mode, note)
                            },
                            onDismiss = { restoreOverlays() }
                        )
                    } else {
                        // Step 3: Neither screenshot nor clipboard found -> Show mini input field
                        overlayManager.showReplies(
                            platform = "LINE",
                            summaryText = "LINE AI [${mode.title}] - 入力待ち",
                            currentMode = mode,
                            screenshotName = null,
                            clipboardText = null,
                            options = emptyList(),
                            onModeSelected = { newMode ->
                                currentMode = newMode
                                handleActionTrigger(newMode)
                            },
                            onSelect = { /* Auto-copied */ },
                            onRegenerate = { handleActionTrigger(mode) },
                            onMiniPromptSubmit = { note ->
                                handleActionTrigger(mode, note)
                            },
                            onDismiss = { restoreOverlays() }
                        )
                    }
                }
            }
        }
    }

    private fun restoreOverlays() {
        val enableBubble = prefs.getBoolean("enable_floating_bubble", true)
        val enableBottomBar = prefs.getBoolean("enable_bottom_bar", true)

        if (enableBubble && !overlayManager.isBubbleShowing()) {
            overlayManager.showBubble(
                platform = "LINE",
                onClick = { handleActionTrigger(currentMode) }
            )
        }
        if (enableBottomBar && !overlayManager.isBottomBarShowing()) {
            overlayManager.showBottomBar(
                onModeClick = { mode ->
                    currentMode = mode
                    handleActionTrigger(mode)
                }
            )
        }
    }

    private fun registerScreenshotObserver() {
        try {
            val observer = ScreenshotDetectionHelper.ScreenshotObserver(this) { info ->
                Toast.makeText(
                    this,
                    "📸 スクショを検知しました！バブルまたは下部バーをタップしてAI返信を作成",
                    Toast.LENGTH_LONG
                ).show()
            }
            contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
            screenshotObserver = observer
        } catch (e: Exception) {
            Log.w(TAG, "Failed registering screenshot observer: ${e.message}")
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
            .setContentText("バブルまたは下部バーをタップして返信・日程・話題を生成")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pOpenApp)
            .addAction(R.drawable.ic_launcher, "アシスタントを停止", pStop)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LINE AI 返信バブル",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "画面上のフローティングバブルおよび下部バー稼働通知"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        screenshotObserver?.let {
            try {
                contentResolver.unregisterContentObserver(it)
            } catch (_: Exception) {}
            screenshotObserver = null
        }
        overlayManager.hideAll()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
