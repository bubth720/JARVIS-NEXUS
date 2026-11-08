package fr.jarvis.nexus.m53

import fr.jarvis.nexus.api.*
import fr.jarvis.nexus.importing.*
import fr.jarvis.nexus.rag.*
import fr.jarvis.nexus.store.NexusDatabase
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** Actual NEXUS/SQLite integration, synthetic corpus only; no CORE authority claimed. */
class M53ReadOnlyTest {
    private fun <T> ok(value: CatalogResult<T>): T = (value as CatalogResult.Ok).value
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private class Lab(val db: NexusDatabase, val api: KnowledgeCatalogService, val scope: SearchScope,
                      val content: Map<String, ByteArray>) {
        var authorized = true
        var cancelled = false
        var now = 100L
        val gateway = DocumentaryGateway(db, api, { scope }, { now })
        val rag = LocalRagService(db, api) { scope }
        fun call() = DocumentaryCall(200, { authorized }, { cancelled })
        fun retrieve(query: String, call: DocumentaryCall = call(), maxBytes: Int = 8192) =
            gateway.retrieve(KnowledgeQuery(query), scope, call, ContextLimits(maxCharacters = 8000), maxBytes)
        fun ready(query: String) = (retrieve(query) as DocumentaryResult.Ready).bundle
    }
    private fun fixture(action: (Lab) -> Unit) {
        val root = Files.createTempDirectory("jarvis-m53-public-synthetic-")
        try {
            NexusDatabase.open(root.resolve("review.sqlite")).use { db ->
                var setup = true
                val api = KnowledgeCatalogService(db, AccessPolicy { operation -> setup || operation == Operation.READ })
                val collection = ok(api.createCollection("M53 public synthetic corpus"))
                val parser = ParserPort { _, bytes, cancellation ->
                    cancellation.check(); listOf(ExtractedPage(1, bytes.toString(Charsets.UTF_8)))
                }
                val importer = ImportCoordinator(db, api, ParserRegistry(mapOf("txt" to parser)), chunkChars = 1024)
                val content = listOf("astronomy.txt", "navigation.txt", "injection.txt").associate { name ->
                    val bytes = javaClass.getResourceAsStream("/$name")!!.use { it.readBytes() }
                    val job = ok(importer.enqueue(ImportSource(collection.id, "fixture:m53/$name", name, name), bytes.inputStream()))
                    assertEquals(ImportState.COMPLETED, ok(importer.process(job.id, bytes.inputStream())).state)
                    "fixture:m53/$name" to bytes
                }
                setup = false // The public read-only exercise begins after bounded setup import.
                val before = ok(api.mutations())
                action(Lab(db, api, SearchScope(setOf(collection.id)), content))
                assertEquals("Queries must not write catalog mutations", before, ok(api.mutations()))
                assertTrue(db.integrityCheck())
            }
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
    @Test fun realFtsSearchHasExactHashesAndResolvableLocators() = fixture { lab ->
        for (query in listOf("astronomy", "navigation", "injection")) {
            val item = lab.ready(query).items.single()
            val citation = item.citations.single()
            val source = lab.content.getValue(citation.sourceUri)
            assertEquals(hash(source), citation.sourceSha256)
            assertEquals(hash(item.text.toByteArray(Charsets.UTF_8)), citation.textSha256)
            assertEquals(item.text, source.toString(Charsets.UTF_8).substring(citation.start, citation.end))
            assertEquals(item.text, ok(lab.rag.resolve(citation, lab.scope))) // real CitationResolver
            assertEquals(1, citation.page)
            assertEquals(1, citation.version)
            assertTrue(citation.documentId.isNotBlank())
        }
    }
    @Test fun multibyteUtf8RoundTripsWithoutInventedByteOffsets() = fixture { lab ->
        val item = lab.ready("navigation").items.single()
        assertTrue(item.text.contains("東京, 🛰️"))
        assertEquals(item.text, ok(lab.rag.resolve(item.citations.single(), lab.scope)))
        assertEquals(item.text, item.text.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
    }
    @Test fun documentInjectionRemainsUntrustedQuotedData() = fixture { lab ->
        val bundle = lab.ready("injection")
        val carrier = bundle.asUntrustedJson()
        assertTrue(carrier.startsWith("{\"format\":\"jarvis.nexus.context/1\",\"trusted\":false"))
        assertTrue(carrier.contains("\\\"role\\\":\\\"SYSTEM\\\""))
        assertFalse(carrier.contains("\"role\":\"SYSTEM\""))
        assertEquals(CatalogResult.Rejected(KnowledgeError.DENIED), lab.api.createCollection("document cannot authorize writes"))
        lab.authorized = false
        assertEquals(DocumentaryResult.Refused(DocumentaryRefusal.DENIED), lab.retrieve("injection"))
    }
    @Test fun noEvidenceAndInvalidInputAreExplicit() = fixture { lab ->
        assertEquals(DocumentaryResult.Refused(DocumentaryRefusal.NEXUS_REJECTED, KnowledgeError.NO_EVIDENCE), lab.retrieve("zzzzzzzz"))
        assertEquals(DocumentaryResult.Refused(DocumentaryRefusal.NEXUS_REJECTED, KnowledgeError.INVALID_INPUT), lab.retrieve("x".repeat(257)))
    }
    @Test fun missingAuthorityAndScopeGrantNothing() = fixture { lab ->
        assertEquals(DocumentaryResult.Refused(DocumentaryRefusal.DENIED), lab.retrieve("astronomy", DocumentaryCall(200)))
        assertEquals(CatalogResult.Rejected(KnowledgeError.DENIED), LocalRagService(lab.db, lab.api).retrieve(KnowledgeQuery("astronomy"), lab.scope))
        assertEquals(CatalogResult.Rejected(KnowledgeError.DENIED), lab.rag.retrieve(KnowledgeQuery("astronomy"), SearchScope(emptySet())))
    }
    @Test fun cancellationAndMonotonicDeadlineRefuseResults() = fixture { lab ->
        lab.cancelled = true
        assertEquals(DocumentaryResult.Refused(DocumentaryRefusal.CANCELLED), lab.retrieve("astronomy"))
        lab.cancelled = false; lab.now = 200
        assertEquals(DocumentaryResult.Refused(DocumentaryRefusal.DEADLINE_EXCEEDED), lab.retrieve("astronomy"))
    }
    @Test fun citationTamperingAndForeignScopeAreRefused() = fixture { lab ->
        val citation = lab.ready("astronomy").items.single().citations.single()
        assertEquals(CatalogResult.Rejected(KnowledgeError.INVALID_INPUT), lab.rag.resolve(citation.copy(sourceSha256 = "0".repeat(64)), lab.scope))
        assertEquals(CatalogResult.Rejected(KnowledgeError.DENIED), lab.rag.resolve(citation, SearchScope(setOf("foreign"))))
    }
    @Test fun serializedCarrierByteBudgetRefusesWithoutPartialExcerpt() = fixture { lab ->
        assertEquals(DocumentaryResult.Refused(DocumentaryRefusal.BYTE_BUDGET), lab.retrieve("astronomy", maxBytes = 256))
        assertTrue(lab.ready("astronomy").asUntrustedJson().toByteArray(Charsets.UTF_8).size <= 8192)
    }
}
