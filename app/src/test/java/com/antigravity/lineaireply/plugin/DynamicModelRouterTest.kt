package com.antigravity.lineaireply.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for DynamicModelRouterPlugin.
 * Validates:
 * - Deprecated/shutdown models (404 risk) are completely removed
 * - 2026 latest high-speed models in order of priority
 * - API key sanitization (stripping whitespace, quotes, fullwidth spaces)
 * - Safe generation of thinkingConfig (minimal for 3.x, budget 0 for 2.5-flash, null for 2.5-flash-lite)
 */
class DynamicModelRouterTest {

    @Test
    fun testDeprecatedModelsAreExcluded() {
        val candidates = DynamicModelRouterPlugin.CANDIDATE_MODELS
        val deprecatedModels = listOf(
            "gemini-2.0-flash",
            "gemini-2.0-flash-lite",
            "gemini-1.5-flash",
            "gemini-1.5-flash-8b"
        )

        for (dep in deprecatedModels) {
            assertFalse("Candidate models must NOT contain deprecated model: $dep", candidates.contains(dep))
        }
    }

    @Test
    fun testFastestModelsPrioritization() {
        val candidates = DynamicModelRouterPlugin.CANDIDATE_MODELS
        assertTrue("gemini-3.5-flash-lite should be candidate #1", candidates.isNotEmpty() && candidates[0] == "gemini-3.5-flash-lite")
        assertTrue("gemini-3.1-flash-lite should be candidate #2", candidates.size > 1 && candidates[1] == "gemini-3.1-flash-lite")
        assertTrue("gemini-2.5-flash-lite should be candidate #3", candidates.size > 2 && candidates[2] == "gemini-2.5-flash-lite")
        assertTrue("gemini-3.6-flash should be candidate #4", candidates.contains("gemini-3.6-flash"))
        assertTrue("gemini-3.8-flash should be candidate #5", candidates.contains("gemini-3.8-flash"))
    }

    @Test
    fun testSanitizeKey() {
        assertEquals("AIzaSy12345", DynamicModelRouterPlugin.sanitizeKey("  AIzaSy12345  "))
        assertEquals("AIzaSy12345", DynamicModelRouterPlugin.sanitizeKey("\"AIzaSy12345\""))
        assertEquals("AIzaSy12345", DynamicModelRouterPlugin.sanitizeKey("'AIzaSy12345'"))
        assertEquals("AIzaSy12345", DynamicModelRouterPlugin.sanitizeKey("AIza\nSy12\r345"))
        assertEquals("AIzaSy12345", DynamicModelRouterPlugin.sanitizeKey("AIza　Sy\t12345"))
    }

    @Test
    fun testThinkingConfigGeneration() {
        // 2.5-flash-lite: thinking is OFF / null
        val liteConfig = DynamicModelRouterPlugin.buildThinkingConfig("gemini-2.5-flash-lite")
        assertNull("2.5-flash-lite should not have thinkingConfig", liteConfig)

        // 2.5-flash (standard): thinkingBudget 0
        val flash25Config = DynamicModelRouterPlugin.buildThinkingConfig("gemini-2.5-flash")
        assertNotNull("2.5-flash should have thinkingConfig", flash25Config)
        assertEquals(0, flash25Config?.optInt("thinkingBudget", -1))

        // 3.5-flash-lite: thinkingLevel minimal
        val flash35Config = DynamicModelRouterPlugin.buildThinkingConfig("gemini-3.5-flash-lite")
        assertNotNull("3.5-flash-lite should have thinkingConfig", flash35Config)
        assertEquals("minimal", flash35Config?.optString("thinkingLevel"))

        // 3.1-flash-lite: thinkingLevel minimal
        val flash31Config = DynamicModelRouterPlugin.buildThinkingConfig("gemini-3.1-flash-lite")
        assertNotNull("3.1-flash-lite should have thinkingConfig", flash31Config)
        assertEquals("minimal", flash31Config?.optString("thinkingLevel"))

        // 3.6-flash: thinkingLevel minimal
        val flash36Config = DynamicModelRouterPlugin.buildThinkingConfig("gemini-3.6-flash")
        assertNotNull("3.6-flash should have thinkingConfig", flash36Config)
        assertEquals("minimal", flash36Config?.optString("thinkingLevel"))
    }
}
