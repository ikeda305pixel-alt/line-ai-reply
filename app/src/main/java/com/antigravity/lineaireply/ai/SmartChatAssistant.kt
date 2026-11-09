package com.antigravity.lineaireply.ai

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

class SmartChatAssistant(
    private val apiKey: String,
    private val guard: FreeTierGuard
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        const val USER_LINE_LINK = "https://line.me/ti/p/TYxbDIk4sd"
        const val JA_LINE_INVITE = "予定立てやすいのでライン交換してください!\n$USER_LINE_LINK"
        const val EN_LINE_INVITE = "It's much easier to plan and stay in touch on LINE! Let's connect here: $USER_LINE_LINK"
    }

    suspend fun generateReplies(context: ChatContext): List<SmartReplyOption> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || !guard.canRequest()) {
            return@withContext fallbackMockReplies(context)
        }

        val historyText = context.conversationHistory.joinToString("\n") { "・$it" }
        val isBumble = context.platform == "Bumble"
        val isEnglish = context.isEnglishPartner

        val prompt = if (isBumble) {
            if (isEnglish) {
                """
                You are a smart dating assistant for Bumble.
                The partner's profile / messages indicate they speak English.
                Recent chat history:
                $historyText
                Latest message: "${context.latestPartnerMessage}"

                Generate 4 natural, attractive reply candidates.
                IMPORTANT: Option 4 MUST be a smooth invitation to switch to LINE using this exact link:
                "$EN_LINE_INVITE"

                Output strict JSON array only:
                [
                  {"label": "😊 Warm + Question", "text": "Reply 1"},
                  {"label": "✨ Friendly", "text": "Reply 2"},
                  {"label": "😄 Humorous", "text": "Reply 3"},
                  {"label": "📲 Switch to LINE", "text": "$EN_LINE_INVITE"}
                ]
                """.trimIndent()
            } else {
                """
                あなたはBumble（マッチングアプリ）の返信アシスタントです。
                相手は日本人・日本語話者です。
                直近の会話ログ:
                $historyText
                最新メッセージ: "${context.latestPartnerMessage}"

                会話の文脈に合わせた魅力的な4つの返信案を作成してください。
                【最重要】4つ目の選択肢は、必ず以下のLINE交換の誘導文（リンク付き）を自然に提案してください：
                「$JA_LINE_INVITE」

                出力は以下のJSON配列のみ：
                [
                  {"label": "😊 共感＋質問", "text": "返信文1"},
                  {"label": "✨ フレンドリー", "text": "返信文2"},
                  {"label": "😄 ユーモア", "text": "返信文3"},
                  {"label": "📲 LINEへ誘導", "text": "$JA_LINE_INVITE"}
                ]
                """.trimIndent()
            }
        } else {
            // LINE context: heavily weight conversation flow and history
            """
            あなたはLINEのトーク返信アシスタントです。
            これまでの会話の流れ・文脈を最重視して、自然で好感度の高い4つの返信案を作成してください。

            【会話ログの流れ】
            $historyText

            【最新のメッセージ】
            "${context.latestPartnerMessage}"

            出力は以下のJSON配列のみ：
            [
              {"label": "😊 共感＋質問", "text": "返信文1"},
              {"label": "✨ フレンドリー", "text": "返信文2"},
              {"label": "😄 ユーモア", "text": "返信文3"},
              {"label": "☕ お誘い・提案", "text": "返信文4"}
            ]
            """.trimIndent()
        }

        try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
            val body = JSONObject().apply {
                val parts = JSONArray().put(JSONObject().put("text", prompt))
                put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
            }

            val req = Request.Builder().url(endpoint).post(body.toString().toRequestBody(mediaType)).build()
            val resp = client.newCall(req).execute()

            if (!resp.isSuccessful) {
                return@withContext fallbackMockReplies(context)
            }

            guard.recordRequest()
            val jsonStr = resp.body?.string() ?: ""
            val root = JSONObject(jsonStr)
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
            if (list.isNotEmpty()) return@withContext list
        } catch (_: Exception) {}

        fallbackMockReplies(context)
    }

    private fun fallbackMockReplies(context: ChatContext): List<SmartReplyOption> {
        val full = (context.conversationHistory + context.latestPartnerMessage).joinToString(" ")

        if (context.platform == "Bumble") {
            if (context.isEnglishPartner) {
                return listOf(
                    SmartReplyOption("😊 Warm + Question", "That sounds awesome! What got you into that recently? 😊"),
                    SmartReplyOption("✨ Friendly", "Haha I totally relate! How is your week going so far?"),
                    SmartReplyOption("😄 Playful", "Okay, now you definitely have to tell me the full story behind that 😄"),
                    SmartReplyOption("📲 Switch to LINE", EN_LINE_INVITE)
                )
            } else {
                return listOf(
                    SmartReplyOption("😊 共感＋質問", "すごくわかります！お話しやすくて嬉しいです✨ 普段はどんなことされてるんですか？"),
                    SmartReplyOption("✨ フレンドリー", "いいですね！メッセージやり取りできて嬉しいです😊"),
                    SmartReplyOption("😄 ユーモア", "センス良すぎます😂 もっと詳しく聞かせてください！"),
                    SmartReplyOption("📲 LINEへ誘導", JA_LINE_INVITE)
                )
            }
        }

        // LINE flow
        return when {
            full.contains(Regex("ご飯|カフェ|美味しい|ラーメン|焼肉|飲み|ランチ|ディナー")) -> listOf(
                LineReplyOptionMock("😊 共感＋質問", "美味しそう！そのお店、普段よく行くの？😋"),
                LineReplyOptionMock("✨ フレンドリー", "いいね！美味しいもの食べてる時が一番幸せ✨"),
                LineReplyOptionMock("😄 ユーモア", "飯テロやめて〜！今すぐお腹空いてきた😂"),
                LineReplyOptionMock("☕ お誘い・提案", "よかったら今度一緒に行かない？🍽️")
            )
            full.contains(Regex("映画|アニメ|ドラマ|ネトフリ|動画")) -> listOf(
                LineReplyOptionMock("😊 共感＋質問", "それ気になってた！一番おすすめの見どころってどこ？🎬"),
                LineReplyOptionMock("✨ フレンドリー", "いいね！私も休日によく動画観るよ〜😊"),
                LineReplyOptionMock("😄 ユーモア", "それ見始めたら夜更かし確定するやつだ😂"),
                LineReplyOptionMock("☕ お誘い・提案", "今度おすすめ作品語り合おう✨")
            )
            else -> {
                val snippet = if (context.latestPartnerMessage.length > 15) "「${context.latestPartnerMessage.take(15)}…」" else "「${context.latestPartnerMessage}」"
                listOf(
                    LineReplyOptionMock("😊 共感＋質問", "$snippet、いいね！最近どう過ごしてた？😊"),
                    LineReplyOptionMock("✨ フレンドリー", "連絡ありがとう！すごく話しやすくて嬉しい✨"),
                    LineReplyOptionMock("😄 ユーモア", "それ面白いね😂 もっと詳しく聞かせて！"),
                    LineReplyOptionMock("☕ お誘い・提案", "よかったら今度ゆっくりお話ししよう☕")
                )
            }
        }
    }

    private fun LineReplyOptionMock(l: String, t: String) = SmartReplyOption(l, t)
}
