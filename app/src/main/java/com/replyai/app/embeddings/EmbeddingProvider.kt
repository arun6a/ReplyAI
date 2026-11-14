package com.replyai.app.embeddings

/**
 * EmbeddingProvider — pluggable text embedding backends for RAG.
 *
 * Implementations:
 *   - [GeminiEmbeddings]   — Google embedding-001 (768-dim, default)
 *   - [OpenAIEmbeddings]   — OpenAI text-embedding-3-small (1536-dim)
 *   - [OnDeviceEmbeddings] — local MiniLM via ONNX Runtime (384-dim, Phase 2)
 *
 * Selection is done by [EmbeddingProviderFactory] based on which API key is
 * configured. See ARCHITECTURE.md section 9 for the design rationale.
 *
 * All methods are suspending so callers can run them on IO dispatchers;
 * implementations bridge the synchronous OkHttp call internally.
 */
interface EmbeddingProvider {

    /** Short identifier persisted alongside each chunk (e.g. "gemini"). */
    val name: String

    /** Output vector dimensionality (must be consistent across all stored chunks). */
    val dimensions: Int

    /** Maximum input tokens the model accepts per call. */
    val maxTokensPerChunk: Int

    /** Embed a single text. Returns a FloatArray of length [dimensions]. */
    suspend fun embed(text: String): FloatArray

    /**
     * Embed a batch of texts. Default implementations call [embed] sequentially;
     * providers that support a batch endpoint (e.g. OpenAI) override for speed.
     */
    suspend fun embedBatch(texts: List<String>): List<FloatArray>
}
