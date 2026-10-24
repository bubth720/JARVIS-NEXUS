package fr.jarvis.nexus.android
import org.junit.Test
import org.junit.Assert.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import android.database.sqlite.SQLiteDatabase
import java.io.File
/** Legacy host SQLite without FTS5 proves the real probe refusal, not a mocked capability flag. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[24])
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class UnavailableFtsTest {
 @get:Rule val temp=TemporaryFolder()
 @Test fun unavailableFtsLeavesExistingStateUntouched() {
  val file=File(temp.root,"nexus.db")
  SQLiteDatabase.openOrCreateDatabase(file,null).use { it.execSQL("CREATE TABLE sentinel(value TEXT)");it.execSQL("INSERT INTO sentinel VALUES('original')") }
  assertEquals(StorageOpenResult.Rejected(OpenFailure.CAPABILITY_UNAVAILABLE),AndroidNexusStorage.open(temp.root))
  SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY).use { raw ->
   assertEquals(0,raw.version)
   raw.rawQuery("SELECT value FROM sentinel",null).use { assertTrue(it.moveToFirst());assertEquals("original",it.getString(0)) }
   raw.rawQuery("SELECT name FROM sqlite_master WHERE name='collection'",null).use { assertFalse(it.moveToFirst()) }
  }
 }
}
