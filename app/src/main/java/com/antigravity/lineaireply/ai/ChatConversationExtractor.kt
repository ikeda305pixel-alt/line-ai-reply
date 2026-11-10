package com.antigravity.lineaireply.ai

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

import com.antigravity.lineaireply.plugin.IConversationHarvesterPlugin

data class MessageTurn(
    val speaker: String, // "相手" or "自分"
    val text: String,
    val isPartner: Boolean,
    val top: Int
)

data class ChatContext(
    val platform: String, // "LINE" or "Bumble"
    val partnerName: String,
    val conversationHistory: List<MessageTurn>,
    val allMessageTurns: List<MessageTurn> = emptyList(),
    val latestPartnerMessage: String,
    val latestMyMessage: String,
    val lastMessageIsMe: Boolean = false,
    val profileSnippet: String,
    val isEnglishPartner: Boolean,
    val languageDesc: String,
    val rallyCount: Int,
    val shouldSuggestLineInvite: Boolean,
    val inputNode: AccessibilityNodeInfo?,
    val partnerMessageCount: Int,
    val userMessageCount: Int
)

/**
 * Spatial conversation & profile extractor for LINE and Bumble.
 * Features:
 * - Left/Right screen coordinate speaker attribution (Left = Partner, Right = Me).
 * - Full profile bio & prompt extraction for Bumble matches (cached across screen views).
 * - Multi-language and foreigner detection (English, bilingual, Japanese).
 * - Partner name extraction from screen header.
 */
object ChatConversationExtractor : IConversationHarvesterPlugin {

    const val PKG_LINE = "jp.naver.line.android"
    const val PKG_BUMBLE = "com.bumble.app"

    private val TIMESTAMP_REGEX = Regex("^(午前|午後)?\\s*\\d{1,2}:\\d{2}$")
    private val DATE_REGEX = Regex("^(\\d{1,4}[年/.-])?\\d{1,2}[月/.-]\\d{1,2}(日)?(\\s*\\(.*\\))?$")
    private val SYSTEM_WORDS = setOf(
        "line", "bumble", "送信", "send", "キャンセル", "cancel", "既読", "未読", "送信中",
        "通話", "音声通話", "ビデオ通話", "アルバム", "ノート", "イベント", "スタンプ", "写真", "動画",
        "メッセージを入力", "メッセージを入力してください", "type a message", "type something",
        "ホーム", "トーク", "タイムライン", "voom", "ニュース", "ウォレット", "メニュー", "検索",
        "戻る", "設定", "スタンプを送信しました", "写真を送信しました", "動画を送信しました",
        "ピン留め", "通知オフ", "ブロック", "通報", "matches", "chats", "swipe", "liked", "filter"
    )

    private val PROFILE_KEYWORDS = setOf(
        "about me", "my bio", "lives in", "from", "hometown", "work", "job", "company",
        "education", "college", "university", "looking for", "languages", "interests",
        "height", "drinking", "smoking", "pets", "non-negotiable", "simple pleasures",
        "swipe right", "get along if", "excited about", "quickest way", "spotify", "instagram",
        "自己紹介", "仕事", "職業", "勤務先", "出身", "居住地", "学校", "学歴", "恋愛観",
        "言語", "身長", "血液型", "お酒", "タバコ", "ペット", "趣味", "興味", "プロンプト", "ステータスメッセージ"
    )

    // Cached profile across views in Bumble
    private var cachedBumbleProfile: String = ""

    override fun cacheProfileIfVisible(rootNode: AccessibilityNodeInfo?) {
        if (rootNode == null) return
        val profileList = mutableListOf<String>()
        scanForProfileTexts(rootNode, profileList)
        val snippet = profileList.distinct().take(4).joinToString(" | ")
        if (snippet.isNotBlank()) {
            cachedBumbleProfile = snippet
        }
    }

