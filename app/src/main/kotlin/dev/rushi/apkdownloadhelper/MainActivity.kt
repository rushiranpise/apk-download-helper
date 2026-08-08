package dev.rushi.apkdownloadhelper

import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.aurora.gplayapi.data.models.App
import com.aurora.gplayapi.data.models.AuthData
import com.aurora.gplayapi.data.models.PlayFile
import com.aurora.gplayapi.helpers.AppDetailsHelper
import com.aurora.gplayapi.helpers.PurchaseHelper
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import dev.rushi.apkdownloadhelper.play.NativeDeviceInfoProvider
import dev.rushi.apkdownloadhelper.play.PlayHttpClient
import java.io.ByteArrayInputStream
import java.io.File
import java.io.SequenceInputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Query

private const val AURORA_AUTH_URL = "https://auroraoss.com/api/auth"
private const val TAG = "ApkDownloadHelper"
private const val PREFS_NAME = "helper_settings"
private const val TEMP_CLEANUP_DELAY_MS = 5 * 60 * 1000L
private const val TEMP_CLEANUP_MAX_AGE_MS = 6 * 60 * 60 * 1000L
private val DOWNLOAD_FILE_KIND_ORDER = listOf("apk", "apkm", "apks", "xapk")
private val APK_COMBO_FILE_KIND_ORDER = listOf("apk", "xapk", "apks")
private val DOWNLOAD_FILE_KIND_SET = DOWNLOAD_FILE_KIND_ORDER.toSet()
private val SPLIT_ARCHIVE_FILE_KINDS = setOf("apkm", "apks", "xapk")
private val DOWNLOAD_FILE_KIND_REGEX = Regex("""apkm|apks|xapk|apk""", RegexOption.IGNORE_CASE)
private val APK_PICKER_MIME_TYPES = arrayOf(
    "application/vnd.android.package-archive",
    "application/zip",
    "application/octet-stream",
    "*/*"
)

