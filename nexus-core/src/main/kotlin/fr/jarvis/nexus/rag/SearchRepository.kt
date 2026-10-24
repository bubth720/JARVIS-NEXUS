package fr.jarvis.nexus.rag

import fr.jarvis.nexus.api.*
import fr.jarvis.nexus.store.*
import java.security.MessageDigest
import fr.jarvis.nexus.store.NexusSession
import fr.jarvis.nexus.store.NexusRow
import java.text.Normalizer

internal fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
internal class RagRefusal(val error: KnowledgeError) : RuntimeException()
internal fun scopeCheck(scope: SearchScope) {
    if (scope.collectionIds.isEmpty() || scope.sourceIds?.isEmpty() == true) throw RagRefusal(KnowledgeError.DENIED)
    require(scope.collectionIds.size <= 100 && (scope.sourceIds?.size ?: 0) <= 100)
    require((scope.collectionIds + (scope.sourceIds ?: emptySet())).all { it.isNotBlank() && it.length <= 128 })
}
internal fun citationRow(r: NexusRow): Citation {
    val raw = Citation("", r.getString("document_id"), r.getInt("version"), r.getInt("ordinal"), r.getString("source_id"), r.getString("uri"), r.getString("sha256"), r.getObject("page")?.let { r.getInt("page") }, r.getInt("start_offset"), r.getInt("end_offset"), sha256(r.getString("text")))
    return raw.copy(id = citationDigest(raw))
}
internal fun citationDigest(c: Citation): String = sha256(listOf(c.documentId,c.version.toString(),c.ordinal.toString(),c.sourceId,c.sourceUri,c.sourceSha256,c.page?.toString() ?: "",c.start.toString(),c.end.toString(),c.textSha256).joinToString("\u0000"))

class SearchRepository internal constructor(private val database: NexusStoragePort) {
    internal fun search(query: KnowledgeQuery, scope: SearchScope, limit: Int = 100): List<SearchHit> {
        scopeCheck(scope)
        require(query.text.length in 1..256 && limit in 1..100)
        require(query.importedAfterMs == null || query.importedAfterMs >= 0)
        require(query.importedBeforeMs == null || query.importedBeforeMs >= 0)
        require(query.importedAfterMs == null || query.importedBeforeMs == null || query.importedAfterMs <= query.importedBeforeMs)
        val words = Regex("[\\p{L}\\p{N}]+").findAll(Normalizer.normalize(query.text, Normalizer.Form.NFC)).map { it.value }.take(13).toList()
        require(words.isNotEmpty() && words.size <= 12)
        val match = words.joinToString(" AND ") { "\"$it\"" }
        val parameters = mutableListOf<Any?>(match)
        val clauses = mutableListOf("d.deleted=0", "d.version=f.version", "i.state='READY'", "i.indexed_version=d.version")
        clauses += "d.collection_id IN (${scope.collectionIds.joinToString(",") { "?" }})"
        parameters.addAll(scope.collectionIds.sorted())
        scope.sourceIds?.let { ids -> clauses += "s.id IN (${ids.joinToString(",") { "?" }})"; parameters.addAll(ids.sorted()) }
        query.importedAfterMs?.let { clauses += "d.imported_at_ms>=?"; parameters += it }
        query.importedBeforeMs?.let { clauses += "d.imported_at_ms<=?"; parameters += it }
        parameters += limit
        val joins = "FROM fragment_fts x JOIN fragment f ON f.document_id=x.document_id AND f.version=x.version AND f.ordinal=x.ordinal JOIN document d ON d.id=f.document_id JOIN revision r ON r.document_id=d.id AND r.version=d.version JOIN source s ON s.id=r.source_id JOIN index_state i ON i.document_id=d.id"
        return database.readSession { c ->
            fun hits(matchParameters: List<Any?>, mode: MatchMode): List<SearchHit> = c.query(
                "SELECT f.*,s.id AS source_id,s.uri,s.sha256,bm25(fragment_fts) AS rank $joins WHERE fragment_fts MATCH ? AND ${clauses.joinToString(" AND ")} ORDER BY rank,f.document_id,f.ordinal LIMIT ?", *matchParameters.toTypedArray(),
            ) { r -> SearchHit(r.getString("text"), citationRow(r), r.getDouble("rank"), mode) }
            val exact = hits(parameters, MatchMode.EXACT)
            if (exact.isNotEmpty() || words.size != 1 || !words.single().matches(Regex("[A-Za-z]{4,32}"))) return@readSession exact
            val word = words.single().lowercase(java.util.Locale.ROOT)
            val vocabularyParameters = parameters.drop(1).dropLast(1) + listOf(word.length - 1, word.length + 1, word.take(1))
            val alternatives = c.query("SELECT DISTINCT v.term $joins JOIN fragment_vocab v ON v.doc=x.rowid WHERE ${clauses.joinToString(" AND ")} AND length(v.term) BETWEEN ? AND ? AND substr(v.term,1,1)=? ORDER BY v.term LIMIT 256", *vocabularyParameters.toTypedArray()) { it.getString(1) }
            val candidate = alternatives.firstOrNull { oneEdit(word,it) } ?: return@readSession emptyList()
            val corrected = parameters.toMutableList().also { it[0] = "\"$candidate\"" }
            hits(corrected, MatchMode.FUZZY_ONE_EDIT)
        }
    }
}

class CitationResolver internal constructor(private val database: NexusStoragePort) {
    internal fun resolve(citation: Citation, scope: SearchScope): String {
        scopeCheck(scope)
        require(citation.id == citationDigest(citation))
        return database.readSession { c ->
            c.query("SELECT f.*,s.id AS source_id,s.uri,s.sha256,d.collection_id FROM fragment f JOIN document d ON d.id=f.document_id JOIN revision r ON r.document_id=d.id AND r.version=d.version JOIN source s ON s.id=r.source_id JOIN index_state i ON i.document_id=d.id WHERE d.id=? AND f.version=? AND f.ordinal=? AND d.deleted=0 AND d.version=f.version AND i.state='READY' AND i.indexed_version=d.version", citation.documentId, citation.version, citation.ordinal) { r ->
                if (r.getString("collection_id") !in scope.collectionIds || scope.sourceIds?.let { r.getString("source_id") !in it } == true) throw RagRefusal(KnowledgeError.DENIED)
                if (citationRow(r) != citation) throw RagRefusal(KnowledgeError.CONFLICT)
                r.getString("text")
            }.singleOrNull() ?: throw RagRefusal(KnowledgeError.NOT_FOUND)
        }
    }
}

/** Deliberately limited one-edit fallback, never a model-generated correction. */
private fun oneEdit(a: String, b: String): Boolean {
    if (kotlin.math.abs(a.length-b.length)>1) return false
    var i=0; var j=0; var changes=0
    while (i<a.length && j<b.length) {
        if (a[i]==b[j]) { i++; j++; continue }
        if (++changes>1) return false
        when { a.length>b.length -> i++; b.length>a.length -> j++; else -> { i++; j++ } }
    }
    return changes + (if (i<a.length || j<b.length) 1 else 0) <= 1
}
