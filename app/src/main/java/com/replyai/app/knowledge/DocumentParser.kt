package com.replyai.app.knowledge

import android.content.Context
import com.replyai.app.util.LogCapture
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.w3c.dom.Node
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * DocumentParser — extracts plain text from many file types so the user can
 * drop arbitrary knowledge files (PDF, DOCX, TXT, MD, HTML, CSV, JSON, RTF…)
 * into a bot's knowledge base.
 *
 * Why an object: parsing is stateless once PDFBox is initialised; a single
 * instance keeps memory pressure low and the init-once pattern simple.
 *
 * Supported formats:
 *   - .txt, .md, .csv, .json, .log  → read as UTF-8 text
 *   - .html, .htm                    → strip HTML tags, keep text
 *   - .pdf                            → PDFBox text extraction (needs [init])
 *   - .docx                           → unzip + parse word/document.xml
 *   - .doc                            → NOT supported (legacy binary), returns null
 *   - .rtf                            → strip RTF control words
 *   - Unknown                         → try UTF-8 read; fall back to null
 *
 * All extractors are wrapped in try/catch: a corrupt or password-protected
 * file yields `null` + a log line, never a crash. Callers should treat null
 * as "couldn't parse this file" and surface a friendly message.
 *
 * NOTE: [extractText] is `suspend` because PDF/DOCX parsing can be slow on
 * large files; callers should already be on a background dispatcher.
 */
object DocumentParser {

    private const val TAG = "DocumentParser"

    /** Extensions the user is allowed to pick in the file picker. */
    val SUPPORTED_EXTENSIONS = listOf(
        "txt", "md", "csv", "json", "log",
        "html", "htm",
        "pdf",
        "docx",
        "rtf",
    )

    /**
     * MIME types for the file picker. We use the wildcard MIME type because
     * Android's MIME inference for things like .md / .csv / .log is
     * inconsistent across OEMs, and we filter by extension ourselves.
     */
    val SUPPORTED_MIME = "*/*"

    /** App context kept for PDFBox resource loader. Set once via [init]. */
    @Volatile
    private var appContext: Context? = null

    /** True after PDFBox has been initialised. */
    @Volatile
    private var pdfBoxInitialised = false

    /**
     * Initialise PDFBox's native resource loader. MUST be called once from
     * [com.replyai.app.ReplyAIApp.onCreate] before any PDF is parsed.
     *
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    fun init(context: Context) {
        if (pdfBoxInitialised) return
        try {
            PDFBoxResourceLoader.init(context)
            appContext = context.applicationContext
            pdfBoxInitialised = true
            LogCapture.i(TAG, "PDFBox initialised")
        } catch (t: Throwable) {
            // Don't crash the app if PDFBox fails to load native libs — we'll
            // just return null for PDFs. Other formats still work.
            LogCapture.e(TAG, "PDFBox init failed — PDF parsing disabled", t)
        }
    }

    /**
     * Extract plain text from a file.
     *
     * @param filePath absolute path to the file
     * @return extracted text (possibly empty), or `null` if parsing failed
     */
    suspend fun extractText(filePath: String): String? {
        if (filePath.isBlank()) return null
        val file = File(filePath)
        if (!file.exists() || !file.isFile) {
            LogCapture.w(TAG, "File not found: $filePath")
            return null
        }

        val ext = file.extension.lowercase().trim()
        return try {
            when (ext) {
                "txt", "md", "csv", "json", "log" -> readPlainText(file)
                "html", "htm"                     -> parseHtml(file)
                "pdf"                             -> parsePdf(file)
                "docx"                            -> parseDocx(file)
                "doc"                             -> {
                    // Legacy .doc is a binary OLE format — we don't support it.
                    // (Apache POI's HWPF could do it, but POI is heavy + slow
                    // on Android. Tell the user to convert to .docx.)
                    LogCapture.w(TAG, ".doc (legacy binary) is not supported — convert to .docx")
                    null
                }
                "rtf"                             -> parseRtf(file)
                else                              -> {
                    // Unknown extension — try UTF-8 read, but bail out if it
                    // looks binary (lots of replacement chars / control bytes).
                    val raw = tryReadUtf8(file)
                    if (raw == null) {
                        LogCapture.w(TAG, "Unknown extension '$ext' and file looks binary")
                        null
                    } else {
                        raw
                    }
                }
            }
        } catch (t: Throwable) {
            LogCapture.e(TAG, "Failed to parse '$filePath' (ext=$ext)", t)
            null
        }
    }

