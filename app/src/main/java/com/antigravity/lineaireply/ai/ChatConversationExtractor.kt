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
    val conversationHistory: List<MessageTurn>,
    val latestPartnerMessage: String,
    val latestMyMessage: String,
    val profileSnippet: String,
    val isEnglishPartner: Boolean,
    val rallyCount: Int,
    val shouldSuggestLineInvite: Boolean,
    val inputNode: AccessibilityNodeInfo?,
    val partnerMessageCount: Int,
    val userMessageCount: Int
)

/**
 * High-precision spatial conversation extractor for LINE and Bumble.
 * Distinguishes speaker based on screen coordinates (Left = Partner, Right = Me),
 * filters out timestamps, dates, and header/footer UI noise,
 * and reconstructs chronological multi-turn dialogue.
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
        "ピン留め", "通知オフ", "ブロック", "通報"
    )

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

        val rawCandidates = mutableListOf<RawTextNode>()
        traverseNodes(rootNode, rawCandidates, effectiveWidth, effectiveHeight)

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

        val profileSnippet = if (platform == "Bumble") {
            messageTurns.map { it.text }.filter { it.length > 30 }.take(2).joinToString(" ")
        } else ""

        val combinedSample = (messageTurns.takeLast(6).map { it.text } + profileSnippet).joinToString(" ")
        val isEnglish = detectIfEnglish(combinedSample)

        val messageCount = messageTurns.size
        val rallyCount = ((messageCount + 1) / 2).coerceAtLeast(1)
        val shouldSuggestLineInvite = platform == "Bumble" && (rallyCount >= 3 || messageCount >= 4)

        return ChatContext(
            platform = platform,
            conversationHistory = messageTurns.takeLast(10),
            latestPartnerMessage = latestPartnerMessage,
            latestMyMessage = latestMyMessage,
            profileSnippet = profileSnippet,
            isEnglishPartner = isEnglish,
            rallyCount = rallyCount,
            shouldSuggestLineInvite = shouldSuggestLineInvite,
            inputNode = inputNode,
            partnerMessageCount = partnerTurns.size,
            userMessageCount = myTurns.size
        )
    }

    private data class RawTextNode(
        val text: String,
        val rect: Rect
    )

    private fun traverseNodes(
        node: AccessibilityNodeInfo,
        list: MutableList<RawTextNode>,
        screenWidth: Int,
        screenHeight: Int
    ) {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        // Filter out header area (top 8%) and footer input area (bottom 10%)
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
            node.getChild(i)?.let { traverseNodes(it, list, screenWidth, screenHeight) }
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

    private fun detectIfEnglish(sample: String): Boolean {
        val trimmed = sample.trim()
        if (trimmed.isEmpty()) return false
        val hasJapanese = trimmed.any { it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9faf' }
        if (hasJapanese) return false
        val latinLetterCount = trimmed.count { it in 'a'..'z' || it in 'A'..'Z' }
        return latinLetterCount > 8
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
