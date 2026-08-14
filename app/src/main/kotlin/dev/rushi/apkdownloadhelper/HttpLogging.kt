package dev.rushi.apkdownloadhelper

import android.util.Log
import java.util.Locale
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * OkHttp interceptor that mirrors each HTTP exchange to Logcat under [TAG]:
 * the outgoing method + URL, every redirect hop with its status and Location
 * header, and the final response status with elapsed time.
 *
 * Each line is prefixed with the source name inferred from the URL host
 * (e.g. [APKMirror], [APKPure]); [fallbackLabel] is used for hosts that do
 * not map to a known source, like opaque CDN domains.
 *
 * Works with `followRedirects(true)`: the application interceptor sees one
 * final response whose [Response.priorResponse] chain records each redirect,
 * so no network interceptor is needed to observe the hops.
 */
internal fun httpLoggingInterceptor(fallbackLabel: String = "HTTP"): Interceptor =
    Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        if (logcatLoggingEnabled) {
            logHttpExchange(request, response, fallbackLabel)
        }
        response
    }

private fun logHttpExchange(request: Request, response: Response, fallbackLabel: String) {
    Log.i(
        TAG,
        "HTTP > [${sourceLabel(request.url, fallbackLabel)}] ${request.method} ${request.url}"
    )
    val hops = buildList {
        var prior = response.priorResponse
        while (prior != null) {
            add(prior)
            prior = prior.priorResponse
        }
    }.asReversed()
    hops.forEach { hop ->
        val location = hop.header("Location")
        val target = if (location != null) " -> $location" else ""
        Log.i(
            TAG,
            "HTTP < [${sourceLabel(hop.request.url, fallbackLabel)}] ${hop.code} " +
                "${hop.request.url}$target${responseDetails(hop)}"
        )
    }
    val elapsedMs = response.receivedResponseAtMillis - response.sentRequestAtMillis
    Log.i(
        TAG,
        "HTTP < [${sourceLabel(response.request.url, fallbackLabel)}] ${response.code} " +
            "${response.request.url} (${elapsedMs}ms)${responseDetails(response)}"
    )
}

private fun responseDetails(response: Response): String {
    val parts = mutableListOf<String>()
    response.header("Content-Type")
        ?.takeIf(String::isNotBlank)
        ?.let(parts::add)
    val length = response.body?.contentLength() ?: -1L
    if (length > 0L) parts.add("$length bytes")
    return if (parts.isEmpty()) "" else parts.joinToString(", ", prefix = " (", postfix = ")")
}

private fun sourceLabel(url: HttpUrl, fallback: String): String {
    val host = url.host.lowercase(Locale.US)
    return when {
        "apkmirror" in host -> "APKMirror"
        "uptodown" in host -> "Uptodown"
        "pureapk" in host || "apkpure" in host -> "APKPure"
        "apkcombo" in host -> "APKCombo"
        "aptoide" in host -> "Aptoide"
        "auroraoss" in host -> "Aurora"
        "android.clients.google.com" in host -> "Play"
        "googleapis" in host || host == "play.google.com" || host.endsWith(".google.com") -> "Google"
        else -> fallback
    }
}
