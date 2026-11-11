package com.antigravity.lineaireply.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
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
 * Service that manages:
 * 1. Draggable floating trigger bubble (💬) with edge snapping.
 * 2. Instant context retrieval from NotificationChatCacheManager + Clipboard.
 * 3. 4 Action Modes: 返信提案, 日程調整, 話題提案, ムード分析.
 * 4. Tap-to-copy reply cards popup overlay.
 *
 * Zero screen recording, zero screen capture, zero AccessibilityService.
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

    private var selectedRoom: String? = null
    private var currentMode: AssistantMode = AssistantMode.REPLY

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
        startForeground(NOTIFICATION_ID, notification)
        isRunning = true

        // Show the draggable floating bubble
        overlayManager.showBubble(
            platform = "LINE",
            onClick = { handleBubbleClicked() },
            onClose = {
                Toast.makeText(this, "バブルを非表示にしました（通知バーから停止・再開可能）", Toast.LENGTH_SHORT).show()
            }
        )

        return START_STICKY
    }

    private fun handleBubbleClicked() {
        // Hide bubble while loading
        overlayManager.hideBubble()

        serviceScope.launch {
            val key = prefs.getString("gemini_key", "") ?: ""
            assistant.updateApiKey(key)

            val availableRooms = NotificationChatCacheManager.getRecentRooms(this@FloatingBubbleService)
            val roomToUse = selectedRoom ?: NotificationChatCacheManager.getMostRecentRoom(this@FloatingBubbleService)
            ?: availableRooms.firstOrNull() ?: "LINEトーク相手"

            val cachedMessages = NotificationChatCacheManager.getMessagesForRoom(this@FloatingBubbleService, roomToUse)
            val clipText = getClipboardText()

            val latestMsg = cachedMessages.lastOrNull()?.text ?: ""

            // Show a brief toast indicating AI generation is running
            Toast.makeText(
                this@FloatingBubbleService,
                "🧠 [$roomToUse] の${currentMode.title}を生成中...",
                Toast.LENGTH_SHORT
            ).show()

            val result = assistant.generateRepliesFromNotificationContext(
                roomName = roomToUse,
                cachedMessages = cachedMessages,
                clipboardText = clipText,
                mode = currentMode
            )

            // Present the replies popup sheet with 4 modes & room switcher
            overlayManager.showReplies(
                platform = "LINE",
                summaryText = "LINE AI [${currentMode.title}] - ${result.engine}",
                currentRoom = roomToUse,
                availableRooms = availableRooms,
                currentMode = currentMode,
                partnerMessage = latestMsg,
                clipboardText = clipText,
                options = result.options,
                onRoomSelected = { newRoom ->
                    selectedRoom = newRoom
                    handleBubbleClicked()
                },
                onModeSelected = { newMode ->
                    currentMode = newMode
                    handleBubbleClicked()
                },
                onSelect = { selectedText ->
                    // Automatically copied to clipboard in SmartOverlayManager
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

    private fun getClipboardText(): String? {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (cm.hasPrimaryClip()) {
                val item = cm.primaryClip?.getItemAt(0)
                val text = item?.text?.toString()?.trim()
                if (!text.isNullOrBlank()) text else null
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading clipboard: ${e.message}")
            null
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
            .setContentText("画面端の『💬』バブルをタップして返信・日程・話題を生成")
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
                description = "画面上のフローティングバブル稼働通知"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        overlayManager.hideAll()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
