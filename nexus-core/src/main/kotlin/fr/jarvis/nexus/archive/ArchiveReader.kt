package fr.jarvis.nexus.archive

import fr.jarvis.nexus.api.*
import fr.jarvis.nexus.rag.*

enum class ArchiveError { UNSUPPORTED, DENIED, NOT_FOUND, CORRUPT, SOURCE_CHANGED, LIMIT_EXCEEDED, STORAGE_FAILURE, INVALID_INPUT }
class ArchiveFailure(val error: ArchiveError) : RuntimeException(error.name)
data class ArticleSelection(val archiveId: String, val path: String)
data class ArchiveArticle(val title: String, val path: String, val text: String, val rawSha256: String, val textSha256: String, val uri: String)
fun interface ArchiveReader { fun article(selection: ArticleSelection): ArchiveArticle }

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
            "zim://${it.archiveId}/" + encodeArticlePath(it.path) == citation.sourceUri }
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

private fun encodeArticlePath(path: String) = java.net.URLEncoder.encode(path, "UTF-8")
    .replace("+", "%20").replace("%7E", "~").replace("*", "%2A")
