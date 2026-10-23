package fr.jarvis.nexus

import fr.jarvis.nexus.api.*
import fr.jarvis.nexus.store.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.Executors

class NexusStorageTest {
    private fun <T> ok(result: CatalogResult<T>): T = (result as CatalogResult.Ok).value
    private fun refused(code: KnowledgeError, result: CatalogResult<*>) = assertEquals(CatalogResult.Rejected(code), result)
    private fun database(test: (NexusDatabase, KnowledgeCatalogService, CollectionRecord) -> Unit) {
        val path = Files.createTempFile("nexus-synthetic-", ".db")
        try { NexusDatabase.open(path).use { db ->
            val api = KnowledgeCatalogService(db, AccessPolicy { true })
            test(db, api, ok(api.createCollection("synthetic")))
            assertTrue(db.integrityCheck())
        } } finally { Files.deleteIfExists(path) }
    }
    private fun add(api: KnowledgeCatalogService, collection: CollectionRecord, text: String = "hello", uri: String = "opaque:one", expected: Int? = null): CatalogResult<ImportOutcome> = api.importSource(
        ImportRequest(collection.id, uri, "Synthetic", expected, listOf(Fragment(0, text, 0, text.length, 1))), text.byteInputStream(),
    )
    @Test fun createsAllSevenTablesAndIndexes() = database { db, _, _ ->
        val tables = db.read { c -> c.query("SELECT name FROM sqlite_master WHERE type='table'") { it.getString(1) } }
        assertTrue(tables.containsAll(listOf("collection", "source", "document", "revision", "fragment", "index_state", "mutation_log")))
    }
    @Test fun exactDuplicateIsIdempotentAndDoesNotAppendAudit() = database { _, api, collection ->
        val first = ok(add(api, collection)) as ImportOutcome.Created
        val audit = ok(api.mutations())
        val duplicate = ok(add(api, collection)) as ImportOutcome.Unchanged
        assertEquals(first.document, duplicate.document)
        assertEquals(audit, ok(api.mutations()))
        assertEquals(1, ok(api.sources()).size)
    }
    @Test fun changesRequireExpectedVersionAndKeepImmutableProvenance() = database { _, api, collection ->
        val first = ok(add(api, collection)).document
        refused(KnowledgeError.CONFLICT, add(api, collection, "changed"))
        val next = ok(add(api, collection, "changed", expected = first.version)).document
        assertEquals(2, next.version)
        assertEquals("hello", ok(api.fragments(first.id, 1)).single().text)
        assertEquals("changed", ok(api.fragments(first.id, 2)).single().text)
        assertEquals(2, ok(api.sources()).size)
        refused(KnowledgeError.CONFLICT, add(api, collection, "third", expected = 1))
    }
    @Test fun metadataChangesAndDeleteRestoreAreVersioned() = database { _, api, collection ->
        val first = ok(add(api, collection)).document
        val renamed = ok(api.rename(first.id, first.version, "New name"))
        assertEquals(2, renamed.version)
        val deleted = ok(api.setDeleted(first.id, renamed.version, true))
        assertTrue(ok(api.documents()).isEmpty())
        refused(KnowledgeError.NOT_FOUND, api.fragments(first.id, 1))
        refused(KnowledgeError.CONFLICT, api.setDeleted(first.id, renamed.version, false))
        val restored = ok(api.setDeleted(first.id, deleted.version, false))
        assertEquals(4, restored.version)
        assertEquals("hello", ok(api.fragments(first.id, restored.version)).single().text)
    }
    @Test fun purgeRequiresConfirmationAndPriorDeletionAndKeepsAudit() = database { _, api, collection ->
        val first = ok(add(api, collection)).document
        refused(KnowledgeError.DENIED, api.purge(first.id, 1, false))
        refused(KnowledgeError.CONFLICT, api.purge(first.id, 1, true))
        val deleted = ok(api.setDeleted(first.id, 1, true))
        ok(api.purge(first.id, deleted.version, true))
        assertTrue(ok(api.documents(includeDeleted = true)).isEmpty())
        assertEquals("PURGED", ok(api.mutations()).last().kind)
        assertEquals(1, ok(api.sources()).size)
    }
    @Test fun denialPrecedesStreamReadAndAllReadPathsAreProtected() = database { db, _, collection ->
        val denied = KnowledgeCatalogService(db, AccessPolicy { false })
        val explosive = object : java.io.InputStream() { override fun read(): Int = error("must not read") }
        refused(KnowledgeError.DENIED, denied.importSource(ImportRequest(collection.id, "opaque:one", "title"), explosive))
        refused(KnowledgeError.DENIED, denied.documents())
        refused(KnowledgeError.DENIED, denied.sources())
        refused(KnowledgeError.DENIED, denied.catalogJson())
        refused(KnowledgeError.DENIED, denied.mutationJson())
    }
    @Test fun revocationWhileHashingPreventsCommit() = database { db, _, collection ->
        var allowed = true
        val api = KnowledgeCatalogService(db, AccessPolicy { allowed || it == Operation.READ })
        val stream = object : ByteArrayInputStream("hello".toByteArray()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int { allowed = false; return super.read(b, off, len) }
        }
        refused(KnowledgeError.DENIED, api.importSource(ImportRequest(collection.id, "opaque:x", "title"), stream))
        assertTrue(ok(api.documents()).isEmpty())
    }
    @Test fun boundsInvalidUnicodeAndUnknownCollectionAreRefused() = database { db, _, collection ->
        val api = KnowledgeCatalogService(db, AccessPolicy { true }, CatalogLimits(maxSourceBytes = 4, maxFragments = 1))
        refused(KnowledgeError.LIMIT_EXCEEDED, add(api, collection, "12345"))
        refused(KnowledgeError.INVALID_INPUT, api.documents(limit = 0))
        refused(KnowledgeError.INVALID_INPUT, api.createCollection("\uD800"))
        refused(KnowledgeError.NOT_FOUND, api.importSource(ImportRequest("missing", "opaque:x", "title"), "abc".byteInputStream()))
        assertTrue(ok(api.documents()).isEmpty())
    }
    @Test fun failureMidImportRollsBackSourceDocumentFragmentsAndAudit() = database { db, api, collection ->
        val before = ok(api.mutations())
        db.read { c -> c.createStatement().use { it.execute("CREATE TRIGGER injected_failure BEFORE INSERT ON fragment BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END") } }
        refused(KnowledgeError.STORAGE_FAILURE, add(api, collection))
        assertTrue(ok(api.documents()).isEmpty()); assertTrue(ok(api.sources()).isEmpty())
        assertEquals(before, ok(api.mutations()))
        db.read { c -> c.createStatement().use { it.execute("DROP TRIGGER injected_failure") } }
        assertTrue(ok(add(api, collection)) is ImportOutcome.Created)
    }
    @Test fun concurrentRevisionsHaveOneWinnerAndNoLostUpdate() = database { _, api, collection ->
        ok(add(api, collection))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = listOf("second", "other").map { value -> executor.submit<CatalogResult<ImportOutcome>> { add(api, collection, value, expected = 1) } }.map { it.get() }
            assertEquals(1, results.count { it is CatalogResult.Ok })
            assertEquals(1, results.count { it == CatalogResult.Rejected(KnowledgeError.CONFLICT) })
            assertEquals(2, ok(api.documents()).single().version)
        } finally { executor.shutdownNow() }
    }
    @Test fun pagesAndJsonEscapingPreserveSourceIdentity() = database { _, api, collection ->
        ok(add(api, collection, uri = "opaque:\"line\n\\é")); ok(add(api, collection, uri = "opaque:two"))
        val first = ok(api.documents(limit = 1)); val second = ok(api.documents(limit = 1, offset = 1))
        assertNotEquals(first.single().id, second.single().id)
        val json = ok(api.catalogJson())
        assertTrue(json.contains("\\\"line\\u000a\\\\é"))
        assertTrue(json.contains("\"schemaVersion\":${SchemaMigrator.CURRENT_VERSION}"))
    }
    @Test fun reopenKeepsRealRowsRevisionsAndIntegrity() {
        val path = Files.createTempFile("nexus-reopen-", ".db")
        var id = ""
        try {
            NexusDatabase.open(path).use { db -> val api = KnowledgeCatalogService(db, AccessPolicy { true }); id = ok(add(api, ok(api.createCollection("synthetic")))).document.id }
            NexusDatabase.open(path).use { db -> val api = KnowledgeCatalogService(db, AccessPolicy { true }); assertEquals(id, ok(api.documents()).single().id); assertTrue(db.integrityCheck()) }
        } finally { Files.deleteIfExists(path) }
    }
    @Test fun interruptedMigrationLeavesNoPartialSchemaAndCanRetry() {
        val path = Files.createTempFile("nexus-migration-", ".db")
        try {
            DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
                try { SchemaMigrator.migrate(c) { if (it == 3) error("simulated interruption") }; fail("must interrupt") } catch (_: IllegalStateException) { }
                val count = c.query("SELECT count(*) FROM sqlite_master WHERE type='table'") { it.getInt(1) }.single()
                assertEquals(0, count)
                assertEquals(0, c.query("PRAGMA user_version") { it.getInt(1) }.single())
                SchemaMigrator.migrate(c)
                assertEquals(SchemaMigrator.CURRENT_VERSION, c.query("PRAGMA user_version") { it.getInt(1) }.single())
            }
            NexusDatabase.open(path).use { assertTrue(it.integrityCheck()) }
        } finally { Files.deleteIfExists(path) }
    }
    @Test fun futureSchemaIsRefusedWithoutRewritingVersion() {
        val path = Files.createTempFile("nexus-future-", ".db")
        try {
            DriverManager.getConnection("jdbc:sqlite:$path").use { c -> c.createStatement().use { it.execute("PRAGMA user_version=99") } }
            try { NexusDatabase.open(path).close(); fail("must refuse") } catch (_: IllegalArgumentException) { }
            DriverManager.getConnection("jdbc:sqlite:$path").use { c -> assertEquals(99, c.query("PRAGMA user_version") { it.getInt(1) }.single()) }
        } finally { Files.deleteIfExists(path) }
    }
    @Test fun titleHistoryIsPreservedInRevisions() = database { _, api, collection ->
        val first = ok(add(api, collection)).document
        ok(api.rename(first.id, 1, "new title"))
        assertEquals(listOf("Synthetic", "new title"), ok(api.revisions(first.id)).map { it.title })
    }
    @Test fun callerCannotMutateFragmentsAfterValidationDuringHashing() = database { _, api, collection ->
        val fragments = mutableListOf(Fragment(0, "safe", 0, 4))
        val stream = object : ByteArrayInputStream("safe".toByteArray()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                fragments.clear(); fragments.add(Fragment(0, "changed", -1, -1))
                return super.read(b, off, len)
            }
        }
        val document = ok(api.importSource(ImportRequest(collection.id, "opaque:x", "title", fragments = fragments), stream)).document
        assertEquals("safe", ok(api.fragments(document.id, 1)).single().text)
    }
    @Test fun fingerprintsUseRealStreamingShaAndBoundedConsumption() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", SourceFingerprint.compute("abc".byteInputStream(), 3).sha256)
        val stream = ByteArrayInputStream(ByteArray(100))
        try { SourceFingerprint.compute(stream, 4); fail("must refuse") } catch (_: SourceTooLarge) { }
        assertEquals(95, stream.available())
    }
    @Test fun duplicatesAcrossCollectionsRetainOneImmutableSource() = database { _, api, collection ->
        ok(add(api, collection)); val second = ok(api.createCollection("second")); ok(add(api, second))
        assertEquals(2, ok(api.documents()).size); assertEquals(1, ok(api.sources()).size)
    }
}
