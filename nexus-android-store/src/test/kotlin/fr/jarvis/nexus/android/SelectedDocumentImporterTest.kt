package fr.jarvis.nexus.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import fr.jarvis.nexus.api.*
import fr.jarvis.nexus.importing.*
import fr.jarvis.nexus.rag.*
import fr.jarvis.nexus.store.SourceFingerprint
import fr.jarvis.nexus.store.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

/** Real bounded PFD reads, simulated Android framework and host SQLite, synthetic files only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24,25,26,35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SelectedDocumentImporterTest {
    @get:Rule val temp = TemporaryFolder()
    class FixtureProvider : ContentProvider() {
        lateinit var source: File
        val descriptors = ArrayList<ParcelFileDescriptor>()
        var denied = false
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (denied) throw SecurityException("synthetic denial")
            check(mode == "r")
            return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).also { descriptors.add(it) }
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri) = "text/plain"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read only")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("Read only")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("Read only")
    }
    @Test fun selectedPfdIsReadAndClosedWithBoundedDigest() {
        val provider=Robolectric.buildContentProvider(FixtureProvider::class.java).create("nex06a").get()
        provider.source=File(temp.root,"fixture.txt").also { it.writeText("Un phare bleu protège le port.") }
        ShadowContentResolver.registerProviderInternal("nex06a",provider)
        val streams=SelectedDocumentStreams(RuntimeEnvironment.getApplication().contentResolver)
        val selection=SelectedDocument.fromUserSelection(Uri.parse("content://nex06a/selected"),"fixture.txt",true)
        val result=streams.open(selection) { CatalogResult.Ok(SourceFingerprint.compute(it,1024)) }
        assertEquals(CatalogResult.Ok(provider.source.inputStream().use { SourceFingerprint.compute(it,1024) }),result)
        assertEquals(1,provider.descriptors.size)
        provider.descriptors.forEach { descriptor ->
            try { descriptor.fd; fail("PFD must be closed") } catch (_: IllegalStateException) { }
        }
    }
    @Test fun deniedAndOversizedSelectedContentAreRefusedAndDescriptorsClosed() {
        val provider=Robolectric.buildContentProvider(FixtureProvider::class.java).create("nex06a").get()
        provider.source=File(temp.root,"fixture.txt").also { it.writeText("123456789") }
        ShadowContentResolver.registerProviderInternal("nex06a",provider)
        val streams=SelectedDocumentStreams(RuntimeEnvironment.getApplication().contentResolver,maxBytes=8)
        val selection=SelectedDocument.fromUserSelection(Uri.parse("content://nex06a/selected"),"fixture.txt",true)
        assertEquals(CatalogResult.Rejected(KnowledgeError.LIMIT_EXCEEDED),streams.open(selection) { CatalogResult.Ok(it.readBytes()) })
        provider.denied=true
        assertEquals(CatalogResult.Rejected(KnowledgeError.DENIED),streams.open(selection) { CatalogResult.Ok(it.readBytes()) })
        provider.descriptors.forEach { descriptor -> try { descriptor.fd; fail("PFD leaked") } catch (_: IllegalStateException) { } }
    }
    @Test fun retainingReadPermissionRequiresExactOwnerConsentAndProviderFlags() {
        // This double is never used for SQL; it asserts that permission refusal
        // precedes any document import, even when the provider is not connected.
        val unopened=object : NexusStoragePort {
            override fun <T> readSession(block:(NexusSession)->T):T = error("No SQL expected")
            override fun <T> inTransaction(block:(NexusSession)->T):T = error("No SQL expected")
            override fun acquireImportLease():AutoCloseable? = error("No import expected")
            override fun close() = Unit
        }
        val api=KnowledgeCatalogService(unopened,AccessPolicy { false })
        val importer=SelectedDocumentImporter(RuntimeEnvironment.getApplication().contentResolver,
            ImportCoordinator(unopened,api,ParserRegistry(emptyMap())))
        val selected=SelectedDocument.fromUserSelection(Uri.parse("content://nex06a/selected"),"fixture.txt",true)
        val read=android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        val persist=android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        assertEquals(CatalogResult.Rejected(KnowledgeError.DENIED),importer.retainReadSelection(selected,read or persist,false))
        assertEquals(CatalogResult.Rejected(KnowledgeError.DENIED),importer.retainReadSelection(selected,read,true))
        assertEquals(CatalogResult.Rejected(KnowledgeError.DENIED),importer.retainReadSelection(selected,persist or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,true))
    }
}
