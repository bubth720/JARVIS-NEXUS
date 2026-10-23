package fr.jarvis.nexus.store

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/** Single connection owner. Operations and close are serialized on this object. */
class NexusDatabase private constructor(internal val connection: Connection, internal val path: Path) : AutoCloseable {
    companion object {
        /** The host chooses this private DB path, never a document URI or untrusted string. */
        fun open(path: Path): NexusDatabase {
            require(!Files.isSymbolicLink(path)) { "A database symlink is not allowed" }
            val canonicalPath = path.toAbsolutePath().parent.toRealPath().resolve(path.fileName)
            Class.forName("org.sqlite.JDBC")
            val connection = DriverManager.getConnection("jdbc:sqlite:" + canonicalPath)
            return try {
                connection.createStatement().use { it.execute("PRAGMA foreign_keys=ON"); it.execute("PRAGMA busy_timeout=5000") }
                SchemaMigrator.migrate(connection)
                NexusDatabase(connection, canonicalPath)
            } catch (failure: Throwable) { connection.close(); throw failure }
        }
    }
    @Synchronized internal fun <T> transaction(block: (Connection) -> T): T {
        if (connection.isClosed) throw java.sql.SQLException("Database closed")
        check(connection.autoCommit) { "Nested transactions not allowed" }
        connection.autoCommit = false
        try {
            val value = block(connection)
            connection.commit()
            return value
        } catch (failure: Throwable) {
            try { connection.rollback() } catch (rollback: Throwable) { failure.addSuppressed(rollback) }
            throw failure
        } finally { connection.autoCommit = true }
    }
    @Synchronized internal fun <T> read(block: (Connection) -> T): T = block(connection)
    fun integrityCheck(): Boolean = read { c ->
        c.createStatement().use { s -> s.executeQuery("PRAGMA integrity_check").use { r -> r.next() && r.getString(1) == "ok" && !r.next() } } &&
            c.createStatement().use { s -> s.executeQuery("PRAGMA foreign_key_check").use { !it.next() } }
    }
    @Synchronized override fun close() = connection.close()
}

/** Migrations are atomic, including their version marker; future versions are refused. */
object SchemaMigrator {
    const val CURRENT_VERSION = 2
    private val v1 = listOf(
        "CREATE TABLE collection(id TEXT PRIMARY KEY, name TEXT NOT NULL UNIQUE CHECK(length(name) BETWEEN 1 AND 256))",
        "CREATE TABLE source(id TEXT PRIMARY KEY, uri TEXT NOT NULL, sha256 TEXT NOT NULL CHECK(length(sha256)=64), byte_count INTEGER NOT NULL CHECK(byte_count>=0), UNIQUE(uri,sha256))",
        "CREATE TABLE document(id TEXT PRIMARY KEY, collection_id TEXT NOT NULL REFERENCES collection(id), uri TEXT NOT NULL, title TEXT NOT NULL, version INTEGER NOT NULL CHECK(version>=1), deleted INTEGER NOT NULL DEFAULT 0 CHECK(deleted IN (0,1)), UNIQUE(collection_id,uri))",
        "CREATE TABLE revision(document_id TEXT NOT NULL REFERENCES document(id) ON DELETE CASCADE, version INTEGER NOT NULL CHECK(version>=1), source_id TEXT NOT NULL REFERENCES source(id), title TEXT NOT NULL, PRIMARY KEY(document_id,version))",
        "CREATE TABLE fragment(document_id TEXT NOT NULL, version INTEGER NOT NULL, ordinal INTEGER NOT NULL CHECK(ordinal>=0), text TEXT NOT NULL, start_offset INTEGER NOT NULL CHECK(start_offset>=0), end_offset INTEGER NOT NULL CHECK(end_offset>=start_offset), page INTEGER CHECK(page>=1), PRIMARY KEY(document_id,version,ordinal), FOREIGN KEY(document_id,version) REFERENCES revision(document_id,version) ON DELETE CASCADE)",
        "CREATE TABLE index_state(document_id TEXT PRIMARY KEY REFERENCES document(id) ON DELETE CASCADE, indexed_version INTEGER NOT NULL DEFAULT 0 CHECK(indexed_version>=0), state TEXT NOT NULL CHECK(state IN ('PENDING','READY','FAILED')))",
        // Audit deliberately outlives a purged document; no FK to erased rows.
        "CREATE TABLE mutation_log(sequence INTEGER PRIMARY KEY AUTOINCREMENT, document_id TEXT, kind TEXT NOT NULL)",
        "CREATE INDEX source_digest ON source(sha256)",
        "CREATE INDEX document_collection ON document(collection_id,deleted)",
    )
    internal fun migrate(c: Connection, afterStatement: (Int) -> Unit = {}) {
        val version = c.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }
        require(version in 0..CURRENT_VERSION) { "Unsupported NEXUS schema: $version" }
        if (version == CURRENT_VERSION) return
        c.autoCommit = false
        try {
            var step = 0
            if (version < 1) v1.forEach { sql -> c.createStatement().use { it.execute(sql) }; afterStatement(step++) }
            if (version < 2) v2.forEach { sql -> c.createStatement().use { it.execute(sql) }; afterStatement(step++) }
            c.createStatement().use { it.execute("PRAGMA user_version=$CURRENT_VERSION") }
            c.commit()
        } catch (failure: Throwable) { c.rollback(); throw failure }
        finally { c.autoCommit = true }
    }
    private val v2 = listOf(
        "CREATE TABLE import_job(id TEXT PRIMARY KEY, collection_id TEXT NOT NULL REFERENCES collection(id), uri TEXT NOT NULL, name TEXT NOT NULL, title TEXT NOT NULL, sha256 TEXT NOT NULL, byte_count INTEGER NOT NULL, expected_version INTEGER, state TEXT NOT NULL CHECK(state IN ('QUEUED','PROCESSING','CANCELLING','CANCELLED','INTERRUPTED','FAILED','COMPLETED')), document_id TEXT, error TEXT)",
        "CREATE TABLE import_page(document_id TEXT NOT NULL, version INTEGER NOT NULL, page INTEGER NOT NULL CHECK(page>=0), text_sha256 TEXT NOT NULL, PRIMARY KEY(document_id,version,page), FOREIGN KEY(document_id,version) REFERENCES revision(document_id,version) ON DELETE CASCADE)",
        "CREATE VIRTUAL TABLE fragment_fts USING fts5(document_id UNINDEXED, version UNINDEXED, ordinal UNINDEXED, body, tokenize='unicode61 remove_diacritics 2')",
        "CREATE TRIGGER document_purge_fts AFTER DELETE ON document BEGIN DELETE FROM fragment_fts WHERE document_id=OLD.id; END",
        "CREATE TRIGGER document_hide_fts AFTER UPDATE OF deleted ON document WHEN NEW.deleted=1 BEGIN DELETE FROM fragment_fts WHERE document_id=NEW.id; END",
    )

}

internal fun Connection.execute(sql: String, vararg values: Any?): Int = prepareStatement(sql).use { s ->
    values.forEachIndexed { index, value -> s.setObject(index + 1, value) }; s.executeUpdate()
}
internal fun <T> Connection.query(sql: String, vararg values: Any?, map: (ResultSet) -> T): List<T> = prepareStatement(sql).use { s ->
    values.forEachIndexed { index, value -> s.setObject(index + 1, value) }
    s.executeQuery().use { r -> buildList { while (r.next()) add(map(r)) } }
}
