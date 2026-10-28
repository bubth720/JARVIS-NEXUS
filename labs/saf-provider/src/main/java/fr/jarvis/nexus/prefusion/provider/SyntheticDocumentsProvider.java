package fr.jarvis.nexus.prefusion.provider;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.net.Uri;
import android.content.Intent;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Separate UID test fixture. Fixed public synthetic files only; never accepts a path or bytes. */
public final class SyntheticDocumentsProvider extends DocumentsProvider {
    private static final String[] ROOT = {DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
        DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.COLUMN_MIME_TYPES};
    private static final String[] DOC = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_SIZE};
    public boolean onCreate() { return true; }
    public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection == null ? ROOT : projection);
        MatrixCursor.RowBuilder row = cursor.newRow();
        put(row,cursor,DocumentsContract.Root.COLUMN_ROOT_ID,"lot3");
        put(row,cursor,DocumentsContract.Root.COLUMN_DOCUMENT_ID,"root");
        put(row,cursor,DocumentsContract.Root.COLUMN_TITLE,"NEXUS LOT3 FIXTURE");
        put(row,cursor,DocumentsContract.Root.COLUMN_FLAGS,DocumentsContract.Root.FLAG_LOCAL_ONLY);
        put(row,cursor,DocumentsContract.Root.COLUMN_MIME_TYPES,"text/plain"); return cursor;
    }
    private static void put(MatrixCursor.RowBuilder row, MatrixCursor cursor, String name, Object value) {
        if(cursor.getColumnIndex(name)>=0) row.add(name,value);
    }
    private void add(MatrixCursor cursor,String id) throws FileNotFoundException {
        if(!id.equals("root") && !id.equals("selected") && !id.equals("oversized") && !id.equals("revocable")) throw new FileNotFoundException();
        MatrixCursor.RowBuilder row=cursor.newRow();
        put(row,cursor,DocumentsContract.Document.COLUMN_DOCUMENT_ID,id);
        put(row,cursor,DocumentsContract.Document.COLUMN_DISPLAY_NAME,id.equals("root")?"LOT3 synthetic":id+".txt");
        put(row,cursor,DocumentsContract.Document.COLUMN_MIME_TYPE,id.equals("root")?DocumentsContract.Document.MIME_TYPE_DIR:"text/plain");
        put(row,cursor,DocumentsContract.Document.COLUMN_FLAGS,0);
        put(row,cursor,DocumentsContract.Document.COLUMN_SIZE,id.equals("root")?0:id.equals("oversized")?2097153:content().length);
    }
    public Cursor queryDocument(String id,String[] projection) throws FileNotFoundException {
        MatrixCursor cursor=new MatrixCursor(projection==null?DOC:projection);add(cursor,id);return cursor;
    }
    public Cursor queryChildDocuments(String parent,String[] projection,String sortOrder) throws FileNotFoundException {
        if(!parent.equals("root")) throw new FileNotFoundException();
        MatrixCursor cursor=new MatrixCursor(projection==null?DOC:projection);
        for(String id:new String[]{"selected","oversized","revocable"}) add(cursor,id);return cursor;
    }
    public boolean isChildDocument(String parent,String id) {return parent.equals("root") && (id.equals("selected")||id.equals("oversized")||id.equals("revocable"));}
    private static byte[] content(){return "Un phare bleu protège le port. Source synthétique LOT3.".getBytes(StandardCharsets.UTF_8);}
    public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal stop) throws FileNotFoundException {
        if(!mode.equals("r")) throw new SecurityException("Read only fixture");
        if(!id.equals("selected")&&!id.equals("oversized")&&!id.equals("revocable")) throw new FileNotFoundException();
        File file=new File(getContext().getFilesDir(),"lot3-"+id+".txt");
        synchronized(this) {
            if(!file.exists()) try(OutputStream out=new FileOutputStream(file)) {
                if(id.equals("oversized")) {byte[] block=new byte[8192];int remaining=2097153;while(remaining>0){int n=Math.min(remaining,block.length);out.write(block,0,n);remaining-=n;}}
                else out.write(content());
            } catch(IOException e){throw new FileNotFoundException("Fixture creation failed");}
        }
        ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
        if(id.equals("revocable")) {
            Uri uri=DocumentsContract.buildDocumentUri("fr.jarvis.nexus.prefusion.provider.documents",id);
            getContext().revokeUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        return descriptor;
    }
}
