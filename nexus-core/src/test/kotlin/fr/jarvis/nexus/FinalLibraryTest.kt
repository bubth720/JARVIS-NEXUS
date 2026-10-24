package fr.jarvis.nexus

import fr.jarvis.nexus.api.*
import fr.jarvis.nexus.graph.*
import fr.jarvis.nexus.importing.*
import fr.jarvis.nexus.library.*
import fr.jarvis.nexus.rag.*
import fr.jarvis.nexus.store.NexusDatabase
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FinalLibraryTest {
    private fun <T> ok(result: CatalogResult<T>): T = (result as CatalogResult.Ok).value
    private fun fixture(action: (NexusDatabase,KnowledgeCatalogService,String)->Unit) {
        val root=Files.createTempDirectory("nexus-final-")
        try { NexusDatabase.open(root.resolve("library.db")).use { db ->
            val catalog=KnowledgeCatalogService(db,AccessPolicy { true })
            action(db,catalog,ok(catalog.createCollection("Synthetic")).id)
        } } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
    private fun zip(vararg members: Pair<String,ByteArray>): ByteArray {
        val out=java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> members.forEach { (name,bytes) -> z.putNextEntry(ZipEntry(name));z.write(bytes);z.closeEntry() } }
        return out.toByteArray()
    }

    @Test fun realDocxAndCsvHaveResolvableTextLocations() {
        val xml="""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Mécanique quantique.</w:t></w:r></w:p></w:body></w:document>"""
        val bytes=zip("[Content_Types].xml" to "<Types/>".toByteArray(),"word/document.xml" to xml.toByteArray())
        val parsed=StructuredParser().parse("synthetic.docx",bytes,Cancellation { false }).single()
        assertEquals("Mécanique quantique.\n",parsed.text);assertTrue(parsed.location!!.contains("word/document.xml"));assertNull(parsed.page)
        val csv="titre,note\r\nParis,\"ville, capitale\"\r\n"
        assertEquals(csv,StructuredParser().parse("synthetic.csv",csv.toByteArray(),Cancellation { false }).single().text)
    }

    @Test fun hostileContainersAndExternalEntitiesAreRefused() {
        val stop=Cancellation { false };val parser=StructuredParser()
        for((name,bytes) in listOf(
            "bad.csv" to "a,\"unterminated".toByteArray(),
            "bad.zip" to zip("../outside.csv" to "a,b".toByteArray()),
            "nested.zip" to zip("nested.zip" to zip("a.csv" to "a,b".toByteArray())),
            "bomb.zip" to zip("bomb.csv" to ByteArray(2*1024*1024+1){65}),
            "bad.xml" to "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><x>&e;</x>".toByteArray(),
            "bad.docx" to "not a zip".toByteArray()
        )) assertTrue(name,runCatching { parser.parse(name,bytes,stop) }.isFailure)
    }

    @Test fun unchangedHashReusesIndexAndMetadataPersists()=fixture { db,catalog,collection ->
        var calls=0;val parser=ParserPort { n,b,c -> calls++;StructuredParser().parse(n,b,c) }
        val coordinator=ImportCoordinator(db,catalog,ParserRegistry(mapOf("csv" to parser)))
        val bytes="concept,note\nphysique,mécanique quantique\n".toByteArray()
        val source=ImportSource(collection,"synthetic:table","table.csv","Synthetic")
        repeat(2) {
            val job=ok(coordinator.enqueue(source,bytes.inputStream(),ImportMetadata(100,"Science",setOf("local"))))
            ok(coordinator.process(job.id,bytes.inputStream()))
        }
        assertEquals(1,calls)
        val library=LibraryService(db,catalog) { SearchScope(setOf(collection)) }
        val entry=ok(library.list(SearchScope(setOf(collection)))).single()
        assertEquals(setOf("local"),entry.tags);assertEquals(1,entry.document.version)
        val evidence=ok(library.search(KnowledgeQuery("mécanique quantique",mediaTypes=setOf("csv"),phrase=true),SearchScope(setOf(collection)))).evidence.single()
        assertNotNull(evidence.location)
        val port=LocalRagService(db,catalog) { SearchScope(setOf(collection)) }
        assertEquals(evidence.hit.text,ok(port.resolve(evidence.hit.citation,SearchScope(setOf(collection)))))
    }

    @Test fun lowSpaceAndCancellationRemainTyped()=fixture { db,catalog,collection ->
        val source=ImportSource(collection,"synthetic:x","a.csv","A")
        val bytes="a,b".toByteArray()
        val low=ImportCoordinator(db,catalog,StructuredParser().registry(),freeBytes={0})
        assertEquals(CatalogResult.Rejected(KnowledgeError.LIMIT_EXCEEDED),low.enqueue(source,bytes.inputStream()))
        val importer=ImportCoordinator(db,catalog,StructuredParser().registry())
        val job=ok(importer.enqueue(source,bytes.inputStream()))
        assertEquals(CatalogResult.Rejected(KnowledgeError.CANCELLED),importer.process(job.id,bytes.inputStream(),Cancellation { true }))
        assertEquals(ImportState.CANCELLED,ok(importer.job(job.id)).state)
    }

    @Test fun graphRelationsKeepEvidenceAndRespectCollectionBoundaries()=fixture { db,catalog,collection ->
        val scope=SearchScope(setOf(collection));val importer=ImportCoordinator(db,catalog,StructuredParser().registry())
        val bytes="concept,note\nscience,atomes".toByteArray()
        val job=ok(importer.enqueue(ImportSource(collection,"synthetic:science","a.csv","Science"),bytes.inputStream()))
        val document=ok(importer.process(job.id,bytes.inputStream())).documentId!!
        val hit=ok(LibraryService(db,catalog) { scope }.search(KnowledgeQuery("atomes"),scope)).evidence.single().hit
        val graph=GraphService(db,catalog) { scope }
        val a=ok(graph.putNode(collection,NodeKind.DOCUMENT,"Science",documentId=document))
        val b=ok(graph.putNode(collection,NodeKind.CONCEPT,"Atome"))
        val edge=ok(graph.relate(collection,a.id,b.id,"MENTIONS",RelationOrigin.SUGGESTED,hit.citation))
        val page=ok(graph.page(collection));assertEquals(2,page.nodes.size);assertEquals(hit.citation,page.edges.single().citation)
        assertTrue(page.toJson().contains("SUGGESTED"))
        ok(graph.editRelation(collection,edge,1,"MENTIONS",RelationOrigin.SUGGESTED,true))
        assertTrue(ok(graph.page(collection)).edges.isEmpty())
        assertEquals(CatalogResult.Rejected(KnowledgeError.CONFLICT),graph.editRelation(collection,edge,1,"MENTIONS",RelationOrigin.EXPLICIT,false))
        assertEquals(CatalogResult.Rejected(KnowledgeError.DENIED),GraphService(db,catalog).page(collection))
    }

    @Test fun collectionRemovalRequiresUnchangedPreviewAndLeavesSources()=fixture { db,catalog,collection ->
        val scope=SearchScope(setOf(collection));val library=LibraryService(db,catalog) { scope }
        val importer=ImportCoordinator(db,catalog,StructuredParser().registry());val bytes="a,b".toByteArray()
        val job=ok(importer.enqueue(ImportSource(collection,"synthetic:a","a.csv","A"),bytes.inputStream()))
        ok(importer.process(job.id,bytes.inputStream()))
        val preview=ok(library.previewRemoval(collection))
        assertEquals(CatalogResult.Rejected(KnowledgeError.DENIED),library.removeCollection(collection,preview,false))
        assertEquals(1,ok(library.removeCollection(collection,preview,true)))
        assertTrue(ok(library.list(scope)).isEmpty());assertEquals(1,ok(catalog.sources()).size)
    }

    @Test fun absentEmbeddingModelIsExplicitlyUnsupported() {
        assertEquals(CatalogResult.Rejected(KnowledgeError.UNSUPPORTED_FORMAT),SemanticReranker(null).rerank("physics",emptyList()))
    }

    @Test fun queuedImportCanResumeInNewDatabaseOwner() {
        val root=Files.createTempDirectory("nexus-resume-");val path=root.resolve("nexus.db")
        try {
            val bytes="subject,note\nscience,atoms".toByteArray()
            val state=NexusDatabase.open(path).use { db ->
                val catalog=KnowledgeCatalogService(db,AccessPolicy { true });val collection=ok(catalog.createCollection("Resume")).id
                val importer=ImportCoordinator(db,catalog,StructuredParser().registry())
                val job=ok(importer.enqueue(ImportSource(collection,"synthetic:resume","resume.csv","Resume"),bytes.inputStream(),ImportMetadata(category="Science",tags=setOf("pending"))))
                collection to job.id
            }
            NexusDatabase.open(path).use { db ->
                val catalog=KnowledgeCatalogService(db,AccessPolicy { true });val importer=ImportCoordinator(db,catalog,StructuredParser().registry())
                ok(importer.recoverInterrupted());assertEquals(ImportState.COMPLETED,ok(importer.process(state.second,bytes.inputStream())).state)
                val library=LibraryService(db,catalog) { SearchScope(setOf(state.first)) }
                assertEquals(setOf("pending"),ok(library.list(SearchScope(setOf(state.first)))).single().tags)
            }
        } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
}
