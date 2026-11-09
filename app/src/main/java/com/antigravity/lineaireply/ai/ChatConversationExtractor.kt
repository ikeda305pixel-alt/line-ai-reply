package com.antigravity.lineaireply.ai

import android.view.accessibility.AccessibilityNodeInfo

data class ChatContext(
    val platform: String, // "LINE" or "Bumble"
    val conversationHistory: List<String>,
    val latestPartnerMessage: String,
    val profileSnippet: String,
    val isEnglishPartner: Boolean,
    val rallyCount: Int,
    val shouldSuggestLineInvite: Boolean,
    val inputNode: AccessibilityNodeInfo?
)

/**
 * Extracts conversation log, partner profile snippet, and input fields from LINE and Bumble.
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
        "戻る", "設定", "スタンプを送信しました", "写真を送信しました", "動画を送信しました"
    )

    fun extract(rootNode: AccessibilityNodeInfo?, packageName: String): ChatContext? {
        if (rootNode == null) return null

        val platform = when (packageName) {
            PKG_LINE -> "LINE"
            PKG_BUMBLE -> "Bumble"
            else -> "LINE"
        }

        val inputNode = findInputEditText(rootNode)

        val rawList = mutableListOf<String>()
        traverseTexts(rootNode, rawList)

        // Filter out system labels, dates, timestamps
        val cleanList = rawList.filter { !isSystemLabel(it) }

        if (cleanList.isEmpty()) {
            return null
        }

        val profileSnippet = if (platform == "Bumble") {
            cleanList.filter { it.length > 30 }.take(2).joinToString(" ")
        } else ""

        val combinedSample = (cleanList.takeLast(5) + profileSnippet).joinToString(" ")
        val isEnglish = detectIfEnglish(combinedSample)

        // The latest meaningful message
        val latest = cleanList.last()

        val messageCount = cleanList.size
        val rallyCount = ((messageCount + 1) / 2).coerceAtLeast(1)
        val shouldSuggestLineInvite = platform == "Bumble" && (rallyCount >= 3 || messageCount >= 4)

        return ChatContext(
            platform = platform,
            conversationHistory = cleanList.takeLast(12),
            latestPartnerMessage = latest,
            profileSnippet = profileSnippet,
            isEnglishPartner = isEnglish,
            rallyCount = rallyCount,
            shouldSuggestLineInvite = shouldSuggestLineInvite,
            inputNode = inputNode
        )
    }

    private fun detectIfEnglish(sample: String): Boolean {
        val trimmed = sample.trim()
        if (trimmed.isEmpty()) return false
        val hasJapanese = trimmed.any { it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9faf' }
        if (hasJapanese) return false
        val latinLetterCount = trimmed.count { it in 'a'..'z' || it in 'A'..'Z' }
        return latinLetterCount > 8
    }

    private fun traverseTexts(node: AccessibilityNodeInfo, list: MutableList<String>) {
        val text = (node.text?.toString()?.trim() ?: node.contentDescription?.toString()?.trim() ?: "")
        if (text.length in 2..500) {
            if (list.lastOrNull() != text) {
                list.add(text)
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { traverseTexts(it, list) }
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
