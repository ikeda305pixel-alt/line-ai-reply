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
            val turnsToInclude = allTurns.takeLast(120)
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

        val userSamplesText = userStyle.sampleQuotes.take(10).joinToString("\n") { "  ・$it" }
        val partnerResolvedName = context.partnerName.ifEmpty { partnerDossier.name.ifEmpty { "相手" } }

        return if (isBumble) {
            """
            あなたは【飾らない大人の魅力と自然なユーモアを持つユーザー本人（俺）の分身】です。
            チャラいホスト風の褒め殺しやキザな恋愛工学は【完全禁止】です。女性が安心して楽しく話せる「自然体の誠実さ ＋ 爽やかなウィット（俺流）」で最高の返信案を4つ作成してください。

            【ユーザー本人の拠点・生活圏（★最重要）】
            - ユーザーの生活拠点: 神奈川県（厚木市・茅ヶ崎市・湘南エリア在住）
            - 【絶対禁止】：東京（渋谷・新宿・恵比寿・六本木など）を勝手に提案することは【完全禁止】です！
            - 提案する場所・エリアは、相手のプロフィールや居住地を踏まえつつ、厚木、本厚木、茅ヶ崎、辻堂、藤沢、海老名、平塚、または中間の横浜など神奈川エリアを基準にしてください。
            - 相手のプロフィールに場所（居住地、勤務地、出身）が書かれている場合、それを最優先で考慮し、お互いが会いやすい神奈川・中間地点をスマートに提案してください。

            【相手（その人）の深層プロファイル】
            - 相手の名前/ニックネーム: $partnerResolvedName
            - 推定生息エリア・生活圏: ${partnerDossier.location}
            - 相手の性格タイプ分析: ${partnerDossier.personalityType}
            $profileSection

            【相手の口調・トーン適応：${if (context.isPartnerPolite) "敬語・丁寧語" else "フランク・タメ口"}】
            ${if (context.isPartnerPolite) """
            ★【最重要：敬語マッチング】
            相手は【敬語・丁寧語】で話しかけてきています。馴れ馴れしいタメ口は【厳禁】です！
            相手の丁寧さに合わせ、大人の落ち着きと爽やかさ、誠実さを持った好印象な敬語（丁寧語：「〜ですね！」「〜でしょうか？」「ぜひ！」等）で返信案を作成してください。
            """ else """
            ★相手は【フランク・タメ口】です。等身大で親しみやすい自然体のフランクな口調で返信してください。
            """}

            ${if (context.isPartnerPhotoRecent) """
            【相手が直前に写真を送ってきました（★写真リアクション必須）】
            相手が写真を送ってくれたことに対して、自然で好印象な褒め言葉や興味を示すリアクション（例: 『写真ありがとう！めっちゃ美味しそう！どこ？』『いい景色だね！旅行？』『可愛い！』など）を必ず交えて返信案を作成してください。
            """ else ""}

            【言語・国際的背景】
            - 検出傾向: ${context.languageDesc} (外国人/英語話者の可能性: ${if (isEnglishOrForeign) "高" else "低/日本人"})
            - 相手が英語話者の場合: チャーミングで自然な英語で作成してください。

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
               必ず相手のチャット「${context.latestPartnerMessage}」に対して回答してください。自分の発言への自己返信は厳禁です。
            ${if (isEnglishOrForeign) """
            1. 【言語設定：100%自然なネイティブ英語（★最重要・日本語厳禁）】
               相手は英語話者です。返信案4つはすべて【100%自然でチャーミングな日常会話の英語】で作成してください。日本語での回答は【絶対に禁止】です！
               教科書のような堅い直訳や、安っぽいナンパ（Pick-up lines）は厳禁。等身大で好印象、自然体のウィットと誠実さが伝わる英語にしてください。
            2. 【チャラさゼロ × 全部俺流】
               過剰な褒め殺しやキザな駆け引きは禁止。親しみやすいトーンを保ってください。
            3. 【4つの異なる英語バリエーション】
               - 案1 🎯【Direct Answer】: 自然体でド直球の好印象な即答。
               - 案2 ☕️【Connection & Question】: 相手の話題やプロフィールに共感しつつ自然に広げる逆質問。
               - 案3 😄【Witty Banter】: クスッと笑える爽やかなウィット・イジり。
               - 案4 ${if (shouldInvite) "📲【Switch to LINE】" else "🚀【Casual Plan】"}: ${if (shouldInvite) "以下のLINE移行メッセージを提示：\n$EN_LINE_INVITE" else "神奈川（湘南/横浜等）の自然で爽やかなカフェやご飯の提案。"}

            出力は以下のJSON配列のみ（Markdownコードブロック不要）：
            [
              {"label": "🎯 Direct Answer", "text": "(Charming friendly direct English response)"},
              {"label": "☕️ Connection & Question", "text": "(Engaging follow-up question in English)"},
              {"label": "😄 Witty Banter", "text": "(Playful witty remark in English)"},
              {"label": "${if (shouldInvite) "📲 Switch to LINE" else "🚀 Casual Plan"}", "text": "${if (shouldInvite) EN_LINE_INVITE else "(Casual fun proposal in English, Kanagawa/Shonan area)"}"}
            ]
            """.trimIndent() else """
            1. 【チャラさゼロ × トーン同期】
               安っぽい褒め言葉やホスト風の転がしは一切不要です。
               ${if (context.isPartnerPolite) "相手が敬語なので、こちらも礼儀正しく好印象な敬語（です・ます調）で返信してください。" else "ユーザー本人の文体サンプルに忠実な、等身大で親しみやすい口調にしてください。"}
            2. 【蓄積全ログの文脈理解＆神奈川ローカル配慮】
               蓄積された過去全ログを理解し、前に話した話題や好みを自然に踏まえてください。
               場所の話題では、厚木・茅ヶ崎・湘南・海老名・藤沢・横浜など神奈川県内を前提とし、東京の提案は禁止です。
            3. 【4つの異なる俺流バリエーション】
               - 案1 🎯【俺流・即答】: 自然体でド直球の好印象な即答（相手が敬語なら敬語、写真なら写真リアクション）。
               - 案2 ☕️【俺流・共感質問】: 相手の話題・プロフ（場所含む）に共感しつつ自然に広げる逆質問。
               - 案3 😄【俺流・ツッコミ】: ギャップや話題にクスッと笑えるウィットなイジり（敬語でも品のあるユーモア）。
               - 案4 ${if (shouldInvite) "📲【俺流・LINE交換】" else "🚀【俺流・爽やか提案】"}: ${if (shouldInvite) "以下のLINE移行文（リンク付き）を自然に提示：\n「$JA_LINE_INVITE」" else "相手の生活圏を踏まえた神奈川（厚木・海老名・茅ヶ崎・藤沢・横浜等）のサクッと気軽なお茶・ご飯の提案。"}

            出力は以下のJSON配列のみ（Markdownコードブロック不要）：
            [
              {"label": "🎯 俺流・即答", "text": "（自然体でド直球の好印象な即答）"},
              {"label": "☕️ 俺流・共感質問", "text": "（共感して話題を広げる逆質問）"},
              {"label": "😄 俺流・ツッコミ", "text": "（爽やかなイジり・笑い）"},
              {"label": "${if (shouldInvite) "📲 LINE交換" else "🚀 爽やか提案"}", "text": "${if (shouldInvite) JA_LINE_INVITE else "（神奈川エリアの気軽な提案）"}"}
            ]
            """.trimIndent()}
            """.trimIndent()
        } else {
            // LINE context: Friends, Family, Work, Everyday Life
            """
            あなたは【ユーザー本人（俺）そのもの】です。
            ここはLINEのトーク画面です。相手は【友達・家族・同僚・知り合い】などの日常の人間関係です。
            チャラい発言、思わせぶりな態度、ホスト風の転がし、キザな恋愛工学は【完全永久追放】です。
            友達や家族に送っても1ミリも不自然さのない、100%等身大の「俺流（タメ口、短文、テンポ、飾らない親近感）」で、人間が日常チャットで最も必要とする【4大基本パターン（YES系、NO系、質問調整系、ツッコミ共感系）】の返信案を作成してください。

            【ユーザー本人の拠点・生活圏】
            - ユーザーの生活拠点: 神奈川県（厚木市・茅ヶ崎市・湘南エリア在住）
            - 会う場所やご飯の提案をする際は、厚木、海老名、茅ヶ崎、藤沢、横浜など神奈川県内を基本とし、東京（渋谷等）を勝手に提案しないでください。

            【相手の名前/グループ】: $partnerResolvedName
            【相手の生活圏・特徴（もしあれば）】: ${partnerDossier.location}

            【相手の口調・トーン適応：${if (context.isPartnerPolite) "敬語・丁寧語" else "フランク・タメ口"}】
            ${if (context.isPartnerPolite) """
            ★【相手は敬語・丁寧語です】
            相手が敬語で話しかけてきているため、失礼のないよう好印象で親しみやすい丁寧語（「〜ですね！」「〜でしょうか？」「ありがとうございます！」等）で返信してください。
            """ else """
            ★【相手はフランク・タメ口です】
            いつもの俺流（タメ口・短文・テンポ感）で親しみやすく返信してください。
            """}

            ${if (context.isPartnerPhotoRecent) """
            【相手が直前に写真を送ってきました（★写真リアクション必須）】
            写真に対する自然な感想や褒め言葉、質問（『写真ありがとう！めっちゃ美味そう！どこ？』『いい写真だね！』など）を必ず交えて返信してください。
            """ else ""}

            【言語・国際的背景】
            - 検出傾向: ${context.languageDesc} (外国人/英語話者の可能性: ${if (isEnglishOrForeign) "高" else "低/日本人"})

            【ユーザー本人の回答スタイル（自分がどういう回答をするのかの精密プロファイル）】
            - 回答・口調の特徴: ${userStyle.summary}
            - 典型的な言い回し・文体サンプル（※このトーン＆マナーを4案すべてに100%適用せよ）:
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

            【LINE用・絶対厳守の返信生成ルール】
            0. 【最重要：自分の発言への自己返信・相槌の完全禁止】
               必ず相手のチャット「${context.latestPartnerMessage}」に対して回答してください。自分への自己返信は厳禁です。
            1. 【チャラさ・恋愛工学の完全排除】
               キザな口説きや不自然な褒め言葉は厳禁。家族や男友達に胸を張って送れる等身大の「俺」として返信してください。
            ${if (isEnglishOrForeign) """
            2. 【言語設定：100%自然な日常会話カジュアル英語（★最重要・日本語厳禁）】
               相手は英語話者です。返信案4つはすべて【100%自然な日常会話のカジュアル英語（タメ口、スラング、テンポの良い短文）】で作成してください。日本語での回答は【絶対に禁止】です！
               ユーザー本人の等身大・親しみやすさ・フレンドリーな雰囲気を保った、短く自然な英語にしてください。硬いビジネス英語や教科書英語は禁止です。
            3. 【4大基本パターンの網羅（英語）】
               - 案1 🎯【Direct YES / Count me in】:
                 承諾、合意、快諾（例: "Sounds awesome, count me in!", "Yeah absolutely! Let's do it 👍"）
               - 案2 🙅‍♂️【Alternative NO / Reschedule】:
                 角を立てずに断る、別日程提案（例: "Ah I'm tied up today, how about next week?", "Super slammed right now, I'll text you later tonight!"）
               - 案3 💬【Details / Question】:
                 時間・場所・詳細の質問（例: "What time were you thinking?", "Where should we meet?", "What kind of vibe is the spot?"）
               - 案4 😄【Humor / Reaction】:
                 クスッと笑えるツッコミ、共感、労い（例: "Haha that cracked me up 😂 You're wild", "Hang in there today! Don't work too hard 💪"）

            出力は以下のJSON配列のみ（Markdownコードブロック不要）：
            [
              {"label": "🎯 Direct YES", "text": "(Enthusiastic natural casual English answer)"},
              {"label": "🙅‍♂️ Alternative NO", "text": "(Natural polite decline or reschedule in English)"},
              {"label": "💬 Details / Question", "text": "(Time/place/details question in natural English)"},
              {"label": "😄 Humor / Reaction", "text": "(Warm casual banter or reaction in natural English)"}
            ]
            """.trimIndent() else """
            2. 【全部「俺流」のトーン＆マナー】
               4案すべてが「ユーザー本人が実際にLINEで送るリアルな言葉遣い（短文・語尾・ツッコミ・テンポ）」でなければなりません。優等生AIのような敬語や定型文は【完全禁止】です。
            3. 【4大基本パターンの網羅】
               - 案1 🎯【俺流・即答（YES / 了解）】:
                 承諾、合意、快諾、感謝など、相手の提案・誘い・質問に「YES」とド直球に答える返答。（例:「おけ！行こ！」「了解〜！」「助かる、ありがとう！」）
               - 案2 🙅‍♂️【俺流・代案（NO / 条件付き）】:
                 角を立てずに断る、別の日程や場所を提案する、または「後で連絡する」などの条件付き返答。（例:「その日先約あって厳しいわ！来週どう？」「今バタバタしてるから夜連絡するね！」）
               - 案3 💬【俺流・質問調整】:
                 話や予定を具体的に進めるための時間・場所・詳細の質問や日程調整。（例:「何時くらいになりそう？」「どこ集合にする？」「どんな店？」）
               - 案4 😄【俺流・ツッコミ共感】:
                 親しい間柄でのクスッと笑えるツッコミ、または素直な労い・共感。（例:「それ笑うんだけど😂 さすがだわ」「今日もお疲れ！無理すんなよ」）

            出力は以下のJSON配列のみ（Markdownコードブロック不要）：
            [
              {"label": "🎯 俺流・即答", "text": "（YES/了解の直球返答）"},
              {"label": "🙅‍♂️ 俺流・代案", "text": "（NO/条件付き/別日提案）"},
              {"label": "💬 俺流・質問調整", "text": "（時間・場所・詳細の質問）"},
              {"label": "😄 俺流・ツッコミ共感", "text": "（クスッと笑えるツッコミや労い）"}
            ]
            """.trimIndent()}
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
                        SmartReplyOption("🎯 俺流・即答", "あいてるよ！肉めっちゃ好き🥩 渋谷か恵比寿あたりなら行きやすいな"),
                        SmartReplyOption("☕️ 俺流・共感質問", "それめっちゃわかる！普段どんな店によく行くの？😊"),
                        SmartReplyOption("😄 俺流・ツッコミ", "行動力ありすぎて笑った😂 でもそういうノリ好きだわ笑"),
                        SmartReplyOption("📲 LINE交換", JA_LINE_INVITE)
                    )
                } else {
                    listOf(
                        SmartReplyOption("🎯 俺流・即答", "それめっちゃわかる！俺もちょうどそれ食べたかったところ"),
                        SmartReplyOption("☕️ 俺流・共感質問", "プロフ見て気になってた！いつ頃からハマってるの？✨"),
                        SmartReplyOption("😄 俺流・ツッコミ", "センス良すぎて笑う😂 もっと詳しく聞かせて！"),
                        SmartReplyOption("🚀 爽やか提案", "もしよかったら今度サクッと美味しいご飯行こ！予定合わせよ〜")
                    )
                }
            }
        }

        // LINE context (Friends, Family, Everyday)
        if (context.isEnglishPartner) {
            val q = latest.lowercase()
            return when {
                q.contains("time") || q.contains("when") || q.contains("what day") || q.contains("friday") || q.contains("tonight") || q.contains("weekend") -> listOf(
                    SmartReplyOption("🎯 Direct YES", "Sounds great! 7 PM works perfect for me 👍"),
                    SmartReplyOption("🙅‍♂️ Alternative NO", "Ah got plans then, how about next Tuesday?"),
                    SmartReplyOption("💬 Details / Question", "What time were you thinking? Where should we meet?"),
                    SmartReplyOption("😄 Humor / Reaction", "Haha I was literally just about to text you about that! Let's do it 🍻")
                )
                q.contains("where") || q.contains("place") || q.contains("restaurant") || q.contains("bar") || q.contains("spot") -> listOf(
                    SmartReplyOption("🎯 Direct YES", "Anywhere around Shibuya or Ebisu is super convenient for me!"),
                    SmartReplyOption("🙅‍♂️ Alternative NO", "That place might be packed, how about that quiet spot near the station?"),
                    SmartReplyOption("💬 Details / Question", "What kind of food are you in the mood for? 😋"),
                    SmartReplyOption("😄 Humor / Reaction", "As long as there's good food, count me in anywhere haha")
                )
                else -> listOf(
                    SmartReplyOption("🎯 Direct YES", "Sounds awesome! Count me in 👍"),
                    SmartReplyOption("🙅‍♂️ Alternative NO", "Ah tied up today, but super down for next week!"),
                    SmartReplyOption("💬 Details / Question", "Haha tell me more, what happened?"),
                    SmartReplyOption("😄 Humor / Reaction", "That made my day haha 😂 You're the best!")
                )
            }
        }

        return when {
            latest.contains(Regex("[?？]")) && latest.contains(Regex("時|いつ|日程|日|曜|何時")) -> listOf(
                SmartReplyOption("🎯 俺流・即答", "おけ！19時くらいはどう？仕事終わりちょうど良さそう！"),
                SmartReplyOption("🙅‍♂️ 俺流・代案", "その日ちょっと先約あって厳しいわ！来週の平日どう？"),
                SmartReplyOption("💬 俺流・質問調整", "何時くらいになりそう？そっちの都合いい時間に合わせるよ！"),
                SmartReplyOption("😄 俺流・ツッコミ共感", "早い時間から乾杯しちゃおっか笑 18時半とかいける？🍻")
            )
            latest.contains(Regex("[?？]")) && latest.contains(Regex("どこ|場所|駅|店|エリア")) -> listOf(
                SmartReplyOption("🎯 俺流・即答", "渋谷か恵比寿あたりが行きやすくて助かる！"),
                SmartReplyOption("🙅‍♂️ 俺流・代案", "そこ混んでそうなら、駅前の静かなとこにする？"),
                SmartReplyOption("💬 俺流・質問調整", "そっちの行きやすいエリアどこら辺？何系食べたい？😋"),
                SmartReplyOption("😄 俺流・ツッコミ共感", "美味い飯があるならどこへでも飛んでくわ笑")
            )
            else -> listOf(
                SmartReplyOption("🎯 俺流・即答", "おけ！了解〜！それでいこ👍"),
                SmartReplyOption("🙅‍♂️ 俺流・代案", "今ちょっと立て込んでるから後でゆっくりLINEするわ！"),
                SmartReplyOption("💬 俺流・質問調整", "それってどういうこと？もうちょい詳しく教えて！"),
                SmartReplyOption("😄 俺流・ツッコミ共感", "それ笑うんだけど😂 さすがやな！今日もお疲れ！")
            )
        }
    }
}
