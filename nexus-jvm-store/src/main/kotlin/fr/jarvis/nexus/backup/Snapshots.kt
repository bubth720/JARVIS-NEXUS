package fr.jarvis.nexus.backup

import fr.jarvis.nexus.api.*
import fr.jarvis.nexus.store.*
import org.sqlite.SQLiteConnection
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private fun <T> backupResult(action:()->T):BackupResult<T> = try { BackupResult.Ok(action()) }
catch(e:BackupFailure){BackupResult.Rejected(e.error)}
catch(_:SourceTooLarge){BackupResult.Rejected(BackupError.LIMIT_EXCEEDED)}
catch(_:java.security.ProviderException){BackupResult.Rejected(BackupError.AUTHENTICATION_FAILED)}
catch(_:UnsupportedOperationException){BackupResult.Rejected(BackupError.UNSUPPORTED_PLATFORM)}
catch(_:java.util.zip.ZipException){BackupResult.Rejected(BackupError.INVALID_ARCHIVE)}
catch(_:FileAlreadyExistsException){BackupResult.Rejected(BackupError.CONFLICT)}
catch(_:java.security.GeneralSecurityException){BackupResult.Rejected(BackupError.AUTHENTICATION_FAILED)}
catch(_:java.io.IOException){BackupResult.Rejected(BackupError.STORAGE_FAILURE)}
catch(_:java.sql.SQLException){BackupResult.Rejected(BackupError.STORAGE_FAILURE)}
private fun requireAccess(catalog:KnowledgeCatalogService,operation:Operation,confirmed:Boolean){if(!confirmed || !catalog.permits(operation))throw BackupFailure(BackupError.DENIED)}
private fun copyBounded(input:InputStream,output:OutputStream,max:Long):Long {
    val buffer=ByteArray(8192);var count=0L
    while(true){val read=input.read(buffer,0,minOf(buffer.size.toLong(),max-count+1).toInt());if(read<0)return count
        if(read==0){val one=input.read();if(one<0)return count;count++;if(count>max)throw BackupFailure(BackupError.LIMIT_EXCEEDED);output.write(one)}
        else{count+=read;if(count>max)throw BackupFailure(BackupError.LIMIT_EXCEEDED);output.write(buffer,0,read)}}
}
internal class OwnedWorkspace(val path:Path) {
    private val identity=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS).fileKey()
        ?: throw BackupFailure(BackupError.UNSUPPORTED_PLATFORM)
    fun assertOwned(){
        val attrs=try{Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)}catch(_:java.io.IOException){throw BackupFailure(BackupError.WORKSPACE_CHANGED)}
        if(!attrs.isDirectory || attrs.fileKey()!=identity)throw BackupFailure(BackupError.WORKSPACE_CHANGED)
    }
    fun resolve(name:String):Path {assertOwned();return path.resolve(name)}
    fun cleanup(){
        assertOwned()
        val entries=Files.walk(path).use{paths->paths.map{p->p to Files.readAttributes(p,java.nio.file.attribute.BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS).fileKey()}.toList()}
        for((p,key) in entries.sortedByDescending{it.first.nameCount}){
            assertOwned()
            val actual=Files.readAttributes(p,java.nio.file.attribute.BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS).fileKey()
            if(key==null || actual!=key)throw BackupFailure(BackupError.WORKSPACE_CHANGED)
            Files.delete(p)
        }
    }
}
private fun temporary(parent:Path):OwnedWorkspace {
    val path=try{Files.createTempDirectory(parent,"nexus-recovery-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))}
    catch(_:UnsupportedOperationException){Files.createTempDirectory(parent,"nexus-recovery-")}
    return OwnedWorkspace(path)
}
private fun hash(path:Path,max:Long):String=Files.newInputStream(path).use{SourceFingerprint.compute(it,max).sha256}
private fun durable(path:Path){FileChannel.open(path,StandardOpenOption.WRITE).use{it.force(true)}}

private data class DocumentState(val id:String,val version:Int,val fingerprint:String)
private fun documentStates(c:java.sql.Connection,limit:Int):List<DocumentState> = c.query("SELECT d.id,d.version,d.deleted,d.title,d.uri,d.collection_id,s.sha256 FROM document d JOIN revision r ON r.document_id=d.id AND r.version=d.version JOIN source s ON s.id=r.source_id ORDER BY d.id LIMIT ?",limit+1){r->
    val id=r.getString(1);val version=r.getInt(2);val digest=java.security.MessageDigest.getInstance("SHA-256")
    val out=java.io.DataOutputStream(java.security.DigestOutputStream(OutputStream.nullOutputStream(),digest))
    fun text(value:String){val bytes=value.toByteArray(Charsets.UTF_8);out.writeInt(bytes.size);out.write(bytes)}
    out.writeInt(version);out.writeInt(r.getInt(3));for(i in 4..7)text(r.getString(i))
    c.query("SELECT ordinal,text,start_offset,end_offset,page FROM fragment WHERE document_id=? AND version=? ORDER BY ordinal",id,version){f->
        out.writeInt(f.getInt(1));text(f.getString(2));out.writeInt(f.getInt(3));out.writeInt(f.getInt(4));out.writeInt(f.getObject(5)?.let{f.getInt(5)} ?: -1)
    }
    out.flush();DocumentState(id,version,digest.digest().joinToString(""){"%02x".format(it)})
}

class SnapshotExporter(private val database:NexusDatabase,private val catalog:KnowledgeCatalogService,private val crypto:CryptoPort,private val limits:SnapshotLimits=SnapshotLimits()) {
    /** Never overwrites an output or source; link publication is atomic and fails if target exists. */
    fun export(output:Path,confirmed:Boolean=false):BackupResult<SnapshotReceipt> = backupResult {
        requireAccess(catalog,Operation.EXPORT,confirmed)
        if(Files.exists(output,LinkOption.NOFOLLOW_LINKS))throw BackupFailure(BackupError.CONFLICT)
        val parent=output.toAbsolutePath().parent.toRealPath();val destination=parent.resolve(output.fileName);val workspace=temporary(parent)
        try{
            val snapshot=workspace.resolve("knowledge.sqlite")
            database.read{connection->
                val bytes=connection.query("PRAGMA page_count"){it.getLong(1)}.single()*connection.query("PRAGMA page_size"){it.getLong(1)}.single()
                if(bytes>limits.maxDatabaseBytes)throw BackupFailure(BackupError.LIMIT_EXCEEDED)
                IntegrityVerifier.verify(connection,SchemaMigrator.CURRENT_VERSION)
                if((connection as SQLiteConnection).database.backup("main",snapshot.toString(),null)!=0)throw BackupFailure(BackupError.STORAGE_FAILURE)
            }
            NexusDatabase.open(snapshot).use{copy->copy.read{c->
                IntegrityVerifier.verify(c,SchemaMigrator.CURRENT_VERSION)
                c.execute("INSERT INTO fragment_fts(fragment_fts) VALUES('integrity-check')")
            }}
            val size=Files.size(snapshot);if(size>limits.maxDatabaseBytes)throw BackupFailure(BackupError.LIMIT_EXCEEDED)
            val manifest=SnapshotManifest(SchemaMigrator.CURRENT_VERSION,size,hash(snapshot,limits.maxDatabaseBytes))
            val zip=workspace.resolve("payload.zip")
            Files.newOutputStream(zip,StandardOpenOption.CREATE_NEW).use{file->ZipOutputStream(file).use{out->
                out.putNextEntry(ZipEntry("manifest.json"));out.write(manifest.toJson().toByteArray(Charsets.UTF_8));out.closeEntry()
                out.putNextEntry(ZipEntry("knowledge.sqlite"));Files.newInputStream(snapshot).use{copyBounded(it,out,limits.maxDatabaseBytes)};out.closeEntry()
            }}
            if(Files.size(zip)>limits.maxArchiveBytes)throw BackupFailure(BackupError.LIMIT_EXCEEDED)
            val encrypted=workspace.resolve("completed.jnb")
            Files.newInputStream(zip).use{input->Files.newOutputStream(encrypted,StandardOpenOption.CREATE_NEW).use{out->crypto.encrypt(input,out,limits.maxArchiveBytes)}}
            workspace.assertOwned();durable(encrypted);requireAccess(catalog,Operation.EXPORT,confirmed)
            val digest=hash(encrypted,limits.maxArchiveBytes+64)
            // Both paths are on the same filesystem. No fallback can replace an existing archive.
            Files.createLink(destination,encrypted)
            SnapshotReceipt(destination,digest,manifest)
        }finally{workspace.cleanup()}
    }
}

/** Restored DB is a separate verified copy. Closing rolls back only this private workspace. */
class RecoveryWorkspace internal constructor(private val owned:OwnedWorkspace,val databaseFile:Path,val manifest:SnapshotManifest,val preview:ImportPreview):AutoCloseable {
    val directory:Path get()=owned.path
    private var retained=false;private var closed=false
    @Synchronized fun retainSeparateCopy():Path {check(!closed);owned.assertOwned();retained=true;return databaseFile}
    /** Audit cursor only; this does not claim reconstruction of all historical state from old logs. */
    @Synchronized fun audit(afterSequence:Long=0,limit:Int=100):CatalogResult<List<MutationRecord>> {
        check(!closed);owned.assertOwned()
        return NexusDatabase.open(databaseFile).use{db->KnowledgeCatalogService(db,AccessPolicy{it==Operation.READ}).mutations(afterSequence,limit)}
    }
    @Synchronized override fun close(){if(!closed){closed=true;if(!retained)owned.cleanup()}}
}
class SnapshotImporter(private val current:NexusDatabase,private val catalog:KnowledgeCatalogService,private val crypto:CryptoPort,private val limits:SnapshotLimits=SnapshotLimits()) {
    fun prepare(archive:Path,recoveryParent:Path,confirmed:Boolean=false,expectedArchiveSha256:String?=null):BackupResult<RecoveryWorkspace> = backupResult {
        requireAccess(catalog,Operation.RESTORE,confirmed)
        if(Files.isSymbolicLink(archive)||!Files.isRegularFile(archive))throw BackupFailure(BackupError.INVALID_ARCHIVE)
        if(Files.size(archive)>limits.maxArchiveBytes+64)throw BackupFailure(BackupError.LIMIT_EXCEEDED)
        val workspace=temporary(recoveryParent.toRealPath());var ready=false
        try{
            val zip=workspace.resolve("authenticated.zip")
            val digest=java.security.MessageDigest.getInstance("SHA-256")
            Files.newInputStream(archive,LinkOption.NOFOLLOW_LINKS).use{raw->java.security.DigestInputStream(raw,digest).use{input->Files.newOutputStream(zip,StandardOpenOption.CREATE_NEW).use{out->crypto.decrypt(input,out,limits.maxArchiveBytes+64)}}}
            workspace.assertOwned()
            val observed=digest.digest().joinToString(""){"%02x".format(it)}
            if(expectedArchiveSha256!=null && observed!=expectedArchiveSha256)throw BackupFailure(BackupError.INVALID_ARCHIVE)
            if(Files.size(zip)>limits.maxArchiveBytes)throw BackupFailure(BackupError.LIMIT_EXCEEDED)
            val restored=workspace.resolve("knowledge.sqlite")
            val manifest=java.util.zip.ZipFile(zip.toFile()).use{entries->
                val members=entries.entries().asSequence().take(3).toList()
                if(members.size!=2 || members.map{it.name}.toSet()!=setOf("manifest.json","knowledge.sqlite") || members.any{it.isDirectory})throw BackupFailure(BackupError.INVALID_ARCHIVE)
                val entry=entries.getEntry("manifest.json")
                if(entry.size !in 1..4096)throw BackupFailure(BackupError.LIMIT_EXCEEDED)
                val bytes=java.io.ByteArrayOutputStream();entries.getInputStream(entry).use{copyBounded(it,bytes,4096)}
                val text=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
                val manifest=SnapshotManifest.parse(text)
                val databaseEntry=entries.getEntry("knowledge.sqlite")
                if(manifest.databaseBytes !in 4096..limits.maxDatabaseBytes || databaseEntry.size!=manifest.databaseBytes)throw BackupFailure(BackupError.LIMIT_EXCEEDED)
                entries.getInputStream(databaseEntry).use{input->Files.newOutputStream(restored,StandardOpenOption.CREATE_NEW).use{out->if(copyBounded(input,out,limits.maxDatabaseBytes)!=manifest.databaseBytes)throw BackupFailure(BackupError.INVALID_ARCHIVE)}}
                if(hash(restored,limits.maxDatabaseBytes)!=manifest.databaseSha256)throw BackupFailure(BackupError.INVALID_ARCHIVE)
                val header=Files.newInputStream(restored).use{it.readNBytes(100)}
                if(header.size!=100 || !header.take(16).toByteArray().contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)))throw BackupFailure(BackupError.INVALID_ARCHIVE)
                // Check before opening: restore must not silently migrate a mislabelled database.
                if(java.nio.ByteBuffer.wrap(header).getInt(60)!=manifest.schemaVersion)throw BackupFailure(BackupError.INCOMPATIBLE_VERSION)
                manifest
            }
            val restoredRows=NexusDatabase.open(restored).use{db->
                db.read{c->
                    IntegrityVerifier.verify(c,manifest.schemaVersion)
                    val actual=c.query("PRAGMA user_version"){it.getInt(1)}.single()
                    if(actual!=manifest.schemaVersion)throw BackupFailure(BackupError.INCOMPATIBLE_VERSION)
                    c.execute("INSERT INTO fragment_fts(fragment_fts) VALUES('integrity-check')")
                    val rows=documentStates(c,limits.maxPreviewDocuments)
                    if(rows.size>limits.maxPreviewDocuments)throw BackupFailure(BackupError.LIMIT_EXCEEDED)
                    val sourceCount=c.query("SELECT count(*) FROM source"){it.getInt(1)}.single()
                    Pair(rows,sourceCount)
                }
            }
            val existing=current.read{c->
                val bytes=c.query("PRAGMA page_count"){it.getLong(1)}.single()*c.query("PRAGMA page_size"){it.getLong(1)}.single()
                if(bytes>limits.maxDatabaseBytes)throw BackupFailure(BackupError.LIMIT_EXCEEDED)
                documentStates(c,limits.maxPreviewDocuments)
            }
            if(existing.size>limits.maxPreviewDocuments)throw BackupFailure(BackupError.LIMIT_EXCEEDED)
            val byId=existing.associateBy{it.id}
            val conflicts=restoredRows.first.mapNotNull{r->byId[r.id]?.takeIf{it.fingerprint!=r.fingerprint}?.let{VersionConflict(r.id,it.version,r.version,if(it.version!=r.version)ConflictReason.VERSION_DIFFERS else ConflictReason.CONTENT_OR_METADATA_DIFFERS)}}
            requireAccess(catalog,Operation.RESTORE,confirmed)
            val preview=ImportPreview(restoredRows.first.size,restoredRows.second,conflicts)
            Files.delete(zip);ready=true
            RecoveryWorkspace(workspace,restored,manifest,preview)
        }finally{if(!ready)workspace.cleanup()}
    }
    fun dryRun(archive:Path,recoveryParent:Path,confirmed:Boolean=false):BackupResult<ImportPreview> = when(val result=prepare(archive,recoveryParent,confirmed)){
        is BackupResult.Rejected->result
        is BackupResult.Ok->result.value.use{BackupResult.Ok(it.preview)}
    }
}
