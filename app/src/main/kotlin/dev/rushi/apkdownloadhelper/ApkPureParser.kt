package dev.rushi.apkdownloadhelper

import android.net.Uri
import android.util.Log
import java.net.URLEncoder
import java.util.Locale
import org.jsoup.nodes.Document

internal class ApkPureParser(private val ctx: SourceParserContext) : ApkSourceParser {
    override val source: DownloadSource = DownloadSource.APK_PURE

    private val apkPureApi: ApkPureApi
        get() = ctx.apkPureApi

    override suspend fun findCandidates(
        request: HelperRequest,
        option: CandidateOption
    ): List<DownloadCandidate> = when (option) {
        CandidateOption.REQUESTED -> listOfNotNull(apkPureRequestedCandidate(request))
        CandidateOption.LATEST -> apkPureLatestCandidates(request)
        CandidateOption.MANUAL -> emptyList()
    }

    override fun searchUrl(packageName: String): String? = apkPureInfoUrl(packageName)

    override suspend fun resolveHistory(request: HelperRequest): List<DownloadCandidate> {
        val appPageUrl = runCatching { apkPureAppPageUrl(request) }
            .onFailure { Log.w(TAG, "APKPure app page resolve failed", it) }
            .getOrNull()
            ?: return emptyList()
        val versionsDoc = runCatching { fetchDocument("$appPageUrl/versions", referer = appPageUrl) }
            .onFailure { Log.w(TAG, "APKPure versions page resolve failed", it) }
            .getOrNull()
            ?: return emptyList()
        val entries = apkPureVersionEntries(versionsDoc, request)

        return entries
            .distinctBy { it.versionName?.normalizedVersionName() }
            .mapNotNull { entry ->
                val versionName = entry.versionName ?: return@mapNotNull null
                DownloadCandidate(
                    source = DownloadSource.APK_PURE,
                    name = request.appName,
                    packageName = request.packageName,
                    versionName = versionName,
                    versionCode = entry.versionCode,
                    url = entry.downloadPageUrl,
                    fileKind = entry.fileKind,
                    option = CandidateOption.LATEST,
                    directDownload = false,
                    versionStatus = request.versionStatus(versionName, entry.versionCode),
                    formatMatches = request.acceptsFormat(entry.fileKind),
                    note = null
                )
            }
            .sortedWith { left, right -> compareVersionNames(right.versionName, left.versionName) }
    }

    override suspend fun resolveHistoryCandidate(
        request: HelperRequest,
        candidate: DownloadCandidate
    ): DownloadCandidate? {
        val appPageUrl = candidate.url.substringBefore("/download").ifBlank { candidate.url }
        return runCatching {
            apkPureCandidateFromDownloadPage(
                request = request,
                appPageUrl = appPageUrl,
                downloadPageUrl = candidate.url,
                versionName = candidate.versionName,
                versionCode = candidate.versionCode,
                option = CandidateOption.LATEST
            )
        }
            .onFailure { Log.w(TAG, "APKPure history version resolve failed: ${candidate.url}", it) }
            .getOrNull()
    }

    private suspend fun apkPureLatestCandidates(request: HelperRequest): List<DownloadCandidate> {
        val response = apkPureApi.getAppUpdate(
            header = gson.toJson(ApkPureDeviceHeader()),
            request = ApkPureUpdateRequest(
                app_info_for_update = listOf(
                    ApkPureAppInfo(package_name = request.packageName, version_code = 0L)
                )
            )
        )

        val apiLatestCandidates = response.app_update_response
            .filter { it.package_name == request.packageName }
            .mapNotNull { item -> apkPureApiCandidate(item, request, CandidateOption.LATEST) }

        val appPageUrl = runCatching { apkPureAppPageUrl(request) }
            .onFailure { Log.w(TAG, "APKPure app page resolve failed", it) }
            .getOrNull()
        val webLatestCandidate = if (apiLatestCandidates.isEmpty()) {
            appPageUrl?.let { url ->
                runCatching {
                    apkPureCandidateFromDownloadPage(
                        request = request,
                        appPageUrl = url,
                        downloadPageUrl = "$url/downloading/",
                        versionName = null,
                        versionCode = null,
                        option = CandidateOption.LATEST
                    )
                }
                    .onFailure { Log.w(TAG, "APKPure latest web resolve failed", it) }
                    .getOrNull()
            }
        } else {
            null
        }

        return apiLatestCandidates + listOfNotNull(webLatestCandidate)
    }

