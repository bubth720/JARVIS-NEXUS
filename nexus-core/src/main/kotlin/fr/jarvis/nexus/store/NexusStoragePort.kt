package fr.jarvis.nexus.store

/** Serialized connection owner. Never expose JDBC, cursors or a database path to business logic. */
interface NexusStoragePort : AutoCloseable {
    fun <T> readSession(block: (NexusSession) -> T): T
    /** All statements and the version marker commit together, otherwise rollback. No nesting. */
    fun <T> inTransaction(block: (NexusSession) -> T): T
    /** Cross-process document parser lease; null means busy. Not an LLM resource reservation. */
    fun acquireImportLease(): AutoCloseable?
    /** Unknown space fails closed for imports; migration-only fixtures may omit it. */
    fun availableBytes(): Long? = null
}
interface NexusSession {
    fun execute(sql: String, vararg values: Any?): Int
    fun <T> query(sql: String, vararg values: Any?, map: (NexusRow) -> T): List<T>
}
class NexusStorageException(cause: Throwable) : RuntimeException(cause)
fun interface NexusClock { fun millis(): Long }
