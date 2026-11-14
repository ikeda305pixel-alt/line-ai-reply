package com.antigravity.lineaireply.plugin

import android.util.Log
import com.antigravity.lineaireply.ai.SmartReplyOption
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persona Synthesizer Plugin.
 * Responsibilities:
 * - Parses JSON responses with resilient error recovery.
 */
class PersonaSynthesizerPlugin : IPersonaSynthesizerPlugin {

    companion object {
        const val TAG = "PersonaSynthesizer"
        const val USER_LINE_LINK = "https://line.me/ti/p/TYxbDIk4sd"
        const val JA_LINE_INVITE = "予定立てやすいしLINE交換しよー！\n$USER_LINE_LINK"
        const val EN_LINE_INVITE = "It's much easier to plan and stay in touch on LINE! Let's connect here: $USER_LINE_LINK"
    }

    override fun parseReplies(rawResponseText: String): List<SmartReplyOption> {
        var text = rawResponseText.trim()
        if (text.startsWith("```")) {
            val firstNewline = text.indexOf('\n')
            if (firstNewline != -1) {
                text = text.substring(firstNewline + 1)
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length - 3)
            }
            text = text.trim()
        }

        val list = mutableListOf<SmartReplyOption>()

        if (text.startsWith("{")) {
            try {
                val obj = JSONObject(text)
                val candidateKeys = listOf("options", "replies", "candidates", "answers", "data")
                var array: JSONArray? = null
                for (k in candidateKeys) {
                    if (obj.has(k)) {
                        array = obj.optJSONArray(k)
                        if (array != null) break
                    }
                }
                if (array != null) {
                    for (i in 0 until array.length().coerceAtMost(4)) {
                        val item = array.getJSONObject(i)
                        list.add(
                            SmartReplyOption(
                                label = item.optString("label", "案 ${i + 1}"),
                                text = item.optString("text", "")
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed parsing JSONObject wrapper: ${e.message}")
            }
        } else if (text.startsWith("[")) {
            try {
                val array = JSONArray(text)
                for (i in 0 until array.length().coerceAtMost(4)) {
                    val item = array.getJSONObject(i)
                    list.add(
                        SmartReplyOption(
                            label = item.optString("label", "案 ${i + 1}"),
                            text = item.optString("text", "")
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed parsing JSONArray: ${e.message}")
            }
        }

        return list
    }
}

