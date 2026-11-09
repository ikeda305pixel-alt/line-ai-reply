package com.antigravity.lineaireply.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.antigravity.lineaireply.ai.ChatConversationExtractor
import com.antigravity.lineaireply.ai.FreeTierGuard
import com.antigravity.lineaireply.ai.SmartChatAssistant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class LineAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var prefs: SharedPreferences
    private lateinit var guard: FreeTierGuard
    private lateinit var assistant: SmartChatAssistant
    private lateinit var overlayManager: SmartOverlayManager

    companion object {
        const val PKG_LINE = "jp.naver.line.android"
        const val PKG_BUMBLE = "com.bumble.app"
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("line_ai_prefs", Context.MODE_PRIVATE)
        guard = FreeTierGuard(this)
        overlayManager = SmartOverlayManager(this)

        val key = prefs.getString("gemini_key", "") ?: ""
        assistant = SmartChatAssistant(key, guard)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: ""

        if (pkg != PKG_LINE && pkg != PKG_BUMBLE) {
            overlayManager.hideAll()
            return
        }

        val root = rootInActiveWindow ?: return
        val chatContext = ChatConversationExtractor.extract(root, pkg)

        if (chatContext == null) {
            overlayManager.hideAll()
            return
        }

        // Show floating bubble trigger on active chat screen
        overlayManager.showBubble(chatContext.platform) {
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

    private fun triggerReplySuggestions(chatContext: com.antigravity.lineaireply.ai.ChatContext) {
        val summary = if (chatContext.platform == "Bumble") {
            if (chatContext.isEnglishPartner) "Bumble (English partner detected)" else "Bumble (LINE誘導案を含めて生成中)"
        } else {
            "LINE (会話ログ ${chatContext.conversationHistory.size} 件を考慮中)"
        }

        serviceScope.launch {
            val options = assistant.generateReplies(chatContext)

            overlayManager.showReplies(
                platform = chatContext.platform,
                summaryText = summary,
                options = options,
                onSelect = { selectedText ->
                    overlayManager.insertTextIntoInput(chatContext.inputNode, selectedText)
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
