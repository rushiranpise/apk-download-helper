package dev.rushi.apkdownloadhelper

/**
 * Mi9's APK Downloader (mi9.com/apk-downloader) and its token API
 * (token.mi9.com / api.mi9.com) sit behind a Cloudflare "Just a moment" JS
 * challenge that plain HTTP clients cannot pass (verified from both desktop
 * and mobile networks). Rather than burning requests on the wall, these
 * sources resolve to an honest "Open link" candidate pointing at the site so
 * the user can download manually in a browser.
 */

internal class Mi9Parser : ApkSourceParser {
    override val source = DownloadSource.MI9

    override fun searchUrl(packageName: String): String? =
        "https://mi9.com/package/$packageName/"

    override suspend fun findCandidates(
        request: HelperRequest,
        option: CandidateOption
    ): List<DownloadCandidate> = emptyList()

    override fun requestedFallbackCandidate(request: HelperRequest): DownloadCandidate =
        blockedCandidate(request, CandidateOption.REQUESTED, VersionStatus.REQUESTED)

    override fun latestFallbackCandidate(request: HelperRequest): DownloadCandidate =
        blockedCandidate(request, CandidateOption.LATEST, VersionStatus.LATEST)

    private fun blockedCandidate(
        request: HelperRequest,
        option: CandidateOption,
        status: VersionStatus
    ): DownloadCandidate = DownloadCandidate(
        source = source,
        name = request.appName,
        packageName = request.packageName,
        versionName = request.versionName.takeIf { option == CandidateOption.REQUESTED },
        versionCode = request.versionCode.takeIf { option == CandidateOption.REQUESTED },
        url = searchUrl(request.packageName) ?: request.fallbackWebUrl,
        fileKind = "web",
        option = option,
        directDownload = false,
        versionStatus = status,
        formatMatches = true,
        note = "Mi9 blocks automated downloads (Cloudflare challenge). Open the site to download manually."
    )
}

internal class ApkDownloaderPagesParser : ApkSourceParser {
    override val source = DownloadSource.APK_DOWNLOADER

    override fun searchUrl(packageName: String): String? =
        "https://apkdownloader.pages.dev/?package=$packageName"

    override suspend fun findCandidates(
        request: HelperRequest,
        option: CandidateOption
    ): List<DownloadCandidate> = emptyList()

    override fun requestedFallbackCandidate(request: HelperRequest): DownloadCandidate =
        blockedCandidate(request, CandidateOption.REQUESTED, VersionStatus.REQUESTED)

    override fun latestFallbackCandidate(request: HelperRequest): DownloadCandidate =
        blockedCandidate(request, CandidateOption.LATEST, VersionStatus.LATEST)

    private fun blockedCandidate(
        request: HelperRequest,
        option: CandidateOption,
        status: VersionStatus
    ): DownloadCandidate = DownloadCandidate(
        source = source,
        name = request.appName,
        packageName = request.packageName,
        versionName = request.versionName.takeIf { option == CandidateOption.REQUESTED },
        versionCode = request.versionCode.takeIf { option == CandidateOption.REQUESTED },
        url = searchUrl(request.packageName) ?: request.fallbackWebUrl,
        fileKind = "web",
        option = option,
        directDownload = false,
        versionStatus = status,
        formatMatches = true,
        note = "APK Downloader fetches files through the Mi9 API, which blocks automated access (Cloudflare challenge). Open the site to download manually."
    )
}
