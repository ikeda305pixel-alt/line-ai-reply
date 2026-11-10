package com.antigravity.lineaireply.plugin

data class ModelResult(
    val success: Boolean,
    val responseText: String,
    val modelName: String,
    val errorMessage: String? = null
)

/**
 * Plugin interface for Gemini model discovery, automatic routing, and failover execution.
 */
interface IDynamicModelRouterPlugin {
    suspend fun fetchAvailableModels(apiKey: String): List<String>
    suspend fun generateContent(
        apiKey: String,
        prompt: String,
        preferredModel: String? = null
    ): ModelResult
    suspend fun testApiKey(apiKey: String): Pair<Boolean, String>
}
