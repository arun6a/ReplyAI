package com.replyai.app.embeddings

import com.replyai.app.data.ReplyAIDatabase
import kotlin.math.sqrt

/**
 * VectorStore — stores and searches chunk embeddings for RAG.
 *
 * Storage: embeddings are persisted as little-endian BLOBs in the `chunks`
 * table via [ReplyAIDatabase.saveChunk].
 *
 * Search: brute-force cosine similarity, computed in Kotlin over every chunk
 * that belongs to [botId]'s knowledge_files. For v1.0 knowledge bases (<500
 * chunks per bot) this is roughly 5 ms on a phone — no ANN index needed.
 *
 * When a bot's chunk count grows beyond ~10K, swap this for an HNSW index
 * (hnswlib or FAISS). See ARCHITECTURE.md section 7 for the design notes.
 */
object VectorStore {

    /** A single retrieved chunk + its cosine similarity score. */
    data class SearchResult(
        val chunkId: Long,
        val text: String,
        val score: Float,
        val fileName: String,
        val fileId: Long,
    )

    /**
     * Persist a chunk's text + embedding alongside the parent file. The
     * chunk's [chunkIndex] keeps the chunks ordered within a file so callers
     * can reconstruct document context when needed.
     *
     * @param provider Short provider name persisted for traceability
     *                  (e.g. "gemini", "openai", "ondevice").
     */
    fun storeEmbedding(
        fileId: Long,
        chunkIndex: Int,
        text: String,
        embedding: FloatArray,
        provider: String,
    ) {
        val db = ReplyAIDatabase.getInstance()
        db.saveChunk(fileId, chunkIndex, text, encodeEmbedding(embedding), provider)
    }

    /**
     * Brute-force cosine-similarity search over every embedded chunk that
     * belongs to [botId]'s knowledge_files. Returns the top [limit] chunks
     * sorted by descending similarity.
     *
     * Chunks without an embedding (null BLOB) are skipped, as are chunks
     * whose embedding dimensionality doesn't match [queryEmbedding] — this
     * guards against mixed-provider corpora during a provider switch.
     */
    fun searchSimilar(queryEmbedding: FloatArray, botId: Long, limit: Int = 5): List<SearchResult> {
        val db = ReplyAIDatabase.getInstance()
        val files = db.listKnowledgeFiles(botId)
        if (files.isEmpty()) return emptyList()
        val scored = ArrayList<SearchResult>(files.size * 16)
        for (file in files) {
            val chunks = db.getChunksByFile(file.id)
            for (chunk in chunks) {
                val bytes = chunk.embedding ?: continue
                val emb = decodeEmbedding(bytes)
                if (emb.size != queryEmbedding.size) continue
                val score = cosineSimilarity(queryEmbedding, emb)
                scored.add(SearchResult(chunk.id, chunk.text, score, file.fileName, file.id))
            }
        }
        return scored.sortedByDescending { it.score }.take(limit)
    }

    /**
     * Pure-math cosine similarity between two equal-length vectors.
     * Returns 0..1 (typical for normalized embeddings), or 0 if either
     * vector is zero-length or the inputs have mismatched dimensions.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0f) dot / denom else 0f
    }

    // === Little-endian FloatArray <-> ByteArray (matches the BLOB format
    // written by the database; kept here so the embedding code is the single
    // owner of the on-disk encoding). ===

    private fun encodeEmbedding(values: FloatArray): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(values.size * 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (v in values) buffer.putFloat(v)
        return buffer.array()
    }

    private fun decodeEmbedding(bytes: ByteArray): FloatArray {
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.getFloat() }
    }
}
