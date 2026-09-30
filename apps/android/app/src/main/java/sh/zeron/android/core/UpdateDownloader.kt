package sh.zeron.android.core

import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest

/**
 * Resumable APK download that walks a list of [UpdateSources.Source]s.
 *
 * A source is abandoned (and the next one tried, keeping the bytes already on
 * disk) when it fails to connect, stops sending for [readTimeoutMs], answers
 * with an error, or crawls below [slowMinBytes] per [slowWindowMs] while other
 * sources are still untried. The finished file must match the expected size
 * and SHA-256 when known; a mismatch discards it and moves on.
 *
 * No Android dependencies, so it runs in plain JVM tests against a local server.
 */
class UpdateDownloader(
    private val userAgent: String,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
    private val slowWindowMs: Long = 30_000,
    private val slowMinBytes: Long = 256L * 1024,
    private val attemptsPerSource: Int = 2,
    private val retryDelayMs: Long = 1_500,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    data class Progress(val done: Long, val total: Long, val source: String, val bytesPerSec: Long)

    enum class Kind { TIMEOUT, SLOW, HTTP, SIZE, DIGEST, DNS, TLS, IO }

    data class Failure(val source: String, val kind: Kind, val detail: String = "")

    class Failed(val failures: List<Failure>) : IOException(failures.joinToString("; ") { "${it.source}: ${it.kind} ${it.detail}".trim() })

    private class SourceError(val kind: Kind, val detail: String = "") : IOException("$kind $detail")

    /**
     * Downloads into [part] and returns the source that finished it. [part]
     * is left in place on failure so a later call resumes it.
     */
    fun download(
        sources: List<UpdateSources.Source>,
        part: File,
        expectedSize: Long,
        sha256: String?,
        progress: (Progress) -> Unit,
    ): UpdateSources.Source {
        val failures = mutableListOf<Failure>()
        sources.forEachIndexed { index, source ->
            val last = index == sources.lastIndex
            var attempt = 0
            while (true) {
                try {
                    fetch(source, part, expectedSize, allowSlowAbort = !last, progress)
                    verify(part, expectedSize, sha256)
                    return source
                } catch (e: SourceError) {
                    if (e.kind == Kind.DIGEST || e.kind == Kind.SIZE) part.delete()
                    // Timeouts, slowness and bad files: straight to the next source.
                    if (e.kind != Kind.IO || ++attempt >= attemptsPerSource) {
                        failures += Failure(source.label, e.kind, e.detail)
                        break
                    }
                } catch (e: SocketTimeoutException) {
                    failures += Failure(source.label, Kind.TIMEOUT)
                    break
                } catch (e: InterruptedIOException) {
                    failures += Failure(source.label, Kind.TIMEOUT)
                    break
                } catch (e: java.net.UnknownHostException) {
                    failures += Failure(source.label, Kind.DNS)
                    break
                } catch (e: javax.net.ssl.SSLException) {
                    failures += Failure(source.label, Kind.TLS, e.message.orEmpty())
                    break
                } catch (e: IOException) {
                    if (++attempt >= attemptsPerSource) {
                        failures += Failure(source.label, Kind.IO, e.message ?: e.javaClass.simpleName)
                        break
                    }
                }
                Thread.sleep(retryDelayMs)
            }
        }
        throw Failed(failures)
    }

    private fun verify(part: File, expectedSize: Long, sha256: String?) {
        if (expectedSize > 0 && part.length() != expectedSize) {
            throw SourceError(Kind.SIZE, "${part.length()} != $expectedSize")
        }
        if (sha256 != null) {
            val actual = sha256Of(part)
            if (!actual.equals(sha256, ignoreCase = true)) throw SourceError(Kind.DIGEST)
        }
    }

    private fun open(url: URL, token: String?): HttpURLConnection {
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("User-Agent", userAgent)
        if (token != null) {
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/octet-stream")
        }
        return conn
    }

    private fun fetch(
        source: UpdateSources.Source,
        part: File,
        total: Long,
        allowSlowAbort: Boolean,
        progress: (Progress) -> Unit,
    ) {
        var url = URL(source.url)
        var token = source.token?.takeIf { url.host.endsWith("github.com") }
        var hops = 0
        while (true) {
            val have = if (part.exists()) part.length() else 0L
            if (total > 0 && have >= total) return
            val conn = open(url, token)
            try {
                if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
                val code = conn.responseCode
                if (code in 300..399) {
                    val next = conn.getHeaderField("Location") ?: throw SourceError(Kind.HTTP, "$code without Location")
                    url = URL(url, next)
                    token = null // never send the token to the CDN
                    if (++hops > 6) throw SourceError(Kind.HTTP, "too many redirects")
                    continue
                }
                if (code == 416) {
                    // Nothing left to send for this offset: either complete, or a stale file.
                    if (total > 0 && have == total) return
                    part.delete()
                    continue
                }
                if (code !in 200..299) throw SourceError(Kind.HTTP, code.toString())
                val append = code == 206 && have > 0
                val base = if (append) have else 0L
                val length = conn.contentLengthLong.let { if (it > 0) it + base else total }
                // A proxy error page or a different file: leave the bytes on disk alone.
                if (total > 0 && length > 0 && length != total) throw SourceError(Kind.HTTP, "size $length != $total")
                copy(conn, part, append, base, length, source.label, allowSlowAbort, progress)
                return
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun copy(
        conn: HttpURLConnection,
        part: File,
        append: Boolean,
        base: Long,
        length: Long,
        label: String,
        allowSlowAbort: Boolean,
        progress: (Progress) -> Unit,
    ) {
        RandomAccessFile(part, "rw").use { out ->
            if (!append) out.setLength(0)
            out.seek(base)
            var written = base
            var windowStart = nowMs()
            var windowBytes = 0L
            var speedMark = windowStart
            var speedBytes = written
            var speed = 0L
            var lastReport = 0L
            val buf = ByteArray(64 * 1024)
            conn.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    written += n
                    windowBytes += n
                    val now = nowMs()
                    if (now - speedMark >= 1_000) {
                        speed = (written - speedBytes) * 1_000 / (now - speedMark)
                        speedMark = now
                        speedBytes = written
                    }
                    if (now - windowStart >= slowWindowMs) {
                        if (allowSlowAbort && windowBytes < slowMinBytes) {
                            throw SourceError(Kind.SLOW, "${windowBytes * 1_000 / (now - windowStart)}")
                        }
                        windowStart = now
                        windowBytes = 0
                    }
                    if (now - lastReport >= 200 || (length > 0 && written >= length)) {
                        lastReport = now
                        progress(Progress(written, length, label, speed))
                    }
                }
            }
            if (length > 0 && written < length) throw IOException("connection closed early")
        }
    }

    companion object {
        fun sha256Of(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
