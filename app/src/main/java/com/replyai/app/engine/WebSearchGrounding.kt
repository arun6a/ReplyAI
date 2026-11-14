package com.replyai.app.engine

import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * WebSearchGrounding — v1 implementation of the "live web RAG" retrieval mode.
 *
 * When the [IntentClassifier] picks [IntentClassifier.RetrievalMode.WEB_SEARCH],
 * the [RuleEngine] uses this object to ground the bot's reply in fresh data
 * from the bot's [com.replyai.app.bots.Bot.companyUrl].
 *
 * v1 strategy (deliberately simple — see ARCHITECTURE.md section 8):
 *   1. Build a Google "site:" query:  site:xyz.com <user question>
 *   2. Fetch the SERP HTML.
 *   3. Extract up to 3 result links + snippets.
 *   4. Return them as a text block the LLM can cite.
 *
 * Future upgrades (out of scope for v1):
 *   - Real SERP API (Bing/Brave/Google CSE) instead of HTML scraping.
 *   - fetchPageText() on the top result and pass its first 2K chars too.
 *   - Cache results per (site, query) for 1h.
 *
 * Uses only OkHttp + org.json. No Android UI dependencies. Safe to call from
 * a background coroutine.
 *
 * See ARCHITECTURE.md section 5 ("WebSearchGrounding") + section 8.
 */
object WebSearchGrounding {

    private const val TAG = "WebSearchGrounding"

    /** Cap page text so we don't blow up the LLM context window. */
    private const val MAX_PAGE_CHARS = 2000

    /** How many SERP snippets to keep. */
    private const val MAX_RESULTS = 3

    /** HTTP client — shared, with generous timeouts for slow mobile networks. */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** Desktop Chrome UA — Google SERP needs a real browser UA to render links. */
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /**
     * Run a `site:`-scoped Google search and return up to [MAX_RESULTS]
     * formatted results.
     *
     * Returns a multi-line string like:
     *   [1] https://www.acme.com/pricing
     *       Acme pricing — Starter $9/mo, Pro $29/mo ...
     *   [2] https://www.acme.com/faq
     *       ...
     * Returns null on network failure or if no results could be parsed.
     *
     * @param siteUrl  e.g. "acme.com" — no protocol needed. Will be stripped if present.
     * @param query    the user's natural-language question.
     */
    suspend fun searchSite(siteUrl: String, query: String): String? {
        val cleanSite = siteUrl
            .removePrefix("https://")
            .removePrefix("http://")
            .trim()
        if (cleanSite.isEmpty() || query.isBlank()) return null

        val encodedQuery = URLEncoder.encode("site:$cleanSite $query", "UTF-8")
        val searchUrl = "https://www.google.com/search?q=$encodedQuery"

        val html = fetchRaw(searchUrl) ?: return null
        val results = extractSerResults(html)
        if (results.isEmpty()) return null

        val sb = StringBuilder()
        results.take(MAX_RESULTS).forEachIndexed { i, r ->
            sb.append("[${i + 1}] ${r.url}\n")
            if (r.snippet.isNotBlank()) {
                sb.append("    ${r.snippet}\n")
            }
        }
        return sb.toString().trim().ifEmpty { null }
    }

    /**
     * Fetch [url], strip HTML tags, collapse whitespace, return the first
     * [MAX_PAGE_CHARS] characters of visible text.
     *
     * Returns null on any error (network, non-2xx, parse).
     */
    suspend fun fetchPageText(url: String): String? {
        val html = fetchRaw(url) ?: return null
        val text = stripHtml(html)
        if (text.isBlank()) return null
        return if (text.length <= MAX_PAGE_CHARS) text else text.substring(0, MAX_PAGE_CHARS)
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private data class SerResult(val url: String, val snippet: String)

    /**
     * Raw HTTP GET that returns the response body as a String, or null on
     * failure. Uses the desktop Chrome UA so Google SERP renders links.
     *
     * Runs on [Dispatchers.IO] so the blocking OkHttp call doesn't pin the
     * caller's thread (which is usually Main or Default).
     */
    private suspend fun fetchRaw(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string()
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "fetchRaw failed for $url: ${e.message}")
            null
        }
    }

    /**
     * Best-effort SERP parser. Google's HTML changes often, so we use a
     * forgiving strategy: scan all `<a href="/url?q=...">` links (the form
     * Google uses for result clicks) and the surrounding text as a snippet.
     *
     * This won't catch every result on every layout but reliably gets the
     * top few. Future versions should use a real SERP API.
     */
    private fun extractSerResults(html: String): List<SerResult> {
        val out = LinkedHashMap<String, SerResult>() // dedupe by URL
        // Google wraps result URLs as /url?q=<actual url>&sa=...
        val linkRegex = Regex("""/url\?q=([^&"']+)""")
        for (m in linkRegex.findAll(html)) {
            val raw = m.groupValues[1]
            // Skip Google-internal links.
            if (raw.startsWith("https://www.google.") ||
                raw.startsWith("https://accounts.google.") ||
                raw.startsWith("https://support.google.") ||
                raw.startsWith("https://policies.google.") ||
                raw.startsWith("https://maps.google.")
            ) {
                continue
            }
            if (raw.startsWith("http://") || raw.startsWith("https://")) {
                // Already a clean URL — keep it.
                out.putIfAbsent(raw, SerResult(raw, snippetFor(html, m.range.first, raw)))
                if (out.size >= MAX_RESULTS * 3) break
            }
        }
        // Also try plain href="https://..." occurrences (mobile SERP layout).
        if (out.isEmpty()) {
            val directRegex = Regex("""href="(https?://[^"]+)"""")
            for (m in directRegex.findAll(html)) {
                val raw = m.groupValues[1]
                if (raw.contains("google.")) continue
                out.putIfAbsent(raw, SerResult(raw, ""))
                if (out.size >= MAX_RESULTS * 3) break
            }
        }
        return out.values.toList()
    }

    /**
     * Grab ~300 chars of nearby text as a snippet. Best-effort — if it fails
     * we just return an empty snippet; the URL alone is still useful to the LLM.
     */
    private fun snippetFor(html: String, anchorPos: Int, url: String): String {
        return try {
            val start = (anchorPos + 200).coerceAtMost(html.length - 1)
            val end = (start + 400).coerceAtMost(html.length)
            if (start >= end) return ""
            val chunk = html.substring(start, end)
            val text = stripHtml(chunk).trim()
            // Filter out boilerplate that often follows a Google link.
            val cleaned = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("http") }
                .joinToString(" ")
                .take(300)
            cleaned
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Strip HTML tags + entities + scripts + styles. Collapse whitespace.
     */
    private fun stripHtml(html: String): String {
        var s = html
        // Drop script/style blocks entirely.
        s = Regex("(?is)<script.*?</script>").replace(s, " ")
        s = Regex("(?is)<style.*?</style>").replace(s, " ")
        s = Regex("(?is)<noscript.*?</noscript>").replace(s, " ")
        // Drop all tags.
        s = Regex("(?s)<[^>]+>").replace(s, " ")
        // Decode common entities.
        s = s.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
        // Collapse whitespace.
        s = Regex("\\s+").replace(s, " ").trim()
        return s
    }
}
