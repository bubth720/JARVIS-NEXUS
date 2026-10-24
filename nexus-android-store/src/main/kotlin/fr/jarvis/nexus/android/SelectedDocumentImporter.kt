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
class SelectedDocumentImporter(resolver: ContentResolver, private val imports: ImportCoordinator) {
    private val streams = SelectedDocumentStreams(resolver)
    fun enqueue(selection: SelectedDocument, collection: String, title: String): CatalogResult<ImportJob> =
        streams.open(selection) { imports.enqueue(ImportSource(collection, selection.uri.toString(), selection.name, title), it) }
    /** Reopens only the selected URI; coordinator rechecks its original digest and bounded length. */
    fun process(selection: SelectedDocument, job: ImportJob, cancellation: Cancellation = Cancellation { false }): CatalogResult<ImportJob> {
        if (job.source.uri != selection.uri.toString() || job.source.name != selection.name) return CatalogResult.Rejected(KnowledgeError.CONFLICT)
        return streams.open(selection) { imports.process(job.id, it, cancellation) }
    }
}
