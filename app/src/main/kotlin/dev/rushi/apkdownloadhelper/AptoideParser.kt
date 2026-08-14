package dev.rushi.apkdownloadhelper

import java.net.URLEncoder

internal class AptoideParser(private val ctx: SourceParserContext) : ApkSourceParser {
    override val source: DownloadSource = DownloadSource.APTOIDE

    private val aptoideApi: AptoideApi
        get() = ctx.aptoideApi

    override suspend fun findCandidates(
        request: HelperRequest,
        option: CandidateOption
    ): List<DownloadCandidate> = when (option) {
        CandidateOption.REQUESTED -> listOfNotNull(aptoideRequestedCandidate(request))
        CandidateOption.LATEST -> listOfNotNull(aptoideLatestCandidate(request))
        CandidateOption.MANUAL -> emptyList()
    }

    override fun searchUrl(packageName: String): String? = aptoideSearchUrl(packageName)

    override suspend fun resolveHistory(request: HelperRequest): List<DownloadCandidate> {
        val appPageUrl = runCatching { resolveAptoideAppPageUrl(request) }.getOrNull()
            ?: return emptyList()
        val versionsUrl = appPageUrl.trimEnd('/').removeSuffix("/app") + "/versions"
        val versions = aptoideVersionsFromPage(versionsUrl, request.packageName)

        return versions
            .distinctBy(AptoideVersionItem::id)
            .mapNotNull { item ->
                val versionName = item.vername.ifBlank { item.name }.ifBlank { return@mapNotNull null }
                DownloadCandidate(
                    source = DownloadSource.APTOIDE,
                    name = request.appName,
                    packageName = request.packageName,
                    versionName = versionName,
                    versionCode = item.vercode.takeIf { it > 0L },
                    // Synthetic per-version URL; resolveHistoryCandidate re-reads the id.
                    url = "$versionsUrl/${item.id}",
                    fileKind = "apk",
                    option = CandidateOption.LATEST,
                    directDownload = false,
                    versionStatus = request.versionStatus(versionName, item.vercode.takeIf { it > 0L }),
                    formatMatches = request.acceptsFormat("apk"),
                    note = null
                )
            }
            .sortedWith { left, right -> compareVersionNames(right.versionName, left.versionName) }
    }

    override suspend fun resolveHistoryCandidate(
        request: HelperRequest,
        candidate: DownloadCandidate
    ): DownloadCandidate? {
        val versionId = candidate.url.substringAfterLast('/').toLongOrNull() ?: return null
        val app = runCatching { aptoideApi.getAppById(versionId).nodes.meta.data }
            .getOrNull()
            ?.takeIf { it.packageName == request.packageName }
            ?: return null
        val resolved = aptoideCandidateFromApp(request, app, CandidateOption.LATEST) ?: return null

        return if (resolved.versionName == null && candidate.versionName != null) {
            resolved.copy(
                versionName = candidate.versionName,
                versionStatus = request.versionStatus(candidate.versionName, candidate.versionCode)
            )
        } else {
            resolved
        }
    }

    private suspend fun aptoideLatestCandidate(request: HelperRequest): DownloadCandidate? {
        val historyLatest = runCatching { aptoideLatestVersionHistoryCandidate(request) }.getOrNull()
        val appPageUrl = resolveAptoideAppPageUrl(request) ?: return null
        val webLatest = aptoideCandidateFromWebAppPage(request, appPageUrl, CandidateOption.LATEST)

        val versionsUrl = appPageUrl.trimEnd('/').removeSuffix("/app") + "/versions"
        val version = aptoideVersionsFromPage(versionsUrl, request.packageName).firstOrNull()
        val versionsLatest = version?.let {
            val app = aptoideApi.getAppById(it.id).nodes.meta.data
                .takeIf { app -> app.packageName == request.packageName }
                ?: return@let null
            aptoideCandidateFromApp(request, app, CandidateOption.LATEST)
        }

        return listOfNotNull(historyLatest, webLatest, versionsLatest)
            .maxWithOrNull { left, right -> compareVersionNames(left.versionName, right.versionName) }
    }

