package com.antigravity.lineaireply.plugin

import android.util.Log
import com.antigravity.lineaireply.ai.ChatContext
import com.antigravity.lineaireply.ai.MessageTurn
import com.antigravity.lineaireply.ai.PartnerDossier
import com.antigravity.lineaireply.ai.SmartReplyOption
import com.antigravity.lineaireply.ai.UserStyleProfile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persona Synthesizer Plugin.
 * Responsibilities:
 * - Assembles high-EQ AI prompts blending user style, partner dossier, and full accumulated conversation history.
 * - Extracts 4 distinct reply personas.
 * - Parses JSON responses with resilient error recovery.
 * - Generates high-EQ fallback mock replies when API is unreachable.
 */
class PersonaSynthesizerPlugin : IPersonaSynthesizerPlugin {

    companion object {
        const val TAG = "PersonaSynthesizer"
        const val USER_LINE_LINK = "https://line.me/ti/p/TYxbDIk4sd"
        const val JA_LINE_INVITE = "予定立てやすいしLINE交換しよー！\n$USER_LINE_LINK"
        const val EN_LINE_INVITE = "It's much easier to plan and stay in touch on LINE! Let's connect here: $USER_LINE_LINK"
    }

    override fun buildPrompt(
        context: ChatContext,
        accumulatedHistory: List<MessageTurn>,
        userStyle: UserStyleProfile,
        partnerDossier: PartnerDossier,
        forceLineInvite: Boolean
    ): String {
        val isBumble = context.platform == "Bumble"
        val shouldInvite = (context.shouldSuggestLineInvite || forceLineInvite)
        val isEnglishOrForeign = context.isEnglishPartner

        // Use accumulated history if present, fallback to on-screen turns
        val allTurns = if (accumulatedHistory.isNotEmpty()) {
            accumulatedHistory
        } else {
            context.allMessageTurns.ifEmpty { context.conversationHistory }
        }

        val historyText = if (allTurns.isNotEmpty()) {
            val turnsToInclude = allTurns.takeLast(80)
            turnsToInclude.joinToString("\n") { turn ->
                val spk = if (turn.isPartner) (context.partnerName.ifEmpty { "相手" }) else "自分"
                "[$spk]: ${turn.text}"
            }
        } else {
            "[相手]: ${context.latestPartnerMessage}"
        }

        val profileSection = if (context.profileSnippet.isNotBlank()) {
            """
            【相手のプロフィール・自己紹介・プロンプト情報】
            ${context.profileSnippet}
            """.trimIndent()
        } else ""

        val userSamplesText = userStyle.sampleQuotes.take(6).joinToString("\n") { "  ・$it" }

        val partnerResolvedName = context.partnerName.ifEmpty { partnerDossier.name.ifEmpty { "相手" } }

        return if (isBumble) {
            """
            あなたは【超一流の会話プロフェッショナル（心理学・恋愛工学・傾聴とツッコミの達人）】であり、同時に【ユーザー本人（俺）の分身】です。
            相手の性格や生活圏、めんどくささやわがままさを見極め、プロの高度なコミュ力で相手を心地よく転がしつつ、言葉遣いやテンポは【俺のテイスト】をブレンドした最高の返信案を4つ作成してください。

            【相手（その人）の深層プロファイル（人間観察・生息エリア・性格分析カルテ）】
            - 相手の名前/ニックネーム: $partnerResolvedName
            - 推定生息エリア・生活圏: ${partnerDossier.location} (例: 渋谷・恵比寿、中目黒、中央線沿線、横浜など)
            - 相手の性格タイプ分析: ${partnerDossier.personalityType} (例: わがまま、めんどくさい構ってちゃん、ノリ重視、警戒心強め、サバサバなど)
            - プロとしての攻略・転がし方針: ${partnerDossier.strategy} (相手の機嫌を損ねず、主導権を握りながら好感度を最大化する戦略)
            $profileSection

            【言語・国際的背景】
            - 検出傾向: ${context.languageDesc} (外国人/英語話者の可能性: ${if (isEnglishOrForeign) "高" else "低/日本人"})
            - 相手が英語話者の場合: チャーミングでウィットに富んだ自然な英語で作成してください。
            - 相手が外国人で日本語を使っている場合: 親しみやすく分かりやすい日本語（または英語を交えた自然なバイリンガルトーク）にしてください。

            【ユーザー本人の回答スタイル（自分がどういう回答をするのかの精密プロファイル）】
            - 回答・口調の特徴: ${userStyle.summary}
            - ユーザー本人の典型的な言い回し・文体サンプル:
            $userSamplesText

            【相手（$partnerResolvedName）との蓄積会話全ログ（★全件自動読込済・時系列：過去から現在へ）】
            $historyText

            ${if (context.lastMessageIsMe) """
            【状況認識：直前に自分自身が「${context.latestMyMessage}」と送信済み】
            - 相手からの直前のメッセージ（★回答ターゲット）: "${context.latestPartnerMessage}"
            - 直前のあなたのメッセージ（送信済み）: "${context.latestMyMessage}"
            - 【禁止】自分の発言「${context.latestMyMessage}」に対して自分で返信したり、自分に相槌を打つことは【絶対に禁止】です。相手の「${context.latestPartnerMessage}」への別案、または送信済みに続く自然な追加フォローを提案してください。
            """.trimIndent() else """
            【相手の最新メッセージ（★返信対象・左側チャット）】
            "${context.latestPartnerMessage}"

            ${if (context.latestMyMessage.isNotBlank()) "【直前のあなたのメッセージ（右側・すでに送信済み）】\n\"${context.latestMyMessage}\"" else ""}
            """.trimIndent()}

            【絶対厳守の返信生成ルール】
            0. 【最重要：自分の発言への自己返信・相槌の完全禁止】
               返信案は【相手のチャット（左側）】に対する返答です。右側にある「あなたのメッセージ」は自分がすでに送信済みの発言です。自分に対して返信したり、自分の言葉に相槌を打つような不自然な返信は【絶対に禁止】です。必ず相手の「${context.latestPartnerMessage}」に対して回答してください。
            1. 【自分がどういう回答をするのかの100%再現】
               上記の「ユーザー本人の回答スタイル」に基づき、ユーザー本人が実際に送るリアルな口調（語尾・テンポ・ツッコミのノリ）を完璧に再現してください。優等生AIのような敬語や定型文は【完全禁止】です。
            2. 【その人（相手）の性格に合わせた転がし】
               上記の「相手の深層プロファイル（性格・攻略方針）」に基づき、相手のわがまま・めんどくささ・ノリ・警戒心をプロのコミュ力で転がしてください。
            3. 【蓄積された過去全ログの文脈反映】
               上記に掲載されている「蓄積会話全ログ」を完全に理解し、過去に話した約束、食べ物の好み、行った場所、共有した笑いのノリを自然に踏まえた返信にしてください。
            4. 【プロフィール・生息エリアの自然な拾い上げ】
               相手のプロフィールや生息エリア（渋谷・恵比寿等）を自然に絡め、「ちゃんとプロフを見て興味を持ってくれている！」という安心感を与えてください。
            5. 【質問へのド直球回答】
               相手が質問している場合は、曖昧な相槌やごまかしは厳禁。具体的で明確な回答（時間、場所、料理ジャンル等）を必ず返してください。
            6. 【4つの異なる返信ペルソナ】
               - 案1 🎯【俺スタイル × ド直球即答】: ユーザー本人が言いそうなリアルな言葉遣いで、質問にズバッと答える。
               - 案2 🤝【コミュ力プロ × 傾聴・転がし】: 相手のわがまま・めんどくささを華麗にいなし、機嫌を取るプロの返し。
               - 案3 😄【俺流ツッコミ × 軽快ユーモア】: ユーザーのノリでクスッと笑わせる鋭いイジり・ツッコミ。
               - 案4 🚀【主導権掌握 × 次の提案（/ 📲 LINEへ移行）】:
                 ${if (shouldInvite) "絶妙なタイミングなので、自然な流れで以下のLINE移行文（リンク付き）を提示してください：\n" + (if (isEnglishOrForeign) "「$EN_LINE_INVITE」" else "「$JA_LINE_INVITE」") else "相手の生活圏を踏まえた具体的でスマートな提案。"}

            出力は以下のJSON配列のみ（Markdownコードブロック不要）：
            [
              {"label": "🎯 俺スタイル即答", "text": "（ユーザー本人の口調での直球返答）"},
              {"label": "🤝 プロの転がし・傾聴", "text": "（相手の性格を受け止めて機嫌を取る返し）"},
              {"label": "😄 俺流ツッコミ", "text": "（クスッと笑えるウィットなイジり）"},
              {"label": "${if (shouldInvite) "📲 LINEへ移行" else "🚀 主導権提案"}", "text": "${if (shouldInvite) (if (isEnglishOrForeign) EN_LINE_INVITE else JA_LINE_INVITE) else "（生活圏を踏まえたスマートな提案）"}"}
            ]
            """.trimIndent()
        } else {
            """
            あなたは【超一流の会話プロフェッショナル（心理学・恋愛工学・傾聴とツッコミの達人）】であり、同時に【ユーザー本人（俺）の分身】です。
            相手の性格や生活圏、めんどくささやわがままさを見極め、プロの高度なコミュ力で相手を心地よく転がしつつ、言葉遣いやテンポは【俺のテイスト】をブレンドした最高の返信案を4つ作成してください。

            【相手（その人）の深層プロファイル（人間観察・生息エリア・性格分析カルテ）】
            - 相手の名前/ニックネーム: $partnerResolvedName
            - 推定生息エリア・生活圏: ${partnerDossier.location} (例: 渋谷・恵比寿、中目黒、中央線沿線、横浜など)
            - 相手の性格タイプ分析: ${partnerDossier.personalityType} (例: わがまま、めんどくさい構ってちゃん、ノリ重視、警戒心強め、サバサバなど)
            - プロとしての攻略・転がし方針: ${partnerDossier.strategy} (相手の機嫌を損ねず、主導権を握りながら好感度を最大化する戦略)
            $profileSection

            【言語・文化的背景】
            - 検出傾向: ${context.languageDesc}
            - 相手が英語話者・英語メッセージの場合: 自然でフレンドリーな英語で返答してください。

            【ユーザー本人の回答スタイル（自分がどういう回答をするのかの精密プロファイル）】
            - 回答・口調の特徴: ${userStyle.summary}
            - 典型的な言い回し・文体サンプル:
            $userSamplesText

            【相手（$partnerResolvedName）との蓄積会話全ログ（★全件自動読込済・時系列：過去から現在へ）】
            $historyText

            ${if (context.lastMessageIsMe) """
            【状況認識：直前に自分自身が「${context.latestMyMessage}」と送信済み】
            - 相手からの直前のメッセージ（★回答ターゲット）: "${context.latestPartnerMessage}"
            - 直前のあなたのメッセージ（送信済み）: "${context.latestMyMessage}"
            - 【禁止】自分の発言「${context.latestMyMessage}」に対して自分で返信したり、自分に相槌を打つことは【絶対に禁止】です。相手の「${context.latestPartnerMessage}」への別案、または送信済みに続く自然な追加フォローを提案してください。
            """.trimIndent() else """
            【直前の相手のメッセージ（★返信対象・左側チャット）】
            "${context.latestPartnerMessage}"

            ${if (context.latestMyMessage.isNotBlank()) "【直前のあなたのメッセージ（右側・すでに送信済み）】\n\"${context.latestMyMessage}\"" else ""}
            """.trimIndent()}

            【絶対厳守の返信生成ルール】
            0. 【最重要：自分の発言への自己返信・相槌の完全禁止】
               あなたの返信は【相手のチャット（左側）】に対する返答でなければなりません。右側にある「あなたのメッセージ」は自分がすでに送信済みの発言です。自分に対して返信したり、自分の言葉に相槌を打つような不自然な返信は【絶対に禁止】です。必ず相手の「${context.latestPartnerMessage}」に対して回答してください。
            1. 【自分がどういう回答をするのかの100%再現】
               上記の「ユーザー本人の回答スタイル」に基づき、ユーザー本人が実際に送るリアルな口調（語尾・テンポ・ツッコミのノリ）を完璧に再現してください。優等生AIのような敬語や定型文は【完全禁止】です。
            2. 【その人（相手）の性格に合わせた転がし】
               上記の「相手の深層プロファイル（性格・攻略方針）」に基づき、相手のわがまま・めんどくささ・ノリ・警戒心をプロのコミュ力で転がしてください。
            3. 【蓄積された過去全ログの文脈反映】
               上記に掲載されている「蓄積会話全ログ」を完全に理解し、過去に話した約束、食べ物の好み、行った場所、共有した笑いのノリを自然に踏まえた返信にしてください。
            3. 【生活圏・エリアを活かした提案】
               相手の生息エリアや生活圏（渋谷、新宿、目黒、恵比寿など）が判明している場合、次のアクション提案（案4）でそのエリアに自然に触れてください。
            4. 【質問へのド直球回答】
               相手が質問している場合は、案1（🎯 即答）で絶対に曖昧にごまかさず、具体的で明確な回答を返してください。
            5. 【4つの異なる返信ペルソナ】
               - 案1 🎯【俺スタイル × ド直球即答】: ユーザー本人が言いそうなリアルな言葉遣いで、質問にズバッと答える。
               - 案2 🤝【コミュ力プロ × 傾聴・転がし】: 相手のわがまま・めんどくささを華麗にいなし、機嫌を取るプロの返し。
               - 案3 😄【俺流ツッコミ × 軽快ユーモア】: ユーザーのノリでクスッと笑わせる鋭いイジり・ツッコミ。
               - 案4 🚀【主導権掌握 × 次の提案】: 相手の生活圏を踏まえた具体的でスマートな提案。

            出力は以下のJSON配列のみ（Markdownコードブロック不要）：
            [
              {"label": "🎯 俺スタイル即答", "text": "（ユーザー本人の口調での直球返答）"},
              {"label": "🤝 プロの転がし・傾聴", "text": "（相手の性格を受け止めて機嫌を取る返し）"},
              {"label": "😄 俺流ツッコミ", "text": "（クスッと笑えるウィットなイジり）"},
              {"label": "🚀 主導権提案", "text": "（生活圏を踏まえたスマートな提案）"}
            ]
            """.trimIndent()
        }
    }

