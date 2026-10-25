package fr.jarvis.nexus.rag

import fr.jarvis.nexus.api.*
import fr.jarvis.nexus.store.NexusStoragePort

/** Deterministic ranking/deduplication; no embeddings model or second LLM. */
class EvidenceReranker {
    fun group(hits: List<SearchHit>, maxItemSerializedCharacters: Int = 4000): List<ContextItem> {
        val unique = linkedMapOf<String, ContextItem>()
        hits.forEach { hit ->
            val key = sha256(hit.text)
            val existing = unique[key]
            unique[key] = if (existing == null) ContextItem(hit.text, listOf(hit.citation), hit.ftsRank, hit.matchMode)
            else existing.copy(citations = (existing.citations + hit.citation).distinctBy { it.id }.take(20), ftsRank = minOf(existing.ftsRank, hit.ftsRank), grouping=EvidenceGrouping.IDENTICAL_TEXT)
        }
        val groups = mutableListOf<ContextItem>()
        val singles = unique.values.filter { it.citations.size==1 }.sortedWith(compareBy<ContextItem> { it.citations.single().documentId }.thenBy { it.citations.single().page ?: 0 }.thenBy { it.citations.single().start })
        singles.forEach { candidate ->
            val previous = groups.lastOrNull()
            val a = previous?.citations?.lastOrNull(); val b = candidate.citations.single()
            val combined = previous?.let { ContextItem(it.text+candidate.text,it.citations+candidate.citations,minOf(it.ftsRank,candidate.ftsRank),it.matchMode,EvidenceGrouping.CONTIGUOUS_FRAGMENTS) }
            if (a != null && a.documentId==b.documentId && a.version==b.version && a.page==b.page && a.end==b.start && combined!!.citations.size<=8 && ContextBundle(listOf(combined),combined.text.length,0).asUntrustedJson().length<=maxItemSerializedCharacters) groups[groups.lastIndex]=combined
            else groups+=candidate
        }
        groups += unique.values.filter { it.citations.size>1 }
        return groups.sortedWith(compareBy<ContextItem> { it.ftsRank }.thenBy { it.citations.first().id })
    }
}
class ContextBudgeter {
    /** Budget includes the actual JSON carrier and citation metadata, not only excerpts. */
    fun prepare(items: List<ContextItem>, limits: ContextLimits): ContextBundle {
        val selected = mutableListOf<ContextItem>(); val sources = mutableSetOf<String>(); var characters = 0; var skipped = 0
        items.forEach { original ->
            var item = original
            if (item.grouping==EvidenceGrouping.IDENTICAL_TEXT) {
                val allowed=mutableListOf<Citation>(); val candidateSources=sources.toMutableSet()
                item.citations.forEach { citation -> if (citation.sourceId in candidateSources || candidateSources.size<limits.maxSources) { allowed+=citation;candidateSources+=citation.sourceId } }
                if (allowed.isNotEmpty()) item=item.copy(citations=allowed.toList())
            }
            fun fits(candidate: ContextItem): Boolean = selected.size<limits.maxItems && (sources+candidate.citations.map { it.sourceId }).size<=limits.maxSources && ContextBundle(selected+candidate,characters+candidate.text.length,skipped).asUntrustedJson().length<=limits.maxCharacters
            if (item.grouping==EvidenceGrouping.IDENTICAL_TEXT) while (!fits(item) && item.citations.size>1) item=item.copy(citations=item.citations.dropLast(1))
            if (!fits(item)) skipped++
            else { selected+=item; characters+=item.text.length; sources+=item.citations.map { it.sourceId } }
        }
        if (selected.isEmpty()) throw RagRefusal(if (items.isEmpty()) KnowledgeError.NO_EVIDENCE else KnowledgeError.BUDGET_EXCEEDED)
        // The final skipped count can add digits to the carrier; re-check the exact final form.
        val result=ContextBundle(selected.toList(),characters,skipped)
        if (result.asUntrustedJson().length>limits.maxCharacters) throw RagRefusal(KnowledgeError.BUDGET_EXCEEDED)
        return result
    }
}

class LocalRagService(
    private val database: NexusStoragePort,
    private val catalog: KnowledgeCatalogService,
    private val hostScope: () -> SearchScope = { SearchScope(emptySet()) },
) : RagContextPort {
    private val search = SearchRepository(database)
    private val resolver = CitationResolver(database)
    override fun retrieve(query: KnowledgeQuery, scope: SearchScope, limits: ContextLimits): CatalogResult<ContextBundle> = checked {
        // Freeze caller-owned scope collections before SQL planning.
        val frozen = scope.copy(collectionIds = scope.collectionIds.toSet(), sourceIds = scope.sourceIds?.toSet())
        val result=ContextBudgeter().prepare(EvidenceReranker().group(search.search(query.copy(mediaTypes=query.mediaTypes.toSet()),effectiveScope(frozen)),limits.maxCharacters), limits)
        val stillAuthorized=effectiveScope(frozen)
        result.items.flatMap { it.citations }.forEach { resolver.resolve(it,stillAuthorized) }
        result
    }
    override fun resolve(citation: Citation, scope: SearchScope): CatalogResult<String> = checked {
        resolver.resolve(citation,effectiveScope(scope.copy(collectionIds = scope.collectionIds.toSet(), sourceIds = scope.sourceIds?.toSet())))
    }
    private fun effectiveScope(requested: SearchScope): SearchScope {
        val host=try { hostScope().let { it.copy(collectionIds=it.collectionIds.toSet(),sourceIds=it.sourceIds?.toSet()) } } catch (_: Exception) { throw RagRefusal(KnowledgeError.DENIED) }
        scopeCheck(host);scopeCheck(requested)
        val collections=host.collectionIds.intersect(requested.collectionIds)
        val sources=when { host.sourceIds==null -> requested.sourceIds;requested.sourceIds==null -> host.sourceIds;else -> host.sourceIds.intersect(requested.sourceIds) }
        return SearchScope(collections,sources).also(::scopeCheck)
    }
    private fun <T> checked(action: () -> T): CatalogResult<T> {
        if (!catalog.permits(Operation.READ)) return CatalogResult.Rejected(KnowledgeError.DENIED)
        return try { CatalogResult.Ok(action()) }
        catch (e: RagRefusal) { CatalogResult.Rejected(e.error) }
        catch (_: IllegalArgumentException) { CatalogResult.Rejected(KnowledgeError.INVALID_INPUT) }
        catch (_: fr.jarvis.nexus.store.NexusStorageException) { CatalogResult.Rejected(KnowledgeError.STORAGE_FAILURE) }
    }
}
