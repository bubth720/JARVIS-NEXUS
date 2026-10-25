package fr.jarvis.nexus.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.jarvis.nexus.api.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Compile-only until a separately authorized laboratory runs this APK. Never a physical GO from CI. */
@RunWith(AndroidJUnit4::class)
class AndroidNexusDeviceTest {
    @Test fun deviceCapabilityOrNativeCitationIsReportedTruthfully() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.cacheDir,"nex06a-instrumented-${UUID.randomUUID()}")
        check(directory.mkdir())
        try {
            when(val opened=AndroidNexusStorage.open(directory)) {
                is StorageOpenResult.Rejected -> {
                    assertEquals(OpenFailure.CAPABILITY_UNAVAILABLE,opened.reason)
                    println("NEX06A CAPABILITY_UNAVAILABLE: platform FTS5/vocab missing; no RAG success asserted")
                }
                is StorageOpenResult.Ready -> {
                    opened.storage.close()
                    val result=SyntheticNexusDemo.run(directory,true)
                    assertTrue(result.toString(),result is CatalogResult.Ok)
                    val evidence=(result as CatalogResult.Ok).value
                    assertTrue(evidence.reopenedText.contains("phare bleu"))
                    assertEquals("fixture://nex06a/synthetic.txt",evidence.citation.sourceUri)
                    println("NEX06A real platform SQLite citation and close/reopen passed")
                }
            }
        } finally { directory.deleteRecursively() } // only this test's fresh synthetic directory
    }
    @Test fun demoRequiresExplicitActivationBeforeAnyFileCreation() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.cacheDir,"nex06a-disabled-${UUID.randomUUID()}")
        assertEquals(CatalogResult.Rejected(KnowledgeError.DENIED),SyntheticNexusDemo.run(directory,false))
        assertFalse(directory.exists())
    }
}
