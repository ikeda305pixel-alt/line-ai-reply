package com.antigravity.lineaireply.plugin

import android.view.accessibility.AccessibilityNodeInfo
import com.antigravity.lineaireply.ai.ChatContext

/**
 * Plugin interface for harvesting messages from accessibility hierarchy.
 * Responsibilities:
 * - Geometric speaker attribution (Left = Partner, Right = Me).
 * - Multi-language and foreigner detection.
 * - Bumble profile bio & prompt caching.
 * - Partner name extraction.
 */
interface IConversationHarvesterPlugin {
    fun extract(
        rootNode: AccessibilityNodeInfo?,
        packageName: String,
        screenWidth: Int,
        screenHeight: Int
    ): ChatContext?

    fun cacheProfileIfVisible(rootNode: AccessibilityNodeInfo?)

    fun detectLanguageBackground(sample: String, profileText: String): Pair<Boolean, String>
}
