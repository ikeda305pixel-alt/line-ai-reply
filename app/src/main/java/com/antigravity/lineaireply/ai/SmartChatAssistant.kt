package com.antigravity.lineaireply.ai

import android.util.Log
import com.antigravity.lineaireply.plugin.DynamicModelRouterPlugin
import com.antigravity.lineaireply.plugin.IDynamicModelRouterPlugin
import com.antigravity.lineaireply.plugin.IPersonaSynthesizerPlugin
import com.antigravity.lineaireply.plugin.PersonaSynthesizerPlugin
import com.antigravity.lineaireply.service.CachedMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SmartReplyOption(
    val label: String,
    val text: String
)

data class GenerationResult(
    val options: List<SmartReplyOption>,
    val engine: String, // "Gemini 2.5 Flash" or "定型文 (理由)"
    val errorDetails: String? = null
)

/**
 * 4 Action Modes for LINE AI Reply Assistant:
 * - REPLY: Context-aware smart replies
 * - SCHEDULE: Date/meetup suggestions (strictly Kanagawa)
 * - TOPIC: Topic/question suggestions to keep chat flowing
 * - MOOD: Mood analysis & tactical responses
 */
enum class AssistantMode(val title: String, val icon: String) {
    REPLY("返信提案", "💬"),
    SCHEDULE("日程調整", "📅"),
    TOPIC("話題提案", "💡"),
    MOOD("ムード分析", "🎭")
}

/**
 * Smart Chat Assistant.
 * Orchestrates conversation reply generation using:
 * - [ConversationMemoryManager] for complete partner-specific conversation history injection.
 * - [NotificationChatCacheManager] for background notification history without MediaProjection/Accessibility.
 * - [IPersonaSynthesizerPlugin] for high-EQ prompt assembly and reply parsing.
 * - [IDynamicModelRouterPlugin] for runtime model discovery and automatic fallback routing.
 */
