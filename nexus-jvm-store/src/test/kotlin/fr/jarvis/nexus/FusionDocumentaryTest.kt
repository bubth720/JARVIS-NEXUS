package fr.jarvis.nexus

import fr.jarvis.nexus.api.*
import fr.jarvis.nexus.importing.*
import fr.jarvis.nexus.rag.*
import fr.jarvis.nexus.store.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/** Real SQLite/FTS plus synthetic documents. No CORE/Android/LLM integration claim. */
class FusionDocumentaryTest {
    private fun <T> ok(result: CatalogResult<T>): T = (result as CatalogResult.Ok).value
    private fun fixture(action:(NexusDatabase,KnowledgeCatalogService,SearchScope,DocumentaryGateway)->Unit) {
        val dir=Files.createTempDirectory("fusion-documentary-")
        try { NexusDatabase.open(dir.resolve("nexus.db")).use { db ->
            val catalog=KnowledgeCatalogService(db,AccessPolicy { true })
            val collection=ok(catalog.createCollection("FIXTURE"));val scope=SearchScope(setOf(collection.id))
            val parser=StructuredParser().registry();val importer=ImportCoordinator(db,catalog,parser)
            val content="concept,preuve\nphare,Le phare bleu protège le port.\n".toByteArray()
            val job=ok(importer.enqueue(ImportSource(collection.id,"fixture:fusion.csv","fixture.csv","Fixture"),content.inputStream()))
            ok(importer.process(job.id,content.inputStream()))
            action(db,catalog,scope,DocumentaryGateway(db,catalog,{scope},{100}))
        } } finally { Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
    private fun call() = DocumentaryCall(1000,{true})
    @Test fun realDocumentAndEveryCitationSurviveCloseAndReopen() {
        val dir=Files.createTempDirectory("fusion-reopen-");val file=dir.resolve("fixture.csv")
        val bytes="concept,preuve\nphare,Le phare bleu protège le port.\n".toByteArray();Files.write(file,bytes)
        try {
            val pair=NexusDatabase.open(dir.resolve("nexus.db")).use { db ->
                val api=KnowledgeCatalogService(db,AccessPolicy { true });val c=ok(api.createCollection("Reopen"))
                val imports=ImportCoordinator(db,api,StructuredParser().registry());val job=Files.newInputStream(file).use { ok(imports.enqueue(ImportSource(c.id,file.toUri().toString(),"fixture.csv","Fixture"),it)) }
                Files.newInputStream(file).use { ok(imports.process(job.id,it)) }
                val scope=SearchScope(setOf(c.id));val result=DocumentaryGateway(db,api,{scope},{100}).retrieve(KnowledgeQuery("phare"),scope,call()) as DocumentaryResult.Ready
                scope to result.bundle.items.single().citations.single()
            }
            NexusDatabase.open(dir.resolve("nexus.db")).use { db ->
                val api=KnowledgeCatalogService(db,AccessPolicy { true });val resolved=ok(LocalRagService(db,api) { pair.first }.resolve(pair.second,pair.first))
                assertTrue(resolved.contains("phare bleu"));assertEquals(file.toUri().toString(),pair.second.sourceUri)
                assertEquals(sha256(bytes.toString(Charsets.UTF_8)),pair.second.sourceSha256)
                assertEquals(1,pair.second.version);assertEquals(sha256(resolved),pair.second.textSha256)
            }
            assertArrayEquals(bytes,Files.readAllBytes(file))
        } finally { Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
    @Test fun denyCancellationAndExpiredDeadlinePrecedeSql()=fixture { _,_,scope,gateway ->
        assertEquals(DocumentaryResult.Refused(DocumentaryRefusal.DENIED),gateway.retrieve(KnowledgeQuery("phare"),scope,DocumentaryCall(1000)))
        assertEquals(DocumentaryResult.Refused(DocumentaryRefusal.CANCELLED),gateway.retrieve(KnowledgeQuery("phare"),scope,DocumentaryCall(1000,{true},{true})))
        assertEquals(DocumentaryResult.Refused(DocumentaryRefusal.DEADLINE_EXCEEDED),gateway.retrieve(KnowledgeQuery("phare"),scope,DocumentaryCall(100,{true})))
    }
    @Test fun indexPendingIsExplicitAndExistingIndexIsNeverRebuilt()=fixture { db,_,scope,gateway ->
        val rows=db.read { it.query("SELECT count(*) FROM fragment_fts") { r -> r.getInt(1) }.single() }
        db.transaction { it.execute("UPDATE index_state SET state='PENDING'") }
        assertEquals(DocumentaryResult.Refused(DocumentaryRefusal.INDEX_MISSING),gateway.retrieve(KnowledgeQuery("phare"),scope,call()))
        assertEquals(rows,db.read { it.query("SELECT count(*) FROM fragment_fts") { r -> r.getInt(1) }.single() })
    }
    @Test fun missingPhysicalFtsTableIsExplicit()=fixture { db,_,scope,gateway ->
        db.transaction { it.execute("DROP TABLE fragment_vocab");it.execute("DROP TABLE fragment_fts") }
        assertEquals(DocumentaryResult.Refused(DocumentaryRefusal.INDEX_MISSING),gateway.retrieve(KnowledgeQuery("phare"),scope,call()))
    }
    @Test fun permissionRevocationDuringIndexInspectionNeverReturnsReady()=fixture { db,catalog,scope,_ ->
        var granted=true
        val wrapped=object : NexusStoragePort by db {
            override fun <T> readSession(block:(NexusSession)->T):T = db.readSession { session -> block(session).also { granted=false } }
        }
        val gateway=DocumentaryGateway(wrapped,catalog,{scope},{100})
        assertEquals(DocumentaryResult.Refused(DocumentaryRefusal.DENIED),gateway.retrieve(KnowledgeQuery("phare"),scope,DocumentaryCall(1000,{granted})))
    }
    @Test fun deadlineBecomingExpiredWhileReadingNeverReturnsReady()=fixture { db,catalog,scope,_ ->
        var now=0L
        val wrapped=object : NexusStoragePort by db {
            override fun <T> readSession(block:(NexusSession)->T):T = db.readSession { session -> block(session).also { now=1000 } }
        }
        assertEquals(DocumentaryResult.Refused(DocumentaryRefusal.DEADLINE_EXCEEDED),DocumentaryGateway(wrapped,catalog,{scope},{now}).retrieve(KnowledgeQuery("phare"),scope,call()))
    }
    @Test fun realUtf8CarrierBudgetAndWrongScopeAreRefused()=fixture { _,_,scope,gateway ->
        assertEquals(DocumentaryResult.Refused(DocumentaryRefusal.BYTE_BUDGET),gateway.retrieve(KnowledgeQuery("phare"),scope,call(),maxUtf8Bytes=256))
        val rejected=gateway.retrieve(KnowledgeQuery("phare"),SearchScope(setOf("foreign")),call()) as DocumentaryResult.Refused
        assertEquals(DocumentaryRefusal.DENIED,rejected.reason)
    }
    @Test fun corruptedFragmentIsNotResolvedAsOriginalCitation()=fixture { db,catalog,scope,gateway ->
        val result=gateway.retrieve(KnowledgeQuery("phare"),scope,call()) as DocumentaryResult.Ready
        val citation=result.bundle.items.single().citations.single()
        db.transaction { it.execute("UPDATE fragment SET text='corrupt'") }
        assertEquals(CatalogResult.Rejected(KnowledgeError.CONFLICT),LocalRagService(db,catalog) { scope }.resolve(citation,scope))
    }
    @Test fun emptyCollectionHasNoEvidenceRatherThanInventedReadiness()=fixture { db,catalog,_,_ ->
        val scope=SearchScope(setOf(ok(catalog.createCollection("empty")).id))
        val result=DocumentaryGateway(db,catalog,{scope},{100}).retrieve(KnowledgeQuery("phare"),scope,call()) as DocumentaryResult.Refused
        assertEquals(KnowledgeError.NO_EVIDENCE,result.knowledgeError)
    }
    @Test fun anotherConnectionCannotRewriteProvenanceInsideTheReadSnapshot()=fixture { db,catalog,scope,_ ->
        var attempted=false;var blocked=false
        val wrapped=object : NexusStoragePort by db {
            override fun <T> readSession(block:(NexusSession)->T):T = db.readSession { session ->
                block(session).also {
                    if(!attempted) {
                        attempted=true
                        java.sql.DriverManager.getConnection("jdbc:sqlite:"+db.path).use { other ->
                            other.createStatement().use { it.execute("PRAGMA busy_timeout=1") }
                            try { other.execute("UPDATE source SET uri='fixture:concurrent-corruption'") }
                            catch(_: java.sql.SQLException) { blocked=true }
                        }
                    }
                }
            }
        }
        val result=DocumentaryGateway(wrapped,catalog,{scope},{100}).retrieve(KnowledgeQuery("phare"),scope,call())
        assertTrue(result is DocumentaryResult.Ready);assertTrue(attempted);assertTrue(blocked)
        assertEquals("fixture:fusion.csv",(result as DocumentaryResult.Ready).bundle.items.single().citations.single().sourceUri)
    }
}
