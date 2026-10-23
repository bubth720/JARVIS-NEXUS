package fr.jarvis.nexus

import fr.jarvis.nexus.api.*
import fr.jarvis.nexus.backup.*
import fr.jarvis.nexus.store.NexusDatabase
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import javax.crypto.KeyGenerator

class SnapshotRecoveryTest {
    private fun <T> ok(result:BackupResult<T>):T=(result as BackupResult.Ok).value
    private fun <T> catalog(result:CatalogResult<T>):T=(result as CatalogResult.Ok).value
    private fun fixture(test:(Path,NexusDatabase,KnowledgeCatalogService,CryptoPort)->Unit){
        val root=Files.createTempDirectory("nexus-encrypted-synthetic-")
        val key=KeyGenerator.getInstance("AES").also{it.init(256)}.generateKey()
        try{NexusDatabase.open(root.resolve("original.db")).use{db->
            val api=KnowledgeCatalogService(db,AccessPolicy{true});val c=catalog(api.createCollection("synthetic"))
            catalog(api.importSource(ImportRequest(c.id,"opaque:synthetic","Synthetic",fragments=listOf(Fragment(0,"synthetic evidence",0,18))),"synthetic evidence".byteInputStream()))
            test(root,db,api,AesGcmCryptoPort{key})
            assertTrue(db.integrityCheck())
        }}finally{Files.walk(root).use{paths->paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)}}
    }
    @Test fun realBackupRestoresEquivalentDataToSeparateWorkspaceAndRollsBackOnClose()=fixture{root,db,api,crypto->
        val original=catalog(api.catalogJson());val audit=catalog(api.mutations())
        val receipt=ok(SnapshotExporter(db,api,crypto).export(root.resolve("backup.jnb"),true))
        val recovery=ok(SnapshotImporter(db,api,crypto).prepare(receipt.archive,root,true,receipt.archiveSha256))
        val recoveredPath=recovery.directory
        recovery.use{
            assertEquals(1,it.preview.documentCount);assertEquals(1,it.preview.sourceCount);assertTrue(it.preview.conflicts.isEmpty())
            NexusDatabase.open(it.databaseFile).use{copy->assertEquals(original,catalog(KnowledgeCatalogService(copy,AccessPolicy{true}).catalogJson()))}
            assertEquals(audit,catalog(it.audit()))
        }
        assertFalse(Files.exists(recoveredPath));assertEquals(original,catalog(api.catalogJson()))
        assertTrue(Files.exists(receipt.archive))
    }
    @Test fun wrongKeyAndTamperingRefuseWithoutChangingOriginal()=fixture{root,db,api,crypto->
        val archive=ok(SnapshotExporter(db,api,crypto).export(root.resolve("backup.jnb"),true)).archive
        val another=KeyGenerator.getInstance("AES").also{it.init(256)}.generateKey()
        val before=Files.readAllBytes(root.resolve("original.db"))
        assertEquals(BackupResult.Rejected(BackupError.AUTHENTICATION_FAILED),SnapshotImporter(db,api,AesGcmCryptoPort{another}).prepare(archive,root,true))
        val bytes=Files.readAllBytes(archive);bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();Files.write(archive,bytes)
        assertEquals(BackupResult.Rejected(BackupError.AUTHENTICATION_FAILED),SnapshotImporter(db,api,crypto).prepare(archive,root,true))
        assertArrayEquals(before,Files.readAllBytes(root.resolve("original.db")))
        assertEquals(0,Files.list(root).use{it.filter{p->p.fileName.toString().startsWith("nexus-recovery-")}.count()})
    }
    @Test fun absentKeyAndAbsentConfirmationRefuseAndExistingArchiveIsNeverOverwritten()=fixture{root,db,api,crypto->
        val target=root.resolve("existing.jnb");Files.writeString(target,"ORIGINAL")
        assertEquals(BackupResult.Rejected(BackupError.CONFLICT),SnapshotExporter(db,api,crypto).export(target,true))
        assertEquals("ORIGINAL",Files.readString(target))
        assertEquals(BackupResult.Rejected(BackupError.DENIED),SnapshotExporter(db,api,crypto).export(root.resolve("unconfirmed.jnb")))
        assertEquals(BackupResult.Rejected(BackupError.KEY_UNAVAILABLE),SnapshotExporter(db,api,AesGcmCryptoPort{null}).export(root.resolve("no-key.jnb"),true))
        assertFalse(Files.exists(root.resolve("no-key.jnb")))
    }
    @Test fun repeatedExportsUseFreshNonceAndDoNotContainClearDocumentText()=fixture{root,db,api,crypto->
        val a=ok(SnapshotExporter(db,api,crypto).export(root.resolve("a.jnb"),true))
        val b=ok(SnapshotExporter(db,api,crypto).export(root.resolve("b.jnb"),true))
        assertNotEquals(a.archiveSha256,b.archiveSha256)
        assertFalse(Files.readAllBytes(a.archive).toString(Charsets.ISO_8859_1).contains("synthetic evidence"))
    }
    @Test fun changedCurrentVersionProducesPreviewConflictWithoutAdoption()=fixture{root,db,api,crypto->
        val archive=ok(SnapshotExporter(db,api,crypto).export(root.resolve("backup.jnb"),true)).archive
        val document=catalog(api.documents()).single();catalog(api.rename(document.id,document.version,"Changed"))
        val preview=ok(SnapshotImporter(db,api,crypto).dryRun(archive,root,true))
        assertEquals(1,preview.conflicts.size);assertEquals(ConflictResolution.KEEP_CURRENT_AND_SEPARATE_COPY,preview.resolution)
        assertEquals("Changed",catalog(api.documents()).single().title)
    }
    @Test fun truncatedArchiveHashMismatchAndPermissionDenialAreExplicit()=fixture{root,db,api,crypto->
        val receipt=ok(SnapshotExporter(db,api,crypto).export(root.resolve("backup.jnb"),true))
        assertEquals(BackupResult.Rejected(BackupError.INVALID_ARCHIVE),SnapshotImporter(db,api,crypto).prepare(receipt.archive,root,true,"0".repeat(64)))
        val denied=KnowledgeCatalogService(db,AccessPolicy{it==Operation.READ})
        assertEquals(BackupResult.Rejected(BackupError.DENIED),SnapshotImporter(db,denied,crypto).prepare(receipt.archive,root,true))
        val truncated=root.resolve("truncated.jnb");Files.write(truncated,Files.readAllBytes(receipt.archive).take(40).toByteArray())
        assertTrue(SnapshotImporter(db,api,crypto).prepare(truncated,root,true) is BackupResult.Rejected)
    }
    @Test fun raceCreatingDestinationAfterEncryptionCannotClobberIt()=fixture{root,db,api,crypto->
        val destination=root.resolve("race.jnb")
        val racing=object:CryptoPort{
            override fun encrypt(input:java.io.InputStream,output:java.io.OutputStream,maxInputBytes:Long){crypto.encrypt(input,output,maxInputBytes);Files.writeString(destination,"RACE-CREATED",java.nio.file.StandardOpenOption.CREATE_NEW)}
            override fun decrypt(input:java.io.InputStream,output:java.io.OutputStream,maxInputBytes:Long)=crypto.decrypt(input,output,maxInputBytes)
        }
        assertEquals(BackupResult.Rejected(BackupError.CONFLICT),SnapshotExporter(db,api,racing).export(destination,true))
        assertEquals("RACE-CREATED",Files.readString(destination))
    }
    @Test fun authenticatedZipTraversalIsRefusedAndCannotWriteOutsideWorkspace()=fixture{root,db,api,crypto->
        val zip=java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(zip).use{out->out.putNextEntry(java.util.zip.ZipEntry("../outside"));out.write(byteArrayOf(1));out.closeEntry()}
        val archive=root.resolve("bad.jnb")
        Files.newOutputStream(archive).use{out->crypto.encrypt(zip.toByteArray().inputStream(),out,8192)}
        assertEquals(BackupResult.Rejected(BackupError.INVALID_ARCHIVE),SnapshotImporter(db,api,crypto).prepare(archive,root,true))
        assertFalse(Files.exists(root.resolve("outside")))
    }
    @Test fun authenticatedManifestCannotHideWrongDatabaseVersionOrHash()=fixture{root,db,api,crypto->
        val before=Files.readAllBytes(root.resolve("original.db"))
        val bytes=before.clone();java.nio.ByteBuffer.wrap(bytes).putInt(60,1)
        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        for((name,sha)in listOf("version" to digest,"hash" to "0".repeat(64))){
            val zip=java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(zip).use{out->
                out.putNextEntry(java.util.zip.ZipEntry("manifest.json"));out.write(SnapshotManifest(3,bytes.size.toLong(),sha).toJson().toByteArray());out.closeEntry()
                out.putNextEntry(java.util.zip.ZipEntry("knowledge.sqlite"));out.write(bytes);out.closeEntry()
            }
            val archive=root.resolve(name+".jnb");Files.newOutputStream(archive).use{crypto.encrypt(zip.toByteArray().inputStream(),it,20L*1024*1024)}
            assertEquals(BackupResult.Rejected(if(name=="version")BackupError.INCOMPATIBLE_VERSION else BackupError.INVALID_ARCHIVE),SnapshotImporter(db,api,crypto).prepare(archive,root,true))
        }
        assertArrayEquals(before,Files.readAllBytes(root.resolve("original.db")))
    }
    @Test fun exportDoesNotChangeOriginalBytesAndRetainKeepsOnlySeparateCopy()=fixture{root,db,api,crypto->
        val before=Files.readAllBytes(root.resolve("original.db"))
        val receipt=ok(SnapshotExporter(db,api,crypto).export(root.resolve("keep.jnb"),true))
        assertArrayEquals(before,Files.readAllBytes(root.resolve("original.db")))
        val recovered=ok(SnapshotImporter(db,api,crypto).prepare(receipt.archive,root,true))
        val copy=recovered.retainSeparateCopy();recovered.close();assertTrue(Files.isRegularFile(copy))
        assertNotEquals(root.resolve("original.db"),copy)
    }
    @Test fun missingRequiredSchemaIsNeverExportedAsVerifiedBackup()=fixture{root,db,api,crypto->
        db.read{c->c.createStatement().use{it.execute("DROP TABLE mutation_log")}}
        assertEquals(BackupResult.Rejected(BackupError.INVALID_ARCHIVE),SnapshotExporter(db,api,crypto).export(root.resolve("invalid.jnb"),true))
        assertFalse(Files.exists(root.resolve("invalid.jnb")))
    }
    @Test fun equalVersionForksStillProduceAConflictAndNeverOverwriteEitherCopy()=fixture{root,db,api,crypto->
        val base=ok(SnapshotExporter(db,api,crypto).export(root.resolve("base.jnb"),true))
        val document=catalog(api.documents()).single();catalog(api.rename(document.id,1,"Fork A"))
        val fork=ok(SnapshotExporter(db,api,crypto).export(root.resolve("fork-a.jnb"),true))
        ok(SnapshotImporter(db,api,crypto).prepare(base.archive,root,true)).use{copy->
            NexusDatabase.open(copy.databaseFile).use{dbB->
                val apiB=KnowledgeCatalogService(dbB,AccessPolicy{true});catalog(apiB.rename(document.id,1,"Fork B"))
                val preview=ok(SnapshotImporter(dbB,apiB,crypto).dryRun(fork.archive,root,true))
                val conflict=preview.conflicts.single();assertEquals(2,conflict.currentVersion);assertEquals(2,conflict.restoredVersion)
                assertEquals(ConflictReason.CONTENT_OR_METADATA_DIFFERS,conflict.reason)
                assertEquals("Fork B",catalog(apiB.documents()).single().title)
            }
        }
        assertEquals("Fork A",catalog(api.documents()).single().title)
    }
    @Test fun realFtsAndCitationsSurviveEncryptedBackupAndIsolatedRestore()=fixture{root,db,api,crypto->
        val collection=catalog(api.createCollection("indexed synthetic"))
        val parser=fr.jarvis.nexus.importing.ParserPort{_,bytes,_->listOf(fr.jarvis.nexus.importing.ExtractedPage(1,bytes.toString(Charsets.UTF_8)))}
        val coordinator=fr.jarvis.nexus.importing.ImportCoordinator(db,api,fr.jarvis.nexus.importing.ParserRegistry(mapOf("txt" to parser)))
        val job=catalog(coordinator.enqueue(fr.jarvis.nexus.importing.ImportSource(collection.id,"opaque:indexed","fixture.txt","Indexed"),"Science evidence".byteInputStream()))
        catalog(coordinator.process(job.id,"Science evidence".byteInputStream()))
        val scope=fr.jarvis.nexus.rag.SearchScope(setOf(collection.id))
        val rag=fr.jarvis.nexus.rag.LocalRagService(db,api){scope}
        val original=catalog(rag.retrieve(fr.jarvis.nexus.rag.KnowledgeQuery("science"),scope))
        val receipt=ok(SnapshotExporter(db,api,crypto).export(root.resolve("indexed.jnb"),true))
        ok(SnapshotImporter(db,api,crypto).prepare(receipt.archive,root,true)).use{copy->
            NexusDatabase.open(copy.databaseFile).use{restored->
                val restoredApi=KnowledgeCatalogService(restored,AccessPolicy{true})
                val restoredRag=fr.jarvis.nexus.rag.LocalRagService(restored,restoredApi){scope}
                assertEquals(original,catalog(restoredRag.retrieve(fr.jarvis.nexus.rag.KnowledgeQuery("science"),scope)))
                assertEquals("Science evidence",catalog(restoredRag.resolve(original.items.single().citations.single(),scope)))
            }
        }
    }
    @Test fun publicationUsesCapturedCanonicalParentDespiteAliasSwap()=fixture{root,db,api,crypto->
        val first=Files.createDirectory(root.resolve("first"));val second=Files.createDirectory(root.resolve("second"))
        val alias=Files.createSymbolicLink(root.resolve("alias"),first)
        val racing=object:CryptoPort{
            override fun encrypt(input:java.io.InputStream,output:java.io.OutputStream,maxInputBytes:Long){crypto.encrypt(input,output,maxInputBytes);Files.delete(alias);Files.createSymbolicLink(alias,second)}
            override fun decrypt(input:java.io.InputStream,output:java.io.OutputStream,maxInputBytes:Long)=crypto.decrypt(input,output,maxInputBytes)
        }
        val receipt=ok(SnapshotExporter(db,api,racing).export(alias.resolve("backup.jnb"),true))
        assertEquals(first.resolve("backup.jnb"),receipt.archive);assertTrue(Files.exists(receipt.archive));assertFalse(Files.exists(second.resolve("backup.jnb")))
    }
    @Test fun expectedDigestCoversActualDecryptionInputNotEarlierPathContents()=fixture{root,db,api,crypto->
        val a=ok(SnapshotExporter(db,api,crypto).export(root.resolve("a.jnb"),true))
        val doc=catalog(api.documents()).single();catalog(api.rename(doc.id,doc.version,"Different archive"))
        val b=ok(SnapshotExporter(db,api,crypto).export(root.resolve("b.jnb"),true))
        val replacement=Files.readAllBytes(b.archive)
        val racing=object:CryptoPort{
            override fun encrypt(input:java.io.InputStream,output:java.io.OutputStream,maxInputBytes:Long)=crypto.encrypt(input,output,maxInputBytes)
            override fun decrypt(input:java.io.InputStream,output:java.io.OutputStream,maxInputBytes:Long){Files.write(a.archive,replacement);crypto.decrypt(input,output,maxInputBytes)}
        }
        assertEquals(BackupResult.Rejected(BackupError.INVALID_ARCHIVE),SnapshotImporter(db,api,racing).prepare(a.archive,root,true,a.archiveSha256))
        assertEquals("Different archive",catalog(api.documents()).single().title)
    }
    @Test fun changedWorkspaceIdentityCannotDeleteReplacementDirectory()=fixture{root,db,api,crypto->
        var replacement:Path?=null
        val racing=object:CryptoPort{
            override fun encrypt(input:java.io.InputStream,output:java.io.OutputStream,maxInputBytes:Long){
                crypto.encrypt(input,output,maxInputBytes)
                val path=Files.list(root).use{it.filter{p->p.fileName.toString().startsWith("nexus-recovery-")}.findFirst().get()}
                Files.move(path,root.resolve("displaced"));Files.createDirectory(path);Files.writeString(path.resolve("foreign"),"preserve");replacement=path
            }
            override fun decrypt(input:java.io.InputStream,output:java.io.OutputStream,maxInputBytes:Long)=crypto.decrypt(input,output,maxInputBytes)
        }
        assertEquals(BackupResult.Rejected(BackupError.WORKSPACE_CHANGED),SnapshotExporter(db,api,racing).export(root.resolve("backup.jnb"),true))
        assertEquals("preserve",Files.readString(replacement!!.resolve("foreign")));assertFalse(Files.exists(root.resolve("backup.jnb")))
    }
    @Test fun authenticatedManyEntryZipRefusedBeforeExtraction()=fixture{root,db,api,crypto->
        val zip=java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(zip).use{out->repeat(1000){out.putNextEntry(java.util.zip.ZipEntry("entry-$it"));out.closeEntry()}}
        val archive=root.resolve("many.jnb");Files.newOutputStream(archive).use{crypto.encrypt(zip.toByteArray().inputStream(),it,1024*1024)}
        assertEquals(BackupResult.Rejected(BackupError.INVALID_ARCHIVE),SnapshotImporter(db,api,crypto).prepare(archive,root,true))
    }
    @Test fun manifestRejectsUnknownVersionAndAmbiguousFields(){
        val valid=SnapshotManifest(3,4096,"0".repeat(64)).toJson()
        assertEquals(SnapshotManifest(3,4096,"0".repeat(64)),SnapshotManifest.parse(valid))
        for(value in listOf(valid.replace("schemaVersion\":3","schemaVersion\":999"),valid.replace("\"engine\":", "\"extra\":1,\"engine\":"))){
            try{SnapshotManifest.parse(value);fail("must refuse")}catch(_:BackupFailure){}
        }
    }
    @Test fun quotaRefusesBeforeCreatingArchive()=fixture{root,db,api,crypto->
        val limits=SnapshotLimits(maxDatabaseBytes=4096,maxArchiveBytes=8192)
        assertEquals(BackupResult.Rejected(BackupError.LIMIT_EXCEEDED),SnapshotExporter(db,api,crypto,limits).export(root.resolve("tiny.jnb"),true))
        assertFalse(Files.exists(root.resolve("tiny.jnb")))
    }
}
