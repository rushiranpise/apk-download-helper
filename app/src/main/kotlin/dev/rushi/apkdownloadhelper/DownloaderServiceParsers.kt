package dev.rushi.apkdownloadhelper

/**
 * Mi9's APK Downloader (mi9.com/apk-downloader) and its token API
 * (token.mi9.com / api.mi9.com) sit behind a Cloudflare "Just a moment" JS
 * challenge that plain HTTP clients cannot pass (verified from both desktop
 * and mobile networks). The app still offers a real path: every candidate
 * points the in-app captcha browser at the app's version-history page, which
 * a real browser loads fine — the user picks a version there and the
 * download is captured back into the helper.
 */

internal class Mi9Parser : ApkSourceParser {
    override val source = DownloadSource.MI9

    override fun searchUrl(packageName: String): String? =
        "https://mi9.com/package/$packageName/versions/"

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
    ): DownloadCandidate {
        val url = searchUrl(request.packageName) ?: request.fallbackWebUrl
        return DownloadCandidate(
            source = source,
            name = request.appName,
            packageName = request.packageName,
            versionName = request.versionName.takeIf { option == CandidateOption.REQUESTED },
            versionCode = request.versionCode.takeIf { option == CandidateOption.REQUESTED },
            url = url,
            fileKind = "web",
            option = option,
            directDownload = false,
            versionStatus = status,
            formatMatches = true,
            note = "Mi9 is Cloudflare-gated. Solve it in the in-app browser to browse the version history, or open the site manually.",
            captchaUrl = url
        )
    }
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
    ): DownloadCandidate {
        val url = searchUrl(request.packageName) ?: request.fallbackWebUrl
        return DownloadCandidate(
            source = source,
            name = request.appName,
            packageName = request.packageName,
            versionName = request.versionName.takeIf { option == CandidateOption.REQUESTED },
            versionCode = request.versionCode.takeIf { option == CandidateOption.REQUESTED },
            url = url,
            fileKind = "web",
            option = option,
            directDownload = false,
            versionStatus = status,
            formatMatches = true,
            note = "APK Downloader fetches files through the Mi9 API, which blocks automated access (Cloudflare challenge). Solve it in the in-app browser or open the site manually.",
            captchaUrl = url
        )
    }
}
