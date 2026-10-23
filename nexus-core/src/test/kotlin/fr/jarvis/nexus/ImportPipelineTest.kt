package fr.jarvis.nexus

import fr.jarvis.nexus.api.*
import fr.jarvis.nexus.importing.*
import fr.jarvis.nexus.search.FtsIndex
import fr.jarvis.nexus.store.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ImportPipelineTest {
    private fun <T> ok(value: CatalogResult<T>): T = (value as CatalogResult.Ok).value
    private val textParser = ParserPort { _, bytes, stop -> stop.check(); listOf(ExtractedPage(null, bytes.toString(Charsets.UTF_8))) }
    private fun fixture(parser: ParserPort = textParser, body: (NexusDatabase, KnowledgeCatalogService, CollectionRecord, ImportCoordinator) -> Unit) {
        val directory = Files.createTempDirectory("nexus-import-synthetic-")
        try { NexusDatabase.open(directory.resolve("db.sqlite")).use { db ->
            val api = KnowledgeCatalogService(db, AccessPolicy { true })
            body(db, api, ok(api.createCollection("synthetic")), ImportCoordinator(db, api, ParserRegistry(mapOf("txt" to parser)), chunkChars = 64))
            assertTrue(db.integrityCheck())
        } } finally { Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
    private fun job(coordinator: ImportCoordinator, collection: CollectionRecord, text: String, expected: Int? = null) = ok(coordinator.enqueue(ImportSource(collection.id, "opaque:source", "source.txt", "Source", expected), text.byteInputStream()))
    @Test fun realFtsIndexesRawOffsetsAndUnicodeNormalizationOnlyInIndex() = fixture { db, _, collection, coordinator ->
        val text = "Cafe\u0301 secret\n" + "😀 scientific evidence ".repeat(9)
        val pending = job(coordinator, collection, text)
        val done = ok(coordinator.process(pending.id, text.byteInputStream()))
        assertEquals(ImportState.COMPLETED, done.state)
        val api = KnowledgeCatalogService(db, AccessPolicy { true })
        val hits = ok(FtsIndex(db, api).search("café"))
        assertEquals(1, hits.size)
        assertEquals(text.substring(hits.single().start, hits.single().end), hits.single().text)
        assertTrue(hits.single().text.contains("Cafe\u0301"))
    }
    @Test fun interruptedJobRequiresExplicitReconciliationAndResumesWithoutDuplicates() = fixture { db, api, collection, coordinator ->
        val pending = job(coordinator, collection, "searchable source")
        db.transaction { it.execute("UPDATE import_job SET state='PROCESSING' WHERE id=?", pending.id) }
        assertEquals(CatalogResult.Rejected(KnowledgeError.BUSY), coordinator.process(pending.id, "searchable source".byteInputStream()))
        assertEquals(1, ok(coordinator.recoverInterrupted()))
        assertEquals(ImportState.INTERRUPTED, ok(coordinator.job(pending.id)).state)
        val done = ok(coordinator.process(pending.id, "searchable source".byteInputStream()))
        ok(coordinator.process(pending.id, "ignored".byteInputStream()))
        assertEquals(1, ok(api.documents()).size)
        assertEquals(1, ok(api.revisions(done.documentId!!)).size)
    }
    @Test fun indexFailureRollsBackDocumentsAndFts() = fixture { db, api, collection, coordinator ->
        val text = "searchable source"
        val pending = job(coordinator, collection, text)
        // SQL trigger deterministically aborts the same transaction after document creation.
        db.read { c -> c.createStatement().use { it.execute("CREATE TRIGGER fail_index BEFORE INSERT ON import_page BEGIN SELECT RAISE(ABORT,'injected'); END") } }
        assertEquals(CatalogResult.Rejected(KnowledgeError.STORAGE_FAILURE), coordinator.process(pending.id, text.byteInputStream()))
        assertTrue(ok(api.documents()).isEmpty()); assertTrue(ok(FtsIndex(db, api).search("searchable")).isEmpty())
        assertEquals(ImportState.FAILED, ok(coordinator.job(pending.id)).state)
        db.read { c -> c.createStatement().use { it.execute("DROP TRIGGER fail_index") } }
        ok(coordinator.process(pending.id, text.byteInputStream()))
        assertEquals(1, ok(FtsIndex(db, api).search("searchable")).size)
    }
    @Test fun cancellationAfterPageInsertionRollsBackTheWholeRealTransaction() = fixture { db, api, collection, coordinator ->
        val token = CancellationToken()
        val pending = job(coordinator, collection, "source")
        db.read { c ->
            org.sqlite.Function.create(c, "cancel_after_page", object : org.sqlite.Function() {
                override fun xFunc() { token.cancel(); result(0) }
            })
            c.createStatement().use { it.execute("CREATE TRIGGER cancel_index AFTER INSERT ON import_page BEGIN SELECT cancel_after_page(); END") }
        }
        assertEquals(CatalogResult.Rejected(KnowledgeError.CANCELLED), coordinator.process(pending.id, "source".byteInputStream(), token))
        assertEquals(ImportState.CANCELLED, ok(coordinator.job(pending.id)).state)
        assertTrue(ok(api.documents()).isEmpty())
        assertTrue(ok(FtsIndex(db, api).search("source")).isEmpty())
        assertTrue(ok(api.sources()).isEmpty())
    }
    @Test fun cancelledParserCannotCommitAnyNewDocument() {
        val token = CancellationToken()
        fixture(ParserPort { _, bytes, _ -> token.cancel(); listOf(ExtractedPage(null, bytes.toString(Charsets.UTF_8))) }) { _, api, collection, coordinator ->
            val pending = job(coordinator, collection, "cancelled source")
            assertEquals(CatalogResult.Rejected(KnowledgeError.CANCELLED), coordinator.process(pending.id, "cancelled source".byteInputStream(), token))
            assertEquals(ImportState.CANCELLED, ok(coordinator.job(pending.id)).state)
            assertTrue(ok(api.documents()).isEmpty())
        }
    }
    @Test fun changedSourceAndUnsupportedArchiveAreRefusedWithoutImport() = fixture { _, api, collection, coordinator ->
        val pending = job(coordinator, collection, "original")
        assertEquals(CatalogResult.Rejected(KnowledgeError.CONFLICT), coordinator.process(pending.id, "tampered".byteInputStream()))
        assertEquals(CatalogResult.Rejected(KnowledgeError.UNSUPPORTED_FORMAT), coordinator.enqueue(ImportSource(collection.id,"opaque:zip","bad.zip","Bad"), byteArrayOf(1).inputStream()))
        assertTrue(ok(api.documents()).isEmpty())
    }
    @Test fun pendingQueueAndInputBytesAreBounded() = fixture { db, api, collection, _ ->
        val coordinator = ImportCoordinator(db, api, ParserRegistry(mapOf("txt" to textParser)), maxBytes = 4, maxPending = 1)
        job(coordinator, collection, "one")
        assertEquals(CatalogResult.Rejected(KnowledgeError.BUSY), coordinator.enqueue(ImportSource(collection.id,"opaque:two","x.txt","x"), "two".byteInputStream()))
        assertEquals(CatalogResult.Rejected(KnowledgeError.LIMIT_EXCEEDED), coordinator.enqueue(ImportSource(collection.id,"opaque:three","x.txt","x"), "12345".byteInputStream()))
    }
    @Test fun liveWorkerPreventsRecoveryAndKeepsCancellingUntilItReturns() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        fixture(ParserPort { _, bytes, _ -> entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); listOf(ExtractedPage(null, bytes.toString(Charsets.UTF_8))) }) { _, _, collection, coordinator ->
            val pending = job(coordinator, collection, "source")
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit<CatalogResult<ImportJob>> { coordinator.process(pending.id, "source".byteInputStream()) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertEquals(CatalogResult.Rejected(KnowledgeError.BUSY), coordinator.recoverInterrupted())
                assertEquals(ImportState.CANCELLING, ok(coordinator.cancel(pending.id)).state)
                release.countDown()
                assertEquals(CatalogResult.Rejected(KnowledgeError.CANCELLED), future.get(5, TimeUnit.SECONDS))
                assertEquals(ImportState.CANCELLED, ok(coordinator.job(pending.id)).state)
            } finally { release.countDown(); executor.shutdownNow() }
        }
    }
    @Test fun incrementalRevisionDropsOldHitsAndDeletionNeverReturnsStaleSources() = fixture { db, api, collection, coordinator ->
        val first = job(coordinator, collection, "oldphrase")
        val imported = ok(coordinator.process(first.id, "oldphrase".byteInputStream()))
        val second = job(coordinator, collection, "newphrase", 1)
        ok(coordinator.process(second.id, "newphrase".byteInputStream()))
        val index = FtsIndex(db, api)
        assertTrue(ok(index.search("oldphrase")).isEmpty()); assertEquals(1, ok(index.search("newphrase")).size)
        val deleted = ok(api.setDeleted(imported.documentId!!, 2, true))
        assertTrue(ok(index.search("newphrase")).isEmpty())
        val restored = ok(api.setDeleted(imported.documentId, deleted.version, false))
        assertTrue(ok(index.search("newphrase")).isEmpty()) // Restored version is PENDING, not silently asserted indexed.
        val reindex = job(coordinator, collection, "newphrase", restored.version)
        ok(coordinator.process(reindex.id, "newphrase".byteInputStream()))
        assertEquals(1, ok(index.search("newphrase")).size)
        assertEquals(restored.version, ok(api.documents()).single().version)
    }
    @Test fun parserCannotChangeTheSourceBytesBeingCommitted() {
        fixture(ParserPort { _, bytes, _ -> bytes.fill(0); listOf(ExtractedPage(null, "safe parser output")) }) { _, api, collection, coordinator ->
            val pending = job(coordinator, collection, "source")
            ok(coordinator.process(pending.id, "source".byteInputStream()))
            assertEquals(pending.sha256, ok(api.sources()).single().sha256)
        }
    }
    @Test fun unauthorizedPipelineDoesNotReadSourceOrParse() = fixture { db, _, collection, _ ->
        val api = KnowledgeCatalogService(db, AccessPolicy { false })
        val coordinator = ImportCoordinator(db, api, ParserRegistry(mapOf("txt" to textParser)))
        val forbidden = object : java.io.InputStream() { override fun read(): Int = error("must not read") }
        assertEquals(CatalogResult.Rejected(KnowledgeError.DENIED), coordinator.enqueue(ImportSource(collection.id,"opaque:x","x.txt","x"), forbidden))
    }
    @Test fun schemaV1WithExistingDocumentMigratesWithoutChangingHistoricalRows() {
        val root = Files.createTempDirectory("nexus-v1-fixture-")
        val path = root.resolve("old.db")
        try {
            java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
                val ddl = javaClass.getResource("/schema-v1.sql")!!.readText().lines().filterNot { it.startsWith("--") }.joinToString("\n")
                ddl.split(';').filter { it.isNotBlank() }.forEach { sql -> c.createStatement().use { it.execute(sql) } }
                c.execute("INSERT INTO collection VALUES('c','synthetic')")
                c.execute("INSERT INTO source VALUES('s','opaque:historic',?,4)", "0".repeat(64))
                c.execute("INSERT INTO document VALUES('d','c','opaque:historic','Old',1,0)")
                c.execute("INSERT INTO revision VALUES('d',1,'s','Old')")
                c.execute("INSERT INTO fragment VALUES('d',1,0,'text',0,4,NULL)")
                c.execute("INSERT INTO index_state VALUES('d',0,'PENDING')")
            }
            NexusDatabase.open(path).use { db ->
                val api = KnowledgeCatalogService(db, AccessPolicy { true })
                assertEquals("d", ok(api.documents()).single().id)
                assertEquals("text", ok(api.fragments("d",1)).single().text)
                assertTrue(db.integrityCheck())
                assertEquals(2, db.read { c -> c.query("PRAGMA user_version") { it.getInt(1) }.single() })
            }
        } finally { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
    @Test fun recoveryPreservesPriorCancellation() = fixture { db, _, collection, coordinator ->
        val pending = job(coordinator, collection, "source")
        db.transaction { it.execute("UPDATE import_job SET state='CANCELLING' WHERE id=?", pending.id) }
        ok(coordinator.recoverInterrupted())
        assertEquals(ImportState.CANCELLED, ok(coordinator.job(pending.id)).state)
        assertEquals(CatalogResult.Rejected(KnowledgeError.CANCELLED), coordinator.process(pending.id, "source".byteInputStream()))
    }
}
