package fr.jarvis.nexus.adapters

import fr.jarvis.nexus.api.*
import fr.jarvis.nexus.importing.*
import fr.jarvis.nexus.search.FtsIndex
import fr.jarvis.nexus.store.NexusDatabase
import org.apache.pdfbox.pdmodel.*
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class ServicesImportTest {
    private fun <T> ok(result: CatalogResult<T>): T = (result as CatalogResult.Ok).value
    private fun pdf(): ByteArray = ByteArrayOutputStream().use { output ->
        PDDocument().use { document ->
            listOf("Alpha science evidence", "Beta quantum physics").forEach { text ->
                val page = PDPage(); document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    content.beginText(); content.setFont(PDType1Font.HELVETICA, 12f); content.newLineAtOffset(30f, 700f); content.showText(text); content.endText()
                }
            }
            document.save(output)
        }; output.toByteArray()
    }
    @Test fun threeRealFormatsReachSqliteFtsAndPdfPreservesActualPage() {
        val root = Files.createTempDirectory("nexus-real-parser-synthetic-")
        try {
            NexusDatabase.open(root.resolve("db.sqlite")).use { db ->
                val api = KnowledgeCatalogService(db, AccessPolicy { true }); val collection = ok(api.createCollection("synthetic"))
                val coordinator = ImportCoordinator(db, api, ServicesParser.registry())
                val documents = listOf("sample.txt" to "Cafe\u0301 technical text".toByteArray(), "sample.md" to "# Markdown\nLocal astronomy".toByteArray(), "sample.pdf" to pdf())
                documents.forEach { (name, bytes) ->
                    val job = ok(coordinator.enqueue(ImportSource(collection.id, "fixture:$name", name, name), bytes.inputStream()))
                    assertEquals(ImportState.COMPLETED, ok(coordinator.process(job.id, bytes.inputStream())).state)
                }
                val index = FtsIndex(db, api)
                assertEquals(1, ok(index.search("café")).size)
                assertEquals(1, ok(index.search("astronomy")).size)
                val hit = ok(index.search("quantum")).single()
                assertEquals(2, hit.page)
                assertTrue(hit.text.contains("Beta quantum physics"))
                assertEquals(hit.text.length, hit.end - hit.start)
                assertEquals(3, ok(api.documents()).size)
                assertTrue(db.integrityCheck())
            }
            NexusDatabase.open(root.resolve("db.sqlite")).use { db ->
                val api = KnowledgeCatalogService(db, AccessPolicy { true })
                assertEquals(2, ok(FtsIndex(db, api).search("quantum")).single().page)
            }
        } finally { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
    @Test fun invalidUtf8CorruptPdfAndArchiveDoNotBecomeDocuments() {
        val parser = ServicesParser()
        for ((name, bytes) in listOf("bad.txt" to byteArrayOf(0xc3.toByte(), 0x28), "bad.pdf" to "%PDF broken".toByteArray(), "bad.zip" to byteArrayOf(1))) {
            try { parser.parse(name, bytes, Cancellation { false }); fail("must refuse $name") }
            catch (_: ParserFailure) { }
        }
    }
    @Test fun cancellationRefusesBeforeServicesExtraction() {
        val token = CancellationToken().also { it.cancel() }
        try { ServicesParser().parse("sample.pdf", pdf(), token); fail("must cancel") } catch (_: ImportCancelled) { }
    }
}
