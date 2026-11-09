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
    val engine: String, // "Gemini 1.5 Flash" or "モック (理由)"
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
                engine = "定型文 (無料利用上限)",
                errorDetails = "1日の無料利用枠（1,500回）またはレート制限に達しました"
            )
        }

        val historyText = context.conversationHistory.joinToString("\n") { "・$it" }
        val isBumble = context.platform == "Bumble"
        val isEnglish = context.isEnglishPartner

        val prompt = if (isBumble) {
            if (isEnglish) {
                if (shouldInvite) {
                    """
                    You are a witty, charming dating assistant for Bumble.
                    The conversation has progressed nicely (${context.rallyCount} rallies).
                    It is now the perfect natural moment to invite them to LINE to continue chatting and make plans.

                    Recent chat history:
                    $historyText

                    Latest message from match:
                    "${context.latestPartnerMessage}"

                    Instructions:
                    - Understand their latest message deeply and respond with genuine charm and chemistry.
                    - Option 4 MUST invite them to LINE using this exact link:
                    "$EN_LINE_INVITE"

                    Output strict JSON array only:
                    [
                      {"label": "😊 Warm + Follow-up", "text": "thoughtful response connecting to their topic"},
                      {"label": "✨ Charming & Playful", "text": "engaging banter"},
                      {"label": "😄 Witty Humorous", "text": "fun humorous take on what they said"},
                      {"label": "📲 Switch to LINE", "text": "$EN_LINE_INVITE"}
                    ]
                    """.trimIndent()
                } else {
                    """
                    You are a witty, charming dating assistant for Bumble.
                    Early conversation (${context.rallyCount} rallies). Do NOT suggest LINE yet.
                    Focus on strong chemistry, playful flirting, and deep interest in what they shared.

                    Recent chat history:
                    $historyText

                    Latest message from match:
                    "${context.latestPartnerMessage}"

                    Output strict JSON array only:
                    [
                      {"label": "😊 Deep Question", "text": "curious, genuine question on their topic"},
                      {"label": "✨ Playful Banter", "text": "light teasing or playful connection"},
                      {"label": "😄 Humorous", "text": "witty, funny reply"},
                      {"label": "☕ Shared Vibe", "text": "relatable shared experience"}
                    ]
                    """.trimIndent()
                }
            } else {
                if (shouldInvite) {
                    """
                    あなたはマッチングアプリ（Bumble）のプロの返信アシスタントです。
                    会話が ${context.rallyCount} 回以上ラリーし、十分に打ち解けた絶妙なタイミングです。
                    直前の相手の言葉に深く共感しつつ、自然にLINE交換を提案する返信を作ってください。

                    【これまでの会話の流れ】
                    $historyText

                    【相手の最新メッセージ】
                    "${context.latestPartnerMessage}"

                    【重要ルール】
                    1. 「いいね！最近どう？」のような浅い定型文は厳禁。相手の具体的な話題（趣味、仕事、休日の過ごし方、感情）をしっかり拾ってください。
                    2. 4つ目の選択肢は、必ず以下のLINE交換の誘導文（指定リンク付き）を自然に提案してください：
                    「$JA_LINE_INVITE」

                    出力は以下のJSON配列のみ：
                    [
                      {"label": "😊 共感＋深掘り", "text": "相手の話題に具体的に共感し、さらに話を広げる返信"},
                      {"label": "✨ フレンドリー", "text": "親しみやすく魅力的なリアクション"},
                      {"label": "😄 ユーモア", "text": "クスッと笑える楽しい返し"},
                      {"label": "📲 LINEへ移行", "text": "$JA_LINE_INVITE"}
                    ]
                    """.trimIndent()
                } else {
                    """
                    あなたはマッチングアプリ（Bumble）のプロの返信アシスタントです。
                    序盤のやり取り（${context.rallyCount} ラリー目）なので、LINE交換はまだ急がず、相手が「この人と話すと楽しい！」と感じる魅力的な会話を広げてください。

                    【これまでの会話の流れ】
                    $historyText

                    【相手の最新メッセージ】
                    "${context.latestPartnerMessage}"

                    【重要ルール】
                    1. 相手の最新メッセージに具体的に反応してください。おざなりな定型文は禁止です。
                    2. 自然な親近感とユーモアを大切にしてください。

                    出力は以下のJSON配列のみ：
                    [
                      {"label": "😊 共感＋質問", "text": "相手の発言に共感し、具体的な質問で返す"},
                      {"label": "✨ フレンドリー", "text": "自分のエピソードや感想を交えた親しみやすい返し"},
                      {"label": "😄 ユーモア", "text": "ちょっとクスッと笑える返し"},
                      {"label": "☕ 共通の話題", "text": "お互いの趣味や好きなことを深掘りする返し"}
                    ]
                    """.trimIndent()
                }
            }
        } else {
            // LINE context: heavily weight conversation flow and history
            """
            あなたはLINEのトーク相手に寄り添う、超優秀なAI返信アシスタントです。
            過去の会話の流れ（最大12通）と、相手の直前のメッセージを深く読み解き、文脈に完璧に合致した自然で人間味あふれる4つの返信案を作成してください。

            【絶対禁止事項】
            - 「いいね！最近どう？」「連絡ありがとう！」などの誰にでも言える浅い定型文は絶対に禁止です。

            【必須要件】
            - 相手が話している具体的な内容（場所、食べ物、趣味、予定、悩み、出来事、感情など）に直接触れてください。
            - 日本のLINEらしい、自然なテンポ感・適度な絵文字・親しみやすいトーンで作成してください。

            【これまでの会話の流れ（過去最大12通）】
            $historyText

            【直前の相手のメッセージ】
            "${context.latestPartnerMessage}"

            出力は以下のJSON配列のみ（Markdown記号は不要）：
            [
              {"label": "😊 共感＋深掘り", "text": "（相手の言ったことに具体的に共感し、話をさらに広げる返信）"},
              {"label": "✨ フレンドリー", "text": "（明るくポジティブに自分の感想やリアクションを伝える返信）"},
              {"label": "😄 ユーモア", "text": "（相手の言葉に楽しくツッコミを入れたり笑いを誘う返信）"},
              {"label": "☕ お誘い・提案", "text": "（会話の流れから自然に「今度行こう」「やってみよう」と提案する返信）"}
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

        // If both models failed
        GenerationResult(
            options = fallbackMockReplies(context, shouldInvite),
            engine = "定型文 (通信エラー)",
            errorDetails = lastError
        )
    }

    private fun fallbackMockReplies(context: ChatContext, shouldInvite: Boolean): List<SmartReplyOption> {
        val latest = context.latestPartnerMessage
        val full = (context.conversationHistory + latest).joinToString(" ")

        if (context.platform == "Bumble") {
            if (context.isEnglishPartner) {
                return if (shouldInvite) {
                    listOf(
                        SmartReplyOption("😊 Warm + Question", "That sounds awesome! What got you into that recently? 😊"),
                        SmartReplyOption("✨ Friendly", "Haha I totally relate! How is your week going so far?"),
                        SmartReplyOption("😄 Playful", "Okay, now you definitely have to tell me the full story behind that 😄"),
                        SmartReplyOption("📲 Switch to LINE", EN_LINE_INVITE)
                    )
                } else {
                    listOf(
                        SmartReplyOption("😊 Warm + Question", "That sounds awesome! What got you into that recently? 😊"),
                        SmartReplyOption("✨ Friendly", "Haha I totally relate! How is your week going so far?"),
                        SmartReplyOption("😄 Playful Tease", "Okay, now you definitely have to tell me the full story behind that 😄"),
                        SmartReplyOption("☕ Shared Topic", "By the way, what kind of things do you usually do on weekends? ✨")
                    )
                }
            } else {
                return if (shouldInvite) {
                    listOf(
                        SmartReplyOption("😊 共感＋質問", "すごくわかります！お話しやすくて嬉しいです✨ 普段はどんなことされてるんですか？"),
                        SmartReplyOption("✨ フレンドリー", "いいですね！メッセージやり取りできて嬉しいです😊"),
                        SmartReplyOption("😄 ユーモア", "センス良すぎます😂 もっと詳しく聞かせてください！"),
                        SmartReplyOption("📲 LINEへ移行", JA_LINE_INVITE)
                    )
                } else {
                    listOf(
                        SmartReplyOption("😊 共感＋質問", "すごくわかります！お話しやすくて嬉しいです✨ 普段はどんなことされてるんですか？"),
                        SmartReplyOption("✨ フレンドリー", "プロフの写真素敵ですね！休日は何して過ごすことが多いですか？😊"),
                        SmartReplyOption("😄 ユーモア", "センス良すぎます😂 もっと詳しく聞かせてください！"),
                        SmartReplyOption("☕ 共通の話題", "私もそれ好きです！おすすめのカフェとかありますか？✨")
                    )
                }
            }
        }

        // LINE: Smart contextual analysis based on keywords
        return when {
            latest.contains(Regex("[?？]")) -> listOf(
                SmartReplyOption("😊 丁寧な回答", "それね！まさにちょうど考えてたところだったよ😊"),
                SmartReplyOption("✨ 質問返し", "いい質問！ちなみにそっちはどう思う？✨"),
                SmartReplyOption("😄 ユーモア", "鋭いところ突いてくるね〜！笑😂"),
                SmartReplyOption("☕ 提案", "文章だと長くなりそうだから今度ゆっくり話そう！")
            )
            full.contains(Regex("ご飯|カフェ|美味しい|ラーメン|焼肉|飲み|ランチ|ディナー|居酒屋|スイーツ")) -> listOf(
                SmartReplyOption("😊 共感＋深掘り", "美味しそう！そのお店、前々から気になってたんだよね😋 おすすめメニューある？"),
                SmartReplyOption("✨ フレンドリー", "いいね！美味しいもの食べてる時が一番幸せ✨ 写真見てたらお腹空いてきた！"),
                SmartReplyOption("😄 ユーモア", "完全なる飯テロやめて〜！笑 今すぐ駆けつけたい😂"),
                SmartReplyOption("☕ お誘い・提案", "よかったら今度一緒に行かない？🍽️ 予定合わせよう！")
            )
            full.contains(Regex("映画|アニメ|ドラマ|ネトフリ|動画|YouTube|漫画|マンガ")) -> listOf(
                SmartReplyOption("😊 共感＋深掘り", "それ気になってた作品！一番面白かった見どころってどこ？🎬"),
                SmartReplyOption("✨ フレンドリー", "いいね！私も休日はよく動画観てまったりしてるよ〜😊"),
                SmartReplyOption("😄 ユーモア", "それ見始めたら完全に夜更かし確定するやつだね😂"),
                SmartReplyOption("☕ お誘い・提案", "今度おすすめ作品語り合おう✨ ぜひ教えて！")
            )
            full.contains(Regex("仕事|疲れた|残業|忙しい|眠い|大変")) -> listOf(
                SmartReplyOption("😊 ねぎらい＋共感", "今日もお疲れ様！大変だったね🍵 無理しないでゆっくり休んでね"),
                SmartReplyOption("✨ 励まし", "いつも本当にお疲れ様！週末まであと少し、応援してるよ💪"),
                SmartReplyOption("😄 ユーモア", "お疲れ様！甘いものか美味しいご飯で自分にご褒美あげて😂"),
                SmartReplyOption("☕ 気分転換", "落ち着いたら美味しいものでも食べてリフレッシュしに行こう☕")
            )
            full.contains(Regex("休み|週末|土曜|日曜|祝日|連休|予定")) -> listOf(
                SmartReplyOption("😊 休日トーク", "お休み満喫できてる？✨ 何か楽しい予定とかあるの？"),
                SmartReplyOption("✨ フレンドリー", "いいね！休日はリフレッシュするのが一番だよね😊"),
                SmartReplyOption("😄 ユーモア", "休みの日は時間が溶けるスピードが早すぎるよね😂"),
                SmartReplyOption("☕ お誘い・提案", "もし時間合いそうなら、今度ふらっとカフェでも行かない？☕")
            )
            else -> {
                val cleanTopic = if (latest.length > 20) latest.take(20) + "…" else latest
                listOf(
                    SmartReplyOption("😊 深い共感", "「$cleanTopic」ってことだよね！すごくわかる😊 それからどうなった？"),
                    SmartReplyOption("✨ フレンドリー", "連絡ありがとう！ちょうど話したいなと思ってたところ✨"),
                    SmartReplyOption("😄 ユーモア", "それ面白すぎる😂 さすがのセンスだね！笑"),
                    SmartReplyOption("☕ お誘い・提案", "またゆっくり話そう！近いうちに予定合わせようね☕")
                )
            }
        }
    }
}
