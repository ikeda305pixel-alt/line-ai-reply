package com.antigravity.lineaireply.ai

import android.content.SharedPreferences
import android.util.Log
import com.antigravity.lineaireply.plugin.DynamicModelRouterPlugin
import com.antigravity.lineaireply.plugin.IDynamicModelRouterPlugin
import com.antigravity.lineaireply.plugin.IPersonaSynthesizerPlugin
import com.antigravity.lineaireply.plugin.PersonaSynthesizerPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
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
    private val synthesizer: IPersonaSynthesizerPlugin = PersonaSynthesizerPlugin(),
    private val prefs: SharedPreferences? = null
) {

    init {
        prefs?.getString("cached_working_model", null)?.let { saved ->
            if (saved.isNotBlank() && DynamicModelRouterPlugin.CANDIDATE_MODELS.contains(saved)) {
                cachedWorkingModel = saved
            } else {
                // Eliminate zombie deprecated models (e.g. gemini-2.0-flash, gemini-1.5-flash)
                prefs.edit().remove("cached_working_model").apply()
                if (cachedWorkingModel == saved) {
                    cachedWorkingModel = null
                }
            }
        }
    }

    private fun saveCachedWorkingModel(model: String) {
        if (DynamicModelRouterPlugin.CANDIDATE_MODELS.contains(model)) {
            cachedWorkingModel = model
            prefs?.edit()?.putString("cached_working_model", model)?.apply()
        } else {
            removeCachedWorkingModel()
        }
    }

    private fun removeCachedWorkingModel() {
        cachedWorkingModel = null
        prefs?.edit()?.remove("cached_working_model")?.apply()
    }

    suspend fun warmupConnection() = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com")
                .head()
                .build()
            DynamicModelRouterPlugin.sharedClient.newCall(req).execute().use { }
        } catch (_: Exception) {
            // Safe pre-warm, ignore exceptions
        }
    }

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
            return router.testApiKey(cleanKey)
        }
    }

    fun updateApiKey(newKey: String) {
        this.apiKey = sanitizeApiKey(newKey)
    }

    /**
     * Determines if the text indicates an English speaker or foreign partner.
     */
    fun isEnglishOrForeign(text: String): Boolean = ChatPromptBuilder.isEnglishOrForeign(text)

    fun detectPolite(text: String): Boolean = ChatPromptBuilder.detectPolite(text)

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
        val userStyle = memoryManager?.getUserStyle()
        val partnerDossier = memoryManager?.getPartnerDossier("")

        if (!isValidApiKey(currentKey)) {
            val fallbacks = getFallbackOptions(isPolite = false, mode = mode, isEnglish = false)
            return@withContext GenerationResult(
                options = fallbacks,
                engine = "⚠️ APIキー未設定 (フォールバック)",
                errorDetails = "設定画面でGemini APIキー（AIza... または AQ....）を保存してください"
            )
        }

        if (!guard.canRequest()) {
            val fallbacks = getFallbackOptions(isPolite = false, mode = mode, isEnglish = false)
            return@withContext GenerationResult(
                options = fallbacks,
                engine = "⚠️ 利用枠上限 (フォールバック)",
                errorDetails = "1日の無料利用枠に達しました"
            )
        }

        val prompt = ChatPromptBuilder.buildPromptForVision(
            mode = mode,
            userPromptNote = userPromptNote,
            userStyle = userStyle,
            partnerDossier = partnerDossier,
            includeLineInvite = true
        )

        val callStart = System.currentTimeMillis()
        val modelResult = router.generateContent(
            apiKey = currentKey,
            prompt = prompt,
            preferredModel = cachedWorkingModel,
            imageBase64 = imageBase64
        )
        val latencyMs = System.currentTimeMillis() - callStart

        if (modelResult.success && modelResult.responseText.isNotBlank()) {
            val options = synthesizer.parseReplies(modelResult.responseText)
            val recognizedTarget = extractTargetMessage(modelResult.responseText)
            if (options.isNotEmpty()) {
                guard.recordRequest()
                saveCachedWorkingModel(modelResult.modelName)
                return@withContext GenerationResult(
                    options = options.take(4),
                    engine = "Gemini Vision (${modelResult.modelName} · ${latencyMs}ms)",
                    errorDetails = null,
                    targetMessage = recognizedTarget
                )
            } else {
                Log.w(TAG, "Empty parse from Gemini Vision output: ${modelResult.responseText.take(60)}")
            }
        }

        // On API error or empty parse, return high-EQ fallback options to guarantee options is never empty
        val isEnglishDetected = userPromptNote?.let { isEnglishOrForeign(it) } ?: false
        val fallbackOptions = getFallbackOptions(isPolite = false, mode = mode, isEnglish = isEnglishDetected)

        GenerationResult(
            options = fallbackOptions,
            engine = "⚠️ フォールバック生成",
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
        val isEnglish = isEnglishOrForeign(clipboardText)
        val userStyle = memoryManager?.getUserStyle()
        val partnerDossier = memoryManager?.getPartnerDossier("")

        if (!isValidApiKey(currentKey)) {
            val fallbacks = getFallbackOptions(isPolite = isPolite, mode = mode, isEnglish = isEnglish)
            return@withContext GenerationResult(
                options = fallbacks,
                engine = "⚠️ APIキー未設定 (フォールバック)",
                errorDetails = "設定画面でGemini APIキー（AIza... または AQ....）を保存してください"
            )
        }

        if (!guard.canRequest()) {
            val fallbacks = getFallbackOptions(isPolite = isPolite, mode = mode, isEnglish = isEnglish)
            return@withContext GenerationResult(
                options = fallbacks,
                engine = "⚠️ 利用枠上限 (フォールバック)",
                errorDetails = "1日の無料利用枠に達しました"
            )
        }

        val isMatchingApp = clipboardText.contains("Bumble", ignoreCase = true) ||
                clipboardText.contains("マッチしました") ||
                clipboardText.contains("matched with", ignoreCase = true)

        val prompt = ChatPromptBuilder.buildPromptForText(
            contextText = clipboardText,
            contextSource = "ユーザーがLINEからコピーしたメッセージ",
            isPolite = isPolite,
            mode = mode,
            userPromptNote = userPromptNote,
            userStyle = userStyle,
            partnerDossier = partnerDossier,
            includeLineInvite = isMatchingApp
        )

        val callStart = System.currentTimeMillis()
        val modelResult = router.generateContent(
            apiKey = currentKey,
            prompt = prompt,
            preferredModel = cachedWorkingModel
        )
        val latencyMs = System.currentTimeMillis() - callStart

        if (modelResult.success && modelResult.responseText.isNotBlank()) {
            val options = synthesizer.parseReplies(modelResult.responseText)
            if (options.isNotEmpty()) {
                guard.recordRequest()
                saveCachedWorkingModel(modelResult.modelName)
                return@withContext GenerationResult(
                    options = options.take(4),
                    engine = "Gemini (${modelResult.modelName} · ${latencyMs}ms)",
                    errorDetails = null
                )
            }
        }

        if (!modelResult.success && cachedWorkingModel != null && !modelResult.errorMessage.isNullOrBlank() && modelResult.errorMessage.contains("404")) {
            removeCachedWorkingModel()
        }

        val fallbackOptions = getFallbackOptions(isPolite = isPolite, mode = mode, isEnglish = isEnglish)
        GenerationResult(
            options = fallbackOptions,
            engine = "⚠️ フォールバック生成",
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
        val isEnglish = isEnglishOrForeign(userPromptNote)
        val userStyle = memoryManager?.getUserStyle()
        val partnerDossier = memoryManager?.getPartnerDossier("")

        if (!isValidApiKey(currentKey)) {
            val fallbacks = getFallbackOptions(isPolite = isPolite, mode = mode, isEnglish = isEnglish)
            return@withContext GenerationResult(
                options = fallbacks,
                engine = "⚠️ APIキー未設定 (フォールバック)",
                errorDetails = "設定画面でGemini APIキー（AIza... または AQ....）を保存してください"
            )
        }

        if (!guard.canRequest()) {
            val fallbacks = getFallbackOptions(isPolite = isPolite, mode = mode, isEnglish = isEnglish)
            return@withContext GenerationResult(
                options = fallbacks,
                engine = "⚠️ 利用枠上限 (フォールバック)",
                errorDetails = "1日の無料利用枠に達しました"
            )
        }

        val isMatchingApp = userPromptNote.contains("Bumble", ignoreCase = true) ||
                userPromptNote.contains("マッチング", ignoreCase = true) ||
                userPromptNote.contains("Tinder", ignoreCase = true)

        val prompt = ChatPromptBuilder.buildPromptForText(
            contextText = userPromptNote,
            contextSource = "ユーザーが直接入力したトピック・要件メモ",
            isPolite = isPolite,
            mode = mode,
            userPromptNote = null,
            userStyle = userStyle,
            partnerDossier = partnerDossier,
            includeLineInvite = isMatchingApp
        )

        val callStart = System.currentTimeMillis()
        val modelResult = router.generateContent(
            apiKey = currentKey,
            prompt = prompt,
            preferredModel = cachedWorkingModel
        )
        val latencyMs = System.currentTimeMillis() - callStart

        if (modelResult.success && modelResult.responseText.isNotBlank()) {
            val options = synthesizer.parseReplies(modelResult.responseText)
            if (options.isNotEmpty()) {
                guard.recordRequest()
                saveCachedWorkingModel(modelResult.modelName)
                return@withContext GenerationResult(
                    options = options.take(4),
                    engine = "Gemini (${modelResult.modelName} · ${latencyMs}ms)",
                    errorDetails = null
                )
            }
        }

        if (!modelResult.success && cachedWorkingModel != null && !modelResult.errorMessage.isNullOrBlank() && modelResult.errorMessage.contains("404")) {
            removeCachedWorkingModel()
        }

        val fallbackOptions = getFallbackOptions(isPolite = isPolite, mode = mode, isEnglish = isEnglish)
        GenerationResult(
            options = fallbackOptions,
            engine = "⚠️ フォールバック生成",
            errorDetails = modelResult.errorMessage ?: "Gemini APIとの通信に失敗しました"
        )
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

