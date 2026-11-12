package com.antigravity.lineaireply.ai

import android.util.Log
import com.antigravity.lineaireply.plugin.DynamicModelRouterPlugin
import com.antigravity.lineaireply.plugin.IDynamicModelRouterPlugin
import com.antigravity.lineaireply.plugin.IPersonaSynthesizerPlugin
import com.antigravity.lineaireply.plugin.PersonaSynthesizerPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SmartReplyOption(
    val label: String,
    val text: String
)

data class GenerationResult(
    val options: List<SmartReplyOption>,
    val engine: String,
    val errorDetails: String? = null
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

        var cachedWorkingModel: String? = null

        val FALLBACK_CANDIDATE_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-2.0-flash",
            "gemini-1.5-flash"
        )

        fun sanitizeApiKey(rawKey: String): String = DynamicModelRouterPlugin.sanitizeKey(rawKey)

        suspend fun testApiKey(apiKey: String): Pair<Boolean, String> {
            val router = DynamicModelRouterPlugin()
            val result = router.generateContent(
                apiKey = apiKey,
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

        if (currentKey.isBlank() || !guard.canRequest()) {
            return@withContext GenerationResult(
                options = synthesizer.createFallbackReplies(context, accumulatedHistory, userStyle, shouldInvite),
                engine = "定型文",
                errorDetails = if (currentKey.isBlank()) "APIキー未設定" else "利用枠上限"
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
            options = synthesizer.createFallbackReplies(context, accumulatedHistory, userStyle, shouldInvite),
            engine = "定型文 (通信エラー)",
            errorDetails = modelResult.errorMessage ?: "接続失敗"
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
        val fallbackOptions = getFallbackOptions(isPolite = false, mode = mode)

        if (currentKey.isBlank()) {
            return@withContext GenerationResult(
                options = fallbackOptions,
                engine = "定型文 (※設定画面でAPIキーを保存してください)",
                errorDetails = "APIキー未設定"
            )
        }

        if (!guard.canRequest()) {
            return@withContext GenerationResult(
                options = fallbackOptions,
                engine = "定型文 (利用枠上限)",
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
            if (options.isNotEmpty()) {
                guard.recordRequest()
                cachedWorkingModel = modelResult.modelName
                return@withContext GenerationResult(
                    options = options.take(4),
                    engine = "Gemini Vision (${modelResult.modelName})",
                    errorDetails = null
                )
            } else {
                Log.w(TAG, "Empty parse from Gemini Vision output: ${modelResult.responseText.take(60)}")
            }
        }

        GenerationResult(
            options = fallbackOptions,
            engine = "定型文 (Vision通信エラー)",
            errorDetails = modelResult.errorMessage ?: "全モデル接続失敗"
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
        val fallbackOptions = getFallbackOptions(isPolite = isPolite, mode = mode)

        if (currentKey.isBlank()) {
            return@withContext GenerationResult(
                options = fallbackOptions,
                engine = "定型文 (※設定画面でAPIキーを保存してください)",
                errorDetails = "APIキー未設定"
            )
        }

        if (!guard.canRequest()) {
            return@withContext GenerationResult(
                options = fallbackOptions,
                engine = "定型文 (利用枠上限)",
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
            options = fallbackOptions,
            engine = "定型文 (通信エラー)",
            errorDetails = modelResult.errorMessage ?: "接続失敗"
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
        val fallbackOptions = getFallbackOptions(isPolite = isPolite, mode = mode)

        if (currentKey.isBlank()) {
            return@withContext GenerationResult(
                options = fallbackOptions,
                engine = "定型文 (※設定画面でAPIキーを保存してください)",
                errorDetails = "APIキー未設定"
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
            options = fallbackOptions,
            engine = "定型文 (通信エラー)",
            errorDetails = modelResult.errorMessage ?: "接続失敗"
        )
    }

    private fun detectPolite(text: String): Boolean {
        val politeRegex = Regex("""(です|でした|ました|でしょうか|ですね|ですよ|ですか|はじめまして|よろしくお願い|ありがとうござい|いただけ|頂け|ご都合|いかが|存じ|恐れ入|致し|([ぁ-ん一-龯]ます))""")
        return text.isNotBlank() && politeRegex.containsMatchIn(text)
    }

    private fun buildPromptForVision(mode: AssistantMode, userPromptNote: String?): String {
        val modeInstruction = getModeInstruction(mode)
        val noteSection = if (!userPromptNote.isNullOrBlank()) {
            "【ユーザーからの補足指示・メモ】: 「$userPromptNote」\n"
        } else ""

        return """
            あなたは添付されたLINEトークのスクリーンショット画像を直接読み取って支援する最高峰のAIコミュ力アシスタントです。

            【指示】
            添付されたスクリーンショット画像を直接視覚解析してください：
            1. 相手の直近のメッセージ、相手の名前、会話の文脈を把握してください。
            2. 相手が敬語（です・ます調）か、フランクなタメ口かを厳密に判定してください。
            3. 画像内に相手から送信された写真（料理、ペット、風景など）がある場合は、その内容を認識してください。

            $noteSection
            $modeInstruction

            【最重要ルール】
            1. 【敬語・トーンの完全同期】:
               相手が敬語・丁寧語で話している場合は、必ずすべての返信案を好印象で親しみやすい丁寧語（です・ます調）で統一してください。タメ口は厳禁です！相手がフランクなタメ口なら等身大のフランクな口調で返してください。
            2. 【デート・待ち合わせ場所の厳守】:
               場所や店を提案する場合は、絶対に東京（渋谷・新宿・六本木等）を出さず、必ず神奈川県内（厚木、本厚木、海老名、茅ヶ崎、辻堂、藤沢、湘南、横浜）のエリアを提案してください。
            3. 【写真リアクション】:
               相手が写真を送ってきている場合は、その写真の内容（美味しそうなパスタ、可愛い犬など）に自然に触れてリアクションしてください。

            【出力フォーマット】
            Markdownコードブロックや余計な解説は一切出力せず、必ず以下のJSON配列形式のみを出力してください：
            [
              {"label": "ラベル1", "text": "返信文1"},
              {"label": "ラベル2", "text": "返信文2"},
              {"label": "ラベル3", "text": "返信文3"},
              {"label": "ラベル4", "text": "返信文4"}
            ]
        """.trimIndent()
    }

    private fun buildPromptForText(
        contextText: String,
        contextSource: String,
        isPolite: Boolean,
        mode: AssistantMode,
        userPromptNote: String?
    ): String {
        val modeInstruction = getModeInstruction(mode)
        val noteSection = if (!userPromptNote.isNullOrBlank()) {
            "【ユーザーからの補足指示・メモ】: 「$userPromptNote」\n"
        } else ""

        return """
            あなたはLINE等のメッセージ返信を支援する最高峰のAIコミュ力アシスタントです。

            【入力コンテキスト（$contextSource）】
            「$contextText」

            $noteSection
            $modeInstruction

            【最重要ルール】
            1. 【敬語・トーンの完全同期】:
               ${if (isPolite) "相手は【敬語・丁寧語】（です・ます調）で話しています。必ずすべての返信案を好印象で親しみやすい丁寧語（です・ます調）で統一してください。タメ口は厳禁です！" else "相手は【フランク・タメ口】です。等身大で自然体のフランクな口調で作成してください。"}
            2. 【デート・待ち合わせ場所の厳守】:
               会う場所や店を提案する場合は、絶対に東京（渋谷・新宿・六本木等）を出さず、必ず神奈川県内（厚木、本厚木、海老名、茅ヶ崎、辻堂、藤沢、湘南、横浜）のエリアを提案してください。

            【出力フォーマット】
            Markdownコードブロックや解説は一切出力せず、必ず以下のJSON配列形式のみを出力してください：
            [
              {"label": "ラベル1", "text": "返信文1"},
              {"label": "ラベル2", "text": "返信文2"},
              {"label": "ラベル3", "text": "返信文3"},
              {"label": "ラベル4", "text": "返信文4"}
            ]
        """.trimIndent()
    }

    private fun getModeInstruction(mode: AssistantMode): String {
        return when (mode) {
            AssistantMode.REPLY -> """
                【実行モード: 💬 返信提案】
                相手のメッセージに対して、自然で好印象な4つの異なる切り口の返信案を作成してください。
                - 案1 (🎯 即答/ストレート): 相手の発言への素直で気持ちのいい回答
                - 案2 (😄 共感/ノリ良く): 相手の気持ちや話に強く共感する返し
                - 案3 (💬 質問/話題広げ): 会話が弾む自然な質問を添えた返し
                - 案4 (✨ 代案/ポジティブ): 提案やポジティブな代案を添えた返し
            """.trimIndent()

            AssistantMode.SCHEDULE -> """
                【実行モード: 📅 日程調整・デートお誘い】
                相手と会う約束・食事・お茶・ドライブの予定をスマートに提案する4つの返信案を作成してください。
                【絶対ルール】場所は必ず神奈川県内（厚木、本厚木、海老名、茅ヶ崎、辻堂、藤沢、湘南、横浜）を提案してください。東京（渋谷、新宿等）は【完全禁止】です！
                - 案1 (📅 ランチ/カフェ): 昼間のカフェやお茶の気軽なお誘い
                - 案2 (🍻 飲み/ディナー): 夜の美味しいご飯や飲みのお誘い
                - 案3 (🚗 お出かけ/ドライブ): 湘南や横浜などへのお出かけ提案
                - 案4 (📆 候補日確認): 相手の空いてる曜日や週末の都合を伺う返信
            """.trimIndent()

            AssistantMode.TOPIC -> """
                【実行モード: 💡 話題提案・トーク展開】
                会話が途切れないよう、相手が楽しく答えられる自然な質問や話題を4つ作成してください。
                - 案1 (☕ 休日の過ごし方): 週末の予定や休日の過ごし方に関する話題
                - 案2 (🍜 グルメ/おすすめ): 好きなお店や美味しかったものに関する話題
                - 案3 (✈️ 旅行/お出かけ): 好きな場所や行ってみたい場所に関する話題
                - 案4 (🎵 趣味/最近のハマり): 最近気になっていることやマイブームの話題
            """.trimIndent()

            AssistantMode.MOOD -> """
                【実行モード: 🎭 ムード分析＆戦略返信】
                会話の文脈から相手の感情・温度感・心理状態を分析し、最適な戦略的返信を作成してください。
                - 案1 (🎭 ムード分析): 相手の好感度評価（★1〜3つ）、感情温度、心理的狙いの要約
                - 案2 (💡 戦略返信1): そのムードに最も適したベストな返信案
                - 案3 (💡 戦略返信2): 会話の主導権を握るスマートな返し
                - 案4 (💡 戦略返信3): 相手の関心をさらに惹きつける返し
            """.trimIndent()
        }
    }

    private fun getFallbackOptions(isPolite: Boolean, mode: AssistantMode): List<SmartReplyOption> {
        return when (mode) {
            AssistantMode.REPLY -> if (isPolite) listOf(
                SmartReplyOption("🎯 丁寧・即答", "ご連絡ありがとうございます！ぜひそちらでお願いいたします。"),
                SmartReplyOption("😄 丁寧・共感", "本当ですね！とても楽しみにしています✨"),
                SmartReplyOption("💬 丁寧・質問", "承知いたしました！ちなみに何時頃がご都合よろしいでしょうか？"),
                SmartReplyOption("🙅‍♂️ 丁寧・代案", "申し訳ありません、その日は少し難しく…別の日程はいかがでしょうか？")
            ) else listOf(
                SmartReplyOption("🎯 即答・ストレート", "了解！楽しみに待ってるね😊"),
                SmartReplyOption("😄 共感・ノリ良く", "それめっちゃいいね！詳しく教えて笑"),
                SmartReplyOption("💬 質問・話題広げ", "そうなんだ！ちなみに何時くらいが都合いい？"),
                SmartReplyOption("✨ 代案・ポジティブ", "その日はちょっと予定あって…来週どう？")
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
