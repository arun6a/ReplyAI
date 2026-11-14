package com.replyai.app.knowledge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * WebsiteCrawler — multi-page website crawler.
 *
 * Fetches the homepage at [startUrl], extracts all internal `<a href="…">`
 * links, BFS-visits each linked page up to [maxPages] / [maxDepth], and
 * returns the extracted (url, title, text) for every successfully fetched
 * page.
 *
 * Design choices (deliberately simple, see ARCHITECTURE.md section 5):
 *   - OkHttp for HTTP (same client pattern as WebSearchGrounding).
 *   - Regex-based HTML parsing — no Jsoup dependency. We only need three
 *     things from the HTML: <title>, visible text, and <a href="…"> links.
 *     Regex is good enough and keeps the APK small.
 *   - Polite: 500 ms delay between requests + desktop browser UA + real
 *     Accept headers.
 *   - Resilient: every page fetch is wrapped in try/catch so one bad page
 *     never aborts the whole crawl.
 *   - Same-domain only: external links are dropped to avoid crawling the
 *     entire internet.
 *
 * Output is consumed by the Knowledge screen: each [CrawledPage] becomes a
 * `source_type="web_crawl"` knowledge file with `source_url` set, then goes
 * through the normal chunk + embed pipeline.
 */
object WebsiteCrawler {

    private const val TAG = "WebsiteCrawler"

    /** Polite delay between page fetches (ms). */
    private const val REQUEST_DELAY_MS = 500L

    /** Desktop Chrome UA — many sites serve mobile-only or 404 to unknown UAs. */
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** Content-Types we treat as HTML. Anything else (images, PDFs, JSON feeds) is skipped. */
    private val HTML_CONTENT_TYPES = listOf("text/html", "application/xhtml+xml", "text/plain")

    /** Tags whose contents are pure boilerplate — drop them before extracting text. */
    private val BOILERPLATE_TAGS = listOf("script", "style", "noscript", "nav", "footer", "header", "aside", "form")

    /** Shared HTTP client — generous timeouts for slow mobile networks. */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    data class CrawledPage(
        val url: String,
        val title: String,
        val text: String,
        val fetchedAt: Long,
    )

    /**
     * Crawl [startUrl] BFS-style and return every successfully fetched page.
     *
     * @param startUrl   Homepage URL. "https://" is added if missing; trailing
     *                    slash is stripped.
     * @param maxPages   Hard cap on the number of pages returned (default 20).
     * @param maxDepth   Link-following depth. 0 = homepage only, 1 = homepage +
     *                    its direct links, 2 = homepage → section → article.
     * @param onProgress Called after each page is fetched with (url, pageCount).
     *                    Lets the UI show "fetching https://… (3/20)".
     * @return           Successfully crawled pages in fetch order. The homepage
     *                    is always first when reachable.
     */
    suspend fun crawlSite(
        startUrl: String,
        maxPages: Int = 20,
        maxDepth: Int = 2,
        onProgress: (String, Int) -> Unit = { _, _ -> },
    ): List<CrawledPage> = withContext(Dispatchers.IO) {
        val normalized = normalizeUrl(startUrl) ?: return@withContext emptyList()
        val seedDomain = extractDomain(normalized) ?: return@withContext emptyList()

        val visited = HashSet<String>()
        val results = ArrayList<CrawledPage>()

        // BFS queue: (url, depth).
        data class Frame(val url: String, val depth: Int)
        val queue = ArrayDeque<Frame>()
        queue.addLast(Frame(normalized, 0))
        visited.add(normalized)

        while (queue.isNotEmpty() && results.size < maxPages) {
            val (url, depth) = queue.removeFirst()

            val page = try {
                fetchPage(url)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "fetch failed: $url — ${e.message}")
                null
            }
            if (page == null) {
                onProgress(url, results.size)
                delay(REQUEST_DELAY_MS)
                continue
            }

            results.add(CrawledPage(url = page.url, title = page.title, text = page.text, fetchedAt = System.currentTimeMillis()))
            onProgress(url, results.size)

            // Don't expand links past maxDepth — we still record the page text,
            // just don't queue its children.
            if (depth < maxDepth && results.size < maxPages) {
                val links = extractLinks(page.html, url, seedDomain)
                for (link in links) {
                    if (link !in visited && results.size + queue.size < maxPages * 2) {
                        visited.add(link)
                        queue.addLast(Frame(link, depth + 1))
                    }
                }
            }
            delay(REQUEST_DELAY_MS)
        }

        results
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private data class FetchedPage(val url: String, val title: String, val text: String, val html: String)