    override fun parseReplies(rawResponseText: String): List<SmartReplyOption> {
        var text = rawResponseText.trim()
        if (text.startsWith("```")) {
            val firstNewline = text.indexOf('\n')
            if (firstNewline != -1) {
                text = text.substring(firstNewline + 1)
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length - 3)
            }
            text = text.trim()
        }

        val list = mutableListOf<SmartReplyOption>()

        if (text.startsWith("{")) {
            try {
                val obj = JSONObject(text)
                val candidateKeys = listOf("options", "replies", "candidates", "answers", "data")
                var array: JSONArray? = null
                for (k in candidateKeys) {
                    if (obj.has(k)) {
                        array = obj.optJSONArray(k)
                        if (array != null) break
                    }
                }
                if (array != null) {
                    for (i in 0 until array.length().coerceAtMost(4)) {
                        val item = array.getJSONObject(i)
                        list.add(
                            SmartReplyOption(
                                label = item.optString("label", "案 ${i + 1}"),
                                text = item.optString("text", "")
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed parsing JSONObject wrapper: ${e.message}")
            }
        } else if (text.startsWith("[")) {
            try {
                val array = JSONArray(text)
                for (i in 0 until array.length().coerceAtMost(4)) {
                    val item = array.getJSONObject(i)
                    list.add(
                        SmartReplyOption(
                            label = item.optString("label", "案 ${i + 1}"),
                            text = item.optString("text", "")
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed parsing JSONArray: ${e.message}")
            }
        }

        return list
    }

    override fun createFallbackReplies(
        context: ChatContext,
        accumulatedHistory: List<MessageTurn>,
        userStyle: UserStyleProfile,
        forceLineInvite: Boolean
    ): List<SmartReplyOption> {
        val latest = context.latestPartnerMessage.trim()
        val shouldInvite = (context.shouldSuggestLineInvite || forceLineInvite)
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
                        SmartReplyOption("🎯 俺スタイル即答", "あいてるよ！渋谷か恵比寿あたりなら行きやすいな🥩"),
                        SmartReplyOption("🤝 プロの転がし", "いいじゃん！そういうこだわり持ってる人めっちゃ好き笑"),
                        SmartReplyOption("😄 俺流ツッコミ", "急にわがまま発動してきたな笑 でも嫌いじゃない😂"),
                        SmartReplyOption("📲 LINEへ移行", JA_LINE_INVITE)
                    )
                } else {
                    listOf(
                        SmartReplyOption("🎯 俺スタイル即答", "それめっちゃわかる！俺もちょうどそれ食べたかったところ"),
                        SmartReplyOption("🤝 プロの転がし", "プロフ見て気になってた！普段からよく行くの？😊"),
                        SmartReplyOption("😄 俺流ツッコミ", "センス良すぎて笑う😂 もっと詳しく聞かせて！"),
                        SmartReplyOption("🚀 主導権提案", "今度おすすめの店連れてってよ！予定合わせよ〜")
                    )
                }
            }
        }

        // LINE context
        if (context.isEnglishPartner) {
            return listOf(
                SmartReplyOption("🎯 Direct Answer", "Sounds awesome! Count me in 👍"),
                SmartReplyOption("🤝 Friendly Vibe", "Totally agree with you! How is everything going on your end? 😊"),
                SmartReplyOption("😄 Witty / Fun", "Haha that made my day! You always have the best stories 😂"),
                SmartReplyOption("🚀 Next Plan", "Let's definitely catch up soon! When are you free next? ✨")
            )
        }

        return when {
            latest.contains(Regex("[?？]")) && latest.contains(Regex("時|いつ|日程|日|曜|何時")) -> listOf(
                SmartReplyOption("🎯 俺スタイル即答", "19時くらいはどう？仕事終わりだからちょうど良さそう！"),
                SmartReplyOption("🤝 プロの転がし", "合わせるよ！そっちは何時くらいが一番都合いい？😊"),
                SmartReplyOption("😄 俺流ツッコミ", "早い時間から乾杯しちゃおっか笑 18時半とかいける？🍻"),
                SmartReplyOption("🚀 主導権提案", "金曜か土曜の夜ならいつでも空いてるよ！どっちがいい？")
            )
            latest.contains(Regex("[?？]")) && latest.contains(Regex("どこ|場所|駅|店|エリア")) -> listOf(
                SmartReplyOption("🎯 俺スタイル即答", "渋谷か恵比寿あたりが行きやすくて助かる！"),
                SmartReplyOption("🤝 プロの転がし", "そっちの行きやすいエリアに合わせるよ！どこら辺がいい？✨"),
                SmartReplyOption("😄 俺流ツッコミ", "美味しいご飯があるならどこへでも飛んでくわ笑😋"),
                SmartReplyOption("🚀 主導権提案", "気になってるお店いくつかあるからURL送るね！")
            )
            else -> listOf(
                SmartReplyOption("🎯 俺スタイル即答", "それめっちゃわかる！まさに同じこと思ってたところ"),
                SmartReplyOption("🤝 プロの転がし", "いいじゃん！聞いてるだけで楽しそう😊"),
                SmartReplyOption("😄 俺流ツッコミ", "それは笑うんだけど😂 さすがだわ！"),
                SmartReplyOption("🚀 主導権提案", "また詳しく聞かせて！近いうちに飯でも行こ✨")
            )
        }
    }
}
