package fr.jarvis.nexus.backup

import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class BackupError { DENIED, KEY_UNAVAILABLE, AUTHENTICATION_FAILED, INVALID_ARCHIVE, INCOMPATIBLE_VERSION, LIMIT_EXCEEDED, CONFLICT, WORKSPACE_CHANGED, STORAGE_FAILURE, UNSUPPORTED_PLATFORM }
class BackupFailure(val error: BackupError) : RuntimeException()
sealed interface BackupResult<out T> {
    data class Ok<T>(val value:T):BackupResult<T>
    data class Rejected(val error:BackupError):BackupResult<Nothing>
}
/** Keys belong to the host/platform, not the archive. Caller owns the streams. */
interface CryptoPort {
    fun encrypt(input:InputStream,output:OutputStream,maxInputBytes:Long)
    fun decrypt(input:InputStream,output:OutputStream,maxInputBytes:Long)
}
class AesGcmCryptoPort(private val key:()->SecretKey?):CryptoPort {
    private val random=SecureRandom()
    private val magic="JARVIS-NEXUS-01\n".toByteArray(Charsets.US_ASCII)
    override fun encrypt(input:InputStream,output:OutputStream,maxInputBytes:Long) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        val supplied=key() ?: throw BackupFailure(BackupError.KEY_UNAVAILABLE)
        val nonce=ByteArray(12).also(random::nextBytes)
        cipher.init(Cipher.ENCRYPT_MODE,supplied,GCMParameterSpec(128,nonce));cipher.updateAAD(magic)
        output.write(magic);output.write(nonce)
        transform(input,output,cipher,maxInputBytes)
    }
    override fun decrypt(input:InputStream,output:OutputStream,maxInputBytes:Long) {
        val supplied=key() ?: throw BackupFailure(BackupError.KEY_UNAVAILABLE)
        if (!input.readNBytes(magic.size).contentEquals(magic)) throw BackupFailure(BackupError.INVALID_ARCHIVE)
        val nonce=input.readNBytes(12);if (nonce.size!=12) throw BackupFailure(BackupError.INVALID_ARCHIVE)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,supplied,GCMParameterSpec(128,nonce));cipher.updateAAD(magic)
        try { transform(input,output,cipher,maxInputBytes,16) }
        catch (_:AEADBadTagException) { throw BackupFailure(BackupError.AUTHENTICATION_FAILED) }
    }
    private fun transform(input:InputStream,output:OutputStream,cipher:Cipher,max:Long,minBytes:Long=0) {
        require(max>=1);val buffer=ByteArray(8192);var size=0L
        while (true) {
            val read=input.read(buffer,0,minOf(buffer.size.toLong(),max-size+1).toInt())
            if (read<0) break
            if (read==0) { val value=input.read();if (value<0) break;size++;if(size>max)throw BackupFailure(BackupError.LIMIT_EXCEEDED);cipher.update(byteArrayOf(value.toByte()))?.let(output::write) }
            else { size+=read;if(size>max)throw BackupFailure(BackupError.LIMIT_EXCEEDED);cipher.update(buffer,0,read)?.let(output::write) }
        }
        if(size<minBytes)throw BackupFailure(BackupError.INVALID_ARCHIVE)
        // Authentication must finish before decrypted bytes are parsed or returned to a consumer.
        output.write(cipher.doFinal());output.flush()
    }
}
