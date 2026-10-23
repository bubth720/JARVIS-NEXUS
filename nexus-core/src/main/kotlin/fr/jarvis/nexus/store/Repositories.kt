package fr.jarvis.nexus.store

import fr.jarvis.nexus.api.*
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

internal fun documentRow(r: ResultSet) = DocumentRecord(r.getString("id"), r.getString("collection_id"), r.getString("uri"), r.getString("title"), r.getInt("version"), r.getInt("deleted") != 0)
internal fun sourceRow(r: ResultSet) = SourceRecord(r.getString("id"), r.getString("uri"), r.getString("sha256"), r.getLong("byte_count"))
internal class SourceRepository {
    fun findOrCreate(c: Connection, uri: String, fingerprint: Fingerprint): SourceRecord {
        c.query("SELECT * FROM source WHERE uri=? AND sha256=?", uri, fingerprint.sha256, map = ::sourceRow).firstOrNull()?.let { return it }
        val id = UUID.randomUUID().toString()
        c.execute("INSERT INTO source VALUES(?,?,?,?)", id, uri, fingerprint.sha256, fingerprint.bytes)
        return SourceRecord(id, uri, fingerprint.sha256, fingerprint.bytes)
    }
    fun list(c: Connection, limit: Int, offset: Int) = c.query("SELECT * FROM source ORDER BY id LIMIT ? OFFSET ?", limit, offset, map = ::sourceRow)
}
internal class DocumentRepository {
    fun find(c: Connection, id: String) = c.query("SELECT * FROM document WHERE id=?", id, map = ::documentRow).firstOrNull()
    fun byUri(c: Connection, collectionId: String, uri: String) = c.query("SELECT * FROM document WHERE collection_id=? AND uri=?", collectionId, uri, map = ::documentRow).firstOrNull()
    fun list(c: Connection, limit: Int, offset: Int, includeDeleted: Boolean) = c.query("SELECT * FROM document WHERE (?=1 OR deleted=0) ORDER BY id LIMIT ? OFFSET ?", if (includeDeleted) 1 else 0, limit, offset, map = ::documentRow)
    fun fragments(c: Connection, documentId: String, version: Int, limit: Int, offset: Int): List<Fragment> = c.query(
        "SELECT * FROM fragment WHERE document_id=? AND version=? ORDER BY ordinal LIMIT ? OFFSET ?", documentId, version, limit, offset,
    ) { r -> Fragment(r.getInt("ordinal"), r.getString("text"), r.getInt("start_offset"), r.getInt("end_offset"), r.getObject("page")?.let { r.getInt("page") }) }
    fun append(c: Connection, document: DocumentRecord, source: SourceRecord, fragments: List<Fragment>) {
        c.execute("INSERT INTO revision VALUES(?,?,?,?)", document.id, document.version, source.id, document.title)
        fragments.forEach { f -> c.execute("INSERT INTO fragment VALUES(?,?,?,?,?,?,?)", document.id, document.version, f.ordinal, f.text, f.start, f.end, f.page) }
        c.execute("INSERT INTO index_state VALUES(?,0,'PENDING') ON CONFLICT(document_id) DO UPDATE SET state='PENDING'", document.id)
    }
}
