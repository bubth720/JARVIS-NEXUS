package fr.jarvis.nexus.android

import android.net.Uri
import fr.jarvis.nexus.api.*
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

/**
 * NEXUS-00: the NEX-07 quota stays enforced while CI guard scope is repaired.
 * Normal / exactly-at-limit / exceeded, for the bounded stream and the
 * verifier preflight (which must refuse before any descriptor is opened).
 * Additive test only; production sources and historical tests are unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class QuotaPreflightBoundaryTest {
    @get:Rule val temp = TemporaryFolder()

    private fun provider(content: ByteArray): SelectedDocumentImporterTest.FixtureProvider {
        val p = Robolectric.buildContentProvider(SelectedDocumentImporterTest.FixtureProvider::class.java).create("nexquota").get()
        p.source = File(temp.root, "q.bin").also { it.writeBytes(content) }
        ShadowContentResolver.registerProviderInternal("nexquota", p)
        return p
    }

    private val selection get() = SelectedDocument.fromUserSelection(Uri.parse("content://nexquota/selected"), "q.bin", true)

    private fun assertClosed(p: SelectedDocumentImporterTest.FixtureProvider) =
        p.descriptors.forEach { d -> try { d.fd; fail("PFD leaked") } catch (_: IllegalStateException) { } }

    @Test fun boundedStreamAcceptsBelowAndAtLimitAndRefusesAboveLimit() {
        for ((size, expectOk) in listOf(7 to true, 8 to true, 9 to false)) {
            val p = provider(ByteArray(size) { 'a'.code.toByte() })
            val streams = SelectedDocumentStreams(RuntimeEnvironment.getApplication().contentResolver, maxBytes = 8)
            val result = streams.open(selection) { CatalogResult.Ok(it.readBytes()) }
            if (expectOk) {
                assertTrue("size=$size must be accepted", result is CatalogResult.Ok)
                assertEquals(size, ((result as CatalogResult.Ok).value).size)
            } else {
                assertEquals("size=$size must be refused", CatalogResult.Rejected(KnowledgeError.LIMIT_EXCEEDED), result)
            }
            assertClosed(p)
        }
    }

    @Test fun verifierPreflightRefusesOnlyAboveTwoMebibytesAndWithoutDescriptorIo() {
        val content = "Quota NEXUS".toByteArray()
        val p = provider(content)
        val fingerprint = content.inputStream().use { SourceFingerprint.compute(it, 1024) }
        val source = SourceRecord("quota-source", selection.uri.toString(), fingerprint.sha256, fingerprint.bytes)
        val verifier = SelectedDocumentSourceVerifier(RuntimeEnvironment.getApplication().contentResolver)

        // Normal: verified, one descriptor opened and closed.
        assertEquals(CatalogResult.Ok(Unit), verifier.verify(selection, source))
        assertEquals(1, p.descriptors.size)

        // Exceeded: refused before asking the provider for bytes.
        val before = p.descriptors.size
        assertEquals(CatalogResult.Rejected(KnowledgeError.LIMIT_EXCEEDED),
            verifier.verify(selection, source.copy(byteCount = 2L * 1024 * 1024 + 1)))
        assertEquals("Over-quota must not open a descriptor", before, p.descriptors.size)

        // Exactly at limit: the preflight lets it through to real verification,
        // which then reports the size mismatch as CONFLICT (not LIMIT_EXCEEDED).
        assertEquals(CatalogResult.Rejected(KnowledgeError.CONFLICT),
            verifier.verify(selection, source.copy(byteCount = 2L * 1024 * 1024)))
        assertEquals(before + 1, p.descriptors.size)
        assertClosed(p)
    }
}
