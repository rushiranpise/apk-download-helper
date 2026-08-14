package dev.rushi.apkdownloadhelper

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.SequenceInputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

private const val MAX_DOWNLOAD_ATTEMPTS = 3
private const val DOWNLOAD_RETRY_BASE_MS = 2_000L
private const val HTTP_PARTIAL_CONTENT = 206

/**
 * Streaming download engine with partial-range resume and automatic retries.
 *
 * Downloads are staged into `target` (usually a `.part` file): if the target
 * already holds bytes, the transfer resumes from that offset with a Range
 * request when the server supports it. Transient failures (network I/O,
 * HTTP 408/429/5xx) retry up to [MAX_DOWNLOAD_ATTEMPTS] times with
 * exponential backoff, reporting each retry through [onRetry].
 */
internal class ApkDownloader(
    private val client: OkHttpClient,
    private val apkPureClient: OkHttpClient,
    private val onRetry: (attempt: Int, delayMs: Long) -> Unit = { _, _ -> }
) {
    private var activeCall: Call? = null

    fun cancelCurrent() {
        activeCall?.cancel()
    }

    suspend fun downloadToFile(
        download: CandidateDownloadFile,
        target: File,
        onProgress: (copied: Long, total: Long) -> Unit
    ) {
        var attempt = 0
        while (true) {
            try {
                performDownloadAttempt(download, target, onProgress)
                return
            } catch (error: Throwable) {
                if (error is CancellationException || error.message == "Canceled") throw error
                if (attempt >= MAX_DOWNLOAD_ATTEMPTS - 1 || !isTransientDownloadError(error)) throw error
                attempt++
                val delayMs = DOWNLOAD_RETRY_BASE_MS * (1L shl (attempt - 1))
                onRetry(attempt, delayMs)
                delay(delayMs)
            }
        }
    }

    private fun performDownloadAttempt(
        download: CandidateDownloadFile,
        target: File,
        onProgress: (copied: Long, total: Long) -> Unit
    ) {
        val url = download.url.normalizedHttpUrlOrNull()
            ?: error("Source returned an invalid download URL.".withManualModeHint())
        val partial = if (target.exists()) target.length() else 0L
        val builder = Request.Builder().url(url)
        download.referer?.let { builder.header("Referer", it) }
        if (partial > 0L) builder.header("Range", "bytes=$partial-")

        val call = downloadClientFor(url).newCall(builder.build())
        activeCall = call
        try {
            call.execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                val resumed = response.code == HTTP_PARTIAL_CONTENT && partial > 0L
                val total: Long = if (resumed) {
                    partial + response.body.contentLength().coerceAtLeast(0L)
                } else {
                    download.size ?: response.body.contentLength().coerceAtLeast(0L)
                }
                val contentType = response.body.contentType()?.toString()
                response.body.byteStream().use { input ->
                    val source = if (resumed) {
                        input
                    } else {
                        validateApkLikeStream(input, contentType)
                    }
                    FileOutputStream(target, resumed).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copied = if (resumed) partial else 0L
                        while (true) {
                            val read = source.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            onProgress(copied, total)
                        }
                    }
                }
            }
        } finally {
            if (activeCall === call) activeCall = null
        }
    }

    private fun downloadClientFor(url: String): OkHttpClient =
        if (url.contains("apkpure", ignoreCase = true)) apkPureClient else client

    private fun isTransientDownloadError(error: Throwable): Boolean {
        if (error is java.io.IOException) return true
        val message = error.message.orEmpty()
        return Regex("""HTTP (408|429|5\d\d)\b""").containsMatchIn(message)
    }
}

private fun validateApkLikeStream(
    input: java.io.InputStream,
    contentType: String?
): java.io.InputStream {
    val header = ByteArray(4)
    val headerSize = input.read(header)
    val isZip = headerSize >= 2 &&
        header[0] == 'P'.code.toByte() &&
        header[1] == 'K'.code.toByte()

    check(isZip) {
        val type = contentType?.let { " ($it)" }.orEmpty()
        "Source did not return a valid APK/APKS/XAPK$type."
    }

    return SequenceInputStream(ByteArrayInputStream(header, 0, headerSize), input)
}
