package fr.jarvis.nexus.android

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.security.MessageDigest

/** Host observations only; cannot promote an Android/device gate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24,25,26,29,35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Fts5AdmissionTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun diagnosticReportsRealFailedPrimitiveAndDoesNotClaimRag() {
        val report=AndroidFts5Probe.inspect()
        assertEquals(Fts5Status.UNSUPPORTED,report.status)
        assertEquals(Fts5Stage.CREATE,report.lastStage)
        assertFalse(report.sqliteVersion.isNullOrEmpty())
        assertTrue(temp.root.listFiles()!!.isEmpty())
        println("HOST_ONLY FUSION02 $report")
    }
    @Test fun rejectionPreservesExistingDatabaseAndAllSidecarsByteForByte() {
        val file=File(temp.root,"nexus.db")
        SQLiteDatabase.openOrCreateDatabase(file,null).use {
            it.execSQL("CREATE TABLE sentinel(body TEXT)")
            it.execSQL("INSERT INTO sentinel VALUES('original')")
        }
        fun snapshot()=temp.root.listFiles()!!.associate { it.name to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
        val before=snapshot()
        repeat(2) { assertEquals(StorageOpenResult.Rejected(OpenFailure.CAPABILITY_UNAVAILABLE),AndroidNexusStorage.open(temp.root)) }
        assertEquals(before,snapshot())
    }
    @Test fun corruptDatabaseIsRefusedWithoutDefaultAndroidDeletion() {
        val file=File(temp.root,"nexus.db").also { it.writeBytes(ByteArray(1024) { 88 }) }
        val before=file.readBytes()
        assertEquals(StorageOpenResult.Rejected(OpenFailure.STORAGE_FAILURE),AndroidNexusStorage.open(temp.root))
        assertArrayEquals(before,file.readBytes())
        assertEquals(listOf("nexus.db"),temp.root.list()!!.toList())
    }

    @Test fun unavailableCapabilityDoesNotTouchAnActiveWalDatabase() {
        val file=File(temp.root,"nexus.db")
        SQLiteDatabase.openOrCreateDatabase(file,null).use { raw ->
            assertTrue(raw.enableWriteAheadLogging())
            raw.execSQL("CREATE TABLE sentinel(body TEXT)")
            raw.execSQL("INSERT INTO sentinel VALUES('original')")
            fun snapshot()=temp.root.listFiles()!!.associate { it.name to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
            val before=snapshot()
            assertTrue(before.keys.any { it.endsWith("-wal") })
            assertEquals(StorageOpenResult.Rejected(OpenFailure.CAPABILITY_UNAVAILABLE),AndroidNexusStorage.open(temp.root))
            assertEquals(before,snapshot())
        }
    }

}
