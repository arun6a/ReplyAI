package com.replyai.app.embeddings

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * OnDeviceEmbeddings — fully local embedding provider using a bundled
 * all-MiniLM-L6-v2 INT8-quantized ONNX model (~23 MB).
 *
 * Output dimension: 384
 * Max input: 256 word pieces (BERT WordPiece tokenizer)
 *
 * Pipeline:
 *   1. Tokenize text via WordPiece (BERT uncased vocab).
 *   2. Build input_ids + attention_mask + token_type_ids tensors.
 *   3. Run ONNX inference on a background thread.
 *   4. Mean-pool the last_hidden_state across non-pad tokens.
 *   5. L2-normalize the resulting 384-dim vector for cosine similarity.
 *
 * The model is bundled at assets/models/all_minilm_l6_v2_qint8.onnx so the
 * app works fully offline with no API key, no network, no cloud. Use this
 * provider when the user has not configured a Gemini/OpenAI API key, or when
 * they explicitly select "On-device" for privacy.
 *
 * First call is slow (~500 ms) because the model loads + ORT session is
 * created. Subsequent calls are ~10-30 ms per text on a mid-range phone.
 */
class OnDeviceEmbeddings(private val context: Context) : EmbeddingProvider {

    override val name: String = "ondevice"
    override val dimensions: Int = 384
    override val maxTokensPerChunk: Int = 256

    private val TAG = "OnDeviceEmbeddings"

    @Volatile private var session: OrtSession? = null
    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var tokenizer: BertTokenizer? = null
    private val lock = Any()

    /**
     * Lazily initialize the ONNX Runtime environment + session + tokenizer.
     * Thread-safe; subsequent calls are no-ops.
     */
    private fun ensureInitialized() {
        if (session != null && tokenizer != null) return
        synchronized(lock) {
            if (session != null && tokenizer != null) return
            try {
                val environment = OrtEnvironment.getEnvironment()
                env = environment

                // Load model bytes from assets
                val modelBytes = context.assets.open(MODEL_PATH).use { it.readBytes() }
                val sessionOptions = OrtSession.SessionOptions().apply {
                    // Use the default CPU EP — works on all devices.
                    // Optimization level 3 = best for inference throughput.
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setIntraOpNumThreads(2)
                }
                session = environment.createSession(modelBytes, sessionOptions)

                // Load BERT vocab from assets
                val vocab = mutableMapOf<String, Long>()
                context.assets.open(VOCAB_PATH).use { input ->
                    BufferedReader(InputStreamReader(input)).useLines { lines ->
                        lines.forEachIndexed { index, line ->
                            val token = line.trimEnd('\n', '\r')
                            if (token.isNotEmpty()) vocab[token] = index.toLong()
                        }
                    }
                }
                tokenizer = BertTokenizer(vocab)
                Log.i(TAG, "ONNX session + tokenizer loaded (vocab=${vocab.size} tokens, model=${modelBytes.size / 1024}KB)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize on-device embeddings", e)
                throw RuntimeException("OnDeviceEmbeddings init failed: ${e.message}", e)
            }
        }
    }

    override suspend fun embed(text: String): FloatArray = withContext(Dispatchers.Default) {
        ensureInitialized()
        val sess = session ?: throw RuntimeException("ONNX session not initialized")
        val tok = tokenizer ?: throw RuntimeException("Tokenizer not initialized")

        try {
            val (inputIds, attentionMask, tokenTypeIds) = tok.encode(text, maxLen = maxTokensPerChunk)

            // 2D shape [1, seq_len]; OnnxTensor.createTensor(env, Object) infers
            // shape from the array structure — arrayOf(LongArray) → [1, seq_len] long tensor.
            val inputIdsTensor = OnnxTensor.createTensor(env, arrayOf(inputIds))
            val attentionMaskTensor = OnnxTensor.createTensor(env, arrayOf(attentionMask))
            val tokenTypeIdsTensor = OnnxTensor.createTensor(env, arrayOf(tokenTypeIds))

            val inputs = mapOf(
                "input_ids" to inputIdsTensor,
                "attention_mask" to attentionMaskTensor,
                "token_type_ids" to tokenTypeIdsTensor,
            )

            val output = sess.run(inputs)
            try {
                // Output is last_hidden_state: [1, seq_len, 384]
                @Suppress("UNCHECKED_CAST")
                val raw = output.get(0).value as Array<Array<FloatArray>>
                val tokenEmbeddings = raw[0] // [seq_len][384]

                // Mean-pool across non-pad tokens (using attention_mask)
                val pooled = FloatArray(dimensions)
                var validCount = 0
                for (i in attentionMask.indices) {
                    if (attentionMask[i] == 1L) {
                        for (j in 0 until dimensions) {
                            pooled[j] += tokenEmbeddings[i][j]
                        }
                        validCount++
                    }
                }
                if (validCount > 0) {
                    val inv = 1f / validCount.toFloat()
                    for (j in 0 until dimensions) pooled[j] = pooled[j] * inv
                }

                // L2-normalize for cosine similarity
                var norm = 0f
                for (v in pooled) norm += v * v
                norm = Math.sqrt(norm.toDouble()).toFloat()
                if (norm > 0) {
                    val invNorm = 1f / norm
                    for (j in 0 until dimensions) pooled[j] = pooled[j] * invNorm
                }

                pooled
            } finally {
                inputIdsTensor.close()
                attentionMaskTensor.close()
                tokenTypeIdsTensor.close()
                output.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "embed failed for: ${text.take(50)}...", e)
            FloatArray(dimensions)
        }
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> = withContext(Dispatchers.Default) {
        // Sequential — could be batched with left-padding for performance,
        // but sequential is simpler and fine for our use case (≤5 chunks per query).
        texts.map { embed(it) }
    }

    companion object {
        private const val MODEL_PATH = "models/all_minilm_l6_v2_qint8.onnx"
        private const val VOCAB_PATH = "models/vocab.txt"
    }
}
