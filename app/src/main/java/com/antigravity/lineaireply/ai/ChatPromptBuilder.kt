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
        val noteSection = if (!userPromptNote.isNullOrBlank()) "【補足指示】$userPromptNote\n" else ""
        val userStyleSection = formatUserStyleSection(userStyle)
        val partnerDossierSection = formatPartnerDossierSection(partnerDossier)
        val lineInviteRule = if (includeLineInvite) formatLineInviteRule(isVision = true) else ""

        return """
            あなたはLINE/Bumbleトーク画面・プロフ画像を即時解析する高EQコミュ力AIアシスタントです。
            
            【画面構造・返信対象（厳守）】
            - 上部・中央の過去ログは無視し、**画面最下部（入力欄直上）の相手の最新メッセージ**のみに返信。
            - 相手=左側白吹出（またはアイコン横）、自分=右側。最下部の相手メッセージを特定せよ。
            
            【言語判定（最重要・UI除外）】
            - 「マッチしました」「〜km先」「オンライン」「既読」等のシステムUI文言は言語判定から完全除外。
            - 相手の最新吹出・Bioが英語/外国人の場合：
              * 返信4案は全て【100%自然な日常ネイティブ英語】（日本語厳禁！）。
              * optionsのlabelも英語（"🎯 Direct Answer", "😄 Friendly Banter", "💬 Question", "☕ Casual Plan"）。
              * 提案エリアは神奈川限定（Yokohama, Shonan, Chigasaki, Ebina, Atsugi。東京禁止）。
            - 相手が日本語の場合：
              * 相手が敬語なら全案丁寧語（です・ます調）、タメ口なら等身大タメ口で同期。
              * 提案場所は神奈川県内限定（厚木、本厚木、海老名、茅ヶ崎、辻堂、藤沢、湘南、横浜。東京禁止）。
            
            【写真リアクション】相手の写真（料理/風景等）があれば自然に触れること。
            
            $userStyleSection
            $partnerDossierSection
            $lineInviteRule
            $noteSection
            
            【モード: ${mode.title}】
            ▼日本語時: ${getModeInstruction(mode, false)}
            ▼英語時: ${getModeInstruction(mode, true)}
            
            【出力フォーマット（テンポ良く簡潔に1〜2文、純粋JSONのみ出力）】
            {
              "targetMessage": "最下部の相手最新メッセージ本文",
              "options": [
                {"label": "ラベル1", "text": "簡潔な返信案1"},
                {"label": "ラベル2", "text": "簡潔な返信案2"},
                {"label": "ラベル3", "text": "簡潔な返信案3"},
                {"label": "ラベル4", "text": "簡潔な返信案4"}
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
        val noteSection = if (!userPromptNote.isNullOrBlank()) "【補足指示】$userPromptNote\n" else ""
        val userStyleSection = formatUserStyleSection(userStyle)
        val partnerDossierSection = formatPartnerDossierSection(partnerDossier)
        val lineInviteRule = if (includeLineInvite) formatLineInviteRule() else ""

        val languageRule = if (isEnglish) {
            "【言語設定: 100%日常ネイティブ英語（日本語厳禁）】\n相手は英語です（「$contextText」）。返信4案は全て自然なカジュアル英語（タメ口・短文・1〜2文）で作成。場所提案は神奈川限定（Yokohama, Shonan, Chigasaki, Ebina, Atsugi。東京禁止）。"
        } else {
            "【敬語同期・場所厳守】\n${if (isPolite) "相手は敬語です。全案好印象な丁寧語（です・ます調）で統一（タメ口厳禁）。" else "相手はタメ口です。等身大のフランクな口調で統一。"}\n場所提案は神奈川県内限定（厚木、本厚木、海老名、茅ヶ崎、辻堂、藤沢、湘南、横浜。東京禁止）。"
        }

        val jsonExample = if (isEnglish) {
            """[{"label":"🎯 Direct Answer","text":"Sounds awesome! Count me in 👍"},{"label":"😄 Friendly Banter","text":"Haha love it! Tell me more 😂"},{"label":"💬 Question","text":"What time works best for you?"},{"label":"☕ Casual Plan","text":"How about coffee near Shonan this weekend?"}]"""
        } else {
            """[{"label":"ラベル1","text":"返信文1"},{"label":"ラベル2","text":"返信文2"},{"label":"ラベル3","text":"返信文3"},{"label":"ラベル4","text":"返信文4"}]"""
        }

        return """
            あなたはLINE/Bumbleメッセージ返信を支援する高EQコミュ力AIアシスタントです。
            
            【入力コンテキスト（$contextSource）】
            「$contextText」
            
            $userStyleSection
            $partnerDossierSection
            $lineInviteRule
            $noteSection
            $modeInstruction
            
            $languageRule
            
            【出力フォーマット（テンポ良く簡潔に1〜2文、純粋JSON配列のみ出力）】
            $jsonExample
        """.trimIndent()
    }

    fun getModeInstruction(mode: AssistantMode, isEnglish: Boolean = false): String {
        return if (isEnglish) {
            when (mode) {
                AssistantMode.REPLY -> "4 natural English replies (1-2 sentences): 1 (🎯 Direct Answer), 2 (😄 Friendly Banter), 3 (💬 Engaging Question), 4 (😄 Casual & Honest)."
                AssistantMode.SCHEDULE -> "4 smooth meetup proposals in Kanagawa (Yokohama/Shonan/Chigasaki/Ebina/Atsugi, Never Tokyo): 1 (☕ Coffee/Lunch), 2 (🍻 Drinks/Dinner), 3 (🚗 Chill Drive), 4 (📆 Check Dates)."
                AssistantMode.TOPIC -> "4 fun conversation starters: 1 (☕ Weekend Vibes), 2 (🍜 Food & Cafe), 3 (✈️ Travel & Places), 4 (🎵 Hobbies & Netflix)."
                AssistantMode.MOOD -> "4 mood-based replies: 1 (🎭 Vibe Check), 2 (💡 Smooth Reply 1), 3 (💡 Smooth Reply 2), 4 (💡 Flirty Tease)."
            }
        } else {
            when (mode) {
                AssistantMode.REPLY -> "4つの異なる切り口の自然な返信案（1〜2文）: 案1(🎯即答/ストレート), 案2(😄共感/ノリ良く), 案3(💬質問/話題広げ), 案4(😄ざっくばらん/本音)。"
                AssistantMode.SCHEDULE -> "神奈川県内限定（厚木/海老名/茅ヶ崎/辻堂/湘南/横浜、東京禁止）でスマートな4案: 案1(📅ランチ/カフェ), 案2(🍻飲み/ディナー), 案3(🚗お出かけ/ドライブ), 案4(📆候補日確認)。"
                AssistantMode.TOPIC -> "会話が弾む自然な質問4案: 案1(☕休日の過ごし方), 案2(🍜グルメ/おすすめ), 案3(✈️旅行/お出かけ), 案4(🎵趣味/最近のハマり)。"
                AssistantMode.MOOD -> "文脈・感情分析による戦略返信4案: 案1(🎭ムード分析:好感度★1〜3と感情要約), 案2(💡戦略返信1), 案3(💡戦略返信2), 案4(💡戦略返信3)。"
            }
        }
    }

    private fun formatUserStyleSection(userStyle: UserStyleProfile?): String {
        if (userStyle == null) return ""
        val samples = userStyle.sampleQuotes.take(4).joinToString("\n") { "  ・$it" }
        return """
        【俺スタイル（ユーザー回答傾向）】
        - 特徴: ${userStyle.summary}
        ${if (samples.isNotBlank()) "- 文体サンプル:\n$samples" else ""}
        ※相手が英語の場合はこのフランクさを保ちつつ【100%ネイティブ英語】で出力（日本語厳禁）。
        """.trimIndent()
    }

    private fun formatPartnerDossierSection(partnerDossier: PartnerDossier?): String {
        if (partnerDossier == null || partnerDossier.name.isEmpty()) return ""
        return "【相手カルテ】エリア: ${partnerDossier.location} / 性格: ${partnerDossier.personalityType} / 戦略: ${partnerDossier.strategy}\n"
    }

    private fun formatLineInviteRule(isVision: Boolean = false): String {
        return if (isVision) {
            """
            【LINE交換誘導ルール（※最重要）】
            - ※画面がLINEのトーク画面の場合はすでにLINE上であるため、LINE交換リンクは【絶対に出力しないこと】！
            - 画面がBumble等のマッチングアプリで、かつ日程調整や会話が弾んで自然な文脈の場合のみ、第4案として以下のLINE移行リンクを含めてよい：
              (JA: 「$JA_LINE_INVITE」 / EN: "$EN_LINE_INVITE")
            """.trimIndent() + "\n"
        } else {
            "【LINE誘導（マッチングアプリ等で自然な文脈のみ）】LINE交換リンクを含める: (JA: 「$JA_LINE_INVITE」 / EN: \"$EN_LINE_INVITE\")\n"
        }
    }
}
