package com.antigravity.lineaireply.ai

/**
 * SRP & OCP compliant Prompt Builder for LINE and Bumble Smart Assistant.
 * Separates prompt generation, language detection, and context injection from assistant networking.
 */
object ChatPromptBuilder {

    const val USER_LINE_LINK = "https://line.me/ti/p/TYxbDIk4sd"
    const val JA_LINE_INVITE = "予定立てやすいしLINE交換しよー！\n$USER_LINE_LINK"
    const val EN_LINE_INVITE = "It's much easier to plan and stay in touch on LINE! Let's connect here: $USER_LINE_LINK"

    private val SYSTEM_NOISE_REGEX = Regex(
        """(マッチしました|matched with|you matched|最初のメッセージ|first message|返信まであと|your move|your turn|時間以内に|質問ゲーム|question game|会話を始め|会話をスタート|メッセージ|プロフィール|km先|〜km|\d+\s*km\s*away|オンライン|既読|未読)""",
        RegexOption.IGNORE_CASE
    )

    private val ENGLISH_GREETINGS = setOf(
        "hi", "hey", "yo", "hello", "ok", "okay", "no", "yes", "gm", "gn", "sup", "howdy", "bye", "k"
    )

    private val POLITE_REGEX = Regex(
        """(です|でした|ました|でしょうか|ですね|ですよ|ですか|はじめまして|よろしくお願い|ありがとうござい|いただけ|頂け|ご都合|いかが|存じ|恐れ入|致し|([ぁ-ん一-龯]ます))"""
    )

