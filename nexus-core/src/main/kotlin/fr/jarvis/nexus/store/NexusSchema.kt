package fr.jarvis.nexus.store

object NexusSchema {
    const val CURRENT_VERSION = 3
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
    fun migrate(storage: NexusStoragePort, afterStatement: (Int) -> Unit = {}) {
        val version = storage.readSession { it.query("PRAGMA user_version") { row -> row.getInt(1) }.single() }
        require(version in 0..CURRENT_VERSION) { "Unsupported NEXUS schema: $version" }
        if (version == CURRENT_VERSION) return
        storage.inTransaction { c ->
            var step = 0
            if (version < 1) v1.forEach { c.execute(it); afterStatement(step++) }
            if (version < 2) v2.forEach { c.execute(it); afterStatement(step++) }
            if (version < 3) v3.forEach { c.execute(it); afterStatement(step++) }
            c.execute("PRAGMA user_version=$CURRENT_VERSION")
        }
    }
    private val v2 = listOf(
        "CREATE TABLE import_job(id TEXT PRIMARY KEY, collection_id TEXT NOT NULL REFERENCES collection(id), uri TEXT NOT NULL, name TEXT NOT NULL, title TEXT NOT NULL, sha256 TEXT NOT NULL, byte_count INTEGER NOT NULL, expected_version INTEGER, state TEXT NOT NULL CHECK(state IN ('QUEUED','PROCESSING','CANCELLING','CANCELLED','INTERRUPTED','FAILED','COMPLETED')), document_id TEXT, error TEXT)",
        "CREATE TABLE import_page(document_id TEXT NOT NULL, version INTEGER NOT NULL, page INTEGER NOT NULL CHECK(page>=0), text_sha256 TEXT NOT NULL, PRIMARY KEY(document_id,version,page), FOREIGN KEY(document_id,version) REFERENCES revision(document_id,version) ON DELETE CASCADE)",
        "CREATE VIRTUAL TABLE fragment_fts USING fts5(document_id UNINDEXED, version UNINDEXED, ordinal UNINDEXED, body, tokenize='unicode61 remove_diacritics 2')",
        "CREATE TRIGGER document_purge_fts AFTER DELETE ON document BEGIN DELETE FROM fragment_fts WHERE document_id=OLD.id; END",
        "CREATE TRIGGER document_hide_fts AFTER UPDATE OF deleted ON document WHEN NEW.deleted=1 BEGIN DELETE FROM fragment_fts WHERE document_id=NEW.id; END",
    )

    private val v3 = listOf("ALTER TABLE document ADD COLUMN imported_at_ms INTEGER CHECK(imported_at_ms>=0)", "CREATE VIRTUAL TABLE fragment_vocab USING fts5vocab(fragment_fts, 'instance')", "CREATE TABLE import_job_event(sequence INTEGER PRIMARY KEY AUTOINCREMENT, job_id TEXT NOT NULL REFERENCES import_job(id), kind TEXT NOT NULL, state TEXT NOT NULL, error TEXT)")

}
