package fr.jarvis.nexus.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/** TEST APK ONLY. Fixed public synthetic fixtures; no incoming path/file selection,
 * personal data, write mode, persisted grants or DocumentsUI qualification.
 */
class SyntheticDocumentProvider : ContentProvider() {
    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r" || uri.path == "/denied") throw SecurityException("Read-only fixture refusal")
        val bytes = when(uri.path) {
            "/selected" -> "Un phare bleu protège le port.".toByteArray(Charsets.UTF_8)
            "/changed" -> "Une autre version du document.".toByteArray(Charsets.UTF_8)
            "/oversized" -> ByteArray(2 * 1024 * 1024 + 1) { 65 }
            else -> throw java.io.FileNotFoundException("Unknown synthetic fixture")
        }
        val file=File(context!!.cacheDir,"fusion02-${uri.lastPathSegment}.txt")
        synchronized(this) { file.writeBytes(bytes) }
        return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri) = "text/plain"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw SecurityException("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw SecurityException("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw SecurityException("Read only")
}
