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
}
