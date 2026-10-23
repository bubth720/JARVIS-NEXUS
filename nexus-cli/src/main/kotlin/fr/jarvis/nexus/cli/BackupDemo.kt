package fr.jarvis.nexus.cli

import fr.jarvis.nexus.api.*
import fr.jarvis.nexus.backup.*
import fr.jarvis.nexus.store.NexusDatabase
import java.nio.file.*
import javax.crypto.KeyGenerator

/** Synthetic-only demo; its key/archive never survive the invocation. */
internal fun backupDemo(parent:Path){
    val root=Files.createTempDirectory(parent,"nexus-backup-demo-")
    fun <T> value(r:CatalogResult<T>):T=(r as CatalogResult.Ok).value
    fun <T> backup(r:BackupResult<T>):T=(r as BackupResult.Ok).value
    val key=KeyGenerator.getInstance("AES").also{it.init(256)}.generateKey()
    val crypto=AesGcmCryptoPort{key}
    try{NexusDatabase.open(root.resolve("synthetic.db")).use{db->
        val api=KnowledgeCatalogService(db,AccessPolicy{true})
        val collection=value(api.createCollection("SYNTHETIC-BACKUP-DEMO"))
        value(api.importSource(ImportRequest(collection.id,"demo:backup","Synthetic"),"Synthetic document".byteInputStream()))
        val receipt=backup(SnapshotExporter(db,api,crypto).export(root.resolve("snapshot.jnb"),true))
        val before=value(api.catalogJson())
        val corrupt=root.resolve("corrupt.jnb");val bytes=Files.readAllBytes(receipt.archive);bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();Files.write(corrupt,bytes)
        check(SnapshotImporter(db,api,crypto).prepare(corrupt,root,true)==BackupResult.Rejected(BackupError.AUTHENTICATION_FAILED))
        check(before==value(api.catalogJson()))
        val document=value(api.documents()).single();value(api.rename(document.id,document.version,"Newer synthetic title"))
        val current=value(api.catalogJson())
        val recovered=backup(SnapshotImporter(db,api,crypto).prepare(receipt.archive,root,true,receipt.archiveSha256))
        val conflicts=recovered.use{copy->
            check(copy.preview.documentCount==1)
            check(value(copy.audit()).isNotEmpty())
            copy.preview.conflicts.size
        }
        check(conflicts==1);check(current==value(api.catalogJson()))
        println("BACKUP exported=true authentication=verified restored_documents=1 conflicts=$conflicts corruption_refused=true originals_preserved=true")
        println("Synthetic archive SHA-256=${receipt.archiveSha256}; ephemeral key and files cleaned after demo")
    }}finally{Files.walk(root).use{paths->paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)}}
}
