package com.antigravity.lineaireply.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.antigravity.lineaireply.ai.ChatConversationExtractor
import com.antigravity.lineaireply.ai.ChatContext
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

        // Ignore System UI & Soft Keyboards so bubble stays visible when typing
        if (pkg.isEmpty() ||
            pkg.startsWith("com.android.systemui") ||
            pkg.contains("inputmethod", ignoreCase = true) ||
            pkg.contains("gboard", ignoreCase = true) ||
            pkg.contains("ime", ignoreCase = true)
        ) {
            return
        }

        if (pkg == PKG_LINE || pkg == PKG_BUMBLE || pkg == packageName) {
            val platform = when (pkg) {
                PKG_BUMBLE -> "Bumble"
                else -> "LINE"
            }

            // Always show floating bubble when user is in LINE / Bumble
            overlayManager.showBubble(platform) {
                handleBubbleClicked(platform, pkg)
            }

            // Also trigger if user taps directly on a message bubble in chat
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                val source = event.source
                val clickedText = source?.text?.toString()?.trim() ?: ""
                if (clickedText.length in 2..200) {
                    val root = rootInActiveWindow
                    val chatContext = ChatConversationExtractor.extract(root, pkg)
                    if (chatContext != null) {
                        triggerReplySuggestions(chatContext.copy(latestPartnerMessage = clickedText))
                    }
                }
            }
        } else {
            // User switched to another app (Home, Chrome, etc.)
            overlayManager.hideAll()
        }
    }

    private fun handleBubbleClicked(platform: String, pkg: String) {
        val root = rootInActiveWindow
        val chatContext = ChatConversationExtractor.extract(root, pkg)

        if (chatContext != null) {
            triggerReplySuggestions(chatContext)
        } else {
            // If user is on LINE friends/chat list or opening fresh
            val fallbackContext = ChatContext(
                platform = platform,
                conversationHistory = listOf("こんにちは！"),
                latestPartnerMessage = "こんにちは！最近どうですか？",
                profileSnippet = "",
                isEnglishPartner = false,
                rallyCount = 1,
                shouldSuggestLineInvite = false,
                inputNode = null
            )
            triggerReplySuggestions(fallbackContext)
        }
    }

    private fun triggerReplySuggestions(
        chatContext: ChatContext,
        forceLineInvite: Boolean = false
    ) {
        val isBumble = chatContext.platform == "Bumble"
        val willInvite = (chatContext.shouldSuggestLineInvite || forceLineInvite)

        // ALWAYS read the latest saved API key from SharedPreferences!
        val key = prefs.getString("gemini_key", "") ?: ""
        assistant.updateApiKey(key)

        serviceScope.launch {
            val result = assistant.generateReplies(chatContext, forceLineInvite = willInvite)

            val summary = if (isBumble) {
                val rallyPrefix = "ラリー${chatContext.rallyCount}回目 [${result.engine}]"
                if (willInvite) {
                    if (chatContext.isEnglishPartner) "$rallyPrefix Ready for LINE! 🔥"
                    else "$rallyPrefix LINE交換チャンス！🔥"
                } else {
                    rallyPrefix
                }
            } else {
                "LINE: ログ${chatContext.conversationHistory.size}件 [${result.engine}]"
            }

            overlayManager.showReplies(
                platform = chatContext.platform,
                summaryText = summary,
                partnerMessage = chatContext.latestPartnerMessage,
                options = result.options,
                showQuickInviteButton = isBumble && !willInvite,
                onSelect = { selectedText ->
                    overlayManager.insertTextIntoInput(chatContext.inputNode, selectedText)
                },
                onRegenerate = {
                    triggerReplySuggestions(chatContext, forceLineInvite = willInvite)
                },
                onQuickLineInvite = {
                    triggerReplySuggestions(chatContext, forceLineInvite = true)
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
