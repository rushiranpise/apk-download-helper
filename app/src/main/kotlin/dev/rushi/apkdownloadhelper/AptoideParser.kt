package dev.rushi.apkdownloadhelper

import android.net.Uri
import java.net.URLEncoder
import java.util.Locale

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
        return response.datalist.list
            .firstOrNull { it.packageName == request.packageName }
            ?.let(::aptoideAppPageUrl)
    }

    private fun aptoideAppPageUrl(app: AptoideApp): String? =
        listOf(app.urls.w, app.urls.m)
            .firstNotNullOfOrNull(String::normalizedHttpUrlOrNull)
            ?.let { url ->
                val uri = Uri.parse(url)
                "${uri.scheme}://${uri.authority}/app"
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
