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
        val isEnglish = context.isEnglishPartner

        val prompt = if (isBumble) {
            if (isEnglish) {
                if (shouldInvite) {
                    """
                    You are a witty, charismatic, real human on Bumble chatting with a match.
                    The conversation has progressed nicely (${context.rallyCount} rallies).
                    It is now the perfect natural moment to invite them to LINE to continue chatting and make plans.

                    Recent Dialogue Flow:
                    $historyText

                    Latest message from match:
                    "${context.latestPartnerMessage}"

                    Instructions:
                    - Directly respond to their latest message with authentic chemistry and zero robotic fluff.
                    - Keep it short (1-2 sentences), casual, matching their energy.
                    - Option 4 MUST invite them to LINE using this exact link:
                    "$EN_LINE_INVITE"

                    Output strict JSON array only:
                    [
                      {"label": "🎯 Direct Answer", "text": "thoughtful, engaging response to their topic"},
                      {"label": "🤝 Warm Connection", "text": "relatable shared vibe"},
                      {"label": "😄 Witty Banter", "text": "playful tease or humor"},
                      {"label": "📲 Switch to LINE", "text": "$EN_LINE_INVITE"}
                    ]
                    """.trimIndent()
                } else {
                    """
                    You are a witty, charismatic, real human on Bumble chatting with a match.
                    Early conversation (${context.rallyCount} rallies). Do NOT suggest LINE yet.
                    Focus on strong chemistry, playful flirting, and deep interest in what they shared.

                    Recent Dialogue Flow:
                    $historyText

                    Latest message from match:
                    "${context.latestPartnerMessage}"

                    Output strict JSON array only:
                    [
                      {"label": "🎯 Direct Answer", "text": "direct, authentic answer or reaction"},
                      {"label": "🤝 Warm Connection", "text": "relatable interest in their vibe"},
                      {"label": "😄 Witty Banter", "text": "light, charming tease"},
                      {"label": "☕ Shared Vibe", "text": "curious question opening up fun topics"}
                    ]
                    """.trimIndent()
                }
            } else {
                if (shouldInvite) {
                    """
                    あなたはマッチングアプリ（Bumble）で魅力的なやり取りをするプロの会話アシスタントです。
                    会話が ${context.rallyCount} 回以上ラリーし、十分に打ち解けた絶妙なタイミングです。
                    直前の相手の言葉に深く反応しつつ、自然にLINE交換を提案する返信を作ってください。

                    【これまでの対話履歴（時系列順）】
                    $historyText

                    【相手の最新メッセージ】
                    "${context.latestPartnerMessage}"

                    【重要ルール】
                    1. 「いいね！最近どう？」のような浅い定型文は厳禁。相手の具体的な話題（趣味、仕事、休日の過ごし方、感情）をしっかり拾ってください。
                    2. 相手がタメ口ならタメ口、敬語なら親しみやすい敬語でトーンを同調させてください。
                    3. 4つ目の選択肢は、必ず以下のLINE交換の誘導文（指定リンク付き）を自然に提案してください：
                    「$JA_LINE_INVITE」

                    出力は以下のJSON配列のみ：
                    [
                      {"label": "🎯 直球・リアクション", "text": "相手の話題に対するストレートで魅力的な反応"},
                      {"label": "🤝 共感＋深掘り", "text": "親しみやすく話を広げる返し"},
                      {"label": "😄 軽快なユーモア", "text": "クスッと笑える楽しい返し"},
                      {"label": "📲 LINEへ移行", "text": "$JA_LINE_INVITE"}
                    ]
                    """.trimIndent()
                } else {
                    """
                    あなたはマッチングアプリ（Bumble）で魅力的なやり取りをするプロの会話アシスタントです。
                    序盤のやり取り（${context.rallyCount} ラリー目）なので、LINE交換はまだ急がず、相手が「この人と話すと楽しい！」と感じる魅力的な会話を広げてください。

                    【これまでの対話履歴（時系列順）】
                    $historyText

                    【相手の最新メッセージ】
                    "${context.latestPartnerMessage}"

                    【重要ルール】
                    1. 相手の最新メッセージに具体的に反応してください。おざなりな定型文は禁止です。
                    2. 自然な親近感とユーモアを大切にしてください（短文1〜2文）。

                    出力は以下のJSON配列のみ：
                    [
                      {"label": "🎯 直球・リアクション", "text": "相手の発言に対する具体的でリアルな反応"},
                      {"label": "🤝 共感＋深掘り", "text": "共感しつつ自然に話を広げる返し"},
                      {"label": "😄 軽快なユーモア", "text": "クスッと笑える楽しい返し"},
                      {"label": "☕ 次の話題", "text": "共通の興味や休日の過ごし方を聞く返し"}
                    ]
                    """.trimIndent()
                }
            }
        } else {
            // LINE context: heavily weight dialogue flow, speech-act and realism
            """
            あなたはLINEで極めてリアルで人間らしい返信を作成する、プロの会話アシスタントです。
            会話の流れ（誰が何を言って、どうラリーが続いているか）を完全に把握し、相手の問いかけや言葉に対する「自然で的確な返信」を4つの異なるバリエーションで作成してください。

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

        if (context.platform == "Bumble") {
            if (context.isEnglishPartner) {
                return if (shouldInvite) {
                    listOf(
                        SmartReplyOption("🎯 Direct Answer", "Haha absolutely! That sounds like such a good vibe 😊"),
                        SmartReplyOption("🤝 Warm Connection", "I totally relate to that! How did you first get into it?"),
                        SmartReplyOption("😄 Witty Banter", "Okay, now you definitely owe me the full story behind that 😄"),
                        SmartReplyOption("📲 Switch to LINE", EN_LINE_INVITE)
                    )
                } else {
                    listOf(
                        SmartReplyOption("🎯 Direct Answer", "Haha absolutely! That sounds like such a good vibe 😊"),
                        SmartReplyOption("🤝 Warm Connection", "I totally relate to that! How is your week going so far?"),
                        SmartReplyOption("😄 Witty Banter", "Okay, now you definitely owe me the full story behind that 😄"),
                        SmartReplyOption("☕ Shared Topic", "By the way, what do you usually like doing on weekends? ✨")
                    )
                }
            } else {
                return if (shouldInvite) {
                    listOf(
                        SmartReplyOption("🎯 直球・リアクション", "めっちゃわかります！それすごく楽しそうですね😊"),
                        SmartReplyOption("🤝 共感＋深掘り", "お話しやすくて嬉しいです✨ 普段はどんなことされてるんですか？"),
                        SmartReplyOption("😄 軽快なユーモア", "センス良すぎます😂 もっと詳しく聞かせてほしいです！"),
                        SmartReplyOption("📲 LINEへ移行", JA_LINE_INVITE)
                    )
                } else {
                    listOf(
                        SmartReplyOption("🎯 直球・リアクション", "めっちゃわかります！それすごく楽しそうですね😊"),
                        SmartReplyOption("🤝 共感＋深掘り", "プロフの写真も素敵でした！休日は何して過ごすことが多いですか？✨"),
                        SmartReplyOption("😄 軽快なユーモア", "センス良すぎます😂 もっと詳しく聞かせてほしいです！"),
                        SmartReplyOption("☕ 次の話題", "私もそれ好きです！おすすめのスポットとかありますか？☕")
                    )
                }
            }
        }

        // LINE: Real human replies tailored to speech act
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
