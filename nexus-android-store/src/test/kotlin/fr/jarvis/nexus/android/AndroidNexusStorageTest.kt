package fr.jarvis.nexus.android

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import fr.jarvis.nexus.api.*
import fr.jarvis.nexus.store.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

/** Host-native SQLite without FTS5. These API24/25/26/35 simulations do NOT qualify device RAG. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24,25,26,29,35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AndroidNexusStorageTest {
    @get:Rule val temp = TemporaryFolder()
    /** Test-only raw storage fixture. No production factory can bypass the FTS admission probe. */
    private fun rawFixture(): AndroidNexusStorage {
        val file=File(temp.root,"raw-fixture.db")
        val raw=SQLiteDatabase.openOrCreateDatabase(file,null)
        raw.execSQL("CREATE TABLE IF NOT EXISTS fixture(id TEXT PRIMARY KEY,value TEXT)")
        val constructor=AndroidNexusStorage::class.java.getDeclaredConstructor(SQLiteDatabase::class.java,File::class.java)
        constructor.isAccessible=true
        return constructor.newInstance(raw,file)
    }
    @Test fun hostFtsAbsenceIsRefusedRatherThanClaimingNativeCitation() {
        assertEquals(StorageOpenResult.Rejected(OpenFailure.CAPABILITY_UNAVAILABLE),AndroidNexusStorage.open(temp.root))
        assertEquals(CatalogResult.Rejected(KnowledgeError.DENIED),SyntheticNexusDemo.run(temp.root,false))
        // Established NEX-03 error enum is unchanged. Opening reports the precise capability refusal.
        assertEquals(CatalogResult.Rejected(KnowledgeError.STORAGE_FAILURE),SyntheticNexusDemo.run(temp.root,true))
        assertTrue("Capability refusal must not create any persistent file", temp.root.listFiles()!!.isEmpty())
    }
    @Test fun rawStorageTransactionCrudRollbackCloseAndReopenAreReal() {
        val db=rawFixture()
        db.inTransaction { c -> c.execute("INSERT INTO fixture VALUES(?,?)","stable","original") }
        try {
            db.inTransaction { c -> c.execute("INSERT INTO fixture VALUES(?,?)","rollback","transient"); error("simulated interruption") }
            fail("Expected injected failure")
        } catch (_: IllegalStateException) { }
        assertTrue(db.readSession { it.query("SELECT id FROM fixture WHERE id=?","rollback") { it.getString(1) } }.isEmpty())
        assertEquals(listOf("original"),db.readSession { it.query("SELECT value FROM fixture WHERE id=?","stable") { row -> row.getString(1) } })
        db.close()
        assertEquals(CatalogResult.Rejected(KnowledgeError.STORAGE_FAILURE),KnowledgeCatalogService(db,AccessPolicy { true }).documents())
        rawFixture().use { reopened -> assertEquals(listOf("original"),reopened.readSession { it.query("SELECT value FROM fixture WHERE id=?","stable") { row -> row.getString(1) } }) }
    }
    @Test fun migrationInterruptionAndMissingFtsRollbackAllTablesAndVersion() {
        val file=File(temp.root,"migration.db")
        val raw=SQLiteDatabase.openOrCreateDatabase(file,null)
        raw.setForeignKeyConstraintsEnabled(true)
        raw.execSQL("CREATE TABLE sentinel(value TEXT)")
        raw.execSQL("INSERT INTO sentinel VALUES('original')")
        val storage=object : NexusStoragePort {
            override fun <T> readSession(block: (NexusSession) -> T): T = block(AndroidSession(raw))
            override fun <T> inTransaction(block: (NexusSession) -> T): T {
                raw.beginTransaction()
                try { val value=block(AndroidSession(raw));raw.setTransactionSuccessful();return value }
                finally { raw.endTransaction() }
            }
            override fun acquireImportLease(): AutoCloseable? = error("Migration only")
            override fun close() = raw.close()
        }
        storage.use {
            try { NexusSchema.migrate(it) { step -> if (step == 4) error("injected migration interruption") };fail("Expected interruption") }
            catch (_: IllegalStateException) { }
            assertEquals(0,raw.version)
            assertTrue(it.readSession { c -> c.query("SELECT name FROM sqlite_master WHERE name='collection'") { row -> row.getString(1) } }.isEmpty())
            try { NexusSchema.migrate(it);fail("Host does not provide FTS5") }
            catch (failure: SQLiteException) { assertTrue(failure.message.orEmpty().contains("fts5")) }
            assertEquals(0,raw.version)
            assertTrue(it.readSession { c -> c.query("SELECT name FROM sqlite_master WHERE name='collection'") { row -> row.getString(1) } }.isEmpty())
            assertEquals(listOf("original"),it.readSession { c -> c.query("SELECT value FROM sentinel") { row -> row.getString(1) } })
        }
    }
    @Test fun incompatibleDatabaseIsRefusedWithoutMigration() {
        val file=File(temp.root,"nexus.db")
        SQLiteDatabase.openOrCreateDatabase(file,null).use { raw ->raw.execSQL("PRAGMA user_version=99");raw.execSQL("CREATE TABLE untouched(value TEXT)");raw.execSQL("INSERT INTO untouched VALUES('preserve')") }
        assertEquals(StorageOpenResult.Rejected(OpenFailure.INCOMPATIBLE_VERSION),AndroidNexusStorage.open(temp.root))
        SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertEquals(99,raw.version)
            raw.rawQuery("SELECT value FROM untouched",null).use { assertTrue(it.moveToFirst());assertEquals("preserve",it.getString(0)) }
        }
    }
    @Test fun rawStorageLeaseExcludesOtherConnectionAndAccessDenialPrecedesSql() {
        rawFixture().use { a ->rawFixture().use { b ->
            val lease=a.acquireImportLease()!!
            assertNull(b.acquireImportLease())
            lease.close();b.acquireImportLease()!!.close()
            val catalog=KnowledgeCatalogService(a,AccessPolicy { false })
            assertEquals(CatalogResult.Rejected(KnowledgeError.DENIED),catalog.createCollection("refused"))
            assertEquals(CatalogResult.Rejected(KnowledgeError.DENIED),catalog.documents())
            // Raw fixture deliberately has no NEXUS schema: no fake RAG success is possible here.
        } }
    }
    @Test fun selectedUriRejectsImplicitOrFilesystemAccess() {
        for (uri in listOf("file:///tmp/private.txt","https://example.com/doc")) {
            try { SelectedDocument.fromUserSelection(android.net.Uri.parse(uri),"a.txt",true);fail("Unselected filesystem/network") } catch (_: IllegalArgumentException) { }
        }
        try { SelectedDocument.fromUserSelection(android.net.Uri.parse("content://fixture/a"),"a.txt",false);fail("Unconfirmed") } catch (_: IllegalArgumentException) { }
    }
}
