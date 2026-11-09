package com.antigravity.lineaireply.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.antigravity.lineaireply.ai.FreeTierGuard
import com.antigravity.lineaireply.ai.GeminiLineAssistant
import com.antigravity.lineaireply.ai.LineConversationExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class LineAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var prefs: SharedPreferences
    private lateinit var guard: FreeTierGuard
    private lateinit var assistant: GeminiLineAssistant
    private lateinit var overlayManager: LineOverlayManager

    companion object {
        const val LINE_PKG = "jp.naver.line.android"
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("line_ai_prefs", Context.MODE_PRIVATE)
        guard = FreeTierGuard(this)
        overlayManager = LineOverlayManager(this)

        val key = prefs.getString("gemini_key", "") ?: ""
        assistant = GeminiLineAssistant(key, guard)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: ""

        if (pkg != LINE_PKG) {
            overlayManager.hideAll()
            return
        }

        val root = rootInActiveWindow ?: return
        val chatContext = LineConversationExtractor.extract(root)

        if (chatContext == null) {
            overlayManager.hideAll()
            return
        }

        // Show floating bubble trigger on LINE chat screen
        overlayManager.showBubble {
            triggerReplySuggestions(chatContext)
        }

        // Also trigger if user taps directly on a message bubble
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val source = event.source
            val clickedText = source?.text?.toString()?.trim() ?: ""
            if (clickedText.length in 2..200) {
                triggerReplySuggestions(chatContext.copy(latestPartnerMessage = clickedText))
            }
        }
    }

    private fun triggerReplySuggestions(chatContext: com.antigravity.lineaireply.ai.LineChatContext) {
        val summary = if (chatContext.conversationHistory.isNotEmpty()) {
            "会話ログ ${chatContext.conversationHistory.size} 件を考慮中..."
        } else {
            "最新メッセージを分析中..."
        }

        serviceScope.launch {
            val options = assistant.generateReplies(
                chatContext.conversationHistory,
                chatContext.latestPartnerMessage
            )

            overlayManager.showReplies(
                summaryText = summary,
                options = options,
                onSelect = { selectedText ->
                    overlayManager.insertTextIntoLineInput(chatContext.inputNode, selectedText)
                },
                onRegenerate = {
                    triggerReplySuggestions(chatContext)
                },
                onDirectType = {
                    chatContext.inputNode?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                }
            )
        }
    }

    override fun onInterrupt() {
        overlayManager.hideAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayManager.hideAll()
    }
}
