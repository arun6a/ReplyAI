package com.replyai.app.knowledge

/**
 * Chunker — splits long text into overlapping chunks suitable for embedding.
 *
 * Strategy (ARCHITECTURE.md section 5):
 *   - Estimate tokens as chars / 4 (1 token ~= 4 chars, OpenAI's rule of thumb).
 *   - Walk the text with a sliding window of [targetTokens] chars, advancing
 *     by [targetTokens] - [overlapTokens] each step.
 *   - Snap window edges to whitespace so words are never split mid-token.
 *
 * Default: 200-token chunks with 50-token overlap — small enough to fit any
 * embedding model's context window, large enough to carry topical context.
 */
object Chunker {

    /**
     * Split [text] into overlapping chunks of approximately [targetTokens] tokens.
     *
     * @param text           The input text. Whitespace-only input yields an empty list.
     * @param targetTokens   Desired chunk size in tokens (1 token ~= 4 chars). Must be > 0.
     * @param overlapTokens   Overlap between consecutive chunks, in tokens. Must be in [0, targetTokens).
     * @return               Chunks in document order. Empty if [text] is blank.
     */
    fun chunk(text: String, targetTokens: Int = 200, overlapTokens: Int = 50): List<String> {
        if (text.isBlank()) return emptyList()
        val s = text.trim()
        if (s.isEmpty()) return emptyList()
        require(targetTokens > 0) { "targetTokens must be > 0, got $targetTokens" }
        require(overlapTokens in 0 until targetTokens) {
            "overlapTokens must be in [0, targetTokens), got $overlapTokens for target $targetTokens"
        }

        val targetChars = targetTokens * 4
        val overlapChars = overlapTokens * 4
        val step = (targetChars - overlapChars).coerceAtLeast(1)

        if (s.length <= targetChars) return listOf(s)

        val result = mutableListOf<String>()
        val n = s.length
        var start = 0
        var guard = 0
        while (start < n) {
            // Extend end to the next whitespace so we never cut a word in half.
            var end = (start + targetChars).coerceAtMost(n)
            if (end < n) {
                while (end < n && !s[end].isWhitespace()) end++
            }
            val piece = s.substring(start, end).trim()
            if (piece.isNotEmpty()) result.add(piece)
            if (end >= n) break

            // Advance by `step` chars, then snap forward to a word boundary so the
            // next chunk begins at the start of a word.
            var nextStart = start + step
            while (nextStart < end && nextStart < n && !s[nextStart - 1].isWhitespace()) nextStart++
            while (nextStart < n && s[nextStart].isWhitespace()) nextStart++
            if (nextStart <= start) nextStart = start + 1  // safety: always make progress
            start = nextStart

            // Paranoia guard: never loop more than n iterations.
            if (++guard > n) break
        }
        return result
    }
}
