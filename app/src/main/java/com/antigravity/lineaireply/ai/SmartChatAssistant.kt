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
}
