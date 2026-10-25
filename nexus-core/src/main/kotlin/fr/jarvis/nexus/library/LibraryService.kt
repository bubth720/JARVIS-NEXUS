package fr.jarvis.nexus.library

import fr.jarvis.nexus.api.*
import fr.jarvis.nexus.rag.*
import fr.jarvis.nexus.store.*

const val LIBRARY_API_VERSION = 1
data class LibraryEntry(val document: DocumentRecord, val name: String?, val type: String?, val bytes: Long,
    val modifiedMs: Long?, val importedMs: Long?, val category: String, val tags: Set<String>,
    val source: SourceRecord, val indexState: String)
data class LocatedEvidence(val hit: SearchHit, val location: String?)
data class EvidenceAssessment(val insufficient: Boolean, val explicitContradiction: Boolean,
    val interpretationRequired: Boolean = true, val automaticContradictionDetection: Boolean = false)
data class LibrarySearch(val evidence: List<LocatedEvidence>, val assessment: EvidenceAssessment)
data class CollectionRemovalPreview(val documents: Int, val fingerprint: String)

/** Same database, source registry, FTS index and citation resolver as NEX-01/03.
 * Every scope is intersected with host authority. Originals are never opened here.
 */
class LibraryService(private val db: NexusStoragePort, private val catalog: KnowledgeCatalogService,
    private val hostScope: () -> SearchScope = { SearchScope(emptySet()) }) {
    private class Refusal(val error: KnowledgeError): RuntimeException()
    private fun scope(request: SearchScope): SearchScope {
        val host = runCatching { hostScope() }.getOrElse { throw Refusal(KnowledgeError.DENIED) }.let { it.copy(collectionIds=it.collectionIds.toSet(),sourceIds=it.sourceIds?.toSet()) }
        scopeCheck(host); scopeCheck(request)
        return SearchScope(request.collectionIds.intersect(host.collectionIds), when {
            host.sourceIds == null -> request.sourceIds?.toSet()
            request.sourceIds == null -> host.sourceIds
            else -> host.sourceIds.intersect(request.sourceIds)
        }).also(::scopeCheck)
    }
    private fun <T> checked(operation: Operation, action: () -> T): CatalogResult<T> = try {
        if (!catalog.permits(operation)) throw Refusal(KnowledgeError.DENIED)
        CatalogResult.Ok(action())
    } catch (e: Refusal) { CatalogResult.Rejected(e.error) }
      catch (e: RagRefusal) { CatalogResult.Rejected(e.error) }
      catch (_: IllegalArgumentException) { CatalogResult.Rejected(KnowledgeError.INVALID_INPUT) }
      catch (_: fr.jarvis.nexus.store.NexusStorageException) { CatalogResult.Rejected(KnowledgeError.STORAGE_FAILURE) }

    fun list(request: SearchScope, afterId: String = "", limit: Int = 100): CatalogResult<List<LibraryEntry>> = checked(Operation.READ) {
        require(limit in 1..200 && afterId.length <= 128)
        val allowed = scope(request)
        db.readSession { c ->
            val sources = allowed.sourceIds
            val clause = "d.collection_id IN (${allowed.collectionIds.joinToString(",") { "?" }})" +
                if (sources != null) " AND s.id IN (${sources.joinToString(",") { "?" }})" else ""
            val values = allowed.collectionIds.sorted() + (sources?.sorted() ?: emptyList()) + listOf(afterId,limit)
            c.query("SELECT d.*,s.id AS source_id,s.uri AS source_uri,s.sha256,s.byte_count AS source_bytes,m.name,m.media_type,m.modified_ms,m.category,m.tags,i.state FROM document d JOIN revision r ON r.document_id=d.id AND r.version=d.version JOIN source s ON s.id=r.source_id JOIN index_state i ON i.document_id=d.id LEFT JOIN document_metadata m ON m.document_id=d.id WHERE d.deleted=0 AND $clause AND d.id>? ORDER BY d.id LIMIT ?", *values.toTypedArray()) { r ->
                LibraryEntry(documentRow(r),r.getString("name"),r.getString("media_type"),r.getLong("source_bytes"),r.getObject("modified_ms")?.let { r.getLong("modified_ms") },r.getObject("imported_at_ms")?.let { r.getLong("imported_at_ms") },r.getString("category") ?: "",(r.getString("tags") ?: "").split('\n').filter { it.isNotEmpty() }.toSet(),SourceRecord(r.getString("source_id"),r.getString("source_uri"),r.getString("sha256"),r.getLong("source_bytes")),r.getString("state"))
            }.also { scope(request); if (!catalog.permits(Operation.READ)) throw Refusal(KnowledgeError.DENIED) }
        }
    }

    fun search(query: KnowledgeQuery, request: SearchScope, limit: Int = 20): CatalogResult<LibrarySearch> = checked(Operation.READ) {
        require(limit in 1..100)
        val frozen = query.copy(mediaTypes=query.mediaTypes.toSet())
        val hits = SearchRepository(db).search(frozen,scope(request),limit)
        val resolver = CitationResolver(db)
        val currentScope = scope(request)
        val located = hits.map { hit ->
            resolver.resolve(hit.citation,currentScope)
            val location = db.readSession { c -> c.query("SELECT location FROM fragment_location WHERE document_id=? AND version=? AND ordinal=?",hit.citation.documentId,hit.citation.version,hit.citation.ordinal) { it.getString(1) }.singleOrNull() }
            LocatedEvidence(hit,location)
        }
        val ids = hits.map { it.citation.documentId }.distinct()
        val contradictory = if (ids.isEmpty()) false else db.readSession { c ->
            c.query("SELECT e.id FROM graph_edge e JOIN document d ON d.id=e.document_id JOIN graph_node a ON a.id=e.from_id JOIN graph_node b ON b.id=e.to_id WHERE e.kind='CONTRADICTS' AND e.origin='EXPLICIT' AND e.state='ACTIVE' AND d.deleted=0 AND d.version=e.document_version AND e.document_id IN (${ids.joinToString(",") { "?" }}) AND a.document_id IN (${ids.joinToString(",") { "?" }}) AND b.document_id IN (${ids.joinToString(",") { "?" }}) LIMIT 1", *(ids+ids+ids).toTypedArray()) { it.getString(1) }.isNotEmpty()
        }
        if (!catalog.permits(Operation.READ)) throw Refusal(KnowledgeError.DENIED)
        LibrarySearch(located,EvidenceAssessment(located.isEmpty() || located.map { it.hit.citation.sourceSha256 }.distinct().size < 2,contradictory))
    }

    /** Preview is a bounded page; count/version confirmation prevents deleting a changed set.
     * Logical deletion only: source files, provenance and revisions are retained.
     */
    private fun removalState(c: NexusSession, collectionId: String): CollectionRemovalPreview {
        val rows=c.query("SELECT id,version FROM document WHERE collection_id=? AND deleted=0 ORDER BY id LIMIT 10001",collectionId) { it.getString(1)+":"+it.getInt(2) }
        if (rows.size>10000) throw Refusal(KnowledgeError.LIMIT_EXCEEDED)
        return CollectionRemovalPreview(rows.size,sha256(rows.joinToString("\n")))
    }
    fun previewRemoval(collectionId: String): CatalogResult<CollectionRemovalPreview> = checked(Operation.READ) {
        val allowed=scope(SearchScope(setOf(collectionId)))
        if(allowed.sourceIds!=null) throw Refusal(KnowledgeError.DENIED)
        db.readSession { removalState(it,collectionId) }
    }
    fun removeCollection(collectionId: String, expected: CollectionRemovalPreview, confirmed: Boolean): CatalogResult<Int> = checked(Operation.WRITE) {
        if (!confirmed || !catalog.permits(Operation.PURGE)) throw Refusal(KnowledgeError.DENIED)
        val allowed = scope(SearchScope(setOf(collectionId)))
        if (allowed.sourceIds != null) throw Refusal(KnowledgeError.DENIED)
        require(expected.documents in 0..10000 && expected.fingerprint.matches(Regex("[0-9a-f]{64}")))
        db.inTransaction { c ->
            if (removalState(c,collectionId) != expected) throw Refusal(KnowledgeError.CONFLICT)
            if (!catalog.permits(Operation.WRITE) || !catalog.permits(Operation.PURGE)) throw Refusal(KnowledgeError.DENIED)
            if(c.query("SELECT id FROM document WHERE collection_id=? AND deleted=0 AND version=2147483647 LIMIT 1",collectionId) { it.getString(1) }.isNotEmpty()) throw Refusal(KnowledgeError.CONFLICT)
            c.execute("INSERT INTO revision SELECT d.id,d.version+1,r.source_id,d.title FROM document d JOIN revision r ON r.document_id=d.id AND r.version=d.version WHERE d.collection_id=? AND d.deleted=0",collectionId)
            c.execute("INSERT INTO fragment SELECT f.document_id,f.version+1,f.ordinal,f.text,f.start_offset,f.end_offset,f.page FROM fragment f JOIN document d ON d.id=f.document_id AND d.version=f.version WHERE d.collection_id=? AND d.deleted=0",collectionId)
            c.execute("INSERT INTO fragment_location SELECT f.document_id,f.version+1,f.ordinal,f.location FROM fragment_location f JOIN document d ON d.id=f.document_id AND d.version=f.version WHERE d.collection_id=? AND d.deleted=0",collectionId)
            c.execute("UPDATE index_state SET state='PENDING' WHERE document_id IN (SELECT id FROM document WHERE collection_id=? AND deleted=0)",collectionId)
            val changed = c.execute("UPDATE document SET deleted=1,version=version+1 WHERE collection_id=? AND deleted=0",collectionId)
            c.execute("INSERT INTO mutation_log(document_id,kind) VALUES(NULL,?)","COLLECTION_HIDDEN:$collectionId:$changed")
            changed
        }
    }
}

