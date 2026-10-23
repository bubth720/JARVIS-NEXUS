package fr.jarvis.nexus.adapters

import fr.jarvis.nexus.api.KnowledgeError
import fr.jarvis.nexus.importing.*
import fr.jarvis.services.pipeline.DocumentTextExtractor
import fr.jarvis.services.platform.PdfBoxText
import fr.jarvis.services.shared.api.Contracts.Failure
import fr.jarvis.services.shared.tasks.Task

/** Reuses delivered SERVICES code at the source lock, no second PDF parser implementation. */
class ServicesParser : ParserPort {
    private val extractor = DocumentTextExtractor(PdfBoxText())
    override fun parse(name: String, bytes: ByteArray, cancellation: Cancellation): List<ExtractedPage> {
        cancellation.check()
        val task = Task(30_000)
        return try {
            extractor.extract(name, bytes, task).map { page ->
                cancellation.check()
                ExtractedPage(page.page.takeIf { it > 0 }, page.text)
            }
        } catch (e: ImportCancelled) { task.cancel(); throw e }
          catch (e: Failure) {
            throw ParserFailure(when (e.message) {
                "CANCELLED", "EXPIRED" -> KnowledgeError.CANCELLED
                "UNSUPPORTED_FORMAT", "PDF_UNAVAILABLE" -> KnowledgeError.UNSUPPORTED_FORMAT
                "INPUT_TOO_LARGE", "TEXT_TOO_LARGE" -> KnowledgeError.LIMIT_EXCEEDED
                else -> KnowledgeError.EXTRACTION_FAILED
            })
        }
    }
    companion object {
        fun registry(): ParserRegistry { val parser = ServicesParser(); return ParserRegistry(mapOf("txt" to parser, "md" to parser, "pdf" to parser)) }
    }
}
