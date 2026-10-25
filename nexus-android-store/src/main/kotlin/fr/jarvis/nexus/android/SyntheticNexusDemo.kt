package fr.jarvis.nexus.android

import fr.jarvis.nexus.api.*
import fr.jarvis.nexus.importing.*
import fr.jarvis.nexus.rag.*
import java.io.File

/** Explicit, isolated synthetic demo. Never called automatically or fed personal content. */
object SyntheticNexusDemo {
    data class Evidence(val citation: Citation, val reopenedText: String)
    fun run(privateDirectory: File, enabled: Boolean): CatalogResult<Evidence> {
        if (!enabled) return CatalogResult.Rejected(KnowledgeError.DENIED)
        val first = AndroidNexusStorage.open(privateDirectory, "nexus-demo.db")
        if (first !is StorageOpenResult.Ready) return CatalogResult.Rejected(KnowledgeError.STORAGE_FAILURE)
        val citation: Citation
        val collection: String
        first.storage.use { db ->
            val catalog = KnowledgeCatalogService(db, AccessPolicy { true })
            collection = (catalog.createCollection("NEX-06A synthetic") as CatalogResult.Ok).value.id
            val text = "Document synthétique : la bibliothèque contient un phare bleu."
            val imports = ImportCoordinator(db, catalog, ParserRegistry(mapOf("txt" to ParserPort { _, bytes, stop ->
                stop.check(); listOf(ExtractedPage(null, bytes.toString(Charsets.UTF_8)))
            })))
            val source = ImportSource(collection, "fixture://nex06a/synthetic.txt", "synthetic.txt", "Synthetic fixture")
            val queued = imports.enqueue(source, text.byteInputStream())
            if (queued !is CatalogResult.Ok) return queued as CatalogResult.Rejected
            val processed = imports.process(queued.value.id, text.byteInputStream())
            if (processed !is CatalogResult.Ok) return processed as CatalogResult.Rejected
            val rag = LocalRagService(db, KnowledgeCatalogService(db, AccessPolicy { true })) { SearchScope(setOf(collection)) }
            val found = rag.retrieve(KnowledgeQuery("phare"), SearchScope(setOf(collection)))
            if (found !is CatalogResult.Ok) return found as CatalogResult.Rejected
            citation = found.value.items.single().citations.single()
        }
        val reopened = AndroidNexusStorage.open(privateDirectory, "nexus-demo.db")
        if (reopened !is StorageOpenResult.Ready) return CatalogResult.Rejected(KnowledgeError.STORAGE_FAILURE)
        return reopened.storage.use { db ->
            when(val resolved = LocalRagService(db, KnowledgeCatalogService(db, AccessPolicy { true })) { SearchScope(setOf(collection)) }.resolve(citation, SearchScope(setOf(collection)))) {
                is CatalogResult.Rejected -> resolved
                is CatalogResult.Ok -> CatalogResult.Ok(Evidence(citation, resolved.value))
            }
        }
    }
}
