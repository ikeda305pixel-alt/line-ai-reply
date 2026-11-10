package com.antigravity.lineaireply.plugin

import com.antigravity.lineaireply.ai.MessageTurn
import com.antigravity.lineaireply.ai.PartnerDossier
import com.antigravity.lineaireply.ai.UserStyleProfile

/**
 * Plugin interface for persistent conversation memory, chronological history accumulation,
 * user style ("俺スタイル") tracking, and partner profiling ("相手カルテ").
 */
interface IConversationMemoryPlugin {

    /**
     * Retrieves all accumulated chronological turns with a specific partner (up to 150 turns).
     */
    fun getAccumulatedConversation(partnerName: String): List<MessageTurn>

    /**
     * Gets the total count of accumulated turns stored for a specific partner.
     */
    fun getAccumulatedTurnCount(partnerName: String): Int

    /**
     * Autonomously saves newly harvested screen turns into long-term memory for [partnerName].
     * Merges chronologically with sequence alignment, deduplicates, and caps at max turns.
     * Also updates user style quotes and partner profiling heuristics.
     * @return Number of total accumulated turns stored for this partner.
     */
    fun recordScreenTurns(partnerName: String, turns: List<MessageTurn>): Int

    /**
     * Retrieves the learned user style profile ("俺スタイル").
     */
    fun getUserStyle(): UserStyleProfile

    /**
     * Retrieves the profile and psychological analysis for a specific partner.
     */
    fun getPartnerDossier(partnerName: String): PartnerDossier

    /**
     * Clears stored conversation history for a specific partner.
     */
    fun clearAccumulatedConversation(partnerName: String)

    /**
     * Learns from imported raw text logs (LINE export format or pasted text).
     */
    suspend fun importAndLearnLog(
        rawText: String,
        apiKey: String,
        partnerHint: String = ""
    ): Pair<Boolean, String>
}
