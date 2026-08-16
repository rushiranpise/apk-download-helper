package dev.rushi.apkdownloadhelper

/**
 * Mi9's APK Downloader (mi9.com/apk-downloader) and its token API
 * (token.mi9.com / api.mi9.com) sit behind a Cloudflare "Just a moment" JS
 * challenge that plain HTTP clients cannot pass (verified from both desktop
 * and mobile networks). The app still offers a real path: every candidate
 * points the in-app captcha browser at the app's version-history page, which
 * a real browser loads fine  the user picks a version there and the
 * download is captured back into the helper.
 */

internal class Mi9Parser : ApkSourceParser {
    override val source = DownloadSource.MI9

    override fun searchUrl(packageName: String): String? = versionsUrl(packageName)

    override suspend fun findCandidates(
        request: HelperRequest,
        option: CandidateOption
    ): List<DownloadCandidate> = emptyList()

    override fun requestedFallbackCandidate(request: HelperRequest): DownloadCandidate =
        blockedCandidate(request, CandidateOption.REQUESTED, VersionStatus.REQUESTED)

    override fun latestFallbackCandidate(request: HelperRequest): DownloadCandidate =
        blockedCandidate(request, CandidateOption.LATEST, VersionStatus.LATEST)

    /** The version-history page can't be listed over plain HTTP (Cloudflare),
     * so the history tab offers a single "browse in the in-app browser" row
     * that opens the page and captures the download the user picks. */
    override suspend fun resolveHistory(request: HelperRequest): List<DownloadCandidate> {
        val url = versionsUrl(request.packageName)
        return listOf(
            DownloadCandidate(
                source = source,
                name = request.appName,
                packageName = request.packageName,
                versionName = "Version history",
                versionCode = null,
                url = url,
                fileKind = "web",
                option = CandidateOption.LATEST,
                directDownload = false,
                versionStatus = VersionStatus.LATEST,
                formatMatches = true,
                note = MI9_CAPTCHA_NOTE,
                captchaUrl = url
            )
        )
    }

    private fun versionsUrl(packageName: String): String =
        "https://mi9.com/package/$packageName/versions/"

    private fun blockedCandidate(
        request: HelperRequest,
        option: CandidateOption,
        status: VersionStatus
    ): DownloadCandidate {
        val url = versionsUrl(request.packageName)
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
            note = MI9_CAPTCHA_NOTE,
            captchaUrl = url
        )
    }
}

private const val MI9_CAPTCHA_NOTE =
    "Mi9 is Cloudflare-gated. Solve it in the in-app browser to browse the version history, or open the site manually."

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