    // ---------------------------------------------------------------------------
    // Plain-text family: txt, md, csv, json, log
    // ---------------------------------------------------------------------------

    private fun readPlainText(file: File): String =
        file.readText(Charsets.UTF_8)

    /** Read file as UTF-8; return null if it looks binary. */
    private fun tryReadUtf8(file: File): String? {
        val bytes = file.readBytes()
        if (bytes.isEmpty()) return ""
        // Quick binary sniff: count non-printable bytes (excluding common
        // whitespace). >5% non-printable => binary.
        var nonPrintable = 0
        val sampleSize = minOf(bytes.size, 4096)
        for (i in 0 until sampleSize) {
            val b = bytes[i].toInt() and 0xFF
            val printable = b in 0x09..0x0D || b in 0x20..0x7E || b >= 0x80
            if (!printable) nonPrintable++
        }
        val ratio = nonPrintable.toDouble() / sampleSize
        if (ratio > 0.05) return null
        return String(bytes, Charsets.UTF_8)
    }

    // ---------------------------------------------------------------------------
    // HTML
    // ---------------------------------------------------------------------------

    private fun parseHtml(file: File): String {
        val raw = file.readText(Charsets.UTF_8)
        return stripHtml(raw)
    }

    /**
     * Strip HTML to plain text:
     *   1. Drop <script> / <style> / <noscript> / comments (including their contents).
     *   2. Insert whitespace before block-level tags so words don't glue together.
     *   3. Remove all remaining tags via `<[^>]+>`.
     *   4. Decode common HTML entities.
     *   5. Collapse runs of whitespace.
     */
    internal fun stripHtml(html: String): String {
        // (1) Drop script + style contents. DOTALL so `.` matches newlines.
        val noScript = html
            .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
            .replace(Regex("(?is)<noscript[^>]*>.*?</noscript>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
        // (2) Convert <br>, </p>, </div>, </li> etc. to whitespace.
        val withBreaks = noScript
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|li|h[1-6]|tr|td|th)>"), "\n")
            .replace(Regex("(?i)<li[^>]*>"), "- ")
        // (3) Strip remaining tags.
        val noTags = withBreaks.replace(Regex("<[^>]+>"), "")
        // (4) Decode entities.
        val decoded = decodeHtmlEntities(noTags)
        // (5) Collapse whitespace (preserve newlines as paragraph breaks).
        return collapseWhitespace(decoded)
    }

    /** Decode the most common HTML entities. Not exhaustive — covers the 95% case. */
    private fun decodeHtmlEntities(s: String): String {
        return s
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&copy;", "(c)")
            .replace("&reg;", "(r)")
            .replace("&trade;", "(tm)")
            .replace("&hellip;", "...")
            .replace("&mdash;", "--")
            .replace("&ndash;", "-")
            .replace(Regex("&#x([0-9a-fA-F]+);")) { m ->
                m.groupValues[1].toIntOrNull(16)?.let { Character.toChars(it).concatToString() } ?: m.value
            }
            .replace(Regex("&#([0-9]+);")) { m ->
                m.groupValues[1].toIntOrNull()?.let { Character.toChars(it).concatToString() } ?: m.value
            }
    }

    /** Collapse runs of spaces/tabs to a single space; collapse 3+ newlines to 2. */
    private fun collapseWhitespace(s: String): String {
        return s
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" ?\\n ?"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    // ---------------------------------------------------------------------------
    // PDF (via PDFBox)
    // ---------------------------------------------------------------------------

    private fun parsePdf(file: File): String? {
        if (!pdfBoxInitialised) {
            LogCapture.w(TAG, "PDF parsing requested but PDFBox not initialised")
            return null
        }

        var doc: PDDocument? = null
        return try {
            // If the PDF is encrypted, try unlocking with an empty password
            // first (some PDFs are "encrypted" with no user password).
            doc = PDDocument.load(file)
            if (doc.isEncrypted) {
                try { doc.close() } catch (_: Throwable) {}
                doc = PDDocument.load(file, "")  // empty password attempt
                if (doc.isEncrypted) {
                    LogCapture.w(TAG, "PDF is encrypted with a non-empty password — cannot extract text")
                    return null
                }
            }
            val stripper = PDFTextStripper().apply {
                // Be tolerant of malformed PDFs.
                sortByPosition = true
                addMoreFormatting = false
            }
            stripper.getText(doc).trim().ifEmpty { null }
        } catch (t: Throwable) {
            LogCapture.e(TAG, "PDFBox failed on ${file.name}", t)
            null
        } finally {
            try { doc?.close() } catch (_: Throwable) {}
        }
    }

    // ---------------------------------------------------------------------------
    // DOCX (Office Open XML — it's a ZIP with word/document.xml inside)
    // ---------------------------------------------------------------------------

    private fun parseDocx(file: File): String? {
        return try {
            FileInputStream(file).use { fis ->
                ZipInputStream(fis).use { zis ->
                    var entry = zis.nextEntry
                    var documentXml: ByteArray? = null
                    while (entry != null) {
                        if (entry.name.equals("word/document.xml", ignoreCase = true)) {
                            documentXml = zis.readBytes()
                            break
                        }
                        entry = zis.nextEntry
                    }
                    if (documentXml == null) {
                        LogCapture.w(TAG, "${file.name} is not a valid .docx (no word/document.xml)")
                        return null
                    }
                    extractTextFromDocxXml(documentXml)
                }
            }
        } catch (t: Throwable) {
            LogCapture.e(TAG, "Failed to parse .docx ${file.name}", t)
            null
        }
    }

    /**
     * Walk `word/document.xml` and pull text out of every `<w:t>` element.
     *
     * Also honours `<w:p>` (paragraph — insert newline) and `<w:br>` / `<w:tab>`
     * (insert break / tab) so the output isn't a single run-on line.
     */
    private fun extractTextFromDocxXml(xml: ByteArray): String {
        val factory = DocumentBuilderFactory.newInstance().apply {
            // Be permissive — real-world DOCX files are messy.
            isNamespaceAware = true
            isValidating = false
            try { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) } catch (_: Throwable) {}
            try { setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Throwable) {}
            try { setFeature("http://xml.org/sax/features/external-parameter-entities", false) } catch (_: Throwable) {}
        }
        val builder = factory.newDocumentBuilder()
        val dom = builder.parse(xml.inputStream())
        val sb = StringBuilder()
        walkDocxNode(dom.documentElement, sb)
        return collapseWhitespace(sb.toString())
    }

    private fun walkDocxNode(node: Node, sb: StringBuilder) {
        val name = node.localName ?: node.nodeName.substringAfter(':')
        when (name) {
            "t" -> {
                // <w:t> is a text run — append its content.
                sb.append(node.textContent)
            }
            "tab" -> sb.append('\t')
            "br" -> sb.append('\n')
            "p" -> {
                // Paragraph: descend, then add a newline at the end.
                var child = node.firstChild
                while (child != null) {
                    walkDocxNode(child, sb)
                    child = child.nextSibling
                }
                sb.append('\n')
                return
            }
        }
        var child = node.firstChild
        while (child != null) {
            walkDocxNode(child, sb)
            child = child.nextSibling
        }
    }

    // ---------------------------------------------------------------------------
    // RTF (Rich Text Format — plain-text with control words)
    // ---------------------------------------------------------------------------

    private fun parseRtf(file: File): String? {
        return try {
            val raw = file.readText(Charsets.UTF_8)
            stripRtf(raw)
        } catch (t: Throwable) {
            LogCapture.e(TAG, "Failed to parse .rtf ${file.name}", t)
            null
        }
    }

    /**
     * Strip RTF control words and braces, keep the visible text.
     *
     * RTF control words look like `\word123 ` (optional signed integer + space)
     * or `\word`. We also handle:
     *   - `\\`, `\{`, `\}` escaped characters → keep literal `\{` `\}` `\\`
     *   - Unicode escapes `\u1234?` → convert the code point
     *   - Hex escapes `\'XX` → convert (Latin-1)
     *   - `\par` / `\line` / `\row` → newline; `\tab` → tab; `\sect` / `\page` → paragraph break
     */
    internal fun stripRtf(rtf: String): String {
        val sb = StringBuilder(rtf.length)
        var i = 0
        val n = rtf.length
        while (i < n) {
            val c = rtf[i]
            when {
                c == '\\' && i + 1 < n -> {
                    val next = rtf[i + 1]
                    if (next.isLetter()) {
                        // Control word: \word[optional -?digits][trailing space]
                        var j = i + 1
                        while (j < n && rtf[j].isLetter()) j++
                        // Optional signed integer parameter
                        val paramStart = j
                        if (j < n && rtf[j] == '-') j++
                        while (j < n && rtf[j].isDigit()) j++
                        val param = if (j > paramStart) rtf.substring(paramStart, j) else null
                        // Trailing space terminates the control word and is consumed.
                        if (j < n && rtf[j] == ' ') j++
                        val ctrl = rtf.substring(i + 1, paramStart)
                        // Special-case unicode escapes: \uNNNN?
                        if (ctrl == "u" && param != null) {
                            val code = param.toIntOrNull()
                            if (code != null) {
                                // RTF uses signed 16-bit; convert to unsigned.
                                val unsigned = code and 0xFFFF
                                sb.append(unsigned.toChar())
                                // Skip the fallback char after \uN? (usually '?')
                                if (j < n && rtf[j] == '?') j++
                                else if (j < n && rtf[j] == ' ') j++
                            }
                        }
                        // Paragraph / line / tab control words → whitespace.
                        when (ctrl) {
                            "par", "line", "row" -> sb.append('\n')
                            "tab" -> sb.append('\t')
                            "sect", "page" -> sb.append("\n\n")
                        }
                        i = j
                    } else if (next == '\\' || next == '{' || next == '}') {
                        // Escaped literal char.
                        sb.append(next)
                        i += 2
                    } else if (next == '*') {
                        // \*\destination → skip the backslash; brace matcher will handle the group.
                        i += 2
                    } else if (next == '\'') {
                        // \'XX hex escape — interpret as Latin-1 byte.
                        if (i + 3 < n && rtf[i + 2].isLetterOrDigit() && rtf[i + 3].isLetterOrDigit()) {
                            val hex = "${rtf[i + 2]}${rtf[i + 3]}"
                            hex.toIntOrNull(16)?.let { byte ->
                                sb.append(byte.toChar())
                            }
                            i += 4
                        } else {
                            i += 2
                        }
                    } else if (next == '~') {
                        sb.append(' ')
                        i += 2
                    } else if (next == '_' || next == '-') {
                        // \_ = underscore; \- = optional hyphen (drop)
                        if (next == '_') sb.append('_')
                        i += 2
                    } else {
                        // Single-char control symbol we don't recognise — drop backslash.
                        i += 2
                    }
                }
                c == '{' || c == '}' -> {
                    // Brace: group delimiter. Skip.
                    i++
                }
                c == '\r' || c == '\n' -> {
                    // Raw newline in RTF source is ignored by readers; treat as nothing.
                    i++
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return collapseWhitespace(sb.toString())
    }
}
