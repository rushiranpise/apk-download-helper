package dev.rushi.apkdownloadhelper

import android.net.Uri
import android.util.Log
import java.net.URLEncoder
import java.util.Locale
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** App-listing slugs that carry a different version stream than the canonical app. */
private val APKMIRROR_EDITION_SLUG_REGEX = Regex(
    """(amazon|fire-tablet|fire-tv|androidtv|wear|go-edition|lite|beta|alpha|enterprise|kids|headunit|auto)""",
    RegexOption.IGNORE_CASE
)

internal class ApkMirrorParser(private val ctx: SourceParserContext) : ApkSourceParser {
    override val source: DownloadSource = DownloadSource.APK_MIRROR

    override suspend fun findCandidates(
        request: HelperRequest,
        option: CandidateOption
    ): List<DownloadCandidate> = when (option) {
        CandidateOption.REQUESTED -> findApkMirrorRequested(request)
        CandidateOption.LATEST -> findApkMirrorLatest(request)
        CandidateOption.MANUAL -> emptyList()
    }

    override fun searchUrl(packageName: String): String? = apkMirrorPackageSearchUrl(packageName)

    override fun requestedFallbackCandidate(request: HelperRequest): DownloadCandidate =
        apkMirrorRequested(request)

    override fun latestFallbackCandidate(request: HelperRequest): DownloadCandidate =
        apkMirrorLatest(request)

    private val historyCache = HashMap<String, List<DownloadCandidate>>()

    override suspend fun resolveHistory(request: HelperRequest): List<DownloadCandidate> {
        historyCache[request.packageName]?.let { return it }
        val result = resolveHistoryUncached(request)
        historyCache[request.packageName] = result
        return result
    }