class MainActivity : ComponentActivity() {
    private val browserUserAgent =
        "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", browserUserAgent)
                    .build()
            )
        }
        .build()
    private val apkPureClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "APKPure/3.19.39 (Aegon)")
                    .build()
            )
        }
        .build()

    private val playHttpClient by lazy {
        PlayHttpClient(Cache(File(cacheDir, "play-cache"), 64L * 1024L * 1024L))
    }
    private var playAuthData: AuthData? = null

    private val apkPureApi = Retrofit.Builder()
        .client(apkPureClient)
        .baseUrl("https://tapi.pureapk.com/")
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
        .create(ApkPureApi::class.java)

    private val aptoideApi = Retrofit.Builder()
        .client(client)
        .baseUrl("https://ws75.aptoide.com/api/7/")
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
        .create(AptoideApi::class.java)

    private var request by mutableStateOf<HelperRequest?>(null)
    private var uiState by mutableStateOf<UiState>(UiState.Idle)
    private var helperSettings by mutableStateOf(HelperSettings())
    private var installedPackageRefreshToken by mutableIntStateOf(0)
    private val requestLogs = mutableStateListOf<RequestLogEntry>()
    private val logTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        helperSettings = loadHelperSettings()
        request = HelperRequest.from(intent)
        startRequestLog(request)
        lifecycleScope.launch(Dispatchers.IO) {
            cleanupTemporaryDownloads(helperSettings)
        }

        setContent {
            HelperTheme {
                HelperScreen(
                    request = request,
                    state = uiState,
                    settings = helperSettings,
                    logs = requestLogs,
                    installedPackageRefreshToken = installedPackageRefreshToken,
                    onSettingsChange = ::updateHelperSettings,
                    onRefresh = ::loadCandidates,
                    onResolve = ::resolveCandidates,
                    onDownload = ::downloadAndReturn,
                    onPickDownloadedFile = ::returnPickedFile,
                    onUseInstalledApp = ::returnInstalledApp,
                    onClearLogs = { requestLogs.clear() },
                    onCancel = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    }
                )
            }
        }

        if (request != null) loadCandidates()
    }

    override fun onResume() {
        super.onResume()
        installedPackageRefreshToken++
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        request = HelperRequest.from(intent)
        startRequestLog(request)
        if (request != null) {
            loadCandidates()
        } else {
            uiState = UiState.Idle
        }
        installedPackageRefreshToken++
    }

    private fun loadCandidates() {
        val activeRequest = request ?: return
        uiState = UiState.Ready(initialCandidateResult(activeRequest))
        appendLog("Ready. Manual links prepared for ${DownloadSource.entries.size} sources.")
    }

    private fun resolveCandidates(source: DownloadSource, option: CandidateOption) {
        val activeRequest = request ?: return
        helperSettings.networkPolicy.blockReason(this)?.let { message ->
            appendLog(message, LogLevel.Warning)
            updateResolveState(source, option, ResolveState.Error(message))
            return
        }
        appendLog("Checking ${option.labelForLogs} from ${source.label}.")
        updateResolveState(source, option, ResolveState.Loading)
        lifecycleScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                resolveSourceSection(activeRequest, source, option)
            }
            logResolveOutcome(source, option, resolved)

            updateResolveState(
                source = source,
                option = option,
                state = resolved.errorMessage
                    ?.takeIf { resolved.candidates.isEmpty() }
                    ?.let(ResolveState::Error)
                    ?: ResolveState.Done(resolved.candidates)
            )
        }
    }

    private fun updateResolveState(
        source: DownloadSource,
        option: CandidateOption,
        state: ResolveState
    ) {
        val activeRequest = request ?: return
        val current = (uiState as? UiState.Ready)?.result ?: initialCandidateResult(activeRequest)
        uiState = UiState.Ready(current.withResolveState(source, option, state))
    }

    private fun startRequestLog(request: HelperRequest?) {
        requestLogs.clear()
        if (request == null) {
            appendLog("Opened without a Morphe request.", LogLevel.Warning)
        } else {
            appendLog(
                "Request for ${request.appName} (${request.packageName}), " +
                    "version ${request.versionName ?: "any compatible"}, " +
                    "build ${request.versionCodeSummary ?: "any"}, format ${request.requestedFormatLabel}."
            )
        }
    }

    private fun logResolveOutcome(
        source: DownloadSource,
        option: CandidateOption,
        outcome: ResolveOutcome
    ) {
        when {
            outcome.errorMessage != null && outcome.candidates.isEmpty() -> {
                appendLog("${source.label} ${option.labelForLogs} failed: ${outcome.errorMessage}", LogLevel.Error)
            }
            outcome.candidates.isEmpty() -> {
                appendLog("${source.label} ${option.labelForLogs} found no candidates.", LogLevel.Warning)
            }
            else -> {
                val summary = outcome.candidates.joinToString(limit = 3, truncated = "…") { candidate ->
                    candidate.versionDisplay
                }
                appendLog("${source.label} ${option.labelForLogs} found ${outcome.candidates.size}: $summary")
            }
        }
    }

    private fun appendLog(message: String, level: LogLevel = LogLevel.Info) {
        val timestamp = synchronized(logTimeFormat) { logTimeFormat.format(Date()) }
        requestLogs += RequestLogEntry(timestamp, level, message)
        while (requestLogs.size > 200) {
            requestLogs.removeAt(0)
        }
    }

    private fun updateHelperSettings(settings: HelperSettings) {
        helperSettings = settings
        saveHelperSettings(settings)
        lifecycleScope.launch(Dispatchers.IO) {
            cleanupTemporaryDownloads(settings)
        }
    }

    private fun initialCandidateResult(request: HelperRequest): CandidateResult {
        val manual = manualCandidates(request)
        return CandidateResult(
            sourceGroups = DownloadSource.entries.map { source ->
                SourceCandidateGroup(
                    source = source,
                    manual = manual.filter { it.source == source },
                    recommended = ResolveState.Idle,
                    latest = ResolveState.Idle
                )
            }
        )
    }

    private suspend fun resolveSourceSection(
        request: HelperRequest,
        source: DownloadSource,
        option: CandidateOption
    ): ResolveOutcome {
        val lookup = runCatching { findSourceCandidates(request, source, option) }
            .onFailure { Log.w(TAG, "${source.label} ${option.name.lowercase(Locale.US)} lookup failed", it) }
        val sourceCandidates = lookup
            .getOrDefault(emptyList())
            .distinctBy(DownloadCandidate::identityKey)

        val candidates = when (option) {
            CandidateOption.REQUESTED -> recommendedCandidatesForSource(request, source, sourceCandidates)
            CandidateOption.LATEST -> latestCandidatesForSource(request, source, sourceCandidates)
            CandidateOption.MANUAL -> emptyList()
        }

        return ResolveOutcome(
            candidates = candidates,
            errorMessage = lookup.exceptionOrNull()?.let { sourceFailureMessage(source, it) }
        )
    }

    private suspend fun findSourceCandidates(
        request: HelperRequest,
        source: DownloadSource,
        option: CandidateOption
    ): List<DownloadCandidate> = when (source) {
        DownloadSource.APK_MIRROR -> when (option) {
            CandidateOption.REQUESTED -> findApkMirrorRequested(request)
            CandidateOption.LATEST -> findApkMirrorLatest(request)
            CandidateOption.MANUAL -> emptyList()
        }
        DownloadSource.UPTODOWN -> findUptodown(request, option)
        DownloadSource.APK_PURE -> findApkPure(request, option)
        DownloadSource.APK_COMBO -> findApkCombo(request, option)
        DownloadSource.APTOIDE -> when (option) {
            CandidateOption.REQUESTED -> listOfNotNull(aptoideRequestedCandidate(request))
            CandidateOption.LATEST -> listOfNotNull(aptoideLatestCandidate(request))
            CandidateOption.MANUAL -> emptyList()
        }
        DownloadSource.AURORA -> when (option) {
            CandidateOption.LATEST -> findAurora(request)
            CandidateOption.REQUESTED,
            CandidateOption.MANUAL -> emptyList()
        }
        DownloadSource.PLAY -> emptyList()
    }

    private fun recommendedCandidatesForSource(
        request: HelperRequest,
        source: DownloadSource,
        candidates: List<DownloadCandidate>
    ): List<DownloadCandidate> {
        if (!request.hasRequestedVersionRequest || source == DownloadSource.AURORA || source == DownloadSource.PLAY) {
            return emptyList()
        }

        return buildList {
            addAll(candidates.filter(request::isRequestedMatch))
            if (source == DownloadSource.APK_MIRROR && none { it.option == CandidateOption.REQUESTED }) {
                add(apkMirrorRequested(request))
            }
        }
            .distinctBy(DownloadCandidate::identityKey)
            .sortedBy { it.sortIndex }
    }

    private fun latestCandidatesForSource(
        request: HelperRequest,
        source: DownloadSource,
        candidates: List<DownloadCandidate>
    ): List<DownloadCandidate> {
        if (source == DownloadSource.PLAY) {
            return listOf(playStoreCandidate(request))
        }

        return buildList {
            addAll(candidates.filter { it.option == CandidateOption.LATEST })
            if (source == DownloadSource.APK_MIRROR && none { it.option == CandidateOption.LATEST }) {
                add(apkMirrorLatest(request))
            }
            if (none { it.option == CandidateOption.LATEST }) {
                latestWebFallback(request, source)?.let(::add)
            }
        }
            .distinctBy(DownloadCandidate::identityKey)
            .sortedBy { it.sortIndex }
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
        val searchUrl = apkMirrorPackageSearchUrl(request.packageName)
        val latestInfo = runCatching { resolveApkMirrorLatestInfo(request, searchUrl) }
            .onFailure { Log.w(TAG, "APKMirror latest resolve failed", it) }
            .getOrNull()

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
        ) ?: return emptyList()
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

    private fun manualCandidates(request: HelperRequest): List<DownloadCandidate> =
        manualSourceUrls(request).map { (source, url) ->
            DownloadCandidate(
                source = source,
                name = request.appName,
                packageName = request.packageName,
                versionName = null,
                versionCode = null,
                url = url,
                fileKind = "web",
                option = CandidateOption.MANUAL,
                directDownload = false,
                versionStatus = VersionStatus.LATEST,
                formatMatches = true
            )
        }.sortedBy { it.sortIndex }

    private fun latestWebFallback(request: HelperRequest, source: DownloadSource): DownloadCandidate? =
        manualSourceUrls(request)
            .firstOrNull { (candidateSource, _) -> candidateSource == source }
            ?.let { (_, url) ->
                DownloadCandidate(
                    source = source,
                    name = request.appName,
                    packageName = request.packageName,
                    versionName = null,
                    versionCode = null,
                    url = url,
                    fileKind = "web",
                    option = CandidateOption.LATEST,
                    directDownload = false,
                    versionStatus = VersionStatus.LATEST,
                    formatMatches = true
                )
            }

    private fun manualSourceUrls(request: HelperRequest): List<Pair<DownloadSource, String>> =
        listOf(
            DownloadSource.APK_MIRROR to apkMirrorPackageSearchUrl(request.packageName),
            DownloadSource.UPTODOWN to uptodownSearchUrl(request.packageName),
            DownloadSource.APK_PURE to apkPureInfoUrl(request.packageName),
            DownloadSource.APK_COMBO to apkComboSearchUrl(request.packageName),
            DownloadSource.APTOIDE to aptoideSearchUrl(request.packageName)
        )

    private fun playStoreCandidate(request: HelperRequest) = DownloadCandidate(
        source = DownloadSource.PLAY,
        name = request.appName,
        packageName = request.packageName,
        versionName = null,
        versionCode = null,
        url = playStoreUrl(request.packageName),
        fileKind = "web",
        option = CandidateOption.LATEST,
        directDownload = false,
        versionStatus = VersionStatus.LATEST,
        formatMatches = true
    )

    private fun resolveApkMirrorLatestInfo(
        request: HelperRequest,
        searchUrl: String
    ): ApkMirrorLatestInfo? {
        val searchDoc = fetchDocument(searchUrl)
        val appPageUrl = resolveApkMirrorAppPage(
            searchDoc = searchDoc,
            request = request
        ) ?: return ApkMirrorLatestInfo(
            versionName = null,
            openUrl = searchUrl
        )
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
        for (candidate in apkMirrorAppPageCandidates(searchDoc, request)) {
            val candidateDoc = runCatching { fetchDocument(candidate) }.getOrNull() ?: continue
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
        val appSlug = runCatching {
            java.net.URI(url).path.trim('/').split('/').getOrNull(2).orEmpty()
        }.getOrDefault("")
        return when {
            appSlug in expectedSlugs -> 0
            expectedSlugs.any { appSlug.endsWith(it) } -> 1
            expectedSlugs.any { appSlug.contains(it) } -> 2
            else -> 3
        }
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
            val doc = runCatching { fetchDocument(pageUrl, referer = appPageUrl) }
                .onFailure { Log.w(TAG, "APKMirror uploads page resolve failed: $pageUrl", it) }
                .getOrNull()
                ?: continue

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
        if (!request.acceptsFormat(fileKind)) return null
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
        return acceptedVariants.filter { apkMirrorDpiMatches(it.dpi) }
            .takeIf(List<ApkMirrorVariant>::isNotEmpty)
            ?: acceptedVariants
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

        val properties = NativeDeviceInfoProvider(this).getNativeDeviceProperties()
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

    private fun findApkCombo(request: HelperRequest, option: CandidateOption): List<DownloadCandidate> =
        when (option) {
            CandidateOption.REQUESTED -> apkComboRequestedCandidates(request)
            CandidateOption.LATEST -> apkComboLatestCandidates(request)
            CandidateOption.MANUAL -> emptyList()
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

        return doc.select("a.variant[href]")
            .mapNotNull { variant ->
                apkComboCandidateFromVariant(
                    request = request,
                    pageUrl = pageUrl,
                    option = option,
                    versionName = versionName,
                    checkIn = checkIn,
                    variant = variant
                )
            }
            .distinctBy(DownloadCandidate::identityKey)
    }

    private fun apkComboCandidateFromVariant(
        request: HelperRequest,
        pageUrl: String,
        option: CandidateOption,
        versionName: String?,
        checkIn: String,
        variant: Element
    ): DownloadCandidate? {
        val href = variant.absUrl("href").ifBlank { "https://apkcombo.com${variant.attr("href")}" }
        val downloadUrl = (if (checkIn.isNotBlank()) "$href&$checkIn" else href)
            .normalizedHttpUrlOrNull()
            ?: return null
        val versionCode = apkComboVersionCode(href)
        val fileKind = fileKindFromUrl(href)
        if (!request.acceptsFormat(fileKind)) return null
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
                    referer = pageUrl
                )
            )
        )
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

    private fun findUptodown(request: HelperRequest, option: CandidateOption): List<DownloadCandidate> {
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
        val externalUrl = doc.selectFirst("#detail-download-button[data-url-ext]")?.attr("data-url-ext")
            ?.normalizedHttpUrlOrNull()
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

    private fun String.archDisplayLabel(): String =
        if (isUniversalArchLabel()) {
            "Universal"
        } else {
            trim()
        }

    private fun String.isUniversalArchLabel(): Boolean {
        val normalizedLabel = lowercase(Locale.US)
        return "all architectures" in normalizedLabel ||
            "universal" in normalizedLabel ||
            normalizedLabel == "all" ||
            normalizedLabel == "noarch"
    }

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

    private suspend fun findApkPure(request: HelperRequest, option: CandidateOption): List<DownloadCandidate> =
        when (option) {
            CandidateOption.REQUESTED -> listOfNotNull(apkPureRequestedCandidate(request))
            CandidateOption.LATEST -> apkPureLatestCandidates(request)
            CandidateOption.MANUAL -> emptyList()
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
            .mapNotNull { item ->
                val url = item.asset.url.replace("http://", "https://").normalizedHttpUrlOrNull()
                    ?: return@mapNotNull null
                val fileKind = if (url.contains("/XAPK", ignoreCase = true)) "xapk" else "apk"

                DownloadCandidate(
                    source = DownloadSource.APK_PURE,
                    name = item.label.ifBlank { request.appName },
                    packageName = item.package_name,
                    versionName = item.version_name,
                    versionCode = item.version_code,
                    url = url,
                    fileKind = fileKind,
                    option = CandidateOption.LATEST,
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

    private fun apkPureRequestedCandidate(request: HelperRequest): DownloadCandidate? {
        val appPageUrl = runCatching { apkPureAppPageUrl(request) }
            .onFailure { Log.w(TAG, "APKPure app page resolve failed", it) }
            .getOrNull()
            ?: return null

        return runCatching { apkPureRequestedCandidate(request, appPageUrl) }
            .onFailure { Log.w(TAG, "APKPure requested version resolve failed", it) }
            .getOrNull()
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

    private fun fetchDocument(url: String, referer: String? = null): Document =
        Jsoup.parse(fetchText(url, referer), url)

    private fun fetchText(url: String, referer: String? = null): String {
        val builder = Request.Builder().url(url)
        referer?.let { builder.header("Referer", it) }
        client.newCall(builder.build()).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            return response.body.string()
        }
    }

    private fun downloadAndReturn(candidate: DownloadCandidate) {
        val activeRequest = request ?: return
        val settings = helperSettings
        settings.networkPolicy.blockReason(this)?.let { message ->
            appendLog(message, LogLevel.Warning)
            uiState = UiState.Error(message)
            return
        }
        appendLog(
            "Downloading ${candidate.source.label} ${candidate.option.labelForLogs} " +
                "${candidate.versionDisplay} (${candidate.fileKind.uppercase(Locale.US)})."
        )
        uiState = UiState.Downloading(candidate, 0)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val downloadsDir = temporaryDownloadsDir().apply { mkdirs() }
                    val files = candidate.files.ifEmpty {
                        listOf(
                            CandidateDownloadFile(
                                url = candidate.url,
                                fileName = "${candidate.packageName}-${candidate.versionName ?: "latest"}.${candidate.fileKind}"
                                    .sanitizeFileName()
                            )
                        )
                    }

                    val file = if (files.size == 1) {
                        downloadSingleFile(candidate, files.single(), downloadsDir)
                    } else {
                        downloadSplitArchive(candidate, files, downloadsDir)
                    }
                    validateDownloadedArtifact(activeRequest, candidate, file)
                    file
                }
            }

            result
                .onSuccess { file ->
                    appendLog("Download validated: ${file.name} (${file.length()} bytes).")
                    runCatching {
                        returnDownloadedFile(activeRequest, candidate, file, settings)
                    }.onFailure { error ->
                        val message = (error.message ?: "Could not return downloaded APK to Morphe.")
                            .withManualModeHint()
                        appendLog(message, LogLevel.Error)
                        uiState = UiState.Error(message)
                    }
                }
                .onFailure { error ->
                    val message = downloadFailureMessage(candidate, error)
                    appendLog(message, LogLevel.Error)
                    uiState = UiState.Error(message)
                }
        }
    }

    private fun returnPickedFile(candidate: DownloadCandidate, uri: Uri?) {
        val activeRequest = request ?: return
        val settings = helperSettings

        if (uri == null) {
            appendLog("File selection canceled.", LogLevel.Warning)
            return
        }

        appendLog("Checking manually selected file for ${candidate.source.label}.")
        uiState = UiState.CheckingPickedFile(candidate)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = copyPickedFileToTemporary(candidate, uri)
                    validateDownloadedArtifact(activeRequest, candidate, file)
                    file
                }
            }

            result
                .onSuccess { file ->
                    appendLog("Selected file validated: ${file.name} (${file.length()} bytes).")
                    runCatching {
                        returnDownloadedFile(activeRequest, candidate, file, settings)
                    }.onFailure { error ->
                        val message = (error.message ?: "Could not return selected APK to Morphe.")
                            .withManualModeHint()
                        appendLog(message, LogLevel.Error)
                        uiState = UiState.Error(message)
                    }
                }
                .onFailure { error ->
                    val message = (error.message ?: "Selected file could not be used.")
                        .withManualModeHint()
                    appendLog(message, LogLevel.Error)
                    uiState = UiState.Error(message)
                }
        }
    }

    private fun returnInstalledApp(candidate: DownloadCandidate) {
        val activeRequest = request ?: return
        if (!isPackageInstalled(candidate.packageName)) {
            appendLog("${candidate.packageName} is not installed yet.", LogLevel.Warning)
            installedPackageRefreshToken++
            return
        }

        val result = Intent().apply {
            putExtra(DownloadHelperContract.EXTRA_RESULT_USE_INSTALLED_APP, true)
            putExtra(DownloadHelperContract.EXTRA_RESULT_PACKAGE_NAME, candidate.packageName)
            putExtra(DownloadHelperContract.EXTRA_RESULT_VERSION_NAME, candidate.versionName)
            putExtra(DownloadHelperContract.EXTRA_RESULT_SOURCE_NAME, candidate.source.label)
        }

        setResult(Activity.RESULT_OK, result)
        appendLog("Returned installed ${candidate.packageName} to ${activeRequest.callerPackage}.")
        finish()
    }

    private fun copyPickedFileToTemporary(candidate: DownloadCandidate, uri: Uri): File {
        val displayName = displayNameForUri(uri)
        val extension = displayName
            ?.substringAfterLast('.', "")
            ?.takeIf { it.isNotBlank() && it != displayName }
            ?: candidate.fileKind.takeUnless { it.equals("web", ignoreCase = true) }
            ?: request?.requestedFileKinds?.orderedFileKinds()?.firstOrNull()
            ?: "apk"
        val outputName = displayName
            ?.takeIf(String::isNotBlank)
            ?: "${candidate.packageName}-${candidate.versionName ?: "manual"}.$extension"
        val outputFile = temporaryDownloadsDir().apply { mkdirs() }.uniqueChild(outputName)

        try {
            val bytesCopied = contentResolver.openInputStream(uri)?.use { input ->
                outputFile.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Selected file could not be opened.")
            check(bytesCopied > 0L) { "Selected file was empty." }
            return outputFile
        } catch (error: Throwable) {
            outputFile.delete()
            throw error
        }
    }

    private fun displayNameForUri(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
            ?.takeIf(String::isNotBlank)

    private fun downloadSingleFile(
        candidate: DownloadCandidate,
        downloadFile: CandidateDownloadFile,
        downloadsDir: File
    ): File {
        val outputName = if (candidate.source == DownloadSource.AURORA) {
            "${candidate.packageName}-${candidate.versionName ?: "latest"}-aurora.apk"
        } else {
            downloadFile.fileName
        }
        val outputFile = File(downloadsDir, outputName.sanitizeFileName())
        executeDownload(downloadFile) { total, input ->
            outputFile.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    updateDownloadProgress(candidate, copied, total)
                }
            }
        }
        return outputFile
    }

    private fun downloadSplitArchive(
        candidate: DownloadCandidate,
        files: List<CandidateDownloadFile>,
        downloadsDir: File
    ): File {
        val outputFile = File(
            downloadsDir,
            "${candidate.packageName}-${candidate.versionName ?: "latest"}-${candidate.source.label}.apks"
                .sanitizeFileName()
        )
        val knownTotal = files.mapNotNull { it.size }.sum().takeIf { it > 0L }
        var copied = 0L

        ZipOutputStream(outputFile.outputStream()).use { zip ->
            files.forEachIndexed { index, file ->
                executeDownload(file) { _, input ->
                    zip.putNextEntry(ZipEntry(file.fileName.ifBlank { "split_$index.apk" }.sanitizeFileName()))
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        zip.write(buffer, 0, read)
                        copied += read
                        knownTotal?.let { updateDownloadProgress(candidate, copied, it) }
                    }
                    zip.closeEntry()
                }
            }
        }

        return outputFile
    }

    private fun executeDownload(
        file: CandidateDownloadFile,
        copy: (total: Long, input: java.io.InputStream) -> Unit
    ) {
        val url = file.url.normalizedHttpUrlOrNull()
            ?: error("Source returned an invalid download URL.".withManualModeHint())
        val builder = Request.Builder().url(url)
        file.referer?.let { builder.header("Referer", it) }

        downloadClientFor(url).newCall(builder.build()).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val total = file.size ?: response.body.contentLength().coerceAtLeast(0L)
            val contentType = response.body.contentType()?.toString()
            response.body.byteStream().use { input ->
                validateApkLikeStream(input, contentType).use { validated ->
                    copy(total, validated)
                }
            }
        }
    }

    private fun downloadClientFor(url: String): OkHttpClient =
        if (url.contains("apkpure", ignoreCase = true)) apkPureClient else client

    private fun updateDownloadProgress(candidate: DownloadCandidate, copied: Long, total: Long) {
        if (total <= 0L) return
        val percent = ((copied * 100f) / total).roundToInt().coerceIn(0, 100)
        runOnUiThread {
            uiState = UiState.Downloading(candidate, percent)
        }
    }

    private fun returnDownloadedFile(
        request: HelperRequest,
        candidate: DownloadCandidate,
        file: File,
        settings: HelperSettings
    ) {
        val uri = when (settings.downloadLocation) {
            DownloadLocation.TEMPORARY -> FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.files", file)
            DownloadLocation.DOWNLOADS -> copyToDownloads(file)
        }
        grantUriPermission(request.callerPackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val result = Intent().apply {
            data = uri
            clipData = ClipData.newUri(contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(DownloadHelperContract.EXTRA_RESULT_PACKAGE_NAME, candidate.packageName)
            putExtra(DownloadHelperContract.EXTRA_RESULT_VERSION_NAME, candidate.versionName)
            putExtra(DownloadHelperContract.EXTRA_RESULT_SOURCE_NAME, candidate.source.label)
            putExtra(DownloadHelperContract.EXTRA_RESULT_FILE_NAME, file.name)
        }

        setResult(Activity.RESULT_OK, result)
        appendLog("Returned ${file.name} to ${request.callerPackage}.")
        if (
            settings.downloadLocation == DownloadLocation.DOWNLOADS ||
            settings.deleteTemporaryAfterHandoff
        ) {
            scheduleTemporaryDelete(file)
        }
        finish()
    }

    private fun temporaryDownloadsDir(): File = File(cacheDir, "downloads")

    private fun copyToDownloads(file: File): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, file.mimeType())
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/APK Download Helper")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create a Downloads entry.")

            try {
                contentResolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Could not write to Downloads.")

                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                uri
            } catch (error: Throwable) {
                contentResolver.delete(uri, null, null)
                throw error
            }
        } else {
            val downloadsDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "APK Download Helper"
            ).apply { mkdirs() }
            val output = downloadsDir.uniqueChild(file.name)
            file.copyTo(output, overwrite = false)
            FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.files", output)
        }
    }

    private fun cleanupTemporaryDownloads(settings: HelperSettings) {
        if (!settings.deleteTemporaryAfterHandoff) return
        val cutoff = System.currentTimeMillis() - TEMP_CLEANUP_MAX_AGE_MS
        temporaryDownloadsDir()
            .listFiles()
            ?.filter { it.isFile && it.lastModified() < cutoff }
            ?.forEach { file -> runCatching { file.delete() } }
    }

    private fun scheduleTemporaryDelete(file: File) {
        Thread {
            Thread.sleep(TEMP_CLEANUP_DELAY_MS)
            runCatching { file.delete() }
        }.apply {
            name = "apk-helper-temp-cleanup"
            isDaemon = true
            start()
        }
    }

    private fun validateDownloadedArtifact(
        request: HelperRequest,
        candidate: DownloadCandidate,
        file: File
    ) {
        val shouldValidateMetadata = candidate.fileKind.lowercase(Locale.US) in setOf("apk", "apks", "apkm", "xapk") ||
            file.extension.lowercase(Locale.US) in setOf("apk", "apks", "apkm", "xapk")
        val metadata = readDownloadedApkMetadata(file) ?: run {
            check(!shouldValidateMetadata) {
                file.delete()
                "Downloaded file could not be read as an APK.".withManualModeHint()
            }
            return
        }
        val mismatches = buildList {
            if (metadata.packageName != request.packageName) {
                add("Package: requested ${request.packageName}, found ${metadata.packageName}")
            }

            if (candidate.option == CandidateOption.REQUESTED) {
                val requestedNames = request.knownVersionNames
                if (
                    requestedNames.isNotEmpty() &&
                    requestedNames.none { metadata.versionName.versionNameEquals(it) }
                ) {
                    add(
                        "Version: requested ${requestedNames.joinToString()}, " +
                            "found ${metadata.versionName ?: "unknown"}"
                    )
                }

                val requestedCodes = request.requestedVersionCodes +
                    request.compatibleVersionCodes.filter { it > 0L }
                if (
                    requestedCodes.isNotEmpty() &&
                    metadata.versionCode !in requestedCodes
                ) {
                    add(
                        "Version code: requested ${requestedCodes.joinToString()}, " +
                            "found ${metadata.versionCode ?: "unknown"}"
                    )
                }
            }
        }

        check(mismatches.isEmpty()) {
            file.delete()
            "Downloaded file does not match Morphe request.\n${mismatches.joinToString("\n")}"
                .withManualModeHint()
        }
    }
}

