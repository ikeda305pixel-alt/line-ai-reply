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

    fun extract(rootNode: AccessibilityNodeInfo?, packageName: String): ChatContext? {
        if (rootNode == null) return null

        val platform = when (packageName) {
            PKG_LINE -> "LINE"
            PKG_BUMBLE -> "Bumble"
            else -> return null
        }

        val inputNode = findInputEditText(rootNode) ?: return null

        val textList = mutableListOf<String>()
        traverseTexts(rootNode, textList)

        if (textList.isEmpty()) return null

        // Detect if partner profile or messages indicate English
        val profileSnippet = if (platform == "Bumble") {
            textList.filter { it.length > 30 }.take(2).joinToString(" ")
        } else ""

        val combinedSample = (textList.takeLast(5) + profileSnippet).joinToString(" ")
        val isEnglish = detectIfEnglish(combinedSample)

        val latest = textList.lastOrNull { !isSystemLabel(it) } ?: textList.last()

        // Calculate rally count (message turns)
        val messageCount = textList.size
        val rallyCount = ((messageCount + 1) / 2).coerceAtLeast(1)
        // Automatically suggest LINE invite after several rallies (e.g., rally >= 3 or messages >= 4)
        val shouldSuggestLineInvite = platform == "Bumble" && (rallyCount >= 3 || messageCount >= 4)

        return ChatContext(
            platform = platform,
            conversationHistory = textList.takeLast(12),
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
        // Contains typical English words or Latin letters
        val latinLetterCount = trimmed.count { it in 'a'..'z' || it in 'A'..'Z' }
        return latinLetterCount > 10
    }

    private fun traverseTexts(node: AccessibilityNodeInfo, list: MutableList<String>) {
        val text = node.text?.toString()?.trim() ?: ""
        if (text.length in 2..400 && !isSystemLabel(text)) {
            list.add(text)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { traverseTexts(it, list) }
        }
    }

    private fun isSystemLabel(t: String): Boolean {
        val lower = t.lowercase()
        return lower in listOf(
            "line", "bumble", "送信", "send", "キャンセル", "cancel",
            "既読", "read", "通話", "スタンプ", "アルバム", "メッセージを入力", "type a message"
        ) || lower.startsWith("202")
    }

    private fun findInputEditText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val cls = node.className?.toString() ?: ""
            if (cls.contains("EditText", ignoreCase = true) && node.isEditable) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }
}
