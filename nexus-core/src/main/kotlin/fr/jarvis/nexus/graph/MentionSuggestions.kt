package fr.jarvis.nexus.graph

import fr.jarvis.nexus.rag.Citation
import fr.jarvis.nexus.rag.SearchHit

data class MentionSuggestion(val label: String, val kind: NodeKind, val evidence: Citation,
    val origin: RelationOrigin = RelationOrigin.SUGGESTED, val method: String = "literal-mention/1")

/** Deterministic, bounded suggestions over already-authorized retrieved evidence.
 * A literal mention does not assert identity, causality or truth. The host reviews
 * and persists chosen suggestions via GraphService; rejected edges are retained.
 */
class MentionSuggestions {
    fun propose(evidence: List<SearchHit>, vocabulary: Map<String,NodeKind>): List<MentionSuggestion> {
        require(evidence.size <= 100 && vocabulary.size <= 32)
        val terms=vocabulary.toMap()
        require(terms.all { (label,kind) -> label.length in 1..128 && label.none { it < ' ' } && kind != NodeKind.DOCUMENT })
        val result=mutableListOf<MentionSuggestion>()
        for(hit in evidence) {
            require(hit.text.length <= 8192)
            for((label,kind) in terms) {
                val pattern=Regex("(?<![\\p{L}\\p{N}_])"+Regex.escape(label)+"(?![\\p{L}\\p{N}_])",RegexOption.IGNORE_CASE)
                if(pattern.containsMatchIn(hit.text)) result+=MentionSuggestion(label,kind,hit.citation)
                if(result.size==200) return result.toList()
            }
        }
        return result.toList()
    }
}
