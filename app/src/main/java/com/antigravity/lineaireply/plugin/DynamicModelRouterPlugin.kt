package com.antigravity.lineaireply.plugin

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Dynamic model router plugin for Google Gemini API.
 * Responsibilities:
 * - Runtime discovery of available models via ListModels API.
 * - Automatic prioritized model selection (Gemini 2.5/2.0 Flash -> 1.5 Flash).
 * - Multi-candidate failover execution (prevents 404 / deprecated model breakage).
 * - API key sanitization and connection validation.
 */
class DynamicModelRouterPlugin : IDynamicModelRouterPlugin {

    companion object {
        const val TAG = "DynamicModelRouter"

        val CANDIDATE_MODELS = listOf(
            "gemini-3-flash-preview",
            "gemini-3.8-flash",
            "gemini-3.5-flash",
            "gemini-3.1-flash-lite",
            "gemma-4-26b-a4b-it",
            "gemini-flash-latest",
            "gemini-flash-lite-latest",
            "gemini-2.5-flash",
            "gemini-2.0-flash",
            "gemini-1.5-flash"
        )

        @Volatile
        var cachedWorkingModel: String? = null

        fun sanitizeKey(rawKey: String): String {
            return rawKey
                .replace("\"", "")
                .replace("'", "")
                .replace(" ", "")
                .replace("\t", "")
                .replace("\n", "")
                .replace("\r", "")
                .replace("　", "")
                .trim()
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun fetchAvailableModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val cleanKey = sanitizeKey(apiKey)
        if (cleanKey.isBlank()) return@withContext emptyList()
        try {
            val listClient = OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build()
            val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$cleanKey"
            val req = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", cleanKey)
                .get()
                .build()
            val resp = listClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string() ?: ""
                val root = JSONObject(body)
                val modelsArray = root.optJSONArray("models") ?: return@withContext emptyList()
                val result = mutableListOf<String>()
                for (i in 0 until modelsArray.length()) {
                    val mObj = modelsArray.getJSONObject(i)
                    val name = mObj.optString("name", "").removePrefix("models/")
                    val methods = mObj.optJSONArray("supportedGenerationMethods")
                    var canGenerate = false
                    if (methods != null) {
                        for (j in 0 until methods.length()) {
                            if (methods.getString(j).equals("generateContent", ignoreCase = true)) {
                                canGenerate = true
                                break
                            }
                        }
                    }
                    if (canGenerate && name.isNotBlank()) {
                        result.add(name)
                    }
                }

                // Sort: prioritize 3-flash-preview > 3.8-flash > 3.5-flash > flash-latest > gemma > 2.5-flash
                result.sortWith { a, b ->
                    fun score(m: String): Int = when {
                        m.contains("3-flash-preview") -> 120
                        m.contains("3.8-flash") -> 115
                        m.contains("3.5-flash") -> 110
                        m.contains("flash-latest") -> 105
                        m.contains("3.1-flash") -> 100
                        m.contains("gemma-4-26b") -> 95
                        m.contains("2.5-flash") && !m.contains("lite") -> 85
                        m.contains("2.0-flash") -> 75
                        m.contains("flash-lite") -> 70
                        m.contains("1.5-flash") -> 60
                        m.contains("flash") -> 50
                        m.contains("pro") -> 40
                        else -> 10
                    }
                    score(b) - score(a)
                }
                if (result.isNotEmpty()) {
                    Log.i(TAG, "Discovered active models: ${result.take(4)}")
                }
                return@withContext result
            }
        } catch (e: Exception) {
            Log.w(TAG, "ListModels call failed: ${e.message}")
        }
        emptyList()
    }

