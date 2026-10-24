package fr.jarvis.nexus.android

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import fr.jarvis.nexus.store.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException

/** Opening failure is separate from established public catalog/RAG error contracts. */
sealed class StorageOpenResult {
    data class Ready(val storage: AndroidNexusStorage, val compileOptions: List<String>) : StorageOpenResult()
    data class Rejected(val reason: OpenFailure) : StorageOpenResult()
}
enum class OpenFailure { CAPABILITY_UNAVAILABLE, INCOMPATIBLE_VERSION, STORAGE_FAILURE }

/** One private SQLite connection, serialized statements/transactions; no JDBC/native library bundled. */
class AndroidNexusStorage private constructor(private val db: SQLiteDatabase, private val file: File) : NexusStoragePort {
    companion object {
        /** Caller supplies its app-private directory, never a URI selected by a document provider. */
        fun open(privateDirectory: File, name: String = "nexus.db"): StorageOpenResult {
            require(name.matches(Regex("[A-Za-z0-9_-]+\\.db")))
            require(privateDirectory.isDirectory)
            val file = File(privateDirectory.canonicalFile, name)
            // Refuse symlinks before touching existing state, including the import lease sidecar.
            if (file.canonicalFile != file.absoluteFile || File(file.parentFile, ".${file.name}.nexus-import.lock").let { it.canonicalFile != it.absoluteFile })
                return StorageOpenResult.Rejected(OpenFailure.STORAGE_FAILURE)
            var connection: SQLiteDatabase? = null
            return try {
                connection = SQLiteDatabase.openOrCreateDatabase(file, null)
                connection.setForeignKeyConstraintsEnabled(true)
                val storage = AndroidNexusStorage(connection, file)
                val version = storage.readSession { it.query("PRAGMA user_version") { row -> row.getInt(1) }.single() }
                if (version !in 0..NexusSchema.CURRENT_VERSION) {
                    connection.close(); return StorageOpenResult.Rejected(OpenFailure.INCOMPATIBLE_VERSION)
                }
                // compile_options is diagnostic only. Both real temporary virtual tables must work.
                val options = storage.readSession { it.query("PRAGMA compile_options") { row -> row.getString(1) } }
                if (!storage.probeFts5()) {
                    connection.close(); return StorageOpenResult.Rejected(OpenFailure.CAPABILITY_UNAVAILABLE)
                }
                NexusSchema.migrate(storage)
                StorageOpenResult.Ready(storage, options)
            } catch (_: RuntimeException) {
                connection?.close(); StorageOpenResult.Rejected(OpenFailure.STORAGE_FAILURE)
            }
        }
    }
    private val session = AndroidSession(db)
    private fun <T> guarded(action: () -> T): T = try { action() } catch (e: SQLiteException) { throw NexusStorageException(e) }
    @Synchronized override fun <T> readSession(block: (NexusSession) -> T): T = guarded { if (!db.isOpen) throw NexusStorageException(IllegalStateException("Database closed")); block(session) }
    @Synchronized override fun <T> inTransaction(block: (NexusSession) -> T): T = guarded {
        if (!db.isOpen) throw NexusStorageException(IllegalStateException("Database closed"))
        check(!db.inTransaction()) { "Nested transaction" }
        db.beginTransaction()
        try { val result = block(session); db.setTransactionSuccessful(); result } finally { db.endTransaction() }
    }
    private fun probeFts5(): Boolean = try {
        readSession { c ->
            c.execute("CREATE VIRTUAL TABLE temp.nexus_fts_probe USING fts5(body)")
            try {
                c.execute("INSERT INTO temp.nexus_fts_probe(body) VALUES(?)", "preuve")
                c.execute("CREATE VIRTUAL TABLE temp.nexus_vocab_probe USING fts5vocab(nexus_fts_probe, 'instance')")
                try {
                    check(c.query("SELECT body FROM temp.nexus_fts_probe WHERE nexus_fts_probe MATCH ? ORDER BY bm25(nexus_fts_probe)", "preuve") { it.getString(1) } == listOf("preuve"))
                    check(c.query("SELECT term FROM temp.nexus_vocab_probe") { it.getString(1) } == listOf("preuve"))
                } finally { c.execute("DROP TABLE temp.nexus_vocab_probe") }
            } finally { c.execute("DROP TABLE temp.nexus_fts_probe") }
        }; true
    } catch (_: RuntimeException) { false }
    @Synchronized override fun acquireImportLease(): AutoCloseable? {
        check(db.isOpen)
        val sidecar = File(file.parentFile, ".${file.name}.nexus-import.lock")
        if (sidecar.canonicalFile != sidecar.absoluteFile) throw NexusStorageException(IllegalArgumentException("Symlink lease refused"))
        val handle = RandomAccessFile(sidecar, "rw")
        val lock = try { handle.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
        if (lock == null) { handle.close(); return null }
        return AutoCloseable { try { lock.release() } finally { handle.close() } }
    }
    @Synchronized override fun close() = db.close()
}

internal class AndroidSession(private val db: SQLiteDatabase) : NexusSession {
    override fun execute(sql: String, vararg values: Any?): Int = db.compileStatement(sql).use { statement ->
        values.forEachIndexed { index, value -> when (value) {
            null -> statement.bindNull(index+1)
            is ByteArray -> statement.bindBlob(index+1, value)
            is Float, is Double -> statement.bindDouble(index+1, (value as Number).toDouble())
            is Number -> statement.bindLong(index+1, value.toLong())
            is String -> statement.bindString(index+1, value)
            else -> throw IllegalArgumentException("Unsupported SQL parameter")
        } }
        // executeUpdateDelete is not valid for all DDL on platform SQLite.
        statement.execute(); db.rawQuery("SELECT changes()", null).use { it.moveToFirst(); it.getInt(0) }
    }
    override fun <T> query(sql: String, vararg values: Any?, map: (NexusRow) -> T): List<T> {
        require(values.none { it == null || it is ByteArray }) { "Query parameters must be scalar and non-null" }
        return db.rawQuery(sql, values.map { it.toString() }.toTypedArray()).use { cursor ->
            val result = ArrayList<T>(); val row = AndroidRow(cursor)
            while (cursor.moveToNext()) result.add(map(row))
            result
        }
    }
}
private class AndroidRow(private val c: Cursor) : NexusRow {
    private fun index(name: String) = c.getColumnIndexOrThrow(name)
    override fun getString(column: String): String? = getString(index(column)+1)
    override fun getString(column: Int): String? = if (c.isNull(column-1)) null else c.getString(column-1)
    override fun getInt(column: String) = c.getInt(index(column))
    override fun getInt(column: Int) = c.getInt(column-1)
    override fun getLong(column: String) = c.getLong(index(column))
    override fun getLong(column: Int) = c.getLong(column-1)
    override fun getDouble(column: String) = c.getDouble(index(column))
    override fun getDouble(column: Int) = c.getDouble(column-1)
    override fun getObject(column: String): Any? = getObject(index(column)+1)
    override fun getObject(column: Int): Any? = when(c.getType(column-1)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> c.getLong(column-1)
        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(column-1)
        Cursor.FIELD_TYPE_BLOB -> c.getBlob(column-1)
        else -> c.getString(column-1)
    }
}