    private fun apkPureApiCandidate(
        item: ApkPureAppUpdate,
        request: HelperRequest,
        option: CandidateOption
    ): DownloadCandidate? {
        val url = item.asset.url.replace("http://", "https://").normalizedHttpUrlOrNull()
            ?: return null
        val fileKind = if (url.contains("/XAPK", ignoreCase = true)) "xapk" else "apk"

        return DownloadCandidate(
            source = DownloadSource.APK_PURE,
            name = item.label.ifBlank { request.appName },
            packageName = item.package_name,
            versionName = item.version_name,
            versionCode = item.version_code,
            url = url,
            fileKind = fileKind,
            option = option,
            directDownload = true,
            versionStatus = request.versionStatus(item.version_name, item.version_code),
            formatMatches = request.acceptsFormat(fileKind),
            files = listOf(
                CandidateDownloadFile(
                    url = url,
                    fileName = "${item.package_name}-${item.version_name}-apkpure.$fileKind"
                        .sanitizeFileName()
                )
            )
        )
    }

    private suspend fun apkPureRequestedCandidate(request: HelperRequest): DownloadCandidate? {
        val appPageUrl = runCatching { apkPureAppPageUrl(request) }
            .onFailure { Log.w(TAG, "APKPure app page resolve failed", it) }
            .getOrNull()
        val webCandidate = appPageUrl?.let { url ->
            runCatching { apkPureRequestedCandidate(request, url) }
                .onFailure { Log.w(TAG, "APKPure requested version resolve failed", it) }
                .getOrNull()
        }
        if (webCandidate != null) return webCandidate

        // The web versions page can be blocked (HTTP 410) for apps whose listing
        // APKPure removed. The update API still serves the exact requested
        // version in that case — fall back to it before giving up.
        return runCatching { apkPureApiRequestedCandidate(request) }
            .onFailure { Log.w(TAG, "APKPure requested version API resolve failed", it) }
            .getOrNull()
    }

    private suspend fun apkPureApiRequestedCandidate(request: HelperRequest): DownloadCandidate? {
        if (!request.hasRequestedVersionRequest) return null
        val response = apkPureApi.getAppUpdate(
            header = gson.toJson(ApkPureDeviceHeader()),
            request = ApkPureUpdateRequest(
                app_info_for_update = listOf(
                    ApkPureAppInfo(package_name = request.packageName, version_code = 0L)
                )
            )
        )
        return response.app_update_response
            .firstOrNull { item ->
                item.package_name == request.packageName &&
                    request.matchesRequestedVersion(item.version_name, item.version_code)
            }
            ?.let { item -> apkPureApiCandidate(item, request, CandidateOption.REQUESTED) }
    }

    private fun apkPureInfoUrl(packageName: String): String =
        "https://apkpure.com/apk-info/$packageName"

    private fun apkPureAppPageUrl(request: HelperRequest): String? {
        val doc = fetchDocument(apkPureInfoUrl(request.packageName))
        return doc.selectFirst("link[rel=canonical]")
            ?.absUrl("href")
            ?.takeIf { url -> apkPureUrlMatchesPackage(url, request.packageName) }
            ?.trimEnd('/')
    }

    private fun apkPureUrlMatchesPackage(url: String, packageName: String): Boolean =
        runCatching {
            java.net.URI(url).path.trim('/').split('/').lastOrNull() == packageName
        }.getOrDefault(false)