    override fun extract(
        rootNode: AccessibilityNodeInfo?,
        packageName: String,
        screenWidth: Int,
        screenHeight: Int
    ): ChatContext? {
        if (rootNode == null) return null

        val platform = when (packageName) {
            PKG_LINE -> "LINE"
            PKG_BUMBLE -> "Bumble"
            else -> "LINE"
        }

        val effectiveWidth = if (screenWidth > 0) screenWidth else 1080
        val effectiveHeight = if (screenHeight > 0) screenHeight else 2400

        val inputNode = findInputEditText(rootNode)
        val inputRect = Rect()
        inputNode?.getBoundsInScreen(inputRect)
        val inputTopBound = if (inputRect.top > 0) inputRect.top else (effectiveHeight * 0.88).toInt()

        // 1. Extract Partner Name from top header
        val partnerName = extractHeaderTitle(rootNode, effectiveHeight)

        // 2. Extract Profile Snippets (especially for Bumble)
        val visibleProfileList = mutableListOf<String>()
        scanForProfileTexts(rootNode, visibleProfileList)
        val visibleProfile = visibleProfileList.distinct().take(4).joinToString(" | ")
        if (visibleProfile.isNotBlank()) {
            cachedBumbleProfile = visibleProfile
        }
        val finalProfile = if (visibleProfile.isNotBlank()) visibleProfile else cachedBumbleProfile

        // 3. Extract Message Nodes with spatial bounding boxes (strictly above the input field)
        val rawCandidates = mutableListOf<RawTextNode>()
        traverseChatNodes(rootNode, rawCandidates, effectiveWidth, effectiveHeight, inputTopBound)

        // Deduplicate parent-child identical texts within close vertical coordinates
        val deduplicated = mutableListOf<RawTextNode>()
        for (cand in rawCandidates) {
            val duplicate = deduplicated.any {
                it.text == cand.text && Math.abs(it.rect.top - cand.rect.top) < 60
            }
            if (!duplicate) {
                deduplicated.add(cand)
            }
        }

        // Sort chronologically by vertical screen position (top to bottom)
        val sortedNodes = deduplicated.sortedBy { it.rect.top }

        // Classify speaker strictly based on immutable screen layout:
        // In LINE and Bumble:
        // - Right side (自分/Me): Right margin is small (rightMargin <= 12% width) and rect.right >= 88% width.
        //   Even when the user's message is 3-4 lines long and stretches to the left, rect.right is ALWAYS flush right.
        // - Left side (相手/Partner): Starts on the left (rect.left <= 25% width, next to avatar icon)
        //   and NEVER reaches the right margin (rightMargin > 12% width, reserved for timestamps/read receipts).
        val messageTurns = mutableListOf<MessageTurn>()
        for (node in sortedNodes) {
            val rect = node.rect
            val text = node.text

            // Ignore centered dates, timestamps, or system notices
            val isCentered = rect.left > effectiveWidth * 0.20 && rect.right < effectiveWidth * 0.80 &&
                    (rect.centerX() > effectiveWidth * 0.40 && rect.centerX() < effectiveWidth * 0.60)
            if (isCentered && (DATE_REGEX.matches(text) || text.length < 12 || isSystemLabel(text))) {
                continue
            }

            val rightMargin = effectiveWidth - rect.right

            val isMe = (rightMargin <= effectiveWidth * 0.12) || (rect.right >= effectiveWidth * 0.88)
            val isPartner = !isMe

            messageTurns.add(
                MessageTurn(
                    speaker = if (isPartner) "相手" else "自分",
                    text = text,
                    isPartner = isPartner,
                    top = rect.top
                )
            )
        }

        if (messageTurns.isEmpty()) {
            return null
        }

        val partnerTurns = messageTurns.filter { it.isPartner }
        val myTurns = messageTurns.filter { !it.isPartner }

        // STRICT TARGETING:
        // The reply MUST answer the partner's chat (Left side), NEVER the user's own message (Right side)!
        val latestPartnerMessage = partnerTurns.lastOrNull()?.text ?: ""
        val latestMyMessage = myTurns.lastOrNull()?.text ?: ""

        // Check if the latest message on screen was sent by Me
        val lastTurn = messageTurns.lastOrNull()
        val lastMessageIsMe = lastTurn != null && !lastTurn.isPartner

        val combinedSample = (messageTurns.takeLast(6).map { it.text }).joinToString(" ")
        val (isForeignOrEnglish, languageDesc) = detectLanguageBackground(combinedSample, finalProfile)

        val messageCount = messageTurns.size
        val rallyCount = ((messageCount + 1) / 2).coerceAtLeast(1)
        val shouldSuggestLineInvite = platform == "Bumble" && (rallyCount >= 3 || messageCount >= 4)

        return ChatContext(
            platform = platform,
            partnerName = partnerName,
            conversationHistory = messageTurns.takeLast(10),
            allMessageTurns = messageTurns,
            latestPartnerMessage = latestPartnerMessage,
            latestMyMessage = latestMyMessage,
            lastMessageIsMe = lastMessageIsMe,
            profileSnippet = finalProfile,
            isEnglishPartner = isForeignOrEnglish,
            languageDesc = languageDesc,
            rallyCount = rallyCount,
            shouldSuggestLineInvite = shouldSuggestLineInvite,
            inputNode = inputNode,
            partnerMessageCount = partnerTurns.size,
            userMessageCount = myTurns.size
        )
    }