private data class HelperSettings(
    val downloadLocation: DownloadLocation = DownloadLocation.TEMPORARY,
    val networkPolicy: NetworkPolicy = NetworkPolicy.WIFI_AND_MOBILE,
    val deleteTemporaryAfterHandoff: Boolean = true
)

private enum class DownloadLocation(
    val title: String,
    val description: String
) {
    TEMPORARY(
        title = "Hand off only",
        description = "Keep the file in Helper's cache, send it to Morphe, then clean it up later."
    ),
    DOWNLOADS(
        title = "Keep a copy",
        description = "Save a visible copy in Downloads/APK Download Helper after the file checks out."
    )
}

private enum class NetworkPolicy(
    val title: String,
    val description: String
) {
    WIFI_ONLY(
        title = "Wi-Fi only",
        description = "Use Wi-Fi for source checks and downloads."
    ),
    MOBILE_DATA_ONLY(
        title = "Mobile data only",
        description = "Use cellular data and pause when Wi-Fi is active."
    ),
    WIFI_AND_MOBILE(
        title = "Wi-Fi or mobile data",
        description = "Use whichever connection Android is already using."
    );

    fun blockReason(context: Context): String? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
            ?: return "Network status is unavailable. Change Helper settings or try Manual mode."
        val activeNetwork = connectivity.activeNetwork
            ?: return "No active network is available. Connect to an allowed network or use Manual mode."
        val capabilities = connectivity.getNetworkCapabilities(activeNetwork)
            ?: return "Network status is unavailable. Change Helper settings or try Manual mode."
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return "The active network has no internet access. Connect to another network or use Manual mode."
        }

        return when (this) {
            WIFI_ONLY -> if (connectivity.isActiveNetworkMetered) {
                "Helper is set to Wi-Fi only. Connect to Wi-Fi or change Helper settings."
            } else {
                null
            }
            MOBILE_DATA_ONLY -> if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                "Helper is set to mobile data only. Switch to mobile data or change Helper settings."
            } else {
                null
            }
            WIFI_AND_MOBILE -> null
        }
    }
}

private fun Context.loadHelperSettings(): HelperSettings {
    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return HelperSettings(
        downloadLocation = enumValueOrDefault(
            prefs.getString("download_location", null),
            DownloadLocation.TEMPORARY
        ),
        networkPolicy = enumValueOrDefault(
            prefs.getString("network_policy", null),
            NetworkPolicy.WIFI_AND_MOBILE
        ),
        deleteTemporaryAfterHandoff = prefs.getBoolean("delete_temporary_after_handoff", true)
    )
}

private fun Context.saveHelperSettings(settings: HelperSettings) {
    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString("download_location", settings.downloadLocation.name)
        .putString("network_policy", settings.networkPolicy.name)
        .putBoolean("delete_temporary_after_handoff", settings.deleteTemporaryAfterHandoff)
        .apply()
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String?, fallback: T): T =
    name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

private fun File.mimeType(): String = when (extension.lowercase(Locale.US)) {
    "apk" -> "application/vnd.android.package-archive"
    "apks",
    "apkm",
    "xapk" -> "application/zip"
    else -> "application/octet-stream"
}

private fun File.uniqueChild(fileName: String): File {
    val safeName = fileName.sanitizeFileName()
    val base = safeName.substringBeforeLast('.', safeName)
    val extension = safeName.substringAfterLast('.', "")
        .takeIf { it != safeName && it.isNotBlank() }
        ?.let { ".$it" }
        .orEmpty()
    var candidate = File(this, safeName)
    var index = 1
    while (candidate.exists()) {
        candidate = File(this, "$base ($index)$extension")
        index++
    }
    return candidate
}

