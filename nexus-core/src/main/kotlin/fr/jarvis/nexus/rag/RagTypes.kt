package fr.jarvis.nexus.rag

import fr.jarvis.nexus.api.CatalogResult
import fr.jarvis.nexus.api.json

/** Empty collection scope refuses retrieval, rather than granting implicit global access. */
data class SearchScope(val collectionIds: Set<String>, val sourceIds: Set<String>? = null)
data class KnowledgeQuery(val text: String, val importedAfterMs: Long? = null, val importedBeforeMs: Long? = null)
data class ContextLimits(val maxCharacters: Int = 4000, val maxItems: Int = 5, val maxSources: Int = 5) {
    init { require(maxCharacters in 1..32000 && maxItems in 1..20 && maxSources in 1..20) }
}
data class Citation(
    val id: String, val documentId: String, val version: Int, val ordinal: Int,
    val sourceId: String, val sourceUri: String, val sourceSha256: String,
    val page: Int?, val start: Int, val end: Int, val textSha256: String,
)
enum class MatchMode { EXACT, FUZZY_ONE_EDIT }
data class SearchHit(val text: String, val citation: Citation, val ftsRank: Double, val matchMode: MatchMode = MatchMode.EXACT)
enum class EvidenceGrouping { SINGLE, IDENTICAL_TEXT, CONTIGUOUS_FRAGMENTS }
data class ContextItem(val text: String, val citations: List<Citation>, val ftsRank: Double, val matchMode: MatchMode = MatchMode.EXACT, val grouping: EvidenceGrouping = EvidenceGrouping.SINGLE)
data class ContextBundle(val items: List<ContextItem>, val characters: Int, val skippedForBudget: Int) {
    /** Editorial estimate, neither an upper nor a lower bound. Never an inference admission or tokenizer proof. */
    val estimatedTokens: Int get() = (items.sumOf { it.text.toByteArray(Charsets.UTF_8).size } + 3) / 4
    /** Structured untrusted context, deliberately no SYSTEM role, permission or executable instruction. */
    fun asUntrustedJson(): String = "{\"format\":\"jarvis.nexus.context/1\",\"trusted\":false,\"characters\":$characters,\"skippedForBudget\":$skippedForBudget,\"estimatedTokens\":$estimatedTokens,\"tokenMeasurement\":\"excerpt_estimate_not_admission\",\"items\":" + items.joinToString(",", "[", "]}") { item ->
        "{\"text\":${json(item.text)},\"trusted\":false,\"ftsRank\":${item.ftsRank},\"matchMode\":${json(item.matchMode.name)},\"grouping\":${json(item.grouping.name)},\"citations\":" + item.citations.joinToString(",", "[", "]}") { c ->
            "{\"id\":${json(c.id)},\"documentId\":${json(c.documentId)},\"version\":${c.version},\"ordinal\":${c.ordinal},\"sourceId\":${json(c.sourceId)},\"sourceUri\":${json(c.sourceUri)},\"sourceSha256\":${json(c.sourceSha256)},\"page\":${c.page ?: "null"},\"start\":${c.start},\"end\":${c.end},\"textSha256\":${json(c.textSha256)}}"
        }
    }
}
interface RagContextPort {
    fun retrieve(query: KnowledgeQuery, scope: SearchScope, limits: ContextLimits = ContextLimits()): CatalogResult<ContextBundle>
    fun resolve(citation: Citation, scope: SearchScope): CatalogResult<String>
}