    private suspend fun aptoideLatestVersionHistoryCandidate(request: HelperRequest): DownloadCandidate? {
        val app = aptoideApi.listAppVersionsByPackage(request.packageName).list
            .filter { it.packageName == request.packageName }
            .maxWithOrNull { left, right ->
                compareVersionNames(left.file.vername, right.file.vername)
            }
            ?.let { version -> aptoideApi.getAppById(version.id).nodes.meta.data }
            ?.takeIf { it.packageName == request.packageName }
            ?: return null
        return aptoideCandidateFromApp(request, app, CandidateOption.LATEST)
    }

    private suspend fun aptoideRequestedCandidate(request: HelperRequest): DownloadCandidate? {
        val requestedTerm = request.versionName ?: request.versionCodeSummary ?: return null
        val queries = listOf(
            "${request.packageName} $requestedTerm",
            "${request.appName} $requestedTerm",
            request.packageName
        ).distinct()

        return queries.firstNotNullOfOrNull { query ->
            val response = aptoideApi.searchApps(
                AptoideSearchRequest(
                    query = query,
                    limit = "25"
                )
            )
            response.datalist.list
                .asSequence()
                .filter { it.packageName == request.packageName }
                .filter { app ->
                    request.matchesRequestedVersion(app.file.vername, app.file.vercode.toLongOrNull())
                }
                .mapNotNull { app ->
                    aptoideCandidateFromApp(
                        request = request,
                        app = app,
                        option = CandidateOption.REQUESTED
                    )
                }
                .firstOrNull()
        } ?: aptoideVersionHistoryCandidate(request)
    }

    private suspend fun aptoideVersionHistoryCandidate(request: HelperRequest): DownloadCandidate? {
        val appPageUrl = runCatching { resolveAptoideAppPageUrl(request) }.getOrNull()
        val appId = appPageUrl?.let { url ->
            runCatching { aptoideAppFromPage(url)?.id }.getOrNull()
        }

        val versions = buildList {
            addAll(runCatching { aptoideApi.listAppVersionsByPackage(request.packageName).list }
                .getOrDefault(emptyList()))
            appId?.let { id ->
                addAll(runCatching { aptoideApi.listAppVersionsById(id).list }
                    .getOrDefault(emptyList()))
            }
        }

        val requestedVersions = versions
            .filter { it.packageName == request.packageName }
            .filter { app -> request.matchesRequestedVersion(app.file.vername, app.file.vercode.toLongOrNull()) }
            .distinctBy(AptoideApp::id)

        for (version in requestedVersions) {
            val app = runCatching { aptoideApi.getAppById(version.id).nodes.meta.data }
                .getOrNull()
                ?.takeIf { it.packageName == request.packageName }
                ?.takeIf { request.matchesRequestedVersion(it.file.vername, it.file.vercode.toLongOrNull()) }
                ?: continue

            aptoideCandidateFromApp(
                    request = request,
                    app = app,
                    option = CandidateOption.REQUESTED
            )?.let { return it }
        }

        val requestedWebVersions = appPageUrl
            ?.let { url ->
                val versionsUrl = url.trimEnd('/').removeSuffix("/app") + "/versions"
                runCatching { aptoideVersionsFromPage(versionsUrl, request.packageName) }
                    .getOrDefault(emptyList())
            }
            .orEmpty()
            .filter { version ->
                request.matchesRequestedVersion(version.vername, version.vercode.takeIf { it > 0L })
            }
            .distinctBy(AptoideVersionItem::id)

        for (version in requestedWebVersions) {
            val app = runCatching { aptoideApi.getAppById(version.id).nodes.meta.data }
                .getOrNull()
                ?.takeIf { it.packageName == request.packageName }
                ?.takeIf { request.matchesRequestedVersion(it.file.vername, it.file.vercode.toLongOrNull()) }
                ?: continue

            aptoideCandidateFromApp(
                request = request,
                app = app,
                option = CandidateOption.REQUESTED
            )?.let { return it }
        }

        return null
    }