    private fun apkPureRequestedCandidate(request: HelperRequest, appPageUrl: String): DownloadCandidate? {
        if (!request.hasRequestedVersionRequest) return null

        val doc = fetchDocument(apkPureInfoUrl(request.packageName))
        val item = doc.select(".version-item")
            .firstOrNull { element ->
                val versionName = element.attr("data-dt-version").takeIf(String::isNotBlank)
                    ?: sourceVersionFromText(element.text())
                val versionCode = element.attr("data-dt-version_code").toLongOrNull()
                element.attr("data-dt-package_name").equals(request.packageName, ignoreCase = true) &&
                    request.matchesRequestedVersion(versionName, versionCode)
            }
        if (item == null) {
            return apkPureRequestedCandidateFromVersionsPage(request, appPageUrl)
        }

        val versionName = item.attr("data-dt-version").takeIf(String::isNotBlank)
            ?.withoutTrailingVersionCode()
            ?: request.requestedVersionName
        val versionCode = item.attr("data-dt-version_code").toLongOrNull()
            ?: item.attr("data-dt-versioncode").toLongOrNull()
            ?: item.attr("data-dt-version").trailingVersionCode()
        val versionPageUrl = item.selectFirst("a.go-version-btn[href], a.dt-version-name-link[href]")
            ?.absUrl("href")
            ?.normalizedHttpUrlOrNull()
            ?: "$appPageUrl/download/${URLEncoder.encode(versionName.orEmpty(), "UTF-8")}"
        val fileKind = fileKindFromTags(
            tags = item.select(".tag").map { it.text() },
            request = request
        )

        apkPureCandidateFromDownloadPage(
            request = request,
            appPageUrl = appPageUrl,
            downloadPageUrl = apkPureDownloadingUrl(appPageUrl, versionName),
            versionName = versionName,
            versionCode = versionCode,
            option = CandidateOption.REQUESTED
        )?.let { return it }

        return DownloadCandidate(
            source = DownloadSource.APK_PURE,
            name = request.appName,
            packageName = request.packageName,
            versionName = versionName,
            versionCode = versionCode,
            url = versionPageUrl,
            fileKind = fileKind,
            option = CandidateOption.REQUESTED,
            directDownload = false,
            versionStatus = request.versionStatus(versionName, versionCode),
            formatMatches = request.acceptsFormat(fileKind)
        )
    }

    private fun apkPureRequestedCandidateFromVersionsPage(
        request: HelperRequest,
        appPageUrl: String
    ): DownloadCandidate? {
        val versionsDoc = fetchDocument("$appPageUrl/versions", referer = appPageUrl)
        val entry = apkPureVersionEntries(versionsDoc, request)
            .firstOrNull { request.matchesRequestedVersion(it.versionName, it.versionCode) }
            ?: return null

        apkPureCandidateFromDownloadPage(
            request = request,
            appPageUrl = appPageUrl,
            downloadPageUrl = entry.downloadPageUrl,
            versionName = entry.versionName,
            versionCode = entry.versionCode,
            option = CandidateOption.REQUESTED
        )?.let { return it }

        return DownloadCandidate(
            source = DownloadSource.APK_PURE,
            name = request.appName,
            packageName = request.packageName,
            versionName = entry.versionName,
            versionCode = entry.versionCode,
            url = entry.downloadPageUrl,
            fileKind = entry.fileKind,
            option = CandidateOption.REQUESTED,
            directDownload = false,
            versionStatus = request.versionStatus(entry.versionName, entry.versionCode),
            formatMatches = request.acceptsFormat(entry.fileKind)
        )
    }

