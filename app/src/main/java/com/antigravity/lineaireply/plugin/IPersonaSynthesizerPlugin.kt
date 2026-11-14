package com.antigravity.lineaireply.plugin

import com.antigravity.lineaireply.ai.SmartReplyOption

/**
 * Plugin interface for parsing AI reply options.
 */
interface IPersonaSynthesizerPlugin {
    /**
     * Parses the AI response into 4 distinct reply options.
     */
    fun parseReplies(rawResponseText: String): List<SmartReplyOption>
}

