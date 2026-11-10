package com.antigravity.lineaireply.ai

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

data class SmartReplyOption(
    val label: String,
    val text: String
)

data class GenerationResult(
    val options: List<SmartReplyOption>,
    val engine: String, // "Gemini 1.5 Flash" or "定型文 (理由)"
    val errorDetails: String? = null
)

class SmartChatAssistant(
    private var apiKey: String,
    private val guard: FreeTierGuard,
    private val memoryManager: ConversationMemoryManager? = null
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        const val TAG = "SmartChatAssistant"
        const val USER_LINE_LINK = "https://line.me/ti/p/TYxbDIk4sd"
        const val JA_LINE_INVITE = "予定立てやすいのでライン交換してください!\n$USER_LINE_LINK"
        const val EN_LINE_INVITE = "It's much easier to plan and stay in touch on LINE! Let's connect here: $USER_LINE_LINK"

        /**
         * Cleanses API keys: removes quotes, line breaks, leading/trailing spaces, and full-width spaces.
         */
        fun sanitizeApiKey(rawKey: String): String {
            return rawKey
                .replace("\"", "")
                .replace("'", "")
                .replace(" ", "")
                .replace("\t", "")
                .replace("\n", "")
                .replace("\r", "")
                .replace("　", "")
                .trim()
        }

        /**
         * Robustly parses reply options from Gemini response text.
         * Handles markdown fences (```json ... ```), raw JSON arrays, and wrapped JSON objects.
         */
        fun parseRepliesFromJson(rawText: String): List<SmartReplyOption> {
            var text = rawText.trim()
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

        val FALLBACK_CANDIDATE_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-2.0-flash",
            "gemini-2.0-flash-exp",
            "gemini-1.5-flash",
            "gemini-1.5-flash-latest",
            "gemini-1.5-flash-8b",
            "gemini-1.5-pro",
            "gemini-pro"
        )

        @Volatile
        var cachedWorkingModel: String? = null

        /**
         * Dynamically queries the Gemini API for supported models that have "generateContent".
         * Sorts prioritizing 2.5-flash, 2.0-flash, flash-lite, then 1.5-flash.
         */
        suspend fun fetchAvailableModels(key: String): List<String> = withContext(Dispatchers.IO) {
            val cleanKey = sanitizeApiKey(key)
            if (cleanKey.isBlank()) return@withContext emptyList()
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(6, TimeUnit.SECONDS)
                    .readTimeout(8, TimeUnit.SECONDS)
                    .build()
                val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$cleanKey"
                val req = Request.Builder().url(url).get().build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val root = JSONObject(body)
                    val modelsArray = root.optJSONArray("models") ?: return@withContext emptyList()
                    val result = mutableListOf<String>()
                    for (i in 0 until modelsArray.length()) {
                        val mObj = modelsArray.getJSONObject(i)
                        val name = mObj.optString("name", "").removePrefix("models/")
                        val methods = mObj.optJSONArray("supportedGenerationMethods")
                        var canGenerate = false
                        if (methods != null) {
                            for (j in 0 until methods.length()) {
                                if (methods.getString(j).equals("generateContent", ignoreCase = true)) {
                                    canGenerate = true
                                    break
                                }
                            }
                        }
                        if (canGenerate && name.isNotBlank()) {
                            result.add(name)
                        }
                    }

                    // Sort: prioritize 2.5-flash > 2.0-flash > flash-lite > 1.5-flash > flash > pro
                    result.sortWith { a, b ->
                        fun score(m: String): Int = when {
                            m.contains("2.5-flash") && !m.contains("lite") -> 100
                            m.contains("2.0-flash") -> 90
                            m.contains("2.5-flash-lite") -> 85
                            m.contains("flash-lite") -> 80
                            m.contains("1.5-flash") -> 70
                            m.contains("flash") -> 60
                            m.contains("pro") -> 50
                            else -> 10
                        }
                        score(b) - score(a)
                    }
                    if (result.isNotEmpty()) {
                        Log.i(TAG, "Discovered active models: ${result.take(4)}")
                    }
                    return@withContext result
                }
            } catch (e: Exception) {
                Log.w(TAG, "ListModels call failed: ${e.message}")
            }
            emptyList()
        }

        suspend fun testApiKey(rawKey: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
            val key = sanitizeApiKey(rawKey)
            if (key.isBlank()) return@withContext Pair(false, "APIキーが入力されていません。")

            val testClient = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            // 1. Dynamic model discovery from API
            val discovered = fetchAvailableModels(key)
            val modelsToTry = if (discovered.isNotEmpty()) {
                (discovered.take(5) + FALLBACK_CANDIDATE_MODELS).distinct()
            } else {
                FALLBACK_CANDIDATE_MODELS
            }

            var lastError = ""

            for (rawModel in modelsToTry) {
                val cleanModel = rawModel.removePrefix("models/")
                try {
                    val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent?key=$key"
                    val body = JSONObject().apply {
                        val parts = JSONArray().put(JSONObject().put("text", "Say OK"))
                        put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                    }
                    val req = Request.Builder()
                        .url(endpoint)
                        .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()

                    val resp = testClient.newCall(req).execute()
                    val code = resp.code
                    val respBody = resp.body?.string() ?: ""

                    if (resp.isSuccessful) {
                        cachedWorkingModel = cleanModel
                        return@withContext Pair(
                            true,
                            "✅ Gemini API通信成功！(HTTP $code)\n\n【利用モデル】$cleanModel\n\n最新モデルとの接続が確認できました。LINEおよびBumbleで高精度なAI返信が利用可能です。"
                        )
                    } else {
                        val friendlyMsg = when (code) {
                            400 -> "HTTP 400 (無効なAPIキー)\n・APIキーが間違っているか、末尾が欠けている可能性があります。\n・Google AI Studio (aistudio.google.com) で作成したキー（通常 'AIzaSy' から始まる文字列）をご確認ください。"
                            403 -> "HTTP 403 (権限・地域エラー)\n・Google CloudのGenerative Language APIが有効化されていないか、制限地域またはプロジェクトの権限設定をご確認ください。"
                            404 -> "HTTP 404 (モデル $cleanModel 未検出)"
                            429 -> "HTTP 429 (利用枠上限・レート制限)\n・Gemini APIの無料枠制限に達しました。1分ほど置いて再試行してください。"
                            else -> "HTTP $code エラー: $respBody"
                        }
                        lastError = friendlyMsg
                    }
                } catch (e: Exception) {
                    lastError = "通信例外: ${e.message}"
                }
            }
            Pair(false, lastError)
        }
    }

    fun updateApiKey(newKey: String) {
        apiKey = sanitizeApiKey(newKey)
    }

    suspend fun generateReplies(
        context: ChatContext,
        forceLineInvite: Boolean = false
    ): GenerationResult = withContext(Dispatchers.IO) {
        val currentKey = apiKey.trim()
        val shouldInvite = (context.shouldSuggestLineInvite || forceLineInvite)

        val userStyle = memoryManager?.getUserStyle() ?: UserStyleProfile(
            summary = "フランクなタメ口。短文（15〜30文字）でテンポ重視。ツッコミや軽いイジりが好きで、絵文字は控えめ（😂や笑を多用）。ストレートで回りくどい言い回しを嫌う。",
            sampleQuotes = listOf("あいてるよ！なんかある？", "渋谷で肉食べよー🥩", "それ笑うんだけど😂", "仕事終わったら連絡するわ！"),
            totalLearnedCount = 4
        )

        val partnerDossier = memoryManager?.getPartnerDossier(context.partnerName) ?: PartnerDossier(
            name = context.partnerName.ifEmpty { "相手" },
            location = "都内近郊",
            personalityType = "感情表現豊か・リアクション重視",
            strategy = "相手のテンションに合わせ、共感しつつ軽くツッコミを入れて心地よく転がす",
            accumulatedMessageCount = 0
        )

        if (currentKey.isBlank()) {
            return@withContext GenerationResult(
                options = fallbackMockReplies(context, shouldInvite, userStyle),
                engine = "定型文 (※設定画面でAPIキーを保存してください)",
                errorDetails = "APIキーが未設定です"
            )
        }

        if (!guard.canRequest()) {
            return@withContext GenerationResult(
                options = fallbackMockReplies(context, shouldInvite, userStyle),
                engine = "定型文 (無料利用枠上限)",
                errorDetails = "1日の無料利用枠（1,500回）またはレート制限に達しました"
            )
        }

        val historyText = if (context.conversationHistory.isNotEmpty()) {
            context.conversationHistory.joinToString("\n") { "[${it.speaker}]: ${it.text}" }
        } else {
            "[相手]: ${context.latestPartnerMessage}"
        }

        val isBumble = context.platform == "Bumble"
        val isEnglishOrForeign = context.isEnglishPartner

        val profileSection = if (context.profileSnippet.isNotBlank()) {
            """
            【相手のプロフィール・自己紹介・プロンプト情報】
            ${context.profileSnippet}
            """.trimIndent()
        } else ""

        val userSamplesText = userStyle.sampleQuotes.take(6).joinToString("\n") { "  ・$it" }

        val prompt = if (isBumble) {
            """
            あなたは【超一流の会話プロフェッショナル（心理学・恋愛工学・傾聴とツッコミの達人）】であり、同時に【ユーザー本人（俺）の分身】です。
            相手の性格や生活圏、めんどくささやわがままさを見極め、プロの高度なコミュ力で相手を心地よく転がしつつ、言葉遣いやテンポは【俺のテイスト】をブレンドした最高の返信案を4つ作成してください。

            【相手の深層プロファイル（人間観察・生息エリア）】
            - 相手の名前/ニックネーム: ${partnerDossier.name}
            - 推定生息エリア・生活圏: ${partnerDossier.location} (例: 渋谷・恵比寿、中目黒、中央線沿線、横浜など)
            - 相手の性格タイプ分析: ${partnerDossier.personalityType} (例: わがまま、めんどくさい構ってちゃん、ノリ重視、警戒心強め、サバサバなど)
            - プロとしての攻略・転がし方針: ${partnerDossier.strategy} (相手の機嫌を損ねず、主導権を握りながら好感度を最大化する戦略)
            $profileSection

            【言語・国際的背景】
            - 検出傾向: ${context.languageDesc} (外国人/英語話者の可能性: ${if (isEnglishOrForeign) "高" else "低/日本人"})
            - 相手が英語話者の場合: チャーミングでウィットに富んだ自然な英語で作成してください。
            - 相手が外国人で日本語を使っている場合: 親しみやすく分かりやすい日本語（または英語を交えた自然なバイリンガルトーク）にしてください。

            【俺（ユーザー本人）の口調・文体スタイル（※このテイストを必ずブレンド）】
            - 口調の特徴: ${userStyle.summary}
            - ユーザー本人の典型的な言い回し・文体サンプル:
            $userSamplesText

            【直近の対話ログ（時系列順：上から下へ）】
            $historyText

            【相手の最新メッセージ】
            "${context.latestPartnerMessage}"

            ${if (context.latestMyMessage.isNotBlank()) "【直前のあなたのメッセージ】\n\"${context.latestMyMessage}\"" else ""}

            【絶対厳守の返信生成ルール】
            1. 【コミュ力プロ × 俺テイストの融合】
               相手のわがまま・めんどくささ・チャラさを受け止め、プロの心理誘導で転がしつつ、喋り言葉は「俺が実際に送りそうなリアルな口調（語尾・テンポ・ツッコミ）」にしてください。優等生AIのような敬語や定型文は【完全禁止】です。
            2. 【プロフィール・生息エリアの自然な拾い上げ】
               相手のプロフィールや生息エリア（渋谷・恵比寿等）を自然に絡め、「ちゃんとプロフを見て興味を持ってくれている！」という安心感を与えてください。
            3. 【質問へのド直球回答】
               相手が質問している場合は、曖昧な相槌やごまかしは厳禁。具体的で明確な回答（時間、場所、料理ジャンル等）を必ず返してください。
            4. 【4つの異なる返信ペルソナ】
               - 案1 🎯【俺スタイル × ド直球即答】: ユーザー本人が言いそうなリアルな言葉遣いで、質問にズバッと答える。
               - 案2 🤝【コミュ力プロ × 傾聴・転がし】: 相手のわがまま・めんどくささを華麗にいなし、機嫌を取るプロの返し。
               - 案3 😄【俺流ツッコミ × 軽快ユーモア】: ユーザーのノリでクスッと笑わせる鋭いイジり・ツッコミ。
               - 案4 🚀【主導権掌握 × 次の提案（/ 📲 LINEへ移行）】:
                 ${if (shouldInvite) "絶妙なタイミングなので、自然な流れで以下のLINE移行文（リンク付き）を提示してください：\n" + (if (isEnglishOrForeign) "「$EN_LINE_INVITE」" else "「$JA_LINE_INVITE」") else "相手の生活圏を踏まえた具体的でスマートな提案。"}

            出力は以下のJSON配列のみ（Markdownコードブロック不要）：
            [
              {"label": "🎯 俺スタイル即答", "text": "（ユーザー本人の口調での直球返答）"},
              {"label": "🤝 プロの転がし・傾聴", "text": "（相手の性格を受け止めて機嫌を取る返し）"},
              {"label": "😄 俺流ツッコミ", "text": "（クスッと笑えるウィットなイジり）"},
              {"label": "${if (shouldInvite) "📲 LINEへ移行" else "🚀 主導権提案"}", "text": "${if (shouldInvite) (if (isEnglishOrForeign) EN_LINE_INVITE else JA_LINE_INVITE) else "（生活圏を踏まえたスマートな提案）"}"}
            ]
            """.trimIndent()
        } else {
            // LINE context: heavily weight persona, partner profiling, and user style blend
            """
            あなたは【超一流の会話プロフェッショナル（心理学・恋愛工学・傾聴とツッコミの達人）】であり、同時に【ユーザー本人（俺）の分身】です。
            相手の性格や生活圏、めんどくささやわがままさを見極め、プロの高度なコミュ力で相手を心地よく転がしつつ、言葉遣いやテンポは【俺のテイスト】をブレンドした最高の返信案を4つ作成してください。

            【相手の深層プロファイル（人間観察・生息エリア）】
            - 相手の名前/ニックネーム: ${partnerDossier.name}
            - 推定生息エリア・生活圏: ${partnerDossier.location} (例: 渋谷・恵比寿、中目黒、中央線沿線、横浜など)
            - 相手の性格タイプ分析: ${partnerDossier.personalityType} (例: わがまま、めんどくさい構ってちゃん、ノリ重視、警戒心強め、サバサバなど)
            - プロとしての攻略・転がし方針: ${partnerDossier.strategy} (相手の機嫌を損ねず、主導権を握りながら好感度を最大化する戦略)
            $profileSection

            【言語・文化的背景】
            - 検出傾向: ${context.languageDesc}
            - 相手が英語話者・英語メッセージの場合: 自然でフレンドリーな英語で返答してください。

            【俺（ユーザー本人）の口調・文体スタイル（※このテイストを必ずブレンド）】
            - 口調の特徴: ${userStyle.summary}
            - ユーザー本人の典型的な言い回し・文体サンプル:
            $userSamplesText

            【直近の会話履歴（時系列順：上から下へ）】
            $historyText

            【直前の相手のメッセージ】
            "${context.latestPartnerMessage}"

            ${if (context.latestMyMessage.isNotBlank()) "【直前のあなたのメッセージ（もしあれば）】\n\"${context.latestMyMessage}\"" else ""}

            【絶対厳守の返信生成ルール】
            1. 【コミュ力プロ × 俺テイストの融合】
               相手のわがまま・めんどくささ・チャラさを受け止め、プロの心理誘導で転がしつつ、喋り言葉は「俺が実際に送りそうなリアルな口調（語尾・テンポ・ツッコミ）」にしてください。優等生AIのような敬語や定型文は【完全禁止】です。
            2. 【生活圏・エリアを活かした提案】
               相手の生息エリアや生活圏（渋谷、新宿、目黒、恵比寿など）が判明している場合、次のアクション提案（案4）でそのエリアに自然に触れてください。
            3. 【質問へのド直球回答】
               相手が質問している場合は、案1（🎯 即答）で絶対に曖昧にごまかさず、具体的で明確な回答を返してください。
            4. 【4つの異なる返信ペルソナ】
               - 案1 🎯【俺スタイル × ド直球即答】: ユーザー本人が言いそうなリアルな言葉遣いで、質問にズバッと答える。
               - 案2 🤝【コミュ力プロ × 傾聴・転がし】: 相手のわがまま・めんどくささを華麗にいなし、機嫌を取るプロの返し。
               - 案3 😄【俺流ツッコミ × 軽快ユーモア】: ユーザーのノリでクスッと笑わせる鋭いイジり・ツッコミ。
               - 案4 🚀【主導権掌握 × 次の提案】: 相手の生活圏を踏まえた具体的でスマートな提案。

            出力は以下のJSON配列のみ（Markdownコードブロック不要）：
            [
              {"label": "🎯 俺スタイル即答", "text": "（ユーザー本人の口調での直球返答）"},
              {"label": "🤝 プロの転がし・傾聴", "text": "（相手の性格を受け止めて機嫌を取る返し）"},
              {"label": "😄 俺流ツッコミ", "text": "（クスッと笑えるウィットなイジり）"},
              {"label": "🚀 主導権提案", "text": "（生活圏を踏まえたスマートな提案）"}
            ]
            """.trimIndent()
        }

        val discoveredModels = if (cachedWorkingModel != null) {
            listOf(cachedWorkingModel!!) + FALLBACK_CANDIDATE_MODELS
        } else {
            val fetched = fetchAvailableModels(currentKey)
            if (fetched.isNotEmpty()) (fetched + FALLBACK_CANDIDATE_MODELS) else FALLBACK_CANDIDATE_MODELS
        }.distinct()

        var lastError = ""

        for (rawModel in discoveredModels) {
            val model = rawModel.removePrefix("models/")
            try {
                val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$currentKey"
                val body = JSONObject().apply {
                    val parts = JSONArray().put(JSONObject().put("text", prompt))
                    put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                    put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
                }

                val req = Request.Builder()
                    .url(endpoint)
                    .post(body.toString().toRequestBody(mediaType))
                    .build()

                val resp = client.newCall(req).execute()
                val code = resp.code
                val respStr = resp.body?.string() ?: ""

                if (!resp.isSuccessful) {
                    val friendlyMsg = when (code) {
                        400 -> "HTTP 400 (無効なAPIキー): キー形式を確認してください"
                        403 -> "HTTP 403 (権限エラー): Google CloudでGenerative Language APIが有効か確認してください"
                        404 -> "HTTP 404 (モデル $model 未検出)"
                        429 -> "HTTP 429 (利用枠制限): しばらく待って再試行してください"
                        else -> "HTTP $code: $respStr"
                    }
                    lastError = friendlyMsg
                    Log.w(TAG, "Model $model returned error: $lastError")
                    continue
                }

                guard.recordRequest()
                val root = JSONObject(respStr)
                val candidates = root.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    val feedback = root.optJSONObject("promptFeedback")
                    lastError = "生成結果が空です (ブロック理由: ${feedback?.optString("blockReason", "不明")})"
                    continue
                }

                val candidateObj = candidates.getJSONObject(0)
                val contentObj = candidateObj.optJSONObject("content")
                val partsArray = contentObj?.optJSONArray("parts")
                if (partsArray == null || partsArray.length() == 0) {
                    val finishReason = candidateObj.optString("finishReason", "不明")
                    lastError = "返信本文が空です (finishReason: $finishReason)"
                    continue
                }

                val outputText = partsArray.getJSONObject(0).optString("text", "")
                val list = parseRepliesFromJson(outputText)

                if (list.isNotEmpty()) {
                    cachedWorkingModel = model
                    return@withContext GenerationResult(
                        options = list,
                        engine = "Gemini (${model})",
                        errorDetails = null
                    )
                } else {
                    lastError = "JSONパース結果が空です: ${outputText.take(50)}"
                }
            } catch (e: Exception) {
                lastError = "例外 (${model}): ${e.message}"
                Log.e(TAG, "Call failed for $model", e)
            }
        }

        // If all models failed, return high-quality context-aware fallback
        GenerationResult(
            options = fallbackMockReplies(context, shouldInvite, userStyle),
            engine = "定型文 (通信エラー)",
            errorDetails = lastError
        )
    }

    private fun fallbackMockReplies(
        context: ChatContext,
        shouldInvite: Boolean,
        userStyle: UserStyleProfile
    ): List<SmartReplyOption> {
        val latest = context.latestPartnerMessage.trim()
        val full = (context.conversationHistory.map { it.text } + latest).joinToString(" ")
        val hasProfile = context.profileSnippet.isNotBlank()

        if (context.platform == "Bumble") {
            if (context.isEnglishPartner) {
                return if (shouldInvite) {
                    listOf(
                        SmartReplyOption("🎯 Direct Answer", "Haha absolutely! That sounds like such a great vibe 😊"),
                        SmartReplyOption("🤝 Profile & Connection", if (hasProfile) "I saw on your profile that you're into that! How long have you been doing it?" else "I totally relate to that! How is your week going so far?"),
                        SmartReplyOption("😄 Witty Banter", "Okay, now you definitely owe me the full story behind that 😄"),
                        SmartReplyOption("📲 Switch to LINE", EN_LINE_INVITE)
                    )
                } else {
                    listOf(
                        SmartReplyOption("🎯 Direct Answer", "Haha absolutely! That sounds like such a great vibe 😊"),
                        SmartReplyOption("🤝 Profile & Connection", if (hasProfile) "I saw on your profile that you're into that! What got you into it?" else "I totally relate to that! How is your week going so far?"),
                        SmartReplyOption("😄 Witty Banter", "Okay, now you definitely owe me the full story behind that 😄"),
                        SmartReplyOption("☕ Shared Topic", "By the way, what kind of things do you usually enjoy on weekends? ✨")
                    )
                }
            } else {
                return if (shouldInvite) {
                    listOf(
                        SmartReplyOption("🎯 俺スタイル即答", "あいてるよ！渋谷か恵比寿あたりなら行きやすいな🥩"),
                        SmartReplyOption("🤝 プロの転がし", "いいじゃん！そういうこだわり持ってる人めっちゃ好き笑"),
                        SmartReplyOption("😄 俺流ツッコミ", "急にわがまま発動してきたな笑 でも嫌いじゃない😂"),
                        SmartReplyOption("📲 LINEへ移行", JA_LINE_INVITE)
                    )
                } else {
                    listOf(
                        SmartReplyOption("🎯 俺スタイル即答", "それめっちゃわかる！俺もちょうどそれ食べたかったところ"),
                        SmartReplyOption("🤝 プロの転がし", "プロフ見て気になってた！普段からよく行くの？😊"),
                        SmartReplyOption("😄 俺流ツッコミ", "センス良すぎて笑う😂 もっと詳しく聞かせて！"),
                        SmartReplyOption("🚀 主導権提案", "今度おすすめの店連れてってよ！予定合わせよ〜")
                    )
                }
            }
        }

        // LINE
        if (context.isEnglishPartner) {
            return listOf(
                SmartReplyOption("🎯 Direct Answer", "Sounds awesome! Count me in 👍"),
                SmartReplyOption("🤝 Friendly Vibe", "Totally agree with you! How is everything going on your end? 😊"),
                SmartReplyOption("😄 Witty / Fun", "Haha that made my day! You always have the best stories 😂"),
                SmartReplyOption("🚀 Next Plan", "Let's definitely catch up soon! When are you free next? ✨")
            )
        }

        return when {
            latest.contains(Regex("[?？]")) && latest.contains(Regex("時|いつ|日程|日|曜|何時")) -> listOf(
                SmartReplyOption("🎯 俺スタイル即答", "19時くらいはどう？仕事終わりだからちょうど良さそう！"),
                SmartReplyOption("🤝 プロの転がし", "合わせるよ！そっちは何時くらいが一番都合いい？😊"),
                SmartReplyOption("😄 俺流ツッコミ", "早い時間から乾杯しちゃおっか笑 18時半とかいける？🍻"),
                SmartReplyOption("🚀 主導権提案", "金曜か土曜の夜ならいつでも空いてるよ！どっちがいい？")
            )
            latest.contains(Regex("[?？]")) && latest.contains(Regex("どこ|場所|駅|店|エリア")) -> listOf(
                SmartReplyOption("🎯 俺スタイル即答", "渋谷か恵比寿あたりが行きやすくて助かる！"),
                SmartReplyOption("🤝 プロの転がし", "そっちの行きやすいエリアに合わせるよ！どこら辺がいい？✨"),
                SmartReplyOption("😄 俺流ツッコミ", "美味しいご飯があるならどこへでも飛んでくわ笑😋"),
                SmartReplyOption("🚀 主導権提案", "気になってるお店いくつかあるからURL送るね！")
            )
            else -> listOf(
                SmartReplyOption("🎯 俺スタイル即答", "それめっちゃわかる！まさに同じこと思ってたところ"),
                SmartReplyOption("🤝 プロの転がし", "いいじゃん！聞いてるだけで楽しそう😊"),
                SmartReplyOption("😄 俺流ツッコミ", "それは笑うんだけど😂 さすがだわ！"),
                SmartReplyOption("🚀 主導権提案", "また詳しく聞かせて！近いうちに飯でも行こ✨")
            )
        }
    }
}
