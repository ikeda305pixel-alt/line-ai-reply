package com.antigravity.lineaireply.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.antigravity.lineaireply.ai.ChatConversationExtractor
import com.antigravity.lineaireply.ai.ChatContext
import com.antigravity.lineaireply.ai.ConversationMemoryManager
import com.antigravity.lineaireply.ai.FreeTierGuard
import com.antigravity.lineaireply.ai.MessageTurn
import com.antigravity.lineaireply.ai.SmartChatAssistant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Autonomous Accessibility Service for LINE and Bumble.
 * Architecture:
 * - Completely zero-click background conversation harvesting (triggered on window state, scroll, and content changes).
 * - Multi-turn partner memory persistence via [ConversationMemoryManager].
 * - Full chronological conversation history injection into AI reply generation.
 * - Non-intrusive floating trigger bubble and high-EQ suggestion overlay.
 */
class LineAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var prefs: SharedPreferences
    private lateinit var guard: FreeTierGuard
    private lateinit var memoryManager: ConversationMemoryManager
    private lateinit var assistant: SmartChatAssistant
    private lateinit var overlayManager: SmartOverlayManager

    companion object {
        private const val TAG = "LineAccessService"
        const val PKG_LINE = "jp.naver.line.android"
        const val PKG_BUMBLE = "com.bumble.app"
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("line_ai_prefs", Context.MODE_PRIVATE)
        guard = FreeTierGuard(this)
        memoryManager = ConversationMemoryManager(this)
        overlayManager = SmartOverlayManager(this)

        val key = prefs.getString("gemini_key", "") ?: ""
        assistant = SmartChatAssistant(key, guard, memoryManager)
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

            // In Bumble, continuously cache profile prompts if visible
            if (pkg == PKG_BUMBLE) {
                ChatConversationExtractor.cacheProfileIfVisible(rootInActiveWindow)
            }

            // Always show floating bubble when user is in LINE / Bumble
            overlayManager.showBubble(platform) {
                handleBubbleClicked(platform, pkg)
            }

            // Autonomous zero-click continuous background harvesting (100% passive, zero unexpected scrolling):
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            ) {
                harvestCurrentScreenAsync(pkg)
            }

            // Also trigger if user taps directly on a partner message bubble on the left
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                val source = event.source
                val cls = source?.className?.toString() ?: ""
                // STRICT CHECK: Never treat input field / EditText or user right-side messages as partner click!
                if (source != null && !source.isEditable && !cls.contains("EditText", ignoreCase = true)) {
                    val rect = android.graphics.Rect()
                    source.getBoundsInScreen(rect)
                    val metrics = resources.displayMetrics
                    val effectiveWidth = metrics.widthPixels
                    val rightMargin = effectiveWidth - rect.right
                    // ONLY allow left side (Partner message, rightMargin > 15% width)!
                    if (rightMargin > effectiveWidth * 0.15) {
                        val clickedText = source.text?.toString()?.trim() ?: ""
                        if (clickedText.length in 2..200) {
                            val root = rootInActiveWindow
                            val chatContext = ChatConversationExtractor.extract(
                                root, pkg, metrics.widthPixels, metrics.heightPixels
                            )
                            if (chatContext != null) {
                                triggerReplySuggestions(chatContext.copy(latestPartnerMessage = clickedText))
                            }
                        }
                    }
                }
            }
        } else {
            // User switched to another app (Home, Chrome, etc.)
            overlayManager.hideAll()
        }
    }

    // Silent, 100% non-intrusive background screen harvester.
    // NEVER moves the screen, NEVER scrolls, NEVER switches tabs or triggers ads.

    private var lastHarvestTime = 0L
    private fun harvestCurrentScreenAsync(pkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastHarvestTime < 400) return
        lastHarvestTime = now

        serviceScope.launch(Dispatchers.IO) {
            try {
                val root = rootInActiveWindow ?: return@launch
                val metrics = resources.displayMetrics
                val chatContext = ChatConversationExtractor.extract(
                    root, pkg, metrics.widthPixels, metrics.heightPixels
                )
                if (chatContext != null) {
                    val turnsToRecord = chatContext.allMessageTurns.ifEmpty { chatContext.conversationHistory }
                    if (turnsToRecord.isNotEmpty()) {
                        memoryManager.recordScreenTurns(chatContext.partnerName, turnsToRecord)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Autonomous harvest failed: ${e.message}")
            }
        }
    }

    private fun handleBubbleClicked(platform: String, pkg: String) {
        val root = rootInActiveWindow
        val metrics = resources.displayMetrics
        val chatContext = ChatConversationExtractor.extract(
            root, pkg, metrics.widthPixels, metrics.heightPixels
        )

        if (chatContext != null) {
            val turns = chatContext.allMessageTurns.ifEmpty { chatContext.conversationHistory }
            if (turns.isNotEmpty()) {
                memoryManager.recordScreenTurns(chatContext.partnerName, turns)
            }
            triggerReplySuggestions(chatContext)
        } else {
            val fallbackContext = ChatContext(
                platform = platform,
                partnerName = "",
                conversationHistory = listOf(
                    MessageTurn("相手", "こんにちは！", true, 0)
                ),
                latestPartnerMessage = "こんにちは！最近どうですか？",
                latestMyMessage = "",
                profileSnippet = "",
                isEnglishPartner = false,
                languageDesc = "日本語",
                rallyCount = 1,
                shouldSuggestLineInvite = false,
                inputNode = null,
                partnerMessageCount = 1,
                userMessageCount = 0
            )
            triggerReplySuggestions(fallbackContext)
        }
    }

    /**
     * Finds strictly vertical chat message scrollable node (e.g. RecyclerView / ListView).
     * Explicitly EXCLUDES horizontal pagers (ViewPager, ViewPager2), tab layouts, and banner ads.
     */
    private fun findVerticalChatScrollableNode(
        root: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val cls = node.className?.toString() ?: ""
            val isHorizontalOrBanner = cls.contains("ViewPager", ignoreCase = true) ||
                    cls.contains("Horizontal", ignoreCase = true) ||
                    cls.contains("Tab", ignoreCase = true) ||
                    cls.contains("Banner", ignoreCase = true) ||
                    cls.contains("Carousel", ignoreCase = true) ||
                    cls.contains("Sticker", ignoreCase = true)

            if (!isHorizontalOrBanner && node.isScrollable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                // A chat message list is tall and predominantly vertical
                val isTallVertical = rect.height() >= (screenHeight * 0.35) && rect.height() >= (rect.width() * 0.6)
                if (isTallVertical) {
                    return node
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun autoScanAndLearnAllHistory(pkg: String) {
        serviceScope.launch(Dispatchers.Main) {
            val initialRoot = rootInActiveWindow
            if (!ChatConversationExtractor.isChatRoom(initialRoot)) {
                Toast.makeText(this@LineAccessibilityService, "⚠️ チャットのトーク画面を開いた状態で実行してください", Toast.LENGTH_SHORT).show()
                return@launch
            }

            Toast.makeText(this@LineAccessibilityService, "🤖 過去ログを遡り自動同期中...", Toast.LENGTH_SHORT).show()
            val allHarvestedTurns = mutableListOf<MessageTurn>()
            var partnerName = ""
            val metrics = resources.displayMetrics

            // Scroll backwards (upwards) up to 24 times to harvest complete historical conversation
            for (step in 1..24) {
                val currentRoot = rootInActiveWindow
                if (!ChatConversationExtractor.isChatRoom(currentRoot)) {
                    // Abort immediately if user leaves chat room to avoid any unwanted scrolling
                    break
                }

                if (currentRoot != null) {
                    val chatContext = ChatConversationExtractor.extract(
                        currentRoot, pkg, metrics.widthPixels, metrics.heightPixels
                    )
                    if (chatContext != null) {
                        if (partnerName.isEmpty() && chatContext.partnerName.isNotBlank()) {
                            partnerName = chatContext.partnerName
                        }
                        val turns = chatContext.allMessageTurns.ifEmpty { chatContext.conversationHistory }
                        for (turn in turns) {
                            if (!allHarvestedTurns.any { it.text == turn.text }) {
                                allHarvestedTurns.add(turn)
                            }
                        }
                    }
                }

                val currentScroll = findVerticalChatScrollableNode(currentRoot, metrics.widthPixels, metrics.heightPixels)
                val scrolled = currentScroll?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) ?: false
                if (!scrolled) {
                    break
                }
                kotlinx.coroutines.delay(280)
            }

            if (allHarvestedTurns.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    // Record all turns into persistent memory
                    memoryManager.recordScreenTurns(partnerName, allHarvestedTurns)
                    val key = prefs.getString("gemini_key", "") ?: ""
                    val rawPastedText = allHarvestedTurns.joinToString("\n") {
                        "${if (it.isPartner) (partnerName.ifEmpty { "相手" }) else "自分"}: ${it.text}"
                    }
                    memoryManager.importAndLearnLog(rawPastedText, key, partnerName)
                }
                val totalCount = memoryManager.getAccumulatedTurnCount(partnerName)
                Toast.makeText(
                    this@LineAccessibilityService,
                    "✅ 過去ログ全${totalCount}件を完全同期しました！（AIが全履歴を把握して返信します）",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this@LineAccessibilityService, "⚠️ メッセージを検出できませんでした。トーク画面でお試しください。", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun triggerReplySuggestions(
        chatContext: ChatContext,
        forceLineInvite: Boolean = false
    ) {
        val isBumble = chatContext.platform == "Bumble"
        val willInvite = (chatContext.shouldSuggestLineInvite || forceLineInvite)

        // ALWAYS reload the latest saved API key from SharedPreferences
        val key = prefs.getString("gemini_key", "") ?: ""
        assistant.updateApiKey(key)

        // Harvest on-screen messages into long-term memory
        val turns = chatContext.allMessageTurns.ifEmpty { chatContext.conversationHistory }
        if (turns.isNotEmpty()) {
            memoryManager.recordScreenTurns(chatContext.partnerName, turns)
        }

        val accumulatedCount = memoryManager.getAccumulatedTurnCount(chatContext.partnerName)
        val partnerDossier = memoryManager.getPartnerDossier(chatContext.partnerName)

        serviceScope.launch {
            val result = assistant.generateReplies(chatContext, forceLineInvite = willInvite)

            val summary = if (isBumble) {
                val profileTag = if (chatContext.profileSnippet.isNotBlank()) " 👤プロフ有" else ""
                val langTag = if (chatContext.isEnglishPartner) " 🌐${chatContext.languageDesc}" else ""
                val historyTag = if (accumulatedCount > 0) " 📚${accumulatedCount}件蓄積" else ""
                val rallyPrefix = "ラリー${chatContext.rallyCount}回目$profileTag$langTag$historyTag [${result.engine}]"
                if (willInvite) {
                    if (chatContext.isEnglishPartner) "$rallyPrefix Ready for LINE! 🔥"
                    else "$rallyPrefix LINE交換チャンス！🔥"
                } else {
                    rallyPrefix
                }
            } else {
                val langTag = if (chatContext.isEnglishPartner) " 🌐${chatContext.languageDesc}" else ""
                val personaTag = if (partnerDossier.personalityType.isNotBlank() && partnerDossier.personalityType != "感情表現豊か・リアクション重視") " [${partnerDossier.personalityType}]" else ""
                val historyTag = if (accumulatedCount > 0) " 📚${accumulatedCount}件蓄積" else ""
                "LINE: 俺流×プロ$personaTag$langTag$historyTag [${result.engine}]"
            }

            val profilePreview = if (chatContext.profileSnippet.isNotBlank()) {
                chatContext.profileSnippet
            } else if (partnerDossier.location != "都内・行動圏探索中") {
                "生息: ${partnerDossier.location} / 性格: ${partnerDossier.personalityType}"
            } else {
                ""
            }

            overlayManager.showReplies(
                platform = chatContext.platform,
                summaryText = summary,
                partnerMessage = chatContext.latestPartnerMessage,
                profilePreview = profilePreview,
                options = result.options,
                showQuickInviteButton = isBumble && !willInvite,
                accumulatedTurnsCount = accumulatedCount,
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
                },
                onAutoScanHistory = {
                    autoScanAndLearnAllHistory(if (isBumble) PKG_BUMBLE else PKG_LINE)
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
