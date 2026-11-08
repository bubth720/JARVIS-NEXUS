package fr.jarvis.nexus.reuse

import fr.jarvis.nexus.api.*
import fr.jarvis.nexus.importing.*
import fr.jarvis.nexus.rag.*
import fr.jarvis.nexus.store.NexusDatabase
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class ConsultationTest {
    private fun <T> ok(value: CatalogResult<T>): T = (value as CatalogResult.Ok).value
    private class Lab(val db: NexusDatabase, val catalog: KnowledgeCatalogService, val scope: SearchScope) {
        var authorized = true
        var current = 1L
        var cancelled = false
        var now = 100L
        val gateway = DocumentaryGateway(db, catalog, { scope }, { now })
        val rag = LocalRagService(db, catalog) { scope }
        val adapter = ReadOnlyConsultationAdapter(gateway, true, { session, generation -> session=="session" && current==generation }, { now })
        fun request() = ConsultationRequest("session","request",1,KnowledgeQuery("astronomy"),scope)
        fun call() = DocumentaryCall(1000,{authorized},{cancelled})
    }
    private fun fixture(action: (Lab) -> Unit) {
        val root = Files.createTempDirectory("jarvis-reuse-public-corpus-")
        try {
            NexusDatabase.open(root.resolve("review.sqlite")).use { db ->
                var setup = true
                val catalog = KnowledgeCatalogService(db, AccessPolicy { operation -> setup || operation==Operation.READ })
                val collection = ok(catalog.createCollection("REUSE public synthetic M53 corpus"))
                val parser = ParserPort { _, bytes, cancellation -> cancellation.check(); listOf(ExtractedPage(1,bytes.toString(Charsets.UTF_8))) }
                val importer = ImportCoordinator(db,catalog,ParserRegistry(mapOf("txt" to parser)),chunkChars=1024)
                for (name in listOf("astronomy.txt","navigation.txt","injection.txt")) {
                    val bytes = javaClass.getResourceAsStream("/$name")!!.use { it.readBytes() }
                    val job = ok(importer.enqueue(ImportSource(collection.id,"fixture:m53/$name",name,name),bytes.inputStream()))
                    assertEquals(ImportState.COMPLETED,ok(importer.process(job.id,bytes.inputStream())).state)
                }
                setup = false
                val mutations = ok(catalog.mutations())
                action(Lab(db,catalog,SearchScope(setOf(collection.id))))
                assertEquals(mutations,ok(catalog.mutations()))
                assertTrue(db.integrityCheck())
            }
        } finally { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
    @Test fun disabledAndMissingSessionCallbacksNeverGrantRead() = fixture { lab ->
        assertEquals(ConsultationResult.Refused("DENIED"),ReadOnlyConsultationAdapter(lab.gateway).consult(lab.request(),lab.call()))
        assertEquals(ConsultationResult.Refused("DENIED"),ReadOnlyConsultationAdapter(lab.gateway,true).consult(lab.request(),lab.call()))
    }
    @Test fun realSqliteReturnsCorrelatedUntrustedCarrierAndResolvableCitations() = fixture { lab ->
        val result = lab.adapter.consult(lab.request(),lab.call()) as ConsultationResult.Ready
        assertEquals(listOf("session","request"),listOf(result.sessionId,result.requestId))
        assertEquals(1L,result.generation)
        assertTrue(result.untrustedJson.contains("\"trusted\":false"))
        assertFalse(result.citations.isEmpty())
        result.citations.forEach { citation ->
            val text = ok(lab.rag.resolve(citation,lab.scope))
            val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            assertEquals(citation.textSha256,hash)
        }
    }
    @Test fun reusedAdapterDoesNotCacheAuthorizationOrGeneration() = fixture { lab ->
        assertTrue(lab.adapter.consult(lab.request(),lab.call()) is ConsultationResult.Ready)
        lab.authorized=false
        assertEquals(ConsultationResult.Refused("DENIED"),lab.adapter.consult(lab.request(),lab.call()))
        lab.authorized=true; lab.current=2
        assertEquals(ConsultationResult.Refused("DENIED"),lab.adapter.consult(lab.request(),lab.call()))
    }
    @Test fun cancelDeadlineAndCallbackFailureRefuseFreshResults() = fixture { lab ->
        lab.cancelled=true
        assertEquals(ConsultationResult.Refused("CANCELLED"),lab.adapter.consult(lab.request(),lab.call()))
        lab.cancelled=false;lab.now=1000
        assertEquals(ConsultationResult.Refused("DEADLINE_EXCEEDED"),lab.adapter.consult(lab.request(),lab.call()))
        lab.now=100
        val failing = ReadOnlyConsultationAdapter(lab.gateway,true,{_,_->throw IllegalStateException("synthetic failure")},{lab.now})
        assertEquals(ConsultationResult.Refused("DENIED"),failing.consult(lab.request(),lab.call()))
    }
    @Test fun lateRevocationIsRecheckedBeforeReturningCarrier() = fixture { lab ->
        var calls=0
        val adapter = ReadOnlyConsultationAdapter(lab.gateway,true,{_,_->++calls<3},{lab.now})
        assertEquals(ConsultationResult.Refused("DENIED"),adapter.consult(lab.request(),lab.call()))
        assertTrue(calls>=3)
    }
    @Test fun injectionIsDataAndUnknownScopeStillDenied() = fixture { lab ->
        val request=lab.request().copy(query=KnowledgeQuery("injection"))
        val result=lab.adapter.consult(request,lab.call()) as ConsultationResult.Ready
        assertTrue(result.untrustedJson.contains("\\\"role\\\":\\\"SYSTEM\\\""))
        assertEquals(CatalogResult.Rejected(KnowledgeError.DENIED),lab.catalog.createCollection("no write capability"))
        assertTrue(lab.adapter.consult(request.copy(scope=SearchScope(emptySet())),lab.call()) is ConsultationResult.Refused)
    }
    @Test fun invalidCorrelationAndTooSmallCarrierBudgetRefuse() = fixture { lab ->
        assertEquals(ConsultationResult.Refused("INVALID_CORRELATION"),lab.adapter.consult(lab.request().copy(requestId="../bad"),lab.call()))
        assertTrue(lab.adapter.consult(lab.request(),lab.call(),maxUtf8Bytes=256) is ConsultationResult.Refused)
    }
    @Test fun measureReferenceConsumerAgainstGatewayReuseWithoutPerformanceThreshold() = fixture { lab ->
        fun baseline(): String {
            val result=lab.gateway.retrieve(KnowledgeQuery("astronomy"),lab.scope,lab.call()) as DocumentaryResult.Ready
            result.bundle.items.flatMap { it.citations }.forEach { ok(lab.rag.resolve(it,lab.scope)) }
            return result.bundle.asUntrustedJson()
        }
        fun candidate()=(lab.adapter.consult(lab.request(),lab.call()) as ConsultationResult.Ready).untrustedJson
        repeat(10) { assertEquals(baseline(),candidate()) }
        val reference=mutableListOf<Long>();val reuse=mutableListOf<Long>()
        val referenceCpu=mutableListOf<Long>();val reuseCpu=mutableListOf<Long>()
        val thread=java.lang.management.ManagementFactory.getThreadMXBean()
        val memory=java.lang.management.ManagementFactory.getMemoryMXBean()
        val heapBefore=memory.heapMemoryUsage.used
        fun timed(action: () -> String, elapsed: MutableList<Long>, cpu: MutableList<Long>): String {
            val beforeCpu=if(thread.isCurrentThreadCpuTimeSupported && thread.isThreadCpuTimeEnabled) thread.currentThreadCpuTime else -1
            val started=System.nanoTime();val text=action();elapsed.add(System.nanoTime()-started)
            if(beforeCpu>=0) cpu.add(thread.currentThreadCpuTime-beforeCpu)
            return text
        }
        repeat(60) { index ->
            if (index%2==0) {
                val before=timed(::baseline,reference,referenceCpu)
                val after=timed(::candidate,reuse,reuseCpu);assertEquals(before,after)
            } else {
                val after=timed(::candidate,reuse,reuseCpu)
                val before=timed(::baseline,reference,referenceCpu);assertEquals(before,after)
            }
        }
        fun metrics(values: List<Long>): String {
            val sorted=values.sorted()
            return "{\"samples\":${values.size},\"median_ns\":${sorted[values.size/2]},\"p95_ns\":${sorted[(values.size*95+99)/100-1]},\"min_ns\":${sorted.first()},\"max_ns\":${sorted.last()}}"
        }
        val output=System.getenv("REUSE_BENCHMARK_OUTPUT") ?: return@fixture
        val file=java.nio.file.Path.of(output);Files.createDirectories(file.parent)
        val cpuReference=if(referenceCpu.isEmpty()) "null" else metrics(referenceCpu)
        val cpuReuse=if(reuseCpu.isEmpty()) "null" else metrics(reuseCpu)
        Files.writeString(file,"{\"status\":\"MEASURED\",\"scope\":\"JVM_SQLITE_SYNTHETIC_ONLY\",\"baseline\":\"M53_test_consumer_redundant_public_citation_resolve_NOT_production\",\"reference\":${metrics(reference)},\"candidate\":${metrics(reuse)},\"thread_cpu_reference\":$cpuReference,\"thread_cpu_candidate\":$cpuReuse,\"whole_test_jvm_heap_before_bytes\":$heapBefore,\"whole_test_jvm_heap_after_bytes\":${memory.heapMemoryUsage.used},\"available_processors\":${Runtime.getRuntime().availableProcessors()},\"equivalent_payloads\":true,\"timing_threshold_asserted\":false,\"android\":\"NOT_RUN\",\"llm\":\"NOT_RUN\"}\n")
    }
}
