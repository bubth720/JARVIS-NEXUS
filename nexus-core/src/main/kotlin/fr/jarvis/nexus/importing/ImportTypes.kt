package fr.jarvis.nexus.importing

import fr.jarvis.nexus.api.KnowledgeError
import java.util.concurrent.atomic.AtomicBoolean

data class ImportSource(val collectionId: String, val uri: String, val name: String, val title: String, val expectedVersion: Int? = null)
data class ImportMetadata(val modifiedMs: Long? = null, val category: String = "", val tags: Set<String> = emptySet()) {
    init {
        require(modifiedMs == null || modifiedMs >= 0)
        require(category.length <= 128 && category.none { it < ' ' })
        require(tags.size <= 32 && tags.all { it.length in 1..64 && it.none { c -> c < ' ' } })
    }
}
enum class ImportState { QUEUED, PROCESSING, CANCELLING, CANCELLED, INTERRUPTED, FAILED, COMPLETED }
data class ImportJob(val id: String, val source: ImportSource, val sha256: String, val byteCount: Int, val state: ImportState, val documentId: String?, val error: KnowledgeError?)
/** Raw parser text; offsets always refer to this text's UTF-16 code units, not PDF bytes. */
data class ExtractedPage(val page: Int?, val text: String, val location: String? = null)
fun interface ParserPort { fun parse(name: String, bytes: ByteArray, cancellation: Cancellation): List<ExtractedPage> }
fun interface Cancellation {
    fun requested(): Boolean
    fun check() { if (requested() || Thread.currentThread().isInterrupted) throw ImportCancelled() }
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
    init { require(entries.keys.all { it in setOf("txt", "md", "pdf", "docx", "csv", "tsv", "xml", "zip") }) }
    fun find(name: String): ParserPort = entries[name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)]
        ?: throw ParserFailure(KnowledgeError.UNSUPPORTED_FORMAT)
}

enum class ImportEventKind { QUEUED, PROCESSING, CANCEL_REQUESTED, INTERRUPTED, COMPLETED, FAILURE_OBSERVED, CANCEL_OBSERVED }
data class ImportEvent(val sequence: Long, val jobId: String, val kind: ImportEventKind, val state: ImportState, val error: KnowledgeError?)
