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
    private val guard: FreeTierGuard
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

        suspend fun testApiKey(key: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
            if (key.isBlank()) return@withContext Pair(false, "APIキーが空です")
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$key"
                val body = JSONObject().apply {
                    val parts = JSONArray().put(JSONObject().put("text", "Say OK"))
                    put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                }
                val req = Request.Builder()
                    .url(endpoint)
                    .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                val resp = client.newCall(req).execute()
                val code = resp.code
                val respBody = resp.body?.string() ?: ""
                if (resp.isSuccessful) {
                    Pair(true, "通信成功 (HTTP $code)")
                } else {
                    Pair(false, "HTTP $code エラー: $respBody")
                }
            } catch (e: Exception) {
                Pair(false, "通信例外: ${e.message}")
            }
        }
    }

    fun updateApiKey(newKey: String) {
        apiKey = newKey.trim()
    }

    suspend fun generateReplies(
        context: ChatContext,
        forceLineInvite: Boolean = false
    ): GenerationResult = withContext(Dispatchers.IO) {
        val currentKey = apiKey.trim()
        val shouldInvite = (context.shouldSuggestLineInvite || forceLineInvite)

        if (currentKey.isBlank()) {
            return@withContext GenerationResult(
                options = fallbackMockReplies(context, shouldInvite),
                engine = "定型文 (※設定画面でAPIキーを保存してください)",
                errorDetails = "APIキーが未設定です"
            )
        }

        if (!guard.canRequest()) {
            return@withContext GenerationResult(
                options = fallbackMockReplies(context, shouldInvite),
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
            【相手のプロフィール・自己紹介・プロンプト情報（※超重要）】
            ${context.profileSnippet}
            """.trimIndent()
        } else {
            "【相手のプロフィール情報】\n（※画面からプロフィール文が直接取得できなかったため、会話ログの文脈から相手の興味・好みを分析してください）"
        }

        val partnerNameSection = if (context.partnerName.isNotBlank()) {
            "【相手の名前/ニックネーム】: ${context.partnerName}\n"
        } else ""

        val prompt = if (isBumble) {
            """
            あなたはマッチングアプリ（Bumble）で、相手の心を強く惹きつける最高に自然でリアルな返信を作成するプロの会話アシスタントです。
            会話ログだけでなく、【相手のプロフィール情報（趣味、仕事、出身地、興味、プロンプト回答等）】を徹底的に活用し、相手の言語や文化的背景（外国人・英語話者・バイリンガル・日本人）に完璧に合わせた返信を4つのバリエーションで作成してください。

            $partnerNameSection
            【相手の背景・言語分析】
            - 検出傾向: ${context.languageDesc} (外国人/英語話者の可能性: ${if (isEnglishOrForeign) "高" else "低/日本人"})
            - 言語の適用ルール:
              * 相手が英語話者（英語メッセージや海外プロフ）の場合: 英語で最高にチャーミングでウィットに富んだ返信を作成してください。
              * 相手が外国人で日本語を使っている場合: 親しみやすく分かりやすい日本語（または英語を交えた自然なバイリンガルトーク）にしてください。
              * 相手が日本人の場合: 相手のノリ（タメ口/敬語、絵文字、「笑」など）に同調した自然な日本語にしてください。

            $profileSection

            【直近の対話ログ（時系列順：上から下へ）】
            $historyText

            【相手の最新メッセージ】
            "${context.latestPartnerMessage}"

            ${if (context.latestMyMessage.isNotBlank()) "【直前のあなたのメッセージ】\n\"${context.latestMyMessage}\"" else ""}

            【絶対厳守の返信生成ルール】
            1. 【プロフィール情報の自然な拾い上げ（最重要）】
               相手のプロフィールに書かれている話題（趣味、旅行、サウナ、映画、カフェ、音楽、仕事、出身地、プロンプト回答など）を自然に絡めて、「ちゃんとプロフを見て興味を持ってくれている！」という安心感と強い好感度を与えてください。
            2. 【相手の発言・質問への確実な回答】
               相手が質問している場合は、曖昧な相槌やごまかしは厳禁。具体的で明確な回答（時間、場所、料理ジャンル等）を必ず返してください。
            3. 【4つの異なる返信ペルソナ】
               - 案1 🎯【直球回答・プロフ深掘り】: 相手の質問に即答しつつ、相手の話題やプロフに魅力的に切り込む。
               - 案2 🤝【共感・親近感】: 共通の趣味や価値観に優しく寄り添い、安心感を与える返答。
               - 案3 😄【軽快ユーモア・ツッコミ】: クスッと笑える楽しい返し（Bumbleらしいチャーミングなイジり）。
               - 案4 🚀【次の提案 / 📲 LINEへ移行】:
                 ${if (shouldInvite) "絶妙なタイミングなので、自然な流れで以下のLINE移行文（リンク付き）を提示してください：\n" + (if (isEnglishOrForeign) "「$EN_LINE_INVITE」" else "「$JA_LINE_INVITE」") else "「今度〜行こ！」「おすすめ教えて！」と会話を次のアクションに進める提案。"}
            4. 【AI構文・浅い定型文の完全追放】
               「いいね！最近どう？」「お疲れ様です」「ご連絡ありがとうございます」などのAI構文は完全禁止。短文（1〜2文）でテンポよく返してください。

            出力は以下のJSON配列のみ（Markdownコードブロック不要）：
            [
              {"label": "🎯 直球・プロフ話題", "text": "（具体的回答またはプロフに触れた魅力的な返し）"},
              {"label": "🤝 共感・親近感", "text": "（相手のノリや共通の趣味に寄り添う返し）"},
              {"label": "😄 軽快ユーモア", "text": "（クスッと笑えるウィットな返し）"},
              {"label": "${if (shouldInvite) "📲 LINEへ移行" else "🚀 次の提案"}", "text": "${if (shouldInvite) (if (isEnglishOrForeign) EN_LINE_INVITE else JA_LINE_INVITE) else "（具体的なお誘い・提案）"}"}
            ]
            """.trimIndent()
        } else {
            // LINE context: heavily weight dialogue flow, speech-act and foreign/Japanese background
            """
            あなたはLINEで極めてリアルで人間らしい返信を作成する、プロの会話アシスタントです。
            会話の流れ（誰が何を言って、どうラリーが続いているか）を完全に把握し、相手の問いかけや言葉に対する「自然で的確な返信」を4つの異なるバリエーションで作成してください。
            相手が外国人や英語話者の場合も自然に対応してください。

            $partnerNameSection
            【言語・文化的背景】
            - 検出傾向: ${context.languageDesc}
            - 相手が英語話者・英語メッセージの場合: 自然でフレンドリーな英語で返答してください。
            - 相手がタメ口の日本語ならタメ口、敬語なら敬語、外国人風の日本語なら分かりやすく親切な日本語にしてください。

            $profileSection

            【直近の会話履歴（時系列順：上から下へ）】
            $historyText

            【直前の相手のメッセージ】
            "${context.latestPartnerMessage}"

            ${if (context.latestMyMessage.isNotBlank()) "【直前のあなたのメッセージ（もしあれば）】\n\"${context.latestMyMessage}\"" else ""}

            【絶対厳守の返信生成ルール】
            1. 【質問へのド直球回答（最優先）】
               相手が質問している場合（例：「何時にする？」「どこ行く？」「どっちがいい？」「何食べたい？」「知ってる？」等）、
               案1（🎯 即答・ストレート）は、絶対に質問をごまかさず、具体的で明確な回答（具体的な時間・具体的な場所・食べたい料理ジャンル・明確なYes/No）を必ず返してください。
               「いいね！最近どう？」「鋭い質問だね」「なんでもいいよ」「ちょうど考えてた」等の回答放棄は【完全禁止】です。

            2. 【4つの異なる返信ペルソナ】
               以下の4つの異なる角度・ノリで必ず作成してください：
               - 案1 🎯【即答・ストレート】: 相手の問いかけや話題に対して、最も具体的かつストレートに答える。
               - 案2 🤝【共感・ノリ合わせ】: 相手のテンションや感情（楽しそう、疲れた等）に優しく寄り添い、ノリを合わせた返答。
               - 案3 😄【ツッコミ・軽快ユーモア】: 短くクスッと笑えるイジりやウィットに富んだ返し（「笑」「w」などを自然に交える）。
               - 案4 🚀【次の提案・お誘い】: 「〜予約しとくね！」「〜行こっか！」と会話を行動に具体的に一歩進める提案。

            3. 【トーン＆マナー・文字数の完全同調】
               - 相手がタメ口（カジュアル）なら、100%タメ口で返してください。敬語は不自然なので禁止です。
               - 相手が敬語（です・ます）なら、親しみやすい敬語で返してください。
               - 相手の絵文字や「笑」「w」のノリに合わせてください。
               - 1メッセージは短く（15〜40文字、1〜2文程度）。LINEらしいテンポ感を再現してください。

            4. 【AI構文・優等生定型文の完全追放】
               以下のフレーズは絶対に含めないでください：
               ×「お疲れ様です」「ご連絡ありがとうございます」「素敵な質問ですね」「〜についてどう思いますか？」

            出力は以下のJSON配列のみ（Markdownコードブロック不要）：
            [
              {"label": "🎯 即答・ストレート", "text": "（具体的で明確な返答）"},
              {"label": "🤝 共感・ノリ合わせ", "text": "（親近感や感情を受け止める返答）"},
              {"label": "😄 ツッコミ・軽快", "text": "（クスッと笑えるテンポの良い返し）"},
              {"label": "🚀 次の提案・お誘い", "text": "（行動を一歩進める具体的な提案）"}
            ]
            """.trimIndent()
        }

        // Try primary model (gemini-1.5-flash), then fallback model (gemini-2.0-flash)
        val models = listOf("gemini-1.5-flash", "gemini-2.0-flash")
        var lastError = ""

        for (model in models) {
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
                    lastError = "HTTP $code: $respStr"
                    Log.w(TAG, "Model $model returned error: $lastError")
                    continue
                }

                guard.recordRequest()
                val root = JSONObject(respStr)
                val outputText = root.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")

                val array = JSONArray(outputText.trim())
                val list = mutableListOf<SmartReplyOption>()
                for (i in 0 until array.length().coerceAtMost(4)) {
                    val item = array.getJSONObject(i)
                    list.add(
                        SmartReplyOption(
                            label = item.optString("label", "案 ${i + 1}"),
                            text = item.optString("text", "")
                        )
                    )
                }

                if (list.isNotEmpty()) {
                    return@withContext GenerationResult(
                        options = list,
                        engine = "Gemini (${model})",
                        errorDetails = null
                    )
                }
            } catch (e: Exception) {
                lastError = "例外 (${model}): ${e.message}"
                Log.e(TAG, "Call failed for $model", e)
            }
        }

        // If both models failed, return high-quality context-aware fallback
        GenerationResult(
            options = fallbackMockReplies(context, shouldInvite),
            engine = "定型文 (通信エラー)",
            errorDetails = lastError
        )
    }

    private fun fallbackMockReplies(context: ChatContext, shouldInvite: Boolean): List<SmartReplyOption> {
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
                        SmartReplyOption("🎯 直球・プロフ話題", if (hasProfile) "プロフ見て気になってました！すごく楽しそうですね😊" else "めっちゃわかります！すごく楽しそうですね😊"),
                        SmartReplyOption("🤝 共感＋深掘り", "お話しやすくて嬉しいです✨ 普段はどんなことされてるんですか？"),
                        SmartReplyOption("😄 軽快なユーモア", "センス良すぎます😂 もっと詳しく聞かせてほしいです！"),
                        SmartReplyOption("📲 LINEへ移行", JA_LINE_INVITE)
                    )
                } else {
                    listOf(
                        SmartReplyOption("🎯 直球・プロフ話題", if (hasProfile) "プロフ見て気になってました！休日は何して過ごすことが多いですか？✨" else "めっちゃわかります！休日は何して過ごすことが多いですか？✨"),
                        SmartReplyOption("🤝 共感＋深掘り", "写真も素敵でした！お話しできて嬉しいです😊"),
                        SmartReplyOption("😄 軽快なユーモア", "センス良すぎます😂 もっと詳しく聞かせてほしいです！"),
                        SmartReplyOption("☕ 次の話題", "私もそれ好きです！おすすめのスポットとかありますか？☕")
                    )
                }
            }
        }

        // LINE: Real human replies tailored to speech act
        if (context.isEnglishPartner) {
            return listOf(
                SmartReplyOption("🎯 Direct Answer", "Sounds awesome! Count me in 👍"),
                SmartReplyOption("🤝 Friendly Vibe", "Totally agree with you! How is everything going on your end? 😊"),
                SmartReplyOption("😄 Witty / Fun", "Haha that made my day! You always have the best stories 😂"),
                SmartReplyOption("🚀 Next Plan", "Let's definitely catch up soon! When are you free next? ✨")
            )
        }

        return when {
            // Question: time / date
            latest.contains(Regex("[?？]")) && latest.contains(Regex("時|いつ|日程|日|曜|何時")) -> listOf(
                SmartReplyOption("🎯 即答・ストレート", "19時くらいはどう？仕事終わりだからちょうど良さそう！"),
                SmartReplyOption("🤝 共感・ノリ合わせ", "合わせるよ！そっちは何時くらいが都合いい？😊"),
                SmartReplyOption("😄 ツッコミ・軽快", "早い時間から乾杯しちゃおっか笑 18時半とかいける？🍻"),
                SmartReplyOption("🚀 次の提案・お誘い", "金曜か土曜の夜ならいつでも空いてるよ！どっちがいい？")
            )
            // Question: location / place
            latest.contains(Regex("[?？]")) && latest.contains(Regex("どこ|場所|駅|店|エリア")) -> listOf(
                SmartReplyOption("🎯 即答・ストレート", "渋谷か新宿あたりが行きやすくて助かる！"),
                SmartReplyOption("🤝 共感・ノリ合わせ", "そっちの行きやすいエリアに合わせるよ！どこら辺がいい？✨"),
                SmartReplyOption("😄 ツッコミ・軽快", "美味しいご飯があるならどこへでも飛んでくよ笑😋"),
                SmartReplyOption("🚀 次の提案・お誘い", "気になってるお店がいくつかあるからURL送るね！")
            )
            // Question: food / cuisine
            latest.contains(Regex("[?？]")) && latest.contains(Regex("何食べ|何系|ご飯|ランチ|ディナー|店")) -> listOf(
                SmartReplyOption("🎯 即答・ストレート", "お肉かイタリアンが食べたい気分！🥩"),
                SmartReplyOption("🤝 共感・ノリ合わせ", "何でも美味しく食べられる！そっちは何か食べたいのある？😊"),
                SmartReplyOption("😄 ツッコミ・軽快", "完全に飯テロだ笑 美味しいものならなんでも大歓迎😂"),
                SmartReplyOption("🚀 次の提案・お誘い", "行きたいお店リストあるからピックアップして送るね！")
            )
            // General question
            latest.contains(Regex("[?？]")) -> listOf(
                SmartReplyOption("🎯 即答・ストレート", "もちろん！全然大丈夫だよ👍"),
                SmartReplyOption("🤝 共感・ノリ合わせ", "いいよ！そっちのペースに合わせるね😊"),
                SmartReplyOption("😄 ツッコミ・軽快", "まさかの質問きた笑 どっちでもウェルカムだよ😂"),
                SmartReplyOption("🚀 次の提案・お誘い", "今度直接会ったときに詳しく話そ！✨")
            )
            // Fatigue / Empathy
            full.contains(Regex("仕事|疲れた|残業|忙しい|眠い|大変|しんどい")) -> listOf(
                SmartReplyOption("🎯 即答・ストレート", "今日もお疲れ様！本当によく頑張ったね🍵"),
                SmartReplyOption("🤝 共感・ノリ合わせ", "大変だったね…！無理しないで今日はゆっくり休んでね🥺"),
                SmartReplyOption("😄 ツッコミ・軽快", "それは疲れるやつだ😂 美味しいもの食べて癒やされて！"),
                SmartReplyOption("🚀 次の提案・お誘い", "落ち着いたらパーッと美味しいものでも食べ行こ！")
            )
            // Food / Weekend
            full.contains(Regex("ご飯|カフェ|美味しい|ラーメン|焼肉|飲み|ランチ|居酒屋|休み|週末")) -> listOf(
                SmartReplyOption("🎯 即答・ストレート", "美味しそう！そのお店めっちゃ気になってたんだよね😋"),
                SmartReplyOption("🤝 共感・ノリ合わせ", "いいね！美味しいもの食べてる時が一番幸せ✨"),
                SmartReplyOption("😄 ツッコミ・軽快", "写真見てたら急にお腹空いてきたんだけど笑😂"),
                SmartReplyOption("🚀 次の提案・お誘い", "今度一緒に行こ！予定合わせよ〜🍽️")
            )
            else -> listOf(
                SmartReplyOption("🎯 即答・ストレート", "それめっちゃわかる！まさに同じこと思ってたところ"),
                SmartReplyOption("🤝 共感・ノリ合わせ", "いいじゃん！聞いてるだけで楽しそう😊"),
                SmartReplyOption("😄 ツッコミ・軽快", "それは笑うんだけど😂 さすがだわ！"),
                SmartReplyOption("🚀 次の提案・お誘い", "また詳しく聞かせて！近いうちに話そ✨")
            )
        }
    }
}
