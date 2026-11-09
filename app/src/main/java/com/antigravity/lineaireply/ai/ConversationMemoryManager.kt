package com.antigravity.lineaireply.ai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class UserStyleProfile(
    val summary: String,
    val sampleQuotes: List<String>,
    val totalLearnedCount: Int
)

data class PartnerDossier(
    val name: String,
    val location: String, // e.g. "渋谷・恵比寿周辺", "中央線沿線", "不明"
    val personalityType: String, // e.g. "ちょいワガママ・構ってちゃん", "ノリ重視・フランク", "警戒心強め・慎重"
    val strategy: String, // Professional communication strategy
    val accumulatedMessageCount: Int
)

/**
 * Manages long-term conversation memory, user personal style learning,
 * and psychological/demographic profiling of chat partners.
 */
class ConversationMemoryManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("line_ai_memory", Context.MODE_PRIVATE)

    companion object {
        const val TAG = "ConvMemoryManager"

        private const val KEY_USER_STYLE_SUMMARY = "user_style_summary"
        private const val KEY_USER_SAMPLES = "user_samples"
        private const val KEY_USER_LEARNED_COUNT = "user_learned_count"
        private const val KEY_PARTNER_DOSSIERS = "partner_dossiers"
        private const val KEY_ACCUMULATED_CHATS = "accumulated_chats"
    }

    /**
     * Retrieves the current learned profile of the user ("俺スタイル").
     */
    fun getUserStyle(): UserStyleProfile {
        val summary = prefs.getString(
            KEY_USER_STYLE_SUMMARY,
            "フランクなタメ口。短文（15〜30文字）でテンポ重視。ツッコミや軽いイジりが好きで、絵文字は控えめ（😂や笑を多用）。ストレートで回りくどい言い回しを嫌う。"
        ) ?: ""

        val rawSamples = prefs.getString(KEY_USER_SAMPLES, "") ?: ""
        val sampleList = if (rawSamples.isNotBlank()) {
            rawSamples.split("|||").filter { it.isNotBlank() }
        } else {
            listOf("あいてるよ！なんかある？", "渋谷で肉食べよー🥩", "それ笑うんだけど😂", "仕事終わったら連絡するわ！")
        }

        val count = prefs.getInt(KEY_USER_LEARNED_COUNT, sampleList.size)
        return UserStyleProfile(summary, sampleList, count)
    }

    /**
     * Retrieves the profile and psychological analysis for a specific partner.
     */
    fun getPartnerDossier(partnerName: String): PartnerDossier {
        val key = partnerName.trim().ifEmpty { "相手" }
        val allDossiersJson = prefs.getString(KEY_PARTNER_DOSSIERS, "{}") ?: "{}"
        try {
            val root = JSONObject(allDossiersJson)
            if (root.has(key)) {
                val obj = root.getJSONObject(key)
                return PartnerDossier(
                    name = key,
                    location = obj.optString("location", "都内・行動圏探索中"),
                    personalityType = obj.optString("personality", "ノリ重視・フランク"),
                    strategy = obj.optString("strategy", "適度にイジりつつ肯定して甘やかし、テンポよく主導権を握る"),
                    accumulatedMessageCount = obj.optInt("count", 0)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading partner dossier", e)
        }

        // Default dossier with high-EQ conversational defaults
        return PartnerDossier(
            name = key,
            location = "都内・行動圏探索中",
            personalityType = "感情表現豊か・リアクション重視",
            strategy = "相手のテンションに合わせ、共感しつつ軽くツッコミを入れて心地よく転がす",
            accumulatedMessageCount = 0
        )
    }

    /**
     * Records turns harvested in real-time from the screen into long-term memory.
     */
    fun recordScreenTurns(partnerName: String, turns: List<MessageTurn>) {
        if (turns.isEmpty()) return
        val myMessages = turns.filter { !it.isPartner }.map { it.text.trim() }.filter { it.length in 2..200 }
        val partnerMessages = turns.filter { it.isPartner }.map { it.text.trim() }.filter { it.length in 2..200 }

        if (myMessages.isNotEmpty()) {
            val currentSamples = getUserStyle().sampleQuotes.toMutableList()
            var added = false
            for (m in myMessages) {
                if (!currentSamples.contains(m)) {
                    currentSamples.add(0, m)
                    added = true
                }
            }
            if (added) {
                val trimmed = currentSamples.take(50)
                prefs.edit()
                    .putString(KEY_USER_SAMPLES, trimmed.joinToString("|||"))
                    .putInt(KEY_USER_LEARNED_COUNT, prefs.getInt(KEY_USER_LEARNED_COUNT, 0) + myMessages.size)
                    .apply()
            }
        }

        // Update partner dossier basic stats
        if (partnerMessages.isNotEmpty() && partnerName.isNotBlank()) {
            val allDossiersJson = prefs.getString(KEY_PARTNER_DOSSIERS, "{}") ?: "{}"
            try {
                val root = JSONObject(allDossiersJson)
                val obj = if (root.has(partnerName)) root.getJSONObject(partnerName) else JSONObject()
                val currentCount = obj.optInt("count", 0) + partnerMessages.size
                obj.put("count", currentCount)

                // Quick heuristic detection of locations
                val combinedText = partnerMessages.joinToString(" ")
                val detectedArea = detectLocations(combinedText)
                if (detectedArea.isNotBlank() && !obj.has("location")) {
                    obj.put("location", detectedArea)
                }

                root.put(partnerName, obj)
                prefs.edit().putString(KEY_PARTNER_DOSSIERS, root.toString()).apply()
            } catch (e: Exception) {
                Log.e(TAG, "Error updating partner dossier", e)
            }
        }
    }

    /**
     * Imports and learns from a raw text log (e.g. LINE export file or pasted log).
     */
    suspend fun importAndLearnLog(
        rawText: String,
        apiKey: String,
        partnerHint: String = ""
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (rawText.isBlank()) return@withContext Pair(false, "テキストが空です")

        // Parse log lines
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val myMessages = mutableListOf<String>()
        val partnerMessages = mutableListOf<String>()
        var resolvedPartnerName = partnerHint.ifEmpty { "相手" }

        // Detect partner name from LINE export header: e.g. "[LINE] 田中とのトーク履歴"
        val headerRegex = Regex("\\[LINE\\]\\s*(.+)とのトーク履歴")
        for (line in lines.take(10)) {
            val match = headerRegex.find(line)
            if (match != null) {
                resolvedPartnerName = match.groupValues[1].trim()
                break
            }
        }

        val lineTabRegex = Regex("^(\\d{1,2}:\\d{2})?\\s*([^\t]+)\t(.*)$")
        val lineColonRegex = Regex("^([^\t:：]{1,15})[:：](.*)$")

        for (line in lines) {
            val tabMatch = lineTabRegex.find(line)
            if (tabMatch != null) {
                val speaker = tabMatch.groupValues[2].trim()
                val content = tabMatch.groupValues[3].trim()
                if (speaker.contains("自分") || speaker.contains("Me", ignoreCase = true)) {
                    myMessages.add(content)
                } else if (speaker.contains(resolvedPartnerName) || resolvedPartnerName == "相手") {
                    partnerMessages.add(content)
                }
                continue
            }

            val colonMatch = lineColonRegex.find(line)
            if (colonMatch != null) {
                val speaker = colonMatch.groupValues[1].trim()
                val content = colonMatch.groupValues[2].trim()
                if (speaker.contains("自分") || speaker.contains("俺") || speaker.contains("私")) {
                    myMessages.add(content)
                } else {
                    partnerMessages.add(content)
                }
            }
        }

        // If no structured format found, treat raw text as sample messages
        if (myMessages.isEmpty() && partnerMessages.isEmpty()) {
            for (line in lines) {
                if (line.length in 3..150 && !line.startsWith("202") && !line.startsWith("[")) {
                    myMessages.add(line)
                }
            }
        }

        val myClean = myMessages.filter { it.length in 2..200 }.distinct()
        val partnerClean = partnerMessages.filter { it.length in 2..200 }.distinct()

        if (myClean.isEmpty() && partnerClean.isEmpty()) {
            return@withContext Pair(false, "会話メッセージを検出できませんでした。LINEのトーク履歴（.txt）または会話ログ形式で入力してください。")
        }

        // 1. Save samples locally immediately
        val existingSamples = getUserStyle().sampleQuotes.toMutableList()
        val combinedSamples = (myClean + existingSamples).distinct().take(60)
        prefs.edit()
            .putString(KEY_USER_SAMPLES, combinedSamples.joinToString("|||"))
            .putInt(KEY_USER_LEARNED_COUNT, combinedSamples.size)
            .apply()

        // 2. Perform deep AI analysis using Gemini 1.5 Flash if API key is provided
        if (apiKey.isNotBlank()) {
            try {
                val sampleUserSnippet = myClean.take(40).joinToString("\n") { "・$it" }
                val samplePartnerSnippet = partnerClean.take(40).joinToString("\n") { "・$it" }

                val prompt = """
                あなたは人間の心理分析とコミュニケーション心理学の世界的権威です。
                以下のLINE過去ログから、【ユーザー本人（自分）の文体・口調】と【相手（$resolvedPartnerName）の性格・生活圏・攻略法】を深層分析してください。

                【ユーザー本人（自分）の発言サンプル】
                $sampleUserSnippet

                【相手（$resolvedPartnerName）の発言サンプル】
                $samplePartnerSnippet

                以下のJSONフォーマットのみを出力してください（Markdown等不要）：
                {
                  "user_style_summary": "ユーザーの口調・文体の精密な特徴（語尾、テンポ、一人称、ツッコミ癖、絵文字傾向、タメ口度など）を100文字程度で要約",
                  "partner_location": "相手の発言から推定される生活圏・生息エリア（例: 渋谷・恵比寿、中目黒、中央線沿線、横浜など。推測できなければ『都内近郊』）",
                  "partner_personality": "相手の性格・人間性の分析（例: わがまま、めんどくさい構ってちゃん、ノリ重視、警戒心強め、サバサバ系、寂しがりなど）",
                  "partner_strategy": "プロのコミュニケーターとして相手を最も心地よく転がし、主導権を握るための具体的な対応方針"
                }
                """.trimIndent()

                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()

                val cleanKey = SmartChatAssistant.sanitizeApiKey(apiKey)
                val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$cleanKey"
                val body = JSONObject().apply {
                    val parts = JSONArray().put(JSONObject().put("text", prompt))
                    put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                    put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
                }

                val req = Request.Builder()
                    .url(endpoint)
                    .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                val resp = client.newCall(req).execute()
                val respStr = resp.body?.string() ?: ""

                if (resp.isSuccessful) {
                    val root = JSONObject(respStr)
                    val candidates = root.optJSONArray("candidates")
                    val contentObj = candidates?.optJSONObject(0)?.optJSONObject("content")
                    val partsArray = contentObj?.optJSONArray("parts")
                    var rawText = partsArray?.optJSONObject(0)?.optString("text", "") ?: ""

                    if (rawText.startsWith("```")) {
                        val firstNewline = rawText.indexOf('\n')
                        if (firstNewline != -1) {
                            rawText = rawText.substring(firstNewline + 1)
                        }
                        if (rawText.endsWith("```")) {
                            rawText = rawText.substring(0, rawText.length - 3)
                        }
                    }

                    val resultJson = JSONObject(rawText.trim())
                    val userStyleSummary = resultJson.optString("user_style_summary", "")
                    val partnerLoc = resultJson.optString("partner_location", "都内")
                    val partnerPers = resultJson.optString("partner_personality", "ノリ重視")
                    val partnerStrat = resultJson.optString("partner_strategy", "共感とイジりで主導権を握る")

                    // Save AI analysis
                    if (userStyleSummary.isNotBlank()) {
                        prefs.edit().putString(KEY_USER_STYLE_SUMMARY, userStyleSummary).apply()
                    }

                    val allDossiersJson = prefs.getString(KEY_PARTNER_DOSSIERS, "{}") ?: "{}"
                    val allRoot = JSONObject(allDossiersJson)
                    val dossierObj = JSONObject().apply {
                        put("location", partnerLoc)
                        put("personality", partnerPers)
                        put("strategy", partnerStrat)
                        put("count", partnerClean.size)
                    }
                    allRoot.put(resolvedPartnerName, dossierObj)
                    prefs.edit().putString(KEY_PARTNER_DOSSIERS, allRoot.toString()).apply()

                    return@withContext Pair(
                        true,
                        "学習完了！\n・自分の発言: ${myClean.size}件を吸収\n・相手（$resolvedPartnerName）の発言: ${partnerClean.size}件を分析\n\n【俺スタイル】\n$userStyleSummary\n\n【相手プロファイル】\n生息エリア: $partnerLoc\n性格傾向: $partnerPers\nプロ対応方針: $partnerStrat"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "AI profiling failed, using heuristic analysis", e)
            }
        }

        // Fallback local heuristic profiling
        val localSummary = "タメ口中心、短文ストレート。ツッコミとフランクな会話を好むスタイル（学習ログ ${myClean.size} 件）。"
        prefs.edit().putString(KEY_USER_STYLE_SUMMARY, localSummary).apply()

        return@withContext Pair(
            true,
            "ローカル学習完了！自分の発言 ${myClean.size} 件、相手の発言 ${partnerClean.size} 件を吸収しました。"
        )
    }

    private fun detectLocations(text: String): String {
        val areas = listOf(
            "渋谷", "新宿", "池袋", "恵比寿", "中目黒", "代官山", "六本木", "銀座", "表参道",
            "下北沢", "三軒茶屋", "吉祥寺", "品川", "横浜", "川崎", "上野", "秋葉原", "中央線", "井の頭線"
        )
        val found = areas.filter { text.contains(it) }
        return if (found.isNotEmpty()) found.joinToString("・") else ""
    }
}
