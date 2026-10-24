package fr.jarvis.nexus.importing

import fr.jarvis.nexus.api.KnowledgeError
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Adds formats absent from SERVICES. No PDF/text parser fork, macros, OCR or network.
 * ZIP is a bounded batch of text members rendered into one document, never files.
 * Nested containers, encrypted ZIP, external entities and archive paths are refused.
 */
class StructuredParser(private val existing: ParserPort? = null) : ParserPort {
    fun registry(): ParserRegistry = ParserRegistry(buildMap {
        listOf("docx", "csv", "tsv", "xml", "zip").forEach { put(it, this@StructuredParser) }
        if (existing != null) listOf("txt", "md", "pdf").forEach { put(it, existing) }
    })
    private fun refuse(error: KnowledgeError = KnowledgeError.INVALID_INPUT): Nothing = throw ParserFailure(error)
    private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")

    override fun parse(name: String, bytes: ByteArray, cancellation: Cancellation): List<ExtractedPage> {
        cancellation.check()
        if (bytes.size > 2 * 1024 * 1024) refuse(KnowledgeError.LIMIT_EXCEEDED)
        val result = when (name.substringAfterLast('.').lowercase(java.util.Locale.ROOT)) {
            "docx" -> {
                val entries = unzip(bytes, cancellation)
                if ("[Content_Types].xml" !in entries || entries.keys.any { it.endsWith("vbaProject.bin", true) }) refuse()
                val xml = entries["word/document.xml"] ?: refuse()
                val document = xml(xml)
                val paragraphs = document.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "p")
                val text = buildString {
                    for (i in 0 until paragraphs.length) {
                        cancellation.check()
                        val children = (paragraphs.item(i) as org.w3c.dom.Element).getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "t")
                        for (j in 0 until children.length) append(children.item(j).textContent)
                        append('\n')
                        if (length > 250_000) refuse(KnowledgeError.LIMIT_EXCEEDED)
                    }
                }
                listOf(ExtractedPage(null, text, "word/document.xml; paragraphs separated by LF; UTF-16 offsets"))
            }
            "xml" -> listOf(ExtractedPage(null, xml(bytes).documentElement.textContent, "XML documentElement textContent; UTF-16 offsets"))
            "csv", "tsv" -> {
                val text = utf8(bytes)
                validateTable(text, if (name.endsWith(".tsv", true)) '\t' else ',', cancellation)
                listOf(ExtractedPage(null, text, "Original UTF-8 table; UTF-16 offsets (quoted newlines retained)"))
            }
            "zip" -> {
                val entries = unzip(bytes, cancellation)
                if (entries.isEmpty() || entries.size > 32) refuse(KnowledgeError.LIMIT_EXCEEDED)
                val text = buildString {
                    entries.forEach { (path, data) ->
                        cancellation.check()
                        val extension = path.substringAfterLast('.').lowercase(java.util.Locale.ROOT)
                        if (extension !in setOf("txt", "md", "csv", "tsv")) refuse(KnowledgeError.UNSUPPORTED_FORMAT)
                        val extracted = if (extension in setOf("txt", "md"))
                            (existing ?: refuse(KnowledgeError.UNSUPPORTED_FORMAT)).parse(path, data, cancellation).joinToString("\n") { it.text }
                        else parse(path, data, cancellation).single().text
                        append("\n--- member: ").append(path).append(" ---\n").append(extracted)
                        if (length > 250_000) refuse(KnowledgeError.LIMIT_EXCEEDED)
                    }
                }
                listOf(ExtractedPage(null, text, "ZIP member headers retained; offsets in extracted batch"))
            }
            else -> (existing ?: refuse(KnowledgeError.UNSUPPORTED_FORMAT)).parse(name, bytes, cancellation)
        }
        if (result.sumOf { it.text.length.toLong() } > 250_000) refuse(KnowledgeError.LIMIT_EXCEEDED)
        cancellation.check()
        return result
    }

    private fun xml(bytes: ByteArray): org.w3c.dom.Document {
        // Reject DTD at parser level; external resolution is disabled independently.
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        factory.isXIncludeAware = false; factory.isExpandEntityReferences = false
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun unzip(bytes: ByteArray, stop: Cancellation): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>(); var total = 0; var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            while (true) {
                stop.check(); val entry = input.nextEntry ?: break
                if (++count > 128) refuse(KnowledgeError.LIMIT_EXCEEDED)
                val path = entry.name
                if (path.length !in 1..512 || path.startsWith('/') || '\\' in path || ':' in path || path.any { it < ' ' } || path.split('/').any { it in setOf(".", "..") }) refuse()
                if (entry.isDirectory) continue
                if (path in entries) refuse()
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    stop.check(); val n = input.read(buffer); if (n < 0) break
                    total += n
                    if (total > 4 * 1024 * 1024 || out.size() + n > 2 * 1024 * 1024) refuse(KnowledgeError.LIMIT_EXCEEDED)
                    out.write(buffer, 0, n)
                }
                val content = out.toByteArray()
                if (entry.compressedSize > 0 && content.size.toLong() > maxOf(4096L, entry.compressedSize * 100)) refuse(KnowledgeError.LIMIT_EXCEEDED)
                entries[path] = content
            }
        }
        return entries
    }

    private fun validateTable(text: String, separator: Char, stop: Cancellation) {
        var quoted = false; var atStart = true; var afterQuote = false; var rows = 1; var columns = 1; var i = 0
        while (i < text.length) {
            if (i % 4096 == 0) stop.check()
            val ch = text[i++]
            if (ch == '\u0000') refuse()
            if (quoted) {
                if (ch == '"') {
                    if (i < text.length && text[i] == '"') i++
                    else { quoted = false; afterQuote = true }
                }
            } else when {
                ch == '"' && atStart -> { quoted = true; atStart = false }
                ch == separator -> { columns++; atStart = true; afterQuote = false }
                ch == '\n' || ch == '\r' -> { if (ch == '\r' && i < text.length && text[i] == '\n') i++; rows++; columns = 1; atStart = true; afterQuote = false }
                afterQuote || ch == '"' -> refuse()
                else -> atStart = false
            }
            if (rows > 10000 || columns > 256) refuse(KnowledgeError.LIMIT_EXCEEDED)
        }
        if (quoted) refuse()
    }
}
