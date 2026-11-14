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
 * OpenAIEmbeddings — calls OpenAI's text-embedding-3-small model.
 *
 * Endpoint: POST https://api.openai.com/v1/embeddings
 * Auth: Bearer token in the `Authorization` header.
 *
 * Request body:
 *   { "model": "text-embedding-3-small", "input": ["<text>", ...] }
 *
 * Response shape:
 *   { "data": [ { "embedding": [0.1, 0.2, ...] }, ... ] }
 *
 * Output dimension: 1536. Max input: 8191 tokens per chunk.
 */
class OpenAIEmbeddings(private val apiKey: String) : EmbeddingProvider {

    override val name: String = "openai"
    override val dimensions: Int = 1536
    override val maxTokensPerChunk: Int = 8191

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val endpoint: String = "https://api.openai.com/v1/embeddings"

    override suspend fun embed(text: String): FloatArray = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", "text-embedding-3-small")
            put("input", JSONArray().put(text))
        }.toString()
        val req = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string()?.take(500).orEmpty()
                throw OpenAIEmbeddingException("HTTP ${resp.code}: $errBody")
            }
            val json = JSONObject(resp.body?.string().orEmpty())
            val embedding = json.optJSONArray("data")
                ?.optJSONObject(0)
                ?.optJSONArray("embedding")
                ?: throw OpenAIEmbeddingException("Missing data[0].embedding in response: $json")
            FloatArray(embedding.length()) { embedding.getDouble(it).toFloat() }
        }
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        val body = JSONObject().apply {
            put("model", "text-embedding-3-small")
            put("input", JSONArray().apply { texts.forEach { put(it) } })
        }.toString()
        val req = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string()?.take(500).orEmpty()
                throw OpenAIEmbeddingException("HTTP ${resp.code}: $errBody")
            }
            val json = JSONObject(resp.body?.string().orEmpty())
            val data = json.optJSONArray("data")
                ?: throw OpenAIEmbeddingException("Missing data array in response: $json")
            (0 until data.length()).map { i ->
                val arr = data.getJSONObject(i).optJSONArray("embedding")
                    ?: throw OpenAIEmbeddingException("Missing embedding at index $i")
                FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
            }
        }
    }

    private class OpenAIEmbeddingException(msg: String) : RuntimeException(msg)

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