    override suspend fun generateContent(
        apiKey: String,
        prompt: String,
        preferredModel: String?,
        imageBase64: String?
    ): ModelResult = withContext(Dispatchers.IO) {
        val cleanKey = sanitizeKey(apiKey)
        if (cleanKey.isBlank()) {
            return@withContext ModelResult(false, "", "", "APIキーが入力されていません")
        }

        val discovered = if (cachedWorkingModel != null) {
            listOf(cachedWorkingModel!!) + CANDIDATE_MODELS
        } else {
            val list = fetchAvailableModels(cleanKey)
            if (list.isNotEmpty()) list + CANDIDATE_MODELS else CANDIDATE_MODELS
        }.distinct()

        val modelsToTry = if (preferredModel != null) {
            listOf(preferredModel) + discovered
        } else {
            discovered
        }.distinct()

        var lastError = ""

        for (rawModel in modelsToTry) {
            val model = rawModel.removePrefix("models/")
            try {
                val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$cleanKey"
                val body = JSONObject().apply {
                    val parts = JSONArray().apply {
                        if (!imageBase64.isNullOrBlank()) {
                            put(JSONObject().apply {
                                put("inline_data", JSONObject().apply {
                                    put("mime_type", "image/jpeg")
                                    put("data", imageBase64)
                                })
                            })
                        }
                        put(JSONObject().put("text", prompt))
                    }
                    put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                    put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
                }

                val req = Request.Builder()
                    .url(endpoint)
                    .addHeader("x-goog-api-key", cleanKey)
                    .post(body.toString().toRequestBody(mediaType))
                    .build()

                val resp = client.newCall(req).execute()
                val code = resp.code
                val respStr = resp.body?.string() ?: ""

                if (!resp.isSuccessful) {
                    val friendlyMsg = when (code) {
                        400 -> "HTTP 400 (無効なAPIキー): キー形式を確認してください"
                        403 -> "HTTP 403 (権限エラー): Google CloudでGenerative Language APIが有効か確認してください"
                        404 -> "HTTP 404 (モデル $model 未検出)"
                        429 -> "HTTP 429 (利用枠制限): しばらく待って再試行してください"
                        else -> "HTTP $code: $respStr"
                    }
                    lastError = friendlyMsg
                    Log.w(TAG, "Model $model returned error: $lastError")
                    continue
                }

                val root = JSONObject(respStr)
                val candidates = root.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    val feedback = root.optJSONObject("promptFeedback")
                    lastError = "生成結果が空です (ブロック理由: ${feedback?.optString("blockReason", "不明")})"
                    continue
                }

                val candidateObj = candidates.getJSONObject(0)
                val contentObj = candidateObj.optJSONObject("content")
                val partsArray = contentObj?.optJSONArray("parts")
                if (partsArray == null || partsArray.length() == 0) {
                    val finishReason = candidateObj.optString("finishReason", "不明")
                    lastError = "返信本文が空です (finishReason: $finishReason)"
                    continue
                }

                val outputText = partsArray.getJSONObject(0).optString("text", "")
                cachedWorkingModel = model
                return@withContext ModelResult(true, outputText, model, null)
            } catch (e: Exception) {
                lastError = "通信例外 ($model): ${e.message}"
                Log.w(TAG, "Request failed for $model: ${e.message}")
            }
        }

        ModelResult(false, "", "", lastError)
    }

    override suspend fun testApiKey(apiKey: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val cleanKey = sanitizeKey(apiKey)
        if (cleanKey.isBlank()) return@withContext Pair(false, "APIキーが入力されていません。")

        val discovered = fetchAvailableModels(cleanKey)
        val modelsToTry = if (discovered.isNotEmpty()) {
            (discovered.take(5) + CANDIDATE_MODELS).distinct()
        } else {
            CANDIDATE_MODELS
        }

        var lastError = ""
        for (rawModel in modelsToTry) {
            val model = rawModel.removePrefix("models/")
            try {
                val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$cleanKey"
                val body = JSONObject().apply {
                    val parts = JSONArray().put(JSONObject().put("text", "Say OK"))
                    put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                }
                val req = Request.Builder()
                    .url(endpoint)
                    .post(body.toString().toRequestBody(mediaType))
                    .build()

                val resp = client.newCall(req).execute()
                val code = resp.code
                val respBody = resp.body?.string() ?: ""

                if (resp.isSuccessful) {
                    cachedWorkingModel = model
                    return@withContext Pair(
                        true,
                        "✅ Gemini API通信成功！(HTTP $code)\n\n【利用モデル】$model\n\n最新モデルとの接続が確認できました。LINEおよびBumbleで高精度なAI返信が利用可能です。"
                    )
                } else {
                    val friendlyMsg = when (code) {
                        400 -> "HTTP 400 (無効なAPIキー)\n・APIキーが間違っているか、末尾が欠けている可能性があります。"
                        403 -> "HTTP 403 (権限・地域エラー)\n・Google CloudのGenerative Language APIが有効化されているかご確認ください。"
                        404 -> "HTTP 404 (モデル $model 未検出)"
                        429 -> "HTTP 429 (利用枠上限・レート制限)\n・Gemini APIの無料枠制限に達しました。1分ほど置いて再試行してください。"
                        else -> "HTTP $code エラー: $respBody"
                    }
                    lastError = friendlyMsg
                }
            } catch (e: Exception) {
                lastError = "通信例外 ($model): ${e.message}"
            }
        }
        Pair(false, lastError)
    }
}
