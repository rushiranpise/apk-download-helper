package dev.rushi.apkdownloadhelper

import android.content.Context
import android.util.Log
import com.aurora.gplayapi.data.models.App
import com.aurora.gplayapi.data.models.AuthData
import com.aurora.gplayapi.data.models.PlayFile
import com.aurora.gplayapi.helpers.AppDetailsHelper
import com.aurora.gplayapi.helpers.PurchaseHelper
import dev.rushi.apkdownloadhelper.play.NativeDeviceInfoProvider
import dev.rushi.apkdownloadhelper.play.PlayHttpClient

internal class AuroraParser(private val ctx: SourceParserContext) : ApkSourceParser {
    override val source: DownloadSource = DownloadSource.AURORA

    private val playHttpClient: PlayHttpClient
        get() = checkNotNull(ctx.playHttpClient) { "Play client unavailable." }

    private val appContext: Context
        get() = checkNotNull(ctx.appContext) { "App context unavailable." }

    private var playAuthData: AuthData? = null

    override suspend fun findCandidates(
        request: HelperRequest,
        option: CandidateOption
    ): List<DownloadCandidate> = when (option) {
        CandidateOption.LATEST -> findAurora(request)
        CandidateOption.REQUESTED,
        CandidateOption.MANUAL -> emptyList()
    }

    private fun findAurora(request: HelperRequest): List<DownloadCandidate> {
        val auth = playAuth()
        val app = AppDetailsHelper(auth)
            .using(playHttpClient)
            .getAppByPackageName(request.packageName)

        if (app.packageName != request.packageName || app.versionCode <= 0L) return emptyList()

        val playFiles = getPlayInstallFiles(app)
        val files = playFiles
            .mapIndexedNotNull { index, file ->
                val url = file.url.normalizedHttpUrlOrNull()
                if (url == null) {
                    Log.w(TAG, "Aurora returned invalid download URL for ${request.packageName}: ${file.url}")
                    return@mapIndexedNotNull null
                }
                CandidateDownloadFile(
                    url = url,
                    fileName = playFileName(file, index),
                    size = file.size
                )
            }

        if (files.size != playFiles.size) return emptyList()
        if (files.isEmpty()) return emptyList()

        val fileKind = if (files.size > 1) "apks" else "apk"
        return listOf(
            DownloadCandidate(
                source = DownloadSource.AURORA,
                name = app.displayName.ifBlank { request.appName },
                packageName = app.packageName,
                versionName = app.versionName,
                versionCode = app.versionCode,
                url = playStoreUrl(app.packageName),
                fileKind = fileKind,
                option = CandidateOption.LATEST,
                directDownload = true,
                versionStatus = VersionStatus.LATEST,
                formatMatches = request.acceptsFormat(fileKind),
                files = files
            )
        )
    }

    private fun playAuth(): AuthData {
        playAuthData?.let { return it }

        val properties = NativeDeviceInfoProvider(appContext).getNativeDeviceProperties()
        val response = playHttpClient.postAuth(AURORA_AUTH_URL, gson.toJson(properties).toByteArray())
        check(response.isSuccessful) { "Aurora auth failed: HTTP ${response.code}" }

        return gson.fromJson(String(response.responseBytes), AuthData::class.java).also {
            playAuthData = it
        }
    }

    private fun getPlayInstallFiles(app: App): List<PlayFile> =
        PurchaseHelper(playAuth())
            .using(playHttpClient)
            .purchase(app.packageName, app.versionCode, app.offerType)
            .filter { it.type == PlayFile.Type.BASE || it.type == PlayFile.Type.SPLIT }

    private fun playFileName(file: PlayFile, index: Int): String =
        when (file.type) {
            PlayFile.Type.BASE -> "base.apk"
            PlayFile.Type.SPLIT -> "split_$index.apk"
            else -> "file_$index.apk"
        }
}
