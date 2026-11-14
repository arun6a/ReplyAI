package com.replyai.app.embeddings

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
 * GeminiEmbeddings — calls Google's embedding-001 model.
 *
 * Endpoint: POST https://generativelanguage.googleapis.com/v1beta/models/embedding-001:embedContent
 * Auth: API key passed as the `key` query parameter (Google's convention).
 *
 * Request body:
 *   { "content": { "parts": [ { "text": "<input>" } ] } }
 *
 * Response shape:
 *   { "embedding": { "values": [0.1, 0.2, ...] } }
 *
 * Output dimension: 768. Max input: 2048 tokens per chunk.
 */
class GeminiEmbeddings(private val apiKey: String) : EmbeddingProvider {

    override val name: String = "gemini"
    override val dimensions: Int = 768
    override val maxTokensPerChunk: Int = 2048

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val endpoint: String =
        "https://generativelanguage.googleapis.com/v1beta/models/embedding-001:embedContent"

    override suspend fun embed(text: String): FloatArray = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("content", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", text)))
            })
        }.toString()
        val req = Request.Builder()
            .url("$endpoint?key=$apiKey")
            .post(body.toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string()?.take(500).orEmpty()
                throw GeminiEmbeddingException("HTTP ${resp.code}: $errBody")
            }
            val json = JSONObject(resp.body?.string().orEmpty())
            val values = json.optJSONObject("embedding")?.optJSONArray("values")
                ?: throw GeminiEmbeddingException("Missing embedding.values in response: $json")
            FloatArray(values.length()) { values.getDouble(it).toFloat() }
        }
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        // Gemini's embedContent endpoint takes a single text per call. Embed
        // sequentially — a batch endpoint (batchEmbedContents) exists but is
        // left as a future optimization.
        return texts.map { embed(it) }
    }

    private class GeminiEmbeddingException(msg: String) : RuntimeException(msg)

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
