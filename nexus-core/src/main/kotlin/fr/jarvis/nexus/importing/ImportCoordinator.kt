package fr.jarvis.nexus.importing

import fr.jarvis.nexus.api.*
import fr.jarvis.nexus.store.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.text.Normalizer
import java.util.UUID

/** Single parser worker per database (OS sidecar lock), bounded inputs, no background thread. */
class ImportCoordinator(
    private val database: NexusDatabase,
    private val catalog: KnowledgeCatalogService,
    private val parsers: ParserRegistry,
    private val maxBytes: Int = 2 * 1024 * 1024,
    private val maxPending: Int = 8,
    private val chunkChars: Int = 2048,
    private val maxDatabaseBytes: Long = 64L * 1024 * 1024,
    private val minFreeBytes: Long = 16L * 1024 * 1024,
    private val freeBytes: () -> Long = { java.nio.file.Files.getFileStore(database.path.parent).usableSpace },
) {
    init { require(maxBytes in 1..2 * 1024 * 1024); require(maxPending in 1..64); require(chunkChars in 64..8192); require(maxDatabaseBytes in 1024 * 1024..1024L * 1024 * 1024 * 1024 && minFreeBytes in 0..1024L * 1024 * 1024 * 1024) }
    private fun quota(c: Connection, reserve: Long = 0) {
        val allocated = c.query("PRAGMA page_count") { it.getLong(1) }.single() * c.query("PRAGMA page_size") { it.getLong(1) }.single()
        if (allocated > maxDatabaseBytes - reserve || freeBytes() < minFreeBytes + reserve) throw ParserFailure(KnowledgeError.LIMIT_EXCEEDED)
    }
    private fun metadata(c: Connection, job: ImportJob, documentId: String) {
        val pending = c.query("SELECT * FROM import_metadata WHERE job_id=?", job.id) { r ->
            listOf(r.getString("media_type"), r.getObject("modified_ms"), r.getString("category"), r.getString("tags"))
        }.singleOrNull() ?: listOf(job.source.name.substringAfterLast('.').lowercase(java.util.Locale.ROOT), null, "", "")
        c.execute("INSERT INTO document_metadata VALUES(?,?,?,?,?,?,?) ON CONFLICT(document_id) DO UPDATE SET name=excluded.name,media_type=excluded.media_type,byte_count=excluded.byte_count,modified_ms=excluded.modified_ms,category=excluded.category,tags=excluded.tags",
            documentId, job.source.name, pending[0], job.byteCount, pending[1], pending[2], pending[3])
    }
    private fun row(r: ResultSet) = ImportJob(r.getString("id"), ImportSource(r.getString("collection_id"), r.getString("uri"), r.getString("name"), r.getString("title"), r.getObject("expected_version")?.let { r.getInt("expected_version") }), r.getString("sha256"), r.getInt("byte_count"), ImportState.valueOf(r.getString("state")), r.getString("document_id"), r.getString("error")?.let(KnowledgeError::valueOf))
    private fun find(c: Connection, id: String): ImportJob = c.query("SELECT * FROM import_job WHERE id=?", id, map = ::row).singleOrNull() ?: throw ParserFailure(KnowledgeError.NOT_FOUND)
    private fun event(c: Connection, id: String, kind: ImportEventKind, state: ImportState, error: KnowledgeError? = null) {
        c.execute("INSERT INTO import_job_event(job_id,kind,state,error) VALUES(?,?,?,?)",id,kind.name,state.name,error?.name)
    }
    fun events(id: String, after: Long = 0, limit: Int = 100): CatalogResult<List<ImportEvent>> = result(Operation.READ) {
        require(after>=0 && limit in 1..200)
        database.read { c -> c.query("SELECT * FROM import_job_event WHERE job_id=? AND sequence>? ORDER BY sequence LIMIT ?",id,after,limit) { r -> ImportEvent(r.getLong(1),r.getString(2),ImportEventKind.valueOf(r.getString(3)),ImportState.valueOf(r.getString(4)),r.getString(5)?.let(KnowledgeError::valueOf)) } }
    }
    private fun requireAccess(operation: Operation) { if (!catalog.permits(operation)) throw ParserFailure(KnowledgeError.DENIED) }
    private fun <T> result(operation: Operation, action: () -> T): CatalogResult<T> = try {
        requireAccess(operation); CatalogResult.Ok(action())
    } catch (failure: ParserFailure) { CatalogResult.Rejected(failure.error) }
      catch (_: ImportCancelled) { CatalogResult.Rejected(KnowledgeError.CANCELLED) }
      catch (_: IllegalArgumentException) { CatalogResult.Rejected(KnowledgeError.INVALID_INPUT) }
      catch (_: java.sql.SQLException) { CatalogResult.Rejected(KnowledgeError.STORAGE_FAILURE) }
      catch (_: java.io.IOException) { CatalogResult.Rejected(KnowledgeError.STORAGE_FAILURE) }
      catch (_: SecurityException) { CatalogResult.Rejected(KnowledgeError.DENIED) }
    private fun bytes(input: InputStream, cancellation: Cancellation = Cancellation { false }): ByteArray {
        val out = ByteArrayOutputStream(minOf(maxBytes, 8192)); val buffer = ByteArray(8192)
        while (true) {
            cancellation.check()
            val read = input.read(buffer, 0, minOf(buffer.size, maxBytes - out.size() + 1))
            cancellation.check()
            if (read < 0) return out.toByteArray()
            if (read == 0) { val one = input.read(); if (one < 0) return out.toByteArray(); out.write(one) }
            else out.write(buffer, 0, read)
            if (out.size() > maxBytes) throw ParserFailure(KnowledgeError.LIMIT_EXCEEDED)
        }
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun enqueue(source: ImportSource, stream: InputStream, metadata: ImportMetadata = ImportMetadata()): CatalogResult<ImportJob> = result(Operation.WRITE) {
        val frozenTags = metadata.tags.toSet()
        val checkedMetadata = metadata.copy(tags=frozenTags)
        catalog.validateMetadata(source.collectionId, source.uri, source.title, source.expectedVersion)
        require(source.name.isNotBlank() && source.name.length <= 200 && '\u0000' !in source.name)
        parsers.find(source.name) // Archives and unsupported formats are refused without extraction.
        val content = bytes(stream, Cancellation { !catalog.permits(Operation.WRITE) }); val sha = digest(content)
        database.transaction { c ->
            quota(c, content.size.toLong() * 8)
            requireAccess(Operation.WRITE)
            if (c.query("SELECT id FROM collection WHERE id=?", source.collectionId) { it.getString(1) }.isEmpty()) throw ParserFailure(KnowledgeError.NOT_FOUND)
            val pending = c.query("SELECT count(*) FROM import_job WHERE state IN ('QUEUED','PROCESSING','CANCELLING','INTERRUPTED')") { it.getInt(1) }.single()
            if (pending >= maxPending) throw ParserFailure(KnowledgeError.BUSY)
            val id = UUID.randomUUID().toString()
            c.execute("INSERT INTO import_job(id,collection_id,uri,name,title,sha256,byte_count,expected_version,state) VALUES(?,?,?,?,?,?,?,?,'QUEUED')", id, source.collectionId, source.uri, source.name, source.title, sha, content.size, source.expectedVersion)
            c.execute("INSERT INTO import_metadata VALUES(?,?,?,?,?)", id, source.name.substringAfterLast('.').lowercase(java.util.Locale.ROOT), checkedMetadata.modifiedMs, checkedMetadata.category, frozenTags.sorted().joinToString("\n"))
            event(c,id,ImportEventKind.QUEUED,ImportState.QUEUED)
            find(c, id)
        }
    }
    fun job(id: String): CatalogResult<ImportJob> = result(Operation.READ) { database.read { find(it, id) } }
    fun cancel(id: String): CatalogResult<ImportJob> = result(Operation.WRITE) {
        database.transaction { c ->
            val job = find(c, id)
            when (job.state) {
                ImportState.QUEUED, ImportState.INTERRUPTED -> c.execute("UPDATE import_job SET state='CANCELLED',error='CANCELLED' WHERE id=?", id)
                ImportState.PROCESSING -> c.execute("UPDATE import_job SET state='CANCELLING' WHERE id=?", id)
                else -> Unit
            }
            val updated=find(c,id)
            if (updated.state!=job.state) event(c,id,ImportEventKind.CANCEL_REQUESTED,updated.state)
            updated
        }
    }
    /** Reconciliation only after acquiring the exclusive parser lock; never clears history. */
    fun recoverInterrupted(): CatalogResult<Int> = result(Operation.WRITE) {
        worker {
            database.transaction { c ->
                val pending=c.query("SELECT * FROM import_job WHERE state IN ('PROCESSING','CANCELLING')",map=::row)
                pending.forEach { job ->
                    val next=if (job.state==ImportState.CANCELLING) ImportState.CANCELLED else ImportState.INTERRUPTED
                    c.execute("UPDATE import_job SET state=?,error=? WHERE id=?",next.name,if (next==ImportState.CANCELLED) KnowledgeError.CANCELLED.name else job.error?.name,job.id)
                    event(c,job.id,if (next==ImportState.CANCELLED) ImportEventKind.CANCEL_OBSERVED else ImportEventKind.INTERRUPTED,next)
                }
                pending.size
            }
        }
    }
    private fun <T> worker(action: () -> T): T {
        val lockPath = database.path.resolveSibling("." + database.path.fileName + ".nexus-import.lock")
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (lock == null) throw ParserFailure(KnowledgeError.BUSY)
            lock.use { return action() }
        }
    }
    fun process(id: String, stream: InputStream, cancellation: Cancellation = Cancellation { false }, forceReindex: Boolean = false): CatalogResult<ImportJob> = result(Operation.WRITE) {
        worker {
            var begun = false
            try {
                val job = database.transaction { c ->
                    quota(c)
                    val job = find(c, id)
                    if (job.state == ImportState.COMPLETED) return@transaction job
                    if (job.state !in setOf(ImportState.QUEUED, ImportState.INTERRUPTED, ImportState.FAILED)) throw ParserFailure(if (job.state == ImportState.CANCELLED) KnowledgeError.CANCELLED else KnowledgeError.BUSY)
                    c.execute("UPDATE import_job SET state='PROCESSING',error=NULL WHERE id=?", id)
                    event(c,id,ImportEventKind.PROCESSING,ImportState.PROCESSING)
                    begun = true
                    job
                }
                if (!begun) return@worker job
                val stop = Cancellation {
                    cancellation.requested() || !catalog.permits(Operation.WRITE) || database.read { find(it, id).state == ImportState.CANCELLING }
                }
                stop.check()
                val content = bytes(stream, stop)
                if (content.size != job.byteCount || digest(content) != job.sha256) throw ParserFailure(KnowledgeError.CONFLICT)
                // Hash comparison reuses the current READY index; no parsing or FTS rewrite.
                val unchanged = database.transaction { c ->
                    val row = c.query("SELECT d.id,d.version FROM document d JOIN revision r ON r.document_id=d.id AND r.version=d.version JOIN source s ON s.id=r.source_id JOIN index_state i ON i.document_id=d.id WHERE d.collection_id=? AND d.uri=? AND d.deleted=0 AND d.title=? AND s.sha256=? AND i.state='READY' AND i.indexed_version=d.version", job.source.collectionId, job.source.uri, job.source.title, job.sha256) { Pair(it.getString(1),it.getInt(2)) }.singleOrNull()
                    if (row != null && !forceReindex) {
                        if (job.source.expectedVersion != null && job.source.expectedVersion != row.second) throw ParserFailure(KnowledgeError.CONFLICT)
                        stop.check(); requireAccess(Operation.WRITE)
                        metadata(c,job,row.first)
                        c.execute("UPDATE import_job SET state='COMPLETED',document_id=?,error=NULL WHERE id=?",row.first,id)
                        event(c,id,ImportEventKind.COMPLETED,ImportState.COMPLETED)
                    }
                    row != null && !forceReindex
                }
                if (unchanged) return@worker database.read { find(it,id) }
                val pages = try { parsers.find(job.source.name).parse(job.source.name, content.copyOf(), stop).map { it.copy() } }
                    catch (e: ImportCancelled) { throw e }
                    catch (e: ParserFailure) { throw e }
                    catch (_: Exception) { throw ParserFailure(KnowledgeError.EXTRACTION_FAILED) }
                stop.check()
                if (pages.isEmpty() || pages.size > 100 || pages.sumOf { it.text.length.toLong() } > 250_000) throw ParserFailure(KnowledgeError.LIMIT_EXCEEDED)
                require(pages.map { it.page }.distinct().size == pages.size)
                val fragments = buildList {
                    pages.forEach { page ->
                        require(page.page == null || page.page >= 1)
                        require(page.location == null || page.location.length <= 1024)
                        var start = 0
                        while (start < page.text.length) {
                            stop.check()
                            var end = minOf(start + chunkChars, page.text.length)
                            if (end < page.text.length && page.text[end - 1].isHighSurrogate()) end--
                            if (end < page.text.length) {
                                val paragraph = page.text.lastIndexOf('\n', end - 1)
                                if (paragraph > start + chunkChars / 2) end = paragraph + 1
                            }
                            add(Fragment(size, page.text.substring(start, end), start, end, page.page))
                            start = end
                        }
                    }
                }
                if (fragments.isEmpty()) throw ParserFailure(KnowledgeError.INVALID_INPUT)
                val request = ImportRequest(job.source.collectionId, job.source.uri, job.source.title, job.source.expectedVersion, fragments)
                val imported = catalog.importAndIndex(request, content.inputStream()) { c, document ->
                    if (c.query("SELECT state FROM import_job WHERE id=?", id) { it.getString(1) }.single() != "PROCESSING") throw ImportCancelled()
                    cancellation.check(); requireAccess(Operation.WRITE)
                    c.execute("DELETE FROM fragment_fts WHERE document_id=?", document.id)
                    c.execute("DELETE FROM import_page WHERE document_id=? AND version=?", document.id, document.version)
                    c.execute("DELETE FROM fragment_location WHERE document_id=? AND version=?", document.id, document.version)
                    pages.forEach { page ->
                        c.execute("INSERT INTO import_page VALUES(?,?,?,?)", document.id, document.version, page.page ?: 0, digest(page.text.toByteArray(Charsets.UTF_8)))
                    }
                    fragments.forEach { f -> cancellation.check(); c.execute("INSERT INTO fragment_fts(document_id,version,ordinal,body) VALUES(?,?,?,?)", document.id, document.version, f.ordinal, Normalizer.normalize(f.text, Normalizer.Form.NFC)) }
                    fragments.forEach { f -> pages.single { it.page == f.page }.location?.let { location -> c.execute("INSERT INTO fragment_location VALUES(?,?,?,?)",document.id,document.version,f.ordinal,location) } }
                    metadata(c,job,document.id)
                    c.execute("UPDATE index_state SET indexed_version=?,state='READY' WHERE document_id=?", document.version, document.id)
                    cancellation.check(); requireAccess(Operation.WRITE)
                    c.execute("UPDATE import_job SET state='COMPLETED',document_id=?,error=NULL WHERE id=?", document.id, id)
                    event(c,id,ImportEventKind.COMPLETED,ImportState.COMPLETED)
                    quota(c)
                }
                if (imported is CatalogResult.Rejected) throw ParserFailure(imported.error)
                database.read { find(it, id) }
            } catch (failure: Exception) {
                if (begun) {
                    val code = when (failure) {
                        is ImportCancelled -> KnowledgeError.CANCELLED
                        is ParserFailure -> failure.error
                        is IllegalArgumentException -> KnowledgeError.INVALID_INPUT
                        else -> KnowledgeError.STORAGE_FAILURE
                    }
                    val cancellationWon=database.transaction { c ->
                        val current=find(c,id)
                        val cancelled=code==KnowledgeError.CANCELLED || current.state in setOf(ImportState.CANCELLING,ImportState.CANCELLED)
                        if (current.state!=ImportState.COMPLETED) {
                            val state=if (cancelled) ImportState.CANCELLED else ImportState.FAILED
                            c.execute("UPDATE import_job SET state=?,error=? WHERE id=?",state.name,if (cancelled) KnowledgeError.CANCELLED.name else code.name,id)
                            // Keep the observed failure even when cancellation has priority. No raw exception text.
                            event(c,id,if (code==KnowledgeError.CANCELLED) ImportEventKind.CANCEL_OBSERVED else ImportEventKind.FAILURE_OBSERVED,state,code)
                        }
                        cancelled
                    }
                    if (cancellationWon) throw ImportCancelled()
                }
                throw failure
            }
        }
    }
}