class SmartChatAssistant(
    private var apiKey: String,
    private val guard: FreeTierGuard,
    private val memoryManager: ConversationMemoryManager? = null,
    private val router: IDynamicModelRouterPlugin = DynamicModelRouterPlugin(),
    private val synthesizer: IPersonaSynthesizerPlugin = PersonaSynthesizerPlugin()
) {
    companion object {
        const val TAG = "SmartChatAssistant"
        const val USER_LINE_LINK = PersonaSynthesizerPlugin.USER_LINE_LINK
        const val JA_LINE_INVITE = PersonaSynthesizerPlugin.JA_LINE_INVITE
        const val EN_LINE_INVITE = PersonaSynthesizerPlugin.EN_LINE_INVITE

        val FALLBACK_CANDIDATE_MODELS: List<String> = DynamicModelRouterPlugin.CANDIDATE_MODELS

        var cachedWorkingModel: String?
            get() = DynamicModelRouterPlugin.cachedWorkingModel
            set(value) {
                DynamicModelRouterPlugin.cachedWorkingModel = value
            }

        fun sanitizeApiKey(rawKey: String): String {
            return DynamicModelRouterPlugin.sanitizeKey(rawKey)
        }

        fun parseRepliesFromJson(rawText: String): List<SmartReplyOption> {
            return PersonaSynthesizerPlugin().parseReplies(rawText)
        }

        suspend fun fetchAvailableModels(key: String): List<String> {
            return DynamicModelRouterPlugin().fetchAvailableModels(key)
        }

        suspend fun testApiKey(rawKey: String): Pair<Boolean, String> {
            return DynamicModelRouterPlugin().testApiKey(rawKey)
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
            summary = "フランクなタメ口。短文（15〜30文字）でテンポ重視。ツッコミや軽いイジりが好きで、絵文字は控えめ（😂や笑を多用）。ストレートで回りくどい言い回しを嫌う。神奈川（厚木・海老名・茅ヶ崎・湘南）在住。",
            sampleQuotes = listOf("あいてるよ！なんかある？", "本厚木か茅ヶ崎で肉食べよー🥩", "それ笑うんだけど😂", "仕事終わったら連絡するわ！"),
            totalLearnedCount = 4
        )

        val partnerDossier = memoryManager?.getPartnerDossier(context.partnerName) ?: PartnerDossier(
            name = context.partnerName.ifEmpty { "相手" },
            location = "不明",
            personalityType = "感情表現豊か・リアクション重視",
            strategy = "相手のテンションに合わせ、共感しつつ軽くツッコミを入れて心地よく転がす",
            accumulatedMessageCount = 0
        )

        // Fetch full accumulated chronological conversation history for this partner
        val accumulatedHistory = memoryManager?.getAccumulatedConversation(context.partnerName) ?: emptyList()

        if (currentKey.isBlank()) {
            return@withContext GenerationResult(
                options = synthesizer.createFallbackReplies(context, accumulatedHistory, userStyle, shouldInvite),
                engine = "定型文 (※設定画面でAPIキーを保存してください)",
                errorDetails = "APIキーが未設定です"
            )
        }

        if (!guard.canRequest()) {
            return@withContext GenerationResult(
                options = synthesizer.createFallbackReplies(context, accumulatedHistory, userStyle, shouldInvite),
                engine = "定型文 (無料利用枠上限)",
                errorDetails = "1日の無料利用枠（1,500回）またはレート制限に達しました"
            )
        }

        // Synthesize Gemini prompt with accumulated conversation history injected
        val prompt = synthesizer.buildPrompt(
            context = context,
            accumulatedHistory = accumulatedHistory,
            userStyle = userStyle,
            partnerDossier = partnerDossier,
            forceLineInvite = forceLineInvite
        )

        // Route through model router with automatic fallback chain
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
            } else {
                Log.w(TAG, "Empty parse from model output: ${modelResult.responseText.take(60)}")
            }
        }

        // Fallback context-aware mock replies
        GenerationResult(
            options = synthesizer.createFallbackReplies(context, accumulatedHistory, userStyle, shouldInvite),
            engine = "定型文 (通信エラー)",
            errorDetails = modelResult.errorMessage ?: "全モデル接続失敗"
        )
    }

    /**
     * Generates replies using cached incoming notifications (NotificationListenerService)
     * and optional user clipboard content. Zero screen capture, zero accessibility service.
     */
    suspend fun generateRepliesFromNotificationContext(
        roomName: String,
        cachedMessages: List<CachedMessage>,
        clipboardText: String? = null,
        mode: AssistantMode = AssistantMode.REPLY
    ): GenerationResult = withContext(Dispatchers.IO) {
        val currentKey = apiKey.trim()
        val latestPartnerMsg = cachedMessages.lastOrNull { it.isPartner }?.text
            ?: cachedMessages.lastOrNull()?.text ?: ""
        val isPartnerPhoto = cachedMessages.any { it.isPartner && it.isPhoto() }

        // Polite detection: check if partner uses polite tone (です・ます等)
        val politeRegex = Regex("""(です|でした|ました|でしょうか|ですね|ですよ|ですか|はじめまして|よろしくお願い|ありがとうござい|いただけ|頂け|ご都合|いかが|存じ|恐れ入|致し|([ぁ-ん一-龯]ます))""")
        val isPolite = latestPartnerMsg.isNotBlank() && politeRegex.containsMatchIn(latestPartnerMsg)

        val fallbackOptions = when (mode) {
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

        val historySnippet = cachedMessages.takeLast(20).joinToString("\n") { m ->
            val role = if (m.isPartner) m.sender else "自分"
            "[$role]: ${m.text}"
        }.ifEmpty { "[$roomName]: $latestPartnerMsg" }

        val clipboardSnippet = if (!clipboardText.isNullOrBlank()) {
            "【ユーザーが直前にLINEトークからコピーしたメッセージ（★最優先で言及・返信）】\n「$clipboardText」\n"
        } else ""

        val prompt = buildNotificationPrompt(
            roomName = roomName,
            historySnippet = historySnippet,
            clipboardSnippet = clipboardSnippet,
            isPolite = isPolite,
            isPartnerPhoto = isPartnerPhoto,
            mode = mode
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
                    options = options.take(4),
                    engine = "Gemini (${modelResult.modelName})",
                    errorDetails = null
                )
            } else {
                Log.w(TAG, "Empty parse from notification prompt: ${modelResult.responseText.take(60)}")
            }
        }

        GenerationResult(
            options = fallbackOptions,
            engine = "定型文 (通信エラー)",
            errorDetails = modelResult.errorMessage ?: "接続失敗"
        )
    }

    private fun buildNotificationPrompt(
        roomName: String,
        historySnippet: String,
        clipboardSnippet: String,
        isPolite: Boolean,
        isPartnerPhoto: Boolean,
        mode: AssistantMode
    ): String {
        val modeInstruction = when (mode) {
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

        return """
            あなたはLINE等のメッセージ返信を支援する最高峰のAIコミュ力アシスタントです。
            ユーザーがLINE等のトーク画面を確認しながら、あなたに返信アシストを求めています。

            【相手のトークルーム名/相手の名前】: $roomName

            【相手との直近の通知メッセージ履歴（時系列）】
            $historySnippet

            $clipboardSnippet

            $modeInstruction

            【最重要ルール】
            1. 【敬語・トーンの完全同期】:
               ${if (isPolite) "相手は【敬語・丁寧語】（です・ます調）で話しています。必ずすべての返信案を好印象で親しみやすい丁寧語（です・ます調）で統一してください。タメ口は厳禁です！" else "相手は【フランク・タメ口】です。等身大で自然体のフランクな口調で作成してください。"}
            2. 【デート・待ち合わせ場所の厳守】:
               会う場所や店を提案する場合は、絶対に東京（渋谷・新宿・六本木等）を出さず、必ず神奈川県内（厚木、本厚木、海老名、茅ヶ崎、辻堂、藤沢、湘南、横浜）のエリアを提案してください。
            ${if (isPartnerPhoto) "3. 【写真リアクション】: 相手から写真や画像の通知が来ているため、写真や画像の内容に自然に触れてリアクションしてください。" else ""}

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
}
