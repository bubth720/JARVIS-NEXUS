-- Frozen NEX-01 DDL from c25d401dff6865657b75d320361ae81811363fa8. Synthetic test only.
CREATE TABLE collection(id TEXT PRIMARY KEY, name TEXT NOT NULL UNIQUE CHECK(length(name) BETWEEN 1 AND 256));
CREATE TABLE source(id TEXT PRIMARY KEY, uri TEXT NOT NULL, sha256 TEXT NOT NULL CHECK(length(sha256)=64), byte_count INTEGER NOT NULL CHECK(byte_count>=0), UNIQUE(uri,sha256));
CREATE TABLE document(id TEXT PRIMARY KEY, collection_id TEXT NOT NULL REFERENCES collection(id), uri TEXT NOT NULL, title TEXT NOT NULL, version INTEGER NOT NULL CHECK(version>=1), deleted INTEGER NOT NULL DEFAULT 0 CHECK(deleted IN (0,1)), UNIQUE(collection_id,uri));
CREATE TABLE revision(document_id TEXT NOT NULL REFERENCES document(id) ON DELETE CASCADE, version INTEGER NOT NULL CHECK(version>=1), source_id TEXT NOT NULL REFERENCES source(id), title TEXT NOT NULL, PRIMARY KEY(document_id,version));
CREATE TABLE fragment(document_id TEXT NOT NULL, version INTEGER NOT NULL, ordinal INTEGER NOT NULL CHECK(ordinal>=0), text TEXT NOT NULL, start_offset INTEGER NOT NULL CHECK(start_offset>=0), end_offset INTEGER NOT NULL CHECK(end_offset>=start_offset), page INTEGER CHECK(page>=1), PRIMARY KEY(document_id,version,ordinal), FOREIGN KEY(document_id,version) REFERENCES revision(document_id,version) ON DELETE CASCADE);
CREATE TABLE index_state(document_id TEXT PRIMARY KEY REFERENCES document(id) ON DELETE CASCADE, indexed_version INTEGER NOT NULL DEFAULT 0 CHECK(indexed_version>=0), state TEXT NOT NULL CHECK(state IN ('PENDING','READY','FAILED')));
CREATE TABLE mutation_log(sequence INTEGER PRIMARY KEY AUTOINCREMENT, document_id TEXT, kind TEXT NOT NULL);
CREATE INDEX source_digest ON source(sha256);
CREATE INDEX document_collection ON document(collection_id,deleted);
PRAGMA user_version=1;
