package fr.jarvis.nexus.store

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/** Single connection owner. Operations and close are serialized on this object. */
class NexusDatabase private constructor(internal val connection: Connection, internal val path: Path) : NexusStoragePort {
    companion object {
        /** The host chooses this private DB path, never a document URI or untrusted string. */
        fun open(path: Path): NexusDatabase {
            require(!Files.isSymbolicLink(path)) { "A database symlink is not allowed" }
            val canonicalPath = path.toAbsolutePath().parent.toRealPath().resolve(path.fileName)
            Class.forName("org.sqlite.JDBC")
            val connection = DriverManager.getConnection("jdbc:sqlite:" + canonicalPath)
            return try {
                connection.createStatement().use { it.execute("PRAGMA foreign_keys=ON"); it.execute("PRAGMA busy_timeout=5000") }
                NexusSchema.migrate(NexusDatabase(connection, canonicalPath))
                NexusDatabase(connection, canonicalPath)
            } catch (failure: Throwable) { connection.close(); throw failure }
        }
    }
    @Synchronized internal fun <T> transaction(block: (Connection) -> T): T {
        if (connection.isClosed) throw java.sql.SQLException("Database closed")
        check(connection.autoCommit) { "Nested transactions not allowed" }
        connection.autoCommit = false
        try {
            val value = block(connection)
            connection.commit()
            return value
        } catch (failure: Throwable) {
            try { connection.rollback() } catch (rollback: Throwable) { failure.addSuppressed(rollback) }
            throw failure
        } finally { connection.autoCommit = true }
    }
    @Synchronized internal fun <T> read(block: (Connection) -> T): T = block(connection)
    fun integrityCheck(): Boolean = read { c ->
        c.createStatement().use { s -> s.executeQuery("PRAGMA integrity_check").use { r -> r.next() && r.getString(1) == "ok" && !r.next() } } &&
            c.createStatement().use { s -> s.executeQuery("PRAGMA foreign_key_check").use { !it.next() } }
    }
    private fun <T> sql(action: () -> T): T = try { action() } catch (e: java.sql.SQLException) { throw NexusStorageException(e) }
    override fun <T> readSession(block: (NexusSession) -> T): T = sql { read { block(JdbcSession(it)) } }
    override fun <T> inTransaction(block: (NexusSession) -> T): T = sql { transaction { block(JdbcSession(it)) } }
    override fun acquireImportLease(): AutoCloseable? {
        val lockPath = path.resolveSibling("." + path.fileName + ".nexus-import.lock")
        val channel = java.nio.channels.FileChannel.open(lockPath, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        val lock = try { channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
        if (lock == null) { channel.close(); return null }
        return AutoCloseable { try { lock.release() } finally { channel.close() } }
    }
    override fun availableBytes(): Long = Files.getFileStore(path.parent).usableSpace
    @Synchronized override fun close() = connection.close()
}

/** Migrations are atomic, including their version marker; future versions are refused. */

internal fun Connection.execute(sql: String, vararg values: Any?): Int = prepareStatement(sql).use { s ->
    values.forEachIndexed { index, value -> s.setObject(index + 1, value) }; s.executeUpdate()
}
internal fun <T> Connection.query(sql: String, vararg values: Any?, map: (ResultSet) -> T): List<T> = prepareStatement(sql).use { s ->
    values.forEachIndexed { index, value -> s.setObject(index + 1, value) }
    s.executeQuery().use { r -> buildList { while (r.next()) add(map(r)) } }
}

internal class JdbcSession(private val c: Connection) : NexusSession {
 override fun execute(sql: String, vararg values: Any?): Int = c.prepareStatement(sql).use { statement ->
   values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
   // SQLite DDL/PRAGMA may expose a result set; executeUpdate is not a general SQL executor.
   if (statement.execute()) 0 else statement.updateCount.coerceAtLeast(0)
 }
 override fun <T> query(sql: String, vararg values: Any?, map: (NexusRow) -> T): List<T> = c.query(sql, *values) { map(JdbcRow(it)) }
}
private class JdbcRow(private val r: ResultSet) : NexusRow {
 override fun getString(column: String): String? = r.getString(column)
 override fun getString(column: Int): String? = r.getString(column)
 override fun getInt(column: String) = r.getInt(column)
 override fun getInt(column: Int) = r.getInt(column)
 override fun getLong(column: String) = r.getLong(column)
 override fun getLong(column: Int) = r.getLong(column)
 override fun getDouble(column: String) = r.getDouble(column)
 override fun getDouble(column: Int) = r.getDouble(column)
 override fun getObject(column: String): Any? = r.getObject(column)
 override fun getObject(column: Int): Any? = r.getObject(column)
}
object SchemaMigrator {
 const val CURRENT_VERSION = NexusSchema.CURRENT_VERSION
 internal fun migrate(c: Connection, afterStatement: (Int) -> Unit = {}) {
   val storage = object : NexusStoragePort {
     override fun <T> readSession(block: (NexusSession) -> T): T = block(JdbcSession(c))
     override fun <T> inTransaction(block: (NexusSession) -> T): T {
       check(c.autoCommit); c.autoCommit=false
       try { val result=block(JdbcSession(c)); c.commit(); return result }
       catch (failure: Throwable) { c.rollback(); throw failure }
       finally { c.autoCommit=true }
     }
     override fun acquireImportLease(): AutoCloseable? = error("Migration only")
     override fun close() = Unit
   }
   NexusSchema.migrate(storage, afterStatement)
 }
}
