package fr.jarvis.nexus.backup

import fr.jarvis.nexus.store.query
import java.sql.Connection

/** Application schema and real SQLite/FK integrity; does not migrate or repair anything. */
internal object IntegrityVerifier {
    fun verify(connection:Connection,expectedSchema:Int){
        if(connection.query("PRAGMA user_version"){it.getInt(1)}.single()!=expectedSchema)throw BackupFailure(BackupError.INCOMPATIBLE_VERSION)
        val required=setOf("collection","source","document","revision","fragment","index_state","mutation_log","import_job","import_page","fragment_fts","fragment_vocab","import_job_event")
        val present=connection.query("SELECT name FROM sqlite_master WHERE type='table'"){it.getString(1)}.toSet()
        if(!present.containsAll(required))throw BackupFailure(BackupError.INVALID_ARCHIVE)
        if(expectedSchema>=4 && !present.containsAll(setOf("document_metadata","import_metadata","fragment_location","graph_node","graph_edge")))throw BackupFailure(BackupError.INVALID_ARCHIVE)
        if(connection.query("PRAGMA integrity_check"){it.getString(1)}!=listOf("ok"))throw BackupFailure(BackupError.INVALID_ARCHIVE)
        if(connection.query("PRAGMA foreign_key_check"){it.getString(1)}.isNotEmpty())throw BackupFailure(BackupError.INVALID_ARCHIVE)
        if(expectedSchema>=4) {
            if(connection.query("SELECT n.id FROM graph_node n JOIN document d ON d.id=n.document_id WHERE n.collection_id!=d.collection_id LIMIT 1"){it.getString(1)}.isNotEmpty())throw BackupFailure(BackupError.INVALID_ARCHIVE)
            if(connection.query("SELECT e.id FROM graph_edge e JOIN document d ON d.id=e.document_id WHERE e.collection_id!=d.collection_id LIMIT 1"){it.getString(1)}.isNotEmpty())throw BackupFailure(BackupError.INVALID_ARCHIVE)
            connection.query("SELECT e.text_sha256,f.text,e.start_offset,e.end_offset,f.start_offset,f.end_offset FROM graph_edge e JOIN fragment f ON f.document_id=e.document_id AND f.version=e.document_version AND f.ordinal=e.ordinal"){r->
                if(r.getString(1)!=fr.jarvis.nexus.rag.sha256(r.getString(2)) || r.getInt(3)!=r.getInt(5) || r.getInt(4)!=r.getInt(6))throw BackupFailure(BackupError.INVALID_ARCHIVE)
            }
        }
        if(connection.query("SELECT d.id FROM document d LEFT JOIN revision r ON r.document_id=d.id AND r.version=d.version LEFT JOIN index_state i ON i.document_id=d.id WHERE r.document_id IS NULL OR i.document_id IS NULL LIMIT 1"){it.getString(1)}.isNotEmpty())throw BackupFailure(BackupError.INVALID_ARCHIVE)
        val broken=connection.query("SELECT f.document_id FROM fragment f JOIN document d ON d.id=f.document_id JOIN index_state i ON i.document_id=d.id LEFT JOIN fragment_fts x ON x.document_id=f.document_id AND x.version=f.version AND x.ordinal=f.ordinal WHERE d.deleted=0 AND d.version=f.version AND i.state='READY' AND i.indexed_version=d.version GROUP BY f.document_id,f.ordinal HAVING count(x.rowid)!=1 LIMIT 1"){it.getString(1)}
        if(broken.isNotEmpty())throw BackupFailure(BackupError.INVALID_ARCHIVE)
        connection.query("SELECT x.body,f.text FROM fragment_fts x JOIN fragment f ON f.document_id=x.document_id AND f.version=x.version AND f.ordinal=x.ordinal JOIN document d ON d.id=f.document_id JOIN index_state i ON i.document_id=d.id WHERE d.deleted=0 AND d.version=f.version AND i.state='READY'"){r->
            if(r.getString(1)!=java.text.Normalizer.normalize(r.getString(2),java.text.Normalizer.Form.NFC))throw BackupFailure(BackupError.INVALID_ARCHIVE)
        }
    }
}
