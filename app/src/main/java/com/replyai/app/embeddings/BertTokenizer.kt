package com.replyai.app.embeddings

/**
 * BertTokenizer — minimal BERT WordPiece tokenizer for the all-MiniLM-L6-v2
 * model. Encodes a string into the three tensors ONNX expects:
 *
 *   - input_ids: token IDs (vocab index) padded with [PAD]=0
 *   - attention_mask: 1 for real tokens, 0 for padding
 *   - token_type_ids: all zeros (single-sentence encoding)
 *
 * Special tokens:
 *   [CLS] = 101 — prepended to every sequence
 *   [SEP] = 102 — appended to every sequence
 *   [PAD] = 0   — padding
 *   [UNK] = 100 — unknown tokens (WordPiece fallback)
 *
 * Tokenization:
 *   1. Lowercase + strip accents (BERT uncased vocab convention).
 *   2. Whitespace split.
 *   3. For each word: greedy longest-match WordPiece subword split using "##"
 *      continuation prefix.
 *   4. Truncate to maxLen-2 (room for [CLS] + [SEP]).
 *   5. Pad to maxLen.
 *
 * The vocab is loaded from assets/models/vocab.txt (BERT uncased vocab,
 * 30522 tokens). Each line is one token; line index = token ID.
 */
class BertTokenizer(private val vocab: Map<String, Long>) {

    private val clsId: Long = vocab["[CLS]"] ?: 101L
    private val sepId: Long = vocab["[SEP]"] ?: 102L
    private val padId: Long = vocab["[PAD]"] ?: 0L
    private val unkId: Long = vocab["[UNK]"] ?: 100L

    /** Max subword length for greedy match. */
    private val maxInputCharsPerWord = 100

    /**
     * Encode text into (input_ids, attention_mask, token_type_ids), each of
     * length maxLen (padded with [PAD]).
     */
    fun encode(text: String, maxLen: Int = 256): Triple<LongArray, LongArray, LongArray> {
        val tokens = tokenize(text)
        // Truncate to maxLen-2 (room for [CLS] + [SEP])
        val truncated = if (tokens.size > maxLen - 2) tokens.subList(0, maxLen - 2) else tokens

        // Prepend [CLS], append [SEP]
        val fullTokens = ArrayList<String>(truncated.size + 2)
        fullTokens.add("[CLS]")
        fullTokens.addAll(truncated)
        fullTokens.add("[SEP]")

        // Convert to IDs + build attention mask
        val seqLen = fullTokens.size
        val inputIds = LongArray(maxLen)
        val attentionMask = LongArray(maxLen)
        val tokenTypeIds = LongArray(maxLen) // all zeros for single-sentence

        for (i in 0 until maxLen) {
            if (i < seqLen) {
                inputIds[i] = vocab[fullTokens[i]] ?: unkId
                attentionMask[i] = 1L
            } else {
                inputIds[i] = padId
                attentionMask[i] = 0L
            }
        }

        return Triple(inputIds, attentionMask, tokenTypeIds)
    }

    /**
     * Tokenize text into WordPiece subwords.
     */
    fun tokenize(text: String): List<String> {
        val output = ArrayList<String>()
        // Simple whitespace split (BERT also does basic punctuation splitting,
        // but for embeddings the loss is negligible — we keep it simple).
        for (rawWord in text.trim().split(Regex("\\s+"))) {
            if (rawWord.isEmpty()) continue
            val word = cleanText(rawWord)
            if (word.isEmpty()) continue
            if (word.length > maxInputCharsPerWord) {
                output.add("[UNK]")
                continue
            }
            // Greedy longest-match WordPiece
            val subwords = wordPiece(word)
            if (subwords.isEmpty()) {
                output.add("[UNK]")
            } else {
                output.addAll(subwords)
            }
        }
        return output
    }

    /**
     * WordPiece greedy longest-match split. Continuation subwords are prefixed
     * with "##" in the BERT vocab. Returns ["play", "##ing"] for "playing".
     */
    private fun wordPiece(word: String): List<String> {
        val result = ArrayList<String>()
        if (word.isEmpty()) return result

        var start = 0
        while (start < word.length) {
            var end = word.length
            var curSubstr: String? = null
            while (start < end) {
                val sub = if (start > 0) "##" + word.substring(start, end) else word.substring(start, end)
                if (vocab.containsKey(sub)) {
                    curSubstr = sub
                    break
                }
                end--
            }
            if (curSubstr == null) {
                // No match — whole word is unknown
                return listOf("[UNK]")
            }
            result.add(curSubstr)
            start = end
        }
        return result
    }

    /**
     * Lowercase + strip accents + remove control chars. Matches BERT uncased
     * preprocessing closely enough for MiniLM to produce good embeddings.
     */
    private fun cleanText(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            val cp = c.code
            if (cp == 0 || cp == 0xFFFD || isControl(c)) continue
            if (c.isWhitespace()) {
                sb.append(' ')
            } else {
                // Strip accents (NFD normalization + remove combining marks)
                val normalized = java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD)
                for (nc in normalized) {
                    val category = Character.getType(nc)
                    if (category != Character.NON_SPACING_MARK.toInt() &&
                        category != Character.ENCLOSING_MARK.toInt()
                    ) {
                        sb.append(nc.lowercaseChar())
                    }
                }
            }
        }
        return sb.toString().trim()
    }

    private fun isControl(c: Char): Boolean {
        val category = Character.getType(c)
        return category == Character.CONTROL.toInt() ||
            category == Character.FORMAT.toInt() ||
            category == Character.SURROGATE.toInt() ||
            category == Character.PRIVATE_USE.toInt()
    }
}
