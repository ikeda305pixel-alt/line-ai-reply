package com.antigravity.lineaireply.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
import com.antigravity.lineaireply.R
import com.antigravity.lineaireply.ai.SmartReplyOption

class SmartOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // Floating Trigger Bubble (💬)
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    // Reply Options Card
    private var cardView: View? = null

    fun showBubble(platform: String, onClick: () -> Unit) {
        if (bubbleView != null) return

        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.overlay_bubble_trigger, null)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = 24
            y = 100
        }

        bubbleParams = params
        bubbleView = view

        val bubbleIcon = view.findViewById<TextView>(R.id.bubbleIcon)
        if (platform == "Bumble") {
            bubbleIcon.setBackgroundColor(Color.parseColor("#FFC629"))
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
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(event.rawX - touchDownX) > 10 || Math.abs(event.rawY - touchDownY) > 10) {
                        isClick = false
                    }
                    params.x = initialX - (event.rawX - touchDownX).toInt()
                    params.y = initialY + (event.rawY - touchDownY).toInt()
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
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
        } catch (_: Exception) {}
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
        platform: String,
        summaryText: String,
        options: List<SmartReplyOption>,
        onSelect: (String) -> Unit,
        onRegenerate: () -> Unit,
        onDirectType: () -> Unit
    ) {
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
                y = 120
            }

            cardView = view
            try {
                windowManager.addView(view, params)
            } catch (_: Exception) {
                return
            }
        }

        val v = cardView ?: return
        v.findViewById<TextView>(R.id.textContextSummary).text = summaryText

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
                    onSelect(opt.text)
                    hideReplies()
                }
            } else {
                btn.visibility = View.GONE
            }
        }

        v.findViewById<View>(R.id.btnClose).setOnClickListener {
            hideReplies()
        }

        v.findViewById<View>(R.id.btnDirectType).setOnClickListener {
            hideReplies()
            onDirectType()
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

    fun insertTextIntoInput(inputNode: AccessibilityNodeInfo?, text: String): Boolean {
        if (inputNode == null) return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return ok
    }

    fun hideAll() {
        hideBubble()
        hideReplies()
    }
}
