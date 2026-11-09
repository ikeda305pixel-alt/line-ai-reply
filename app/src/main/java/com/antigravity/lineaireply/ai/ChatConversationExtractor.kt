package com.antigravity.lineaireply.ai

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

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
    val latestPartnerMessage: String,
    val latestMyMessage: String,
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
object ChatConversationExtractor {

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

    fun cacheProfileIfVisible(rootNode: AccessibilityNodeInfo?) {
        if (rootNode == null) return
        val profileList = mutableListOf<String>()
        scanForProfileTexts(rootNode, profileList)
        val snippet = profileList.distinct().take(4).joinToString(" | ")
        if (snippet.isNotBlank()) {
            cachedBumbleProfile = snippet
        }
    }

    fun extract(
        rootNode: AccessibilityNodeInfo?,
        packageName: String,
        screenWidth: Int = 1080,
        screenHeight: Int = 2400
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

        // 3. Extract Message Nodes with spatial bounding boxes
        val rawCandidates = mutableListOf<RawTextNode>()
        traverseChatNodes(rootNode, rawCandidates, effectiveWidth, effectiveHeight)

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

        // Classify speaker based on screen layout:
        // Left side (rect.left < width * 0.40) = 相手 (Partner)
        // Right side (rect.right > width * 0.60) = 自分 (Me)
        val messageTurns = mutableListOf<MessageTurn>()
        for (node in sortedNodes) {
            val rect = node.rect
            val text = node.text

            // Ignore centered dates or system notices
            val isCentered = rect.left > effectiveWidth * 0.25 && rect.right < effectiveWidth * 0.75
            if (isCentered && (DATE_REGEX.matches(text) || text.length < 15)) {
                continue
            }

            val isPartner = when {
                rect.left < effectiveWidth * 0.40 -> true
                rect.right > effectiveWidth * 0.60 -> false
                else -> rect.centerX() < effectiveWidth / 2
            }

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

        val latestPartnerMessage = partnerTurns.lastOrNull()?.text ?: messageTurns.last().text
        val latestMyMessage = myTurns.lastOrNull()?.text ?: ""

        val combinedSample = (messageTurns.takeLast(6).map { it.text }).joinToString(" ")
        val (isForeignOrEnglish, languageDesc) = detectLanguageBackground(combinedSample, finalProfile)

        val messageCount = messageTurns.size
        val rallyCount = ((messageCount + 1) / 2).coerceAtLeast(1)
        val shouldSuggestLineInvite = platform == "Bumble" && (rallyCount >= 3 || messageCount >= 4)

        return ChatContext(
            platform = platform,
            partnerName = partnerName,
            conversationHistory = messageTurns.takeLast(10),
            latestPartnerMessage = latestPartnerMessage,
            latestMyMessage = latestMyMessage,
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

    fun detectLanguageBackground(sample: String, profileText: String): Pair<Boolean, String> {
        val combined = "$sample $profileText".trim()
        if (combined.isEmpty()) return Pair(false, "日本語")

        val latinCount = combined.count { it in 'a'..'z' || it in 'A'..'Z' }
        val japaneseCount = combined.count { it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9faf' }

        val hasForeignKeywords = Regex(
            "from\\s+[a-z]+|lives?\\s+in|expat|native|english|speak|tokyo|japan|foreigner|travel|visit|study abroad|アメリカ|カナダ|オーストラリア|イギリス|ハーフ|海外|留学",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(combined)

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

        // Match profile prompt keywords or bio descriptions
        val matchesKeyword = PROFILE_KEYWORDS.any { lower.contains(it) }
        val isDescriptive = t.length in 18..300 && !isSystemLabel(t)

        if ((matchesKeyword || isDescriptive) && !isSystemLabel(t)) {
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
        screenHeight: Int
    ) {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        val topBound = (screenHeight * 0.08).toInt()
        val bottomBound = (screenHeight * 0.90).toInt()

        val isInMessageArea = rect.top >= topBound && rect.bottom <= bottomBound
        val rawText = (node.text?.toString()?.trim() ?: node.contentDescription?.toString()?.trim() ?: "")

        if (isInMessageArea && rawText.isNotBlank()) {
            val isFullWidthContainer = rect.width() > screenWidth * 0.95 && rect.height() > screenHeight * 0.3
            if (!isFullWidthContainer && !isSystemLabel(rawText)) {
                list.add(RawTextNode(rawText, rect))
            }
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { traverseChatNodes(it, list, screenWidth, screenHeight) }
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
