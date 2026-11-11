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
    val engine: String, // "Gemini 2.5 Flash" or "定型文 (理由)"
    val errorDetails: String? = null
)

/**
 * Smart Chat Assistant.
 * Orchestrates conversation reply generation using:
 * - [ConversationMemoryManager] for complete partner-specific conversation history injection.
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
     * Generates high-EQ reply suggestions directly from a screen capture image using Gemini Vision.
     */
    suspend fun generateRepliesFromScreenshot(
        imageBase64: String
    ): GenerationResult = withContext(Dispatchers.IO) {
        val currentKey = apiKey.trim()
        val fallbackOptions = listOf(
            SmartReplyOption("🎯 即答・ストレート", "了解！楽しみに待ってるね😊"),
            SmartReplyOption("😄 共感・ノリ良く", "それめっちゃいいね！詳しく教えて笑"),
            SmartReplyOption("💬 質問・話題広げ", "そうなんだ！ちなみにいつ頃が都合良さそう？")
        )

        if (currentKey.isBlank()) {
            return@withContext GenerationResult(
                options = fallbackOptions,
                engine = "定型文 (※設定画面でAPIキーを保存してください)",
                errorDetails = "APIキーが未設定です"
            )
        }

        if (!guard.canRequest()) {
            return@withContext GenerationResult(
                options = fallbackOptions,
                engine = "定型文 (利用枠上限)",
                errorDetails = "1日の無料利用枠（1,500回）またはレート制限に達しました"
            )
        }

        val visionPrompt = """
            あなたはLINEのメッセージ返信を支援する最高峰のAIアシスタントです。
            添付された画像は、ユーザーのスマートフォンの「LINEトーク画面」のスクリーンショットです。

            【指示】
            画面内のLINEトークの文脈、直前の相手のメッセージ、相手が送ってきた写真・スタンプの有無、および相手のトーン（敬語かタメ口か）を画像から詳細に読み取り、ユーザーがそのまま返信できる自然で魅力的な返信候補を3パターン作成してください。

            【最重要ルール】
            1. 【敬語・トーンの完全同期】:
               - 相手のメッセージが敬語（丁寧語：「です」「ます」「でしょうか」「ありがとうございます」等）の場合は、必ず全候補を好印象で親しみやすい丁寧語（です・ます調）で統一してください。タメ口は厳禁です。
               - 相手がフランクなタメ口の場合は、自然なフランク口調で作成してください。
            2. 【デート・待ち合わせ場所の厳守】:
               - 会う場所や食事を提案・言及する場合は、絶対に東京（渋谷・新宿・六本木等）を出さず、必ず神奈川県内（厚木、本厚木、海老名、茅ヶ崎、辻堂、藤沢、湘南、横浜）のエリアを提案してください。
            3. 【写真へのリアクション】:
               - 相手が画像や写真を送っている場合は、その写真の内容（食事、風景、ペット、服装など）に自然に触れてリアクションしてください。
            4. 【出力フォーマット】:
               Markdownコードブロックや解説は一切出力せず、必ず以下のJSON配列形式のみを出力してください：
               [
                 {"label": "🎯 即答・ストレート", "text": "返信文1"},
                 {"label": "😄 共感・ノリ良く", "text": "返信文2"},
                 {"label": "💬 質問・話題広げ", "text": "返信文3"}
               ]
        """.trimIndent()

        val modelResult = router.generateContent(
            apiKey = currentKey,
            prompt = visionPrompt,
            preferredModel = cachedWorkingModel,
            imageBase64 = imageBase64
        )

        if (modelResult.success && modelResult.responseText.isNotBlank()) {
            val options = synthesizer.parseReplies(modelResult.responseText)
            if (options.isNotEmpty()) {
                guard.recordRequest()
                return@withContext GenerationResult(
                    options = options.take(3),
                    engine = "Gemini Vision (${modelResult.modelName})",
                    errorDetails = null
                )
            } else {
                Log.w(TAG, "Empty parse from vision model: ${modelResult.responseText.take(60)}")
            }
        }

        GenerationResult(
            options = fallbackOptions,
            engine = "定型文 (通信エラー)",
            errorDetails = modelResult.errorMessage ?: "全モデル接続失敗"
        )
    }
}
