package fr.jarvis.nexus.api

import fr.jarvis.nexus.store.*
import java.io.IOException
import java.io.InputStream
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/** All public catalog operations are authorized by the host, including reads. */
class KnowledgeCatalogService(
    private val database: NexusDatabase,
    private val access: AccessPolicy,
    private val limits: CatalogLimits = CatalogLimits(),
) : KnowledgeReadPort {
    private val sources = SourceRepository()
    private val documents = DocumentRepository()
    private class Refusal(val code: KnowledgeError) : RuntimeException()
    private fun deny(code: KnowledgeError): Nothing = throw Refusal(code)
    private fun <T> authorized(operation: Operation, action: () -> T): CatalogResult<T> {
        if (!runCatching { access.allows(operation) }.getOrDefault(false)) return CatalogResult.Rejected(KnowledgeError.DENIED)
        return try { CatalogResult.Ok(action()) }
        catch (e: Refusal) { CatalogResult.Rejected(e.code) }
        catch (e: SourceTooLarge) { CatalogResult.Rejected(KnowledgeError.LIMIT_EXCEEDED) }
        catch (e: IllegalArgumentException) { CatalogResult.Rejected(KnowledgeError.INVALID_INPUT) }
        catch (e: SQLException) { CatalogResult.Rejected(KnowledgeError.STORAGE_FAILURE) }
        catch (e: IOException) { CatalogResult.Rejected(KnowledgeError.STORAGE_FAILURE) }
    }
    private fun text(value: String, max: Int, empty: Boolean = false) {
        require((empty || value.isNotBlank()) && value.length <= max && '\u0000' !in value)
        var i = 0
        while (i < value.length) {
            val ch = value[i++]
            if (ch.isHighSurrogate()) require(i < value.length && value[i++].isLowSurrogate())
            else require(!ch.isLowSurrogate())
        }
    }
    private fun page(limit: Int, offset: Int) { require(limit in 1..limits.maxPageSize && offset >= 0) }
    private fun log(c: Connection, documentId: String?, kind: String) { c.execute("INSERT INTO mutation_log(document_id,kind) VALUES(?,?)", documentId, kind) }
    private fun current(c: Connection, id: String, expected: Int): DocumentRecord {
        text(id, 128)
        val document = documents.find(c, id) ?: deny(KnowledgeError.NOT_FOUND)
        if (document.version != expected) deny(KnowledgeError.CONFLICT)
        return document
    }
    fun createCollection(name: String): CatalogResult<CollectionRecord> = authorized(Operation.WRITE) {
        text(name, 256)
        database.transaction { c ->
            c.query("SELECT * FROM collection WHERE name=?", name) { CollectionRecord(it.getString(1), it.getString(2)) }.firstOrNull()
                ?: CollectionRecord(UUID.randomUUID().toString(), name).also {
                    c.execute("INSERT INTO collection VALUES(?,?)", it.id, it.name); log(c, null, "COLLECTION_CREATED")
                }
        }
    }
    /** Import is metadata/stream based; the stream remains owned by the caller. */
    fun importSource(input: ImportRequest, content: InputStream): CatalogResult<ImportOutcome> = authorized(Operation.WRITE) {
        val request = input.copy(fragments = input.fragments.toList())
        text(request.uri, 2048); text(request.title, 512); text(request.collectionId, 128)
        require(request.expectedVersion == null || request.expectedVersion >= 1)
        if (request.fragments.size > limits.maxFragments) deny(KnowledgeError.LIMIT_EXCEEDED)
        if (request.fragments.sumOf { it.text.length.toLong() } > limits.maxTotalFragmentChars) deny(KnowledgeError.LIMIT_EXCEEDED)
        require(request.fragments.map { it.ordinal }.distinct().size == request.fragments.size)
        request.fragments.forEach { f ->
            text(f.text, limits.maxFragmentChars, true)
            require(f.ordinal >= 0 && f.start >= 0 && f.end >= f.start && (f.page == null || f.page >= 1))
        }
        val fingerprint = SourceFingerprint.compute(content, limits.maxSourceBytes)
        database.transaction { c ->
            if (!runCatching { access.allows(Operation.WRITE) }.getOrDefault(false)) deny(KnowledgeError.DENIED)
            if (c.query("SELECT id FROM collection WHERE id=?", request.collectionId) { it.getString(1) }.isEmpty()) deny(KnowledgeError.NOT_FOUND)
            val previous = documents.byUri(c, request.collectionId, request.uri)
            if (previous?.deleted == true) deny(KnowledgeError.CONFLICT)
            if (previous != null) {
                if (request.expectedVersion != null && previous.version != request.expectedVersion) deny(KnowledgeError.CONFLICT)
                val oldHash = c.query("SELECT s.sha256 FROM revision r JOIN source s ON s.id=r.source_id WHERE r.document_id=? AND r.version=?", previous.id, previous.version) { it.getString(1) }.single()
                val oldFragments = documents.fragments(c, previous.id, previous.version, limits.maxFragments, 0)
                if (oldHash == fingerprint.sha256 && previous.title == request.title && oldFragments == request.fragments.sortedBy { it.ordinal }) return@transaction ImportOutcome.Unchanged(previous)
                if (request.expectedVersion == null || previous.version == Int.MAX_VALUE) deny(KnowledgeError.CONFLICT)
            } else if (request.expectedVersion != null) deny(KnowledgeError.CONFLICT)
            val source = sources.findOrCreate(c, request.uri, fingerprint)
            val document = DocumentRecord(previous?.id ?: UUID.randomUUID().toString(), request.collectionId, request.uri, request.title, (previous?.version ?: 0) + 1, false)
            if (previous == null) c.execute("INSERT INTO document VALUES(?,?,?,?,?,0)", document.id, document.collectionId, document.uri, document.title, document.version)
            else c.execute("UPDATE document SET title=?,version=? WHERE id=?", document.title, document.version, document.id)
            documents.append(c, document, source, request.fragments)
            log(c, document.id, if (previous == null) "CREATED" else "REVISED")
            if (previous == null) ImportOutcome.Created(document) else ImportOutcome.Revised(document)
        }
    }
    fun rename(documentId: String, expectedVersion: Int, title: String): CatalogResult<DocumentRecord> = authorized(Operation.WRITE) {
        text(title, 512)
        database.transaction { c ->
            val old = current(c, documentId, expectedVersion)
            if (old.deleted || old.version == Int.MAX_VALUE) deny(KnowledgeError.CONFLICT)
            val sourceId = c.query("SELECT source_id FROM revision WHERE document_id=? AND version=?", old.id, old.version) { it.getString(1) }.single()
            val source = c.query("SELECT * FROM source WHERE id=?", sourceId, map = ::sourceRow).single()
            val revised = old.copy(title = title, version = old.version + 1)
            c.execute("UPDATE document SET title=?,version=? WHERE id=?", title, revised.version, old.id)
            documents.append(c, revised, source, documents.fragments(c, old.id, old.version, limits.maxFragments, 0))
            log(c, old.id, "RENAMED")
            revised
        }
    }
    fun setDeleted(documentId: String, expectedVersion: Int, deleted: Boolean): CatalogResult<DocumentRecord> = authorized(Operation.WRITE) {
        database.transaction { c ->
            val old = current(c, documentId, expectedVersion)
            if (old.deleted == deleted) return@transaction old
            if (old.version == Int.MAX_VALUE) deny(KnowledgeError.CONFLICT)
            val sourceId = c.query("SELECT source_id FROM revision WHERE document_id=? AND version=?", old.id, old.version) { it.getString(1) }.single()
            val source = c.query("SELECT * FROM source WHERE id=?", sourceId, map = ::sourceRow).single()
            val revised = old.copy(deleted = deleted, version = old.version + 1)
            c.execute("UPDATE document SET deleted=?,version=? WHERE id=?", if (deleted) 1 else 0, revised.version, old.id)
            documents.append(c, revised, source, documents.fragments(c, old.id, old.version, limits.maxFragments, 0))
            log(c, old.id, if (deleted) "SOFT_DELETED" else "RESTORED")
            revised
        }
    }
    /** Purge only acts on an already deleted document with explicit host confirmation. */
    fun purge(documentId: String, expectedVersion: Int, confirmed: Boolean): CatalogResult<Unit> = authorized(Operation.PURGE) {
        if (!confirmed) deny(KnowledgeError.DENIED)
        database.transaction { c ->
            val old = current(c, documentId, expectedVersion)
            if (!old.deleted) deny(KnowledgeError.CONFLICT)
            c.execute("DELETE FROM document WHERE id=?", old.id)
            log(c, old.id, "PURGED")
            // Immutable source provenance and audit intentionally retained.
        }
    }
    override fun documents(limit: Int, offset: Int, includeDeleted: Boolean): CatalogResult<List<DocumentRecord>> = authorized(Operation.READ) {
        page(limit, offset); database.read { documents.list(it, limit, offset, includeDeleted) }
    }
    override fun fragments(documentId: String, version: Int, limit: Int, offset: Int): CatalogResult<List<Fragment>> = authorized(Operation.READ) {
        page(limit, offset); require(version >= 1); text(documentId, 128)
        database.read { c ->
            val doc = documents.find(c, documentId) ?: deny(KnowledgeError.NOT_FOUND)
            if (doc.deleted) deny(KnowledgeError.NOT_FOUND)
            if (c.query("SELECT version FROM revision WHERE document_id=? AND version=?", documentId, version) { it.getInt(1) }.isEmpty()) deny(KnowledgeError.NOT_FOUND)
            documents.fragments(c, documentId, version, limit, offset)
        }
    }
    override fun sources(limit: Int, offset: Int): CatalogResult<List<SourceRecord>> = authorized(Operation.READ) {
        page(limit, offset); database.read { sources.list(it, limit, offset) }
    }
    fun revisions(documentId: String, limit: Int = 100, offset: Int = 0): CatalogResult<List<RevisionRecord>> = authorized(Operation.READ) {
        page(limit, offset); text(documentId, 128)
        database.read { c -> c.query("SELECT * FROM revision WHERE document_id=? ORDER BY version LIMIT ? OFFSET ?", documentId, limit, offset) { RevisionRecord(it.getString(1), it.getInt(2), it.getString(3), it.getString(4)) } }
    }
    fun mutations(afterSequence: Long = 0, limit: Int = 100): CatalogResult<List<MutationRecord>> = authorized(Operation.READ) {
        page(limit, 0); require(afterSequence >= 0)
        database.read { c -> c.query("SELECT * FROM mutation_log WHERE sequence>? ORDER BY sequence LIMIT ?", afterSequence, limit) { MutationRecord(it.getLong(1), it.getString(2), it.getString(3)) } }
    }
    /** Bounded provenance page, never conversation content or arbitrary filesystem reads. */
    fun catalogJson(limit: Int = 100, offset: Int = 0): CatalogResult<String> = authorized(Operation.READ) {
        page(limit, offset)
        database.read { c ->
            val records = sources.list(c, limit, offset)
            val sourceJson = records.joinToString(",", "[", "]") {
                "{\"id\":${json(it.id)},\"uri\":${json(it.uri)},\"sha256\":${json(it.sha256)},\"bytes\":${it.byteCount}}"
            }
            val documentJson = documents.list(c, limit, offset, true).joinToString(",", "[", "]") { doc ->
                val sourceId = c.query("SELECT source_id FROM revision WHERE document_id=? AND version=?", doc.id, doc.version) { it.getString(1) }.single()
                "{\"id\":${json(doc.id)},\"collectionId\":${json(doc.collectionId)},\"uri\":${json(doc.uri)},\"title\":${json(doc.title)},\"version\":${doc.version},\"deleted\":${doc.deleted},\"sourceId\":${json(sourceId)}}"
            }
            "{\"apiVersion\":$CATALOG_API_VERSION,\"schemaVersion\":${SchemaMigrator.CURRENT_VERSION},\"offset\":$offset,\"sources\":$sourceJson,\"documents\":$documentJson}"
        }
    }
    fun mutationJson(afterSequence: Long = 0, limit: Int = 100): CatalogResult<String> = when (val result = mutations(afterSequence, limit)) {
        is CatalogResult.Rejected -> result
        is CatalogResult.Ok -> CatalogResult.Ok(result.value.joinToString(",", "[", "]") {
            "{\"sequence\":${it.sequence},\"documentId\":${it.documentId?.let(::json) ?: "null"},\"kind\":${json(it.kind)}}"
        })
    }
}
private fun json(value: String): String = buildString {
    append('"')
    value.forEach { ch -> when (ch) {
        '"' -> append("\\\""); '\\' -> append("\\\\")
        else -> if (ch.code < 32) append("\\u%04x".format(ch.code)) else append(ch)
    } }
    append('"')
}
