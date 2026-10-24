package fr.jarvis.nexus.archive

import fr.jarvis.nexus.api.*
import fr.jarvis.nexus.rag.*
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class ArchiveError { UNSUPPORTED, DENIED, NOT_FOUND, CORRUPT, SOURCE_CHANGED, LIMIT_EXCEEDED, STORAGE_FAILURE, INVALID_INPUT }
class ArchiveFailure(val error: ArchiveError) : RuntimeException(error.name)
data class ArticleSelection(val archiveId: String, val path: String)
data class ArchiveArticle(val title: String, val path: String, val text: String, val rawSha256: String, val textSha256: String, val uri: String)
fun interface ArchiveReader { fun article(selection: ArticleSelection): ArchiveArticle }

/** Linux mini-PC only. Host supplies trusted interpreter/worker/catalogue paths.
 * A fresh process bounds native lifetime. The host must additionally cap native
 * memory/CPU for hostile clusters; the article/cache limits are not an RSS cap.
 */
class ZimProcessReader(
    private val python: Path, private val worker: Path, private val catalog: Path,
    private val authorized: () -> Boolean = { false },
) : ArchiveReader {
    override fun article(selection: ArticleSelection): ArchiveArticle {
        if (!authorized()) throw ArchiveFailure(ArchiveError.DENIED)
        if (System.getProperty("java.runtime.name", "").contains("Android", true) ||
            System.getProperty("java.vm.name", "").contains("Dalvik", true) ||
            !System.getProperty("os.name", "").startsWith("Linux")) throw ArchiveFailure(ArchiveError.UNSUPPORTED)
        require(selection.archiveId.matches(Regex("[a-f0-9]{64}")))
        require(selection.path.length in 1..4096 && !selection.path.startsWith("/") &&
            selection.path.split('/').none { it == "." || it == ".." } &&
            selection.path.none { it < ' ' || it == '\\' })
        val process = try {
            ProcessBuilder(python.toString(), worker.toString(), "--catalog", catalog.toString(),
                "wire", selection.archiveId, selection.path).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (_: java.io.IOException) { throw ArchiveFailure(ArchiveError.UNSUPPORTED) }
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "nexus-zim-output").apply { isDaemon = true } }
        try {
            val future = executor.submit(Callable {
                process.inputStream.use { stream ->
                    val bytes = stream.readNBytes(2 * 1024 * 1024 + 1)
                    if (bytes.size > 2 * 1024 * 1024) throw ArchiveFailure(ArchiveError.LIMIT_EXCEEDED)
                    bytes.toString(Charsets.UTF_8).trimEnd('\n').split('\n')
                }
            })
            val lines = future.get(30, TimeUnit.SECONDS)
            if (!process.waitFor(1, TimeUnit.SECONDS)) throw ArchiveFailure(ArchiveError.STORAGE_FAILURE)
            if (lines.firstOrNull() != "OK") {
                val error = ArchiveError.entries.firstOrNull { it.name == lines.firstOrNull() } ?: ArchiveError.STORAGE_FAILURE
                throw ArchiveFailure(error)
            }
            if (process.exitValue() != 0 || lines.size != 7) throw ArchiveFailure(ArchiveError.CORRUPT)
            val fields = lines.drop(1).map { Base64.getDecoder().decode(it).toString(Charsets.UTF_8) }
            val result = ArchiveArticle(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5])
            if (result.path != selection.path || result.text.toByteArray().size > 1024 * 1024 ||
                sha256(result.text) != result.textSha256 || result.uri != "zim://${selection.archiveId}/" +
                encodedPath(selection.path)) {
                throw ArchiveFailure(ArchiveError.CORRUPT)
            }
            if (!authorized()) throw ArchiveFailure(ArchiveError.DENIED)
            return result
        } catch (e: ArchiveFailure) { throw e }
        catch (_: java.util.concurrent.TimeoutException) { throw ArchiveFailure(ArchiveError.LIMIT_EXCEEDED) }
        catch (_: Exception) { throw ArchiveFailure(ArchiveError.STORAGE_FAILURE) }
        finally { process.destroyForcibly(); executor.shutdownNow() }
    }
}

/** Explicit host-selected articles only. No automatic corpus ingestion or scope expansion.
 * The source SHA is that of the extracted article text, NEVER of the 55 GB ZIM.
 * The archive fingerprint (URI authority) is documented as unverified metadata.
 */
