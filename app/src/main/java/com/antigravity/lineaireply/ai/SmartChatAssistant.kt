package com.antigravity.lineaireply.ai

import android.util.Log
import com.antigravity.lineaireply.plugin.DynamicModelRouterPlugin
import com.antigravity.lineaireply.plugin.IDynamicModelRouterPlugin
import com.antigravity.lineaireply.plugin.IPersonaSynthesizerPlugin
import com.antigravity.lineaireply.plugin.PersonaSynthesizerPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class SmartReplyOption(
    val label: String,
    val text: String
)

data class GenerationResult(
    val options: List<SmartReplyOption>,
    val engine: String,
    val errorDetails: String? = null,
    val targetMessage: String? = null
)

/**
 * High-EQ AI Chat Assistant that coordinates:
 * 1. Multimodal Gemini Vision analysis of recent screenshots (OS screenshot auto-detection).
 * 2. Clipboard auto-reading via window focus trick.
 * 3. 4 Action Modes: 返信提案, 日程調整, 話題提案, ムード分析.
 * 4. Strict conversation rules: 敬語完全同期, 神奈川（厚木・茅ヶ崎・湘南・海老名・横浜）限定, 写真リアクション.
 *
 * Zero MediaProjection (avoids Android 15 screen-sharing protection).
 * Zero AccessibilityService (avoids PayPay and Banking app RASP restrictions).
 */
