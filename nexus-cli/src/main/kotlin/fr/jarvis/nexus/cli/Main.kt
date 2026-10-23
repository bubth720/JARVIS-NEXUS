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
    require(args.size == 2 && args[0] in setOf("seed-demo", "inspect")) { "Usage: nexus-cli <seed-demo|inspect> <host-chosen-db-path>" }
    val writeDemo = args[0] == "seed-demo"
    NexusDatabase.open(Path.of(args[1])).use { db ->
        val api = KnowledgeCatalogService(db, AccessPolicy { it == Operation.READ || writeDemo && it == Operation.WRITE })
        if (writeDemo) {
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
        check(db.integrityCheck())
        println("NEXUS integrity=ok api=$CATALOG_API_VERSION")
        requireOk(api.documents()).forEach { println("DOCUMENT id=${it.id} uri=${it.uri} version=${it.version} deleted=${it.deleted}") }
        println(requireOk(api.catalogJson()))
        println(requireOk(api.mutationJson()))
    }
}
