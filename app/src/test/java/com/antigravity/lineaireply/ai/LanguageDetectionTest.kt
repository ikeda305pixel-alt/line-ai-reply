package com.antigravity.lineaireply.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Language and English speaker detection logic in ChatPromptBuilder.
 * Validates:
 * - 1-4 char short greetings ("Hi", "Yo", "Ok", "No", "Yes", "GM", "GN")
 * - System UI noise immunity ("マッチしました Hi!")
 * - Natural English conversational questions & expressions
 * - Japanese conversational texts
 */
class LanguageDetectionTest {

    @Test
    fun testShortEnglishGreetings() {
        assertTrue("Hi should be detected as English", ChatPromptBuilder.isEnglishOrForeign("Hi"))
        assertTrue("Hey should be detected as English", ChatPromptBuilder.isEnglishOrForeign("Hey"))
        assertTrue("Yo! should be detected as English", ChatPromptBuilder.isEnglishOrForeign("Yo!"))
        assertTrue("Ok should be detected as English", ChatPromptBuilder.isEnglishOrForeign("Ok"))
        assertTrue("Yes should be detected as English", ChatPromptBuilder.isEnglishOrForeign("Yes"))
        assertTrue("No should be detected as English", ChatPromptBuilder.isEnglishOrForeign("No"))
        assertTrue("GM should be detected as English", ChatPromptBuilder.isEnglishOrForeign("GM"))
        assertTrue("GN should be detected as English", ChatPromptBuilder.isEnglishOrForeign("GN"))
        assertTrue("Hello should be detected as English", ChatPromptBuilder.isEnglishOrForeign("Hello"))
        assertTrue("Sup should be detected as English", ChatPromptBuilder.isEnglishOrForeign("Sup"))
    }

    @Test
    fun testConversationalEnglish() {
        assertTrue("Hey how are you? should be English", ChatPromptBuilder.isEnglishOrForeign("Hey how are you?"))
        assertTrue("Wanna get drinks tonight?", ChatPromptBuilder.isEnglishOrForeign("Wanna get drinks tonight?"))
        assertTrue("Where are you from?", ChatPromptBuilder.isEnglishOrForeign("Where are you from?"))
        assertTrue("Sounds good! See you soon", ChatPromptBuilder.isEnglishOrForeign("Sounds good! See you soon"))
        assertTrue("What are you up to this weekend?", ChatPromptBuilder.isEnglishOrForeign("What are you up to this weekend?"))
    }

    @Test
    fun testSystemUiNoiseRemoval() {
        // Bumble UI header text should be excluded from skewing language detection
        assertTrue("マッチしました Hi! should be English because match banner is stripped",
            ChatPromptBuilder.isEnglishOrForeign("マッチしました Hi!"))
        assertTrue("マッチしました Hey there!",
            ChatPromptBuilder.isEnglishOrForeign("マッチしました Hey there!"))
        assertTrue("3km先 Hello!",
            ChatPromptBuilder.isEnglishOrForeign("3km先 Hello!"))
        assertTrue("オンライン Yo!",
            ChatPromptBuilder.isEnglishOrForeign("オンライン Yo!"))
    }

    @Test
    fun testJapaneseConversations() {
        assertFalse("お疲れ様 is Japanese", ChatPromptBuilder.isEnglishOrForeign("お疲れ様"))
        assertFalse("今週の金曜空いてる？ is Japanese", ChatPromptBuilder.isEnglishOrForeign("今週の金曜空いてる？"))
        assertFalse("了解！また後で連絡するね is Japanese", ChatPromptBuilder.isEnglishOrForeign("了解！また後で連絡するね"))
        assertFalse("はじめまして！よろしくお願いします is Japanese", ChatPromptBuilder.isEnglishOrForeign("はじめまして！よろしくお願いします"))
        assertFalse("茅ヶ崎でカフェ行こー☕️ is Japanese", ChatPromptBuilder.isEnglishOrForeign("茅ヶ崎でカフェ行こー☕️"))
        assertFalse("マッチしました only is not English", ChatPromptBuilder.isEnglishOrForeign("マッチしました"))
    }

    @Test
    fun testEmptyOrInvalidInputs() {
        assertFalse("Empty string is not English", ChatPromptBuilder.isEnglishOrForeign(""))
        assertFalse("Whitespace string is not English", ChatPromptBuilder.isEnglishOrForeign("   "))
        assertFalse("Only URLs is not English", ChatPromptBuilder.isEnglishOrForeign("https://line.me/ti/p/test"))
    }

    @Test
    fun testVisionPromptLineExchangeProhibitionAndKanagawa() {
        val visionPrompt = ChatPromptBuilder.buildPromptForVision(
            mode = AssistantMode.REPLY,
            userPromptNote = null,
            includeLineInvite = true
        )

        // Strict rule: Must instruct model to NEVER output LINE invite when screen is LINE talk room
        assertTrue(
            "Vision prompt must contain strict prohibition against LINE exchange link on LINE screens",
            visionPrompt.contains("画面がLINEのトーク画面の場合はすでにLINE上であるため、LINE交換リンクは【絶対に出力しないこと】") ||
            visionPrompt.contains("画面がLINEのトーク画面の場合はすでにLINE上であるため")
        )

        // Location rule: Must contain Kanagawa locations and forbid Tokyo
        assertTrue(
            "Vision prompt must enforce Kanagawa locations and forbid Tokyo",
            visionPrompt.contains("厚木") && visionPrompt.contains("茅ヶ崎") && visionPrompt.contains("東京禁止")
        )
    }

    @Test
    fun testTextPromptLineExchangeProhibition() {
        // When includeLineInvite = false (e.g. LINE talk room text)
        val textPromptNoInvite = ChatPromptBuilder.buildPromptForText(
            contextText = "了解！また連絡するね",
            contextSource = "ユーザーがLINEからコピーしたメッセージ",
            isPolite = false,
            mode = AssistantMode.REPLY,
            userPromptNote = null,
            includeLineInvite = false
        )

        assertFalse(
            "Text prompt with includeLineInvite = false must NOT contain LINE invite URL",
            textPromptNoInvite.contains(ChatPromptBuilder.USER_LINE_LINK)
        )
        assertFalse(
            "Text prompt with includeLineInvite = false must NOT contain JA_LINE_INVITE",
            textPromptNoInvite.contains("LINE交換しよー")
        )
        assertTrue(
            "Text prompt must enforce Kanagawa locations and forbid Tokyo",
            textPromptNoInvite.contains("厚木") && textPromptNoInvite.contains("茅ヶ崎") && textPromptNoInvite.contains("東京禁止")
        )
    }
}
