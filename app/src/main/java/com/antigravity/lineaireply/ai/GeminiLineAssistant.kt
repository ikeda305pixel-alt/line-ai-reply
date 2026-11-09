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

data class LineReplyOption(
    val label: String,
    val text: String
)

class GeminiLineAssistant(
    private val apiKey: String,
    private val guard: FreeTierGuard
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun generateReplies(history: List<String>, latestMessage: String): List<LineReplyOption> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || !guard.canRequest()) {
            return@withContext fallbackMockReplies(history, latestMessage)
        }

        val historyText = history.joinToString("\n") { "・$it" }
        val prompt = """
            あなたはLINEのトーク返信アシスタントです。
            以下の直近の会話ログの流れを踏まえ、ユーザーがそのまま選んで返信できる自然で好感度の高い4つの返信案を作成してください。

            【会話ログの流れ】
            $historyText

            【最新のメッセージ】
            $latestMessage

            【出力フォーマット】
            必ず以下のJSON配列のみを返してください（マークダウンや解説は一切不要）：
            [
              {"label": "😊 共感＋質問", "text": "返信文1"},
              {"label": "✨ フレンドリー", "text": "返信文2"},
              {"label": "😄 ユーモア", "text": "返信文3"},
              {"label": "☕ お誘い・提案", "text": "返信文4"}
            ]
        """.trimIndent()

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
                return@withContext fallbackMockReplies(history, latestMessage)
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
            val list = mutableListOf<LineReplyOption>()
            for (i in 0 until array.length().coerceAtMost(4)) {
                val item = array.getJSONObject(i)
                list.add(
                    LineReplyOption(
                        label = item.optString("label", "案 ${i + 1}"),
                        text = item.optString("text", "")
                    )
                )
            }
            if (list.isNotEmpty()) return@withContext list
        } catch (_: Exception) {}

        fallbackMockReplies(history, latestMessage)
    }

    private fun fallbackMockReplies(history: List<String>, latest: String): List<LineReplyOption> {
        val fullContext = (history + latest).joinToString(" ")

        return when {
            fullContext.contains(Regex("ご飯|カフェ|美味しい|ラーメン|焼肉|飲み|ランチ|ディナー")) -> listOf(
                LineReplyOption("😊 共感＋質問", "美味しそう！そのお店、普段よく行くの？😋"),
                LineReplyOption("✨ フレンドリー", "いいね！美味しいもの食べてる時が一番幸せ✨"),
                LineReplyOption("😄 ユーモア", "飯テロやめて〜！今すぐお腹空いてきた😂"),
                LineReplyOption("☕ お誘い・提案", "よかったら今度一緒に行かない？🍽️")
            )
            fullContext.contains(Regex("映画|アニメ|ドラマ|ネトフリ|動画")) -> listOf(
                LineReplyOption("😊 共感＋質問", "それ気になってた！一番おすすめの見どころってどこ？🎬"),
                LineReplyOption("✨ フレンドリー", "いいね！私も休日によく動画観るよ〜😊"),
                LineReplyOption("😄 ユーモア", "それ見始めたら夜更かし確定するやつだ😂"),
                LineReplyOption("☕ お誘い・提案", "今度おすすめ作品語り合おう✨")
            )
            fullContext.contains(Regex("休み|休日|週末|土日|予定")) -> listOf(
                LineReplyOption("😊 共感＋質問", "いい休日の過ごし方だね！普段はお出かけ派？インドア派？😊"),
                LineReplyOption("✨ フレンドリー", "リフレッシュできそうで素敵✨ 楽しんできてね！"),
                LineReplyOption("😄 ユーモア", "その充実っぷり、私の休日にも分けてほしい😂"),
                LineReplyOption("☕ お誘い・提案", "タイミング合えば、今週か来週あたりお茶でも行かない？☕")
            )
            else -> {
                val snippet = if (latest.length > 15) "「${latest.take(15)}…」" else "「$latest」"
                listOf(
                    LineReplyOption("😊 共感＋質問", "$snippet、いいね！最近どう過ごしてた？😊"),
                    LineReplyOption("✨ フレンドリー", "連絡ありがとう！すごく話しやすくて嬉しい✨"),
                    LineReplyOption("😄 ユーモア", "それ面白いね😂 もっと詳しく聞かせて！"),
                    LineReplyOption("☕ お誘い・提案", "よかったら今度ゆっくりお話ししよう☕")
                )
            }
        }
    }
}
