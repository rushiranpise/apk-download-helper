package dev.rushi.apkdownloadhelper

import android.net.Uri
import android.util.Log
import java.net.URLEncoder
import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal class UptodownParser(private val ctx: SourceParserContext) : ApkSourceParser {
    override val source: DownloadSource = DownloadSource.UPTODOWN

    override suspend fun findCandidates(
        request: HelperRequest,
        option: CandidateOption
    ): List<DownloadCandidate> {
        return uptodownDetailUrls(request)
            .firstNotNullOfOrNull { detailUrl ->
                runCatching {
                    when (option) {
                        CandidateOption.REQUESTED -> uptodownRequestedCandidatesFromDetailUrl(request, detailUrl)
                        CandidateOption.LATEST -> uptodownCandidatesFromDetailUrl(request, detailUrl)
                        CandidateOption.MANUAL -> emptyList()
                    }
                }
                    .onFailure { Log.w(TAG, "Uptodown ${option.name.lowercase(Locale.US)} resolve failed", it) }
                    .getOrNull()
                    ?.takeIf(List<DownloadCandidate>::isNotEmpty)
            }
            .orEmpty()
    }

    override fun searchUrl(packageName: String): String? = uptodownSearchUrl(packageName)

    override suspend fun resolveHistory(request: HelperRequest): List<DownloadCandidate> {
        val detailUrl = uptodownDetailUrls(request).firstOrNull() ?: return emptyList()
        val normalizedDetailUrl = detailUrl.trimEnd('/')
        val downloadPageUrl = "$normalizedDetailUrl/download"
        val pageDoc = fetchDocument(downloadPageUrl)
        if (uptodownPackageName(pageDoc) != request.packageName) return emptyList()
        val dataCode = uptodownDataCode(pageDoc) ?: return emptyList()

        val candidates = buildList {
            for (page in 1..20) {
                val entries = runCatching {
                    gson.fromJson(
                        fetchText(
                            "$normalizedDetailUrl/apps/$dataCode/versions/$page",
                            referer = "$normalizedDetailUrl/versions"
                        ),
                        UptodownVersionResponse::class.java
                    ).data
                }.getOrDefault(emptyList())
                if (entries.isEmpty()) break

                entries.forEach { entry ->
                    val versionName = entry.version?.trim()?.takeIf(String::isNotBlank) ?: return@forEach
                    val versionPageUrl = uptodownVersionPageUrl(entry) ?: return@forEach
                    val fileKind = (entry.kindFile ?: entry.titleKindFile ?: "apk").lowercase(Locale.US)
                    add(
                        DownloadCandidate(
                            source = DownloadSource.UPTODOWN,
                            name = request.appName,
                            packageName = request.packageName,
                            versionName = versionName,
                            versionCode = null,
                            url = versionPageUrl,
                            fileKind = fileKind,
                            option = CandidateOption.LATEST,
                            directDownload = false,
                            versionStatus = request.versionStatus(versionName, null),
                            formatMatches = request.acceptsFormat(fileKind),
                            note = null
                        )
                    )
                }
            }
        }

        return candidates
            .distinctBy { it.versionName?.normalizedVersionName() }
            .sortedWith { left, right -> compareVersionNames(right.versionName, left.versionName) }
    }

    override suspend fun resolveHistoryCandidate(
        request: HelperRequest,
        candidate: DownloadCandidate
    ): DownloadCandidate? {
        val pageDoc = fetchDocument(candidate.url)
        val directUrl = uptodownDownloadUrlFromPage(pageDoc)
        if (directUrl == null) {
            return null
        }
        return candidate.copy(
            url = directUrl,
            directDownload = true,
            files = listOf(
                CandidateDownloadFile(
                    url = directUrl,
                    fileName = "${candidate.packageName}-${candidate.versionName ?: "latest"}-uptodown.${candidate.fileKind}"
                        .sanitizeFileName(),
                    referer = candidate.url
                )
            )
        )
    }

    private fun uptodownDetailUrls(request: HelperRequest): List<String> {
        val defaultUrl = "https://${request.appName.slugForUrl()}.en.uptodown.com/android"
        val hintedUrls = request.sourceHintUrlsFor(DownloadSource.UPTODOWN)
            .mapNotNull { hint ->
                val normalized = hint.substringBefore("?").substringBefore("#").trimEnd('/')
                when {
                    normalized.contains("/android/search", ignoreCase = true) -> null
                    normalized.endsWith("/download", ignoreCase = true) -> normalized.removeSuffix("/download")
                    normalized.contains("uptodown.com", ignoreCase = true) -> normalized
                    else -> null
                }
            }
        val searchResolvedUrls = runCatching {
            resolveUptodownSearchUrls(uptodownSearchUrl(request.packageName))
        }
            .onFailure { Log.w(TAG, "Uptodown search resolve failed", it) }
            .getOrDefault(emptyList())
        val siteSearchUrls = if (searchResolvedUrls.isEmpty()) {
            runCatching { resolveUptodownPackageSiteUrls(request.packageName) }
                .onFailure { Log.w(TAG, "Uptodown package search resolve failed", it) }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }

        return (hintedUrls + searchResolvedUrls + siteSearchUrls + defaultUrl).distinct()
    }

    private fun uptodownSearchUrl(packageName: String): String =
        "https://en.uptodown.com/android/search?query=${URLEncoder.encode(packageName, "UTF-8")}"

    private fun resolveUptodownSearchUrls(searchUrl: String): List<String> {
        val doc = fetchDocument(searchUrl)
        return doc.select("a[href]")
            .asSequence()
            .map { it.absUrl("href") }
            .filter { url ->
                url.matches(Regex("""https://[a-z0-9-]+\.en\.uptodown\.com/android/?"""))
            }
            .distinct()
            .take(20)
            .toList()
    }

    private fun resolveUptodownPackageSiteUrls(packageName: String): List<String> {
        val query = URLEncoder.encode("site:uptodown.com/android/download \"$packageName\"", "UTF-8")
        val doc = fetchDocument("https://www.bing.com/search?q=$query")
        return Regex("""https://[a-z0-9-]+\.en\.uptodown\.com/android/download""")
            .findAll(doc.html())
            .map { it.value.removeSuffix("/download") }
            .distinct()
            .take(10)
            .toList()
    }

    private fun uptodownCandidatesFromDetailUrl(
        request: HelperRequest,
        detailUrl: String
    ): List<DownloadCandidate> {
        val downloadPageUrl = "${detailUrl.trimEnd('/')}/download"
        val doc = fetchDocument(downloadPageUrl)
        val packageName = uptodownPackageName(doc)

        if (packageName != request.packageName) return emptyList()

        val versionsDoc = runCatching { fetchDocument("${detailUrl.trimEnd('/')}/versions", referer = downloadPageUrl) }
            .getOrNull()
        val versionName = versionsDoc?.selectFirst(".version")?.text()?.trim()?.takeIf(String::isNotBlank)
            ?: doc.selectFirst(".detail .info .version")?.text()?.trim()?.takeIf(String::isNotBlank)
            ?: Regex("""Information about .+? ([\w.-]+)""")
                .find(doc.text())
                ?.groupValues
                ?.getOrNull(1)
        val fileKind = parseInfoTableValue(doc, "File type")?.lowercase(Locale.US) ?: "apk"
        val externalUrl = doc.selectFirst("#detail-download-button[data-url-ext]")
            ?.attr("data-url-ext")
            ?.normalizedHttpUrlOrNull()
            // data-url-ext is the external target for apps Uptodown does not host
            // (e.g. an "external" Play Store listing like Discord). Pointing at a
            // store page is not a downloadable file, so don't treat it as a direct
            // download  the row should fall back to opening the page instead.
            ?.takeUnless { it.isPlayStoreListing() }
            ?: uptodownDownloadUrlFromPage(doc)
        val normalizedDetailUrl = detailUrl.trimEnd('/')
        val dataCode = versionsDoc?.let(::uptodownDataCode) ?: uptodownDataCode(doc)
        dataCode
            ?.let { code ->
                uptodownSelectableVariants(
                    request = request,
                    detailUrl = normalizedDetailUrl,
                    dataCode = code,
                    pageDoc = doc,
                    versionPageUrl = downloadPageUrl
                )
            }
            .orEmpty()
            .mapNotNull { variant ->
                uptodownCandidateFromVersionVariant(
                    request = request,
                    detailUrl = normalizedDetailUrl,
                    versionPageUrl = downloadPageUrl,
                    versionName = versionName,
                    option = CandidateOption.LATEST,
                    variant = variant
                )
            }
            .distinctBy(DownloadCandidate::identityKey)
            .takeIf(List<DownloadCandidate>::isNotEmpty)
            ?.let { return it }

        return listOf(
            DownloadCandidate(
            source = DownloadSource.UPTODOWN,
            name = request.appName,
            packageName = request.packageName,
            versionName = versionName,
            versionCode = null,
            url = externalUrl?.takeIf(String::isNotBlank) ?: downloadPageUrl,
            fileKind = fileKind,
            option = CandidateOption.LATEST,
            directDownload = !externalUrl.isNullOrBlank(),
            versionStatus = request.versionStatus(versionName, null),
            formatMatches = request.acceptsFormat(fileKind),
            note = if (externalUrl.isNullOrBlank() && uptodownPageUsesTurnstile(doc)) {
                "Uptodown now requires solving a captcha before it reveals the download link. Open the link and download manually."
            } else {
                null
            },
            files = externalUrl
                ?.takeIf(String::isNotBlank)
                ?.let {
                    listOf(
                        CandidateDownloadFile(
                            url = it,
                            fileName = "${request.packageName}-${versionName ?: "latest"}-uptodown.$fileKind"
                                .sanitizeFileName(),
                            referer = downloadPageUrl
                        )
                    )
                }
                .orEmpty()
            )
        )
    }

    private fun uptodownRequestedCandidatesFromDetailUrl(
        request: HelperRequest,
        detailUrl: String
    ): List<DownloadCandidate> {
        if (request.versionName == null && request.compatibleVersionNames.isEmpty()) return emptyList()

        val normalizedDetailUrl = detailUrl.trimEnd('/')
        val downloadPageUrl = "$normalizedDetailUrl/download"
        val downloadDoc = fetchDocument(downloadPageUrl)
        if (uptodownPackageName(downloadDoc) != request.packageName) return emptyList()

        val versionsDoc = fetchDocument("$normalizedDetailUrl/versions", referer = downloadPageUrl)
        val dataCode = uptodownDataCode(versionsDoc)
            ?: uptodownDataCode(downloadDoc)
            ?: return emptyList()
        val entry = uptodownRequestedVersionEntry(
            request = request,
            detailUrl = normalizedDetailUrl,
            dataCode = dataCode
        ) ?: return emptyList()

        return uptodownCandidatesFromVersionEntry(
            request = request,
            detailUrl = normalizedDetailUrl,
            dataCode = dataCode,
            entry = entry
        )
    }

    private fun uptodownRequestedVersionEntry(
        request: HelperRequest,
        detailUrl: String,
        dataCode: String
    ): UptodownVersionEntry? {
        for (page in 1..20) {
            val entries = runCatching {
                gson.fromJson(
                    fetchText("$detailUrl/apps/$dataCode/versions/$page", referer = "$detailUrl/versions"),
                    UptodownVersionResponse::class.java
                ).data
            }.getOrDefault(emptyList())

            if (entries.isEmpty()) break

            entries
                .firstOrNull { entry ->
                    val versionName = entry.version?.trim()?.takeIf(String::isNotBlank)
                    versionName != null && request.matchesRequestedVersion(versionName, null)
                }
                ?.let { return it }
        }

        return null
    }

    private fun uptodownCandidatesFromVersionEntry(
        request: HelperRequest,
        detailUrl: String,
        dataCode: String,
        entry: UptodownVersionEntry
    ): List<DownloadCandidate> {
        val versionName = entry.version?.trim()?.takeIf(String::isNotBlank) ?: return emptyList()
        val versionPageUrl = uptodownVersionPageUrl(entry) ?: return emptyList()
        val pageDoc = fetchDocument(versionPageUrl, referer = "$detailUrl/versions")
        val variants = uptodownSelectableVariants(
            request = request,
            detailUrl = detailUrl,
            dataCode = dataCode,
            pageDoc = pageDoc,
            versionPageUrl = versionPageUrl
        )

        variants
            .mapNotNull { variant ->
                uptodownCandidateFromVersionVariant(
                    request = request,
                    detailUrl = detailUrl,
                    versionPageUrl = versionPageUrl,
                    versionName = versionName,
                    option = CandidateOption.REQUESTED,
                    variant = variant
                )
            }
            .distinctBy(DownloadCandidate::identityKey)
            .takeIf(List<DownloadCandidate>::isNotEmpty)
            ?.let { return it }

        val fileKind = (
            entry.kindFile
                ?: entry.titleKindFile
                ?: parseInfoTableValue(pageDoc, "File type")
                ?: "apk"
            )
            .lowercase(Locale.US)
        val directUrl = uptodownDownloadUrlFromPage(pageDoc)

        return listOf(
            DownloadCandidate(
            source = DownloadSource.UPTODOWN,
            name = request.appName,
            packageName = request.packageName,
            versionName = versionName,
            versionCode = null,
            url = directUrl ?: versionPageUrl,
            fileKind = fileKind,
            option = CandidateOption.REQUESTED,
            directDownload = directUrl != null,
            versionStatus = request.versionStatus(versionName, null),
            formatMatches = request.acceptsFormat(fileKind),
            note = if (directUrl == null && uptodownPageUsesTurnstile(pageDoc)) {
                "Uptodown now requires solving a captcha before it reveals the download link. Open the link and download manually."
            } else {
                null
            },
            files = directUrl
                ?.let {
                    listOf(
                        CandidateDownloadFile(
                            url = it,
                            fileName = "${request.packageName}-$versionName-uptodown.$fileKind"
                                .sanitizeFileName(),
                            referer = versionPageUrl
                        )
                    )
                }
                .orEmpty()
            )
        )
    }

    private fun uptodownCandidateFromVersionVariant(
        request: HelperRequest,
        detailUrl: String,
        versionPageUrl: String,
        versionName: String?,
        option: CandidateOption,
        variant: UptodownVariantFile
    ): DownloadCandidate? {
        val tokenDoc = fetchDocument("$detailUrl/download/${variant.fileId}-x", referer = versionPageUrl)
        val fileKind = variant.fileKind.lowercase(Locale.US)
        val directUrl = uptodownDownloadUrlFromPage(tokenDoc) ?: return null
        val variantLabel = variant.displayLabel()
        val variantFileSuffix = variantLabel.variantFileSuffix()

        return DownloadCandidate(
            source = DownloadSource.UPTODOWN,
            name = request.appName,
            packageName = request.packageName,
            versionName = versionName,
            versionCode = null,
            url = directUrl,
            fileKind = fileKind,
            option = option,
            directDownload = true,
            versionStatus = request.versionStatus(versionName, null),
            formatMatches = request.acceptsFormat(fileKind),
            variantLabel = variantLabel,
            files = listOf(
                CandidateDownloadFile(
                    url = directUrl,
                    fileName = "${request.packageName}-${versionName ?: option.name.lowercase(Locale.US)}-uptodown$variantFileSuffix.$fileKind"
                        .sanitizeFileName(),
                    referer = versionPageUrl
                )
            )
        )
    }

    private fun uptodownSelectableVariants(
        request: HelperRequest,
        detailUrl: String,
        dataCode: String,
        pageDoc: Document,
        versionPageUrl: String
    ): List<UptodownVariantFile> {
        val dataVersion = pageDoc.selectFirst(".button.variants[data-version]")
            ?.attr("data-version")
            ?.takeIf(String::isNotBlank)
            ?: return emptyList()
        val appHostUrl = detailUrl.substringBeforeLast("/")
        val content = runCatching {
            gson.fromJson(
                fetchText("$appHostUrl/app/$dataCode/version/$dataVersion/files", referer = versionPageUrl),
                UptodownVariantResponse::class.java
            ).content
        }.getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: return emptyList()
        val variants = uptodownVariantFiles(Jsoup.parse(content, detailUrl))

        return variants.filter { request.acceptsFormat(it.fileKind) }
            .takeIf(List<UptodownVariantFile>::isNotEmpty)
            ?: variants
    }

    private fun uptodownVariantFiles(doc: Document): List<UptodownVariantFile> {
        val content = doc.selectFirst(".content") ?: return emptyList()
        var archLabel: String? = null
        return content.children().mapNotNull { child ->
            if (!child.hasClass("variant")) {
                archLabel = child.text().trim().takeIf(String::isNotBlank)
                null
            } else {
                uptodownVariantFile(child, archLabel)
            }
        }
    }

    private fun uptodownVariantFile(element: Element, archLabel: String?): UptodownVariantFile? {
        val fileId = element.selectFirst(".v-report[data-file-id]")
            ?.attr("data-file-id")
            ?.takeIf(String::isNotBlank)
            ?: return null
        val fileKind = element.selectFirst(".v-file span")
            ?.text()
            ?.lowercase(Locale.US)
            ?.takeIf(String::isNotBlank)
            ?: "apk"

        return UptodownVariantFile(
            fileId = fileId,
            fileKind = fileKind,
            archLabel = archLabel
        )
    }

    private fun UptodownVariantFile.displayLabel(): String? =
        archLabel?.archDisplayLabel()

    private fun uptodownPackageName(doc: Document): String? {
        val playUrl = doc.selectFirst("#gplay-url[data-url]")
            ?.attr("data-url")
            ?.takeIf(String::isNotBlank)
        val playPackage = playUrl
            ?.let { runCatching { Uri.parse(it).getQueryParameter("id") }.getOrNull() }
            ?.takeIf(String::isNotBlank)
            ?: playUrl
                ?.substringAfter("id=", "")
                ?.substringBefore("&")
                ?.takeIf(String::isNotBlank)

        return playPackage ?: parseInfoTableValue(doc, "Package Name")
    }

    private fun uptodownDataCode(doc: Document): String? =
        doc.selectFirst("#detail-app-name[data-code]")
            ?.attr("data-code")
            ?.takeIf(String::isNotBlank)

    private fun uptodownVersionPageUrl(entry: UptodownVersionEntry): String? {
        val versionUrl = entry.versionUrl ?: return null
        val baseUrl = versionUrl.url?.trim()?.trimEnd('/')?.takeIf(String::isNotBlank) ?: return null
        val extraUrl = versionUrl.extraUrl?.trim('/')?.takeIf(String::isNotBlank) ?: "download"
        val versionId = versionUrl.versionId ?: entry.fileId ?: return null
        return "$baseUrl/$extraUrl/$versionId"
    }

    private fun uptodownPageUsesTurnstile(doc: Document): Boolean =
        doc.selectFirst("#download-turnstile-widget[data-sitekey]") != null

    private fun String.isPlayStoreListing(): Boolean =
        contains("play.google.com", ignoreCase = true) ||
            contains("market.android.com", ignoreCase = true) ||
            startsWith("market://", ignoreCase = true)

    private fun uptodownDownloadUrlFromPage(doc: Document): String? {
        val dataUrl = doc.selectFirst("#detail-download-button[data-url]")
            ?.attr("data-url")
            ?.takeIf(String::isNotBlank)
            ?: return null
        val normalized = dataUrl.trim()
        return if (normalized.startsWith("http", ignoreCase = true)) {
            normalized.normalizedHttpUrlOrNull()
        } else {
            "https://dw.uptodown.com/dwn/${normalized.trimStart('/')}".normalizedHttpUrlOrNull()
        }
    }

    private fun fetchDocument(url: String, referer: String? = null): Document =
        ctx.document(url, referer)

    private fun fetchText(url: String, referer: String? = null): String =
        ctx.text(url, referer)
}
