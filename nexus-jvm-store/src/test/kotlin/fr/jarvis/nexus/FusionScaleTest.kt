package fr.jarvis.nexus

import fr.jarvis.nexus.api.*
import fr.jarvis.nexus.importing.*
import fr.jarvis.nexus.library.*
import fr.jarvis.nexus.rag.*
import fr.jarvis.nexus.store.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/** Actual coordinator/parser/FTS benchmark over 1000 synthetic CSV sources.
 * Timings/heap are observations in this JVM, never an Android or hardware SLA.
 */
class FusionScaleTest {
    private fun <T> ok(value: CatalogResult<T>):T=(value as CatalogResult.Ok).value
    @Test fun thousandDocumentsUseRealIncrementalImportAndRemainResolvableAfterReopen() {
        val root=Files.createTempDirectory("fusion-scale-");val database=root.resolve("nexus.db")
        try {
            val saved=NexusDatabase.open(database).use { db ->
                val catalog=KnowledgeCatalogService(db,AccessPolicy { true });val collection=ok(catalog.createCollection("FIXTURE-1000"))
                val scope=SearchScope(setOf(collection.id));var parses=0
                val parser=ParserPort { name,bytes,cancel -> parses++;StructuredParser().parse(name,bytes,cancel) }
                val importer=ImportCoordinator(db,catalog,ParserRegistry(mapOf("csv" to parser)))
                fun content(i:Int)="id,contenu\n$i,La science décrit les atomes numéro $i.\n".toByteArray()
                fun ingest(i:Int,bytes:ByteArray,expected:Int?=null) {
                    val job=ok(importer.enqueue(ImportSource(collection.id,"fixture:scale/$i.csv","$i.csv","Document $i",expected),bytes.inputStream()))
                    ok(importer.process(job.id,bytes.inputStream()))
                }
                val start=System.nanoTime();repeat(1000) { ingest(it,content(it)) };val ingestMs=(System.nanoTime()-start)/1_000_000.0
                assertEquals(1000,parses)
                val noParse=System.nanoTime();ingest(500,content(500));val unchangedMs=(System.nanoTime()-noParse)/1_000_000.0
                assertEquals(1000,parses)
                val revised="id,contenu\n500,Une révision vérifiée décrit les atomes.\n".toByteArray();ingest(500,revised,1)
                assertEquals(1001,parses)
                val library=LibraryService(db,catalog) { scope };val timings=mutableListOf<Double>()
                repeat(20) {
                    val t=System.nanoTime();assertEquals(20,ok(library.search(KnowledgeQuery("science",mediaTypes=setOf("csv")),scope)).evidence.size)
                    timings+=(System.nanoTime()-t)/1_000_000.0
                }
                val citation=ok(library.search(KnowledgeQuery("révision"),scope)).evidence.single().hit.citation
                assertEquals(2,citation.version)
                assertEquals(1000,db.read { it.query("SELECT count(*) FROM document") { r -> r.getInt(1) }.single() })
                assertEquals(1001,db.read { it.query("SELECT count(*) FROM revision") { r -> r.getInt(1) }.single() })
                assertEquals(1000,db.read { it.query("SELECT count(*) FROM fragment_fts") { r -> r.getInt(1) }.single() })
                assertTrue(db.integrityCheck())
                val heap=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()
                val rss=runCatching { Files.readAllLines(java.nio.file.Path.of("/proc/self/status")).first { it.startsWith("VmHWM:") }.filter { it.isDigit() }.toLong() }.getOrNull()
                timings.sort()
                println("FUSION_SCALE_JSON={\"documents\":1000,\"actualParserCalls\":$parses,\"ingestMs\":$ingestMs,\"unchangedMs\":$unchangedMs,\"queryP50Ms\":${timings[10]},\"queryP95Ms\":${timings[18]},\"databaseBytes\":${Files.size(database)},\"heapUsedSnapshotBytes\":$heap,\"processPeakRssKiB\":${rss ?: "null"},\"scope\":\"JVM real ImportCoordinator, CSV parser, FTS, revision and resolver; not Android\"}")
                scope to citation
            }
            NexusDatabase.open(database).use { reopened ->
                val catalog=KnowledgeCatalogService(reopened,AccessPolicy { true })
                val text=ok(LocalRagService(reopened,catalog) { saved.first }.resolve(saved.second,saved.first))
                assertTrue(text.contains("révision vérifiée"));assertEquals(sha256(text),saved.second.textSha256)
            }
        } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
}