    /**
     * Fetch a single page. Returns null on any failure (network, non-2xx,
     * non-HTML content type, empty body).
     */
    private suspend fun fetchPage(url: String): FetchedPage? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val contentType = resp.header("Content-Type")?.lowercase().orEmpty()
                // Skip non-HTML resources (images, PDFs, JSON, etc.).
                if (HTML_CONTENT_TYPES.none { contentType.contains(it) }) return null
                val html = resp.body?.string() ?: return null
                if (html.isBlank()) return null
                val title = extractTitle(html)
                val text = extractText(html)
                if (text.isBlank()) return null
                return FetchedPage(url, title, text, html)
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "fetchPage error: $url — ${e.message}")
            return null
        }
    }

    /**
     * Normalize a user-supplied URL:
     *   - prepend "https://" if no scheme
     *   - strip trailing slash (unless it's the bare domain root)
     *   - return null if the URL is unusable
     */
    private fun normalizeUrl(input: String): String? {
        var s = input.trim()
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "https://$s"
        // Strip trailing slash unless it's just "https://host"
        if (s.count { it == '/' } > 3 && s.endsWith("/")) s = s.dropLast(1)
        // Validate it parses.
        return try {
            URI(s); s
        } catch (_: Exception) {
            null
        }
    }

    /** Extract "example.com" from a URL (host only, lowercased). */
    private fun extractDomain(url: String): String? = try {
        URI(url).host?.lowercase()
    } catch (_: Exception) {
        null
    }

    /** Pull the contents of `<title>…</title>`. Falls back to "" if absent. */
    private fun extractTitle(html: String): String {
        val m = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)
        val raw = m?.groupValues?.lastOrNull().orEmpty()
        return decodeEntities(stripTags(raw)).trim().ifEmpty { "" }
    }

    /**
     * Extract visible text:
     *   1. Drop boilerplate tags (script/style/nav/footer/header/aside/form/noscript).
     *   2. Convert <br>, <p>, </p>, </li>, </h1..6> to newlines so paragraphs survive.
     *   3. Strip remaining tags.
     *   4. Decode HTML entities.
     *   5. Collapse runs of whitespace per line, trim trailing blank lines.
     */
    private fun extractText(html: String): String {
        var s = html
        for (tag in BOILERPLATE_TAGS) {
            s = Regex("(?is)<$tag\\b[^>]*>.*?</$tag>").replace(s, " ")
            // Self-closed / unclosed variants like <nav .../> or bare <nav> at EOF.
            s = Regex("(?is)<$tag\\b[^>]*/?>").replace(s, " ")
        }
        // Treat common block closers as paragraph breaks.
        s = Regex("(?i)</(p|div|li|h[1-6]|tr|td|th|section|article|blockquote)>").replace(s, "\n")
        s = Regex("(?i)<br\\s*/?>").replace(s, "\n")
        s = stripTags(s)
        s = decodeEntities(s)
        // Collapse runs of spaces/tabs but keep newlines.
        s = s.replace(Regex("[ \t]+"), " ")
        s = s.replace(Regex("\n[ \t]+"), "\n")
        s = s.replace(Regex("\n{3,}"), "\n\n")
        return s.trim()
    }

    /** Strip every `<…>` tag from the input. */
    private fun stripTags(s: String): String = Regex("(?s)<[^>]+>").replace(s, " ")

    /** Decode the handful of HTML entities we care about. */
    private fun decodeEntities(s: String): String = s
        .replace("&nbsp;", " ")
        .replace("&#160;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&mdash;", "—")
        .replace("&ndash;", "–")
        .replace("&hellip;", "…")
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m ->
            m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
        }
        .replace(Regex("&#(\\d+);")) { m ->
            m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
        }

    /**
     * Extract all internal `<a href="…">` URLs from the page.
     *
     * - Relative URLs (e.g. "/faq", "about.html") are resolved against [baseUrl].
     * - Anchors (#…), mailto:, tel:, javascript: are skipped.
     * - External links (different domain) are skipped.
     * - De-duplicated, preserves discovery order.
     */
    private fun extractLinks(html: String, baseUrl: String, seedDomain: String): List<String> {
        val out = LinkedHashSet<String>()
        val base = try { URI(baseUrl) } catch (_: Exception) { return emptyList() }
        val linkRegex = Regex("""(?i)<a\b[^>]*href\s*=\s*["']([^"'#]+)["']""")
        for (m in linkRegex.findAll(html)) {
            val raw = m.groupValues[1].trim()
            if (raw.isEmpty()) continue
            if (raw.startsWith("mailto:") || raw.startsWith("tel:") ||
                raw.startsWith("javascript:") || raw.startsWith("data:")
            ) continue
            val resolved = resolveUrl(base, raw) ?: continue
            val host = extractDomain(resolved) ?: continue
            if (host != seedDomain) continue
            // Strip fragment + trailing slash (non-root).
            val noFrag = resolved.substringBefore('#')
            val cleaned = if (noFrag.count { it == '/' } > 3 && noFrag.endsWith("/")) noFrag.dropLast(1) else noFrag
            out.add(cleaned)
        }
        return out.toList()
    }

    /**
     * Resolve a possibly-relative href against the page's URL.
     * Returns null if the result doesn't parse.
     */
    private fun resolveUrl(base: URI, href: String): String? {
        return try {
            if (href.startsWith("http://") || href.startsWith("https://")) {
                href
            } else if (href.startsWith("//")) {
                "${base.scheme}:$href"
            } else if (href.startsWith("/")) {
                "${base.scheme}://${base.host}${if (base.port > 0) ":${base.port}" else ""}$href"
            } else {
                base.resolve(href).toString()
            }
        } catch (_: Exception) {
            null
        }
    }
}
