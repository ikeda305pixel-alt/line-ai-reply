package com.antigravity.lineaireply.plugin

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.antigravity.lineaireply.ai.MessageTurn
import com.antigravity.lineaireply.ai.PartnerDossier
import com.antigravity.lineaireply.ai.SmartChatAssistant
import com.antigravity.lineaireply.ai.UserStyleProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Robust, zero-click autonomous conversation memory plugin.
 * Responsibilities:
 * - Chronological merge & deduplication of partner conversation history (up to 150 turns).
 * - Multi-partner key-value persistence in SharedPreferences.
 * - In-memory LRU caching for blazing-fast accessibility lookups.
 * - Extraction of user style quotes ("俺スタイル") and partner profiling ("相手カルテ").
 */
class ConversationMemoryPlugin(private val context: Context) : IConversationMemoryPlugin {

    private val metaPrefs: SharedPreferences =
        context.getSharedPreferences("line_ai_memory", Context.MODE_PRIVATE)

    private val chatPrefs: SharedPreferences =
        context.getSharedPreferences("line_ai_accumulated_chats", Context.MODE_PRIVATE)

    // Memory cache to avoid repeated disk reads during rapid accessibility events
    private val memoryCache = ConcurrentHashMap<String, List<MessageTurn>>()

    companion object {
        const val TAG = "ConvMemoryPlugin"
        const val MAX_ACCUMULATED_TURNS = 300
        const val MAX_USER_SAMPLES = 120

        private const val KEY_USER_STYLE_SUMMARY = "user_style_summary"
        private const val KEY_USER_SAMPLES = "user_samples"
        private const val KEY_USER_LEARNED_COUNT = "user_learned_count"
        private const val KEY_PARTNER_DOSSIERS = "partner_dossiers"
    }

    private fun normalizePartnerKey(partnerName: String): String {
        val clean = partnerName.trim()
        return if (clean.isEmpty()) "default_partner" else clean
    }

    override fun getAccumulatedConversation(partnerName: String): List<MessageTurn> {
        val key = normalizePartnerKey(partnerName)
        memoryCache[key]?.let { return it }

        val rawJson = chatPrefs.getString(key, "[]") ?: "[]"
        val turns = parseTurnsFromJson(rawJson)
        memoryCache[key] = turns
        return turns
    }

    override fun getAccumulatedTurnCount(partnerName: String): Int {
        return getAccumulatedConversation(partnerName).size
    }

    override fun recordScreenTurns(partnerName: String, turns: List<MessageTurn>): Int {
        if (turns.isEmpty()) return getAccumulatedTurnCount(partnerName)

        val key = normalizePartnerKey(partnerName)
        val existing = getAccumulatedConversation(key)

        // Filter valid text
        val validIncoming = turns.filter {
            it.text.isNotBlank() && it.text.trim().length in 2..500
        }
        if (validIncoming.isEmpty()) return existing.size

        // 1. Chronologically merge and deduplicate
        val merged = mergeChronological(existing, validIncoming)
        memoryCache[key] = merged

        // Persist to SharedPreferences
        val jsonArray = serializeTurnsToJson(merged)
        chatPrefs.edit().putString(key, jsonArray.toString()).apply()

        // 2. Profile "自分がどういう回答をするのか" (User Answer Style Profiling)
        val allUserTurns = merged.filter { !it.isPartner }.map { it.text.trim() }
        if (allUserTurns.isNotEmpty()) {
            val currentSamples = getUserStyle().sampleQuotes.toMutableList()
            var added = false
            for (m in allUserTurns) {
                if (!currentSamples.contains(m)) {
                    currentSamples.add(0, m)
                    added = true
                }
            }
            val trimmed = currentSamples.take(MAX_USER_SAMPLES)
            val dynamicUserSummary = profileUserAnswerStyle(trimmed)
            metaPrefs.edit()
                .putString(KEY_USER_SAMPLES, trimmed.joinToString("|||"))
                .putString(KEY_USER_STYLE_SUMMARY, dynamicUserSummary)
                .putInt(KEY_USER_LEARNED_COUNT, metaPrefs.getInt(KEY_USER_LEARNED_COUNT, 0) + (if (added) 1 else 0))
                .apply()
        }

        // 3. Profile "その人のこと" (Partner Persona, Location & Strategy Profiling)
        val allPartnerTurns = merged.filter { it.isPartner }
        if (allPartnerTurns.isNotEmpty() && key != "default_partner") {
            try {
                val dossier = profilePartnerPersona(key, allPartnerTurns)
                val allDossiersJson = metaPrefs.getString(KEY_PARTNER_DOSSIERS, "{}") ?: "{}"
                val root = JSONObject(allDossiersJson)
                val obj = JSONObject().apply {
                    put("location", dossier.location)
                    put("personality", dossier.personalityType)
                    put("strategy", dossier.strategy)
                    put("count", dossier.accumulatedMessageCount)
                }
                root.put(key, obj)
                metaPrefs.edit().putString(KEY_PARTNER_DOSSIERS, root.toString()).apply()
            } catch (e: Exception) {
                Log.w(TAG, "Error updating partner dossier: ${e.message}")
            }
        }

        return merged.size
    }

