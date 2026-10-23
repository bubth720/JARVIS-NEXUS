package fr.jarvis.nexus.search

import fr.jarvis.nexus.api.*
import fr.jarvis.nexus.store.*

data class IndexedHit(val documentId: String, val version: Int, val ordinal: Int, val text: String, val start: Int, val end: Int, val page: Int?)
/** Actual SQLite FTS5, not a lexical mock. Only current, READY, undeleted versions are visible. */
class FtsIndex(private val database: NexusDatabase, private val catalog: KnowledgeCatalogService) {
    fun search(query: String, limit: Int = 20): CatalogResult<List<IndexedHit>> {
        if (!catalog.permits(Operation.READ)) return CatalogResult.Rejected(KnowledgeError.DENIED)
        if (query.length !in 1..256 || limit !in 1..100) return CatalogResult.Rejected(KnowledgeError.INVALID_INPUT)
        val words = Regex("[\\p{L}\\p{N}]+").findAll(java.text.Normalizer.normalize(query, java.text.Normalizer.Form.NFC)).map { it.value }.take(13).toList()
        if (words.isEmpty() || words.size > 12) return CatalogResult.Rejected(KnowledgeError.INVALID_INPUT)
        val match = words.joinToString(" AND ") { "\"$it\"" }
        return try { CatalogResult.Ok(database.read { c -> c.query(
            "SELECT f.* FROM fragment_fts x JOIN fragment f ON f.document_id=x.document_id AND f.version=x.version AND f.ordinal=x.ordinal JOIN document d ON d.id=f.document_id JOIN index_state i ON i.document_id=d.id WHERE fragment_fts MATCH ? AND d.deleted=0 AND d.version=f.version AND i.state='READY' AND i.indexed_version=d.version ORDER BY bm25(fragment_fts),f.document_id,f.ordinal LIMIT ?", match, limit,
        ) { r -> IndexedHit(r.getString("document_id"), r.getInt("version"), r.getInt("ordinal"), r.getString("text"), r.getInt("start_offset"), r.getInt("end_offset"), r.getObject("page")?.let { r.getInt("page") }) } }) }
        catch (_: java.sql.SQLException) { CatalogResult.Rejected(KnowledgeError.STORAGE_FAILURE) }
    }
}
