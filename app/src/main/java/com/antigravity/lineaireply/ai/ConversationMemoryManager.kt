package com.antigravity.lineaireply.ai

import android.content.Context
import com.antigravity.lineaireply.plugin.ConversationMemoryPlugin
import com.antigravity.lineaireply.plugin.IConversationMemoryPlugin

data class UserStyleProfile(
    val summary: String,
    val sampleQuotes: List<String>,
    val totalLearnedCount: Int
)

data class PartnerDossier(
    val name: String,
    val location: String, // e.g. "湘南・茅ヶ崎", "横浜", "不明"
    val personalityType: String, // e.g. "ちょいワガママ・構ってちゃん", "ノリ重視・フランク", "警戒心強め・慎重"
    val strategy: String, // Professional communication strategy
    val accumulatedMessageCount: Int
)

/**
 * Manages long-term conversation memory, user personal style learning,
 * and psychological/demographic profiling of chat partners.
 * Fully modularized and backed by [IConversationMemoryPlugin].
 */
class ConversationMemoryManager(
    private val context: Context,
    private val plugin: IConversationMemoryPlugin = ConversationMemoryPlugin(context)
) : IConversationMemoryPlugin by plugin {

    companion object {
        const val TAG = "ConvMemoryManager"
        const val MAX_ACCUMULATED_TURNS = ConversationMemoryPlugin.MAX_ACCUMULATED_TURNS
    }
}
