package com.antigravity.lineaireply.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.antigravity.lineaireply.MainActivity
import com.antigravity.lineaireply.R
import com.antigravity.lineaireply.ai.AssistantMode
import com.antigravity.lineaireply.ai.ConversationMemoryManager
import com.antigravity.lineaireply.ai.FreeTierGuard
import com.antigravity.lineaireply.ai.SmartChatAssistant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
    private var lastScreenshotUri: android.net.Uri? = null
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
        assistant = SmartChatAssistant(key, guard, memoryManager, prefs = prefs)

        createNotificationChannel()
        registerScreenshotObserver()
        warmupAssistant()
    }

    private fun warmupAssistant() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                assistant.warmupConnection()
            } catch (t: Throwable) {
                Log.d(TAG, "warmupAssistant notice: ${t.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        if (action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildForegroundNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground typed error: ${e.message}", e)
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "startForeground fallback error: ${e2.message}", e2)
            }
        }
        isRunning = true

        warmupAssistant()

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

    private var currentActionJob: Job? = null

    /**
     * Centralized helper to render replies card and avoid duplicated code.
     */
    private fun renderOverlayCard(
        summaryText: String,
        mode: AssistantMode,
        targetMessage: String? = null,
        screenshotName: String? = null,
        clipboardText: String? = null,
        alertMessage: String? = null,
        options: List<com.antigravity.lineaireply.ai.SmartReplyOption> = emptyList(),
        customUserPrompt: String? = null,
        dismissDelayMs: Long = 1200L
    ) {
        overlayManager.showReplies(
            platform = "LINE",
            summaryText = summaryText,
            currentMode = mode,
            targetMessage = targetMessage,
            screenshotName = screenshotName,
            clipboardText = clipboardText,
            alertMessage = alertMessage,
            options = options,
            onModeSelected = { newMode ->
                currentMode = newMode
                handleActionTrigger(newMode, customUserPrompt, isRegenerateOrModeSwitch = true)
            },
            onSelect = {},
            onRegenerate = { handleActionTrigger(mode, customUserPrompt, isRegenerateOrModeSwitch = true) },
            onMiniPromptSubmit = { note -> handleActionTrigger(mode, note) },
            onDismiss = { restoreOverlays(dismissDelayMs) }
        )
    }

    /**
     * Executes the zero-latency priority logic:
     * 0. API Key check: If not valid, show explicit prompt in popup immediately.
     * 1. Check for recent screenshot:
     *    Calls getRecentScreenshot(20s). If the same screenshot is cached, metadata match returns it in 0ms.
     *    If user took a new screenshot, the new screenshot is detected instantly.
     * 2. If recent screenshot exists -> Gemini Vision.
     * 3. Else if in mode switch / regenerate and previous screenshotUri exists -> reload via getScreenshotByUri.
     * 4. Else if clipboard exists (or previous clipboard preserved) -> Gemini text generation.
     * 5. Else if userPrompt exists -> Gemini text generation.
     * 6. Else -> Mini input prompt in overlay.
     */
    private fun handleActionTrigger(
        mode: AssistantMode,
        customUserPrompt: String? = null,
        isRegenerateOrModeSwitch: Boolean = false
    ) {
        currentActionJob?.cancel()
        currentActionJob = serviceScope.launch {
            val key = prefs.getString("gemini_key", "") ?: ""
            assistant.updateApiKey(key)

            // Step 0: Check API Key validity
            if (!SmartChatAssistant.isValidApiKey(key)) {
                renderOverlayCard(
                    summaryText = "LINE AI [${mode.title}]",
                    mode = mode,
                    alertMessage = "⚠️ 設定画面でGemini APIキー（AIza... または AQ....）を保存してください",
                    customUserPrompt = customUserPrompt,
                    dismissDelayMs = 0L
                )
                return@launch
            }

            // Step 1: Check for screenshot (fast metadata match gives 0ms cache hit or detects brand new screenshot)
            var screenshot = ScreenshotDetectionHelper.getRecentScreenshot(this@FloatingBubbleService, maxAgeSeconds = 20)

            // If in mode switch/regenerate and the screenshot is older than 20s, reload from lastScreenshotUri
            if (screenshot == null && isRegenerateOrModeSwitch && lastScreenshotUri != null) {
                screenshot = ScreenshotDetectionHelper.getScreenshotByUri(this@FloatingBubbleService, lastScreenshotUri!!)
            }

            if (screenshot != null) {
                lastScreenshotUri = screenshot.uri
                lastScreenshotName = screenshot.displayName
                lastClipboardText = null

                renderOverlayCard(
                    summaryText = "📷 スクショを解析中...",
                    mode = mode,
                    screenshotName = screenshot.displayName,
                    customUserPrompt = customUserPrompt
                )

                val result = assistant.generateFromScreenshot(
                    imageBase64 = screenshot.base64Jpeg,
                    mode = mode,
                    userPromptNote = customUserPrompt
                )

                if (prefs.getBoolean("auto_delete_screenshot", false)) {
                    ScreenshotDetectionHelper.deleteScreenshot(this@FloatingBubbleService, screenshot.uri)
                }

                val hasError = result.options.isEmpty() && result.errorDetails != null
                val alertMsg = if (hasError) "❌ [通信エラー] ${result.errorDetails}" else null
                val summary = if (hasError) "LINE AI [${mode.title}] - エラー" else "LINE AI [${mode.title}] - ${result.engine}"

                renderOverlayCard(
                    summaryText = summary,
                    mode = mode,
                    targetMessage = result.targetMessage,
                    screenshotName = screenshot.displayName,
                    alertMessage = alertMsg,
                    options = result.options,
                    customUserPrompt = customUserPrompt
                )
                return@launch
            }

            // Step 2: Clipboard reuse on mode switch or new clipboard read
            var clipText: String? = null
            if (isRegenerateOrModeSwitch && !lastClipboardText.isNullOrBlank()) {
                clipText = lastClipboardText
            } else {
                // Show checking status immediately so user feels zero click delay
                renderOverlayCard(
                    summaryText = "⚡ クリップボードを確認中...",
                    mode = mode,
                    customUserPrompt = customUserPrompt
                )
                clipText = overlayManager.readClipboardWithFocusTrickSuspend()
            }

            if (!clipText.isNullOrBlank()) {
                lastClipboardText = clipText
                lastScreenshotUri = null
                lastScreenshotName = null

                renderOverlayCard(
                    summaryText = "📋 コピー文面を解析中...",
                    mode = mode,
                    clipboardText = clipText,
                    customUserPrompt = customUserPrompt
                )

                val result = assistant.generateFromClipboard(
                    clipboardText = clipText,
                    mode = mode,
                    userPromptNote = customUserPrompt
                )

                val hasError = result.options.isEmpty() && result.errorDetails != null
                val alertMsg = if (hasError) "❌ [通信エラー] ${result.errorDetails}" else null
                val summary = if (hasError) "LINE AI [${mode.title}] - エラー" else "LINE AI [${mode.title}] - ${result.engine}"

                renderOverlayCard(
                    summaryText = summary,
                    mode = mode,
                    clipboardText = clipText,
                    alertMessage = alertMsg,
                    options = result.options,
                    customUserPrompt = customUserPrompt
                )
            } else if (!customUserPrompt.isNullOrBlank()) {
                // Step 3: User typed direct memo
                renderOverlayCard(
                    summaryText = "✍️ メモから生成中...",
                    mode = mode,
                    clipboardText = "メモ: $customUserPrompt",
                    customUserPrompt = customUserPrompt
                )

                val result = assistant.generateFromUserNote(
                    userPromptNote = customUserPrompt,
                    mode = mode
                )

                val hasError = result.options.isEmpty() && result.errorDetails != null
                val alertMsg = if (hasError) "❌ [通信エラー] ${result.errorDetails}" else null
                val summary = if (hasError) "LINE AI [${mode.title}] - エラー" else "LINE AI [${mode.title}] - ${result.engine}"

                renderOverlayCard(
                    summaryText = summary,
                    mode = mode,
                    clipboardText = "メモ: $customUserPrompt",
                    alertMessage = alertMsg,
                    options = result.options,
                    customUserPrompt = customUserPrompt
                )
            } else {
                // Step 4: Neither screenshot nor clipboard found -> Show mini input field
                renderOverlayCard(
                    summaryText = "LINE AI [${mode.title}] - 入力待ち",
                    mode = mode,
                    dismissDelayMs = 1200L
                )
            }
        }
    }

    private fun restoreOverlays(delayMs: Long = 0L) {
        serviceScope.launch(Dispatchers.Main) {
            if (delayMs > 0) {
                kotlinx.coroutines.delay(delayMs)
            }
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
    }

    private fun registerScreenshotObserver() {
        try {
            val observer = ScreenshotDetectionHelper.ScreenshotObserver(this) { info ->
                lastScreenshotUri = info.uri
                lastScreenshotName = info.displayName
                lastClipboardText = null
                warmupAssistant()
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
        try {
            serviceScope.cancel()
        } catch (_: Exception) {}
        screenshotObserver?.let {
            it.cleanup()
            try {
                contentResolver.unregisterContentObserver(it)
            } catch (_: Exception) {}
            screenshotObserver = null
        }
        overlayManager.hideAll()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