    private suspend fun resolveAptoideAppPageUrl(request: HelperRequest): String? {
        runCatching {
            aptoideApi.getAppByPackage(request.packageName).nodes.meta.data
                .takeIf { it.packageName == request.packageName }
                ?.let(::aptoideAppPageUrl)
        }.getOrNull()?.let { return it }

        val response = aptoideApi.searchApps(AptoideSearchRequest(query = request.packageName))
        response.datalist.list
            .firstOrNull { it.packageName == request.packageName }
            ?.let(::aptoideAppPageUrl)
            ?.let { return it }

        // The Aptoide API does not index every listed app (getAppByPackage can 404
        // even though the web page exists, e.g. SCRL at scrl.en.aptoide.com). Fall
        // back to the slug-based web page so the __NEXT_DATA__ parsing can take over.
        return runCatching {
            val slugUrl = "https://${request.appName.slugForUrl()}.en.aptoide.com/app"
            aptoideAppFromPage(slugUrl)
                ?.takeIf { it.packageName == request.packageName }
                ?.let { slugUrl }
        }.getOrNull()
    }

    private fun aptoideAppPageUrl(app: AptoideApp): String? =
        listOf(app.urls.w, app.urls.m)
            .firstNotNullOfOrNull(String::normalizedHttpUrlOrNull)
            ?.let { url ->
                runCatching { java.net.URI(url) }.getOrNull()
                    ?.let { uri -> "${uri.scheme}://${uri.host}/app" }
            }

    private fun aptoideCandidateFromWebAppPage(
        request: HelperRequest,
        appPageUrl: String,
        option: CandidateOption
    ): DownloadCandidate? {
        val app = aptoideAppFromPage(appPageUrl)
            ?.takeIf { it.packageName == request.packageName }
            ?: return null
        return aptoideCandidateFromApp(request, app, option)
    }

    private fun aptoideAppFromPage(appPageUrl: String): AptoideApp? {
        val json = fetchDocument(appPageUrl)
            .selectFirst("script#__NEXT_DATA__")
            ?.data()
            ?.takeIf(String::isNotBlank)
            ?: return null
        return runCatching {
            gson.fromJson(json, AptoideNextData::class.java).props.pageProps.app
        }.getOrNull()
    }

    private fun aptoideVersionsFromPage(versionsUrl: String, packageName: String): List<AptoideVersionItem> {
        val json = fetchDocument(versionsUrl)
            .selectFirst("script#__NEXT_DATA__")
            ?.data()
            ?.takeIf(String::isNotBlank)
            ?: return emptyList()
        return runCatching {
            val page = gson.fromJson(json, AptoideNextData::class.java).props.pageProps
            if (page.packageName.isNotBlank() && page.packageName != packageName) {
                emptyList()
            } else {
                page.versions
            }
        }.getOrDefault(emptyList())
    }

    private fun aptoideCandidateFromApp(
        request: HelperRequest,
        app: AptoideApp,
        option: CandidateOption
    ): DownloadCandidate? {
        val url = listOf(app.file.path, app.file.pathAlt, app.urls.w, app.urls.m)
            .firstNotNullOfOrNull(String::normalizedHttpUrlOrNull)
            ?: return null
        val directDownload = listOf(app.file.path, app.file.pathAlt)
            .any { it.normalizedHttpUrlOrNull() == url }
        val fileKind = "apk"
        val versionCode = app.file.vercode.toLongOrNull()

        return DownloadCandidate(
            source = DownloadSource.APTOIDE,
            name = app.name.ifBlank { request.appName },
            packageName = app.packageName,
            versionName = app.file.vername,
            versionCode = versionCode,
            url = url,
            fileKind = fileKind,
            option = option,
            directDownload = directDownload,
            versionStatus = request.versionStatus(app.file.vername, versionCode),
            formatMatches = request.acceptsFormat(fileKind),
            files = if (directDownload) {
                listOf(
                    CandidateDownloadFile(
                        url = url,
                        fileName = "${app.packageName}-${app.file.vername}-aptoide.apk"
                            .sanitizeFileName()
                    )
                )
            } else {
                emptyList()
            }
        )
    }

    private fun aptoideSearchUrl(packageName: String): String =
        "https://en.aptoide.com/search?query=${URLEncoder.encode(packageName, "UTF-8")}"

    private fun fetchDocument(url: String, referer: String? = null): org.jsoup.nodes.Document =
        ctx.document(url, referer)
}