    private suspend fun resolveHistoryUncached(request: HelperRequest): List<DownloadCandidate> {
        val searchDoc = fetchDocument(apkMirrorPackageSearchUrl(request.packageName))
        val appPageUrl = resolveApkMirrorAppPage(searchDoc, request) ?: return emptyList()
        val category = appPageUrl.trimEnd('/').substringAfterLast('/').takeIf(String::isNotBlank)
            ?: return emptyList()

        val releaseUrls = mutableListOf<String>()
        for (page in 1..3) {
            val pageUrl = if (page == 1) {
                apkMirrorUploadsUrl(appPageUrl)
            } else {
                "https://www.apkmirror.com/uploads/page/$page/?appcategory=$category"
            }
            val doc: Document = try {
                fetchDocument(pageUrl, referer = appPageUrl)
            } catch (e: HttpRateLimitedException) {
                break
            } catch (e: Exception) {
                continue
            }
            // Filter by the app page path: the uploads page also carries
            // trending/sidebar release links for other apps, which must not
            // leak into this app's version history.
            val links = apkMirrorReleaseLinks(doc, appPageUrl)
            if (links.isEmpty()) break
            releaseUrls += links
        }

        return releaseUrls
            .distinct()
            .mapNotNull { releaseUrl ->
                val versionName = apkMirrorVersionFromReleaseUrl(releaseUrl) ?: return@mapNotNull null
                DownloadCandidate(
                    source = DownloadSource.APK_MIRROR,
                    name = request.appName,
                    packageName = request.packageName,
                    versionName = versionName,
                    versionCode = null,
                    url = releaseUrl,
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

    override suspend fun resolveHistoryCandidate(
        request: HelperRequest,
        candidate: DownloadCandidate
    ): DownloadCandidate? {
        // History rows carry the release-page URL. Resolve it the same way the
        // Recommended/Latest tabs do; return null when no direct download can be
        // produced so the UI can offer an "Open link" action instead.
        return runCatching {
            apkMirrorCandidatesFromReleaseUrl(
                request = request,
                releaseUrl = candidate.url,
                versionName = candidate.versionName,
                option = candidate.option
            )
        }
            .getOrNull()
            ?.firstOrNull { it.directDownload }
    }

    private fun apkMirrorRequested(request: HelperRequest) = DownloadCandidate(
        source = DownloadSource.APK_MIRROR,
        name = request.appName,
        packageName = request.packageName,
        versionName = request.versionName,
        versionCode = request.versionCode ?: request.versionCodes.singleOrNull(),
        url = request.sourceHintUrlsFor(DownloadSource.APK_MIRROR).firstOrNull() ?: request.fallbackWebUrl,
        fileKind = request.requestedFormatLabel,
        option = CandidateOption.REQUESTED,
        directDownload = false,
        versionStatus = VersionStatus.REQUESTED,
        formatMatches = true
    )

    private fun apkMirrorLatest(request: HelperRequest): DownloadCandidate =
        apkMirrorLatestWebCandidate(request, runCatching {
            resolveApkMirrorLatestInfo(request, apkMirrorPackageSearchUrl(request.packageName))
        }.getOrNull())

    private fun findApkMirrorLatest(request: HelperRequest): List<DownloadCandidate> {
        val latestInfo = resolveApkMirrorLatestInfo(
            request = request,
            searchUrl = apkMirrorPackageSearchUrl(request.packageName)
        )
        val latestReleaseUrl = latestInfo
            ?.openUrl
            ?.takeIf(::apkMirrorLooksLikeReleaseUrl)
        val directCandidates = latestReleaseUrl?.let { releaseUrl ->
            apkMirrorCandidatesFromReleaseUrl(
                request = request,
                releaseUrl = releaseUrl,
                versionName = latestInfo.versionName ?: apkMirrorVersionFromReleaseUrl(releaseUrl),
                option = CandidateOption.LATEST
            )
        }

        return directCandidates.orEmpty()
            .ifEmpty { listOf(apkMirrorLatestWebCandidate(request, latestInfo)) }
    }

    private fun apkMirrorLatestWebCandidate(
        request: HelperRequest,
        latestInfo: ApkMirrorLatestInfo?
    ): DownloadCandidate {
        val searchUrl = apkMirrorPackageSearchUrl(request.packageName)
        return DownloadCandidate(
            source = DownloadSource.APK_MIRROR,
            name = request.appName,
            packageName = request.packageName,
            versionName = latestInfo?.versionName,
            versionCode = null,
            url = latestInfo?.openUrl ?: searchUrl,
            fileKind = "web",
            option = CandidateOption.LATEST,
            directDownload = false,
            versionStatus = VersionStatus.LATEST,
            formatMatches = true
        )
    }

    private fun findApkMirrorRequested(request: HelperRequest): List<DownloadCandidate> {
        if (!request.hasKnownVersionRequest) return emptyList()

        val searchDoc = fetchDocument(apkMirrorPackageSearchUrl(request.packageName))
        val appPageUrl = resolveApkMirrorAppPage(
            searchDoc = searchDoc,
            request = request
        ) ?: throw SourceAppNotFoundException(request.packageName)
        val appDoc = fetchDocument(appPageUrl)
        val requested = apkMirrorRequestedReleaseUrl(
            request = request,
            appPageUrl = appPageUrl,
            appDoc = appDoc
        )?.let { releaseUrl ->
            apkMirrorCandidatesFromReleaseUrl(
                request = request,
                releaseUrl = releaseUrl,
                versionName = request.requestedVersionNames.firstOrNull()
                    ?: apkMirrorVersionFromReleaseUrl(releaseUrl),
                option = CandidateOption.REQUESTED
            )
        }

        return requested.orEmpty()
    }

    private fun apkMirrorPackageSearchUrl(packageName: String): String {
        val query = URLEncoder.encode(packageName, "UTF-8")
        return "https://www.apkmirror.com/?post_type=app_release&searchtype=app&s=$query"
    }

    private fun resolveApkMirrorLatestInfo(
        request: HelperRequest,
        searchUrl: String
    ): ApkMirrorLatestInfo? {
        val searchDoc = fetchDocument(searchUrl)
        val appPageUrl = resolveApkMirrorAppPage(
            searchDoc = searchDoc,
            request = request
        ) ?: throw SourceAppNotFoundException(request.packageName)
        val searchVersion = apkMirrorSearchVersionForApp(searchDoc, appPageUrl)
            ?: apkMirrorVersions(searchDoc.html()).firstOrNull()
        val appDoc = fetchDocument(appPageUrl)
        val uploadsDoc = runCatching { fetchDocument(apkMirrorUploadsUrl(appPageUrl), referer = appPageUrl) }
            .getOrNull()
        val latestReleaseUrl = apkMirrorLatestReleaseUrl(
            (
                uploadsDoc?.let { apkMirrorReleaseLinks(it, appPageUrl) }.orEmpty() +
                    apkMirrorReleaseLinks(appDoc, appPageUrl)
                ).distinct()
        )
        val latestVersion = latestReleaseUrl?.let(::apkMirrorVersionFromReleaseUrl)
            ?: searchVersion
            ?: apkMirrorVersions(appDoc.html()).firstOrNull()

        return ApkMirrorLatestInfo(
            versionName = latestVersion,
            openUrl = latestReleaseUrl ?: appPageUrl
        )
    }

    private fun resolveApkMirrorAppPage(searchDoc: Document, request: HelperRequest): String? {
        if (searchDoc.isApkMirrorNoResults()) return null
        for (candidate in apkMirrorAppPageCandidates(searchDoc, request)) {
            val candidateDoc: Document = try {
                fetchDocument(candidate)
            } catch (e: HttpRateLimitedException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            if (candidateDoc.apkMirrorMatchesPackage(request.packageName)) return candidate
        }

        return null
    }

    private fun apkMirrorAppPageCandidates(searchDoc: Document, request: HelperRequest): List<String> {
        val expectedSlugs = apkMirrorExpectedAppSlugs(request)
        return searchDoc.select("a[href]")
            .asSequence()
            .mapNotNull { apkMirrorAbsoluteUrl(it.attr("href")) }
            .filter { url ->
                runCatching {
                    java.net.URI(url).path.matches(Regex("""/apk/[^/]+/[^/]+/?"""))
                }.getOrDefault(false)
            }
            .distinct()
            .sortedBy { url -> apkMirrorAppSlugScore(url, expectedSlugs) }
            .take(20)
            .toList()
    }

    private fun apkMirrorExpectedAppSlugs(request: HelperRequest): Set<String> =
        buildSet {
            add(request.appName.slugForUrl())
            val packageParts = request.packageName.split(".")
            packageParts.takeLast(2)
                .joinToString("-")
                .slugForUrl()
                .takeIf(String::isNotBlank)
                ?.let(::add)
            packageParts.takeLast(3)
                .joinToString("-")
                .slugForUrl()
                .takeIf(String::isNotBlank)
                ?.let(::add)
        }

    private fun apkMirrorAppSlugScore(url: String, expectedSlugs: Set<String>): Int {
        val segments = runCatching {
            java.net.URI(url).path.trim('/').split('/')
        }.getOrDefault(emptyList())
        // Path layout: /apk/{developer}/{app-slug}/ — the developer segment
        // alone is ambiguous when a publisher lists several editions (e.g.
        // TikTok vs its Amazon Appstore edition), so score the app segment too.
        val developerSlug = segments.getOrNull(1).orEmpty()
        val appSlug = segments.getOrNull(2).orEmpty()

        fun scoreSlug(slug: String): Int = when {
            slug in expectedSlugs -> 0
            expectedSlugs.any { slug.endsWith(it) } -> 1
            expectedSlugs.any { slug.contains(it) } -> 2
            else -> 3
        }

        // Score both the developer and the app segment (e.g. TikTok's canonical
        // listing vs its Amazon Appstore edition live under the same developer).
        var score = minOf(scoreSlug(developerSlug), scoreSlug(appSlug))

        // Prefer the canonical listing over special editions, which share the
        // package name but carry different version streams (amazon, lite, tv...).
        if (APKMIRROR_EDITION_SLUG_REGEX.containsMatchIn(appSlug)) score += 4
        return score
    }

    private fun apkMirrorSearchVersionForApp(searchDoc: Document, appPageUrl: String): String? {
        val appPath = runCatching { java.net.URI(appPageUrl).path.trimEnd('/') }.getOrNull()
            ?: return null
        val row = searchDoc.select("div.appRow")
            .firstOrNull { row ->
                row.select("a[href]")
                    .asSequence()
                    .map { it.absUrl("href") }
                    .any { url ->
                        runCatching { java.net.URI(url).path.trimEnd('/') == appPath }
                            .getOrDefault(false)
                    }
            }

        return row
            ?.select("img[alt]")
            ?.asSequence()
            ?.map { it.attr("alt") }
            ?.mapNotNull { alt -> Regex("""\b(\d+(?:\.\d+)+(?:[.\w-]*)?)\b""").findAll(alt).lastOrNull()?.value }
            ?.firstOrNull()
    }

    private fun apkMirrorVersions(html: String): List<String> =
        Regex(
            """Version:\s*</span>\s*<span[^>]*class=["'][^"']*infoSlide-value[^"']*["'][^>]*>\s*([^<]+?)\s*</span>""",
            RegexOption.IGNORE_CASE
        )
            .findAll(html)
            .mapNotNull { match -> match.groupValues.getOrNull(1)?.trim()?.takeIf(String::isNotBlank) }
            .filterNot { it.contains("alpha", ignoreCase = true) || it.contains("beta", ignoreCase = true) }
            .distinct()
            .toList()

    private fun apkMirrorReleaseLinks(doc: Document, appPageUrl: String? = null): List<String> {
        val appPath = appPageUrl
            ?.let { url -> runCatching { java.net.URI(url).path.trimEnd('/') }.getOrNull() }

        return doc.select("a[href]")
            .asSequence()
            .mapNotNull { link -> apkMirrorAbsoluteUrl(link.attr("href")) }
            .filter { url ->
                runCatching {
                    val path = java.net.URI(url).path
                    path.startsWith("/apk/") &&
                        path.trimEnd('/').endsWith("-release") &&
                        (appPath == null || path.startsWith("$appPath/"))
                }.getOrDefault(false)
            }
            .distinct()
            .toList()
    }

    private fun apkMirrorLatestReleaseUrl(releaseLinks: List<String>): String? =
        releaseLinks
            .filterNot { it.contains("alpha", ignoreCase = true) || it.contains("beta", ignoreCase = true) }
            .ifEmpty { releaseLinks }
            .maxWithOrNull { left, right ->
                compareVersionNames(
                    apkMirrorVersionFromReleaseUrl(left),
                    apkMirrorVersionFromReleaseUrl(right)
                )
            }

    private fun apkMirrorRequestedReleaseUrl(
        request: HelperRequest,
        appPageUrl: String,
        appDoc: Document
    ): String? {
        val requestedVersions = request.requestedVersionNames
        if (requestedVersions.isEmpty()) return null

        request.sourceHintUrlsFor(DownloadSource.APK_MIRROR)
            .asSequence()
            .mapNotNull(::apkMirrorAbsoluteUrl)
            .firstOrNull { url ->
                apkMirrorLooksLikeReleaseUrl(url) &&
                    requestedVersions.any { version -> apkMirrorReleaseUrlMatchesVersion(url, version) }
            }
            ?.let { return it }

        apkMirrorReleaseLinks(appDoc, appPageUrl)
            .firstOrNull { url ->
                requestedVersions.any { version -> apkMirrorReleaseUrlMatchesVersion(url, version) }
            }
            ?.let { return it }

        val category = appPageUrl.trimEnd('/').substringAfterLast('/').takeIf(String::isNotBlank)
            ?: return null
        val uploadsUrl = "https://www.apkmirror.com/uploads/?appcategory=$category"
        for (page in 1..5) {
            val pageUrl = if (page == 1) {
                uploadsUrl
            } else {
                "https://www.apkmirror.com/uploads/page/$page/?appcategory=$category"
            }
            val doc: Document = try {
                fetchDocument(pageUrl, referer = appPageUrl)
            } catch (e: HttpRateLimitedException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "APKMirror uploads page resolve failed: $pageUrl", e)
                continue
            }

            apkMirrorReleaseLinks(doc)
                .firstOrNull { url ->
                    requestedVersions.any { version -> apkMirrorReleaseUrlMatchesVersion(url, version) }
                }
                ?.let { return it }
        }

        return null
    }

    private fun apkMirrorUploadsUrl(appPageUrl: String): String {
        val category = runCatching {
            Uri.parse(appPageUrl).path
                ?.trim('/')
                ?.substringAfterLast('/')
                ?.takeIf(String::isNotBlank)
        }.getOrNull()
            ?: appPageUrl.substringBefore('#').substringBefore('?').trimEnd('/').substringAfterLast('/')
        return "https://www.apkmirror.com/uploads/?appcategory=$category"
    }

    private fun apkMirrorCandidatesFromReleaseUrl(
        request: HelperRequest,
        releaseUrl: String,
        versionName: String?,
        option: CandidateOption
    ): List<DownloadCandidate> {
        val directCandidates = runCatching {
            apkMirrorDirectCandidatesFromReleaseUrl(
                request = request,
                releaseUrl = releaseUrl,
                versionName = versionName,
                option = option
            )
        }
            .onFailure { Log.w(TAG, "APKMirror direct resolve failed: $releaseUrl", it) }
        val directFailureMessage = directCandidates.exceptionOrNull()
            ?.let { sourceFailureMessage(DownloadSource.APK_MIRROR, it) }
        val resolvedDirectCandidates = directCandidates.getOrDefault(emptyList())

        return resolvedDirectCandidates.ifEmpty {
            listOf(
                apkMirrorReleaseFallbackCandidate(
                    request = request,
                    releaseUrl = releaseUrl,
                    versionName = versionName,
                    option = option,
                    note = directFailureMessage
                )
            )
        }
    }

    private fun apkMirrorDirectCandidatesFromReleaseUrl(
        request: HelperRequest,
        releaseUrl: String,
        versionName: String?,
        option: CandidateOption
    ): List<DownloadCandidate> {
        val releaseDoc = fetchDocument(releaseUrl)
        if (releaseDoc.isCloudflareChallenge()) return emptyList()

        val variants = apkMirrorVariants(releaseDoc)
        val selectedVariants = if (variants.isEmpty()) {
            listOf(null)
        } else {
            apkMirrorSelectableVariants(request, variants)
        }
        if (selectedVariants.isEmpty()) return emptyList()

        return selectedVariants
            .mapNotNull { variant ->
                apkMirrorDirectCandidateFromReleaseUrl(
                    request = request,
                    releaseUrl = releaseUrl,
                    versionName = versionName,
                    option = option,
                    variant = variant
                )
            }
            .distinctBy(DownloadCandidate::identityKey)
    }

    private fun apkMirrorDirectCandidateFromReleaseUrl(
        request: HelperRequest,
        releaseUrl: String,
        versionName: String?,
        option: CandidateOption,
        variant: ApkMirrorVariant?
    ): DownloadCandidate? {
        val variantPageUrl = variant?.url ?: releaseUrl
        val variantDoc = if (variant == null) {
            fetchDocument(releaseUrl)
        } else {
            fetchDocument(variantPageUrl, referer = releaseUrl)
        }
        if (variantDoc.isCloudflareChallenge()) return null

        val isBundle = variant?.isBundle ?: apkMirrorPageLooksBundle(variantDoc)
        val downloadButtonUrl = apkMirrorDownloadButtonUrl(variantDoc, isBundle) ?: return null
        val downloadDoc = fetchDocument(downloadButtonUrl, referer = variantPageUrl)
        if (downloadDoc.isCloudflareChallenge()) return null

        val finalUrl = apkMirrorFinalDownloadUrl(downloadDoc) ?: return null
        val resolvedVersion = versionName
            ?: apkMirrorVersionFromReleaseUrl(releaseUrl)
        val fileKind = variant?.fileKind ?: if (isBundle) "apkm" else fileKindFromUrl(finalUrl)
        val variantLabel = variant?.displayLabel()
        val variantFileSuffix = variantLabel.variantFileSuffix()

        return DownloadCandidate(
            source = DownloadSource.APK_MIRROR,
            name = request.appName,
            packageName = request.packageName,
            versionName = resolvedVersion,
            versionCode = null,
            url = finalUrl,
            fileKind = fileKind,
            option = option,
            directDownload = true,
            versionStatus = request.versionStatus(resolvedVersion, null),
            formatMatches = request.acceptsFormat(fileKind),
            variantLabel = variantLabel,
            files = listOf(
                CandidateDownloadFile(
                    url = finalUrl,
                    fileName = "${request.packageName}-${resolvedVersion ?: option.name.lowercase(Locale.US)}-apkmirror$variantFileSuffix.$fileKind"
                        .sanitizeFileName(),
                    referer = downloadButtonUrl
                )
            )
        )
    }

    private fun apkMirrorReleaseFallbackCandidate(
        request: HelperRequest,
        releaseUrl: String,
        versionName: String?,
        option: CandidateOption,
        note: String? = null
    ) = DownloadCandidate(
        source = DownloadSource.APK_MIRROR,
        name = request.appName,
        packageName = request.packageName,
        versionName = versionName ?: apkMirrorVersionFromReleaseUrl(releaseUrl),
        versionCode = null,
        url = releaseUrl,
        fileKind = "web",
        option = option,
        directDownload = false,
        versionStatus = request.versionStatus(versionName ?: apkMirrorVersionFromReleaseUrl(releaseUrl), null),
        formatMatches = true,
        note = note
    )

    private fun apkMirrorVariants(releaseDoc: Document): List<ApkMirrorVariant> =
        releaseDoc.select("div.table-row.headerFont")
            .mapNotNull(::apkMirrorVariantFromRow)

    private fun apkMirrorSelectableVariants(
        request: HelperRequest,
        variants: List<ApkMirrorVariant>
    ): List<ApkMirrorVariant> {
        val wantedKinds = apkMirrorWantedVariantKinds(request)
        for (kind in wantedKinds) {
            val typedVariants = variants.filter {
                it.fileKind == kind &&
                    request.acceptsFormat(it.fileKind)
            }
            typedVariants.filter { apkMirrorDpiMatches(it.dpi) }
                .takeIf(List<ApkMirrorVariant>::isNotEmpty)
                ?.let { return it }
            typedVariants.takeIf(List<ApkMirrorVariant>::isNotEmpty)
                ?.let { return it }
        }

        val acceptedVariants = variants.filter { request.acceptsFormat(it.fileKind) }
        val dpiPreferred = acceptedVariants.filter { apkMirrorDpiMatches(it.dpi) }
        val dpiAnyFormat = variants.filter { apkMirrorDpiMatches(it.dpi) }
        return when {
            dpiPreferred.isNotEmpty() -> dpiPreferred
            acceptedVariants.isNotEmpty() -> acceptedVariants
            dpiAnyFormat.isNotEmpty() -> dpiAnyFormat
            // No variant matches the requested format (e.g. APKS requested but the
            // release is only offered as an APKM bundle): offer the best variant
            // anyway and let the UI flag the format mismatch, like other sources do.
            else -> variants
        }
    }

    private fun apkMirrorVariantFromRow(row: Element): ApkMirrorVariant? {
        val url = row.selectFirst("div.table-cell:nth-child(1) a[href]")
            ?.attr("href")
            ?.let(::apkMirrorAbsoluteUrl)
            ?: return null
        val type = row.selectFirst("div.table-cell:nth-child(1) span.apkm-badge")
            ?.text()
            ?.trim()
            ?.uppercase(Locale.US)
            ?.takeIf(String::isNotBlank)
            ?: "APK"
        val fileKind = apkMirrorVariantFileKind(type)
        val cells = row.select("div.table-cell")
        val arch = cells.getOrNull(1)?.text()?.trim()?.takeIf(String::isNotBlank)
        val dpi = cells.getOrNull(3)?.text()?.trim()?.takeIf(String::isNotBlank)

        return ApkMirrorVariant(
            url = url,
            type = type,
            fileKind = fileKind,
            arch = arch,
            dpi = dpi,
            isBundle = fileKind != "apk"
        )
    }

    private fun apkMirrorWantedVariantKinds(request: HelperRequest): List<String> {
        val requestedKinds = request.requestedFileKinds
        return DOWNLOAD_FILE_KIND_ORDER
            .filter { it in requestedKinds }
            .ifEmpty { DOWNLOAD_FILE_KIND_ORDER }
    }

    private fun apkMirrorVariantFileKind(type: String): String {
        val normalized = type.lowercase(Locale.US)
        return when {
            "apks" in normalized -> "apks"
            "xapk" in normalized -> "xapk"
            "apkm" in normalized || "bundle" in normalized -> "apkm"
            else -> "apk"
        }
    }

    private fun ApkMirrorVariant.displayLabel(): String? {
        val archLabel = arch?.archDisplayLabel()
        val dpiLabel = dpi
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.takeUnless { it.equals("nodpi", ignoreCase = true) || it.equals("anydpi", ignoreCase = true) }
        return listOfNotNull(archLabel, dpiLabel)
            .joinToString(" / ")
            .takeIf(String::isNotBlank)
    }

    private fun apkMirrorDpiMatches(dpi: String?): Boolean {
        val normalized = dpi?.lowercase(Locale.US)?.takeIf(String::isNotBlank) ?: return true
        return normalized == "nodpi" || normalized == "anydpi"
    }

    private fun apkMirrorDownloadButtonUrl(doc: Document, isBundle: Boolean): String? {
        val urls = (
            doc.select("a.downloadButton[href]").map { it.attr("href") } +
                doc.select("a[href*=/download/?key][href]").map { it.attr("href") }
            )
            .mapNotNull(::apkMirrorAbsoluteUrl)
            .distinct()

        return if (isBundle) {
            urls.firstOrNull { !it.contains("forcebaseapk", ignoreCase = true) } ?: urls.firstOrNull()
        } else {
            urls.firstOrNull { it.contains("forcebaseapk", ignoreCase = true) } ?: urls.firstOrNull()
        }
    }

    private fun apkMirrorFinalDownloadUrl(doc: Document): String? =
        doc.selectFirst("a#download-link[href]")
            ?.attr("href")
            ?.let(::apkMirrorAbsoluteUrl)
            ?: doc.select("a[href]")
                .asSequence()
                .map { it.attr("href") }
                .firstOrNull { href ->
                    href.contains("download.php", ignoreCase = true) ||
                        href.contains("/download/?key=", ignoreCase = true)
                }
                ?.let(::apkMirrorAbsoluteUrl)

    private fun apkMirrorPageLooksBundle(doc: Document): Boolean =
        doc.select("span.apkm-badge, .apkm-badge")
            .any { it.text().contains("bundle", ignoreCase = true) }

    private fun apkMirrorVersionFromReleaseUrl(url: String): String? {
        val slug = runCatching {
            java.net.URI(url).path.trimEnd('/').substringAfterLast('/').removeSuffix("-release")
        }.getOrNull() ?: return null
        return Regex("""\d+(?:[-.]\d+)+(?:[-.](?:alpha|beta|rc)\d*)?""", RegexOption.IGNORE_CASE)
            .findAll(slug)
            .lastOrNull()
            ?.value
            ?.replace("-", ".")
    }

    private fun apkMirrorReleaseUrlMatchesVersion(url: String, version: String): Boolean =
        apkMirrorLooksLikeReleaseUrl(url) &&
            (
                apkMirrorVersionFromReleaseUrl(url).versionNameEquals(version) ||
                    runCatching { java.net.URI(url).path.lowercase(Locale.US) }
                        .getOrDefault(url.lowercase(Locale.US))
                        .contains(version.apkMirrorVersionSlug())
                )

    private fun apkMirrorLooksLikeReleaseUrl(url: String): Boolean =
        runCatching {
            val path = java.net.URI(url).path
            path.startsWith("/apk/") && path.trimEnd('/').endsWith("-release")
        }.getOrDefault(false)

    private fun apkMirrorAbsoluteUrl(url: String): String? {
        val normalized = url.substringBefore("#").trim().replace("&amp;", "&")
        if (normalized.isBlank()) return null
        return when {
            normalized.startsWith("http://", ignoreCase = true) ||
                normalized.startsWith("https://", ignoreCase = true) -> normalized
            normalized.startsWith("//") -> "https:$normalized"
            normalized.startsWith("/") -> "https://www.apkmirror.com$normalized"
            else -> "https://www.apkmirror.com/$normalized"
        }.normalizedHttpUrlOrNull()
    }

    private fun Document.isCloudflareChallenge(): Boolean =
        title().contains("Just a moment", ignoreCase = true) ||
            text().contains("Enable JavaScript and cookies to continue", ignoreCase = true)

    private fun Document.isApkMirrorNoResults(): Boolean =
        select("p").any { it.text().contains("No results found matching your query", ignoreCase = true) }

    private fun Document.apkMirrorMatchesPackage(packageName: String): Boolean {
        val hasPackageId = select("[id]").any { it.id() == packageName }
        val hasPlayPackage = select("a[href]")
            .asSequence()
            .map { it.absUrl("href").ifBlank { it.attr("href") } }
            .any { url ->
                url.contains("play.google.com", ignoreCase = true) &&
                    runCatching { Uri.parse(url).getQueryParameter("id") == packageName }
                        .getOrDefault(false)
            }
        val tablePackage = parseInfoTableValue(this, "Package Name") == packageName
        return hasPackageId || hasPlayPackage || tablePackage
    }

    private fun fetchDocument(url: String, referer: String? = null): Document =
        ctx.document(url, referer)
}
