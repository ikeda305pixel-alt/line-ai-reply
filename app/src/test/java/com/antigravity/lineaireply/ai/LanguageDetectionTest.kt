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
}
