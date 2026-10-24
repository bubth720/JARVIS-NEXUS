package fr.jarvis.nexus

import fr.jarvis.nexus.api.*
import fr.jarvis.nexus.archive.*
import fr.jarvis.nexus.rag.*
import org.junit.Assert.*
import org.junit.Test

/** Contract fixture only, not a replacement ZIM decoder. */
class ArchiveContextTest {
    private val key = "a".repeat(64)
    private val selection = ArticleSelection(key, "Paris")
    private val scope = SearchScope(setOf("encyclopedia"), setOf(key))
    private var allowed = true
    private var text = "Paris est une ville."
    private val reader = ArchiveReader { ArchiveArticle("Paris", "Paris", text, sha256(text), sha256(text), "zim://$key/Paris") }
    private fun port() = SelectedArchiveContext(reader, listOf(selection), "encyclopedia") { allowed }

    @Test fun selectedArticleUsesExistingCarrierAndResolves() {
        val port: RagContextPort = port()
        val result = port.retrieve(KnowledgeQuery("Paris"), scope) as CatalogResult.Ok
        val citation = result.value.items.single().citations.single()
        assertEquals(CatalogResult.Ok(text), port.resolve(citation, scope))
        assertEquals(sha256(text), citation.sourceSha256)
        assertTrue(result.value.asUntrustedJson().contains("\"trusted\":false"))
        assertTrue(result.value.asUntrustedJson().length <= 4000)
    }

    @Test fun revokedAuthorityCannotUseCitations() {
        val port = port()
        val result = port.retrieve(KnowledgeQuery("Paris"), scope) as CatalogResult.Ok
        allowed = false
        assertEquals(CatalogResult.Rejected(KnowledgeError.DENIED), port.resolve(result.value.items.single().citations.single(), scope))
    }

    @Test fun wrongScopeIsDenied() {
        assertEquals(CatalogResult.Rejected(KnowledgeError.DENIED), port().retrieve(KnowledgeQuery("Paris"), SearchScope(emptySet())))
    }

    @Test fun tamperedCitationAndChangedArticleAreRejected() {
        val port = port()
        val bundle = (port.retrieve(KnowledgeQuery("Paris"), scope) as CatalogResult.Ok).value
        val citation = bundle.items.single().citations.single()
        assertEquals(CatalogResult.Rejected(KnowledgeError.CONFLICT), port.resolve(citation.copy(textSha256="wrong"), scope))
        text += " Autre version."
        assertEquals(CatalogResult.Rejected(KnowledgeError.CONFLICT), port.resolve(citation, scope))
    }

    @Test fun noEvidenceAndSmallCarrierBudgetStayTyped() {
        assertEquals(CatalogResult.Rejected(KnowledgeError.NO_EVIDENCE), port().retrieve(KnowledgeQuery("absent"), scope))
        assertEquals(CatalogResult.Rejected(KnowledgeError.BUDGET_EXCEEDED), port().retrieve(KnowledgeQuery("Paris"), scope, ContextLimits(maxCharacters=1)))
    }

    @Test fun nativeAbsenceDoesNotProduceEvidence() {
        val port = SelectedArchiveContext(ArchiveReader { throw ArchiveFailure(ArchiveError.UNSUPPORTED) }, listOf(selection), "encyclopedia") { true }
        assertEquals(CatalogResult.Rejected(KnowledgeError.UNSUPPORTED_FORMAT), port.retrieve(KnowledgeQuery("Paris"), scope))
    }
}
