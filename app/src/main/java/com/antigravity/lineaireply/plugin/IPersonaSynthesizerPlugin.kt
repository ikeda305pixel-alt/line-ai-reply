package com.antigravity.lineaireply.plugin

import com.antigravity.lineaireply.ai.ChatContext
import com.antigravity.lineaireply.ai.MessageTurn
import com.antigravity.lineaireply.ai.PartnerDossier
import com.antigravity.lineaireply.ai.SmartReplyOption
import com.antigravity.lineaireply.ai.UserStyleProfile

/**
 * Plugin interface for synthesizing high-EQ prompts and parsing reply options.
 * Blends user style ("俺スタイル"), partner profile ("相手カルテ"), and full chronological conversation history.
 */
interface IPersonaSynthesizerPlugin {

    /**
     * Synthesizes the Gemini JSON prompt injecting:
     * - Full accumulated chronological conversation history
     * - Partner dossier (location, personality, strategy)
     * - User style profile & quotes
     * - 4 persona tracks (🎯即答, 🤝転がし, 😄ツッコミ, 🚀提案/LINE移行)
     */
    fun buildPrompt(
        context: ChatContext,
        accumulatedHistory: List<MessageTurn>,
        userStyle: UserStyleProfile,
        partnerDossier: PartnerDossier,
        forceLineInvite: Boolean
    ): String

    /**
     * Parses the AI response into 4 distinct reply options.
     */
    fun parseReplies(rawResponseText: String): List<SmartReplyOption>

    /**
     * Creates context-aware fallback replies when offline or on error.
     */
    fun createFallbackReplies(
        context: ChatContext,
        accumulatedHistory: List<MessageTurn>,
        userStyle: UserStyleProfile,
        forceLineInvite: Boolean
    ): List<SmartReplyOption>
}
