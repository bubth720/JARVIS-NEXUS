package fr.jarvis.nexus.archive

import fr.jarvis.nexus.rag.*
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Linux mini-PC only. Host supplies trusted interpreter/worker/catalogue paths.
 * A fresh process bounds native lifetime. The host must additionally cap native
 * memory/CPU for hostile clusters; the article/cache limits are not an RSS cap.
 */
class ZimProcessReader(
    private val python: Path, private val worker: Path, private val catalog: Path,
    private val authorized: () -> Boolean = { false },
) : ArchiveReader {
    override fun article(selection: ArticleSelection): ArchiveArticle {
        if (!authorized()) throw ArchiveFailure(ArchiveError.DENIED)
        if (System.getProperty("java.runtime.name", "").contains("Android", true) ||
            System.getProperty("java.vm.name", "").contains("Dalvik", true) ||
            !System.getProperty("os.name", "").startsWith("Linux")) throw ArchiveFailure(ArchiveError.UNSUPPORTED)
        require(selection.archiveId.matches(Regex("[a-f0-9]{64}")))
        require(selection.path.length in 1..4096 && !selection.path.startsWith("/") &&
            selection.path.split('/').none { it == "." || it == ".." } &&
            selection.path.none { it < ' ' || it == '\\' })
        val process = try {
            ProcessBuilder(python.toString(), worker.toString(), "--catalog", catalog.toString(),
                "wire", selection.archiveId, selection.path).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (_: java.io.IOException) { throw ArchiveFailure(ArchiveError.UNSUPPORTED) }
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "nexus-zim-output").apply { isDaemon = true } }
        try {
            val future = executor.submit(Callable {
                process.inputStream.use { stream ->
                    val bytes = stream.readNBytes(2 * 1024 * 1024 + 1)
                    if (bytes.size > 2 * 1024 * 1024) throw ArchiveFailure(ArchiveError.LIMIT_EXCEEDED)
                    bytes.toString(Charsets.UTF_8).trimEnd('\n').split('\n')
                }
            })
            val lines = future.get(30, TimeUnit.SECONDS)
            if (!process.waitFor(1, TimeUnit.SECONDS)) throw ArchiveFailure(ArchiveError.STORAGE_FAILURE)
            if (lines.firstOrNull() != "OK") {
                val error = ArchiveError.entries.firstOrNull { it.name == lines.firstOrNull() } ?: ArchiveError.STORAGE_FAILURE
                throw ArchiveFailure(error)
            }
            if (process.exitValue() != 0 || lines.size != 7) throw ArchiveFailure(ArchiveError.CORRUPT)
            val fields = lines.drop(1).map { Base64.getDecoder().decode(it).toString(Charsets.UTF_8) }
            val result = ArchiveArticle(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5])
            if (result.path != selection.path || result.text.toByteArray().size > 1024 * 1024 ||
                sha256(result.text) != result.textSha256 || result.uri != "zim://${selection.archiveId}/" +
                encodedPath(selection.path)) {
                throw ArchiveFailure(ArchiveError.CORRUPT)
            }
            if (!authorized()) throw ArchiveFailure(ArchiveError.DENIED)
            return result
        } catch (e: ArchiveFailure) { throw e }
        catch (_: java.util.concurrent.TimeoutException) { throw ArchiveFailure(ArchiveError.LIMIT_EXCEEDED) }
        catch (_: Exception) { throw ArchiveFailure(ArchiveError.STORAGE_FAILURE) }
        finally { process.destroyForcibly(); executor.shutdownNow() }
    }
}


internal fun encodedPath(path: String) = java.net.URLEncoder.encode(path, "UTF-8").replace("+", "%20").replace("%7E", "~").replace("*", "%2A")
