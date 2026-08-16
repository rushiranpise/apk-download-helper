package dev.rushi.apkdownloadhelper

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Evozi's APK Downloader (apps.evozi.com) hands off to APKCube
 * (apkcube.com), which resolves apps and per-version download pages without
 * any bot wall. The actual APK file is gated behind a Cloudflare Turnstile
 * captcha, so this parser resolves the app and version and hands the user an
 * "Open link" candidate to the exact download page.
 */
internal class EvoziParser(
    private val ctx: SourceParserContext
) : ApkSourceParser {

    override val source = DownloadSource.EVOZI

    override fun searchUrl(packageName: String): String? =
        "https://apkcube.com/apk-downloader?url=$packageName"

    override suspend fun findCandidates(
        request: HelperRequest,
        option: CandidateOption
    ): List<DownloadCandidate> = when (option) {
        CandidateOption.LATEST -> latestCandidates(request)
        CandidateOption.REQUESTED -> requestedCandidates(request)
        CandidateOption.MANUAL -> emptyList()
    }

    private fun latestCandidates(request: HelperRequest): List<DownloadCandidate> {
        val pageUrl = searchUrl(request.packageName) ?: return emptyList()
        val html = try {
            ctx.text(pageUrl)
        } catch (e: HttpRateLimitedException) {
            throw e
        } catch (e: Exception) {
            throw SourceAppNotFoundException(request.packageName)
        }
        val flight = html.unescapeFlightPayload()
        val versionName = flight.apkCubePageVersion() ?: return emptyList()
        val downloadHref = flight.apkCubeDownloadHref(request.packageName) ?: return emptyList()
        val downloadUrl = "https://apkcube.com$downloadHref"
            .normalizedHttpUrlOrNull()
            ?: return emptyList()
        return listOf(
            DownloadCandidate(
                source = source,
                name = request.appName,
                packageName = request.packageName,
                versionName = versionName,
                versionCode = null,
                url = downloadUrl,
                fileKind = "web",
                option = CandidateOption.LATEST,
                directDownload = false,
                versionStatus = request.versionStatus(versionName, null),
                formatMatches = true,
                note = EVOZI_CAPTCHA_NOTE,
                captchaUrl = downloadUrl
            )
        )
    }

    private fun requestedCandidates(request: HelperRequest): List<DownloadCandidate> {
        val requestedName = request.requestedVersionName ?: return emptyList()
        // The old-versions page lives under the app's slug path, which only the
        // app page knows  fetch it first to learn the slug.
        val pageUrl = searchUrl(request.packageName) ?: return emptyList()
        val html = try {
            ctx.text(pageUrl)
        } catch (e: HttpRateLimitedException) {
            throw e
        } catch (e: Exception) {
            throw SourceAppNotFoundException(request.packageName)
        }
        val downloadHref = html.unescapeFlightPayload().apkCubeDownloadHref(request.packageName)
            ?: return emptyList()
        val slug = downloadHref
            .trim('/')
            .substringBefore('/')
            .takeIf(String::isNotBlank)
            ?: return emptyList()

        val versionsUrl = "https://apkcube.com/$slug/${request.packageName}/old-versions"
        val versionsHtml = try {
            ctx.text(versionsUrl)
        } catch (e: HttpRateLimitedException) {
            throw e
        } catch (e: Exception) {
            return emptyList()
        }
        val match = versionsHtml.unescapeFlightPayload().apkCubeVersionNames()
            .firstOrNull { it.versionNameEquals(requestedName) }
            ?: return emptyList()

        val encodedVersion = URLEncoder.encode(match, StandardCharsets.UTF_8.name())
        val downloadUrl = "https://apkcube.com/$slug/${request.packageName}/download?version=$encodedVersion"
            .normalizedHttpUrlOrNull()
            ?: return emptyList()
        return listOf(
            DownloadCandidate(
                source = source,
                name = request.appName,
                packageName = request.packageName,
                versionName = match,
                versionCode = null,
                url = downloadUrl,
                fileKind = "web",
                option = CandidateOption.REQUESTED,
                directDownload = false,
                versionStatus = VersionStatus.REQUESTED,
                formatMatches = true,
                note = EVOZI_CAPTCHA_NOTE,
                captchaUrl = downloadUrl
            )
        )
    }
}

private const val EVOZI_CAPTCHA_NOTE =
    "APKCube (via Evozi) gates downloads behind a Cloudflare captcha. Solve it in the in-app browser or open the page manually."

/**
 * The Next.js flight payload JSON-escapes its content (`\"` for quotes), so
 * the page HTML contains literal backslash-quote pairs inside the script
 * payload. Collapse `\"` back to `"` so the fields can be matched as plain
 * JSON. The backslash is built from its char code to keep the source free of
 * fragile escape sequences.
 */
private fun String.unescapeFlightPayload(): String {
    val backslash = 92.toChar()
    if (indexOf(backslash) < 0) return this
    val sb = StringBuilder(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c == backslash && i + 1 < length && this[i + 1] == '"') {
            sb.append('"')
            i += 2
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

/**
 * The app's download-page href inside the flight payload, e.g.
 * `href":"/battery-guru-battery-health/com.paget96.batteryguru/download"`.
 */
private fun String.apkCubeDownloadHref(packageName: String): String? =
    Regex("""href":"(/[a-z0-9-]+/${Regex.escape(packageName)}/download[^"]*)"""")
        .find(this)
        ?.groupValues
        ?.getOrNull(1)

/**
 * The version shown next to the app name on the APKCube downloader page, e.g.
 * `children":["2.5.0.6"," · ","","Paget96"]`. The page has other
 * `children` arrays earlier (nav labels like "apk-downloader"), so prefer a
 * token that looks like a version (starts with a digit) before falling back to
 * the first non-blank match.
 */
private fun String.apkCubePageVersion(): String? {
    val versionLike = Regex("""children":\["(\d[^"]{1,40})"""")
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
    return versionLike ?: Regex("""children":\["([^"]{1,40})"""")
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
        ?.takeIf(String::isNotBlank)
}

/** Version names listed on the old-versions page, from `versionName":"X"` fields. */
private fun String.apkCubeVersionNames(): List<String> =
    Regex("""versionName":"([^"]+)"""")
        .findAll(this)
        .mapNotNull { it.groupValues.getOrNull(1)?.takeIf(String::isNotBlank) }
        .toList()
