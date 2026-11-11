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
import android.widget.TextView
import android.widget.Toast
import com.antigravity.lineaireply.R
import com.antigravity.lineaireply.ai.SmartReplyOption

/**
 * Floating Overlay Manager for MediaProjection AI Reply Assistant.
 * Responsibilities:
 * - Floating trigger bubble (💬) draggable with automatic left/right edge snapping.
 * - Reply suggestions popup overlay with 3-4 clickable candidate cards.
 * - Tap-to-copy with user-friendly "📋 コピーしました。入力欄にペーストしてください" toast.
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

        bubbleIcon.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.alpha = 1.0f
                    initialX = params.x
                    initialY = params.y
                    touchDownX = event.rawX
                    touchDownY = event.rawY
                    isClick = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(event.rawX - touchDownX) > 12 || Math.abs(event.rawY - touchDownY) > 12) {
                        isClick = false
                    }
                    params.x = initialX + (event.rawX - touchDownX).toInt()
                    params.y = initialY + (event.rawY - touchDownY).toInt()
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
        partnerMessage: String = "",
        profilePreview: String = "",
        options: List<SmartReplyOption>,
        onSelect: (String) -> Unit,
        onRegenerate: () -> Unit,
        onDismiss: (() -> Unit)? = null
    ) {
        // Hide the floating trigger bubble while the reply modal is active
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
                y = 100
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

        v.findViewById<TextView>(R.id.textPartnerMessagePreview)?.let { preview ->
            val sb = StringBuilder()
            if (partnerMessage.isNotBlank()) {
                val snippet = if (partnerMessage.length > 35) partnerMessage.take(35) + "…" else partnerMessage
                sb.append("💬 文脈: 「$snippet」")
            }
            if (profilePreview.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append("\n")
                val pSnippet = if (profilePreview.length > 40) profilePreview.take(40) + "…" else profilePreview
                sb.append("📸 画面: $pSnippet")
            }
            if (sb.isNotEmpty()) {
                preview.visibility = View.VISIBLE
                preview.text = sb.toString()
            } else {
                preview.visibility = View.GONE
            }
        }

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
