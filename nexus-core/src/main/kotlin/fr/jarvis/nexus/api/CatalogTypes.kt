package fr.jarvis.nexus.api

/** Storage-local API v1, not an amendment to UNIFIED contracts/v1. */
const val CATALOG_API_VERSION = 1

enum class Operation { READ, WRITE, PURGE, EXPORT, RESTORE }
fun interface AccessPolicy { fun allows(operation: Operation): Boolean }
enum class KnowledgeError { DENIED, INVALID_INPUT, LIMIT_EXCEEDED, NOT_FOUND, CONFLICT, STORAGE_FAILURE, UNSUPPORTED_FORMAT, CANCELLED, BUSY, EXTRACTION_FAILED, NO_EVIDENCE, BUDGET_EXCEEDED }
sealed interface CatalogResult<out T> {
    data class Ok<T>(val value: T) : CatalogResult<T>
    data class Rejected(val error: KnowledgeError) : CatalogResult<Nothing>
}
data class CollectionRecord(val id: String, val name: String)
data class SourceRecord(val id: String, val uri: String, val sha256: String, val byteCount: Long)
data class DocumentRecord(val id: String, val collectionId: String, val uri: String, val title: String, val version: Int, val deleted: Boolean)
data class Fragment(val ordinal: Int, val text: String, val start: Int, val end: Int, val page: Int? = null)
data class RevisionRecord(val documentId: String, val version: Int, val sourceId: String, val title: String)
data class MutationRecord(val sequence: Long, val documentId: String?, val kind: String)
data class ImportRequest(val collectionId: String, val uri: String, val title: String, val expectedVersion: Int? = null, val fragments: List<Fragment> = emptyList())
sealed interface ImportOutcome {
    val document: DocumentRecord
    data class Created(override val document: DocumentRecord) : ImportOutcome
    data class Unchanged(override val document: DocumentRecord) : ImportOutcome
    data class Revised(override val document: DocumentRecord) : ImportOutcome
}
data class CatalogLimits(
    val maxSourceBytes: Long = 16L * 1024 * 1024,
    val maxFragments: Int = 2048,
    val maxFragmentChars: Int = 8192,
    val maxTotalFragmentChars: Int = 2_000_000,
    val maxPageSize: Int = 200,
) {
    init { require(maxSourceBytes in 1..(256L * 1024 * 1024)); require(maxFragments in 1..10000); require(maxFragmentChars in 1..65536); require(maxTotalFragmentChars in 1..10_000_000); require(maxPageSize in 1..1000) }
}

/** A host supplies an authorized stream; NEXUS never opens a URI/path from content. */
interface KnowledgeReadPort {
    fun documents(limit: Int = 100, offset: Int = 0, includeDeleted: Boolean = false): CatalogResult<List<DocumentRecord>>
    fun fragments(documentId: String, version: Int, limit: Int = 100, offset: Int = 0): CatalogResult<List<Fragment>>
    fun sources(limit: Int = 100, offset: Int = 0): CatalogResult<List<SourceRecord>>
}