/** Optional local model injected by the host. No model, downloads or inference engine.
 * Reranks at most 100 already-authorized lexical hits; this is not semantic recall.
 */
fun interface EmbeddingPort { fun embed(text: String): FloatArray }
class SemanticReranker(private val embeddings: EmbeddingPort?) {
    fun rerank(query: String, hits: List<SearchHit>): CatalogResult<List<SearchHit>> {
        val model = embeddings ?: return CatalogResult.Rejected(KnowledgeError.UNSUPPORTED_FORMAT)
        if (hits.size > 100 || query.length !in 1..256) return CatalogResult.Rejected(KnowledgeError.INVALID_INPUT)
        return try {
            val q = model.embed(query)
            require(q.size in 1..1024 && q.all { it.isFinite() })
            fun similarity(text: String): Double {
                val v = model.embed(text.take(8192)); require(v.size == q.size && v.all { it.isFinite() })
                var dot = 0.0; var qa = 0.0; var va = 0.0
                for (i in q.indices) { dot += q[i].toDouble()*v[i]; qa += q[i].toDouble()*q[i]; va += v[i].toDouble()*v[i] }
                require(qa > 0 && va > 0)
                return dot / kotlin.math.sqrt(qa*va)
            }
            CatalogResult.Ok(hits.map { it to similarity(it.text) }.sortedByDescending { it.second }.map { it.first })
        } catch (_: Exception) { CatalogResult.Rejected(KnowledgeError.EXTRACTION_FAILED) }
    }
}
