package fr.jarvis.nexus.prefusion.lab;

import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.*;
import androidx.lifecycle.Lifecycle;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import fr.jarvis.nexus.android.SelectedDocument;
import fr.jarvis.nexus.android.SelectedDocumentSourceVerifier;
import fr.jarvis.nexus.api.CatalogResult;
import fr.jarvis.nexus.api.KnowledgeError;
import fr.jarvis.nexus.api.SourceRecord;

/** Actual platform tests; their presence/build never proves they executed. Synthetic only. */
@RunWith(AndroidJUnit4.class)
public final class Lot3QualificationTest {
 @Test public void labIsOffByDefault() {
  try(ActivityScenario<SyntheticPickerActivity> scenario=ActivityScenario.launch(SyntheticPickerActivity.class)) {
   scenario.onActivity(a->{assertEquals("OFF",a.status);assertNull(a.selected);});
  }
 }
 @Test public void ftsPrimitivesReopenAndRollbackOrExplicitUnsupported() throws Exception {
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
  File f=new File(c.getCacheDir(),"synthetic-fts-lot3.db");SQLiteDatabase.deleteDatabase(f);
  try {
   try(SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(f,null)) {
    try {db.execSQL("CREATE VIRTUAL TABLE terms USING fts5(body)");}
    catch(android.database.sqlite.SQLiteException e) {
     if(!e.getMessage().contains("no such module: fts5"))throw e;
     android.util.Log.i("NEXUS_LOT3","UNSUPPORTED CREATE FTS5 API="+android.os.Build.VERSION.SDK_INT);return;
    }
    db.execSQL("INSERT INTO terms(rowid,body) VALUES(1,'phare bleu phare')");
    try(android.database.Cursor q=db.rawQuery("SELECT rowid,bm25(terms) FROM terms WHERE terms MATCH ?",new String[]{"phare"})) {
     assertTrue(q.moveToFirst());assertEquals(1,q.getInt(0));assertTrue(q.getDouble(1)<0);
    }
    db.execSQL("CREATE VIRTUAL TABLE vocab USING fts5vocab(terms,'instance')");
    try(android.database.Cursor q=db.rawQuery("SELECT offset FROM vocab WHERE term='phare' ORDER BY offset",null)) {
     assertTrue(q.moveToFirst());assertEquals(0,q.getInt(0));assertTrue(q.moveToNext());assertEquals(2,q.getInt(0));assertFalse(q.moveToNext());
    }
    db.setVersion(1);db.beginTransaction();
    try {db.execSQL("CREATE TABLE migration_marker(value TEXT)");db.setVersion(2);throw new IllegalStateException("Synthetic migration interruption");}
    catch(IllegalStateException expected) {assertEquals("Synthetic migration interruption",expected.getMessage());} finally {db.endTransaction();}
   }
   try(SQLiteDatabase db=SQLiteDatabase.openDatabase(f.getPath(),null,SQLiteDatabase.OPEN_READONLY)) {
    assertEquals(1,db.getVersion());
    try(android.database.Cursor q=db.rawQuery("SELECT name FROM sqlite_master WHERE name='migration_marker'",null)){assertFalse(q.moveToFirst());}
    try(android.database.Cursor q=db.rawQuery("SELECT body FROM terms WHERE rowid=1",null)){assertTrue(q.moveToFirst());assertEquals("phare bleu phare",q.getString(0));}
   }
   android.util.Log.i("NEXUS_LOT3","SUPPORTED SQL primitives/reopen/rollback API="+android.os.Build.VERSION.SDK_INT+"; NOT a RAG qualification");
  } finally {SQLiteDatabase.deleteDatabase(f);}
 }
 @Test public void actualPickerReadGrantAndOriginalPreservedWithoutPersistence() throws Exception {
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
  Intent i=new Intent(c,SyntheticPickerActivity.class).putExtra("prefusionSyntheticLab",true);
  final Uri[] selected={null};
  try(ActivityScenario<SyntheticPickerActivity> s=ActivityScenario.launch(i)) {
   choose(s,"selected.txt",selected);
   ContentResolver resolver=c.getContentResolver();
   byte[] one=read(resolver,selected[0]),two=read(resolver,selected[0]);assertArrayEquals(one,two);
   assertEquals("Un phare bleu protège le port. Source synthétique LOT3.",new String(one,StandardCharsets.UTF_8));
   assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(one),MessageDigest.getInstance("SHA-256").digest(two));
   SelectedDocument selection=SelectedDocument.Companion.fromUserSelection(selected[0],"selected.txt",true);
   SelectedDocumentSourceVerifier verifier=new SelectedDocumentSourceVerifier(resolver);
   assertEquals(new CatalogResult.Ok<kotlin.Unit>(kotlin.Unit.INSTANCE),verifier.verify(selection,new SourceRecord("synthetic",selected[0].toString(),sha(one),one.length)));
   assertEquals(new CatalogResult.Rejected(KnowledgeError.CONFLICT),verifier.verify(selection,new SourceRecord("synthetic",selected[0].toString(),sha(new byte[0]),one.length)));
   assertFalse(resolver.getPersistedUriPermissions().stream().anyMatch(p->p.getUri().equals(selected[0])));
   try {resolver.openFileDescriptor(selected[0],"w");fail("No write allowed");}catch(SecurityException|FileNotFoundException expected){}
  }
 }
 @Test public void selectedOversizedSourceRefusesBoundedRead() throws Exception {
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();Uri[] uri={null};
  try(ActivityScenario<SyntheticPickerActivity> s=ActivityScenario.launch(new Intent(c,SyntheticPickerActivity.class).putExtra("prefusionSyntheticLab",true))) {
   choose(s,"oversized.txt",uri);
   SelectedDocument selection=SelectedDocument.Companion.fromUserSelection(uri[0],"oversized.txt",true);
   assertEquals(new CatalogResult.Rejected(KnowledgeError.LIMIT_EXCEEDED),new SelectedDocumentSourceVerifier(c.getContentResolver()).verify(selection,new SourceRecord("synthetic",uri[0].toString(),sha(new byte[0]),2097153)));
   try{read(c.getContentResolver(),uri[0]);fail("2 MiB quota must refuse");}catch(IOException expected){assertEquals("limit",expected.getMessage());}
  }
 }
 @Test public void revokedSelectionCannotBeReopened() throws Exception {
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();Uri[] uri={null};
  try(ActivityScenario<SyntheticPickerActivity> s=ActivityScenario.launch(new Intent(c,SyntheticPickerActivity.class).putExtra("prefusionSyntheticLab",true))) {
   choose(s,"revocable.txt",uri);byte[] before=read(c.getContentResolver(),uri[0]);
   SelectedDocument selection=SelectedDocument.Companion.fromUserSelection(uri[0],"revocable.txt",true);
   assertEquals(new CatalogResult.Rejected(KnowledgeError.DENIED),new SelectedDocumentSourceVerifier(c.getContentResolver()).verify(selection,new SourceRecord("synthetic",uri[0].toString(),sha(before),before.length)));
   try{read(c.getContentResolver(),uri[0]);fail("Revoked grant must refuse second open");}catch(SecurityException expected){}
  }
 }
 private static void choose(ActivityScenario<SyntheticPickerActivity> s,String name,Uri[] selected) {
   UiDevice d=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
   UiObject2 button=d.wait(Until.findObject(By.text("CHOOSE SYNTHETIC DOCUMENT")),5000);assertNotNull(button);button.click();
   UiObject2 root=d.wait(Until.findObject(By.text("NEXUS LOT3 FIXTURE")),2000);
   if(root==null) {
    UiObject2 menu=d.findObject(By.descContains("Show roots"));if(menu!=null)menu.click();
    root=d.wait(Until.findObject(By.text("NEXUS LOT3 FIXTURE")),5000);
   }
   assertNotNull("Synthetic provider must be installed and selectable",root);root.click();
   UiObject2 doc=d.wait(Until.findObject(By.text(name)),5000);assertNotNull(doc);doc.click();
   // DocumentsUI runs in another process. UI idle alone does not establish that
   // the result callback reached our resumed Activity. Observe it, never assign it.
   long deadline=android.os.SystemClock.uptimeMillis()+5000;
   while(android.os.SystemClock.uptimeMillis()<deadline) {
    if(s.getState()==Lifecycle.State.RESUMED) {
     s.onActivity(a->{selected[0]=a.selected;});
     if(selected[0]!=null)break;
    }
    android.os.SystemClock.sleep(50);
   }
   s.onActivity(a->{assertEquals("SELECTED_READ_ONLY",a.status);selected[0]=a.selected;});assertNotNull(selected[0]);
 }
 private static String sha(byte[] bytes)throws Exception {
  StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
 }
 private static byte[] read(ContentResolver resolver,Uri uri)throws Exception {
  ParcelFileDescriptor p=resolver.openFileDescriptor(uri,"r");assertNotNull(p);
  try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(p);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
   byte[] buffer=new byte[1024];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>2097152)throw new IOException("limit");out.write(buffer,0,n);}return out.toByteArray();
  } finally {assertFalse(p.getFileDescriptor().valid());}
 }
}
