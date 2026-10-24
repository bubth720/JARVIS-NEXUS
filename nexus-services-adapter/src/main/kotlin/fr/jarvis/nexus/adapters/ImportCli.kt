package fr.jarvis.nexus.adapters

import fr.jarvis.nexus.api.*
import fr.jarvis.nexus.importing.*
import fr.jarvis.nexus.search.FtsIndex
import fr.jarvis.nexus.store.NexusDatabase
import java.nio.file.Files
import java.nio.file.Path

private fun <T> value(result: CatalogResult<T>): T = when(result) {
    is CatalogResult.Ok -> result.value
    is CatalogResult.Rejected -> error("Operation refused: ${result.error}")
}
/** Explicit host-selected file only; never scans directories or follows document instructions. */
fun main(args: Array<String>) {
    require(args.size >= 3 && args[0] in setOf("import", "search")) { "Usage: import <db> <selected-file> | search <db> <query>" }
    NexusDatabase.open(Path.of(args[1])).use { db ->
        val api = KnowledgeCatalogService(db, AccessPolicy { it == Operation.READ || args[0] == "import" && it == Operation.WRITE })
        if (args[0] == "import") {
            require(args.size in 3..4)
            val file = Path.of(args[2]); require(Files.isRegularFile(file) && !Files.isSymbolicLink(file))
            val collection = value(api.createCollection("SELECTED-LOCAL-DOCUMENTS"))
            val coordinator = ImportCoordinator(db, api, ServicesParser.registry())
            val source = ImportSource(collection.id, file.toAbsolutePath().normalize().toUri().toString(), file.fileName.toString(), file.fileName.toString(),args.getOrNull(3)?.toInt())
            val job = Files.newInputStream(file,java.nio.file.LinkOption.NOFOLLOW_LINKS).use { value(coordinator.enqueue(source, it,ImportMetadata(Files.getLastModifiedTime(file,java.nio.file.LinkOption.NOFOLLOW_LINKS).toMillis()))) }
            val completed = Files.newInputStream(file,java.nio.file.LinkOption.NOFOLLOW_LINKS).use { value(coordinator.process(job.id, it)) }
            println("IMPORT state=${completed.state} documentId=${completed.documentId} sha256=${completed.sha256}")
        } else {
            val hits = value(FtsIndex(db, api).search(args.drop(2).joinToString(" ")))
            hits.forEach { println("HIT documentId=${it.documentId} version=${it.version} page=${it.page ?: "unpaginated"} offsets=${it.start}:${it.end} text=${it.text}") }
        }
        check(db.integrityCheck())
    }
}