class SmartChatAssistant(
    private var apiKey: String,
    private val guard: FreeTierGuard,
    private val memoryManager: ConversationMemoryManager? = null,
    private val router: IDynamicModelRouterPlugin = DynamicModelRouterPlugin(),
    private val synthesizer: IPersonaSynthesizerPlugin = PersonaSynthesizerPlugin()
) {

    companion object {
        private const val TAG = "SmartChatAssistant"

        var cachedWorkingModel: String?
            get() = DynamicModelRouterPlugin.cachedWorkingModel
            set(value) {
                DynamicModelRouterPlugin.cachedWorkingModel = value
            }

        val FALLBACK_CANDIDATE_MODELS = DynamicModelRouterPlugin.CANDIDATE_MODELS

        fun sanitizeApiKey(rawKey: String): String = DynamicModelRouterPlugin.sanitizeKey(rawKey)

        fun isValidApiKey(rawKey: String): Boolean {
            val clean = sanitizeApiKey(rawKey)
            // Supports both legacy 'AIza...' Traffic keys and modern Google AI Studio 'AQ....' Authentication keys
            return (clean.startsWith("AIza") || clean.startsWith("AQ.") || clean.length >= 25) && clean.length >= 20
        }

        suspend fun testApiKey(apiKey: String): Pair<Boolean, String> {
            val cleanKey = sanitizeApiKey(apiKey)
            if (!isValidApiKey(cleanKey)) {
                return Pair(false, "Gemini APIキーを入力してください。\n（AIza... または Google AI Studio 最新形式の AQ....）")
            }
            val router = DynamicModelRouterPlugin()
            val result = router.generateContent(
                apiKey = cleanKey,
                prompt = "返信テストです。[{\"label\":\"テスト\",\"text\":\"OK\"}]の形式で返してください。"
            )
            return if (result.success) {
                Pair(true, "Gemini API との通信に成功しました！\nモデル: ${result.modelName}")
            } else {
                Pair(false, "通信エラー: ${result.errorMessage ?: "APIキーが無効か、通信環境をご確認ください。"}")
            }
        }
    }

    fun updateApiKey(newKey: String) {
        this.apiKey = sanitizeApiKey(newKey)
    }

    suspend fun generateReplies(
        context: ChatContext,
        forceLineInvite: Boolean = false
    ): GenerationResult = withContext(Dispatchers.IO) {
        val currentKey = apiKey.trim()
        val userStyle = memoryManager?.getUserStyle() ?: UserStyleProfile(
            summary = "自然な会話",
            sampleQuotes = emptyList(),
            totalLearnedCount = 0
        )
        val shouldInvite = forceLineInvite || context.isBumble || context.shouldSuggestLineInvite
        val partnerDossier = memoryManager?.getPartnerDossier(context.partnerName) ?: PartnerDossier(
            name = context.partnerName.ifEmpty { "相手" },
            location = "不明",
            personalityType = "感情表現豊か・リアクション重視",
            strategy = "相手のテンションに合わせ、共感しつつ軽くツッコミを入れて心地よく転がす",
            accumulatedMessageCount = 0
        )
        val accumulatedHistory = memoryManager?.getAccumulatedConversation(context.partnerName) ?: emptyList()

        if (!isValidApiKey(currentKey)) {
            return@withContext GenerationResult(
                options = emptyList(),
                engine = "⚠️ APIキー未設定",
                errorDetails = "設定画面でGemini APIキー（AIza... または AQ....）を保存してください"
            )
        }

        if (!guard.canRequest()) {
            return@withContext GenerationResult(
                options = emptyList(),
                engine = "⚠️ 利用枠上限",
                errorDetails = "1日の無料利用枠に達しました"
            )
        }

        val prompt = synthesizer.buildPrompt(
            context = context,
            accumulatedHistory = accumulatedHistory,
            userStyle = userStyle,
            partnerDossier = partnerDossier,
            forceLineInvite = forceLineInvite
        )

        val modelResult = router.generateContent(
            apiKey = currentKey,
            prompt = prompt,
            preferredModel = cachedWorkingModel
        )

        if (modelResult.success && modelResult.responseText.isNotBlank()) {
            val options = synthesizer.parseReplies(modelResult.responseText)
            if (options.isNotEmpty()) {
                guard.recordRequest()
                return@withContext GenerationResult(
                    options = options,
                    engine = "Gemini (${modelResult.modelName})",
                    errorDetails = null
                )
            }
        }

        GenerationResult(
            options = emptyList(),
            engine = "❌ 通信エラー",
            errorDetails = modelResult.errorMessage ?: "API通信に失敗しました"
        )
    }

    /**
     * Route A: Generates AI suggestions from an OS standard screenshot (multimodal Gemini Vision).
     * Bypasses Android 15 MediaProjection block because the user took the screenshot via OS standard mechanism.
     */
    suspend fun generateFromScreenshot(
        imageBase64: String,
        mode: AssistantMode = AssistantMode.REPLY,
        userPromptNote: String? = null
    ): GenerationResult = withContext(Dispatchers.IO) {
        val currentKey = apiKey.trim()

        if (!isValidApiKey(currentKey)) {
            return@withContext GenerationResult(
                options = emptyList(),
                engine = "⚠️ APIキー未設定",
                errorDetails = "設定画面でGemini APIキー（AIza... または AQ....）を保存してください"
            )
        }

        if (!guard.canRequest()) {
            return@withContext GenerationResult(
                options = emptyList(),
                engine = "⚠️ 利用枠上限",
                errorDetails = "1日の無料利用枠に達しました"
            )
        }

        val prompt = buildPromptForVision(mode, userPromptNote)

        val modelResult = router.generateContent(
            apiKey = currentKey,
            prompt = prompt,
            preferredModel = cachedWorkingModel,
            imageBase64 = imageBase64
        )

        if (modelResult.success && modelResult.responseText.isNotBlank()) {
            val options = synthesizer.parseReplies(modelResult.responseText)
            val recognizedTarget = extractTargetMessage(modelResult.responseText)
            if (options.isNotEmpty()) {
                guard.recordRequest()
                cachedWorkingModel = modelResult.modelName
                return@withContext GenerationResult(
                    options = options.take(4),
                    engine = "Gemini Vision (${modelResult.modelName})",
                    errorDetails = null,
                    targetMessage = recognizedTarget
                )
            } else {
                Log.w(TAG, "Empty parse from Gemini Vision output: ${modelResult.responseText.take(60)}")
            }
        }

        GenerationResult(
            options = emptyList(),
            engine = "❌ 通信エラー",
            errorDetails = modelResult.errorMessage ?: "Gemini Vision APIとの通信に失敗しました"
        )
    }

    /**
     * Route B: Generates AI suggestions from copied LINE text (via window focus trick).
     */
    suspend fun generateFromClipboard(
        clipboardText: String,
        mode: AssistantMode = AssistantMode.REPLY,
        userPromptNote: String? = null
    ): GenerationResult = withContext(Dispatchers.IO) {
        val currentKey = apiKey.trim()
        val isPolite = detectPolite(clipboardText)

        if (!isValidApiKey(currentKey)) {
            return@withContext GenerationResult(
                options = emptyList(),
                engine = "⚠️ APIキー未設定",
                errorDetails = "設定画面でGemini APIキー（AIza... または AQ....）を保存してください"
            )
        }

        if (!guard.canRequest()) {
            return@withContext GenerationResult(
                options = emptyList(),
                engine = "⚠️ 利用枠上限",
                errorDetails = "1日の無料利用枠に達しました"
            )
        }

        val prompt = buildPromptForText(
            contextText = clipboardText,
            contextSource = "ユーザーがLINEからコピーしたメッセージ",
            isPolite = isPolite,
            mode = mode,
            userPromptNote = userPromptNote
        )

        val modelResult = router.generateContent(
            apiKey = currentKey,
            prompt = prompt,
            preferredModel = cachedWorkingModel
        )

        if (modelResult.success && modelResult.responseText.isNotBlank()) {
            val options = synthesizer.parseReplies(modelResult.responseText)
            if (options.isNotEmpty()) {
                guard.recordRequest()
                cachedWorkingModel = modelResult.modelName
                return@withContext GenerationResult(
                    options = options.take(4),
                    engine = "Gemini (${modelResult.modelName})",
                    errorDetails = null
                )
            }
        }

        GenerationResult(
            options = emptyList(),
            engine = "❌ 通信エラー",
            errorDetails = modelResult.errorMessage ?: "Gemini APIとの通信に失敗しました"
        )
    }

    /**
     * Route C: Generates AI suggestions from a user-typed short memo or topic keyword.
     */
    suspend fun generateFromUserNote(
        userPromptNote: String,
        mode: AssistantMode = AssistantMode.REPLY
    ): GenerationResult = withContext(Dispatchers.IO) {
        val currentKey = apiKey.trim()
        val isPolite = detectPolite(userPromptNote)

        if (!isValidApiKey(currentKey)) {
            return@withContext GenerationResult(
                options = emptyList(),
                engine = "⚠️ APIキー未設定",
                errorDetails = "設定画面でGemini APIキー（AIza... または AQ....）を保存してください"
            )
        }

        val prompt = buildPromptForText(
            contextText = userPromptNote,
            contextSource = "ユーザーが直接入力したトピック・要件メモ",
            isPolite = isPolite,
            mode = mode,
            userPromptNote = null
        )

        val modelResult = router.generateContent(
            apiKey = currentKey,
            prompt = prompt,
            preferredModel = cachedWorkingModel
        )

        if (modelResult.success && modelResult.responseText.isNotBlank()) {
            val options = synthesizer.parseReplies(modelResult.responseText)
            if (options.isNotEmpty()) {
                guard.recordRequest()
                cachedWorkingModel = modelResult.modelName
                return@withContext GenerationResult(
                    options = options.take(4),
                    engine = "Gemini (${modelResult.modelName})",
                    errorDetails = null
                )
            }
        }

        GenerationResult(
            options = emptyList(),
            engine = "❌ 通信エラー",
            errorDetails = modelResult.errorMessage ?: "Gemini APIとの通信に失敗しました"
        )
    }

    private fun detectPolite(text: String): Boolean {
        val politeRegex = Regex("""(です|でした|ました|でしょうか|ですね|ですよ|ですか|はじめまして|よろしくお願い|ありがとうござい|いただけ|頂け|ご都合|いかが|存じ|恐れ入|致し|([ぁ-ん一-龯]ます))""")
        return text.isNotBlank() && politeRegex.containsMatchIn(text)
    }

    /**
     * Determines if the text indicates an English speaker or foreign partner.
     */
    fun isEnglishOrForeign(text: String): Boolean {
        val clean = text.replace(Regex("https?://\\S+"), " ").trim()
        if (clean.isEmpty()) return false
        val latinCount = clean.count { it in 'a'..'z' || it in 'A'..'Z' }
        val japaneseCount = clean.count { it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9faf' }
        val hasForeignKeywords = Regex(
            "from\\s+[a-z]+|lives?\\s+in|expat|native|english|speak|foreigner|travel|study abroad|where are you|what do you|how about|sounds good|see you",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(clean)
        return (latinCount >= 3 && japaneseCount == 0) ||
               (latinCount > 10 && (japaneseCount == 0 || latinCount > japaneseCount * 2)) ||
               (latinCount > 5 && hasForeignKeywords)
    }

    private fun extractTargetMessage(responseText: String): String? {
        return try {
            var clean = responseText.trim()
            if (clean.startsWith("```")) {
                val firstNl = clean.indexOf('\n')
                if (firstNl != -1) clean = clean.substring(firstNl + 1)
                if (clean.endsWith("```")) clean = clean.substring(0, clean.length - 3)
                clean = clean.trim()
            }
            if (clean.startsWith("{")) {
                val json = JSONObject(clean)
                json.optString("targetMessage", "").takeIf { it.isNotBlank() }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun buildPromptForVision(mode: AssistantMode, userPromptNote: String?): String {
        val modeInstruction = getModeInstruction(mode, isEnglish = false)
        val noteSection = if (!userPromptNote.isNullOrBlank()) {
            "【ユーザーからの補足指示・メモ】: 「$userPromptNote」\n"
        } else ""

        return """
            あなたは添付されたLINEやBumble等のトーク画面・プロフィールのスクリーンショット画像を直接読み取って支援する最高峰のAIコミュ力アシスタントです。

            【LINE / Bumble トーク画面の構造と最新メッセージの特定（最重要ルール）】
            画面は上から下へと時系列順に並んでいます：
            1. 【過去メッセージ（上部・中央）は絶対に無視】:
               画面の上部や中央にある吹き出しはすべて「過去のやり取り」です。これらに返信すると会話が噛み合わなくなります。絶対に画面上部・中央のメッセージに返信しないでください！
            2. 【最下部の最新メッセージに返信】:
               返信すべき対象は、**画面の一番下（最下部・文字入力欄のすぐ上）にある相手の最新メッセージ**です。
            3. 【相手の吹き出しと自分の吹き出しの識別】:
               - 【相手（パートナー）のメッセージ】: 通常は左側の白い吹き出し（または相手のアイコン横）。
               - 【自分（ユーザー）のメッセージ】: 通常は右側の緑色・黄色の吹き出し。
               ※必ず**画面の最下部にある「相手の最新メッセージ」を返信対象**として特定してください。

            【★最重要・言語判定ルール：相手が外国人・英語話者の場合は100%英語で返信（日本語厳禁！）】
            相手の最新メッセージが英語、または相手が外国人/バイリンガル、Bumble等のプロフィール・チャットが英語である場合：
            - 返信案4つはすべて【100%自然でチャーミングな日常会話のネイティブ英語（タメ口・短文・スラング・テンポの良い会話）】で作成してください。
            - 日本語での返信案の出力は【絶対に禁止】です！
            - 教科書英語や硬い直訳は厳禁。等身大で好印象、自然体のウィットと親しみやすさが伝わる英語にしてください。
              （例: "Sounds awesome!", "Haha tell me more! What happened?", "What time works best for you?", "Let's grab a coffee near Shonan!"）
            - 会う場所や日程を提案する場合も、神奈川エリア（Yokohama, Shonan, Chigasaki, Ebina, Atsugi）を自然な英語で提案してください。
            - 相手が英語話者の場合、JSONのoptionsの"label"も英語（"🎯 Direct Answer", "😄 Friendly Banter", "💬 Question", "☕ Casual Plan"）にしてください。

            【相手が日本語話者の場合のトーンと敬語ルール】
            - 相手が敬語（です・ます調）なら、全返信案を好印象な丁寧語（です・ます調）で統一してください。
            - 相手がタメ口なら、等身大で自然なタメ口で返してください。
            - 会う場所を提案する場合は、神奈川県内（厚木、本厚木、海老名、茅ヶ崎、辻堂、藤沢、湘南、横浜）のエリアを提案してください（東京は禁止）。

            【写真へのリアクション】
            相手が写真を送信している場合は、その写真の内容（料理、風景、ペットなど）に自然に触れてください。

            $noteSection
            $modeInstruction

            【出力フォーマット（必ず以下のJSON形式のみを出力）】
            Markdownコードブロックや余計な解説は一切出力せず、必ず以下のJSONオブジェクト形式のみを出力してください：
            {
              "targetMessage": "画面最下部で認識した相手の最新メッセージ本文（例: 「今週の金曜空いてる？」 または \"What are you up to tonight?\"）",
              "options": [
                {"label": "ラベル1", "text": "返信案1（※相手が英語の場合は100%自然な英語）"},
                {"label": "ラベル2", "text": "返信案2（※相手が英語の場合は100%自然な英語）"},
                {"label": "ラベル3", "text": "返信案3（※相手が英語の場合は100%自然な英語）"},
                {"label": "ラベル4", "text": "返信案4（※相手が英語の場合は100%自然な英語）"}
              ]
            }
        """.trimIndent()
    }

    private fun buildPromptForText(
        contextText: String,
        contextSource: String,
        isPolite: Boolean,
        mode: AssistantMode,
        userPromptNote: String?
    ): String {
        val isEnglish = isEnglishOrForeign(contextText)
        val modeInstruction = getModeInstruction(mode, isEnglish)
        val noteSection = if (!userPromptNote.isNullOrBlank()) {
            "【ユーザーからの補足指示・メモ】: 「$userPromptNote」\n"
        } else ""

        val languageRule = if (isEnglish) {
            """
            【★最重要・言語設定：100%自然なネイティブ英語（日本語厳禁！）】
            相手のメッセージは英語です（「$contextText」）。
            返信案4つはすべて【100%自然でチャーミングな日常会話のカジュアル英語（タメ口・短文・スラング・テンポの良い会話）】で作成してください。
            日本語での返信案は【絶対に禁止】です！
            教科書のような堅い直訳やビジネス英語は厳禁。等身大で親しみやすい、ネイティブが普段使う自然な英語にしてください。
            （例: "Sounds awesome!", "Haha tell me more!", "What time works best for you?", "Let's grab a coffee near Shonan!"）
            場所を提案する場合は神奈川エリア（Yokohama, Shonan, Chigasaki, Ebina, Atsugi）を自然な英語で提案してください。
            """.trimIndent()
        } else {
            """
            【敬語・トーンの完全同期】
            ${if (isPolite) "相手は【敬語・丁寧語】（です・ます調）で話しています。必ずすべての返信案を好印象で親しみやすい丁寧語（です・ます調）で統一してください。タメ口は厳禁です！" else "相手は【フランク・タメ口】です。等身大で自然体のフランクな口調で作成してください。"}

            【デート・待ち合わせ場所の厳守】
            会う場所や店を提案する場合は、絶対に東京（渋谷・新宿・六本木等）を出さず、必ず神奈川県内（厚木、本厚木、海老名、茅ヶ崎、辻堂、藤沢、湘南、横浜）のエリアを提案してください。
            """.trimIndent()
        }

        val jsonExample = if (isEnglish) {
            """
            [
              {"label": "🎯 Direct Answer", "text": "Sounds great! Count me in 👍"},
              {"label": "😄 Friendly Banter", "text": "Haha that made my day! Tell me more 😂"},
              {"label": "💬 Question", "text": "What time were you thinking?"},
              {"label": "☕ Casual Plan", "text": "How about grabbing coffee near Shonan beach this weekend?"}
            ]
            """.trimIndent()
        } else {
            """
            [
              {"label": "ラベル1", "text": "返信文1"},
              {"label": "ラベル2", "text": "返信文2"},
              {"label": "ラベル3", "text": "返信文3"},
              {"label": "ラベル4", "text": "返信文4"}
            ]
            """.trimIndent()
        }

        return """
            あなたはLINEやBumble等のメッセージ返信を支援する最高峰のAIコミュ力アシスタントです。

            【入力コンテキスト（$contextSource）】
            「$contextText」

            $noteSection
            $modeInstruction

            【最重要ルール】
            $languageRule

            【出力フォーマット】
            Markdownコードブロックや解説は一切出力せず、必ず以下のJSON配列形式のみを出力してください：
            $jsonExample
        """.trimIndent()
    }

    private fun getModeInstruction(mode: AssistantMode, isEnglish: Boolean = false): String {
        return if (isEnglish) {
            when (mode) {
                AssistantMode.REPLY -> """
                    【Mode: 💬 Reply Suggestions (English)】
                    Generate 4 natural, charming English replies from different angles:
                    - Option 1 (🎯 Direct Answer): Straightforward, warm, and positive response
                    - Option 2 (😄 Friendly Banter): Witty, playful, and fun banter
                    - Option 3 (💬 Engaging Question): Natural follow-up question to keep conversation lively
                    - Option 4 (😄 Casual & Honest): Relaxed, down-to-earth authentic tease or reaction
                """.trimIndent()

                AssistantMode.SCHEDULE -> """
                    【Mode: 📅 Schedule & Meetup (English)】
                    Generate 4 smooth English proposals to meet up (Coffee, Drinks, or Casual Plan).
                    【Location Rule】Always suggest locations in Kanagawa area (Yokohama, Shonan, Chigasaki, Ebina, Atsugi). Never Tokyo.
                    - Option 1 (☕ Coffee/Lunch): Relaxed daytime cafe or lunch invite
                    - Option 2 (🍻 Drinks/Dinner): Fun evening dinner or drinks invite
                    - Option 3 (🚗 Chill Drive): Relaxed drive or seaside visit to Shonan or Yokohama
                    - Option 4 (📆 Check Availability): Asking what day or weekend works best
                """.trimIndent()

                AssistantMode.TOPIC -> """
                    【Mode: 💡 Topic & Conversation Starter (English)】
                    Generate 4 engaging English questions to spark a fun conversation:
                    - Option 1 (☕ Weekend Vibes): What do you usually do on your days off?
                    - Option 2 (🍜 Food & Cafe): Favorite restaurants or delicious food recommendations
                    - Option 3 (✈️ Travel & Places): Favorite trips, bucket-list places, or exploring
                    - Option 4 (🎵 Hobbies & Netflix): Shows, music, or current obsessions
                """.trimIndent()

                AssistantMode.MOOD -> """
                    【Mode: 🎭 Mood Analysis & Strategic Reply (English)】
                    Analyze her tone, energy, and vibe, and generate 4 strategic English options:
                    - Option 1 (🎭 Vibe Check): Quick evaluation of her mood and interest level
                    - Option 2 (💡 Smooth Reply 1): Best engaging response matching her energy
                    - Option 3 (💡 Smooth Reply 2): Playful response taking the conversational lead
                    - Option 4 (💡 Flirty Tease): Charming teasing remark to build chemistry
                """.trimIndent()
            }
        } else {
            when (mode) {
                AssistantMode.REPLY -> """
                    【実行モード: 💬 返信提案】
                    相手のメッセージに対して、自然で好印象な4つの異なる切り口の返信案を作成してください。
                    （※相手が英語話者の場合は、必ず英語で作成してください）
                    - 案1 (🎯 即答/ストレート): 相手の発言への素直で気持ちのいい回答
                    - 案2 (😄 共感/ノリ良く): 相手の気持ちや話に強く共感する返し
                    - 案3 (💬 質問/話題広げ): 会話が弾む自然な質問を添えた返し
                    - 案4 (😄 ざっくばらん/本音): 気取らない等身大の本音やぶっちゃけトーク、飾らない親近感を添えたざっくばらんな返し
                """.trimIndent()

                AssistantMode.SCHEDULE -> """
                    【実行モード: 📅 日程調整・デートお誘い】
                    相手と会う約束・食事・お茶・ドライブの予定をスマートに提案する4つの返信案を作成してください。
                    【絶対ルール】場所は必ず神奈川県内（厚木、本厚木、海老名、茅ヶ崎、辻堂、藤沢、湘南、横浜）を提案してください。東京（渋谷、新宿等）は【完全禁止】です！
                    （※相手が英語話者の場合は、必ず英語で提案してください）
                    - 案1 (📅 ランチ/カフェ): 昼間のカフェやお茶の気軽なお誘い
                    - 案2 (🍻 飲み/ディナー): 夜の美味しいご飯や飲みのお誘い
                    - 案3 (🚗 お出かけ/ドライブ): 湘南や横浜などへのお出かけ提案
                    - 案4 (📆 候補日確認): 相手の空いてる曜日や週末の都合を伺う返信
                """.trimIndent()

                AssistantMode.TOPIC -> """
                    【実行モード: 💡 話題提案・トーク展開】
                    会話が途切れないよう、相手が楽しく答えられる自然な質問や話題を4つ作成してください。
                    （※相手が英語話者の場合は、必ず英語で作成してください）
                    - 案1 (☕ 休日の過ごし方): 週末の予定や休日の過ごし方に関する話題
                    - 案2 (🍜 グルメ/おすすめ): 好きなお店や美味しかったものに関する話題
                    - 案3 (✈️ 旅行/お出かけ): 好きな場所や行ってみたい場所に関する話題
                    - 案4 (🎵 趣味/最近のハマり): 最近気になっていることやマイブームの話題
                """.trimIndent()

                AssistantMode.MOOD -> """
                    【実行モード: 🎭 ムード分析＆戦略返信】
                    会話の文脈から相手の感情・温度感・心理状態を分析し、最適な戦略的返信を作成してください。
                    （※相手が英語話者の場合は、必ず英語で作成してください）
                    - 案1 (🎭 ムード分析): 相手の好感度評価（★1〜3つ）、感情温度、心理的狙いの要約
                    - 案2 (💡 戦略返信1): そのムードに最も適したベストな返信案
                    - 案3 (💡 戦略返信2): 会話の主導権を握るスマートな返し
                    - 案4 (💡 戦略返信3): 相手の関心をさらに惹きつける返し
                """.trimIndent()
            }
        }
    }

    private fun getFallbackOptions(isPolite: Boolean, mode: AssistantMode, isEnglish: Boolean = false): List<SmartReplyOption> {
        if (isEnglish) {
            return when (mode) {
                AssistantMode.REPLY -> listOf(
                    SmartReplyOption("🎯 Direct Answer", "Sounds awesome! Count me in 👍"),
                    SmartReplyOption("😄 Witty Banter", "Haha that made my day! Tell me more 😂"),
                    SmartReplyOption("💬 Question", "Sounds fun! What time were you thinking?"),
                    SmartReplyOption("☕ Casual Plan", "Let's grab a coffee or drinks sometime soon! ☕")
                )
                AssistantMode.SCHEDULE -> listOf(
                    SmartReplyOption("☕ Coffee & Beach", "How about grabbing coffee near Shonan beach this weekend? ☕"),
                    SmartReplyOption("🍻 Dinner & Drinks", "Let's check out a fun dinner spot in Yokohama! When are you free?"),
                    SmartReplyOption("🚗 Weekend Drive", "Down for a relaxed drive around Shonan or Kamakura? 🚗"),
                    SmartReplyOption("📆 Check Dates", "Are you usually free on weekends or weekdays?")
                )
                AssistantMode.TOPIC -> listOf(
                    SmartReplyOption("☕ Weekend Vibes", "What do you usually do on your days off?"),
                    SmartReplyOption("🍜 Good Food", "Have you found any amazing restaurants or cafes in Japan recently?"),
                    SmartReplyOption("✈️ Travel Stories", "What's the best trip you've ever been on?"),
                    SmartReplyOption("🎵 Shows & Music", "What shows or music have you been hooked on lately?")
                )
                AssistantMode.MOOD -> listOf(
                    SmartReplyOption("🎭 Vibe Check", "Positive & Friendly vibe! Keep it light, fun, and natural."),
                    SmartReplyOption("💡 Smooth Reply 1", "Sounds great! I'd love to hear more about that :)"),
                    SmartReplyOption("💡 Smooth Reply 2", "Haha no way! We definitely need to chat about this over drinks"),
                    SmartReplyOption("💡 Playful Tease", "Haha you're too funny! Let's definitely hang out soon")
                )
            }
        }

        return when (mode) {
            AssistantMode.REPLY -> if (isPolite) listOf(
                SmartReplyOption("🎯 丁寧・即答", "ご連絡ありがとうございます！ぜひそちらでお願いいたします。"),
                SmartReplyOption("😄 丁寧・共感", "本当ですね！とても楽しみにしています✨"),
                SmartReplyOption("💬 丁寧・質問", "承知いたしました！ちなみに何時頃がご都合よろしいでしょうか？"),
                SmartReplyOption("😄 丁寧・ざっくばらん", "ぶっちゃけ私もすごく気になってました笑！ぜひご一緒させてください✨")
            ) else listOf(
                SmartReplyOption("🎯 即答・ストレート", "了解！楽しみに待ってるね😊"),
                SmartReplyOption("😄 共感・ノリ良く", "それめっちゃいいね！詳しく教えて笑"),
                SmartReplyOption("💬 質問・話題広げ", "そうなんだ！ちなみに何時くらいが都合いい？"),
                SmartReplyOption("😄 ざっくばらん・本音", "ぶっちゃけそれめっちゃ最高じゃん笑！詳しく聞かせて😂")
            )
            AssistantMode.SCHEDULE -> if (isPolite) listOf(
                SmartReplyOption("📅 ランチ・お茶", "今度の週末、茅ヶ崎や辻堂あたりの落ち着いたカフェでランチでもいかがですか？☕"),
                SmartReplyOption("🍻 ディナー・飲み", "厚木か海老名あたりで美味しいご飯でもご一緒しませんか？ご都合の良い曜日はありますか？"),
                SmartReplyOption("🚗 お出かけ・湘南", "天気の良い日に湘南や横浜方面へドライブがてらお出かけしませんか？✨"),
                SmartReplyOption("📆 日程確認", "来週の平日夜か、次の土日あたりでしたらどちらがご都合よろしいでしょうか？")
            ) else listOf(
                SmartReplyOption("📅 カフェ・ランチ", "今週末、茅ヶ崎で海見ながらカフェでも行こー☕️"),
                SmartReplyOption("🍻 居酒屋・ディナー", "本厚木か海老名あたりで美味いもん食べ行こ！いつ空いてる？"),
                SmartReplyOption("🚗 湘南ドライブ", "ドライブがてら湘南か横浜あたりふらっと行かない？🚗"),
                SmartReplyOption("📆 予定チェック", "来週のどこかで時間ある？何曜日がいい？")
            )
            AssistantMode.TOPIC -> if (isPolite) listOf(
                SmartReplyOption("☕ 休日の過ごし方", "普段のお休みの日はカフェ巡りや映画など、どんなことをして過ごされることが多いですか？"),
                SmartReplyOption("🍜 グルメ・お店", "最近行って美味しかったおすすめのご飯屋さんとかありますか？ぜひ教えていただきたいです😊"),
                SmartReplyOption("✈️ 旅行・お出かけ", "お出かけやご旅行はお好きですか？今まで行かれて一番良かった場所はどちらですか？"),
                SmartReplyOption("🎵 趣味・ハマりもの", "最近何かハマっていることや、マイブームなどはありますでしょうか？✨")
            ) else listOf(
                SmartReplyOption("☕ 休日の過ごし方", "普段休みの日は何して過ごすことが多い？のんびり派？アクティブ派？笑"),
                SmartReplyOption("🍜 グルメ・ご飯", "最近食べて美味しかったものある？おすすめのお店教えてー！"),
                SmartReplyOption("✈️ 旅行・お出かけ", "旅行とかドライブ好き？今まで行って一番最高だったとこどこ？"),
                SmartReplyOption("🎵 趣味・マイブーム", "最近ハマってることとかある？Netflixとか見てる？笑")
            )
            AssistantMode.MOOD -> listOf(
                SmartReplyOption("🎭 相手のムード分析", if (isPolite) "【好感度: 良好 ★★★】丁寧で誠実なトーンです。落ち着いたカフェやランチのお誘いが好印象につながります。" else "【好感度: 親密 ★★☆】フランクでテンポの良い会話です。共感と軽いツッコミを交えつつ次の約束に繋げやすい状態です。"),
                SmartReplyOption("💡 推奨返信プラン1", if (isPolite) "相手の丁寧さに合わせ、誠実かつ前向きに返信する" else "テンポよくノリを合わせて自然に日程を合わせる"),
                SmartReplyOption("💡 推奨返信プラン2", if (isPolite) "質問を1つ添えて相手が返信しやすい配慮をする" else "短いフレーズでサクッと返して会話を弾ませる"),
                SmartReplyOption("💡 推奨返信プラン3", if (isPolite) "神奈川エリア（茅ヶ崎・厚木・海老名）の候補をスマートに添える" else "気軽なノリで週末のお誘いへパスを出す")
            )
        }
    }
}