    /**
     * Determines if the text indicates an English speaker or foreign partner.
     * System UI phrases ("マッチしました", "メッセージ", "〜km先", etc.) are stripped before evaluation.
     * Supports short 1-4 character English greetings ("Hi", "Yo", "Ok", "No", "Yes", "GM", "GN").
     */
    fun isEnglishOrForeign(text: String): Boolean {
        var clean = text.replace(Regex("https?://\\S+"), " ").trim()
        if (clean.isEmpty()) return false

        // Strip screen system UI noise before evaluating language
        clean = clean.replace(SYSTEM_NOISE_REGEX, " ").trim()
        if (clean.isEmpty()) return false

        val latinCount = clean.count { it in 'a'..'z' || it in 'A'..'Z' }
        val japaneseCount = clean.count { it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9faf' }

        val words = clean.lowercase().split(Regex("[^a-zA-Z0-9]+")).filter { it.isNotBlank() }
        val hasGreeting = words.any { it in ENGLISH_GREETINGS }

        val hasForeignKeywords = Regex(
            "from\\s+[a-z]+|lives?\\s+in|expat|native|english|speak|foreigner|travel|study abroad|where are you|what do you|how about|sounds good|see you",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(clean)

        // 1. If 0 Japanese characters:
        // Any English text with latinCount >= 2 or greeting word (or single letter greeting)
        if (japaneseCount == 0) {
            return latinCount >= 2 || hasGreeting || (latinCount >= 1 && clean.length <= 4)
        }

        // 2. English greeting with minor Japanese
        if (hasGreeting && latinCount >= japaneseCount) {
            return true
        }

        // 3. Substantial English ratio
        if (latinCount > 10 && (japaneseCount == 0 || latinCount > japaneseCount * 2)) {
            return true
        }

        // 4. Foreign keywords with English context
        if (latinCount > 5 && hasForeignKeywords) {
            return true
        }

        return false
    }

    fun detectPolite(text: String): Boolean {
        return text.isNotBlank() && POLITE_REGEX.containsMatchIn(text)
    }

    /**
     * Builds Gemini Vision multimodal prompt.
     * Avoids hardcoding isEnglish=false, providing bidirectional instructions for both Japanese and English.
     * Expressly instructs the model to ignore system UI text and follow partner bubble/bio text.
     */
    fun buildPromptForVision(
        mode: AssistantMode,
        userPromptNote: String?,
        userStyle: UserStyleProfile? = null,
        partnerDossier: PartnerDossier? = null,
        includeLineInvite: Boolean = true
    ): String {
        val noteSection = if (!userPromptNote.isNullOrBlank()) {
            "【ユーザーからの補足指示・メモ】: 「$userPromptNote」\n"
        } else ""

        val userStyleSection = formatUserStyleSection(userStyle)
        val partnerDossierSection = formatPartnerDossierSection(partnerDossier)
        val lineInviteRule = if (includeLineInvite) formatLineInviteRule() else ""

        return """
            あなたは添付されたLINEやBumble等のトーク画面・プロフィールのスクリーンショット画像を直接読み取って支援する最高峰のAIコミュ力アシスタントです。

            【LINE / Bumble トーク画面の構造と最新メッセージの特定（最重要ルール）】
            画面は上から下へと時系列順に並んでいます：
            1. 【過去メッセージ（上部・中央）は絶対に無視】:
               画面の上部や中央にある吹き出しはすべて「過去のやり取り」です。これらに返信すると会話が噛み合わなくなります。絶対に画面上部・中央のメッセージに返信しないでください！
            2. 【最下部の最新メッセージに返信】:
               返信すべき対象は、**画面の一番下（最下部・文字入力欄のすぐ上）にある相手の最新メッセージ**です。
            3. 【相手の吹き出しと自分の吹き出しの識別】:
               - 【相手（パートナー）のメッセージ】: 通常は左側の白い吹き出し（または相手のアイコン横）。
               - 【自分（ユーザー）のメッセージ】: 通常は右側の緑色・黄色の吹き出し。
               ※必ず**画面の最下部にある「相手の最新メッセージ」を返信対象**として特定してください。

            【★最重要・言語判定ルール：相手の吹き出し・Bio本文の言語に100%従う】
            - 画面内の「マッチしました」「メッセージ」「〜km先」「オンライン」「既読」「未読」等のシステムUI文言は言語判定から完全に除外してください。
            - 相手の最新吹き出しメッセージ、または相手のプロフィールBio本文の言語のみを判定基準にしてください。
            - 相手が英語話者・外国人の場合（最新メッセージが英語、または相手が外国人）：
              * 返信案4つはすべて【100%自然でチャーミングな日常会話のネイティブ英語（タメ口・短文・スラング・テンポの良い会話）】で作成してください。
              * 日本語での返信案の出力は【絶対に禁止】です！
              * optionsの"label"も英語（"🎯 Direct Answer", "😄 Friendly Banter", "💬 Question", "☕ Casual Plan"）にしてください。
              * 会う場所・日程を提案する場合も神奈川エリア（Yokohama, Shonan, Chigasaki, Ebina, Atsugi）を自然な英語で提案してください（東京禁止）。
            - 相手が日本語話者の場合：
              * 相手が敬語（です・ます調）なら、全返信案を好印象な丁寧語（です・ます調）で統一してください。
              * 相手がタメ口なら、等身大で自然なタメ口で返してください。
              * 会う場所を提案する場合は神奈川県内（厚木、本厚木、海老名、茅ヶ崎、辻堂、藤沢、湘南、横浜）を提案してください（東京禁止）。

            【写真へのリアクション】
            相手が写真を送信している場合は、その写真の内容（料理、風景、ペットなど）に自然に触れてください。

            $userStyleSection
            $partnerDossierSection
            $lineInviteRule
            $noteSection

            【実行モード指示 / Mode Instruction: ${mode.title}】
            ▼相手が日本語の場合（Japanese Partner）:
            ${getModeInstruction(mode, isEnglish = false)}

            ▼相手が英語・外国人の場合（English / Foreign Partner）:
            ${getModeInstruction(mode, isEnglish = true)}

            【出力フォーマット（必ず以下のJSONオブジェクト形式のみを出力）】
            Markdownコードブロックや余計な解説は一切出力せず、必ず以下のJSONオブジェクト形式のみを出力してください：
            {
              "targetMessage": "画面最下部で認識した相手の最新メッセージ本文（例: 「今週の金曜空いてる？」 または \"What are you up to tonight?\"）",
              "options": [
                {"label": "ラベル1", "text": "返信案1（※相手が英語の場合は100%自然な英語）"},
                {"label": "ラベル2", "text": "返信案2（※相手が英語の場合は100%自然な英語）"},
                {"label": "ラベル3", "text": "返信案3（※相手が英語の場合は100%自然な英語）"},
                {"label": "ラベル4", "text": "返信案4（※相手が英語の場合は100%自然な英語）"}
              ]
            }
        """.trimIndent()
    }

    /**
     * Builds text-based prompt (for copied clipboard text or user memos).
     */
    fun buildPromptForText(
        contextText: String,
        contextSource: String,
        isPolite: Boolean,
        mode: AssistantMode,
        userPromptNote: String?,
        userStyle: UserStyleProfile? = null,
        partnerDossier: PartnerDossier? = null,
        includeLineInvite: Boolean = true
    ): String {
        val isEnglish = isEnglishOrForeign(contextText)
        val modeInstruction = getModeInstruction(mode, isEnglish)
        val noteSection = if (!userPromptNote.isNullOrBlank()) {
            "【ユーザーからの補足指示・メモ】: 「$userPromptNote」\n"
        } else ""

        val userStyleSection = formatUserStyleSection(userStyle)
        val partnerDossierSection = formatPartnerDossierSection(partnerDossier)
        val lineInviteRule = if (includeLineInvite) formatLineInviteRule() else ""

        val languageRule = if (isEnglish) {
            """
            【★最重要・言語設定：100%自然なネイティブ英語（日本語厳禁！）】
            相手のメッセージは英語です（「$contextText」）。
            返信案4つはすべて【100%自然でチャーミングな日常会話のカジュアル英語（タメ口・短文・スラング・テンポの良い会話）】で作成してください。
            日本語での返信案は【絶対に禁止】です！
            教科書のような堅い直訳やビジネス英語は厳禁。等身大で親しみやすい、ネイティブが普段使う自然な英語にしてください。
            （例: "Sounds awesome!", "Haha tell me more!", "What time works best for you?", "Let's grab a coffee near Shonan!"）
            場所を提案する場合は神奈川エリア（Yokohama, Shonan, Chigasaki, Ebina, Atsugi）を自然な英語で提案してください。
            """.trimIndent()
        } else {
            """
            【敬語・トーンの完全同期】
            ${if (isPolite) "相手は【敬語・丁寧語】（です・ます調）で話しています。必ずすべての返信案を好印象で親しみやすい丁寧語（です・ます調）で統一してください。タメ口は厳禁です！" else "相手は【フランク・タメ口】です。等身大で自然体のフランクな口調で作成してください。"}

            【デート・待ち合わせ場所の厳守】
            会う場所や店を提案する場合は、絶対に東京（渋谷・新宿・六本木等）を出さず、必ず神奈川県内（厚木、本厚木、海老名、茅ヶ崎、辻堂、藤沢、湘南、横浜）のエリアを提案してください。
            """.trimIndent()
        }

        val jsonExample = if (isEnglish) {
            """
            [
              {"label": "🎯 Direct Answer", "text": "Sounds great! Count me in 👍"},
              {"label": "😄 Friendly Banter", "text": "Haha that made my day! Tell me more 😂"},
              {"label": "💬 Question", "text": "What time were you thinking?"},
              {"label": "☕ Casual Plan", "text": "How about grabbing coffee near Shonan beach this weekend?"}
            ]
            """.trimIndent()
        } else {
            """
            [
              {"label": "ラベル1", "text": "返信文1"},
              {"label": "ラベル2", "text": "返信文2"},
              {"label": "ラベル3", "text": "返信文3"},
              {"label": "ラベル4", "text": "返信文4"}
            ]
            """.trimIndent()
        }

        return """
            あなたはLINEやBumble等のメッセージ返信を支援する最高峰のAIコミュ力アシスタントです。

            【入力コンテキスト（$contextSource）】
            「$contextText」

            $userStyleSection
            $partnerDossierSection
            $lineInviteRule
            $noteSection
            $modeInstruction

            【最重要ルール】
            $languageRule

            【出力フォーマット】
            Markdownコードブロックや解説は一切出力せず、必ず以下のJSON配列形式のみを出力してください：
            $jsonExample
        """.trimIndent()
    }

    fun getModeInstruction(mode: AssistantMode, isEnglish: Boolean = false): String {
        return if (isEnglish) {
            when (mode) {
                AssistantMode.REPLY -> """
                    【Mode: 💬 Reply Suggestions (English)】
                    Generate 4 natural, charming English replies from different angles:
                    - Option 1 (🎯 Direct Answer): Straightforward, warm, and positive response
                    - Option 2 (😄 Friendly Banter): Witty, playful, and fun banter
                    - Option 3 (💬 Engaging Question): Natural follow-up question to keep conversation lively
                    - Option 4 (😄 Casual & Honest): Relaxed, down-to-earth authentic tease or reaction
                """.trimIndent()

                AssistantMode.SCHEDULE -> """
                    【Mode: 📅 Schedule & Meetup (English)】
                    Generate 4 smooth English proposals to meet up (Coffee, Drinks, or Casual Plan).
                    【Location Rule】Always suggest locations in Kanagawa area (Yokohama, Shonan, Chigasaki, Ebina, Atsugi). Never Tokyo.
                    - Option 1 (☕ Coffee/Lunch): Relaxed daytime cafe or lunch invite
                    - Option 2 (🍻 Drinks/Dinner): Fun evening dinner or drinks invite
                    - Option 3 (🚗 Chill Drive): Relaxed drive or seaside visit to Shonan or Yokohama
                    - Option 4 (📆 Check Availability): Asking what day or weekend works best
                """.trimIndent()

                AssistantMode.TOPIC -> """
                    【Mode: 💡 Topic & Conversation Starter (English)】
                    Generate 4 engaging English questions to spark a fun conversation:
                    - Option 1 (☕ Weekend Vibes): What do you usually do on your days off?
                    - Option 2 (🍜 Food & Cafe): Favorite restaurants or delicious food recommendations
                    - Option 3 (✈️ Travel & Places): Favorite trips, bucket-list places, or exploring
                    - Option 4 (🎵 Hobbies & Netflix): Shows, music, or current obsessions
                """.trimIndent()

                AssistantMode.MOOD -> """
                    【Mode: 🎭 Mood Analysis & Strategic Reply (English)】
                    Analyze her tone, energy, and vibe, and generate 4 strategic English options:
                    - Option 1 (🎭 Vibe Check): Quick evaluation of her mood and interest level
                    - Option 2 (💡 Smooth Reply 1): Best engaging response matching her energy
                    - Option 3 (💡 Smooth Reply 2): Playful response taking the conversational lead
                    - Option 4 (💡 Flirty Tease): Charming teasing remark to build chemistry
                """.trimIndent()
            }
        } else {
            when (mode) {
                AssistantMode.REPLY -> """
                    【実行モード: 💬 返信提案】
                    相手のメッセージに対して、自然で好印象な4つの異なる切り口の返信案を作成してください。
                    （※相手が英語話者の場合は、必ず英語で作成してください）
                    - 案1 (🎯 即答/ストレート): 相手の発言への素直で気持ちのいい回答
                    - 案2 (😄 共感/ノリ良く): 相手の気持ちや話に強く共感する返し
                    - 案3 (💬 質問/話題広げ): 会話が弾む自然な質問を添えた返し
                    - 案4 (😄 ざっくばらん/本音): 気取らない等身大の本音やぶっちゃけトーク、飾らない親近感を添えたざっくばらんな返し
                """.trimIndent()

                AssistantMode.SCHEDULE -> """
                    【実行モード: 📅 日程調整・デートお誘い】
                    相手と会う約束・食事・お茶・ドライブの予定をスマートに提案する4つの返信案を作成してください。
                    【絶対ルール】場所は必ず神奈川県内（厚木、本厚木、海老名、茅ヶ崎、辻堂、藤沢、湘南、横浜）を提案してください。東京（渋谷、新宿等）は【完全禁止】です！
                    （※相手が英語話者の場合は、必ず英語で提案してください）
                    - 案1 (📅 ランチ/カフェ): 昼間のカフェやお茶の気軽なお誘い
                    - 案2 (🍻 飲み/ディナー): 夜の美味しいご飯や飲みのお誘い
                    - 案3 (🚗 お出かけ/ドライブ): 湘南や横浜などへのお出かけ提案
                    - 案4 (📆 候補日確認): 相手の空いてる曜日や週末の都合を伺う返信
                """.trimIndent()

                AssistantMode.TOPIC -> """
                    【実行モード: 💡 話題提案・トーク展開】
                    会話が途切れないよう、相手が楽しく答えられる自然な質問や話題を4つ作成してください。
                    （※相手が英語話者の場合は、必ず英語で作成してください）
                    - 案1 (☕ 休日の過ごし方): 週末の予定や休日の過ごし方に関する話題
                    - 案2 (🍜 グルメ/おすすめ): 好きなお店や美味しかったものに関する話題
                    - 案3 (✈️ 旅行/お出かけ): 好きな場所や行ってみたい場所に関する話題
                    - 案4 (🎵 趣味/最近のハマり): 最近気になっていることやマイブームの話題
                """.trimIndent()

                AssistantMode.MOOD -> """
                    【実行モード: 🎭 ムード分析＆戦略返信】
                    会話の文脈から相手の感情・温度感・心理状態を分析し、最適な戦略的返信を作成してください。
                    （※相手が英語話者の場合は、必ず英語で作成してください）
                    - 案1 (🎭 ムード分析): 相手の好感度評価（★1〜3つ）、感情温度、心理的狙いの要約
                    - 案2 (💡 戦略返信1): そのムードに最も適したベストな返信案
                    - 案3 (💡 戦略返信2): 会話の主導権を握るスマートな返し
                    - 案4 (💡 戦略返信3): 相手の関心をさらに惹きつける返し
                """.trimIndent()
            }
        }
    }

    private fun formatUserStyleSection(userStyle: UserStyleProfile?): String {
        if (userStyle == null) return ""
        val samples = userStyle.sampleQuotes.take(6).joinToString("\n") { "  ・$it" }
        return """
        【ユーザー本人の回答スタイル（俺スタイル）】
        - 回答・口調の特徴: ${userStyle.summary}
        ${if (samples.isNotBlank()) "- ユーザー本人の典型的な言い回し・文体サンプル:\n$samples" else ""}
        ※重要：相手が英語話者の場合は、このスタイルの自然なフランクさやテンポ感を踏まえつつ、出力は必ず【100%自然なネイティブ英語】を維持してください（日本語厳禁）。
        """.trimIndent()
    }

    private fun formatPartnerDossierSection(partnerDossier: PartnerDossier?): String {
        if (partnerDossier == null || partnerDossier.name.isEmpty()) return ""
        return """
        【相手のプロファイル（相手カルテ）】
        - 推定生活圏: ${partnerDossier.location}
        - 性格タイプ分析: ${partnerDossier.personalityType}
        - コミュニケーション戦略: ${partnerDossier.strategy}
        """.trimIndent()
    }

    private fun formatLineInviteRule(): String {
        return """
        【LINE誘導オプション（Bumble等のマッチングアプリ・日程調整）】
        マッチングアプリで会話が弾んでいる場合や、日程調整・第4案において、自然にLINEへ移行できるよう以下のLINEリンクを含めた提案を適宜行ってください：
        - 日本語の場合: 「$JA_LINE_INVITE」
        - 英語の場合: "$EN_LINE_INVITE"
        """.trimIndent()
    }
}
