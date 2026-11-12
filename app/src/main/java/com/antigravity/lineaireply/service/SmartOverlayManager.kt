package com.antigravity.lineaireply.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.antigravity.lineaireply.R
import com.antigravity.lineaireply.ai.AssistantMode
import com.antigravity.lineaireply.ai.SmartReplyOption

/**
 * Floating Overlay Manager supporting:
 * 1. Draggable trigger bubble (💬) with edge snapping (guaranteed on-screen positioning).
 * 2. Draggable bottom action dock bar (💬 返信 / 📅 日程 / 💡 話題 / 🔍 分析).
 * 3. Themed context inflation to prevent MaterialComponents theme crashes in Service.
 * 4. Window Focus trick to bypass Android 10+ background clipboard restriction.
 * 5. Reply options popup card with screenshot/clipboard preview & inline mini input fallback.
 */
class SmartOverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "SmartOverlayManager"
    }

    private val themedContext: Context = ContextThemeWrapper(context, R.style.Theme_LineAiReply)
    private val layoutInflater: LayoutInflater = LayoutInflater.from(themedContext)
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // Floating Trigger Bubble (💬)
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    // Floating Bottom Bar Dock
    private var bottomBarView: View? = null
    private var bottomBarParams: WindowManager.LayoutParams? = null

    // Reply Options Card
    private var cardView: View? = null

    fun isBubbleShowing(): Boolean = bubbleView != null
    fun isBottomBarShowing(): Boolean = bottomBarView != null
    fun isRepliesShowing(): Boolean = cardView != null

    private fun getOverlayLayoutType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    // ==========================================
    // 1. Floating Bubble (💬)
    // ==========================================
    fun showBubble(
        platform: String = "LINE",
        onClick: () -> Unit,
        onClose: (() -> Unit)? = null
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Cannot show bubble: SYSTEM_ALERT_WINDOW not granted")
            return
        }

        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val density = displayMetrics.density

        // If already attached, ensure visible and re-dock cleanly
        if (bubbleView != null) {
            bubbleView?.visibility = View.VISIBLE
            bubbleParams?.let { p ->
                val w = bubbleView?.width?.takeIf { it > 0 } ?: (70 * density).toInt()
                val margin = (16 * density).toInt()
                p.x = (screenWidth - w - margin).coerceAtLeast(margin)
                try {
                    windowManager.updateViewLayout(bubbleView, p)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed updating existing bubble: ${e.message}")
                }
            }
            return
        }

        val view = try {
            layoutInflater.inflate(R.layout.overlay_bubble_trigger, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error inflating bubble view: ${e.message}", e)
            return
        }

        view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val bubbleWidth = if (view.measuredWidth > 0) view.measuredWidth else (70 * density).toInt()
        val marginEnd = (16 * density).toInt()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            getOverlayLayoutType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Guaranteed inside the right edge of the screen regardless of device density
            x = (screenWidth - bubbleWidth - marginEnd).coerceAtLeast(16)
            y = (screenHeight / 3).coerceAtLeast(100)
        }

        bubbleParams = params
        bubbleView = view
        view.alpha = 0.90f

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
                    view.alpha = 0.90f
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
            Log.e(TAG, "Error adding bubble to WindowManager: ${e.message}", e)
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

    // ==========================================
    // 2. Floating Bottom Action Bar Dock
    // ==========================================
    fun showBottomBar(
        onModeClick: (AssistantMode) -> Unit,
        onClose: (() -> Unit)? = null
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Cannot show bottom bar: SYSTEM_ALERT_WINDOW not granted")
            return
        }

        if (bottomBarView != null) {
            bottomBarView?.visibility = View.VISIBLE
            return
        }

        val view = try {
            layoutInflater.inflate(R.layout.overlay_bottom_bar, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed inflating overlay_bottom_bar: ${e.message}", e)
            return
        }

        val displayMetrics = context.resources.displayMetrics
        val density = displayMetrics.density
        val screenHeight = displayMetrics.heightPixels

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            getOverlayLayoutType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Positioned at TOP by default, below status bar, completely out of the way of LINE's keyboard & input field!
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (65 * density).toInt()
        }

        bottomBarParams = params
        bottomBarView = view

        view.findViewById<Button>(R.id.btnBarReply)?.setOnClickListener { onModeClick(AssistantMode.REPLY) }
        view.findViewById<Button>(R.id.btnBarSchedule)?.setOnClickListener { onModeClick(AssistantMode.SCHEDULE) }
        view.findViewById<Button>(R.id.btnBarTopic)?.setOnClickListener { onModeClick(AssistantMode.TOPIC) }
        view.findViewById<Button>(R.id.btnBarMood)?.setOnClickListener { onModeClick(AssistantMode.MOOD) }

        // Minimize / Expand toggle
        val layoutActions = view.findViewById<View>(R.id.layoutBarActions)
        val btnMinimize = view.findViewById<TextView>(R.id.btnBarMinimize)
        btnMinimize?.setOnClickListener {
            val isCurrentlyVisible = layoutActions?.visibility == View.VISIBLE
            if (isCurrentlyVisible) {
                layoutActions?.visibility = View.GONE
                btnMinimize.text = "▶"
            } else {
                layoutActions?.visibility = View.VISIBLE
                btnMinimize.text = "◀"
            }
            try {
                windowManager.updateViewLayout(view, params)
            } catch (_: Exception) {}
        }

        view.findViewById<View>(R.id.btnBarClose)?.setOnClickListener {
            hideBottomBar()
            onClose?.invoke()
        }

        // Draggable handle
        var initialX = 0
        var initialY = 0
        var touchDownX = 0f
        var touchDownY = 0f

        view.findViewById<View>(R.id.btnBottomBarDrag)?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchDownX = event.rawX
                    touchDownY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchDownX).toInt()
                    val dy = (event.rawY - touchDownY).toInt() // Standard downwards from TOP
                    params.x = initialX + dx
                    params.y = (initialY + dy).coerceIn((35 * density).toInt(), (screenHeight - 120 * density).toInt())
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding bottom bar to WindowManager: ${e.message}", e)
        }
    }

    fun hideBottomBar() {
        bottomBarView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
            bottomBarView = null
        }
    }

    // ==========================================
    // 3. Window Focus Trick for Clipboard Reading
    // ==========================================
    fun readClipboardWithFocusTrick(targetView: View?, onResult: (String?) -> Unit) {
        val v = targetView ?: bubbleView ?: bottomBarView ?: cardView
        if (v == null) {
            onResult(readClipboardDirect())
            return
        }

        val params = v.layoutParams as? WindowManager.LayoutParams
        if (params == null) {
            onResult(readClipboardDirect())
            return
        }

        val originalFlags = params.flags
        // Temporarily clear FLAG_NOT_FOCUSABLE to allow window focus
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        v.isFocusable = true
        v.isFocusableInTouchMode = true

        try {
            windowManager.updateViewLayout(v, params)
            v.requestFocus()
        } catch (e: Exception) {
            Log.w(TAG, "Failed requesting focus: ${e.message}")
        }

        var handled = false
        fun retrieveAndRestore() {
            if (handled) return
            handled = true
            val clipText = readClipboardDirect()

            // Restore original flags
            params.flags = originalFlags
            try {
                windowManager.updateViewLayout(v, params)
            } catch (e: Exception) {
                Log.w(TAG, "Failed restoring flags: ${e.message}")
            }

            onResult(clipText)
        }

        // Detect window focus change or fallback after 100ms
        v.viewTreeObserver?.addOnWindowFocusChangeListener(object : android.view.ViewTreeObserver.OnWindowFocusChangeListener {
            override fun onWindowFocusChanged(hasFocus: Boolean) {
                if (hasFocus) {
                    try {
                        v.viewTreeObserver?.removeOnWindowFocusChangeListener(this)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed removing focus listener: ${e.message}")
                    }
                    retrieveAndRestore()
                }
            }
        })

        v.postDelayed({
            retrieveAndRestore()
        }, 100)
    }

    private fun readClipboardDirect(): String? {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (cm.hasPrimaryClip()) {
                val item = cm.primaryClip?.getItemAt(0)
                val text = item?.text?.toString()?.trim()
                if (!text.isNullOrBlank()) text else null
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Direct clipboard read error: ${e.message}")
            null
        }
    }

    // ==========================================
    // 4. Reply Options Popup Overlay
    // ==========================================
    fun showReplies(
        platform: String = "LINE",
        summaryText: String,
        currentMode: AssistantMode = AssistantMode.REPLY,
        targetMessage: String? = null,
        screenshotName: String? = null,
        clipboardText: String? = null,
        alertMessage: String? = null,
        options: List<SmartReplyOption>,
        onModeSelected: (AssistantMode) -> Unit,
        onSelect: (String) -> Unit,
        onRegenerate: () -> Unit,
        onMiniPromptSubmit: ((String) -> Unit)? = null,
        onDismiss: (() -> Unit)? = null
    ) {
        // Temporarily hide trigger bubble and bottom bar while modal is active
        hideBubble()
        hideBottomBar()

        if (cardView == null) {
            val view = try {
                layoutInflater.inflate(R.layout.overlay_line_replies, null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed inflating overlay_line_replies: ${e.message}", e)
                return
            }

            val density = context.resources.displayMetrics.density
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                getOverlayLayoutType(),
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = (40 * density).toInt()
            }

            cardView = view
            try {
                windowManager.addView(view, params)
            } catch (e: Exception) {
                Log.e(TAG, "Failed adding replies card: ${e.message}", e)
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

        // 2. 4 Action Mode Buttons
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

        // 3. Error / Alert Banner
        val tvAlert = v.findViewById<TextView>(R.id.textAlertBanner)
        if (!alertMessage.isNullOrBlank()) {
            tvAlert?.visibility = View.VISIBLE
            tvAlert?.text = alertMessage
        } else {
            tvAlert?.visibility = View.GONE
        }

        // 3.5 Target Message Banner (Recognized latest partner message)
        val tvTarget = v.findViewById<TextView>(R.id.textTargetMessage)
        if (!targetMessage.isNullOrBlank()) {
            tvTarget?.visibility = View.VISIBLE
            tvTarget?.text = "🎯 返信対象: 「$targetMessage」"
        } else {
            tvTarget?.visibility = View.GONE
        }

        // 4. Screenshot Preview Banner
        val tvScreenshot = v.findViewById<TextView>(R.id.textScreenshotPreview)
        if (!screenshotName.isNullOrBlank()) {
            tvScreenshot?.visibility = View.VISIBLE
            tvScreenshot?.text = "📸 直近スクショを反映中: 「$screenshotName」"
        } else {
            tvScreenshot?.visibility = View.GONE
        }

        // 5. Clipboard Preview Banner
        val tvClipboard = v.findViewById<TextView>(R.id.textClipboardPreview)
        if (!clipboardText.isNullOrBlank()) {
            tvClipboard?.visibility = View.VISIBLE
            val snippet = if (clipboardText.length > 40) clipboardText.take(40) + "…" else clipboardText
            tvClipboard?.text = "📋 コピー文を反映中: 「$snippet」"
        } else {
            tvClipboard?.visibility = View.GONE
        }

        // 6. Mini Input Box
        val layoutMini = v.findViewById<LinearLayout>(R.id.layoutMiniInput)
        val editMini = v.findViewById<EditText>(R.id.editMiniPrompt)
        val btnMini = v.findViewById<Button>(R.id.btnMiniGenerate)

        btnMini?.setOnClickListener {
            val note = editMini?.text?.toString()?.trim()
            if (!note.isNullOrBlank()) {
                onMiniPromptSubmit?.invoke(note)
            } else {
                Toast.makeText(context, "メモを入力してください", Toast.LENGTH_SHORT).show()
            }
        }

        val showMiniInput = options.isEmpty() && alertMessage.isNullOrBlank()
        layoutMini?.visibility = if (showMiniInput) View.VISIBLE else View.GONE

        // 7. 4 Reply Options Buttons
        val btnOpts = listOf(
            v.findViewById<Button>(R.id.btnOpt1),
            v.findViewById<Button>(R.id.btnOpt2),
            v.findViewById<Button>(R.id.btnOpt3),
            v.findViewById<Button>(R.id.btnOpt4)
        )

        for (i in btnOpts.indices) {
            val btn = btnOpts[i]
            if (i < options.size) {
                val opt = options[i]
                btn.visibility = View.VISIBLE
                btn.text = "${opt.label}: ${opt.text}"
                btn.setOnClickListener {
                    copyToClipboard(opt.text)
                    onSelect(opt.text)
                    hideReplies()
                    onDismiss?.invoke()
                }
            } else {
                btn.visibility = View.GONE
            }
        }

        // Dismiss action
        v.findViewById<View>(R.id.btnClose).setOnClickListener {
            hideReplies()
            onDismiss?.invoke()
        }

        v.findViewById<View>(R.id.btnDirectType).setOnClickListener {
            if (layoutMini?.visibility == View.VISIBLE) {
                layoutMini.visibility = View.GONE
            } else {
                layoutMini?.visibility = View.VISIBLE
                editMini?.requestFocus()
            }
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
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val density = displayMetrics.density
        val currentWidth = if (view.width > 0) view.width else (70 * density).toInt()
        val margin = (16 * density).toInt()
        if (params.x + currentWidth / 2 < screenWidth / 2) {
            params.x = margin
        } else {
            params.x = (screenWidth - currentWidth - margin).coerceAtLeast(margin)
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
        hideBottomBar()
        hideReplies()
    }
}
