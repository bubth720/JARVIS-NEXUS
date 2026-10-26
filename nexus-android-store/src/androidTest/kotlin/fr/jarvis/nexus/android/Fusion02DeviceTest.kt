package fr.jarvis.nexus.android

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.jarvis.nexus.api.*
import fr.jarvis.nexus.importing.*
import fr.jarvis.nexus.rag.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Fusion02DeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun directory()=File(instrumentation.targetContext.cacheDir,"fusion02-${UUID.randomUUID()}").also { check(it.mkdir()) }
    private fun digest(file: File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
    private fun snapshot(dir: File)=dir.listFiles()!!.associate { it.name to digest(it) }
    @Test fun probeAndAdmissionLeaveRefusedPersistentStateUntouched() {
        val dir=directory()
        try {
            val report=AndroidFts5Probe.inspect()
            println("FUSION02_PLATFORM_PROBE "+org.json.JSONObject().apply {
                put("api",report.apiLevel);put("abis",org.json.JSONArray(report.processAbis))
                put("sqliteVersion",report.sqliteVersion);put("status",report.status.name)
                put("stage",report.lastStage.name);put("compileOptions",org.json.JSONArray(report.compileOptions))
                put("pssKiB",android.os.Debug.getPss())
            })
            assertNotEquals(Fts5Status.UNKNOWN,report.status)
            if(report.status==Fts5Status.UNSUPPORTED) {
                assertEquals(StorageOpenResult.Rejected(OpenFailure.CAPABILITY_UNAVAILABLE),AndroidNexusStorage.open(dir))
                assertTrue(dir.listFiles()!!.isEmpty())
                SQLiteDatabase.openOrCreateDatabase(File(dir,"nexus.db"),null).use { db ->
                    db.execSQL("CREATE TABLE sentinel(body TEXT)");db.execSQL("INSERT INTO sentinel VALUES('original')")
                }
                val before=snapshot(dir)
                repeat(2) { assertEquals(StorageOpenResult.Rejected(OpenFailure.CAPABILITY_UNAVAILABLE),AndroidNexusStorage.open(dir)) }
                assertEquals(before,snapshot(dir))
            } else {
                assertEquals(Fts5Stage.COMPLETE,report.lastStage)
                (AndroidNexusStorage.open(dir) as StorageOpenResult.Ready).storage.close()
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun corruptionIsRefusedWithoutDeletingOriginal() {
        val dir=directory()
        try {
            val file=File(dir,"nexus.db").also { it.writeBytes(ByteArray(1024) { 88 }) }
            val before=snapshot(dir)
            assertEquals(StorageOpenResult.Rejected(OpenFailure.STORAGE_FAILURE),AndroidNexusStorage.open(dir))
            assertEquals(before,snapshot(dir))
        } finally { dir.deleteRecursively() }
    }
    @Test fun refusedCapabilityLeavesActiveWalAndSharedMemoryUnchanged() {
        if(AndroidFts5Probe.inspect().status!=Fts5Status.UNSUPPORTED) return
        val dir=directory()
        try {
            SQLiteDatabase.openOrCreateDatabase(File(dir,"nexus.db"),null).use { raw ->
                assertTrue(raw.enableWriteAheadLogging())
                raw.execSQL("CREATE TABLE sentinel(body TEXT)")
                raw.execSQL("INSERT INTO sentinel VALUES('original')")
                val before=snapshot(dir)
                assertTrue(before.keys.any { it.endsWith("-wal") })
                assertEquals(StorageOpenResult.Rejected(OpenFailure.CAPABILITY_UNAVAILABLE),AndroidNexusStorage.open(dir))
                assertEquals(before,snapshot(dir))
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun platformTransactionRollsBackAndSurvivesCloseReopen() {
        val dir=directory();val file=File(dir,"primitives.db")
        try {
            SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
                val session=AndroidSession(db)
                session.execute("CREATE TABLE fixture(id TEXT PRIMARY KEY,value TEXT)")
                db.beginTransaction()
                try { session.execute("INSERT INTO fixture VALUES(?,?)","stable","original");db.setTransactionSuccessful() }
                finally { db.endTransaction() }
                db.beginTransaction()
                try { session.execute("INSERT INTO fixture VALUES(?,?)","rollback","transient");throw IllegalStateException("injected interruption") }
                catch (_: IllegalStateException) { } finally { db.endTransaction() }
            }
            SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
                assertEquals(listOf("stable"),AndroidSession(db).query("SELECT id FROM fixture") { it.getString(1) })
            }
        } finally { dir.deleteRecursively() }
    }
    private fun selected(path: String)=SelectedDocument.fromUserSelection(Uri.parse("content://fr.jarvis.nexus.android.test.synthetic/$path"),"fixture.txt",true)
    @Test fun contentResolverPfdClosesAndRefusesDenialAndOversize() {
        val streams=SelectedDocumentStreams(instrumentation.context.contentResolver)
        var captured: InputStream?=null
        assertEquals(CatalogResult.Ok("Un phare bleu protège le port."),streams.open(selected("selected")) {
            captured=it;CatalogResult.Ok(it.readBytes().toString(Charsets.UTF_8))
        })
        try { captured!!.read();fail("Closed stream expected") } catch (_: java.io.IOException) { }
        assertEquals(CatalogResult.Rejected(KnowledgeError.DENIED),streams.open(selected("denied")) { CatalogResult.Ok(it.readBytes()) })
        assertEquals(CatalogResult.Rejected(KnowledgeError.LIMIT_EXCEEDED),streams.open(selected("oversized")) { CatalogResult.Ok(it.readBytes()) })
        // This provider is not DocumentsUI. Persisted SAF grants remain a separate gate.
    }
    @Test fun selectedSourceHashCheckDoesNotClaimCitationOrSafGrantQualification() {
        val selection=selected("selected")
        val bytes="Un phare bleu protège le port.".toByteArray(Charsets.UTF_8)
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val source=SourceRecord("synthetic-source",selection.uri.toString(),hash,bytes.size.toLong())
        val verifier=SelectedDocumentSourceVerifier(instrumentation.context.contentResolver)
        assertEquals(CatalogResult.Ok(Unit),verifier.verify(selection,source))
        assertEquals(CatalogResult.Rejected(KnowledgeError.CONFLICT),verifier.verify(selection,source.copy(sha256="0".repeat(64))))
        assertEquals(CatalogResult.Rejected(KnowledgeError.CONFLICT),verifier.verify(selection,source.copy(byteCount=source.byteCount+1)))
        assertEquals(CatalogResult.Rejected(KnowledgeError.CONFLICT),verifier.verify(selection,source.copy(uri="content://fr.jarvis.nexus.android.test.synthetic/changed")))
        assertEquals(CatalogResult.Rejected(KnowledgeError.DENIED),verifier.verify(selected("denied"),source.copy(uri=selected("denied").uri.toString())))
    }
    @Test fun contentUriRagReopensOrReportsUnsupportedWithoutFakeCitation() {
        val dir=directory()
        try {
            val opened=AndroidNexusStorage.open(dir)
            if(opened is StorageOpenResult.Rejected) {
                assertEquals(OpenFailure.CAPABILITY_UNAVAILABLE,opened.reason)
                assertTrue(dir.listFiles()!!.isEmpty())
                println("FUSION02_CONTENT_RAG UNSUPPORTED: no citation generated")
                return
            }
            val selection=selected("selected")
            val citation: Citation
            val collection: String
            (opened as StorageOpenResult.Ready).storage.use { db ->
                val catalog=KnowledgeCatalogService(db,AccessPolicy { true })
                collection=(catalog.createCollection("FUSION02 synthetic") as CatalogResult.Ok).value.id
                val imports=ImportCoordinator(db,catalog,ParserRegistry(mapOf("txt" to ParserPort { _,bytes,stop ->
                    stop.check();listOf(ExtractedPage(null,bytes.toString(Charsets.UTF_8)))
                })))
                val importer=SelectedDocumentImporter(instrumentation.context.contentResolver,imports)
                val queued=importer.enqueue(selection,collection,"Selected synthetic TXT") as CatalogResult.Ok
                assertTrue(importer.process(selection,queued.value) is CatalogResult.Ok)
                val rag=LocalRagService(db,catalog) { SearchScope(setOf(collection)) }
                val found=rag.retrieve(KnowledgeQuery("phare"),SearchScope(setOf(collection))) as CatalogResult.Ok
                citation=found.value.items.single().citations.single()
                assertEquals(selection.uri.toString(),citation.sourceUri)
            }
            (AndroidNexusStorage.open(dir) as StorageOpenResult.Ready).storage.use { db ->
                val rag=LocalRagService(db,KnowledgeCatalogService(db,AccessPolicy { true })) { SearchScope(setOf(collection)) }
                val resolved=rag.resolve(citation,SearchScope(setOf(collection))) as CatalogResult.Ok
                assertTrue(resolved.value.contains("phare bleu"))
            }
            println("FUSION02_CONTENT_RAG PASS: synthetic provider, not persisted SAF")
        } finally { dir.deleteRecursively() }
    }
}
