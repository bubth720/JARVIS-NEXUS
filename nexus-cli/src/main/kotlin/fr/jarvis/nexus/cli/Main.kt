package fr.jarvis.nexus.cli

import fr.jarvis.nexus.api.*
import fr.jarvis.nexus.store.NexusDatabase
import java.nio.file.Path

private fun <T> requireOk(result: CatalogResult<T>): T = when (result) {
    is CatalogResult.Ok -> result.value
    is CatalogResult.Rejected -> error("Catalog refused: ${result.error}")
}
/** CLI intentionally exposes only synthetic seeding and read-only inspection, not user-file import. */
fun main(args: Array<String>) {
    require(args.size == 2 && args[0] in setOf("seed-demo", "inspect", "rag-demo", "backup-demo")) { "Usage: nexus-cli <seed-demo|inspect|rag-demo> <host-chosen-db-path>" }
    if(args[0]=="backup-demo"){backupDemo(Path.of(args[1]));return}
    val writeDemo = args[0] in setOf("seed-demo", "rag-demo")
    NexusDatabase.open(Path.of(args[1])).use { db ->
        val api = KnowledgeCatalogService(db, AccessPolicy { it == Operation.READ || writeDemo && it == Operation.WRITE })
        if (args[0]=="seed-demo") {
            val collection = requireOk(api.createCollection("SYNTHETIC-DEMO"))
            for ((uri, text) in listOf("demo:alpha" to "Alpha synthetic text", "demo:beta" to "Beta synthetic text")) {
                requireOk(api.importSource(ImportRequest(collection.id, uri, uri, fragments = listOf(Fragment(0, text, 0, text.length, 1))), text.byteInputStream()))
            }
            val alpha = requireOk(api.documents()).single { it.uri == "demo:alpha" }
            if (alpha.version == 1) {
                val revised = "Alpha revised synthetic text"
                requireOk(api.importSource(ImportRequest(collection.id, alpha.uri, alpha.title, alpha.version, listOf(Fragment(0, revised, 0, revised.length, 1))), revised.byteInputStream()))
            }
        }
        if (args[0]=="rag-demo") ragDemo(db,api)
        check(db.integrityCheck())
        println("NEXUS integrity=ok api=$CATALOG_API_VERSION")
        requireOk(api.documents()).forEach { println("DOCUMENT id=${it.id} uri=${it.uri} version=${it.version} deleted=${it.deleted}") }
        println(requireOk(api.catalogJson()))
        println(requireOk(api.mutationJson()))
    }
}

private fun ragDemo(db: NexusDatabase, api: KnowledgeCatalogService) {
    val collection=requireOk(api.createCollection("SYNTHETIC-RAG-DEMO"))
    // Pass-through for these known synthetic fixture strings, not a production PDF/text parser.
    val parser=fr.jarvis.nexus.importing.ParserPort { _,bytes,stop -> stop.check();listOf(fr.jarvis.nexus.importing.ExtractedPage(1,bytes.toString(Charsets.UTF_8))) }
    val importer=fr.jarvis.nexus.importing.ImportCoordinator(db,api,fr.jarvis.nexus.importing.ParserRegistry(mapOf("txt" to parser)))
    listOf("Science: physics describes atoms.","Science: biology describes cells.","Science: astronomy describes stars.").forEachIndexed { index,text ->
        val job=requireOk(importer.enqueue(fr.jarvis.nexus.importing.ImportSource(collection.id,"demo:rag-$index","fixture.txt","Synthetic $index"),text.byteInputStream()))
        requireOk(importer.process(job.id,text.byteInputStream()))
    }
    val rag=fr.jarvis.nexus.rag.LocalRagService(db,api) { fr.jarvis.nexus.rag.SearchScope(setOf(collection.id)) }
    val scope=fr.jarvis.nexus.rag.SearchScope(setOf(collection.id))
    val bundle=requireOk(rag.retrieve(fr.jarvis.nexus.rag.KnowledgeQuery("science"),scope))
    bundle.items.flatMap { it.citations }.forEach { requireOk(rag.resolve(it,scope)) }
    println(bundle.asUntrustedJson())
    println("RAG citations="+bundle.items.sumOf { it.citations.size }+" resolved="+bundle.items.sumOf { it.citations.size })
}