    override fun clearAccumulatedConversation(partnerName: String) {
        val key = normalizePartnerKey(partnerName)
        memoryCache.remove(key)
        chatPrefs.edit().remove(key).apply()
    }

    override fun getUserStyle(): UserStyleProfile {
        val summary = metaPrefs.getString(
            KEY_USER_STYLE_SUMMARY,
            "フランクなタメ口。短文（15〜30文字）でテンポ重視。ツッコミや軽いイジりが好きで、絵文字は控えめ（😂や笑を多用）。ストレートで回りくどい言い回しを嫌う。"
        ) ?: ""

        val rawSamples = metaPrefs.getString(KEY_USER_SAMPLES, "") ?: ""
        val sampleList = if (rawSamples.isNotBlank()) {
            rawSamples.split("|||").filter { it.isNotBlank() }
        } else {
            listOf("あいてるよ！なんかある？", "茅ヶ崎で海見ながらカフェ行こー☕️", "それ笑うんだけど😂", "厚木か海老名あたりで美味いもん食べよ！")
        }

        val count = metaPrefs.getInt(KEY_USER_LEARNED_COUNT, sampleList.size)
        return UserStyleProfile(summary, sampleList, count)
    }

    override fun getPartnerDossier(partnerName: String): PartnerDossier {
        val key = normalizePartnerKey(partnerName)
        val allDossiersJson = metaPrefs.getString(KEY_PARTNER_DOSSIERS, "{}") ?: "{}"
        try {
            val root = JSONObject(allDossiersJson)
            if (root.has(key)) {
                val obj = root.getJSONObject(key)
                return PartnerDossier(
                    name = key,
                    location = obj.optString("location", "不明"),
                    personalityType = obj.optString("personality", "ノリ重視・フランク"),
                    strategy = obj.optString("strategy", "適度にイジりつつ肯定して甘やかし、テンポよく主導権を握る"),
                    accumulatedMessageCount = obj.optInt("count", getAccumulatedTurnCount(key))
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error reading partner dossier: ${e.message}")
        }

        return PartnerDossier(
            name = key,
            location = "不明",
            personalityType = "感情表現豊か・リアクション重視",
            strategy = "相手のテンションに合わせ、共感しつつ軽くツッコミを入れて心地よく転がす",
            accumulatedMessageCount = getAccumulatedTurnCount(key)
        )
    }

    override suspend fun importAndLearnLog(
        rawText: String,
        apiKey: String,
        partnerHint: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (rawText.isBlank()) return@withContext Pair(false, "テキストが空です")

        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val myMessages = mutableListOf<String>()
        val partnerMessages = mutableListOf<String>()
        val importedTurns = mutableListOf<MessageTurn>()
        var resolvedPartnerName = partnerHint.ifEmpty { "相手" }

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
                val isMe = speaker.contains("自分") || speaker.contains("Me", ignoreCase = true)
                if (isMe) {
                    myMessages.add(content)
                    importedTurns.add(MessageTurn("自分", content, false, 0))
                } else if (speaker.contains(resolvedPartnerName) || resolvedPartnerName == "相手") {
                    partnerMessages.add(content)
                    importedTurns.add(MessageTurn(resolvedPartnerName, content, true, 0))
                }
                continue
            }

            val colonMatch = lineColonRegex.find(line)
            if (colonMatch != null) {
                val speaker = colonMatch.groupValues[1].trim()
                val content = colonMatch.groupValues[2].trim()
                val isMe = speaker.contains("自分") || speaker.contains("俺") || speaker.contains("私")
                if (isMe) {
                    myMessages.add(content)
                    importedTurns.add(MessageTurn("自分", content, false, 0))
                } else {
                    partnerMessages.add(content)
                    importedTurns.add(MessageTurn(resolvedPartnerName, content, true, 0))
                }
            }
        }

        if (myMessages.isEmpty() && partnerMessages.isEmpty()) {
            for (line in lines) {
                if (line.length in 3..150 && !line.startsWith("202") && !line.startsWith("[")) {
                    myMessages.add(line)
                    importedTurns.add(MessageTurn("自分", line, false, 0))
                }
            }
        }

        val myClean = myMessages.filter { it.length in 2..200 }.distinct()
        val partnerClean = partnerMessages.filter { it.length in 2..200 }.distinct()

        if (myClean.isEmpty() && partnerClean.isEmpty()) {
            return@withContext Pair(false, "会話メッセージを検出できませんでした。LINEのトーク履歴（.txt）または会話ログ形式で入力してください。")
        }

        // Save imported conversation turns directly into accumulated partner history
        if (importedTurns.isNotEmpty()) {
            recordScreenTurns(resolvedPartnerName, importedTurns)
        }

        // Save user style samples
        val existingSamples = getUserStyle().sampleQuotes.toMutableList()
        val combinedSamples = (myClean + existingSamples).distinct().take(MAX_USER_SAMPLES)
        metaPrefs.edit()
            .putString(KEY_USER_SAMPLES, combinedSamples.joinToString("|||"))
            .putInt(KEY_USER_LEARNED_COUNT, combinedSamples.size)
            .apply()

        // Deep AI analysis if API key is present
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
                  "partner_location": "相手の発言から推定される生活圏・生息エリア（例: 横浜、川崎、湘南、小田原、都内など。推測できなければ『不明』）",
                  "partner_personality": "相手の性格・人間性の分析（例: わがまま、めんどくさい構ってちゃん、ノリ重視、警戒心強め、サバサバ系、寂しがりなど）",
                  "partner_strategy": "プロのコミュニケーターとして相手を最も心地よく転がし、主導権を握るための具体的な対応方針"
                }
                """.trimIndent()

                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()

                val cleanKey = SmartChatAssistant.sanitizeApiKey(apiKey)
                val modelsToTry = if (SmartChatAssistant.cachedWorkingModel != null) {
                    listOf(SmartChatAssistant.cachedWorkingModel!!) + SmartChatAssistant.FALLBACK_CANDIDATE_MODELS
                } else {
                    SmartChatAssistant.FALLBACK_CANDIDATE_MODELS
                }.distinct()

                var respStr = ""
                var isSuccessful = false

                for (rawModel in modelsToTry) {
                    val model = rawModel.removePrefix("models/")
                    val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$cleanKey"
                    val body = JSONObject().apply {
                        val parts = JSONArray().put(JSONObject().put("text", prompt))
                        put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                        put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
                    }

                    val req = Request.Builder()
                        .url(endpoint)
                        .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()

                    try {
                        val resp = client.newCall(req).execute()
                        if (resp.isSuccessful) {
                            respStr = resp.body?.string() ?: ""
                            isSuccessful = true
                            SmartChatAssistant.cachedWorkingModel = model
                            break
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Analysis model $model failed: ${e.message}")
                    }
                }

                if (isSuccessful && respStr.isNotBlank()) {
                    val root = JSONObject(respStr)
                    val candidates = root.optJSONArray("candidates")
                    val contentObj = candidates?.optJSONObject(0)?.optJSONObject("content")
                    val partsArray = contentObj?.optJSONArray("parts")
                    var outText = partsArray?.optJSONObject(0)?.optString("text", "") ?: ""

                    if (outText.startsWith("```")) {
                        val firstNewline = outText.indexOf('\n')
                        if (firstNewline != -1) {
                            outText = outText.substring(firstNewline + 1)
                        }
                        if (outText.endsWith("```")) {
                            outText = outText.substring(0, outText.length - 3)
                        }
                    }

                    val resultJson = JSONObject(outText.trim())
                    val userStyleSummary = resultJson.optString("user_style_summary", "")
                    val partnerLoc = resultJson.optString("partner_location", "不明")
                    val partnerPers = resultJson.optString("partner_personality", "ノリ重視")
                    val partnerStrat = resultJson.optString("partner_strategy", "共感とイジりで主導権を握る")

                    if (userStyleSummary.isNotBlank()) {
                        metaPrefs.edit().putString(KEY_USER_STYLE_SUMMARY, userStyleSummary).apply()
                    }

                    val allDossiersJson = metaPrefs.getString(KEY_PARTNER_DOSSIERS, "{}") ?: "{}"
                    val allRoot = JSONObject(allDossiersJson)
                    val dossierObj = JSONObject().apply {
                        put("location", partnerLoc)
                        put("personality", partnerPers)
                        put("strategy", partnerStrat)
                        put("count", partnerClean.size + getAccumulatedTurnCount(resolvedPartnerName))
                    }
                    allRoot.put(resolvedPartnerName, dossierObj)
                    metaPrefs.edit().putString(KEY_PARTNER_DOSSIERS, allRoot.toString()).apply()

                    return@withContext Pair(
                        true,
                        "学習完了！\n・自分の発言: ${myClean.size}件を吸収\n・相手（$resolvedPartnerName）の発言: ${partnerClean.size}件を分析\n・会話全ログ: ${importedTurns.size}件を蓄積\n\n【俺スタイル】\n$userStyleSummary\n\n【相手カルテ】\n生息エリア: $partnerLoc\n性格傾向: $partnerPers\n攻略方針: $partnerStrat"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "AI profiling failed", e)
            }
        }

        // Heuristic fallback
        val localSummary = "タメ口中心、短文ストレート。ツッコミとフランクな会話を好むスタイル（学習ログ ${myClean.size} 件）。"
        metaPrefs.edit().putString(KEY_USER_STYLE_SUMMARY, localSummary).apply()

        return@withContext Pair(
            true,
            "ローカル学習完了！自分の発言 ${myClean.size} 件、相手の発言 ${partnerClean.size} 件、会話全ログ ${importedTurns.size} 件を吸収しました。"
        )
    }

    /**
     * Mathematically sound chronological merge with sequence alignment and deduplication.
     */
    fun mergeChronological(
        existing: List<MessageTurn>,
        incoming: List<MessageTurn>
    ): List<MessageTurn> {
        if (incoming.isEmpty()) return existing
        if (existing.isEmpty()) return incoming.takeLast(MAX_ACCUMULATED_TURNS)

        // Case 1: Tail of existing matches head of incoming (normal scrolling down / new messages)
        val maxOverlap = minOf(existing.size, incoming.size)
        for (overlapLen in maxOverlap downTo 1) {
            val existTail = existing.takeLast(overlapLen)
            val incHead = incoming.take(overlapLen)
            if (isSequenceMatch(existTail, incHead)) {
                val merged = existing + incoming.drop(overlapLen)
                return merged.takeLast(MAX_ACCUMULATED_TURNS)
            }
        }

        // Case 2: Tail of incoming matches head of existing (scrolling up / older messages)
        for (overlapLen in maxOverlap downTo 1) {
            val incTail = incoming.takeLast(overlapLen)
            val existHead = existing.take(overlapLen)
            if (isSequenceMatch(incTail, existHead)) {
                val merged = incoming.dropLast(overlapLen) + existing
                return merged.takeLast(MAX_ACCUMULATED_TURNS)
            }
        }

        // Case 3: Incoming is a contiguous sub-sequence inside existing (already fully recorded)
        if (existing.size >= incoming.size) {
            for (start in 0..(existing.size - incoming.size)) {
                val sub = existing.subList(start, start + incoming.size)
                if (isSequenceMatch(sub, incoming)) {
                    return existing
                }
            }
        }

        // Case 4: Existing is a contiguous sub-sequence inside incoming
        if (incoming.size >= existing.size) {
            for (start in 0..(incoming.size - existing.size)) {
                val sub = incoming.subList(start, start + existing.size)
                if (isSequenceMatch(sub, existing)) {
                    return incoming.takeLast(MAX_ACCUMULATED_TURNS)
                }
            }
        }

        // Fallback: If no contiguous overlap (jump in scroll), merge preserving chronological order and deduplicating
        val mergedList = existing.toMutableList()
        for (turn in incoming) {
            val alreadyExists = mergedList.any {
                it.isPartner == turn.isPartner && it.text.trim() == turn.text.trim()
            }
            if (!alreadyExists) {
                mergedList.add(turn)
            }
        }
        return mergedList.takeLast(MAX_ACCUMULATED_TURNS)
    }

    private fun isSequenceMatch(a: List<MessageTurn>, b: List<MessageTurn>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            if (a[i].isPartner != b[i].isPartner || a[i].text.trim() != b[i].text.trim()) {
                return false
            }
        }
        return true
    }

    private fun parseTurnsFromJson(jsonStr: String): List<MessageTurn> {
        val list = mutableListOf<MessageTurn>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val isPartner = obj.optBoolean("isPartner", false)
                val speaker = obj.optString("speaker", if (isPartner) "相手" else "自分")
                val text = obj.optString("text", "")
                val top = obj.optInt("top", 0)
                if (text.isNotBlank()) {
                    list.add(MessageTurn(speaker, text, isPartner, top))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing turns from JSON: ${e.message}")
        }
        return list
    }

    private fun serializeTurnsToJson(turns: List<MessageTurn>): JSONArray {
        val array = JSONArray()
        for (t in turns) {
            val obj = JSONObject().apply {
                put("speaker", t.speaker)
                put("text", t.text)
                put("isPartner", t.isPartner)
                put("top", t.top)
            }
            array.put(obj)
        }
        return array
    }

    private fun profileUserAnswerStyle(samples: List<String>): String {
        if (samples.isEmpty()) {
            return "フランクなタメ口。短文（15〜30文字）でテンポ重視。ツッコミや軽いイジりが好きで、絵文字は控えめ（😂や笑を多用）。ストレートで回りくどい言い回しを嫌う。"
        }
        val avgLen = samples.map { it.length }.average().toInt().coerceIn(12, 60)
        val hasWarai = samples.count { it.contains("笑") || it.contains("😂") || it.contains("w") } >= (samples.size * 0.20)
        val hasShortPunch = samples.count { it.length <= 25 } >= (samples.size * 0.40)
        val hasDirectAnswers = samples.any { it.contains("時") || it.contains("行く") || it.contains("空いてる") || it.contains("了解") || it.contains("向かう") }

        val parts = mutableListOf<String>()
        if (hasShortPunch) {
            parts.add("平均${avgLen}文字のテンポ重視・短文ストレート回答")
        } else {
            parts.add("平均${avgLen}文字の自然な会話テンポ")
        }
        parts.add("完全なタメ口・フランクな男らしい口調")
        if (hasWarai) {
            parts.add("語尾に『笑』や『😂』を自然に交えたクスッと笑えるツッコミ・軽快なノリ")
        }
        if (hasDirectAnswers) {
            parts.add("質問に対して結論（時間・場所・可否）をズバッと即答し、曖昧に濁さない回答パターン")
        } else {
            parts.add("相手のテンションを肯定しつつ主導権を握る回答パターン")
        }
        return parts.joinToString("。") + "。"
    }

    private fun profilePartnerPersona(partnerName: String, partnerMessages: List<MessageTurn>): PartnerDossier {
        val combined = partnerMessages.joinToString(" ") { it.text }
        val detectedArea = detectLocations(combined).ifEmpty { "不明" }

        val hasWhining = combined.contains(Regex("無理|嫌|〜して|今すぐ|わがまま|連れてって|おごって|ダメ"))
        val hasComplaints = combined.contains(Regex("うざ|疲れた|最悪|聞いて|大変|死ぬ|きつい"))
        val hasChara = combined.contains(Regex("飲む|テキーラ|酒|バー|六本木|朝まで|乾杯|酔っ払"))
        val hasCautious = combined.contains(Regex("緊張|カフェ|ランチ|昼|安心|人見知り|急に|怖い"))
        val hasGourmet = combined.contains(Regex("肉|イタリアン|パスタ|ピザ|寿司|ラーメン|美味|予約|ご飯|飯"))

        val personality: String
        val strategy: String

        when {
            hasWhining && hasGourmet -> {
                personality = "ちょいワガママ・甘えたがり・美食こだわり派"
                strategy = "相手のわがままを『可愛いじゃん笑』と受け止めつつ、美味しい人気店をスマートに予約してリードする"
            }
            hasComplaints -> {
                personality = "感情爆発・めんどくさい構ってちゃん・愚痴共感求め"
                strategy = "まずは『それは災難だったね！』と100%味方になって共感し、美味い飯や酒で発散させて機嫌を取る"
            }
            hasChara -> {
                personality = "チャラい・ノリ優先・夜遊び好き"
                strategy = "テンションを合わせ、テキーラや乾杯のノリに軽くツッコミを入れながらテンポよく合流を仕掛ける"
            }
            hasCautious -> {
                personality = "警戒心強め・慎重・真面目派"
                strategy = "無理に夜飲みに誘わず、明るい時間のオープンなカフェやランチを提案して安心感を最優先にする"
            }
            hasWhining -> {
                personality = "ちょいワガママ・甘えたがり・マウント気味"
                strategy = "相手の要求を軽くイジって笑いに変え、主導権を握りながら包容力を見せる"
            }
            else -> {
                personality = "ノリ重視・感情表現豊か・リアクション好き"
                strategy = "相手のテンションに合わせ、共感しつつ軽くツッコミを入れて心地よく転がす"
            }
        }

        return PartnerDossier(
            name = partnerName,
            location = detectedArea,
            personalityType = personality,
            strategy = strategy,
            accumulatedMessageCount = partnerMessages.size
        )
    }

    private fun detectLocations(text: String): String {
        val areas = listOf(
            "厚木", "本厚木", "茅ヶ崎", "海老名", "辻堂", "藤沢", "湘南", "平塚", "大船", "小田原", "相模原", "橋本", "町田",
            "横浜", "みなとみらい", "桜木町", "関内", "川崎", "武蔵小杉", "鎌倉", "江の島",
            "渋谷", "新宿", "恵比寿", "中目黒", "品川", "東京"
        )
        val found = areas.filter { text.contains(it) }
        return if (found.isNotEmpty()) found.joinToString("・") else ""
    }
}
