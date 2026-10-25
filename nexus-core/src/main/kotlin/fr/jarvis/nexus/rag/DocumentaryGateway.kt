package fr.jarvis.nexus.rag

import fr.jarvis.nexus.api.*
import fr.jarvis.nexus.store.*

const val DOCUMENTARY_GATEWAY_VERSION = 1
enum class DocumentaryRefusal { DENIED, CANCELLED, DEADLINE_EXCEEDED, INDEX_MISSING, UNSUPPORTED, BYTE_BUDGET, CITATION_MISMATCH, NEXUS_REJECTED }
sealed interface DocumentaryResult {
    data class Ready(val bundle: ContextBundle) : DocumentaryResult
    data class Refused(val reason: DocumentaryRefusal, val knowledgeError: KnowledgeError? = null) : DocumentaryResult
}
/** Trusted host callbacks, never deserialized from documentary content. CORE owns
 * session/request/generation correlation and supplies the corresponding closure.
 * Cancellation is logical; it says nothing about JNI, worker completion or unload.
 */
class DocumentaryCall(val deadlineMonotonicMs: Long, val authorized: () -> Boolean = { false },
    val cancelled: () -> Boolean = { false }) {
    init { require(deadlineMonotonicMs >= 0) }
}

/** In-process adapter around the real NEX-03 port, no transport or new authority.
 * Missing index is explicit; late/revoked/cancelled evidence cannot be returned READY.
 */
class DocumentaryGateway(private val storage: NexusStoragePort, private val catalog: KnowledgeCatalogService,
    private val hostScope: () -> SearchScope = { SearchScope(emptySet()) },
    private val monotonicMs: () -> Long = { System.nanoTime()/1_000_000 }) {
    private class Refusal(val reason: DocumentaryRefusal) : RuntimeException()
    private val rag = LocalRagService(storage,catalog,hostScope)
    private fun check(call: DocumentaryCall) {
        if (!catalog.permits(Operation.READ) || !runCatching(call.authorized).getOrDefault(false)) throw Refusal(DocumentaryRefusal.DENIED)
        if (runCatching(call.cancelled).getOrDefault(true)) throw Refusal(DocumentaryRefusal.CANCELLED)
        if (monotonicMs() >= call.deadlineMonotonicMs) throw Refusal(DocumentaryRefusal.DEADLINE_EXCEEDED)
    }
    private fun allowed(request: SearchScope): SearchScope {
        val host=runCatching(hostScope).getOrElse { throw Refusal(DocumentaryRefusal.DENIED) }
            .let { it.copy(collectionIds=it.collectionIds.toSet(),sourceIds=it.sourceIds?.toSet()) }
        scopeCheck(host);scopeCheck(request)
        val sources=when { host.sourceIds==null -> request.sourceIds;request.sourceIds==null -> host.sourceIds;else -> host.sourceIds.intersect(request.sourceIds) }
        return SearchScope(host.collectionIds.intersect(request.collectionIds),sources).also(::scopeCheck)
    }
    private fun requireIndex(scope: SearchScope) {
        storage.readSession { c ->
            val tables=c.query("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('fragment_fts','fragment_vocab','index_state')") { it.getString(1) }.toSet()
            if(tables.size!=3) throw Refusal(DocumentaryRefusal.INDEX_MISSING)
            val clauses=mutableListOf("d.deleted=0","d.collection_id IN (${scope.collectionIds.joinToString(",") { "?" }})")
            val parameters=scope.collectionIds.sorted().toMutableList<Any?>()
            scope.sourceIds?.let { sources ->
                clauses+="r.source_id IN (${sources.joinToString(",") { "?" }})";parameters.addAll(sources.sorted())
            }
            val missing=c.query("SELECT d.id FROM document d JOIN revision r ON r.document_id=d.id AND r.version=d.version LEFT JOIN index_state i ON i.document_id=d.id WHERE ${clauses.joinToString(" AND ")} AND (i.state IS NULL OR i.state!='READY' OR i.indexed_version!=d.version) LIMIT 1",*parameters.toTypedArray()) { it.getString(1) }
            if(missing.isNotEmpty()) throw Refusal(DocumentaryRefusal.INDEX_MISSING)
        }
    }
    fun retrieve(query: KnowledgeQuery, request: SearchScope, call: DocumentaryCall,
        limits: ContextLimits = ContextLimits(), maxUtf8Bytes: Int = 8192): DocumentaryResult = try {
        require(maxUtf8Bytes in 256..32000)
        check(call)
        val frozen=request.copy(collectionIds=request.collectionIds.toSet(),sourceIds=request.sourceIds?.toSet())
        val frozenQuery=query.copy(mediaTypes=query.mediaTypes.toSet())
        // One SQLite snapshot for index inspection, retrieval and all citation
        // resolutions. Host callbacks must not start a nested NEXUS transaction.
        val returned=storage.inTransaction {
        check(call);requireIndex(allowed(frozen));check(call)
        when(val result=rag.retrieve(frozenQuery,frozen,limits)) {
            is CatalogResult.Rejected -> rejected(result.error)
            is CatalogResult.Ok -> {
                val bundle=result.value
                check(call)
                if(bundle.asUntrustedJson().toByteArray(Charsets.UTF_8).size>maxUtf8Bytes) throw Refusal(DocumentaryRefusal.BYTE_BUDGET)
                bundle.items.forEach { item ->
                    val resolved=item.citations.map { citation ->
                        check(call)
                        when(val text=rag.resolve(citation,frozen)) {
                            is CatalogResult.Ok -> text.value
                            is CatalogResult.Rejected -> throw RagRefusal(text.error)
                        }.also { check(call) }
                    }
                    val valid=when(item.grouping) {
                        EvidenceGrouping.SINGLE -> resolved.size==1 && resolved.single()==item.text
                        EvidenceGrouping.IDENTICAL_TEXT -> resolved.isNotEmpty() && resolved.all { it==item.text }
                        EvidenceGrouping.CONTIGUOUS_FRAGMENTS -> resolved.isNotEmpty() && resolved.joinToString("")==item.text
                    }
                    if(!valid) throw Refusal(DocumentaryRefusal.CITATION_MISMATCH)
                }
                requireIndex(allowed(frozen));check(call)
                DocumentaryResult.Ready(bundle)
            }
        }
        }
        check(call)
        returned
    } catch(e: Refusal) { DocumentaryResult.Refused(e.reason) }
      catch(e: RagRefusal) { rejected(e.error) }
      catch(_: IllegalArgumentException) { rejected(KnowledgeError.INVALID_INPUT) }
      catch(_: NexusStorageException) { rejected(KnowledgeError.STORAGE_FAILURE) }
    private fun rejected(error: KnowledgeError) = DocumentaryResult.Refused(
        when(error) { KnowledgeError.DENIED -> DocumentaryRefusal.DENIED; KnowledgeError.UNSUPPORTED_FORMAT -> DocumentaryRefusal.UNSUPPORTED; else -> DocumentaryRefusal.NEXUS_REJECTED },error)
}
