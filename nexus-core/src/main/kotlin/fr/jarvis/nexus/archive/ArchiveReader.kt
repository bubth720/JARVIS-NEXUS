package fr.jarvis.nexus.archive

/** Read-only future archive adapter. No ZIM decoder, extraction, scanning or whole-file copy implied. */
interface ArchiveReader : AutoCloseable {
    val format: String
    val sizeBytes: Long?
    /** Reads at most maxBytes into caller-owned data, offsets are 64-bit for giant archives. */
    fun readRange(offset: Long, maxBytes: Int): ByteArray
}
