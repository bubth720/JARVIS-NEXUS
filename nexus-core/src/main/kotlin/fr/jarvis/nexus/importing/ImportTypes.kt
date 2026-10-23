package fr.jarvis.nexus.importing

import fr.jarvis.nexus.api.KnowledgeError
import java.util.concurrent.atomic.AtomicBoolean

data class ImportSource(val collectionId: String, val uri: String, val name: String, val title: String, val expectedVersion: Int? = null)
enum class ImportState { QUEUED, PROCESSING, CANCELLING, CANCELLED, INTERRUPTED, FAILED, COMPLETED }
data class ImportJob(val id: String, val source: ImportSource, val sha256: String, val byteCount: Int, val state: ImportState, val documentId: String?, val error: KnowledgeError?)
/** Raw parser text; offsets always refer to this text's UTF-16 code units, not PDF bytes. */
data class ExtractedPage(val page: Int?, val text: String)
fun interface ParserPort { fun parse(name: String, bytes: ByteArray, cancellation: Cancellation): List<ExtractedPage> }
fun interface Cancellation {
    fun requested(): Boolean
    fun check() { if (requested()) throw ImportCancelled() }
}
class CancellationToken : Cancellation {
    private val cancelled = AtomicBoolean()
    fun cancel() { cancelled.set(true) }
    override fun requested() = cancelled.get()
}
class ImportCancelled : RuntimeException()
class ParserFailure(val error: KnowledgeError) : RuntimeException()
class ParserRegistry(private val parsers: Map<String, ParserPort>) {
    private val entries = parsers.mapKeys { it.key.lowercase(java.util.Locale.ROOT) }.toMap()
    init { require(entries.keys.all { it in setOf("txt", "md", "pdf") }) }
    fun find(name: String): ParserPort = entries[name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)]
        ?: throw ParserFailure(KnowledgeError.UNSUPPORTED_FORMAT)
}