    private fun apkPureVersionEntries(doc: Document, request: HelperRequest): List<ApkPureVersionEntry> =
        doc.select(".ver_download_link[data-dt-version]")
            .mapNotNull { item ->
                val rawVersion = item.attr("data-dt-version").takeIf(String::isNotBlank)
                    ?: sourceVersionFromText(item.text())
                val versionName = rawVersion
                    ?.withoutTrailingVersionCode()
                    ?.takeIf(String::isNotBlank)
                val versionCode = item.attr("data-dt-versioncode").toLongOrNull()
                    ?: item.attr("data-dt-version_code").toLongOrNull()
                    ?: rawVersion?.trailingVersionCode()
                val downloadPageUrl = item
                    .selectFirst("a.dt-version-name-link[href], a[href*=/download/][href]")
                    ?.absUrl("href")
                    ?.normalizedHttpUrlOrNull()
                    ?: return@mapNotNull null
                val tags = buildList {
                    addAll(item.select("[data-tag]").map { it.attr("data-tag") })
                    addAll(item.select(".tag, .apk-type-tag-list *").map { it.text() })
                    apkPureFileKindFromApkId(item.attr("data-dt-apkid"))?.let(::add)
                }
                val fileKind = fileKindFromTags(tags, request).takeUnless { it == "web" }
                    ?: fileKindFromUrl(downloadPageUrl)

                ApkPureVersionEntry(
                    versionName = versionName,
                    versionCode = versionCode,
                    downloadPageUrl = downloadPageUrl,
                    fileKind = fileKind
                )
            }

    private fun apkPureFileKindFromApkId(apkId: String): String? =
        apkId.substringAfter("b/", "")
            .substringBefore("/")
            .lowercase(Locale.US)
            .takeIf { it in DOWNLOAD_FILE_KIND_SET }

    private fun apkPureDownloadingUrl(appPageUrl: String, versionName: String?): String =
        if (versionName.isNullOrBlank()) {
            "$appPageUrl/downloading/"
        } else {
            "$appPageUrl/downloading/${Uri.encode(versionName)}"
        }

    private fun apkPureCandidateFromDownloadPage(
        request: HelperRequest,
        appPageUrl: String,
        downloadPageUrl: String,
        versionName: String?,
        versionCode: Long?,
        option: CandidateOption
    ): DownloadCandidate? {
        val doc = fetchDocument(downloadPageUrl, referer = appPageUrl)
        if (!apkPureDownloadPageIsValid(doc)) return null

        val downloadUrl = doc.selectFirst("a#download_link[href]")
            ?.absUrl("href")
            ?.replace("http://", "https://")
            ?.normalizedHttpUrlOrNull()
            ?: return null
        val resolvedVersion = versionName
            ?: Regex("""["']softwareVersion["']\s*:\s*["']([^"']+)["']""")
                .find(doc.html())
                ?.groupValues
                ?.getOrNull(1)
            ?: sourceVersionFromText(doc.select("h1, h2").joinToString(" ") { it.text() })
        val fileKind = fileKindFromUrl(downloadUrl)

        return DownloadCandidate(
            source = DownloadSource.APK_PURE,
            name = request.appName,
            packageName = request.packageName,
            versionName = resolvedVersion,
            versionCode = versionCode,
            url = downloadUrl,
            fileKind = fileKind,
            option = option,
            directDownload = true,
            versionStatus = request.versionStatus(resolvedVersion, versionCode),
            formatMatches = request.acceptsFormat(fileKind),
            files = listOf(
                CandidateDownloadFile(
                    url = downloadUrl,
                    fileName = "${request.packageName}-${resolvedVersion ?: option.name.lowercase(Locale.US)}-apkpure.$fileKind"
                        .sanitizeFileName(),
                    referer = downloadPageUrl
                )
            )
        )
    }

    private fun apkPureDownloadPageIsValid(doc: Document): Boolean {
        if (doc.title().equals("Error", ignoreCase = true)) return false
        if (doc.text().contains("Oopps! The page can't be found", ignoreCase = true)) return false
        return true
    }

    private fun fetchDocument(url: String, referer: String? = null): Document =
        ctx.document(url, referer)
}
