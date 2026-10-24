package fr.jarvis.nexus.cli

import fr.jarvis.nexus.api.*
import fr.jarvis.nexus.graph.*
import fr.jarvis.nexus.importing.*
import fr.jarvis.nexus.library.*
import fr.jarvis.nexus.rag.*
import fr.jarvis.nexus.store.NexusDatabase
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

private fun <T> take(result: CatalogResult<T>): T = when(result) {
    is CatalogResult.Ok -> result.value
    is CatalogResult.Rejected -> error("NEXUS refused: ${result.error}")
}

/** Commands operate only on explicitly selected local paths. No watcher or server. */
internal fun libraryCli(args: Array<String>) {
    require(args.size >= 3) { "library <db> <create|import|list|search|graph> ..." }
    val operation=args[2]
    require(operation in setOf("create","import","list","search","graph"))
    NexusDatabase.open(Path.of(args[1])).use { db ->
        val catalog=KnowledgeCatalogService(db,AccessPolicy { it==Operation.READ || operation in setOf("create","import") && it==Operation.WRITE })
        if(operation=="create") { require(args.size==4); println(take(catalog.createCollection(args[3])).id);return }
        require(args.size>=4)
        val collection=args[3];val scope=SearchScope(setOf(collection))
        val library=LibraryService(db,catalog) { scope }
        when(operation) {
            "import" -> {
                require(args.size==5 || args.size==6)
                val path=Path.of(args[4]).toAbsolutePath().normalize()
                require(!Files.isSymbolicLink(path) && Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))
                val source=ImportSource(collection,path.toUri().toString(),path.fileName.toString(),path.fileName.toString(),args.getOrNull(5)?.toInt())
                val importer=ImportCoordinator(db,catalog,StructuredParser().registry())
                val job=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS).use { take(importer.enqueue(source,it,ImportMetadata(Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toMillis()))) }
                val result=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS).use { take(importer.process(job.id,it)) }
                println("documentId=${result.documentId} state=${result.state} sha256=${result.sha256}")
            }
            "list" -> {
                require(args.size in 4..5)
                take(library.list(scope,args.getOrNull(4) ?: "")).forEach { e ->
                    println("${e.document.id}\t${e.document.version}\t${e.type ?: "unknown"}\t${e.bytes}\t${e.indexState}\t${e.document.title}")
                }
            }
            "search" -> {
                require(args.size in 5..7)
                val query=KnowledgeQuery(args[4],mediaTypes=args.getOrNull(5)?.let { setOf(it) } ?: emptySet(),phrase=args.getOrNull(6)=="phrase")
                val found=take(library.search(query,scope))
                println(ContextBundle(found.evidence.map { ContextItem(it.hit.text,listOf(it.hit.citation),it.hit.ftsRank) },found.evidence.sumOf { it.hit.text.length },0).asUntrustedJson())
                println("insufficient=${found.assessment.insufficient} explicitContradiction=${found.assessment.explicitContradiction} interpretationRequired=true")
            }
            "graph" -> {
                require(args.size in 4..6)
                println(take(GraphService(db,catalog) { scope }.page(collection,args.getOrNull(4) ?: "",args.getOrNull(5) ?: "",200,20)).toJson())
            }
        }
    }
}

/** Synthetic end-to-end demo into a NEW database only; emits a real GraphPage. */
internal fun atlasDemo(path: Path) {
    require(!Files.exists(path,LinkOption.NOFOLLOW_LINKS)) { "Choose a new synthetic database path" }
    NexusDatabase.open(path).use { db ->
        val catalog=KnowledgeCatalogService(db,AccessPolicy { true })
        val collection=take(catalog.createCollection("SYNTHETIC-ATLAS"));val scope=SearchScope(setOf(collection.id))
        val importer=ImportCoordinator(db,catalog,StructuredParser().registry())
        val library=LibraryService(db,catalog) { scope };val graph=GraphService(db,catalog) { scope }
        listOf("Atome","Énergie","Cellule","Mémoire","Océan","Musique","Histoire","Logique","Réseau","Langage","Matière","Lumière").forEachIndexed { i,concept ->
            val bytes="concept,note\n$concept,Document synthétique mentionnant $concept\n".toByteArray()
            val job=take(importer.enqueue(ImportSource(collection.id,"synthetic:atlas-$i","atlas-$i.csv","Fiche $concept"),bytes.inputStream(),ImportMetadata(category=if(i%2==0) "Sciences" else "Culture")))
            val document=take(importer.process(job.id,bytes.inputStream())).documentId!!
            val hit=take(library.search(KnowledgeQuery(concept),scope,100)).evidence.first { it.hit.citation.documentId==document }.hit
            val node=take(graph.putNode(collection.id,NodeKind.DOCUMENT,"Fiche $concept",if(i%2==0) "Sciences" else "Culture",document))
            val idea=take(graph.putNode(collection.id,NodeKind.CONCEPT,concept,if(i%2==0) "Sciences" else "Culture"))
            take(graph.relate(collection.id,node.id,idea.id,"MENTIONS",RelationOrigin.EXPLICIT,hit.citation))
        }
        check(db.integrityCheck())
        println(take(graph.page(collection.id,nodeLimit=200,edgeLimit=20)).toJson())
    }
}
