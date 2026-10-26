package fr.jarvis.nexus.android

import android.content.ContentResolver
import android.net.Uri
import fr.jarvis.nexus.api.*
import fr.jarvis.nexus.importing.*
import java.io.InputStream

/** Host must construct only from a user-confirmed SAF result, not a document/model instruction. */
class SelectedDocument private constructor(val uri: Uri, val name: String) {
    companion object {
        fun fromUserSelection(uri: Uri, name: String, confirmed: Boolean): SelectedDocument {
            require(confirmed && uri.scheme == "content" && !uri.authority.isNullOrBlank())
            require(name.length in 1..200 && '\u0000' !in name)
            return SelectedDocument(uri, name)
        }
    }
}

/** No directory scan, persisted URI grant, background import or eager archive copy. */
internal class SelectedDocumentStreams(private val resolver: ContentResolver, private val maxBytes: Int = 2 * 1024 * 1024) {
    init { require(maxBytes in 1..2 * 1024 * 1024) }
    fun <T> open(selection: SelectedDocument, action: (InputStream) -> CatalogResult<T>): CatalogResult<T> {
        return try {
            val descriptor = resolver.openFileDescriptor(selection.uri, "r")
                ?: return CatalogResult.Rejected(KnowledgeError.NOT_FOUND)
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                val bounded = object : java.io.FilterInputStream(stream) {
                    private var count = 0
                    override fun read(): Int = stream.read().also { if (it >= 0 && ++count > maxBytes) throw fr.jarvis.nexus.store.SourceTooLarge() }
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        val read = stream.read(bytes, offset, minOf(length, maxBytes - count + 1))
                        if (read > 0) { count += read; if (count > maxBytes) throw fr.jarvis.nexus.store.SourceTooLarge() }
                        return read
                    }
                    override fun skip(n: Long): Long {
                        // A skipped byte still counts toward the bound; never delegate an unbounded skip.
                        if (n <= 0) return 0
                        var skipped = 0L
                        while (skipped < n && read() >= 0) skipped++
                        return skipped
                    }
                    // Consumers may size a buffer from available(); never expose a
                    // provider's unbounded size as a memory allocation suggestion.
                    override fun available(): Int = minOf(stream.available(), maxOf(0, maxBytes - count))
                    override fun markSupported() = false
                    override fun reset(): Unit = throw java.io.IOException("Bounded stream cannot reset")
                }
                action(bounded)
            }
        } catch (_: SecurityException) { CatalogResult.Rejected(KnowledgeError.DENIED) }
          catch (_: fr.jarvis.nexus.store.SourceTooLarge) { CatalogResult.Rejected(KnowledgeError.LIMIT_EXCEEDED) }
          catch (_: java.io.IOException) { CatalogResult.Rejected(KnowledgeError.STORAGE_FAILURE) }
    }
}
class SelectedDocumentImporter(private val resolver: ContentResolver, private val imports: ImportCoordinator) {
    private val streams = SelectedDocumentStreams(resolver)
    /** Optional owner action after ACTION_OPEN_DOCUMENT. No WRITE grant, implicit
     * retention or permission inferred from document/model content. Actual provider
     * support/grants remain Android authority and may be revoked at any time.
     */
    fun retainReadSelection(selection: SelectedDocument, returnedFlags: Int, confirmed: Boolean): CatalogResult<Unit> {
        val read=android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        val persist=android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        if(!confirmed || returnedFlags and read==0 || returnedFlags and persist==0) return CatalogResult.Rejected(KnowledgeError.DENIED)
        return try {
            resolver.takePersistableUriPermission(selection.uri,read)
            if(resolver.persistedUriPermissions.none { it.uri==selection.uri && it.isReadPermission }) CatalogResult.Rejected(KnowledgeError.DENIED)
            else CatalogResult.Ok(Unit)
        } catch(_: SecurityException) { CatalogResult.Rejected(KnowledgeError.DENIED) }
          catch(_: UnsupportedOperationException) { CatalogResult.Rejected(KnowledgeError.UNSUPPORTED_FORMAT) }
    }
    fun enqueue(selection: SelectedDocument, collection: String, title: String): CatalogResult<ImportJob> =
        streams.open(selection) { imports.enqueue(ImportSource(collection, selection.uri.toString(), selection.name, title), it) }
    /** Reopens only the selected URI; coordinator rechecks its original digest and bounded length. */
    fun process(selection: SelectedDocument, job: ImportJob, cancellation: Cancellation = Cancellation { false }): CatalogResult<ImportJob> {
        if (job.source.uri != selection.uri.toString() || job.source.name != selection.name) return CatalogResult.Rejected(KnowledgeError.CONFLICT)
        return streams.open(selection) { imports.process(job.id, it, cancellation) }
    }
}

/** Re-read selected bytes against an already authorized catalog source. This is
 * source availability/integrity only, not citation/scope authorization. The host
 * must also resolve citations through RagContextPort and recheck its authority.
 * A provider can change/revoke content after this read: no durable URI lease or
 * atomic external-provider snapshot is promised.
 */
class SelectedDocumentSourceVerifier(resolver: ContentResolver) {
    private val streams = SelectedDocumentStreams(resolver)
    fun verify(selection: SelectedDocument, source: SourceRecord): CatalogResult<Unit> {
        if (selection.uri.toString() != source.uri) return CatalogResult.Rejected(KnowledgeError.CONFLICT)
        if (!source.sha256.matches(Regex("[a-f0-9]{64}")) || source.byteCount < 0)
            return CatalogResult.Rejected(KnowledgeError.INVALID_INPUT)
        return streams.open(selection) {
            val actual = fr.jarvis.nexus.store.SourceFingerprint.compute(it, 2L * 1024 * 1024)
            if (actual.sha256 == source.sha256 && actual.bytes == source.byteCount) CatalogResult.Ok(Unit)
            else CatalogResult.Rejected(KnowledgeError.CONFLICT)
        }
    }
}
