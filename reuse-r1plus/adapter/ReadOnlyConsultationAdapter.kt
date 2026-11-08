package fr.jarvis.nexus.reuse

import fr.jarvis.nexus.rag.*
import java.util.Collections

/** Experimental, in-process test-only carrier. Not a canonical wire contract or authority. */
data class ConsultationRequest(val sessionId: String, val requestId: String, val generation: Long,
    val query: KnowledgeQuery, val scope: SearchScope)

sealed interface ConsultationResult {
    data class Ready(val sessionId: String, val requestId: String, val generation: Long,
        val untrustedJson: String, val citations: List<Citation>) : ConsultationResult
    data class Refused(val reason: String) : ConsultationResult
}

/** Reuses DocumentaryGateway/1 and its real SQLite/citation validation.
 * No DB ownership, cache, network, write API, model, renderer, or CORE grant.
 * All callbacks are trusted fixture/host inputs; document text never enables them.
 * Default construction refuses every call. The opt-in init compiles this into tests only.
 */
class ReadOnlyConsultationAdapter(private val gateway: DocumentaryGateway,
    private val enabled: Boolean = false,
    private val currentSession: (String, Long) -> Boolean = { _, _ -> false },
    private val monotonicMs: () -> Long = { System.nanoTime()/1_000_000 }) {

    fun consult(request: ConsultationRequest, call: DocumentaryCall,
        limits: ContextLimits = ContextLimits(), maxUtf8Bytes: Int = 8192): ConsultationResult {
        val id = Regex("[A-Za-z0-9._:-]{1,64}")
        if (!id.matches(request.sessionId) || !id.matches(request.requestId) || request.generation < 0)
            return ConsultationResult.Refused("INVALID_CORRELATION")
        val bound = DocumentaryCall(call.deadlineMonotonicMs,
            { enabled && currentSession(request.sessionId, request.generation) && call.authorized() },
            call.cancelled)
        fun refusal(): ConsultationResult.Refused? {
            if (!runCatching(bound.authorized).getOrDefault(false)) return ConsultationResult.Refused("DENIED")
            if (runCatching(bound.cancelled).getOrDefault(true)) return ConsultationResult.Refused("CANCELLED")
            if (monotonicMs() >= bound.deadlineMonotonicMs) return ConsultationResult.Refused("DEADLINE_EXCEEDED")
            return null
        }
        refusal()?.let { return it }
        return when (val result = gateway.retrieve(request.query, request.scope, bound, limits, maxUtf8Bytes)) {
            is DocumentaryResult.Refused -> ConsultationResult.Refused(result.reason.name)
            is DocumentaryResult.Ready -> {
                // Gateway already resolves every citation under its SQLite snapshot;
                // do not repeat that public resolve call in the consumer.
                val carrier = result.bundle.asUntrustedJson()
                val citations = Collections.unmodifiableList(result.bundle.items.flatMap { it.citations }.map { it.copy() })
                refusal()?.let { return it }
                ConsultationResult.Ready(request.sessionId, request.requestId, request.generation, carrier, citations)
            }
        }
    }
}
