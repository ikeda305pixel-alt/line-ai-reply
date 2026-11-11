package com.antigravity.lineaireply.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.antigravity.lineaireply.R
import com.antigravity.lineaireply.ai.AssistantMode
import com.antigravity.lineaireply.ai.SmartReplyOption

/**
 * Floating Overlay Manager for Notification-backed AI Reply Assistant.
 * Responsibilities:
 * - Floating trigger bubble (💬) draggable with automatic left/right edge snapping.
 * - Multi-room switching chips (one-tap switch between recent chat rooms).
 * - 4 Action Mode selector (返信提案 / 日程調整 / 話題提案 / ムード分析).
 * - Priority clipboard context preview.
 * - Reply suggestions popup overlay with tap-to-copy and toast notification.
 */
class SmartOverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "SmartOverlayManager"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // Floating Trigger Bubble (💬)
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    // Reply Options Card
    private var cardView: View? = null

    fun isBubbleShowing(): Boolean = bubbleView != null
    fun isRepliesShowing(): Boolean = cardView != null

    fun showBubble(
        platform: String = "LINE",
        onClick: () -> Unit,
        onClose: (() -> Unit)? = null
    ) {
        if (bubbleView != null) return

        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.overlay_bubble_trigger, null)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - 140
            y = screenHeight / 3
        }

        bubbleParams = params
        bubbleView = view
        view.alpha = 0.85f

        val bubbleIcon = view.findViewById<TextView>(R.id.bubbleIcon)
        if (platform == "Bumble") {
            bubbleIcon.setBackgroundColor(Color.parseColor("#FFC629"))
        } else {
            bubbleIcon.setBackgroundColor(Color.parseColor("#06C755"))
        }

        view.findViewById<View>(R.id.btnBubbleClose)?.setOnClickListener {
            hideBubble()
            onClose?.invoke()
        }

        var initialX = 0
        var initialY = 0
        var touchDownX = 0f
        var touchDownY = 0f
        var isClick = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchDownX = event.rawX
                    touchDownY = event.rawY
                    isClick = true
                    view.alpha = 1.0f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchDownX).toInt()
                    val dy = (event.rawY - touchDownY).toInt()
                    if (Math.abs(dx) > 12 || Math.abs(dy) > 12) {
                        isClick = false
                    }
                    params.x = initialX + dx
                    params.y = initialY + dy
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (e: Exception) {
                        Log.w(TAG, "Update bubble layout error: ${e.message}")
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.alpha = 0.85f
                    snapToEdge(view, params)
                    if (isClick) {
                        onClick()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding bubble to WindowManager: ${e.message}")
        }
    }

    fun hideBubble() {
        bubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
            bubbleView = null
        }
    }

    fun showReplies(
        platform: String = "LINE",
        summaryText: String,
        currentRoom: String,
        availableRooms: List<String>,
        currentMode: AssistantMode = AssistantMode.REPLY,
        partnerMessage: String = "",
        clipboardText: String? = null,
        options: List<SmartReplyOption>,
        onRoomSelected: (String) -> Unit,
        onModeSelected: (AssistantMode) -> Unit,
        onSelect: (String) -> Unit,
        onRegenerate: () -> Unit,
        onDismiss: (() -> Unit)? = null
    ) {
        // Hide trigger bubble while modal is active
        hideBubble()

        if (cardView == null) {
            val inflater = LayoutInflater.from(context)
            val view = inflater.inflate(R.layout.overlay_line_replies, null)

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 60
            }

            cardView = view
            try {
                windowManager.addView(view, params)
            } catch (e: Exception) {
                Log.e(TAG, "Failed adding replies card: ${e.message}")
                return
            }
        }

        val v = cardView ?: return

        // 1. Platform tag & Context summary
        val tagView = v.findViewById<TextView>(R.id.textPlatformTag)
        if (platform == "Bumble") {
            tagView.text = "Bumble AI"
            tagView.setBackgroundColor(Color.parseColor("#FFC629"))
            tagView.setTextColor(Color.parseColor("#1C1917"))
        } else {
            tagView.text = "LINE AI"
            tagView.setBackgroundColor(Color.parseColor("#06C755"))
            tagView.setTextColor(Color.WHITE)
        }
        v.findViewById<TextView>(R.id.textContextSummary).text = summaryText

        // 2. Room Switcher Chips
        val layoutRooms = v.findViewById<LinearLayout>(R.id.layoutRoomChips)
        layoutRooms?.removeAllViews()
        val roomsToDisplay = if (availableRooms.isNotEmpty()) availableRooms else listOf(currentRoom)
        for (room in roomsToDisplay) {
            val isCurrent = (room == currentRoom)
            val chip = TextView(context).apply {
                text = if (isCurrent) "👤 $room (選択中)" else "👤 $room"
                textSize = 11f
                setPadding(20, 8, 20, 8)
                val marginParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = 12
                }
                layoutParams = marginParams
                if (isCurrent) {
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#06C755"))
                } else {
                    setTextColor(Color.parseColor("#334155"))
                    setBackgroundColor(Color.parseColor("#E2E8F0"))
                }
                setOnClickListener {
                    if (!isCurrent) {
                        onRoomSelected(room)
                    }
                }
            }
            layoutRooms?.addView(chip)
        }

        // 3. 4 Action Mode Buttons
        fun updateModeButton(btn: Button, mode: AssistantMode) {
            val isSelected = (mode == currentMode)
            if (isSelected) {
                btn.setBackgroundColor(Color.parseColor("#06C755"))
                btn.setTextColor(Color.WHITE)
            } else {
                btn.setBackgroundColor(Color.TRANSPARENT)
                btn.setTextColor(Color.parseColor("#475569"))
            }
            btn.setOnClickListener {
                if (!isSelected) {
                    onModeSelected(mode)
                }
            }
        }

        v.findViewById<Button>(R.id.btnModeReply)?.let { updateModeButton(it, AssistantMode.REPLY) }
        v.findViewById<Button>(R.id.btnModeSchedule)?.let { updateModeButton(it, AssistantMode.SCHEDULE) }
        v.findViewById<Button>(R.id.btnModeTopic)?.let { updateModeButton(it, AssistantMode.TOPIC) }
        v.findViewById<Button>(R.id.btnModeMood)?.let { updateModeButton(it, AssistantMode.MOOD) }

        // 4. Clipboard Priority Banner
        v.findViewById<TextView>(R.id.textClipboardPreview)?.let { clipView ->
            if (!clipboardText.isNullOrBlank()) {
                clipView.visibility = View.VISIBLE
                val snippet = if (clipboardText.length > 35) clipboardText.take(35) + "…" else clipboardText
                clipView.text = "📋 クリップボード文を優先反映中: 「$snippet」"
            } else {
                clipView.visibility = View.GONE
            }
        }

        // 5. Partner Message Preview
        v.findViewById<TextView>(R.id.textPartnerMessagePreview)?.let { preview ->
            if (partnerMessage.isNotBlank()) {
                val snippet = if (partnerMessage.length > 50) partnerMessage.take(50) + "…" else partnerMessage
                preview.text = "💬 [$currentRoom] 直近通知: 「$snippet」"
                preview.visibility = View.VISIBLE
            } else {
                preview.text = "💬 [$currentRoom] （まだ新しい通知はありません）"
                preview.visibility = View.VISIBLE
            }
        }

        // 6. 4 Selectable Reply Options
        val btnList = listOf(
            v.findViewById<Button>(R.id.btnOpt1),
            v.findViewById<Button>(R.id.btnOpt2),
            v.findViewById<Button>(R.id.btnOpt3),
            v.findViewById<Button>(R.id.btnOpt4)
        )

        for (i in btnList.indices) {
            val btn = btnList[i]
            if (i < options.size) {
                val opt = options[i]
                btn.visibility = View.VISIBLE
                btn.text = "${opt.label}\n${opt.text}"
                btn.setOnClickListener {
                    copyToClipboard(opt.text)
                    hideReplies()
                    onSelect(opt.text)
                    onDismiss?.invoke()
                }
            } else {
                btn.visibility = View.GONE
            }
        }

        // 7. Close and Actions
        v.findViewById<View>(R.id.btnClose).setOnClickListener {
            hideReplies()
            onDismiss?.invoke()
        }

        v.findViewById<View>(R.id.btnDirectType).setOnClickListener {
            hideReplies()
            onDismiss?.invoke()
        }

        v.findViewById<View>(R.id.btnRegenerate).setOnClickListener {
            onRegenerate()
        }
    }

    fun hideReplies() {
        cardView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
            cardView = null
        }
    }

    fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val currentWidth = view.width.coerceAtLeast(120)
        if (params.x + currentWidth / 2 < screenWidth / 2) {
            params.x = 24
        } else {
            params.x = screenWidth - currentWidth - 24
        }
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {}
    }

    fun copyToClipboard(text: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("LINE AI Reply", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "📋 コピーしました。入力欄にペーストしてください", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.w(TAG, "Clipboard copy error: ${e.message}")
        }
    }

    fun hideAll() {
        hideBubble()
        hideReplies()
    }
}
