package dev.rushi.apkdownloadhelper

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal class ApkComboParser(private val ctx: SourceParserContext) : ApkSourceParser {
    override val source: DownloadSource = DownloadSource.APK_COMBO

    override suspend fun findCandidates(
        request: HelperRequest,
        option: CandidateOption
    ): List<DownloadCandidate> = when (option) {
        CandidateOption.REQUESTED -> apkComboRequestedCandidates(request)
        CandidateOption.LATEST -> apkComboLatestCandidates(request)
        CandidateOption.MANUAL -> emptyList()
    }

    override fun searchUrl(packageName: String): String? = apkComboSearchUrl(packageName)

    override suspend fun resolveHistory(request: HelperRequest): List<DownloadCandidate> {
        val latestPageUrl = apkComboDownloadPageUrls(request).firstOrNull() ?: return emptyList()
        val oldVersionsUrls = listOf(
            apkComboOldVersionsUrl(request, latestPageUrl),
            "https://apkcombo.com/${request.appName.slugForUrl()}/${request.packageName}/old-versions/"
        ).distinct()

        for (oldVersionsUrl in oldVersionsUrls) {
            val doc: Document = try {
                fetchDocument(oldVersionsUrl)
            } catch (e: HttpRateLimitedException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            val items = doc.select("a.ver-item[href]")
            if (items.isEmpty()) continue

            return items
                .mapNotNull { item ->
                    val versionName = apkComboVersionFromText(item.text())
                        ?: item.text().trim().takeIf(String::isNotBlank)
                        ?: return@mapNotNull null
                    val pageUrl = item.absUrl("href")
                        .ifBlank { "https://apkcombo.com${item.attr("href")}" }
                        .normalizedHttpUrlOrNull()
                        ?: return@mapNotNull null
                    DownloadCandidate(
                        source = DownloadSource.APK_COMBO,
                        name = request.appName,
                        packageName = request.packageName,
                        versionName = versionName,
                        versionCode = null,
                        url = pageUrl,
                        fileKind = "web",
                        option = CandidateOption.LATEST,
                        directDownload = false,
                        versionStatus = request.versionStatus(versionName, null),
                        formatMatches = true,
                        note = null
                    )
                }
                .sortedWith { left, right -> compareVersionNames(right.versionName, left.versionName) }
        }

        return emptyList()
    }

    override suspend fun resolveHistoryCandidate(
        request: HelperRequest,
        candidate: DownloadCandidate
    ): DownloadCandidate? {
        val resolved = runCatching {
            apkComboCandidatesFromPage(
                request = request,
                pageUrl = candidate.url,
                option = CandidateOption.LATEST
            ).firstOrNull { it.directDownload }
        }.getOrNull()
            ?: return null

        return if (resolved.versionName == null && candidate.versionName != null) {
            resolved.copy(
                versionName = candidate.versionName,
                versionStatus = request.versionStatus(candidate.versionName, null)
            )
        } else {
            resolved
        }
    }

    private fun apkComboLatestCandidates(request: HelperRequest): List<DownloadCandidate> =
        apkComboDownloadPageUrls(request)
            .firstNotNullOfOrNull { pageUrl ->
                runCatching {
                    apkComboCandidatesFromPage(
                        request = request,
                        pageUrl = pageUrl,
                        option = CandidateOption.LATEST
                    ).takeIf(List<DownloadCandidate>::isNotEmpty)
                }.getOrNull()
            }
            .orEmpty()

    private fun apkComboRequestedCandidates(request: HelperRequest): List<DownloadCandidate> {
        val latestPageUrl = apkComboDownloadPageUrls(request).first()
        return request.requestedVersionNames.firstNotNullOfOrNull { requestedVersion ->
            runCatching {
                apkComboVersionedPageUrls(request, requestedVersion)
                    .firstNotNullOfOrNull { pageUrl ->
                        apkComboCandidatesFromPage(
                            request = request,
                            pageUrl = pageUrl,
                            option = CandidateOption.REQUESTED
                        ).filter(request::isRequestedMatch)
                            .takeIf(List<DownloadCandidate>::isNotEmpty)
                    }
                    ?: apkComboOldVersionPageUrl(request, latestPageUrl, requestedVersion)
                        ?.let { oldPageUrl ->
                            apkComboCandidatesFromPage(
                                request = request,
                                pageUrl = oldPageUrl,
                                option = CandidateOption.REQUESTED
                            ).filter(request::isRequestedMatch)
                                .takeIf(List<DownloadCandidate>::isNotEmpty)
                        }
            }.getOrNull()
        }.orEmpty()
    }

    private fun apkComboDownloadPageUrls(request: HelperRequest): List<String> {
        val defaultUrl = "https://apkcombo.com/${request.appName.slugForUrl()}/${request.packageName}/download/apk"
        val packageSearchUrl = "${apkComboSearchUrl(request.packageName)}/download/apk"
        val hintedUrls = request.sourceHintUrlsFor(DownloadSource.APK_COMBO).mapNotNull { hint ->
            val normalized = hint.substringBefore("?").substringBefore("#").trimEnd('/')
            when {
                normalized.contains("/download/", ignoreCase = true) -> normalized
                normalized.contains("/${request.packageName}", ignoreCase = true) -> "$normalized/download/apk"
                else -> null
            }
        }

        return (listOf(packageSearchUrl) + hintedUrls + defaultUrl).distinct()
    }

    private fun apkComboSearchUrl(packageName: String): String =
        "https://apkcombo.com/search/$packageName"

    private fun apkComboVersionedPageUrls(request: HelperRequest, versionName: String): List<String> {
        val suffixes = apkComboWantedSuffixes(request)
        val searchBase = "https://apkcombo.com/search/${request.packageName}/download"
        val defaultBase = "https://apkcombo.com/${request.appName.slugForUrl()}/${request.packageName}/download"
        val hintedBases = request.sourceHintUrlsFor(DownloadSource.APK_COMBO).mapNotNull { hint ->
            val normalized = hint.substringBefore("?").substringBefore("#").trimEnd('/')
            when {
                normalized.contains("/download/", ignoreCase = true) -> normalized.substringBeforeLast("/download")
                normalized.contains("/${request.packageName}", ignoreCase = true) -> normalized
                else -> null
            }?.trimEnd('/')?.let { "$it/download" }
        }

        return (listOf(searchBase) + hintedBases + defaultBase)
            .flatMap { base -> suffixes.map { suffix -> "$base/phone-$versionName-$suffix" } }
            .distinct()
    }

    private fun apkComboWantedSuffixes(request: HelperRequest): List<String> {
        val requestedKinds = request.requestedFileKinds
        return APK_COMBO_FILE_KIND_ORDER
            .filter { it in requestedKinds }
            .ifEmpty { APK_COMBO_FILE_KIND_ORDER }
    }

    private fun apkComboOldVersionPageUrl(
        request: HelperRequest,
        latestPageUrl: String,
        requestedVersion: String
    ): String? {
        val latestDoc = fetchDocument(latestPageUrl)
        val visibleOldVersion = latestDoc.select("a.ver-item[href]")
            .firstOrNull { item ->
                val itemVersion = apkComboVersionFromText(item.text()) ?: item.text()
                itemVersion.versionNameEquals(requestedVersion)
            }
            ?.absUrl("href")
        if (!visibleOldVersion.isNullOrBlank()) return visibleOldVersion

        val oldVersionsUrl = apkComboOldVersionsUrl(request, latestPageUrl)
        val oldVersionsDoc = fetchDocument(oldVersionsUrl)
        return oldVersionsDoc.select("a.ver-item[href]")
            .firstOrNull { item ->
                val itemVersion = apkComboVersionFromText(item.text()) ?: item.text()
                itemVersion.versionNameEquals(requestedVersion)
            }
            ?.absUrl("href")
    }

    private fun apkComboOldVersionsUrl(request: HelperRequest, latestPageUrl: String): String {
        val uri = runCatching { java.net.URI(latestPageUrl) }.getOrNull()
        val segments = uri?.path
            ?.trim('/')
            ?.split('/')
            .orEmpty()
        val packageIndex = segments.indexOf(request.packageName)
        if (uri != null && packageIndex >= 0) {
            val appPath = segments.take(packageIndex + 1).joinToString("/")
            return "${uri.scheme}://${uri.host}/$appPath/old-versions/"
        }

        return "https://apkcombo.com/${request.appName.slugForUrl()}/${request.packageName}/old-versions/"
    }

    private fun apkComboCandidatesFromPage(
        request: HelperRequest,
        pageUrl: String,
        option: CandidateOption
    ): List<DownloadCandidate> {
        val doc = fetchDocument(pageUrl)
        if (!apkComboPageMatchesPackage(doc, pageUrl, request.packageName)) return emptyList()

        val checkIn = fetchText("https://apkcombo.com/checkin", referer = pageUrl).trim()
        val versionName = apkComboVersion(doc, pageUrl) ?: request.versionName.takeIf { option == CandidateOption.REQUESTED }

        val allVariants = doc.select("a.variant[href]")
            .mapNotNull { variant ->
                apkComboCandidateFromVariant(
                    request = request,
                    pageUrl = pageUrl,
                    option = option,
                    versionName = versionName,
                    checkIn = checkIn,
                    variant = variant,
                    allowFormatMismatch = true
                )
            }
            .distinctBy(DownloadCandidate::identityKey)
        // Prefer variants that match the requested format; if the page only offers
        // another kind (e.g. APKS requested but only XAPK variants), offer those
        // anyway and let the UI flag the format mismatch, like other sources do.
        val matchingVariants = allVariants.filter { it.formatMatches }
        if (matchingVariants.isNotEmpty()) return matchingVariants
        if (allVariants.isNotEmpty()) return allVariants

        // No variant links in the HTML: APKCombo gates some downloads behind a
        // reCAPTCHA that the app cannot solve. Surface that honestly instead of
        // reporting the version as not found on the source.
        if (doc.apkComboIsCaptchaGated()) {
            return listOf(
                DownloadCandidate(
                    source = DownloadSource.APK_COMBO,
                    name = request.appName,
                    packageName = request.packageName,
                    versionName = versionName,
                    versionCode = null,
                    url = pageUrl,
                    fileKind = "web",
                    option = option,
                    directDownload = false,
                    versionStatus = request.versionStatus(versionName, null),
                    formatMatches = true,
                    note = "APKCombo is showing a captcha for this download. Open the link and download manually."
                )
            )
        }
        return emptyList()
    }

    private fun Document.apkComboIsCaptchaGated(): Boolean =
        // Matches both grecaptcha.execute(...) and the site's bare aptcha.execute(...).
        html().contains("aptcha.execute", ignoreCase = true)

    private fun apkComboCandidateFromVariant(
        request: HelperRequest,
        pageUrl: String,
        option: CandidateOption,
        versionName: String?,
        checkIn: String,
        variant: Element,
        allowFormatMismatch: Boolean = false
    ): DownloadCandidate? {
        val href = variant.absUrl("href").ifBlank { "https://apkcombo.com${variant.attr("href")}" }
        val downloadUrl = (if (checkIn.isNotBlank()) "$href&$checkIn" else href)
            .normalizedHttpUrlOrNull()
            ?: return null
        val versionCode = apkComboVersionCode(href)
        // Variant hrefs are obfuscated tokens that often carry no readable kind
        // marker (fileKindFromUrl then defaults to "apk" and rejects split-archive
        // requests). Trust the visible .vtype badge (e.g. "XAPK") when present.
        val fileKind = apkComboVariantFileKind(variant) ?: fileKindFromUrl(href)
        if (!request.acceptsFormat(fileKind) && !allowFormatMismatch) return null
        val variantLabel = apkComboVariantLabel(variant)
        val fileName = "${request.packageName}-${versionName ?: "latest"}-apkcombo${variantLabel.variantFileSuffix()}.$fileKind"
            .sanitizeFileName()

        return DownloadCandidate(
            source = DownloadSource.APK_COMBO,
            name = request.appName,
            packageName = request.packageName,
            versionName = versionName,
            versionCode = versionCode,
            url = downloadUrl,
            fileKind = fileKind,
            option = option,
            directDownload = true,
            versionStatus = request.versionStatus(versionName, versionCode),
            formatMatches = request.acceptsFormat(fileKind),
            variantLabel = variantLabel,
            files = listOf(
                CandidateDownloadFile(
                    url = downloadUrl,
                    fileName = fileName,
                    // No Referer: APKCombo's /d redirects to download.pureapk.com,
                    // which bounces any request carrying an apkcombo.com Referer to
                    // an apkpure.com/url HTML page instead of serving the file.
                    referer = null
                )
            )
        )
    }

    private fun apkComboVariantFileKind(variant: Element): String? {
        val text = variant.selectFirst(".vtype")
            ?.text()
            ?.lowercase(Locale.US)
            ?.takeIf(String::isNotBlank)
            ?: return null
        return when {
            "apks" in text -> "apks"
            "xapk" in text -> "xapk"
            "apkm" in text -> "apkm"
            "apk" in text -> "apk"
            else -> null
        }
    }

    private fun apkComboVariantLabel(variant: Element): String? =
        listOf(
            variant.attr("data-arch"),
            variant.attr("data-abi"),
            variant.attr("data-cpu"),
            variant.attr("title"),
            variant.text()
        )
            .firstOrNull { it.isNotBlank() }
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.archDisplayLabel()

    private fun apkComboPageMatchesPackage(doc: Document, pageUrl: String, packageName: String): Boolean {
        if (pageUrl.contains("/search/$packageName/", ignoreCase = true) ||
            pageUrl.contains("/$packageName/", ignoreCase = true)
        ) {
            return true
        }

        val canonicalPackage = doc.selectFirst("link[rel=canonical]")
            ?.absUrl("href")
            ?.let { url -> runCatching { java.net.URI(url).path.trim('/').split('/').lastOrNull() }.getOrNull() }
        return canonicalPackage == packageName || doc.html().contains(packageName)
    }

    private fun apkComboVersion(doc: Document, pageUrl: String): String? {
        Regex("""phone-(.+?)-(?:apk|xapk|apks|apkm)(?:[/?#]|$)""", RegexOption.IGNORE_CASE)
            .find(pageUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { return it }

        val html = doc.html()
        Regex("\"softwareVersion\"\\s*:\\s*\"([^\"]+)\"")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { return it }

        val description = doc.selectFirst("meta[name=description]")?.attr("content").orEmpty()
        Regex("""Version:\s*([^-]+?)\s*-""")
            .find(description)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { return it }

        Regex("""Version:\s*(.*?)(?=\s+-\s+[A-Za-z0-9_.]+)""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(Regex("<[^>]+>"), "")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { return it }

        Regex("""Version</[^>]+>\s*<[^>]+>([^<]+)""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { return it }

        Regex("""phone-([0-9][^-]+)-apk""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { return it }

        return doc.selectFirst("h1.title")
            ?.text()
            ?.let(::apkComboVersionFromText)
    }

    private fun apkComboVersionFromText(text: String): String? =
        Regex("""\b(\d+(?:[._-]\d+)+(?:[-.][A-Za-z0-9]+)?)\b""")
            .find(text)
            ?.value
            ?.replace("_", ".")

    private fun apkComboVersionCode(url: String): Long? {
        val decoded = URLDecoder.decode(url, StandardCharsets.UTF_8.name())
        Regex("""/(\d{1,12})[._-][A-Fa-f0-9]{8,}""")
            .find(decoded)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.let { return it }

        return decoded
            .split("/")
            .firstOrNull { it.all(Char::isDigit) && it.length >= 4 }
            ?.toLongOrNull()
    }

    private fun fetchDocument(url: String, referer: String? = null): Document =
        ctx.document(url, referer)

    private fun fetchText(url: String, referer: String? = null): String =
        ctx.text(url, referer)
}
