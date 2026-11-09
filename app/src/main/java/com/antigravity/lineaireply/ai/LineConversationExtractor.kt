package com.antigravity.lineaireply.ai

import android.view.accessibility.AccessibilityNodeInfo

data class LineChatContext(
    val conversationHistory: List<String>,
    val latestPartnerMessage: String,
    val inputNode: AccessibilityNodeInfo?
)

/**
 * Extracts conversation log (history) from LINE's active chat screen.
 */
object LineConversationExtractor {

    fun extract(rootNode: AccessibilityNodeInfo?): LineChatContext? {
        if (rootNode == null) return null

        val inputNode = findInputEditText(rootNode) ?: return null

        // Collect all text messages visible in the conversation list
        val messageList = mutableListOf<String>()
        traverseMessages(rootNode, messageList)

        if (messageList.isEmpty()) return null

        val latest = messageList.last()
        return LineChatContext(
            conversationHistory = messageList.takeLast(12),
            latestPartnerMessage = latest,
            inputNode = inputNode
        )
    }

    private fun traverseMessages(node: AccessibilityNodeInfo, list: MutableList<String>) {
        val text = node.text?.toString()?.trim() ?: ""
        if (text.length in 2..300 && !isSystemLabel(text)) {
            list.add(text)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { traverseMessages(it, list) }
        }
    }

    private fun isSystemLabel(t: String): Boolean {
        val lower = t.lowercase()
        return lower in listOf("line", "送信", "キャンセル", "既読", "通話", "スタンプ", "アルバム", "メッセージを入力") ||
                lower.startsWith("202") || lower.contains("既読")
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