private object HelperDefaults {
    val CardCornerRadius = 16.dp
    val CompactCornerRadius = 12.dp
    val SectionCornerRadius = 18.dp
    val ButtonCornerRadius = 16.dp
    val ContentPadding = 16.dp
    val ContentPaddingSmall = 8.dp
    val ItemSpacing = 12.dp
    val IconSizeSmall = 20.dp
    val ButtonHeight = 52.dp
}

@Composable
private fun HelperTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFA4C9FF),
            onPrimary = Color(0xFF00315D),
            primaryContainer = Color(0xFF004884),
            onPrimaryContainer = Color(0xFFD4E3FF),
            secondary = Color(0xFFBCC7DB),
            onSecondary = Color(0xFF263141),
            secondaryContainer = Color(0xFF3D4758),
            onSecondaryContainer = Color(0xFFD8E3F8),
            tertiary = Color(0xFFD9BDE3),
            onTertiary = Color(0xFF3D2946),
            tertiaryContainer = Color(0xFF543F5E),
            onTertiaryContainer = Color(0xFFF6D9FF),
            error = Color(0xFFFFB4AB),
            errorContainer = Color(0xFF93000A),
            onError = Color(0xFF690005),
            onErrorContainer = Color(0xFFFFDAD6),
            background = Color(0xFF0B0C10),
            onBackground = Color(0xFFE3E2E6),
            surface = Color(0xFF1A1C1E),
            onSurface = Color(0xFFE3E2E6),
            surfaceVariant = Color(0xFF43474E),
            onSurfaceVariant = Color(0xFFC3C6CF),
            outline = Color(0xFF8D9199),
            outlineVariant = Color(0xFF43474E),
        ),
        content = content
    )
}

@Composable
private fun HelperCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = HelperDefaults.CardCornerRadius,
    color: Color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(cornerRadius),
        color = color,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 2.dp,
        shadowElevation = 0.dp
    ) {
        content()
    }
}

@Composable
private fun HelperButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    val primary = MaterialTheme.colorScheme.primary
    Button(
        onClick = onClick,
        modifier = modifier.height(HelperDefaults.ButtonHeight),
        shape = RoundedCornerShape(HelperDefaults.ButtonCornerRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = primary.copy(alpha = 0.28f),
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        border = BorderStroke(1.dp, primary.copy(alpha = 0.48f)),
        contentPadding = PaddingValues(horizontal = HelperDefaults.ContentPadding)
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                modifier = Modifier.size(HelperDefaults.IconSizeSmall)
            )
            Spacer(Modifier.width(HelperDefaults.ContentPaddingSmall))
        }
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HelperOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    val primary = MaterialTheme.colorScheme.primary
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(HelperDefaults.ButtonHeight),
        shape = RoundedCornerShape(HelperDefaults.ButtonCornerRadius),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
        ),
        border = BorderStroke(1.dp, primary.copy(alpha = 0.32f)),
        contentPadding = PaddingValues(horizontal = HelperDefaults.ContentPadding)
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                modifier = Modifier.size(HelperDefaults.IconSizeSmall)
            )
            Spacer(Modifier.width(HelperDefaults.ContentPaddingSmall))
        }
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HelperScreen(
    request: HelperRequest?,
    state: UiState,
    settings: HelperSettings,
    logs: List<RequestLogEntry>,
    installedPackageRefreshToken: Int,
    onSettingsChange: (HelperSettings) -> Unit,
    onRefresh: () -> Unit,
    onResolve: (DownloadSource, CandidateOption) -> Unit,
    onDownload: (DownloadCandidate) -> Unit,
    onPickDownloadedFile: (DownloadCandidate, Uri?) -> Unit,
    onUseInstalledApp: (DownloadCandidate) -> Unit,
    onClearLogs: () -> Unit,
    onCancel: () -> Unit
) {
    var showLogs by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var pendingFilePick by remember { mutableStateOf<DownloadCandidate?>(null) }
    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val candidate = pendingFilePick
        pendingFilePick = null
        candidate?.let { onPickDownloadedFile(it, uri) }
    }
    val openDownloadedFilePicker: (DownloadCandidate) -> Unit = { candidate ->
        pendingFilePick = candidate
        filePickerLauncher.launch(APK_PICKER_MIME_TYPES)
    }
    BackHandler(enabled = showSettings) {
        showSettings = false
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (showSettings) {
            HelperSettingsScreen(
                settings = settings,
                onSettingsChange = onSettingsChange,
                onBack = { showSettings = false }
            )
            return@Surface
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = HelperDefaults.ContentPadding, vertical = HelperDefaults.ContentPadding),
            verticalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "APK Download Helper",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f)
                    )
                    HelperOutlinedButton(
                        text = "Settings",
                        onClick = { showSettings = true },
                        icon = Icons.Outlined.Settings,
                        modifier = Modifier.widthIn(min = 132.dp)
                    )
                }
            }

            if (request == null) {
                item { EmptyLaunchState() }
                return@LazyColumn
            }

            item {
                AppInfoCard(request)
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing)
                ) {
                    HelperOutlinedButton(
                        text = if (showLogs) "Hide logs" else "Logs",
                        onClick = { showLogs = !showLogs },
                        modifier = Modifier.weight(1f)
                    )
                    HelperOutlinedButton(
                        text = "Refresh",
                        onClick = onRefresh,
                        icon = Icons.Outlined.Refresh,
                        modifier = Modifier.weight(1f)
                    )
                    HelperOutlinedButton(
                        text = "Cancel",
                        onClick = onCancel,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (showLogs) {
                item {
                    RequestLogsCard(
                        logs = logs,
                        onClearLogs = onClearLogs
                    )
                }
            }

            when (state) {
                UiState.Idle,
                UiState.Loading -> item { LoadingState() }

                is UiState.Ready -> {
                    item {
                        SourceTabs(
                            request = request,
                            result = state.result,
                            onResolve = onResolve,
                            onDownload = onDownload,
                            onPickDownloadedFile = openDownloadedFilePicker,
                            onUseInstalledApp = onUseInstalledApp,
                            installedPackageRefreshToken = installedPackageRefreshToken
                        )
                    }
                }

                is UiState.CheckingPickedFile -> item { CheckingPickedFileState(state) }
                is UiState.Downloading -> item { DownloadingState(state) }
                is UiState.Error -> item {
                    ErrorState(message = state.message, onRefresh = onRefresh, onCancel = onCancel)
                }
            }
        }
    }
}

@Composable
private fun HelperSettingsScreen(
    settings: HelperSettings,
    onSettingsChange: (HelperSettings) -> Unit,
    onBack: () -> Unit
) {
    val swipeThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = HelperDefaults.ContentPadding, vertical = HelperDefaults.ContentPadding)
            .pointerInput(swipeThresholdPx) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onHorizontalDrag = { _, dragAmount ->
                        totalDrag += dragAmount
                    },
                    onDragEnd = {
                        if (totalDrag >= swipeThresholdPx) onBack()
                    },
                    onDragCancel = { totalDrag = 0f }
                )
            },
        verticalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HelperOutlinedButton(
                    text = "Back",
                    onClick = onBack,
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    modifier = Modifier.widthIn(min = 112.dp)
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Version ${BuildConfig.VERSION_NAME}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            HelperSettingsCard(
                settings = settings,
                onSettingsChange = onSettingsChange
            )
        }
    }
}

@Composable
private fun HelperSettingsCard(
    settings: HelperSettings,
    onSettingsChange: (HelperSettings) -> Unit
) {
    HelperCard(cornerRadius = HelperDefaults.SectionCornerRadius) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(HelperDefaults.ContentPadding),
            verticalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing)
        ) {
            SettingsGroupTitle("Save downloads")
            DownloadLocation.entries.forEach { location ->
                SettingsChoiceRow(
                    title = location.title,
                    description = location.description,
                    selected = settings.downloadLocation == location,
                    onClick = {
                        onSettingsChange(settings.copy(downloadLocation = location))
                    }
                )
            }

            SettingsGroupTitle("Connection")
            NetworkPolicy.entries.forEach { policy ->
                SettingsChoiceRow(
                    title = policy.title,
                    description = policy.description,
                    selected = settings.networkPolicy == policy,
                    onClick = {
                        onSettingsChange(settings.copy(networkPolicy = policy))
                    }
                )
            }

            TemporaryCleanupRow(
                checked = settings.deleteTemporaryAfterHandoff,
                onCheckedChange = {
                    onSettingsChange(settings.copy(deleteTemporaryAfterHandoff = it))
                }
            )
        }
    }
}

@Composable
private fun SettingsGroupTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun SettingsChoiceRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(HelperDefaults.CompactCornerRadius)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = if (selected) colors.primary.copy(alpha = 0.18f) else colors.surfaceColorAtElevation(2.dp),
        contentColor = colors.onSurface,
        border = BorderStroke(
            1.dp,
            if (selected) colors.primary.copy(alpha = 0.54f) else colors.outlineVariant.copy(alpha = 0.45f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(HelperDefaults.ContentPadding),
            horizontalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(
                    description,
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (selected) {
                HelperChip(text = "Selected", tone = ChipTone.Success)
            }
        }
    }
}

@Composable
private fun TemporaryCleanupRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val shape = RoundedCornerShape(HelperDefaults.CompactCornerRadius)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(HelperDefaults.ContentPadding),
            horizontalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Clean up hand-off files", fontWeight = FontWeight.Bold)
                Text(
                    "Remove temporary APKs after Morphe gets them, and clear old cache files on launch.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

@Composable
private fun EmptyLaunchState() {
    InfoCard("Open this helper from Morphe Manager when it asks for an original APK.")
}

@Composable
private fun AppInfoCard(request: HelperRequest) {
    HelperCard(cornerRadius = HelperDefaults.SectionCornerRadius) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(HelperDefaults.ContentPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "App info",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            AppInfoRow(label = "App name", value = request.appName)
            AppInfoRow(label = "Package name", value = request.packageName)
            AppInfoRow(label = "Version", value = request.requestedVersionName ?: "Any compatible")
            AppInfoRow(label = "Version code", value = request.versionCodeSummary ?: "Any")
            AppInfoRow(label = "Format", value = request.requestedFormatLabel)
            AppInfoRow(label = "Device ABI", value = request.abiSummary)

            if (request.stockInstallRequired) {
                Text(
                    text = "Root mount may require the stock app before Morphe patches it.",
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun AppInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f)
        )
        Text(
            text = value,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.58f)
        )
    }
}

