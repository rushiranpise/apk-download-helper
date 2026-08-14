package dev.rushi.apkdownloadhelper

import android.content.Context
import dev.rushi.apkdownloadhelper.play.PlayHttpClient
import java.util.Locale
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** Abstraction over network text fetching so parsers can be unit-tested with fixtures. */
internal fun interface SourceTextFetcher {
    fun fetchText(url: String, referer: String?): String
}

internal class OkHttpSourceTextFetcher(
    private val client: OkHttpClient
) : SourceTextFetcher {
    override fun fetchText(url: String, referer: String?): String {
        val builder = Request.Builder().url(url)
        referer?.let { builder.header("Referer", it) }
        client.newCall(builder.build()).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            return response.body.string()
        }
    }
}

/**
 * Everything a per-source parser needs. `apkPureApi`/`aptoideApi` are non-null
 * so parsers can call them directly; `playHttpClient`/`appContext` are only
 * needed by the Aurora parser and are nullable for fixture-based tests.
 */
internal class SourceParserContext(
    val fetcher: SourceTextFetcher,
    val apkPureApi: ApkPureApi,
    val aptoideApi: AptoideApi,
    val playHttpClient: PlayHttpClient? = null,
    val appContext: Context? = null
) {
    fun document(url: String, referer: String? = null): Document =
        Jsoup.parse(fetcher.fetchText(url, referer), url)

    fun text(url: String, referer: String? = null): String = fetcher.fetchText(url, referer)
}

internal fun String.archDisplayLabel(): String =
    if (isUniversalArchLabel()) {
        "Universal"
    } else {
        trim()
    }

internal fun String.isUniversalArchLabel(): Boolean {
    val normalizedLabel = lowercase(Locale.US)
    return "all architectures" in normalizedLabel ||
        "universal" in normalizedLabel ||
        normalizedLabel == "all" ||
        normalizedLabel == "noarch"
}

/**
 * Thrown by a source parser when the app is genuinely absent from the source
 * (e.g. the search page returns no matching listing), as opposed to a transient
 * resolution failure. Callers render this as "not found" instead of an error
 * with a fallback candidate.
 */
internal class SourceAppNotFoundException(packageName: String) :
    Exception("No listing found on source for $packageName")

/** Common contract every APK source parser implements. */
internal interface ApkSourceParser {
    val source: DownloadSource

    suspend fun findCandidates(
        request: HelperRequest,
        option: CandidateOption
    ): List<DownloadCandidate>

    /** Web search/info URL for manual mode; null when the source has no web page. */
    fun searchUrl(packageName: String): String? = null

    /** Web-page fallback shown when no direct candidate resolves for the requested version. */
    fun requestedFallbackCandidate(request: HelperRequest): DownloadCandidate? = null

    /** Web-page fallback shown when no direct candidate resolves for the latest version. */
    fun latestFallbackCandidate(request: HelperRequest): DownloadCandidate? = null

    suspend fun resolveHistory(request: HelperRequest): List<DownloadCandidate> = emptyList()

    suspend fun resolveHistoryCandidate(
        request: HelperRequest,
        candidate: DownloadCandidate
    ): DownloadCandidate? = null
}
