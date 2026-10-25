package fr.jarvis.nexus

import fr.jarvis.nexus.api.*
import fr.jarvis.nexus.backup.*
import fr.jarvis.nexus.rag.*
import fr.jarvis.nexus.store.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.sql.DriverManager
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.KeyGenerator

class FusionSnapshotMigrationTest {
    private fun <T> ok(value: CatalogResult<T>)=(value as CatalogResult.Ok).value
    @Test fun genuineV3SchemaEncryptedArchiveMigratesOnlyItsIsolatedCopyAndResolvesCitation() {
        val root=Files.createTempDirectory("fusion-v3-");val original=root.resolve("original-v3.db")
        val text="Un phare bleu protège le port.";val sourceHash=sha256(text)
        try {
            Class.forName("org.sqlite.JDBC")
            DriverManager.getConnection("jdbc:sqlite:"+original).use { c ->
                c.createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
                javaClass.getResourceAsStream("/schema-v3.sql")!!.bufferedReader().useLines { lines -> lines.filter { it.isNotBlank() }.forEach { sql -> c.createStatement().use { it.execute(sql) } } }
                c.execute("INSERT INTO collection VALUES('c','V3 fixture')")
                c.execute("INSERT INTO source VALUES('s','fixture:v3.txt',?,?)",sourceHash,text.toByteArray().size)
                c.execute("INSERT INTO document VALUES('d','c','fixture:v3.txt','V3',1,0,100)")
                c.execute("INSERT INTO revision VALUES('d',1,'s','V3')")
                c.execute("INSERT INTO fragment VALUES('d',1,0,?,0,?,1)",text,text.length)
                c.execute("INSERT INTO index_state VALUES('d',1,'READY')")
                c.execute("INSERT INTO fragment_fts(document_id,version,ordinal,body) VALUES('d',1,0,?)",text)
                assertEquals(3,c.query("PRAGMA user_version") { it.getInt(1) }.single())
            }
            val before=Files.readAllBytes(original)
            val digest=java.security.MessageDigest.getInstance("SHA-256").digest(before).joinToString("") { "%02x".format(it) }
            val manifest=SnapshotManifest(3,before.size.toLong(),digest)
            val zip=java.io.ByteArrayOutputStream()
            ZipOutputStream(zip).use { out ->
                out.putNextEntry(ZipEntry("manifest.json"));out.write(manifest.toJson().toByteArray());out.closeEntry()
                out.putNextEntry(ZipEntry("knowledge.sqlite"));out.write(before);out.closeEntry()
            }
            val key=KeyGenerator.getInstance("AES").also { it.init(256) }.generateKey()
            val crypto=AesGcmCryptoPort { key };val archive=root.resolve("old-snapshot.jnb")
            Files.newOutputStream(archive).use { crypto.encrypt(zip.toByteArray().inputStream(),it,1024*1024) }
            val archiveBytes=Files.readAllBytes(archive)
            NexusDatabase.open(root.resolve("current.db")).use { current ->
                val catalog=KnowledgeCatalogService(current,AccessPolicy { true });val currentBytes=Files.readAllBytes(root.resolve("current.db"))
                val result=SnapshotImporter(current,catalog,crypto).prepare(archive,root,true) as BackupResult.Ok
                val recovery=result.value;val copy=recovery.databaseFile
                recovery.use {
                    assertEquals(3,it.manifest.schemaVersion);assertEquals(4,it.preview.restoredSchemaVersion)
                    assertEquals(1,it.preview.documentCount);assertEquals(0,it.preview.graphNodes)
                    NexusDatabase.open(copy).use { reopened ->
                        val scope=SearchScope(setOf("c"));val rag=LocalRagService(reopened,KnowledgeCatalogService(reopened,AccessPolicy { true })) { scope }
                        val citation=ok(rag.retrieve(KnowledgeQuery("phare"),scope)).items.single().citations.single()
                        assertEquals(text,ok(rag.resolve(citation,scope)))
                        assertEquals("fixture:v3.txt",citation.sourceUri);assertEquals(sourceHash,citation.sourceSha256)
                        assertEquals(1,citation.page);assertEquals(0,citation.start);assertEquals(text.length,citation.end)
                    }
                }
                assertFalse(Files.exists(copy));assertArrayEquals(currentBytes,Files.readAllBytes(root.resolve("current.db")))
            }
            assertArrayEquals(before,Files.readAllBytes(original));assertArrayEquals(archiveBytes,Files.readAllBytes(archive))
        } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
}