@Composable
private fun LoadingState() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun SourceTabs(
    request: HelperRequest,
    result: CandidateResult,
    onResolve: (DownloadSource, CandidateOption) -> Unit,
    onDownload: (DownloadCandidate) -> Unit,
    onPickDownloadedFile: (DownloadCandidate) -> Unit,
    onUseInstalledApp: (DownloadCandidate) -> Unit,
    installedPackageRefreshToken: Int
) {
    val groups = result.sourceGroups

    var selectedIndex by remember(groups.map { it.source }) { mutableIntStateOf(0) }
    val safeSelectedIndex = selectedIndex.coerceIn(0, groups.lastIndex)
    val selectedGroup = groups[safeSelectedIndex]
    val swipeThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SourceSelector(
            groups = groups,
            selectedIndex = safeSelectedIndex,
            onSelect = { selectedIndex = it }
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(groups.size, safeSelectedIndex, swipeThresholdPx) {
                    if (groups.size < 2) return@pointerInput
                    var totalDrag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { totalDrag = 0f },
                        onHorizontalDrag = { _, dragAmount ->
                            totalDrag += dragAmount
                        },
                        onDragEnd = {
                            if (abs(totalDrag) >= swipeThresholdPx) {
                                selectedIndex = if (totalDrag < 0) {
                                    (safeSelectedIndex + 1).coerceAtMost(groups.lastIndex)
                                } else {
                                    (safeSelectedIndex - 1).coerceAtLeast(0)
                                }
                            }
                        },
                        onDragCancel = { totalDrag = 0f }
                    )
                },
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (selectedGroup.manual.isNotEmpty()) {
                SectionHeader("Manual")
                selectedGroup.manual.forEach { candidate ->
                    CandidateCard(
                        request = request,
                        candidate = candidate,
                        onDownload = { onDownload(candidate) },
                        onPickDownloadedFile = { onPickDownloadedFile(candidate) },
                        onUseInstalledApp = { onUseInstalledApp(candidate) },
                        installedPackageRefreshToken = installedPackageRefreshToken
                    )
                }
            }

            if (request.hasKnownVersionRequest) {
                when (selectedGroup.source) {
                    DownloadSource.AURORA -> {
                        InfoCard("Aurora only provides the latest Play Store version. Use Manual mode if you need a specific version.")
                    }
                    DownloadSource.PLAY -> {
                        InfoCard("Play opens the official Play Store listing for this app. Use Manual mode if you need a specific version.")
                    }
                    else -> {
                        SectionHeader("Recommended")
                        CandidateResolveSection(
                            request = request,
                            state = selectedGroup.recommended,
                            actionText = "Find recommended",
                            loadingText = "Checking recommended version...",
                            emptyText = "Requested version was not found on this source. Use Manual mode for this source instead.",
                            onResolve = {
                                onResolve(selectedGroup.source, CandidateOption.REQUESTED)
                            },
                            onDownload = onDownload,
                            onPickDownloadedFile = onPickDownloadedFile,
                            onUseInstalledApp = onUseInstalledApp,
                            installedPackageRefreshToken = installedPackageRefreshToken
                        )
                    }
                }
            }

            SectionHeader("Latest")
            CandidateResolveSection(
                request = request,
                state = selectedGroup.latest,
                actionText = "Find latest",
                loadingText = "Checking latest version...",
                emptyText = "Latest version was not found on this source. Use Manual mode for this source instead.",
                onResolve = {
                    onResolve(selectedGroup.source, CandidateOption.LATEST)
                },
                onDownload = onDownload,
                onPickDownloadedFile = onPickDownloadedFile,
                onUseInstalledApp = onUseInstalledApp,
                installedPackageRefreshToken = installedPackageRefreshToken
            )
        }
    }
}

@Composable
private fun CandidateResolveSection(
    request: HelperRequest,
    state: ResolveState,
    actionText: String,
    loadingText: String,
    emptyText: String,
    onResolve: () -> Unit,
    onDownload: (DownloadCandidate) -> Unit,
    onPickDownloadedFile: (DownloadCandidate) -> Unit,
    onUseInstalledApp: (DownloadCandidate) -> Unit,
    installedPackageRefreshToken: Int
) {
    when (state) {
        ResolveState.Idle -> {
            HelperOutlinedButton(
                text = actionText,
                onClick = onResolve,
                icon = Icons.Outlined.Search,
                modifier = Modifier.fillMaxWidth()
            )
        }

        ResolveState.Loading -> {
            HelperCard(cornerRadius = HelperDefaults.CompactCornerRadius) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(HelperDefaults.ContentPadding),
                    horizontalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text(loadingText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        is ResolveState.Done -> {
            if (state.candidates.isEmpty()) {
                InfoCard(emptyText)
            } else {
                state.candidates.forEach { candidate ->
                    CandidateCard(
                        request = request,
                        candidate = candidate,
                        onDownload = { onDownload(candidate) },
                        onPickDownloadedFile = { onPickDownloadedFile(candidate) },
                        onUseInstalledApp = { onUseInstalledApp(candidate) },
                        installedPackageRefreshToken = installedPackageRefreshToken
                    )
                }
            }
        }

        is ResolveState.Error -> {
            InfoCard(state.message)
            HelperOutlinedButton(
                text = "Try again",
                onClick = onResolve,
                icon = Icons.Outlined.Refresh,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SourceSelector(
    groups: List<SourceCandidateGroup>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(HelperDefaults.ContentPaddingSmall)
    ) {
        groups.forEachIndexed { index, group ->
            SourcePill(
                text = group.source.label,
                selected = index == selectedIndex,
                onClick = { onSelect(index) }
            )
        }
    }
}

@Composable
private fun SourcePill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(50)
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .height(44.dp)
            .widthIn(min = 92.dp)
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = if (selected) colors.primary.copy(alpha = 0.24f) else colors.surfaceColorAtElevation(3.dp),
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
        border = BorderStroke(
            1.dp,
            if (selected) colors.primary.copy(alpha = 0.6f) else colors.outlineVariant.copy(alpha = 0.55f)
        )
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = HelperDefaults.ContentPadding)
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground
    )
}

@Composable
private fun InfoCard(text: String) {
    HelperCard(
        cornerRadius = HelperDefaults.CompactCornerRadius,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.44f)
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(HelperDefaults.ContentPadding)
        )
    }
}