    override fun detectLanguageBackground(sample: String, profileText: String): Pair<Boolean, String> {
        val combined = "$sample $profileText".trim()
        if (combined.isEmpty()) return Pair(false, "日本語")

        // Strip URLs (e.g. LINE invite link, instagram URL) so they don't skew Latin count
        val cleanText = combined.replace(Regex("https?://\\S+"), " ")

        val latinCount = cleanText.count { it in 'a'..'z' || it in 'A'..'Z' }
        val japaneseCount = cleanText.count { it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9faf' }

        val hasForeignKeywords = Regex(
            "from\\s+[a-z]+|lives?\\s+in|expat|native|english|speak|tokyo|japan|foreigner|travel|visit|study abroad|アメリカ|カナダ|オーストラリア|イギリス|ハーフ|海外|留学",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(cleanText)

        val isEnglishDominant = latinCount > 20 && (japaneseCount == 0 || latinCount > japaneseCount * 2)
        val isForeigner = isEnglishDominant || (latinCount > 10 && hasForeignKeywords)

        val desc = when {
            isEnglishDominant -> "英語"
            isForeigner -> "外国人 / バイリンガル"
            else -> "日本語"
        }

        return Pair(isForeigner, desc)
    }

    private fun extractHeaderTitle(root: AccessibilityNodeInfo, screenHeight: Int): String {
        val topLimit = (screenHeight * 0.09).toInt()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.top < topLimit && rect.bottom <= topLimit + 80) {
                val t = (node.text?.toString()?.trim() ?: "")
                if (t.length in 2..20 && !isSystemLabel(t)) {
                    return t
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return ""
    }

    private fun scanForProfileTexts(node: AccessibilityNodeInfo, list: MutableList<String>) {
        val t = (node.text?.toString()?.trim() ?: node.contentDescription?.toString()?.trim() ?: "")
        val lower = t.lowercase()

        // Match profile prompt keywords or bio descriptions specifically
        val matchesKeyword = PROFILE_KEYWORDS.any { lower.contains(it) }

        if (matchesKeyword && !isSystemLabel(t) && t.length in 4..300) {
            list.add(t)
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { scanForProfileTexts(it, list) }
        }
    }

    private data class RawTextNode(
        val text: String,
        val rect: Rect
    )

    private fun traverseChatNodes(
        node: AccessibilityNodeInfo,
        list: MutableList<RawTextNode>,
        screenWidth: Int,
        screenHeight: Int,
        inputTopBound: Int = -1
    ) {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        val cls = node.className?.toString() ?: ""
        // CRITICAL FIX: EXCLUDE INPUT FIELDS, EDITTEXT, AND USER DRAFTS COMPLETELY!
        if (node.isEditable || cls.contains("EditText", ignoreCase = true)) {
            return
        }

        // Exclude anything in the input bar or below it (stickers, send button, keyboard)
        if (inputTopBound > 0 && rect.top >= inputTopBound) {
            return
        }

        val topBound = (screenHeight * 0.08).toInt()
        val bottomBound = if (inputTopBound > 0) (inputTopBound - 8) else (screenHeight * 0.88).toInt()

        val isInMessageArea = rect.top >= topBound && rect.bottom <= bottomBound
        val rawText = (node.text?.toString()?.trim() ?: node.contentDescription?.toString()?.trim() ?: "")

        if (isInMessageArea && rawText.isNotBlank()) {
            val isFullWidthContainer = rect.width() > screenWidth * 0.95 && rect.height() > screenHeight * 0.3
            if (!isFullWidthContainer && !isSystemLabel(rawText)) {
                list.add(RawTextNode(rawText, rect))
            }
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { traverseChatNodes(it, list, screenWidth, screenHeight, inputTopBound) }
        }
    }

    private fun isSystemLabel(t: String): Boolean {
        val trimmed = t.trim()
        val lower = trimmed.lowercase()
        if (lower in SYSTEM_WORDS) return true
        if (TIMESTAMP_REGEX.matches(trimmed)) return true
        if (DATE_REGEX.matches(trimmed)) return true
        if (lower.startsWith("202") && lower.length < 15) return true
        if (trimmed == "既読" || trimmed == "未読") return true
        return false
    }

    private fun findInputEditText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val cls = node.className?.toString() ?: ""
            if (cls.contains("EditText", ignoreCase = true) || node.isEditable) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }
}
