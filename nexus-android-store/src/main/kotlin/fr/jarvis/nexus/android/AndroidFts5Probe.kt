package fr.jarvis.nexus.android

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException

/** Process-local observation, never proof for another device/API/SQLite provider.
 * SUPPORTED qualifies SQL primitives only, not SAF, RAG, ABI or production use.
 */
enum class Fts5Status { SUPPORTED, UNSUPPORTED, UNKNOWN }
enum class Fts5Stage { OPEN, VERSION, CREATE, MATCH, VOCAB_INSTANCE, BM25, COMPLETE }
data class Fts5Capability(
    val status: Fts5Status,
    val sqliteVersion: String?,
    val compileOptions: List<String>,
    val lastStage: Fts5Stage,
    val apiLevel: Int,
    val processAbis: List<String>
)

/** No file path, grant, external library, permission, migration or network access.
 * Running this under Robolectric observes its host SQLite, not Android SQLite.
 */
object AndroidFts5Probe {
    fun inspect(): Fts5Capability {
        var stage = Fts5Stage.OPEN
        var version: String? = null
        var options = emptyList<String>()
        fun result(status: Fts5Status) = Fts5Capability(status, version, options, stage,
            android.os.Build.VERSION.SDK_INT, android.os.Build.SUPPORTED_ABIS.toList())
        return try {
            SQLiteDatabase.create(null).use { db ->
                val session = AndroidSession(db)
                stage = Fts5Stage.VERSION
                version = session.query("SELECT sqlite_version()") { it.getString(1) }.single()
                options = session.query("PRAGMA compile_options") { it.getString(1)!! }
                stage = Fts5Stage.CREATE
                session.execute("CREATE VIRTUAL TABLE nexus_fts_probe USING fts5(body)")
                session.execute("INSERT INTO nexus_fts_probe(rowid,body) VALUES(1,?)", "phare bleu phare")
                session.execute("INSERT INTO nexus_fts_probe(rowid,body) VALUES(2,?)", "arbre vert")
                stage = Fts5Stage.MATCH
                check(session.query("SELECT rowid FROM nexus_fts_probe WHERE nexus_fts_probe MATCH ?", "\"phare bleu\"") { it.getInt(1) } == listOf(1))
                stage = Fts5Stage.VOCAB_INSTANCE
                session.execute("CREATE VIRTUAL TABLE nexus_vocab_probe USING fts5vocab(nexus_fts_probe, 'instance')")
                check(session.query("SELECT offset FROM nexus_vocab_probe WHERE term=? AND doc=1 ORDER BY offset", "phare") { it.getInt(1) } == listOf(0, 2))
                stage = Fts5Stage.BM25
                val scores = session.query("SELECT bm25(nexus_fts_probe) FROM nexus_fts_probe WHERE nexus_fts_probe MATCH ?", "phare") { it.getDouble(1) }
                check(scores.size == 1 && scores.single().isFinite() && scores.single() < 0.0)
                stage = Fts5Stage.COMPLETE
            }
            result(Fts5Status.SUPPORTED)
        } catch (failure: SQLiteException) {
            // A lock, memory failure or other storage fault is UNKNOWN, not
            // evidence that a platform feature is absent.
            val message = failure.message.orEmpty().lowercase(java.util.Locale.ROOT)
            val missing = when (stage) {
                Fts5Stage.CREATE -> "no such module: fts5" in message
                Fts5Stage.VOCAB_INSTANCE -> "no such module: fts5vocab" in message
                Fts5Stage.BM25 -> "no such function: bm25" in message
                else -> false
            }
            result(if (missing) Fts5Status.UNSUPPORTED else Fts5Status.UNKNOWN)
        } catch (_: RuntimeException) { result(Fts5Status.UNKNOWN) }
    }
}