@Composable
private fun RequestLogsCard(
    logs: List<RequestLogEntry>,
    onClearLogs: () -> Unit
) {
    HelperCard(cornerRadius = HelperDefaults.SectionCornerRadius) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(HelperDefaults.ContentPadding),
            verticalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Request logs",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                HelperOutlinedButton(
                    text = "Clear",
                    onClick = onClearLogs,
                    modifier = Modifier.widthIn(min = 96.dp)
                )
            }

            if (logs.isEmpty()) {
                Text(
                    text = "No logs yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        logs.takeLast(80).forEach { entry ->
                            Text(
                                text = "${entry.time} ${entry.level.badge} ${entry.message}",
                                color = entry.level.color(),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateCard(
    request: HelperRequest,
    candidate: DownloadCandidate,
    onDownload: () -> Unit,
    onPickDownloadedFile: () -> Unit,
    onUseInstalledApp: () -> Unit,
    installedPackageRefreshToken: Int
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val match = candidate.matchSummary(request)
    val hasResolvedCandidateInfo = candidate.versionName != null ||
        candidate.versionCode != null ||
        !candidate.fileKind.equals("web", ignoreCase = true)
    var hasOpenedLink by remember(candidate.identityKey()) { mutableStateOf(false) }
    val showUseInstalledApp = candidate.source == DownloadSource.PLAY &&
        hasOpenedLink &&
        remember(candidate.packageName, hasOpenedLink, installedPackageRefreshToken) {
            context.isPackageInstalled(candidate.packageName)
        }

    HelperCard(cornerRadius = HelperDefaults.SectionCornerRadius) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(HelperDefaults.ContentPadding),
            verticalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing)
        ) {
            if (candidate.option != CandidateOption.MANUAL && hasResolvedCandidateInfo) {
                CandidateInfoChips(request, candidate)
            }
            if (candidate.option != CandidateOption.MANUAL && hasResolvedCandidateInfo && !match.matches) {
                CandidateMatchBox(match)
            }
            candidate.note?.let { note ->
                InfoCard(note)
            }

            if (candidate.directDownload) {
                HelperButton(
                    text = "Download and return",
                    onClick = onDownload,
                    icon = Icons.Outlined.Download,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                HelperOutlinedButton(
                    text = "Open link",
                    onClick = {
                        if (candidate.source == DownloadSource.PLAY) {
                            context.openPlayStoreListing(candidate.packageName, candidate.url)
                        } else {
                            uriHandler.openUri(candidate.url)
                        }
                        hasOpenedLink = true
                    },
                    icon = Icons.Outlined.OpenInBrowser,
                    modifier = Modifier.fillMaxWidth()
                )
                if (candidate.source.supportsManualArtifactPicker && hasOpenedLink) {
                    HelperButton(
                        text = "Select downloaded file",
                        onClick = onPickDownloadedFile,
                        icon = Icons.Outlined.FolderOpen,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (showUseInstalledApp) {
                    HelperButton(
                        text = "Use installed app",
                        onClick = onUseInstalledApp,
                        icon = Icons.Outlined.CheckCircle,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun CandidateInfoChips(request: HelperRequest, candidate: DownloadCandidate) {
    val requestedVersionNames = request.requestedVersionNames
    val requestedVersionCodes = request.requestedVersionCodes
    val versionTone = when {
        requestedVersionNames.isEmpty() -> ChipTone.Success
        candidate.versionName != null && requestedVersionNames.any { candidate.versionName.versionNameEquals(it) } -> {
            ChipTone.Success
        }
        else -> ChipTone.Error
    }
    val versionCodeTone = when {
        requestedVersionCodes.isEmpty() -> ChipTone.Success
        candidate.versionCode in requestedVersionCodes -> ChipTone.Success
        else -> ChipTone.Error
    }
    val formatTone = when {
        candidate.fileKind.equals("web", ignoreCase = true) -> ChipTone.Neutral
        request.acceptsFormat(candidate.fileKind) -> ChipTone.Success
        else -> ChipTone.Error
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(HelperDefaults.ContentPaddingSmall),
        verticalArrangement = Arrangement.spacedBy(HelperDefaults.ContentPaddingSmall)
    ) {
        candidate.versionName?.let {
            HelperChip(text = "Version $it", tone = versionTone)
        }
        if (candidate.versionCode != null) {
            HelperChip(text = "Code ${candidate.versionCode}", tone = versionCodeTone)
        }
        if (candidate.versionName == null && candidate.versionCode == null) {
            HelperChip(text = candidate.versionDisplay, tone = versionTone)
        }
        if (!candidate.fileKind.equals("web", ignoreCase = true)) {
            HelperChip(text = candidate.fileKind.uppercase(), tone = formatTone)
        }
        candidate.variantLabel?.let { label ->
            HelperChip(text = label, tone = ChipTone.Neutral)
        }
    }
}

@Composable
private fun CandidateMatchBox(match: CandidateMatchSummary) {
    val containerColor = if (match.matches) {
        Color(0xFF12382D)
    } else {
        Color(0xFF432023)
    }
    val contentColor = if (match.matches) {
        Color(0xFF79DEAF)
    } else {
        Color(0xFFFFB3AC)
    }

    HelperCard(
        cornerRadius = HelperDefaults.CompactCornerRadius,
        color = containerColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(HelperDefaults.ItemSpacing),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(match.title, color = contentColor, fontWeight = FontWeight.Bold)
            match.details.forEach { detail ->
                Text(
                    text = detail,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun HelperChip(text: String, tone: ChipTone = ChipTone.Neutral) {
    val colors = MaterialTheme.colorScheme
    val containerColor = when (tone) {
        ChipTone.Neutral -> colors.surfaceVariant.copy(alpha = 0.42f)
        ChipTone.Success -> Color(0xFF12382D)
        ChipTone.Error -> Color(0xFF432023)
    }
    val contentColor = when (tone) {
        ChipTone.Neutral -> colors.onSurfaceVariant
        ChipTone.Success -> Color(0xFF79DEAF)
        ChipTone.Error -> Color(0xFFFFB3AC)
    }
    val borderColor = when (tone) {
        ChipTone.Neutral -> colors.outlineVariant.copy(alpha = 0.5f)
        ChipTone.Success -> Color(0xFF79DEAF).copy(alpha = 0.34f)
        ChipTone.Error -> Color(0xFFFFB3AC).copy(alpha = 0.34f)
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = HelperDefaults.ItemSpacing, vertical = 7.dp)
        )
    }
}

private enum class ChipTone {
    Neutral,
    Success,
    Error
}

@Composable
private fun CheckingPickedFileState(state: UiState.CheckingPickedFile) {
    HelperCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(HelperDefaults.ContentPadding),
            horizontalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Checking selected file")
                Text(
                    text = state.candidate.source.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun DownloadingState(state: UiState.Downloading) {
    HelperCard {
        Column(
            modifier = Modifier.padding(HelperDefaults.ContentPadding),
            verticalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing)
        ) {
        Text("Downloading from ${state.candidate.source.label}")
        LinearProgressIndicator(
            progress = { state.percent / 100f },
            modifier = Modifier.fillMaxWidth()
        )
        Text("${state.percent}%")
        }
    }
}

@Composable
private fun ErrorState(message: String, onRefresh: () -> Unit, onCancel: () -> Unit) {
    HelperCard {
        Column(
            modifier = Modifier.padding(HelperDefaults.ContentPadding),
            verticalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing)
        ) {
        Text(text = message, color = MaterialTheme.colorScheme.error)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing)
        ) {
            HelperButton(
                text = "Retry",
                onClick = onRefresh,
                modifier = Modifier.weight(1f)
            )
            HelperOutlinedButton(
                text = "Cancel",
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            )
        }
        }
    }
}

private data class HelperRequest(
    val callerPackage: String,
    val packageName: String,
    val appName: String,
    val versionName: String?,
    val versionCode: Long?,
    val versionCodes: Set<Long>,
    val compatibleVersionNames: Set<String>,
    val compatibleVersionCodes: Set<Long>,
    val supportedAbis: List<String>,
    val requestedFileType: String?,
    val allowSplitArchive: Boolean,
    val stockInstallRequired: Boolean,
    val fallbackWebUrl: String,
    val sourceHintUrls: List<String>
) {
    val availableAbis: List<String>
        get() = supportedAbis
            .ifEmpty { Build.SUPPORTED_ABIS.toList() }
            .mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .distinct()

    val requestedVersionName: String?
        get() = versionName
            ?.withoutTrailingVersionCode()
            ?.takeIf(String::isNotBlank)

    val embeddedVersionCode: Long?
        get() = versionName?.trailingVersionCode()

    val abiSummary: String
        get() = availableAbis.joinToString().ifBlank { "Default" }

    val requestedFileKinds: Set<String>
        get() = requestedFileKindsFrom(requestedFileType, allowSplitArchive)

    val requestedFormatLabel: String
        get() = requestedFileKinds
            .orderedFileKinds()
            .joinToString("/") { it.uppercase(Locale.US) }

    val versionCodeSummary: String?
        get() = when {
            requestedVersionCodes.isEmpty() -> null
            requestedVersionCodes.size == 1 -> requestedVersionCodes.first().toString()
            else -> requestedVersionCodes.joinToString(limit = 3, truncated = "+${requestedVersionCodes.size - 3} more")
        }

    val requestedVersionCodes: Set<Long>
        get() = buildSet {
            versionCode?.takeIf { it > 0L }?.let(::add)
            embeddedVersionCode?.takeIf { it > 0L }?.let(::add)
            addAll(versionCodes.filter { it > 0L })
        }

    val knownVersionNames: List<String>
        get() = (listOfNotNull(requestedVersionName) + compatibleVersionNames.map { it.withoutTrailingVersionCode() })
            .mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .distinctBy { it.normalizedVersionName() }

    val requestedVersionNames: List<String>
        get() = listOfNotNull(requestedVersionName)
            .mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .distinctBy { it.normalizedVersionName() }

    val hasRequestedVersionRequest: Boolean
        get() = requestedVersionName != null || requestedVersionCodes.isNotEmpty()

    val hasKnownVersionRequest: Boolean
        get() = requestedVersionName != null ||
            requestedVersionCodes.isNotEmpty() ||
            compatibleVersionNames.isNotEmpty() ||
            compatibleVersionCodes.any { it > 0L }

    val requestedVersionLabel: String
        get() = listOfNotNull(
            requestedVersionName,
            versionCodeSummary?.let { "build $it" }
        ).joinToString(" ").ifBlank { "any compatible version" }

    fun isRequestedMatch(candidate: DownloadCandidate): Boolean =
        matchesRequestedVersion(candidate.versionName, candidate.versionCode)

    fun versionStatus(candidateVersionName: String?, candidateVersionCode: Long?): VersionStatus {
        if (matchesRequestedVersion(candidateVersionName, candidateVersionCode)) return VersionStatus.REQUESTED

        val compatibleName = candidateVersionName != null &&
            compatibleVersionNames.any { candidateVersionName.versionNameEquals(it.withoutTrailingVersionCode()) }
        val compatibleCode = candidateVersionCode != null &&
            candidateVersionCode > 0L &&
            candidateVersionCode in compatibleVersionCodes
        return if (compatibleName || compatibleCode) VersionStatus.COMPATIBLE else VersionStatus.LATEST
    }

    fun acceptsFormat(fileKind: String): Boolean {
        val kind = fileKind.lowercase(Locale.US)
        return kind in requestedFileKinds
    }

    fun matchesKnownVersion(candidateVersionName: String?, candidateVersionCode: Long?): Boolean =
        if (!hasKnownVersionRequest) {
            false
        } else {
            matchesRequestedVersion(candidateVersionName, candidateVersionCode) ||
                (
                    candidateVersionName != null &&
                        compatibleVersionNames.any { candidateVersionName.versionNameEquals(it.withoutTrailingVersionCode()) }
                    ) ||
                (
                    candidateVersionCode != null &&
                        candidateVersionCode > 0L &&
                        candidateVersionCode in compatibleVersionCodes
                    )
        }

    fun matchesRequestedVersion(candidateVersionName: String?, candidateVersionCode: Long?): Boolean {
        val requestedCodes = requestedVersionCodes
        if (versionName == null && requestedCodes.isEmpty()) return false

        val nameMatches = requestedVersionName != null && candidateVersionName.versionNameEquals(requestedVersionName)
        val codeMatches = requestedCodes.isNotEmpty() &&
            candidateVersionCode != null &&
            candidateVersionCode > 0L &&
            candidateVersionCode in requestedCodes
        return nameMatches || codeMatches
    }

    fun sourceHintUrlsFor(source: DownloadSource): List<String> {
        val needles = when (source) {
            DownloadSource.AURORA,
            DownloadSource.PLAY -> listOf("play.google.com")
            DownloadSource.APK_MIRROR -> listOf("apkmirror.com", "google.com/search")
            DownloadSource.APK_COMBO -> listOf("apkcombo.com")
            DownloadSource.APTOIDE -> listOf("aptoide.com")
            DownloadSource.APK_PURE -> listOf("apkpure.com")
            DownloadSource.UPTODOWN -> listOf("uptodown.com")
        }

        return (sourceHintUrls + fallbackWebUrl).distinct().filter { url ->
            needles.any { needle -> url.contains(needle, ignoreCase = true) }
        }
    }

    companion object {
        fun from(intent: Intent): HelperRequest? {
            if (intent.action != DownloadHelperContract.ACTION_DOWNLOAD_ORIGINAL_APK) return null
            val packageName = intent.getStringExtra(DownloadHelperContract.EXTRA_PACKAGE_NAME)
                ?.takeIf { it.isNotBlank() }
                ?: return null

            return HelperRequest(
                callerPackage = intent.getStringExtra(DownloadHelperContract.EXTRA_CALLER_PACKAGE)
                    ?: "app.morphe.manager",
                packageName = packageName,
                appName = intent.getStringExtra(DownloadHelperContract.EXTRA_APP_NAME) ?: packageName,
                versionName = intent.getStringExtra(DownloadHelperContract.EXTRA_VERSION_NAME),
                versionCode = if (intent.hasExtra(DownloadHelperContract.EXTRA_VERSION_CODE)) {
                    intent.getLongExtra(DownloadHelperContract.EXTRA_VERSION_CODE, 0L)
                        .takeIf { it > 0L }
                } else {
                    null
                },
                versionCodes = intent
                    .getLongArrayExtra(DownloadHelperContract.EXTRA_VERSION_CODES)
                    ?.filter { it > 0L }
                    ?.toSet()
                    .orEmpty(),
                compatibleVersionNames = intent
                    .getStringArrayListExtra(DownloadHelperContract.EXTRA_COMPATIBLE_VERSION_NAMES)
                    ?.toSet()
                    .orEmpty(),
                compatibleVersionCodes = intent
                    .getLongArrayExtra(DownloadHelperContract.EXTRA_COMPATIBLE_VERSION_CODES)
                    ?.filter { it > 0L }
                    ?.toSet()
                    .orEmpty(),
                supportedAbis = intent
                    .getStringArrayExtra(DownloadHelperContract.EXTRA_SUPPORTED_ABIS)
                    ?.toList()
                    .orEmpty(),
                requestedFileType = intent.getStringExtra(DownloadHelperContract.EXTRA_FILE_TYPE)
                    ?: intent.getStringExtra(DownloadHelperContract.EXTRA_REQUESTED_FILE_TYPE),
                allowSplitArchive = intent.getBooleanExtra(DownloadHelperContract.EXTRA_ALLOW_SPLIT_ARCHIVE, false),
                stockInstallRequired = intent.getBooleanExtra(
                    DownloadHelperContract.EXTRA_STOCK_INSTALL_REQUIRED,
                    intent.getBooleanExtra(DownloadHelperContract.EXTRA_INSTALL_STOCK_AFTER_DOWNLOAD, false)
                ),
                fallbackWebUrl = intent.getStringExtra(DownloadHelperContract.EXTRA_FALLBACK_WEB_URL)
                    ?: "https://www.apkmirror.com/?post_type=app_release&searchtype=app&s=$packageName",
                sourceHintUrls = intent
                    .getStringArrayListExtra(DownloadHelperContract.EXTRA_SOURCE_HINT_URLS)
                    .orEmpty()
            )
        }
    }
}

private data class CandidateResult(
    val sourceGroups: List<SourceCandidateGroup>
) {
    fun withResolveState(
        source: DownloadSource,
        option: CandidateOption,
        state: ResolveState
    ): CandidateResult = copy(
        sourceGroups = sourceGroups.map { group ->
            if (group.source != source) {
                group
            } else {
                when (option) {
                    CandidateOption.REQUESTED -> group.copy(recommended = state)
                    CandidateOption.LATEST -> group.copy(latest = state)
                    CandidateOption.MANUAL -> group
                }
            }
        }
    )
}

private data class SourceCandidateGroup(
    val source: DownloadSource,
    val manual: List<DownloadCandidate>,
    val recommended: ResolveState,
    val latest: ResolveState
)

private data class ResolveOutcome(
    val candidates: List<DownloadCandidate>,
    val errorMessage: String? = null
)

private sealed interface ResolveState {
    data object Idle : ResolveState
    data object Loading : ResolveState
    data class Done(val candidates: List<DownloadCandidate>) : ResolveState
    data class Error(val message: String) : ResolveState
}

private data class ApkMirrorLatestInfo(
    val versionName: String?,
    val openUrl: String
)

private data class ApkMirrorVariant(
    val url: String,
    val type: String,
    val fileKind: String,
    val arch: String?,
    val dpi: String?,
    val isBundle: Boolean
)

private data class UptodownVersionResponse(
    val data: List<UptodownVersionEntry> = emptyList()
)

private data class UptodownVersionEntry(
    @SerializedName("fileID")
    val fileId: Long? = null,
    val version: String? = null,
    @SerializedName("kindFile")
    val kindFile: String? = null,
    @SerializedName("titleKindFile")
    val titleKindFile: String? = null,
    @SerializedName("versionURL")
    val versionUrl: UptodownVersionUrl? = null
)

private data class UptodownVersionUrl(
    val url: String? = null,
    @SerializedName("extraURL")
    val extraUrl: String? = null,
    @SerializedName("versionID")
    val versionId: Long? = null
)

private data class UptodownVariantResponse(
    val content: String? = null
)

private data class UptodownVariantFile(
    val fileId: String,
    val fileKind: String,
    val archLabel: String?
)

private data class DownloadCandidate(
    val source: DownloadSource,
    val name: String,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long?,
    val url: String,
    val fileKind: String,
    val option: CandidateOption,
    val directDownload: Boolean,
    val versionStatus: VersionStatus,
    val formatMatches: Boolean,
    val note: String? = null,
    val variantLabel: String? = null,
    val files: List<CandidateDownloadFile> = emptyList()
) {
    val sortIndex: Int get() = source.sortIndex
    val versionDisplay: String
        get() = when {
            versionName != null && versionCode != null -> "$versionName ($versionCode)"
            versionName != null -> versionName
            option == CandidateOption.MANUAL -> "Manual"
            option == CandidateOption.REQUESTED -> "Requested search"
            else -> "Latest"
        }
}

private data class CandidateMatchSummary(
    val matches: Boolean,
    val title: String,
    val details: List<String>
)

private fun DownloadCandidate.matchSummary(request: HelperRequest): CandidateMatchSummary {
    val requestedVersionNames = request.requestedVersionNames
    val versionNameMatches = requestedVersionNames.isEmpty() ||
        (versionName != null && requestedVersionNames.any { versionName.versionNameEquals(it) })
    val requestedVersionCodes = request.requestedVersionCodes
    val versionCodeMatches = requestedVersionCodes.isEmpty() ||
        versionCode == null ||
        versionCode in requestedVersionCodes
    val formatMatches = fileKind.equals("web", ignoreCase = true) || request.acceptsFormat(fileKind)

    val mismatchNames = buildList {
        if (!versionNameMatches) add("Version")
        if (!versionCodeMatches) add("Version code")
        if (!formatMatches) add("Format")
    }
    val details = buildList {
        if (!versionNameMatches) {
            add("Version: requested ${requestedVersionNames.joinToString().ifBlank { "Any" }}, found ${versionName ?: "Unknown"}")
        }
        if (!versionCodeMatches) {
            add("Version code: requested ${requestedVersionCodes.joinToString()}, found $versionCode")
        }
        if (!formatMatches) {
            add("Format: requested ${request.requestedFormatLabel}, found ${fileKind.uppercase()}")
        }
    }
    val matches = mismatchNames.isEmpty()

    return CandidateMatchSummary(
        matches = matches,
        title = if (matches) {
            "Same as recommended version"
        } else {
            "${mismatchNames.joinToString(" / ")} mismatch"
        },
        details = details
    )
}

private fun DownloadCandidate.identityKey(): String =
    "${source.name}:$versionName:$versionCode:$fileKind:$variantLabel:$url"

private data class CandidateDownloadFile(
    val url: String,
    val fileName: String,
    val size: Long? = null,
    val referer: String? = null
)

private data class DownloadedApkMetadata(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long?
)

private enum class DownloadSource(
    val label: String,
    val sortIndex: Int,
    val supportsManualArtifactPicker: Boolean = true
) {
    APK_MIRROR("APKMirror", 0),
    UPTODOWN("Uptodown", 1),
    APK_PURE("APKPure", 2),
    APK_COMBO("APKCombo", 3),
    APTOIDE("Aptoide", 4),
    AURORA("Aurora", 5, supportsManualArtifactPicker = false),
    PLAY("Play", 6, supportsManualArtifactPicker = false)
}

private enum class CandidateOption {
    MANUAL,
    REQUESTED,
    LATEST
}

private val CandidateOption.labelForLogs: String
    get() = when (this) {
        CandidateOption.MANUAL -> "manual"
        CandidateOption.REQUESTED -> "recommended"
        CandidateOption.LATEST -> "latest"
    }

private enum class VersionStatus(val label: String) {
    REQUESTED("Requested"),
    COMPATIBLE("Compatible"),
    LATEST("Latest")
}

private data class RequestLogEntry(
    val time: String,
    val level: LogLevel,
    val message: String
)

private enum class LogLevel(val badge: String) {
    Info("I"),
    Warning("W"),
    Error("E")
}

@Composable
private fun LogLevel.color(): Color = when (this) {
    LogLevel.Info -> MaterialTheme.colorScheme.onSurfaceVariant
    LogLevel.Warning -> Color(0xFFFFD166)
    LogLevel.Error -> MaterialTheme.colorScheme.error
}

private sealed interface UiState {
    data object Idle : UiState
    data object Loading : UiState
    data class Ready(val result: CandidateResult) : UiState
    data class CheckingPickedFile(val candidate: DownloadCandidate) : UiState
    data class Downloading(val candidate: DownloadCandidate, val percent: Int) : UiState
    data class Error(val message: String) : UiState
}

private object DownloadHelperContract {
    const val ACTION_DOWNLOAD_ORIGINAL_APK = "app.morphe.manager.action.DOWNLOAD_ORIGINAL_APK"
    const val EXTRA_PROTOCOL_VERSION = "app.morphe.manager.extra.PROTOCOL_VERSION"
    const val EXTRA_CALLER_PACKAGE = "app.morphe.manager.extra.CALLER_PACKAGE"
    const val EXTRA_PACKAGE_NAME = "app.morphe.manager.extra.PACKAGE_NAME"
    const val EXTRA_APP_NAME = "app.morphe.manager.extra.APP_NAME"
    const val EXTRA_VERSION_NAME = "app.morphe.manager.extra.VERSION_NAME"
    const val EXTRA_VERSION_CODE = "app.morphe.manager.extra.VERSION_CODE"
    const val EXTRA_VERSION_CODES = "app.morphe.manager.extra.VERSION_CODES"
    const val EXTRA_COMPATIBLE_VERSION_NAMES = "app.morphe.manager.extra.COMPATIBLE_VERSION_NAMES"
    const val EXTRA_COMPATIBLE_VERSION_CODES = "app.morphe.manager.extra.COMPATIBLE_VERSION_CODES"
    const val EXTRA_SUPPORTED_ABIS = "app.morphe.manager.extra.SUPPORTED_ABIS"
    const val EXTRA_FILE_TYPE = "app.morphe.manager.extra.FILE_TYPE"
    const val EXTRA_REQUESTED_FILE_TYPE = "app.morphe.manager.extra.REQUESTED_FILE_TYPE"
    const val EXTRA_ALLOW_SPLIT_ARCHIVE = "app.morphe.manager.extra.ALLOW_SPLIT_ARCHIVE"
    const val EXTRA_STOCK_INSTALL_REQUIRED = "app.morphe.manager.extra.STOCK_INSTALL_REQUIRED"
    const val EXTRA_INSTALL_STOCK_AFTER_DOWNLOAD = "app.morphe.manager.extra.INSTALL_STOCK_AFTER_DOWNLOAD"
    const val EXTRA_FALLBACK_WEB_URL = "app.morphe.manager.extra.FALLBACK_WEB_URL"
    const val EXTRA_SOURCE_HINT_URLS = "app.morphe.manager.extra.SOURCE_HINT_URLS"
    const val EXTRA_RESULT_USE_INSTALLED_APP = "app.morphe.manager.extra.RESULT_USE_INSTALLED_APP"
    const val EXTRA_RESULT_PACKAGE_NAME = "app.morphe.manager.extra.RESULT_PACKAGE_NAME"
    const val EXTRA_RESULT_VERSION_NAME = "app.morphe.manager.extra.RESULT_VERSION_NAME"
    const val EXTRA_RESULT_SOURCE_NAME = "app.morphe.manager.extra.RESULT_SOURCE_NAME"
    const val EXTRA_RESULT_FILE_NAME = "app.morphe.manager.extra.RESULT_FILE_NAME"
}

private interface ApkPureApi {
    @Headers(
        "content-type: application/json",
        "ual-access-businessid: projecta"
    )
    @POST("v3/get_app_update")
    suspend fun getAppUpdate(
        @Header("ual-access-projecta") header: String,
        @Body request: ApkPureUpdateRequest
    ): ApkPureUpdateResponse
}

private data class ApkPureUpdateRequest(
    val app_info_for_update: List<ApkPureAppInfo>,
    val android_id: String = Random.nextLong().toString(16),
    val application_id: String = "com.apkpure.aegon",
    val cached_size: Long = -1
)

private data class ApkPureAppInfo(
    val package_name: String,
    val version_code: Long,
    val is_system: Boolean = false,
    val version_id: String = "",
    val cached_size: Int = -1
)

private data class ApkPureUpdateResponse(
    val retcode: Int = 0,
    val app_update_response: List<ApkPureAppUpdate> = emptyList()
)

private data class ApkPureAppUpdate(
    val package_name: String = "",
    val version_code: Long = 0L,
    val version_name: String = "",
    val label: String = "",
    val asset: ApkPureAsset = ApkPureAsset()
)

private data class ApkPureAsset(
    val type: String = "",
    val url: String = ""
)

private data class ApkPureVersionEntry(
    val versionName: String?,
    val versionCode: Long?,
    val downloadPageUrl: String,
    val fileKind: String
)

private data class ApkPureDeviceHeader(
    val device_info: ApkPureDeviceInfo = ApkPureDeviceInfo()
)

private data class ApkPureDeviceInfo(
    val abis: List<String> = Build.SUPPORTED_ABIS.toList(),
    val android_id: String = Random.nextLong().toString(16),
    val os_ver: String = Build.VERSION.SDK_INT.toString(),
    val os_ver_name: String = Build.VERSION.RELEASE,
    val platform: Int = 1
)

private interface AptoideApi {
    @POST("listSearchApps")
    suspend fun searchApps(@Body request: AptoideSearchRequest): AptoideSearchResponse

    @GET("getApp")
    suspend fun getAppByPackage(@Query("package_name") packageName: String): AptoideGetAppResponse

    @GET("getApp")
    suspend fun getAppById(@Query("app_id") appId: Long): AptoideGetAppResponse

    @GET("listAppVersions")
    suspend fun listAppVersionsByPackage(@Query("package_name") packageName: String): AptoideVersionListResponse

    @GET("listAppVersions")
    suspend fun listAppVersionsById(@Query("app_id") appId: Long): AptoideVersionListResponse
}

private data class AptoideSearchRequest(
    val query: String = "",
    val limit: String = "10",
    val q: String? = null,
    val not_apk_tags: String = "alpha,beta",
    val store_ids: List<Long>? = listOf(15L, 711454L)
)

private data class AptoideSearchResponse(
    val datalist: AptoideDataList = AptoideDataList()
)

private data class AptoideDataList(
    val list: List<AptoideApp> = emptyList()
)

private data class AptoideGetAppResponse(
    val nodes: AptoideNodes = AptoideNodes()
)

private data class AptoideVersionListResponse(
    val list: List<AptoideApp> = emptyList()
)

private data class AptoideNodes(
    val meta: AptoideMetaNode = AptoideMetaNode()
)

private data class AptoideMetaNode(
    val data: AptoideApp = AptoideApp()
)

private data class AptoideNextData(
    val props: AptoideNextProps = AptoideNextProps()
)

private data class AptoideNextProps(
    val pageProps: AptoidePageProps = AptoidePageProps()
)

private data class AptoidePageProps(
    val app: AptoideApp = AptoideApp(),
    val packageName: String = "",
    val versions: List<AptoideVersionItem> = emptyList()
)

private data class AptoideVersionItem(
    val id: Long = 0L,
    val name: String = "",
    val vername: String = "",
    val vercode: Long = 0L
)

private data class AptoideApp(
    val id: Long = 0L,
    val name: String = "",
    @SerializedName("package")
    val packageName: String = "",
    val file: AptoideFile = AptoideFile(),
    val urls: AptoideUrls = AptoideUrls()
)

private data class AptoideFile(
    val vername: String = "",
    val vercode: String = "0",
    val path: String = "",
    @SerializedName(value = "path_alt", alternate = ["pathAlt"])
    val pathAlt: String = ""
)

private data class AptoideUrls(
    val w: String = "",
    val m: String = ""
)

private fun playStoreUrl(packageName: String): String =
    "https://play.google.com/store/apps/details?id=${URLEncoder.encode(packageName, "UTF-8")}"

private fun fileKindFromTags(tags: List<String>, request: HelperRequest): String {
    val available = tags
        .map { it.trim().lowercase(Locale.US) }
        .filter { it in DOWNLOAD_FILE_KIND_SET }
        .distinct()
    val requested = available.firstOrNull { it in request.requestedFileKinds }

    return requested
        ?: available.firstOrNull { request.acceptsFormat(it) }
        ?: available.firstOrNull()
        ?: "web"
}

private fun Context.openPlayStoreListing(packageName: String, fallbackUrl: String) {
    val marketIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("market://details?id=${Uri.encode(packageName)}")
    ).apply {
        setPackage("com.android.vending")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    runCatching { startActivity(marketIntent) }
        .onFailure { startActivity(webIntent) }
}

private fun Context.isPackageInstalled(packageName: String): Boolean =
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
    }.isSuccess

private fun String.normalizedHttpUrlOrNull(): String? {
    val normalized = trim().let { url ->
        when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://", ignoreCase = true) ||
                url.startsWith("https://", ignoreCase = true) -> url
            else -> return null
        }
    }

    return runCatching {
        Request.Builder().url(normalized)
        normalized
    }.getOrNull()
}

private fun validateApkLikeStream(
    input: java.io.InputStream,
    contentType: String?
): java.io.InputStream {
    val header = ByteArray(4)
    val headerSize = input.read(header)
    val isZip = headerSize >= 2 &&
        header[0] == 'P'.code.toByte() &&
        header[1] == 'K'.code.toByte()

    check(isZip) {
        val type = contentType?.let { " ($it)" }.orEmpty()
        "Source did not return a valid APK/APKS/XAPK$type."
    }

    return SequenceInputStream(ByteArrayInputStream(header, 0, headerSize), input)
}

private fun Context.readDownloadedApkMetadata(file: File): DownloadedApkMetadata? {
    if (file.extension.equals("apk", ignoreCase = true)) {
        return readApkMetadata(file)
    }

    return runCatching {
        val validationDir = File(cacheDir, "validation").apply { mkdirs() }
        ZipFile(file).use { zip ->
            val entry = zip.entries()
                .asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                .sortedWith(
                    compareBy<java.util.zip.ZipEntry> {
                        !it.name.substringAfterLast('/').equals("base.apk", ignoreCase = true)
                    }.thenBy { it.name }
                )
                .firstOrNull()
                ?: return@runCatching null
            val extracted = File(
                validationDir,
                "${file.nameWithoutExtension}-${entry.name.hashCode()}.apk".sanitizeFileName()
            )
            zip.getInputStream(entry).use { input ->
                extracted.outputStream().use { output -> input.copyTo(output) }
            }
            readApkMetadata(extracted).also { extracted.delete() }
        }
    }.getOrNull()
}

@Suppress("DEPRECATION")
private fun Context.readApkMetadata(file: File): DownloadedApkMetadata? {
    val info = packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_META_DATA)
        ?: return null
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        info.versionCode.toLong()
    }.takeIf { it > 0L }

    return DownloadedApkMetadata(
        packageName = info.packageName,
        versionName = info.versionName,
        versionCode = versionCode
    )
}

private fun parseInfoTableValue(doc: Document, label: String): String? =
    doc.select("tr")
        .firstOrNull { it.select("th").text().equals(label, ignoreCase = true) }
        ?.select("td")
        ?.lastOrNull()
        ?.text()
        ?.trim()
        ?.takeIf(String::isNotBlank)

private fun fileKindFromUrl(url: String): String {
    val decoded = URLDecoder.decode(url, StandardCharsets.UTF_8.name()).lowercase(Locale.US)
    val fileNameKind = Regex("""filename[^.]*\.(apk|apks|apkm|xapk)""")
        .find(decoded)
        ?.groupValues
        ?.getOrNull(1)
    return when {
        fileNameKind != null -> fileNameKind
        "xapk" in decoded -> "xapk"
        "apks" in decoded -> "apks"
        "apkm" in decoded -> "apkm"
        else -> "apk"
    }
}

private fun String.slugForUrl(): String =
    lowercase(Locale.US)
        .replace("&", " and ")
        .replace("'", "")
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "app" }

private fun String.apkMirrorVersionSlug(): String =
    lowercase(Locale.US)
        .replace(".", "-")
        .replace("_", "-")
        .replace(Regex("[^a-z0-9-]+"), "-")
        .replace(Regex("-+"), "-")
        .trim('-')

private fun String?.versionNameEquals(other: String?): Boolean {
    if (this == null || other == null) return false
    val left = normalizedVersionName()
    val right = other.normalizedVersionName()
    if (left.isBlank() || right.isBlank()) return false
    if (left == right) return true

    val leftParts = left.versionNumberParts()
    val rightParts = right.versionNumberParts()
    return leftParts.isNotEmpty() && leftParts == rightParts
}

private fun String.withoutTrailingVersionCode(): String =
    replace(Regex("""\s*\(\s*\d+\s*\)\s*$"""), "")
        .trim()

private fun String.trailingVersionCode(): Long? =
    Regex("""\(\s*(\d+)\s*\)\s*$""")
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
        ?.toLongOrNull()

private fun String.normalizedVersionName(): String =
    withoutTrailingVersionCode()
        .lowercase(Locale.US)
        .replace(Regex("""\b(version|ver|v|release|stable|apk|xapk|apkm|apks|bundle)\b"""), " ")
        .replace(Regex("""[^\p{Alnum}]+"""), ".")
        .trim('.')

private fun String.withManualModeHint(): String {
    if (contains("Manual mode", ignoreCase = true)) return this
    val message = trimEnd()
    val hint = "Use Manual mode for this source instead."
    return if (message.contains('\n')) "$message\n$hint" else "$message $hint"
}

private fun sourceFailureMessage(source: DownloadSource, error: Throwable): String =
    sourceFailureMessage(source.label, error, action = "check")

private fun downloadFailureMessage(candidate: DownloadCandidate, error: Throwable): String =
    sourceFailureMessage(candidate.source.label, error, action = "download")

private fun sourceFailureMessage(sourceLabel: String, error: Throwable, action: String): String {
    val details = error.failureDetails()
    val httpCode = Regex("""\bHTTP\s+(\d{3})\b""", RegexOption.IGNORE_CASE)
        .find(details)
        ?.groupValues
        ?.getOrNull(1)
    val actionText = if (action == "download") "download" else "check"

    return when {
        httpCode == "403" -> {
            "$sourceLabel blocked automated access (HTTP 403), likely due to bot protection. Open the link and download manually."
        }
        httpCode == "429" -> {
            "$sourceLabel rate-limited the helper (HTTP 429). Try again later or use Manual mode."
        }
        httpCode == "404" -> {
            "$sourceLabel did not have the requested page (HTTP 404). Use Manual mode for this source instead."
        }
        httpCode != null -> {
            "$sourceLabel returned HTTP $httpCode during $actionText. Use Manual mode for this source instead."
        }
        details.contains("cloudflare", ignoreCase = true) -> {
            "$sourceLabel showed a browser verification page, so direct access is blocked. Open the link and download manually."
        }
        details.contains("timeout", ignoreCase = true) -> {
            "$sourceLabel took too long to respond. Try again or use Manual mode."
        }
        details.contains("Unable to resolve host", ignoreCase = true) ||
            details.contains("failed to connect", ignoreCase = true) -> {
            "Could not connect to $sourceLabel. Check your connection or use Manual mode."
        }
        else -> {
            "Could not $actionText $sourceLabel: ${details.ifBlank { "unknown error" }}".withManualModeHint()
        }
    }
}

private fun Throwable.failureDetails(): String =
    generateSequence(this) { it.cause }
        .mapNotNull { it.message?.trim()?.takeIf(String::isNotBlank) }
        .firstOrNull()
        ?: javaClass.simpleName

private fun sourceVersionFromText(text: String): String? =
    Regex("""\b(v?\d+(?:[._-]\d+)+(?:[-.][A-Za-z0-9]+)?)\b""", RegexOption.IGNORE_CASE)
        .find(text)
        ?.value
        ?.trim()

private fun compareVersionNames(left: String?, right: String?): Int {
    if (left == right) return 0
    if (left == null) return -1
    if (right == null) return 1

    val leftParts = left.versionNumberParts()
    val rightParts = right.versionNumberParts()
    val size = maxOf(leftParts.size, rightParts.size)
    for (index in 0 until size) {
        val leftPart = leftParts.getOrElse(index) { 0 }
        val rightPart = rightParts.getOrElse(index) { 0 }
        if (leftPart != rightPart) return leftPart.compareTo(rightPart)
    }

    return left.compareTo(right, ignoreCase = true)
}

private fun String.versionNumberParts(): List<Int> =
    Regex("""\d+""")
        .findAll(this)
        .mapNotNull { it.value.toIntOrNull() }
        .toList()

private fun requestedFileKindsFrom(rawFileType: String?, allowSplitArchive: Boolean): Set<String> {
    val normalized = rawFileType?.lowercase(Locale.US).orEmpty()
    val explicitKinds = DOWNLOAD_FILE_KIND_REGEX
        .findAll(normalized)
        .map { it.value.lowercase(Locale.US) }
        .toMutableSet()
    if (explicitKinds.isEmpty() && "package-archive" in normalized) {
        explicitKinds.add("apk")
    }

    if (explicitKinds.isEmpty()) {
        return if (allowSplitArchive) DOWNLOAD_FILE_KIND_SET else setOf("apk")
    }

    if (allowSplitArchive && "apk" in explicitKinds) {
        explicitKinds.addAll(SPLIT_ARCHIVE_FILE_KINDS)
    }

    return explicitKinds
}

private fun Collection<String>.orderedFileKinds(): List<String> {
    val knownKinds = DOWNLOAD_FILE_KIND_ORDER.filter { it in this }
    val extraKinds = filter { it !in DOWNLOAD_FILE_KIND_SET }.distinct()
    return knownKinds + extraKinds
}

private fun String?.variantFileSuffix(): String =
    this
        ?.lowercase(Locale.US)
        ?.replace(Regex("""[^a-z0-9._-]+"""), "-")
        ?.trim('-')
        ?.takeIf(String::isNotBlank)
        ?.let { "-$it" }
        .orEmpty()

private fun String.sanitizeFileName(): String =
    replace(Regex("[^A-Za-z0-9._-]"), "_")