class SelectedArchiveContext(
    private val reader: ArchiveReader,
    selections: List<ArticleSelection>,
    private val collectionId: String,
    private val authorized: () -> Boolean = { false },
) : RagContextPort {
    private val selections = selections.toList()
    init { require(selections.size in 1..20 && collectionId.isNotBlank()) }

    private fun allowed(scope: SearchScope, selection: ArticleSelection) = authorized() &&
        collectionId in scope.collectionIds && (scope.sourceIds == null || selection.archiveId in scope.sourceIds)

    private fun makeCitation(article: ArchiveArticle, selection: ArticleSelection, text: String): Citation {
        val document = sha256(article.uri)
        return Citation(sha256(document + ":" + article.textSha256 + ":" + text.length), document, 1, 0,
            selection.archiveId, article.uri, article.textSha256, null, 0, text.length, sha256(text))
    }

    override fun retrieve(query: KnowledgeQuery, scope: SearchScope, limits: ContextLimits): CatalogResult<ContextBundle> = checked {
        require(query.text.isNotBlank() && query.text.length <= 256 && query.importedAfterMs == null && query.importedBeforeMs == null && query.mediaTypes.isEmpty())
        val frozen = scope.copy(collectionIds=scope.collectionIds.toSet(), sourceIds=scope.sourceIds?.toSet())
        scopeCheck(frozen)
        val selected = selections.filter { allowed(frozen, it) }
        if (selected.isEmpty()) return@checked CatalogResult.Rejected(KnowledgeError.DENIED)
        val items = selected.mapNotNull { selection ->
            val article = reader.article(selection)
            if (!article.title.contains(query.text, true) && !article.text.contains(query.text, true)) null
            else {
                val text = article.text.take(2048)
                ContextItem(text, listOf(makeCitation(article, selection, text)), 0.0)
            }
        }
        val bundle = ContextBudgeter().prepare(items, limits)
        if (selected.any { !allowed(frozen, it) }) CatalogResult.Rejected(KnowledgeError.DENIED)
        else CatalogResult.Ok(bundle)
    }

    override fun resolve(citation: Citation, scope: SearchScope): CatalogResult<String> = checked {
        val frozen = scope.copy(collectionIds=scope.collectionIds.toSet(), sourceIds=scope.sourceIds?.toSet())
        scopeCheck(frozen)
        val selection = selections.firstOrNull { it.archiveId == citation.sourceId && allowed(frozen, it) &&
            "zim://${it.archiveId}/" + encodedPath(it.path) == citation.sourceUri }
            ?: return@checked CatalogResult.Rejected(KnowledgeError.DENIED)
        val article = reader.article(selection)
        val text = article.text.take(2048)
        if (citation != makeCitation(article, selection, text)) CatalogResult.Rejected(KnowledgeError.CONFLICT)
        else if (!allowed(frozen, selection)) CatalogResult.Rejected(KnowledgeError.DENIED)
        else CatalogResult.Ok(text)
    }

    private fun <T> checked(block: () -> CatalogResult<T>): CatalogResult<T> = try { block() }
    catch (e: ArchiveFailure) { CatalogResult.Rejected(when (e.error) {
        ArchiveError.UNSUPPORTED -> KnowledgeError.UNSUPPORTED_FORMAT
        ArchiveError.DENIED -> KnowledgeError.DENIED
        ArchiveError.NOT_FOUND -> KnowledgeError.NOT_FOUND
        ArchiveError.SOURCE_CHANGED -> KnowledgeError.CONFLICT
        ArchiveError.LIMIT_EXCEEDED -> KnowledgeError.LIMIT_EXCEEDED
        ArchiveError.INVALID_INPUT -> KnowledgeError.INVALID_INPUT
        else -> KnowledgeError.EXTRACTION_FAILED
    }) }
    catch (e: RagRefusal) { CatalogResult.Rejected(e.error) }
    catch (_: IllegalArgumentException) { CatalogResult.Rejected(KnowledgeError.INVALID_INPUT) }
    catch (_: RuntimeException) { CatalogResult.Rejected(KnowledgeError.EXTRACTION_FAILED) }
}

private fun encodedPath(path: String) = java.net.URLEncoder.encode(path, "UTF-8")
    .replace("+", "%20").replace("%7E", "~").replace("*", "%2A")
