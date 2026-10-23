package fr.jarvis.nexus.backup

import fr.jarvis.nexus.store.SchemaMigrator
import java.nio.file.Path

data class SnapshotLimits(val maxDatabaseBytes:Long=16L*1024*1024,val maxArchiveBytes:Long=20L*1024*1024,val maxPreviewDocuments:Int=1000) {
    init { require(maxDatabaseBytes in 4096..64L*1024*1024);require(maxArchiveBytes>=maxDatabaseBytes && maxArchiveBytes<=80L*1024*1024);require(maxPreviewDocuments in 1..10000) }
}
data class SnapshotManifest(val schemaVersion:Int,val databaseBytes:Long,val databaseSha256:String) {
    fun toJson():String="{\"format\":\"jarvis.nexus.snapshot/1\",\"schemaVersion\":$schemaVersion,\"engine\":\"sqlite\",\"files\":[{\"path\":\"knowledge.sqlite\",\"bytes\":$databaseBytes,\"sha256\":\"$databaseSha256\"}]}"
    companion object {
        // Canonical, deliberately closed v1 grammar: duplicates/unknown fields/versions are not guessed.
        private val pattern=Regex("""\{"format":"jarvis\.nexus\.snapshot/1","schemaVersion":([1-9][0-9]{0,3}),"engine":"sqlite","files":\[\{"path":"knowledge\.sqlite","bytes":([1-9][0-9]{0,9}),"sha256":"([0-9a-f]{64})"}\]}""")
        fun parse(json:String):SnapshotManifest {
            val fields=pattern.matchEntire(json)?.groupValues ?: throw BackupFailure(BackupError.INVALID_ARCHIVE)
            val schema=fields[1].toInt();if(schema!=SchemaMigrator.CURRENT_VERSION)throw BackupFailure(BackupError.INCOMPATIBLE_VERSION)
            return SnapshotManifest(schema,fields[2].toLong(),fields[3])
        }
    }
}
data class SnapshotReceipt(val archive:Path,val archiveSha256:String,val manifest:SnapshotManifest)
enum class ConflictReason { VERSION_DIFFERS, CONTENT_OR_METADATA_DIFFERS }
data class VersionConflict(val documentId:String,val currentVersion:Int,val restoredVersion:Int,val reason:ConflictReason=ConflictReason.VERSION_DIFFERS)
enum class ConflictResolution { KEEP_CURRENT_AND_SEPARATE_COPY }
data class ImportPreview(val documentCount:Int,val sourceCount:Int,val conflicts:List<VersionConflict>,val resolution:ConflictResolution=ConflictResolution.KEEP_CURRENT_AND_SEPARATE_COPY)
