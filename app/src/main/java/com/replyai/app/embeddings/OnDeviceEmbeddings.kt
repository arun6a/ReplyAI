package com.replyai.app.embeddings

import android.content.Context

/**
 * OnDeviceEmbeddings — on-device embedding provider using a locally bundled
 * MiniLM model via ONNX Runtime.
 *
 * Phase 1 (now): STUB. All methods throw [NotImplementedError] so callers fail
 * fast rather than silently producing wrong embeddings. This is the fallback
 * provider returned by [EmbeddingProviderFactory] when no cloud API key is
 * configured, but it cannot actually embed yet.
 *
 * Phase 2: implement with ONNX Runtime + MiniLM. Plan:
 *   - Bundle `all-MiniLM-L6-v2.onnx` (~23 MB) under app/src/main/assets/models/
 *   - Add dependency: com.microsoft.onnxruntime:onnxruntime-android:1.17.0
 *   - Tokenize input with a SentencePiece / huggingface tokenizer
 *   - Run inference on a background thread; mean-pool the token-level outputs
 *     into a 384-dim vector and L2-normalize for cosine similarity.
 *
 * Output dimension: 384. Max input: 256 tokens (MiniLM's context window).
 *
 * @param context Application context used in Phase 2 to load the ONNX model
 *                from assets. Currently unused.
 */
class OnDeviceEmbeddings(@Suppress("unused") private val context: Context) : EmbeddingProvider {

    override val name: String = "ondevice"
    override val dimensions: Int = 384
    override val maxTokensPerChunk: Int = 256

    override suspend fun embed(text: String): FloatArray {
        throw NotImplementedError(
            "OnDeviceEmbeddings is a Phase-2 stub. " +
                "Configure a Gemini or OpenAI API key to use cloud embeddings."
        )
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        throw NotImplementedError(
            "OnDeviceEmbeddings is a Phase-2 stub. " +
                "Configure a Gemini or OpenAI API key to use cloud embeddings."
        )
    }
}
