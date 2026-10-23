package fr.jarvis.nexus.store

import java.io.InputStream
import java.security.MessageDigest

class SourceTooLarge : RuntimeException()
data class Fingerprint(val sha256: String, val bytes: Long)
object SourceFingerprint {
    /** Does not close the caller-owned stream. Never buffers a full source. */
    fun compute(stream: InputStream, maxBytes: Long): Fingerprint {
        require(maxBytes >= 1)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var count = 0L
        while (true) {
            val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), maxBytes - count + 1).toInt())
            if (read < 0) break
            // Defensive fallback for nonconforming streams that return zero.
            if (read == 0) {
                val one = stream.read()
                if (one < 0) break
                count++
                if (count > maxBytes) throw SourceTooLarge()
                digest.update(one.toByte())
            } else {
                count += read
                if (count > maxBytes) throw SourceTooLarge()
                digest.update(buffer, 0, read)
            }
        }
        return Fingerprint(digest.digest().joinToString("") { "%02x".format(it) }, count)
    }
}
