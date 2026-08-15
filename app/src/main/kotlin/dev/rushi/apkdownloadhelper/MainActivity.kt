package dev.rushi.apkdownloadhelper

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.widget.Toast
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationManagerCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Warning
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.TextStyle
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Query

internal const val AURORA_AUTH_URL = "https://auroraoss.com/api/auth"
internal const val TAG = "ApkDownloadHelper"
internal val DOWNLOAD_FILE_KIND_ORDER = listOf("apk", "apkm", "apks", "xapk")
internal val APK_COMBO_FILE_KIND_ORDER = listOf("apk", "xapk", "apks")
internal val DOWNLOAD_FILE_KIND_SET = DOWNLOAD_FILE_KIND_ORDER.toSet()
internal val SPLIT_ARCHIVE_FILE_KINDS = setOf("apkm", "apks", "xapk")
internal val DOWNLOAD_FILE_KIND_REGEX = Regex("""apkm|apks|xapk|apk""", RegexOption.IGNORE_CASE)
internal val gson = Gson()

// Morphe Manager installs to test against: release first, debug as fallback.
private val MORPHE_MANAGER_PACKAGES = listOf(
    "app.morphe.manager",
    "app.morphe.manager.debug"
)

// Where to point the user when Morphe Manager is not installed at all.
private const val MORPHE_MANAGER_SITE_URL =
    "https://morphe.software/"

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
        .addInterceptor(httpLoggingInterceptor("Web"))
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
        .addInterceptor(httpLoggingInterceptor("APKPure"))
        .build()

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

    private val parsers: Map<DownloadSource, ApkSourceParser> by lazy {
        val playHttpClient = PlayHttpClient(Cache(File(cacheDir, "play-cache"), 64L * 1024L * 1024L))
        val parserContext = SourceParserContext(
            fetcher = OkHttpSourceTextFetcher(client),
            apkPureApi = apkPureApi,
            aptoideApi = aptoideApi,
            playHttpClient = playHttpClient,
            appContext = this
        )
        listOf(
            ApkMirrorParser(parserContext),
            UptodownParser(parserContext),
            ApkPureParser(parserContext),
            ApkComboParser(parserContext),
            AptoideParser(parserContext),
            EvoziParser(parserContext),
            Mi9Parser(),
            ApkDownloaderPagesParser(),
            AuroraParser(parserContext)
        ).associateBy { it.source }
    }

    private var request by mutableStateOf<HelperRequest?>(null)
    private var uiState by mutableStateOf<UiState>(UiState.Idle)
    private var helperSettings by mutableStateOf(HelperSettings())
    private var installedPackageRefreshToken by mutableIntStateOf(0)
    private val requestLogs = mutableStateListOf<RequestLogEntry>()
    private val logTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val historyTimeFormat = SimpleDateFormat("MMM d, HH:mm", Locale.US)
    private var pendingDownload: PendingDownload? = null
    // Non-null while the in-app captcha browser is open for a candidate whose
    // source gates the file behind a challenge a plain HTTP client cannot pass.
    private var captchaBrowser by mutableStateOf<DownloadCandidate?>(null)
    private var fastModeActive = false
    private var fastModeQueue: MutableList<DownloadSource>? = null
    private var fastModeDecision: CompletableDeferred<FastModeChoice?>? = null
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> startPendingDownload() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        helperSettings = loadHelperSettings()
        request = HelperRequest.from(intent)
        startRequestLog(request)
        lifecycleScope.launch(Dispatchers.IO) {
            cleanupTemporaryDownloads(helperSettings)
        }
        lifecycleScope.launch {
            DownloadJobManager.events.collect(::handleDownloadEvent)
        }
        if (deliverPendingResultIfPresent(request)) return

        setContent {
            HelperTheme {
                val captcha = captchaBrowser
                if (captcha != null) {
                    CaptchaBrowserScreen(
                        candidate = captcha,
                        onClose = ::closeCaptchaBrowser,
                        onUrlCaptured = ::onCaptchaUrlCaptured
                    )
                } else {
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
                        onVersionHistory = ::loadVersionHistory,
                        onDownloadVersion = ::downloadVersion,
                        onOpenHistoryEntry = ::openHistoryEntry,
                        onShareHistoryEntry = ::shareHistoryEntry,
                        onClearHistory = { DownloadHistoryStore.clear(applicationContext) },
                        onClearLogs = { requestLogs.clear() },
                        onCancel = {
                            appendLog("Query canceled by user.", LogLevel.Warning)
                            setResult(Activity.RESULT_CANCELED)
                            finish()
                        },
                        onCancelDownload = ::cancelDownload,
                        onCancelFastMode = ::cancelFastMode,
                        onUseFastModeMismatch = { fastModeChoose(FastModeChoice.USE) },
                        onSkipFastModeMismatch = { fastModeChoose(FastModeChoice.NEXT) },
                        onOpenMorphe = ::openMorpheManager,
                        onSolveCaptcha = ::openCaptchaBrowser
                    )
                }
            }
        }

        val activeRequest = request
        if (activeRequest != null) {
            loadCandidates()
            startFastModeIfEnabled(activeRequest)
        }
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
        val activeRequest = request
        if (activeRequest != null) {
            if (!deliverPendingResultIfPresent(activeRequest)) {
                loadCandidates()
                startFastModeIfEnabled(activeRequest)
            }
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
                state = resolved.notFoundMessage
                    ?.takeIf { resolved.candidates.isEmpty() }
                    ?.let { ResolveState.Done(emptyList()) }
                    ?: resolved.errorMessage
                        ?.takeIf { resolved.candidates.isEmpty() }
                        ?.let { message ->
                            ResolveState.Error(
                                message = message,
                                fallbackCandidate = resolved.fallbackCandidate
                            )
                        }
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

    private fun loadVersionHistory(source: DownloadSource) {
        val activeRequest = request ?: return
        helperSettings.networkPolicy.blockReason(this)?.let { message ->
            appendLog(message, LogLevel.Warning)
            updateHistoryState(source, VersionHistoryState.Error(message))
            return
        }
        updateHistoryState(source, VersionHistoryState.Loading)
        appendLog("Loading ${source.label} version history.")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { resolveVersionHistory(activeRequest, source) }
            }
            result
                .onSuccess { candidates ->
                    appendLog("${source.label} version history loaded: ${candidates.size} versions.")
                    updateHistoryState(source, VersionHistoryState.Done(candidates))
                }
                .onFailure { error ->
                    val message = sourceFailureMessage(source, error)
                    appendLog(message, LogLevel.Error)
                    updateHistoryState(source, VersionHistoryState.Error(message))
                }
        }
    }

    private fun updateHistoryState(source: DownloadSource, state: VersionHistoryState) {
        val activeRequest = request ?: return
        val current = (uiState as? UiState.Ready)?.result ?: initialCandidateResult(activeRequest)
        uiState = UiState.Ready(current.withHistoryState(source, state))
    }

    private fun downloadVersion(candidate: DownloadCandidate) {
        val activeRequest = request ?: return
        // Capture the current result before Loading replaces it, so a failed
        // resolution can restore the history list with this row flipped to
        // "Open link" instead of wiping back to a fresh screen.
        val currentResult = (uiState as? UiState.Ready)?.result
            ?: initialCandidateResult(activeRequest)
        appendLog("Resolving ${candidate.versionDisplay} from ${candidate.source.label} for download...")
        uiState = UiState.Loading
        lifecycleScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                runCatching { resolveHistoryCandidate(activeRequest, candidate) }
            }
            resolved
                .onSuccess { direct ->
                    if (direct == null) {
                        // No direct download exists for this version. Keep the
                        // history list usable by flipping this row to an
                        // "Open link" action instead of erroring the whole screen.
                        val message =
                            "No direct download was available for ${candidate.versionDisplay} " +
                                "on ${candidate.source.label}. Open the version page manually."
                        appendLog(message, LogLevel.Warning)
                        uiState = UiState.Ready(
                            currentResult.markHistoryCandidateNoDirectDownload(
                                source = candidate.source,
                                candidateKey = candidate.identityKey()
                            )
                        )
                    } else {
                        downloadAndReturn(direct)
                    }
                }
                .onFailure { error ->
                    val message = downloadFailureMessage(candidate, error)
                    appendLog(message, LogLevel.Error)
                    uiState = UiState.Error(message)
                }
        }
    }

    private suspend fun resolveVersionHistory(
        request: HelperRequest,
        source: DownloadSource
    ): List<DownloadCandidate> =
        parsers[source]?.resolveHistory(request).orEmpty()

    private suspend fun resolveHistoryCandidate(
        request: HelperRequest,
        candidate: DownloadCandidate
    ): DownloadCandidate? =
        parsers[candidate.source]?.resolveHistoryCandidate(request, candidate)

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
            logQueryIntentExtras()
        }
    }

    private fun logQueryIntentExtras() {
        val extras = intent.extras ?: return
        val dump = extras.keySet().sorted().joinToString(", ") { key ->
            val value = when (val raw = extras.get(key)) {
                null -> "null"
                is Array<*> -> raw.joinToString("|")
                is LongArray -> raw.joinToString("|")
                is IntArray -> raw.joinToString("|")
                else -> raw.toString()
            }
            "$key=$value"
        }
        if (logcatLoggingEnabled) Log.i(TAG, "Query intent extras: $dump")
    }

    private fun logResolveOutcome(
        source: DownloadSource,
        option: CandidateOption,
        outcome: ResolveOutcome
    ) {
        when {
            outcome.notFoundMessage != null -> {
                appendLog("${source.label} ${option.labelForLogs}: ${outcome.notFoundMessage}.", LogLevel.Warning)
            }
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
        if (logcatLoggingEnabled) {
            when (level) {
                LogLevel.Info -> Log.i(TAG, message)
                LogLevel.Warning -> Log.w(TAG, message)
                LogLevel.Error -> Log.e(TAG, message)
            }
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
            .onFailure { error ->
                if (error !is SourceAppNotFoundException) {
                    Log.w(TAG, "${source.label} ${option.name.lowercase(Locale.US)} lookup failed", error)
                }
            }
        lookup.exceptionOrNull()
            ?.takeIf { it is SourceAppNotFoundException }
            ?.let {
                return ResolveOutcome(
                    candidates = emptyList(),
                    notFoundMessage = "no listing for ${request.packageName}"
                )
            }
        lookup.exceptionOrNull()?.let { error ->
            return ResolveOutcome(
                candidates = emptyList(),
                errorMessage = sourceFailureMessage(source, error),
                fallbackCandidate = sourceErrorFallbackCandidate(request, source, option)
            )
        }
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
    ): List<DownloadCandidate> =
        parsers[source]?.findCandidates(request, option).orEmpty()

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
            if (none { it.option == CandidateOption.REQUESTED }) {
                parsers[source]?.requestedFallbackCandidate(request)?.let(::add)
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
            if (none { it.option == CandidateOption.LATEST }) {
                parsers[source]?.latestFallbackCandidate(request)?.let(::add)
            }
            if (none { it.option == CandidateOption.LATEST }) {
                latestWebFallback(request, source)?.let(::add)
            }
        }
            .distinctBy(DownloadCandidate::identityKey)
            .sortedBy { it.sortIndex }
    }

    private fun sourceErrorFallbackCandidate(
        request: HelperRequest,
        source: DownloadSource,
        option: CandidateOption
    ): DownloadCandidate? {
        if (option == CandidateOption.MANUAL || source == DownloadSource.AURORA) return null

        val manual = manualCandidates(request).firstOrNull { it.source == source } ?: return null
        val requestedVersionName = request.requestedVersionNames.firstOrNull()
        val requestedVersionCode = request.versionCode ?: request.versionCodes.singleOrNull()
        val url = when (option) {
            CandidateOption.REQUESTED -> request.sourceHintUrlsFor(source).firstOrNull()
                ?: sourceVersionSearchUrl(request, source)
                ?: manual.url
            CandidateOption.LATEST -> manual.url
            CandidateOption.MANUAL -> manual.url
        }

        return manual.copy(
            versionName = requestedVersionName.takeIf { option == CandidateOption.REQUESTED },
            versionCode = requestedVersionCode.takeIf { option == CandidateOption.REQUESTED },
            url = url,
            fileKind = "web",
            option = option,
            directDownload = false,
            versionStatus = if (option == CandidateOption.REQUESTED) VersionStatus.REQUESTED else VersionStatus.LATEST,
            formatMatches = true,
            note = null,
            variantLabel = null,
            files = emptyList()
        )
    }

    private fun sourceVersionSearchUrl(request: HelperRequest, source: DownloadSource): String? {
        val domain = source.searchDomain() ?: return null
        val version = request.requestedVersionLabel.takeIf { it != "any compatible version" } ?: return null
        val query = buildList {
            add(request.packageName)
            add("\"$version\"")
            request.requestedFileKinds
                .orderedFileKinds()
                .firstOrNull()
                ?.let(::add)
            add("site:$domain")
        }.joinToString(" ")
        return "https://www.google.com/search?q=${URLEncoder.encode(query, "UTF-8")}"
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
        parsers.values
            .mapNotNull { parser ->
                parser.searchUrl(request.packageName)?.let { parser.source to it }
            }
            .sortedBy { it.first.ordinal }

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
        uiState = if (fastModeActive) {
            UiState.FastMode(
                FastModeProgress(
                    sourceLabel = candidate.source.label,
                    detail = "Downloading from ${candidate.source.label}…",
                    percent = 0
                )
            )
        } else {
            UiState.Downloading(candidate, 0)
        }
        pendingDownload = PendingDownload(activeRequest, candidate, settings)
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startPendingDownload()
        }
    }

    private fun startPendingDownload() {
        val pending = pendingDownload ?: return
        pendingDownload = null
        DownloadJobManager.start(
            DownloadJobManager.DownloadJob(
                request = pending.request,
                candidate = pending.candidate,
                settings = pending.settings,
                requestIntentExtras = intent.getExtras()
            )
        )
        startForegroundService(
            Intent(this, DownloadService::class.java).setAction(ACTION_START_DOWNLOAD)
        )
    }

    private fun cancelDownload() {
        appendLog("Cancelling download…", LogLevel.Warning)
        runCatching {
            startService(
                Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL_DOWNLOAD)
            )
        }.onFailure {
            appendLog("Could not cancel the download.", LogLevel.Error)
        }
    }

    // ---- In-app captcha browser: let the user solve a captcha in a real
    // browser and capture the download URL it produces ----

    private fun openCaptchaBrowser(candidate: DownloadCandidate) {
        if (candidate.source == DownloadSource.AURORA || candidate.source == DownloadSource.PLAY) return
        appendLog(
            "Opening in-app browser for ${candidate.source.label} to solve the captcha.",
            LogLevel.Info
        )
        captchaBrowser = candidate
    }

    private fun closeCaptchaBrowser() {
        captchaBrowser = null
    }

    private fun onCaptchaUrlCaptured(url: String) {
        val candidate = captchaBrowser ?: return
        captchaBrowser = null
        val fileKind = fileKindFromUrl(url)
        appendLog(
            "Captured download link from ${candidate.source.label} in the in-app browser " +
                "($fileKind): $url",
            LogLevel.Info
        )
        downloadAndReturn(
            candidate.copy(
                url = url,
                fileKind = fileKind,
                directDownload = true,
                note = null
            )
        )
    }

    private fun openMorpheManager() {
        // Prefer the release build of Morphe Manager, then the debug build.
        val launchIntent = MORPHE_MANAGER_PACKAGES
            .asSequence()
            .mapNotNull { packageName -> packageManager.getLaunchIntentForPackage(packageName) }
            .firstOrNull()
        if (launchIntent != null) {
            appendLog("Opening Morphe Manager (${launchIntent.component?.packageName ?: "Morphe Manager"}).")
            runCatching {
                startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure {
                appendLog("Could not open Morphe Manager.", LogLevel.Error)
            }
        } else {
            appendLog("Morphe Manager is not installed — opening morphe.software.", LogLevel.Warning)
            val releases = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(MORPHE_MANAGER_SITE_URL)
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            runCatching { startActivity(releases) }
                .onFailure {
                    appendLog("Could not open Morphe Manager releases page.", LogLevel.Error)
                }
        }
    }

    // ---- Fast Mode: auto-find the exact requested version and return it ----

    private fun startFastModeIfEnabled(request: HelperRequest) {
        if (!helperSettings.fastMode) return
        if (!request.hasRequestedVersionRequest) return
        if (fastModeActive) return
        fastModeActive = true
        fastModeQueue = DownloadSource.entries
            .filter { it !in NON_FAST_MODE_SOURCES }
            .toMutableList()
        appendLog("Fast Mode: auto-searching sources for the exact requested version.", LogLevel.Info)
        uiState = UiState.FastMode(FastModeProgress(detail = "Auto-searching sources…"))
        lifecycleScope.launch {
            fastModeNext(request)
        }
    }

    private fun cancelFastMode() {
        appendLog("Fast Mode cancelled — taking over manually.", LogLevel.Warning)
        fastModeActive = false
        fastModeQueue = null
        // Unblock a pending "use this version?" question so the loop exits.
        fastModeDecision?.complete(null)
        fastModeDecision = null
        // If a fast-mode download is in flight, stop it too; its Cancelled
        // event also restores the source list.
        if (DownloadJobManager.activeJob != null) {
            cancelDownload()
        }
        val activeRequest = request
        uiState = if (activeRequest != null) {
            UiState.Ready(initialCandidateResult(activeRequest))
        } else {
            UiState.Idle
        }
    }

    private fun fastModeChoose(choice: FastModeChoice) {
        fastModeDecision?.complete(choice)
    }

    private suspend fun fastModeNext(request: HelperRequest) {
        val queue = fastModeQueue ?: return
        while (queue.isNotEmpty()) {
            // The user may have cancelled while a source was resolving.
            if (!fastModeActive) return
            val source = queue.removeAt(0)
            appendLog("Fast Mode: checking ${source.label} for ${request.requestedVersionLabel}...")
            uiState = UiState.FastMode(
                FastModeProgress(sourceLabel = source.label, detail = "Checking ${source.label}…")
            )
            val result = withContext(Dispatchers.IO) {
                runCatching { fastModeFindCandidate(request, source) }
                    .getOrDefault(FastModeFindResult.None)
            }
            if (!fastModeActive) return
            when (result) {
                is FastModeFindResult.Exact -> {
                    val candidate = result.candidate
                    appendLog(
                        "Fast Mode: found ${candidate.versionDisplay} on ${source.label} " +
                            "(${candidate.fileKind.uppercase(Locale.US)}) — downloading.",
                        LogLevel.Info
                    )
                    uiState = UiState.FastMode(
                        FastModeProgress(
                            sourceLabel = source.label,
                            detail = "Found ${candidate.versionDisplay} — downloading…",
                            percent = 0
                        )
                    )
                    downloadAndReturn(candidate)
                    return
                }
                is FastModeFindResult.VersionMismatch -> {
                    val candidate = result.candidate
                    appendLog(
                        "Fast Mode: ${source.label} has ${candidate.versionDisplay} — " +
                            "build differs from requested ${request.versionCodeSummary ?: "version"}. Asking the user.",
                        LogLevel.Warning
                    )
                    val decisionGate = CompletableDeferred<FastModeChoice?>()
                    fastModeDecision = decisionGate
                    uiState = UiState.FastMode(
                        FastModeProgress(
                            sourceLabel = source.label,
                            detail = "Version code mismatch",
                            awaitingDecision = true,
                            mismatchDetail = "Requested build ${request.versionCodeSummary ?: "?"} — " +
                                "${source.label} has ${candidate.versionDisplay}."
                        )
                    )
                    val decision = decisionGate.await()
                    if (!fastModeActive || decision == null) return
                    if (decision == FastModeChoice.USE) {
                        appendLog(
                            "Fast Mode: using ${candidate.versionDisplay} from ${source.label} " +
                                "despite the code mismatch.",
                            LogLevel.Info
                        )
                        uiState = UiState.FastMode(
                            FastModeProgress(
                                sourceLabel = source.label,
                                detail = "Found ${candidate.versionDisplay} — downloading…",
                                percent = 0
                            )
                        )
                        downloadAndReturn(candidate)
                        return
                    }
                    appendLog("Fast Mode: user skipped ${source.label} — trying the next source.", LogLevel.Info)
                    continue
                }
                FastModeFindResult.None -> {
                    appendLog("Fast Mode: no exact match on ${source.label}.", LogLevel.Info)
                }
            }
        }
        fastModeActive = false
        fastModeQueue = null
        appendLog("Fast Mode: no source had the exact version. Use the sources below.", LogLevel.Warning)
        val activeRequest = request
        uiState = if (activeRequest != null) {
            UiState.FastMode(
                FastModeProgress(
                    detail = "No source had the exact version ${request.requestedVersionLabel}.",
                    done = true,
                    succeeded = false,
                    result = initialCandidateResult(activeRequest)
                )
            )
        } else {
            UiState.Idle
        }
    }

    private suspend fun fastModeFindCandidate(
        request: HelperRequest,
        source: DownloadSource
    ): FastModeFindResult {
        val outcome = resolveSourceSection(request, source, CandidateOption.REQUESTED)
        val exact = outcome.candidates.firstOrNull { candidate ->
            // Format differences are fine, but the exact version name AND
            // version code are both required. A candidate that reports a code
            // outside the request is surfaced as a mismatch the user can
            // accept or skip — never silently downloaded.
            candidate.directDownload &&
                request.matchesRequestedVersionStrict(candidate.versionName, candidate.versionCode)
        }
        if (exact != null) return FastModeFindResult.Exact(exact)

        // The version name exists on this source but with a different build —
        // hand it to the loop so it can ask the user before proceeding.
        val mismatch = outcome.candidates.firstOrNull { candidate ->
            candidate.directDownload &&
                request.requestedVersionName != null &&
                candidate.versionName != null &&
                candidate.versionName.versionNameEquals(request.requestedVersionName) &&
                candidate.versionCode != null &&
                !request.matchesRequestedVersionStrict(candidate.versionName, candidate.versionCode)
        }
        return if (mismatch != null) {
            FastModeFindResult.VersionMismatch(mismatch)
        } else {
            FastModeFindResult.None
        }
    }

    private fun handleDownloadEvent(event: DownloadJobManager.Event?) {
        when (event) {
            null -> Unit
            is DownloadJobManager.Event.Progress -> {
                uiState = if (fastModeActive) {
                    UiState.FastMode(
                        FastModeProgress(
                            sourceLabel = event.candidate.source.label,
                            detail = "Downloading from ${event.candidate.source.label}…",
                            percent = event.percent
                        )
                    )
                } else {
                    UiState.Downloading(event.candidate, event.percent)
                }
            }
            is DownloadJobManager.Event.Completed -> {
                // StateFlow replays its last value to every new collector, so a
                // freshly created activity can receive the Completed event of a
                // previous request session (after the old activity finished
                // handing the file to Morphe). Only act when the result belongs
                // to the current request; otherwise discard it so the old file
                // is never handed to a different request.
                if (event.result.belongsTo(request)) {
                    appendLog("Download validated: ${event.result.fileName}.")
                    if (!deliverResult(event.result)) {
                        appendLog(
                            "Download is ready; ${event.result.callerPackage} can request it again to receive the file.",
                            LogLevel.Warning
                        )
                        // Opened standalone with no caller to return to — keep the
                        // Fast Mode card showing the result and the source list
                        // reachable beneath it.
                        val wasFastMode = fastModeActive
                        fastModeActive = false
                        fastModeQueue = null
                        val activeRequest = request
                        uiState = if (wasFastMode) {
                            UiState.FastMode(
                                FastModeProgress(
                                    sourceLabel = event.result.sourceName,
                                    detail = "Download ready: ${event.result.fileName}. " +
                                        "Request it again from Morphe to receive it.",
                                    done = true,
                                    succeeded = true,
                                    result = activeRequest?.let { initialCandidateResult(it) }
                                )
                            )
                        } else if (activeRequest != null) {
                            UiState.Ready(initialCandidateResult(activeRequest))
                        } else {
                            UiState.Idle
                        }
                    }
                }
                // The event has been observed (or discarded) — clear it so a
                // future activity recreation cannot replay it into a new
                // request session.
                DownloadJobManager.clearEvent()
            }
            is DownloadJobManager.Event.Failed -> {
                appendLog(event.message, LogLevel.Error)
                if (fastModeActive) {
                    val activeRequest = request
                    if (activeRequest != null && !fastModeQueue.isNullOrEmpty()) {
                        appendLog(
                            "Fast Mode: ${event.candidate.source.label} did not work — trying the next source.",
                            LogLevel.Warning
                        )
                        lifecycleScope.launch { fastModeNext(activeRequest) }
                    } else {
                        fastModeActive = false
                        fastModeQueue = null
                        uiState = if (activeRequest != null) {
                            UiState.FastMode(
                                FastModeProgress(
                                    sourceLabel = event.candidate.source.label,
                                    detail = "Fast Mode failed: ${event.message.lineSequence().firstOrNull().orEmpty().take(160)}",
                                    done = true,
                                    succeeded = false,
                                    result = initialCandidateResult(activeRequest)
                                )
                            )
                        } else {
                            UiState.Error(event.message)
                        }
                    }
                } else {
                    uiState = UiState.Error(event.message)
                }
            }
            is DownloadJobManager.Event.ValidationMismatch -> {
                // The download itself succeeded; only the version code differs
                // from the request (the parser couldn't know it up front). The
                // file is kept. Fast Mode asks the user; manual mode keeps the
                // old hard-fail behavior.
                val activeRequest = request
                // Only the activity that owns this request may act on the
                // event. A stale instance (no request, or a different request)
                // must ignore it — and must NOT clear it first, or the owner's
                // collector would never see it (StateFlow conflates: a clear
                // before the owner processes the event swallows it).
                if (activeRequest == null || event.candidate.packageName != activeRequest.packageName) return
                if (fastModeActive) {
                    appendLog(
                        "Fast Mode: ${event.candidate.source.label} downloaded " +
                            "${event.candidate.versionDisplay} — build differs from requested " +
                            "${activeRequest.versionCodeSummary ?: "version"}. Asking the user.",
                        LogLevel.Warning
                    )
                    val decisionGate = CompletableDeferred<FastModeChoice?>()
                    fastModeDecision = decisionGate
                    uiState = UiState.FastMode(
                        FastModeProgress(
                            sourceLabel = event.candidate.source.label,
                            detail = "Version code mismatch",
                            awaitingDecision = true,
                            mismatchDetail = "Requested build ${activeRequest.versionCodeSummary ?: "?"} — " +
                                "${event.candidate.source.label} downloaded " +
                                "${event.candidate.versionDisplay}, found build " +
                                "${event.foundVersionCode ?: "unknown"}."
                        )
                    )
                    lifecycleScope.launch {
                        val decision = decisionGate.await()
                        DownloadJobManager.clearEvent()
                        if (!fastModeActive || decision == null) {
                            event.file.delete()
                            return@launch
                        }
                        if (decision == FastModeChoice.USE) {
                            appendLog(
                                "Fast Mode: using downloaded ${event.candidate.versionDisplay} from " +
                                    "${event.candidate.source.label} despite the code mismatch.",
                                LogLevel.Info
                            )
                            runCatching {
                                returnPreparedFile(activeRequest, event.candidate, event.file, helperSettings)
                            }.onFailure { error ->
                                appendLog(error.message ?: "Could not return the file.", LogLevel.Error)
                                event.file.delete()
                            }
                            fastModeActive = false
                            fastModeQueue = null
                            uiState = UiState.Ready(initialCandidateResult(activeRequest))
                        } else {
                            event.file.delete()
                            appendLog(
                                "Fast Mode: user skipped ${event.candidate.source.label} — " +
                                    "trying the next source.",
                                LogLevel.Info
                            )
                            fastModeNext(activeRequest)
                        }
                    }
                } else {
                    DownloadJobManager.clearEvent()
                    event.file.delete()
                    val message = "Downloaded version code does not match the request " +
                        "(found ${event.foundVersionCode ?: "unknown"})."
                    appendLog(message, LogLevel.Error)
                    uiState = UiState.Error(message)
                }
            }
            is DownloadJobManager.Event.Cancelled -> {
                appendLog("Download cancelled.", LogLevel.Warning)
                fastModeActive = false
                fastModeQueue = null
                val activeRequest = request
                uiState = if (activeRequest != null) {
                    UiState.Ready(initialCandidateResult(activeRequest))
                } else {
                    UiState.Idle
                }
            }
        }
    }

    private fun deliverPendingResultIfPresent(request: HelperRequest?): Boolean {
        if (request == null) return false
        val pending = DownloadJobManager.readPendingResult(applicationContext) ?: return false
        // The stored result belongs to a previous request session. A new request
        // for a different app (or for a different version of the same app)
        // invalidates it — drop the pending file and its stale "return to
        // Morphe" notification so the old APK can never be handed back for the
        // new request (e.g. when the old completion notification is tapped
        // while the new request is on screen).
        if (!pending.belongsTo(request)) {
            DownloadJobManager.clearPendingResult(applicationContext)
            cancelCompletionNotification()
            return false
        }
        return deliverResult(pending)
    }

    private fun cancelCompletionNotification() {
        runCatching {
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID_DONE)
        }
    }

    private fun deliverResult(pending: PendingDownloadResult): Boolean {
        if (getCallingActivity() == null) return false
        val uri = Uri.parse(pending.uri)
        val result = Intent().apply {
            data = uri
            clipData = ClipData.newUri(contentResolver, pending.fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(DownloadHelperContract.EXTRA_RESULT_PACKAGE_NAME, pending.packageName)
            putExtra(DownloadHelperContract.EXTRA_RESULT_VERSION_NAME, pending.versionName)
            putExtra(DownloadHelperContract.EXTRA_RESULT_SOURCE_NAME, pending.sourceName)
            putExtra(DownloadHelperContract.EXTRA_RESULT_FILE_NAME, pending.fileName)
        }
        setResult(Activity.RESULT_OK, result)
        appendLog("Returned ${pending.fileName} to ${pending.callerPackage}.")
        if (logcatLoggingEnabled) {
            Log.i(
                TAG,
                "Query result: OK package=${pending.packageName}, " +
                    "version=${pending.versionName ?: "any"}, source=${pending.sourceName}, " +
                    "file=${pending.fileName}, uri=${pending.uri}"
            )
        }
        DownloadJobManager.clearPendingResult(applicationContext)
        cancelCompletionNotification()
        finish()
        return true
    }

    private fun shareHistoryEntry(entry: DownloadHistoryEntry) {
        val uri = Uri.parse(entry.uri)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = fileNameMimeType(entry.fileName)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(contentResolver, entry.fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(share, "Share ${entry.fileName}"))
        }.onFailure {
            appendLog("Could not share ${entry.fileName}.", LogLevel.Warning)
        }
    }

    private fun openHistoryEntry(entry: DownloadHistoryEntry) {
        val uri = Uri.parse(entry.uri)
        val open = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, fileNameMimeType(entry.fileName))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(open)
        }.onFailure {
            appendLog("Could not open ${entry.fileName}.", LogLevel.Warning)
        }
    }

    private fun returnPreparedFile(
        request: HelperRequest,
        candidate: DownloadCandidate,
        file: File,
        settings: HelperSettings
    ) {
        val uri = when (settings.downloadLocation) {
            DownloadLocation.TEMPORARY -> FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.files", file)
            DownloadLocation.DOWNLOADS -> copyToDownloads(file)
        }
        val pending = PendingDownloadResult(
            uri = uri.toString(),
            fileName = file.name,
            packageName = candidate.packageName,
            versionName = candidate.versionName,
            sourceName = candidate.source.label,
            requestPackage = request.packageName,
            callerPackage = request.callerPackage
        )
        DownloadJobManager.persistPendingResult(pending, applicationContext)
        recordHandOff(request, candidate, file, uri)
        if (
            settings.downloadLocation == DownloadLocation.DOWNLOADS ||
            settings.deleteTemporaryAfterHandoff
        ) {
            scheduleTemporaryDelete(file)
        }
        if (!deliverResult(pending)) {
            appendLog(
                "File is ready; ${request.callerPackage} can request it again to receive it.",
                LogLevel.Warning
            )
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
                    validateDownloadedArtifact(this@MainActivity, activeRequest, candidate, file)
                    file
                }
            }

            result
                .onSuccess { file ->
                    appendLog("Selected file validated: ${file.name} (${file.length()} bytes).")
                    runCatching {
                        returnPreparedFile(activeRequest, candidate, file, settings)
                    }.onFailure { error ->
                        val message = (error.message ?: "Could not return selected APK to Morphe.")
                            .withManualModeHint()
                        appendLog(message, LogLevel.Error)
                        uiState = UiState.Error(message)
                    }
                }
                .onFailure { error ->
                    // A code-mismatch keeps the file (so Fast Mode can ask); the
                    // picked-file flow has no ask, so clean it up here.
                    if (error is VersionCodeMismatchException) {
                        error.file.delete()
                    }
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
        if (logcatLoggingEnabled) {
            Log.i(
                TAG,
                "Query result: OK package=${candidate.packageName}, " +
                    "version=${candidate.versionName ?: "any"}, source=${candidate.source.label}, " +
                    "useInstalledApp=true"
            )
        }
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


}

private val historyTimeFormat = SimpleDateFormat("MMM d, HH:mm", Locale.US)

private fun formatHistoryTimestamp(timestamp: Long): String =
    synchronized(historyTimeFormat) { historyTimeFormat.format(Date(timestamp)) }

private fun fileNameMimeType(fileName: String): String =
    when (fileName.substringAfterLast('.', "").lowercase(Locale.US)) {
        "apk" -> "application/vnd.android.package-archive"
        "apks",
        "apkm",
        "xapk" -> "application/zip"
        else -> "application/octet-stream"
    }

private fun Context.isHistoryUriUsable(uriString: String): Boolean {
    val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
    return runCatching {
        contentResolver.openInputStream(uri)?.use { } != null
    }.getOrDefault(false)
}

internal fun File.mimeType(): String = when (extension.lowercase(Locale.US)) {
    "apk" -> "application/vnd.android.package-archive"
    "apks",
    "apkm",
    "xapk" -> "application/zip"
    else -> "application/octet-stream"
}

internal fun File.uniqueChild(fileName: String): File {
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
    val ActionShowWidth = 88.dp
    val ActionClearWidth = 96.dp
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

// ---- In-app captcha browser: a real WebView that passes Cloudflare-style
// challenges, captures the download URL the page produces, and hands it to the
// normal download pipeline. Any source that gates its file behind a captcha can
// opt in by setting DownloadCandidate.captchaUrl.

@Composable
private fun CaptchaBrowserScreen(
    candidate: DownloadCandidate,
    onClose: () -> Unit,
    onUrlCaptured: (String) -> Unit
) {
    val context = LocalContext.current
    var progress by remember { mutableIntStateOf(0) }
    val bridge = remember {
        CaptchaCaptureBridge { url ->
            Handler(Looper.getMainLooper()).post {
                onUrlCaptured(url)
            }
        }
    }
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            addJavascriptInterface(bridge, CAPTCHA_BRIDGE_NAME)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (url.looksLikeApkDownload()) {
                        onUrlCaptured(url)
                        return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(CAPTCHA_CAPTURE_JS, null)
                }
            }
            setDownloadListener { url, _, _, _, _ ->
                // Authoritative signal: the page started a real download.
                onUrlCaptured(url)
            }
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }
    LaunchedEffect(candidate.captchaUrl ?: candidate.url) {
        webView.loadUrl(candidate.captchaUrl ?: candidate.url)
    }
    BackHandler {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            onClose()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = HelperDefaults.ContentPadding, vertical = HelperDefaults.ContentPadding),
            verticalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Solve captcha — ${candidate.source.label}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = candidate.versionDisplay,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                HelperOutlinedButton(
                    text = "Close",
                    onClick = onClose,
                    icon = Icons.Outlined.Close,
                    modifier = Modifier.widthIn(min = 96.dp)
                )
            }
            InfoCard(
                "Solve the captcha in the browser. When the page starts the download, " +
                    "the file is captured and returned to Morphe automatically."
            )
            if (progress > 0 && progress < 100) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            AndroidView(
                factory = { webView },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(HelperDefaults.SectionCornerRadius))
            )
        }
    }
}

/** Posts captured URLs from the WebView's JS bridge back to Kotlin. */
private class CaptchaCaptureBridge(
    private val onUrl: (String) -> Unit
) {
    @JavascriptInterface
    fun capture(url: String) {
        onUrl(url)
    }
}

private const val CAPTCHA_BRIDGE_NAME = "Android"

/**
 * Injected into every page the captcha browser finishes loading. Captures
 * clicks on download-looking links and window.open calls that the download
 * listener would not see (e.g. links opened in a new tab).
 */
private const val CAPTCHA_CAPTURE_JS = """
(function () {
  var re = /\.(apk|apks|apkm|xapk)(\?|#|$)/i;
  function looksLike(u) {
    if (!u) return false;
    try { u = decodeURIComponent(u); } catch (e) {}
    return re.test(u) || /filename[^.]*\.(apk|apks|apkm|xapk)/i.test(u);
  }
  
  document.addEventListener('click', function (e) {
    var el = e.target;
    while (el && el !== document && !(el.tagName === 'A' && el.href)) el = el.parentNode;
    if (el && el.tagName === 'A' && looksLike(el.href)) {
      window.Android && window.Android.capture(el.href);
    }
  }, true);
  var origOpen = window.open;
  window.open = function (u) {
    if (u && looksLike(u)) { window.Android && window.Android.capture(u); return null; }
    return origOpen ? origOpen.apply(window, arguments) : null;
  };
})();
"""

/** True for URLs that point at an APK-family file (extension or filename hint). */
private fun String.looksLikeApkDownload(): Boolean {
    val decoded = Uri.decode(this).lowercase(Locale.US)
    return Regex("""\.(apk|apks|apkm|xapk)(\?|#|$)""").containsMatchIn(decoded) ||
        Regex("""filename[^.]*\.(apk|apks|apkm|xapk)""").containsMatchIn(decoded)
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
    onVersionHistory: (DownloadSource) -> Unit,
    onDownloadVersion: (DownloadCandidate) -> Unit,
    onOpenHistoryEntry: (DownloadHistoryEntry) -> Unit,
    onShareHistoryEntry: (DownloadHistoryEntry) -> Unit,
    onClearHistory: () -> Unit,
    onClearLogs: () -> Unit,
    onCancel: () -> Unit,
    onCancelDownload: () -> Unit,
    onCancelFastMode: () -> Unit,
    onUseFastModeMismatch: () -> Unit,
    onSkipFastModeMismatch: () -> Unit,
    onOpenMorphe: () -> Unit,
    onSolveCaptcha: (DownloadCandidate) -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }
    var pendingFilePick by remember { mutableStateOf<DownloadCandidate?>(null) }
    val context = LocalContext.current
    var historyEntries by remember { mutableStateOf<List<DownloadHistoryEntry>>(emptyList()) }
    val refreshHistory = {
        historyEntries = DownloadHistoryStore.entries(context)
    }
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
                logs = logs,
                onClearLogs = onClearLogs,
                healthEntries = (state as? UiState.Ready)?.result?.sourceHealth().orEmpty(),
                historyEntries = historyEntries,
                onOpenHistoryEntry = onOpenHistoryEntry,
                onShareHistoryEntry = onShareHistoryEntry,
                onClearHistory = {
                    onClearHistory()
                    refreshHistory()
                },
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
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Helper",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "v${BuildConfig.VERSION_NAME}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1
                        )
                    }
                    HelperBoltButton(
                        fastMode = settings.fastMode,
                        onClick = {
                            val enabled = !settings.fastMode
                            onSettingsChange(settings.copy(fastMode = enabled))
                            Toast.makeText(
                                context,
                                if (enabled) "Fast Mode on" else "Fast Mode off",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                    HelperOutlinedButton(
                        text = "Settings",
                        onClick = {
                            refreshHistory()
                            showSettings = true
                        },
                        icon = Icons.Outlined.Settings,
                        modifier = Modifier.widthIn(min = 120.dp)
                    )
                }
            }

            if (request == null) {
                item { EmptyLaunchState(onOpenMorphe) }
                return@LazyColumn
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("App info")
                    AppInfoCard(request)
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
                            onSolveCaptcha = onSolveCaptcha,
                            onVersionHistory = onVersionHistory,
                            onDownloadVersion = onDownloadVersion,
                            onRefresh = onRefresh,
                            onCancel = onCancel,
                            installedPackageRefreshToken = installedPackageRefreshToken
                        )
                    }
                }

                is UiState.CheckingPickedFile -> item { CheckingPickedFileState(state) }
                is UiState.Downloading -> item { DownloadingState(state, onCancelDownload) }
                is UiState.Error -> item {
                    ErrorState(message = state.message, onRefresh = onRefresh, onCancel = onCancel)
                }

                is UiState.FastMode -> {
                    item {
                        FastModeCard(
                            progress = state.progress,
                            onCancel = onCancelFastMode,
                            onUseMismatch = onUseFastModeMismatch,
                            onSkipMismatch = onSkipFastModeMismatch
                        )
                    }
                    state.progress.result?.let { result ->
                        item {
                            SourceTabs(
                                request = request,
                                result = result,
                                onResolve = onResolve,
                                onDownload = onDownload,
                                onPickDownloadedFile = openDownloadedFilePicker,
                                onUseInstalledApp = onUseInstalledApp,
                                onSolveCaptcha = onSolveCaptcha,
                                onVersionHistory = onVersionHistory,
                                onDownloadVersion = onDownloadVersion,
                                onRefresh = onRefresh,
                                onCancel = onCancel,
                                installedPackageRefreshToken = installedPackageRefreshToken
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceHealthCard(entries: List<SourceHealthEntry>) {
    val hasFailures = entries.any { it.status == SourceHealthStatus.Failed }
    val hasActivity = entries.any { it.status == SourceHealthStatus.Checking }
    var expanded by remember(hasFailures) { mutableStateOf(hasFailures) }
    val failedCount = entries.count { it.status == SourceHealthStatus.Failed }
    val okCount = entries.count { it.status == SourceHealthStatus.Ok }

    val summary = when {
        hasActivity -> "Checking sources..."
        failedCount > 0 && okCount > 0 -> "$failedCount source${if (failedCount == 1) "" else "s"} had problems, $okCount OK"
        failedCount > 0 -> "$failedCount source${if (failedCount == 1) "" else "s"} had problems"
        okCount > 0 -> "$okCount source${if (okCount == 1) "" else "s"} available"
        else -> "Sources not checked yet"
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Source health",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = summary,
                    color = if (hasFailures) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            HelperOutlinedButton(
                text = if (expanded) "Hide" else "Show",
                onClick = { expanded = !expanded },
                modifier = Modifier.width(HelperDefaults.ActionShowWidth)
            )
        }

        if (expanded) {
            if (entries.isEmpty()) {
                InfoCard("No sources checked yet. Resolve candidates on the main screen to see per-source health here.")
            } else {
                entries.forEach { entry ->
                    SourceHealthRow(entry)
                }
            }
        }
    }
}

@Composable
private fun SourceHealthRow(entry: SourceHealthEntry) {
    val (dotColor, statusText) = when (entry.status) {
        SourceHealthStatus.Ok -> Color(0xFF66BB6A) to "Available"
        SourceHealthStatus.Checking -> Color(0xFFFFD166) to "Checking..."
        SourceHealthStatus.Failed -> MaterialTheme.colorScheme.error to (entry.message ?: "Failed")
        SourceHealthStatus.NoResult -> MaterialTheme.colorScheme.onSurfaceVariant to "No matching candidates"
        SourceHealthStatus.NotChecked -> MaterialTheme.colorScheme.outline to "Not checked"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(HelperDefaults.ContentPaddingSmall),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(dotColor)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = entry.source.label,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = statusText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (entry.status == SourceHealthStatus.Failed) 3 else 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HistoryEntryCard(
    entry: DownloadHistoryEntry,
    usable: Boolean,
    onOpen: () -> Unit,
    onShare: () -> Unit
) {
    HelperCard(cornerRadius = HelperDefaults.CompactCornerRadius) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(HelperDefaults.ContentPadding),
            verticalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = entry.appName,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = entry.packageName,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = formatHistoryTimestamp(entry.timestamp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                text = buildString {
                    entry.versionName?.let { append(it).append(" · ") }
                    append(entry.sourceName)
                    append(" · ")
                    append(entry.fileName)
                    if (!entry.fileKind.equals("web", ignoreCase = true)) {
                        append(" · ")
                        append(entry.fileKind.uppercase(Locale.US))
                    }
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (usable) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing)
                ) {
                    HelperButton(
                        text = "Share",
                        onClick = onShare,
                        icon = Icons.Outlined.Share,
                        modifier = Modifier.weight(1f)
                    )
                    HelperOutlinedButton(
                        text = "Open",
                        onClick = onOpen,
                        icon = Icons.Outlined.FolderOpen,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                Text(
                    text = "File no longer available (temporary hand-off files are cleaned up after Morphe copies them).",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun HelperSettingsScreen(
    settings: HelperSettings,
    onSettingsChange: (HelperSettings) -> Unit,
    logs: List<RequestLogEntry>,
    onClearLogs: () -> Unit,
    healthEntries: List<SourceHealthEntry>,
    historyEntries: List<DownloadHistoryEntry>,
    onOpenHistoryEntry: (DownloadHistoryEntry) -> Unit,
    onShareHistoryEntry: (DownloadHistoryEntry) -> Unit,
    onClearHistory: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
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

        item {
            SourceHealthCard(healthEntries)
        }

        item {
            DownloadHistorySection(
                entries = historyEntries,
                onClear = onClearHistory,
                onOpen = onOpenHistoryEntry,
                onShare = onShareHistoryEntry
            )
        }

        item {
            RequestLogsCard(
                logs = logs,
                onClearLogs = onClearLogs
            )
        }
    }
}

@Composable
private fun HelperSettingsCard(
    settings: HelperSettings,
    onSettingsChange: (HelperSettings) -> Unit
) {
    val context = LocalContext.current
    var cacheBytes by remember(context) { mutableStateOf(context.temporaryDownloadsSize()) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing)
    ) {
        SettingsGroupCard("Save downloads") {
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
            SettingsClearRow(
                sizeBytes = cacheBytes,
                onClear = {
                    context.clearTemporaryDownloads()
                    cacheBytes = 0L
                }
            )
            SettingSwitchRow(
                title = "Auto-clear after hand-off",
                description = "Remove temporary APKs after handing off to Morphe, and clear old cache files on launch.",
                checked = settings.deleteTemporaryAfterHandoff,
                onCheckedChange = {
                    onSettingsChange(settings.copy(deleteTemporaryAfterHandoff = it))
                }
            )
        }

        SettingsGroupCard("Connection") {
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
        }

        SettingsGroupCard("Fast Mode") {
            SettingSwitchRow(
                title = "Fast Mode",
                description = "Auto-find the exact requested version and version code across sources " +
                    "(APKMirror, Uptodown, APKPure, APKCombo, Aptoide) and return it to Morphe automatically. " +
                    "Format differences are allowed.",
                checked = settings.fastMode,
                onCheckedChange = {
                    onSettingsChange(settings.copy(fastMode = it))
                }
            )
        }

        SettingsGroupCard("Logging") {
            SettingSwitchRow(
                title = "Log to Logcat",
                description = "Write request, result, and source HTTP details to the system log (adb logcat) for debugging.",
                checked = settings.logcatLogging,
                onCheckedChange = {
                    onSettingsChange(settings.copy(logcatLogging = it))
                }
            )
        }
    }
}

@Composable
private fun SettingsGroupCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    // No card background: the group is just a section title followed by its
    // individual option rows, which carry their own card styling.
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing)
    ) {
        SectionTitle(title)
        content()
    }
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
        // Stay on the dark card style; the selected state is a subtle primary
        // tint + border + check icon instead of a solid light fill that clashes
        // with the dark theme.
        color = if (selected) {
            colors.primary.copy(alpha = 0.13f)
        } else {
            colors.surfaceColorAtElevation(2.dp)
        },
        contentColor = colors.onSurface,
        border = BorderStroke(
            1.dp,
            if (selected) {
                colors.primary.copy(alpha = 0.55f)
            } else {
                colors.outlineVariant.copy(alpha = 0.45f)
            }
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
                Text(
                    title,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) colors.primary else colors.onSurface
                )
                Text(
                    description,
                    color = if (selected) {
                        colors.primary.copy(alpha = 0.78f)
                    } else {
                        colors.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = "Selected",
                    tint = colors.primary
                )
            }
        }
    }
}

@Composable
private fun SettingsClearRow(
    sizeBytes: Long,
    onClear: () -> Unit
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
                Text("Clear storage, cache & downloads", fontWeight = FontWeight.Bold)
                Text(
                    text = if (sizeBytes > 0L) {
                        "${sizeBytes.formatBytes()} in cache — tap Clear to remove it now."
                    } else {
                        "Cache is empty — nothing to clear."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            HelperOutlinedButton(
                text = "Clear",
                onClick = onClear,
                modifier = Modifier.widthIn(min = 96.dp)
            )
        }
    }
}

private fun Long.formatBytes(): String {
    if (this <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) "${toLong()} B" else String.format(Locale.US, "%.1f %s", value, units[unit])
}

@Composable
private fun SettingSwitchRow(
    title: String,
    description: String,
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
                Text(title, fontWeight = FontWeight.Bold)
                Text(
                    description,
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
private fun EmptyLaunchState(onOpenMorphe: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing)) {
        InfoCard("Open this helper from Morphe Manager when it asks for an original APK.")
        HelperButton(
            text = "Open Morphe Manager",
            onClick = onOpenMorphe,
            icon = Icons.Outlined.OpenInNew,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun HelperBoltButton(
    fastMode: Boolean,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.size(HelperDefaults.ButtonHeight),
        shape = RoundedCornerShape(HelperDefaults.ButtonCornerRadius),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (fastMode) primary.copy(alpha = 0.28f) else Color.Transparent,
            contentColor = if (fastMode) {
                primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
            }
        ),
        border = BorderStroke(
            1.dp,
            if (fastMode) primary.copy(alpha = 0.6f) else primary.copy(alpha = 0.32f)
        ),
        contentPadding = PaddingValues(0.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Bolt,
            contentDescription = if (fastMode) "Fast Mode on" else "Fast Mode off",
            modifier = Modifier.size(HelperDefaults.IconSizeSmall)
        )
    }
}

@Composable
private fun AppInfoCard(request: HelperRequest) {
    // Collapsed by default so the Sources/Variants sections stay visible on
    // screen; tap the header to expand the request and device details.
    var expanded by rememberSaveable(request.packageName) { mutableStateOf(false) }

    HelperCard(cornerRadius = HelperDefaults.SectionCornerRadius) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(HelperDefaults.ContentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AppInfoHeader(request, expanded = expanded, onToggle = { expanded = !expanded })

            if (expanded) {
                androidx.compose.material3.HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                )

                AppInfoSection("Request") {
                    val versionLabel = listOfNotNull(
                        request.requestedVersionName,
                        request.versionCodeSummary?.let { "build $it" }
                    ).joinToString(" · ").ifBlank { "Any compatible" }
                    AppInfoRow(label = "Version", value = versionLabel)
                    AppInfoRow(label = "Format", value = request.requestedFormatLabel)
                }

                val abis = request.availableAbis
                if (abis.isNotEmpty()) {
                    AppInfoSection("Device") {
                        AppInfoChipRow(label = "ABI", items = abis)
                    }
                }

                if (request.stockInstallRequired) {
                    Text(
                        text = "Root mount may require the stock app before Morphe patches it.",
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}

@Composable
private fun AppInfoHeader(
    request: HelperRequest,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    var copied by remember(request.packageName) { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(HelperDefaults.SectionCornerRadius))
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppAvatar(initial = request.appName.firstOrNull()?.uppercaseChar() ?: '?')

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = request.appName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = request.packageName,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    text = if (copied) "Copied" else "Copy",
                    color = if (copied) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable {
                            clipboard?.setPrimaryClip(
                                ClipData.newPlainText("package", request.packageName)
                            )
                            copied = true
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Icon(
            imageVector = if (expanded) {
                Icons.Outlined.ExpandLess
            } else {
                Icons.Outlined.ExpandMore
            },
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AppAvatar(initial: Char) {
    val colors = listOf(
        Color(0xFF1A73E8),
        Color(0xFF34A853),
        Color(0xFFFBBC04),
        Color(0xFFEA4335),
        Color(0xFF4285F4),
        Color(0xFFF25C1B)
    )
    val color = colors[initial.code % colors.size]
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial.toString(),
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun AppInfoSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SectionTitle(title)
        content()
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold
    )
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
            modifier = Modifier.weight(0.30f)
        )
        Text(
            text = value,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.70f)
        )
    }
}

@Composable
private fun AppInfoChipRow(label: String, items: List<String>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.30f)
        )
        FlowRow(
            modifier = Modifier.weight(0.70f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items.forEach { abi ->
                HelperChip(text = abi)
            }
        }
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
    onSolveCaptcha: (DownloadCandidate) -> Unit,
    onVersionHistory: (DownloadSource) -> Unit,
    onDownloadVersion: (DownloadCandidate) -> Unit,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
    installedPackageRefreshToken: Int
) {
    val groups = result.sourceGroups

    val pagerState = rememberPagerState(initialPage = 0) { groups.size }
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Sources")
            SourceSelector(
                groups = groups,
                selectedIndex = pagerState.currentPage,
                onSelect = { index ->
                    scope.launch {
                        // Slide for adjacent sources (feels like a swipe), but jump
                        // straight to distant ones instead of dragging the pager
                        // through every source in between.
                        if (abs(index - pagerState.currentPage) <= 1) {
                            pagerState.animateScrollToPage(index)
                        } else {
                            pagerState.scrollToPage(index)
                        }
                    }
                }
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Commands")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing)
            ) {
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

        HorizontalPager(
            state = pagerState,
            key = { index -> groups[index].source },
            // Only compose the current page so the pager's height matches the page on
            // screen instead of the tallest neighbor (which left dead space on short
            // pages). Pages are top-aligned so content never floats away from the pills.
            beyondViewportPageCount = 0,
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            SourcePageContent(
                request = request,
                group = groups[page],
                onResolve = onResolve,
                onDownload = onDownload,
                onPickDownloadedFile = onPickDownloadedFile,
                onUseInstalledApp = onUseInstalledApp,
                onSolveCaptcha = onSolveCaptcha,
                onVersionHistory = onVersionHistory,
                onDownloadVersion = onDownloadVersion,
                installedPackageRefreshToken = installedPackageRefreshToken
            )
        }
    }
}

private enum class SourceSubTab(val label: String) {
    Manual("Manual"),
    Recommended("Recommended"),
    Latest("Latest"),
    History("History")
}

/** Sources that can resolve the exact requested version from their own data. */
private val DownloadSource.supportsRecommended: Boolean
    get() = when (this) {
        // Mi9 exposes a real version-history page that the in-app captcha
        // browser opens; its recommended candidate routes there. APK
        // Downloader stays manual-only (Cloudflare-gated with no version list).
        DownloadSource.APK_DOWNLOADER,
        DownloadSource.AURORA,
        DownloadSource.PLAY -> false
        else -> true
    }

/** Sources that expose a version history list. */
private val DownloadSource.supportsHistory: Boolean
    get() = when (this) {
        // Evozi has an old-versions page but no history list; APK Downloader
        // is Cloudflare-gated with no version list. Mi9's history tab offers a
        // "browse the version history in the in-app browser" row. Aurora/Play
        // never offer originals.
        DownloadSource.EVOZI,
        DownloadSource.APK_DOWNLOADER,
        DownloadSource.AURORA,
        DownloadSource.PLAY -> false
        else -> true
    }

@Composable
private fun SourcePageContent(
    request: HelperRequest,
    group: SourceCandidateGroup,
    onResolve: (DownloadSource, CandidateOption) -> Unit,
    onDownload: (DownloadCandidate) -> Unit,
    onPickDownloadedFile: (DownloadCandidate) -> Unit,
    onUseInstalledApp: (DownloadCandidate) -> Unit,
    onSolveCaptcha: (DownloadCandidate) -> Unit,
    onVersionHistory: (DownloadSource) -> Unit,
    onDownloadVersion: (DownloadCandidate) -> Unit,
    installedPackageRefreshToken: Int
) {
    val subTabs = remember(group, request) {
        buildList {
            if (group.manual.isNotEmpty()) add(SourceSubTab.Manual)
            if (request.hasKnownVersionRequest && group.source.supportsRecommended) {
                add(SourceSubTab.Recommended)
            }
            add(SourceSubTab.Latest)
            if (group.source.supportsHistory) {
                add(SourceSubTab.History)
            }
        }
    }
    var selectedTab by rememberSaveable(group.source) {
        mutableStateOf(subTabs.firstOrNull() ?: SourceSubTab.Latest)
    }
    val safeTab = subTabs.firstOrNull { it == selectedTab } ?: subTabs.firstOrNull()
        ?: SourceSubTab.Latest

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Variants")
            SubTabRow(
                tabs = subTabs,
                selected = safeTab,
                onSelect = { selectedTab = it }
            )
        }

        when (safeTab) {
            SourceSubTab.Manual -> {
                InfoCard(
                    "Manual: open the link to download this app from the source's site, " +
                        "then tap \"Select downloaded file\" to return it to Morphe."
                )
                group.manual.forEach { candidate ->
                    CandidateCard(
                        request = request,
                        candidate = candidate,
                        onDownload = { onDownload(candidate) },
                        onPickDownloadedFile = { onPickDownloadedFile(candidate) },
                        onUseInstalledApp = { onUseInstalledApp(candidate) },
                        onSolveCaptcha = { onSolveCaptcha(candidate) },
                        installedPackageRefreshToken = installedPackageRefreshToken
                    )
                }
            }

            SourceSubTab.Recommended -> {
                InfoCard(
                    "Recommended: finds the exact version Morphe requested on this source, " +
                        "then download it to return to Morphe."
                )
                CandidateResolveSection(
                    request = request,
                    state = group.recommended,
                    actionText = "Find recommended",
                    loadingText = "Checking recommended version...",
                    emptyText = "Requested version was not found on this source. Use Manual mode for this source instead.",
                    onResolve = {
                        onResolve(group.source, CandidateOption.REQUESTED)
                    },
                    onDownload = onDownload,
                    onPickDownloadedFile = onPickDownloadedFile,
                    onUseInstalledApp = onUseInstalledApp,
                    onSolveCaptcha = onSolveCaptcha,
                    installedPackageRefreshToken = installedPackageRefreshToken
                )
            }

            SourceSubTab.Latest -> {
                when (group.source) {
                    DownloadSource.AURORA -> {
                        InfoCard("Aurora only provides the latest Play Store version. Use Manual mode if you need a specific version.")
                    }
                    DownloadSource.PLAY -> {
                        InfoCard("Play opens the official Play Store listing for this app. Use Manual mode if you need a specific version.")
                    }
                    else -> {
                        InfoCard(
                            "Latest: finds the newest version of this app on the source, " +
                                "then download it to return to Morphe."
                        )
                    }
                }
                CandidateResolveSection(
                    request = request,
                    state = group.latest,
                    actionText = "Find latest",
                    loadingText = "Checking latest version...",
                    emptyText = "Latest version was not found on this source. Use Manual mode for this source instead.",
                    onResolve = {
                        onResolve(group.source, CandidateOption.LATEST)
                    },
                    onDownload = onDownload,
                    onPickDownloadedFile = onPickDownloadedFile,
                    onUseInstalledApp = onUseInstalledApp,
                    onSolveCaptcha = onSolveCaptcha,
                    installedPackageRefreshToken = installedPackageRefreshToken
                )
            }

            SourceSubTab.History -> {
                InfoCard(
                    "Version history: lists every version this source offers, " +
                        "then download any of them to return to Morphe."
                )
                VersionHistorySection(
                    state = group.history,
                    onResolve = { onVersionHistory(group.source) },
                    onDownloadVersion = onDownloadVersion,
                    onSolveCaptcha = onSolveCaptcha
                )
            }
        }
    }
}

@Composable
private fun SubTabRow(
    tabs: List<SourceSubTab>,
    selected: SourceSubTab,
    onSelect: (SourceSubTab) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(HelperDefaults.ContentPaddingSmall)
    ) {
        tabs.forEach { tab ->
            SourcePill(
                text = tab.label,
                selected = tab == selected,
                onClick = { onSelect(tab) },
                modifier = Modifier.weight(1f),
                height = 36.dp,
                minWidth = 0.dp,
                textStyle = MaterialTheme.typography.labelMedium,
                textHorizontalPadding = 8.dp
            )
        }
    }
}

@Composable
private fun VersionHistorySection(
    state: VersionHistoryState,
    onResolve: () -> Unit,
    onDownloadVersion: (DownloadCandidate) -> Unit,
    onSolveCaptcha: (DownloadCandidate) -> Unit
) {
    when (state) {
        VersionHistoryState.Idle -> {
            HelperOutlinedButton(
                text = "Load versions",
                onClick = onResolve,
                icon = Icons.Outlined.Search,
                modifier = Modifier.fillMaxWidth()
            )
        }

        VersionHistoryState.Loading -> {
            HelperCard(cornerRadius = HelperDefaults.CompactCornerRadius) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(HelperDefaults.ContentPadding),
                    horizontalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text("Loading versions...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        is VersionHistoryState.Error -> {
            InfoCard(state.message)
            HelperOutlinedButton(
                text = "Try again",
                onClick = onResolve,
                icon = Icons.Outlined.Refresh,
                modifier = Modifier.fillMaxWidth()
            )
        }

        is VersionHistoryState.Done -> {
            if (state.candidates.isEmpty()) {
                InfoCard("No version list was available for this source.")
            } else {
                state.candidates.forEach { candidate ->
                    VersionHistoryRow(
                        candidate = candidate,
                        showOpenLink = candidate.identityKey() in state.noDirectDownloadKeys,
                        onDownloadVersion = { onDownloadVersion(candidate) },
                        onSolveCaptcha = { onSolveCaptcha(candidate) }
                    )
                }
            }
        }
    }
}

@Composable
private fun VersionHistoryRow(
    candidate: DownloadCandidate,
    showOpenLink: Boolean,
    onDownloadVersion: () -> Unit,
    onSolveCaptcha: (DownloadCandidate) -> Unit
) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    HelperCard(cornerRadius = HelperDefaults.CompactCornerRadius) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(HelperDefaults.ContentPadding),
            horizontalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = candidate.versionDisplay,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when {
                        // Captcha-gated rows (e.g. Mi9's version history) are
                        // browsed in the in-app captcha browser, not downloaded.
                        candidate.captchaUrl != null && !candidate.directDownload ->
                            "Browsable in the in-app browser (version history)"
                        showOpenLink -> "No direct download — open the version page"
                        // History rows know only the version page until the
                        // user taps Download, which resolves the real format.
                        // "web" is a placeholder, not an actual file type, so
                        // don't render it as if the row just links out.
                        candidate.fileKind.equals("web", ignoreCase = true) ->
                            "Direct download — format resolved on download"
                        else -> candidate.fileKind.uppercase(Locale.US)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            when {
                (showOpenLink || (candidate.captchaUrl != null && !candidate.directDownload)) &&
                    candidate.source != DownloadSource.AURORA &&
                    candidate.source != DownloadSource.PLAY -> {
                    HelperButton(
                        text = "Solve captcha",
                        onClick = { onSolveCaptcha(candidate) },
                        icon = Icons.Outlined.VerifiedUser,
                        modifier = Modifier.widthIn(min = 140.dp)
                    )
                }
                showOpenLink -> {
                    HelperOutlinedButton(
                        text = "Open link",
                        onClick = {
                            if (candidate.source == DownloadSource.PLAY) {
                                context.openPlayStoreListing(candidate.packageName, candidate.url)
                            } else {
                                uriHandler.openUri(candidate.url)
                            }
                        },
                        icon = Icons.Outlined.OpenInBrowser,
                        modifier = Modifier.widthIn(min = 120.dp)
                    )
                }
                else -> {
                    HelperButton(
                        text = "Download",
                        onClick = onDownloadVersion,
                        icon = Icons.Outlined.Download,
                        modifier = Modifier.widthIn(min = 120.dp)
                    )
                }
            }
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
    onSolveCaptcha: (DownloadCandidate) -> Unit,
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
                        onSolveCaptcha = { onSolveCaptcha(candidate) },
                        installedPackageRefreshToken = installedPackageRefreshToken
                    )
                }
            }
        }

        is ResolveState.Error -> {
            InfoCard(state.message)
            state.fallbackCandidate?.let { candidate ->
                CandidateCard(
                    request = request,
                    candidate = candidate,
                    onDownload = { onDownload(candidate) },
                    onPickDownloadedFile = { onPickDownloadedFile(candidate) },
                    onUseInstalledApp = { onUseInstalledApp(candidate) },
                    onSolveCaptcha = { onSolveCaptcha(candidate) },
                    installedPackageRefreshToken = installedPackageRefreshToken
                )
            }
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
    // Split the sources into two balanced rows; pills stretch to fill each row end to end.
    val firstRowCount = (groups.size + 1) / 2
    Column(verticalArrangement = Arrangement.spacedBy(HelperDefaults.ContentPaddingSmall)) {
        var globalIndex = 0
        groups.chunked(firstRowCount).forEach { rowGroups ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(HelperDefaults.ContentPaddingSmall)
            ) {
                rowGroups.forEach { group ->
                    val index = globalIndex++
                    SourcePill(
                        text = group.source.label,
                        selected = index == selectedIndex,
                        onClick = { onSelect(index) },
                        modifier = Modifier.weight(1f),
                        minWidth = 0.dp,
                        textStyle = MaterialTheme.typography.labelMedium,
                        textHorizontalPadding = 8.dp
                    )
                }
            }
        }
    }
}

@Composable
private fun SourcePill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    minWidth: Dp = 92.dp,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
    textHorizontalPadding: Dp = HelperDefaults.ContentPadding
) {
    val shape = RoundedCornerShape(50)
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .height(height)
            .widthIn(min = minWidth)
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = if (selected) colors.primary else colors.surfaceColorAtElevation(3.dp),
        contentColor = if (selected) colors.onPrimary else colors.onSurfaceVariant,
        border = BorderStroke(
            1.dp,
            if (selected) colors.primary else colors.outlineVariant.copy(alpha = 0.55f)
        )
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = textStyle,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = textHorizontalPadding)
            )
        }
    }
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
private fun DownloadHistorySection(
    entries: List<DownloadHistoryEntry>,
    onClear: () -> Unit,
    onOpen: (DownloadHistoryEntry) -> Unit,
    onShare: (DownloadHistoryEntry) -> Unit
) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Download history",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            HelperOutlinedButton(
                text = "Clear",
                onClick = onClear,
                modifier = Modifier.width(HelperDefaults.ActionClearWidth)
            )
            HelperOutlinedButton(
                text = if (expanded) "Hide" else "Show",
                onClick = { expanded = !expanded },
                modifier = Modifier.width(HelperDefaults.ActionShowWidth)
            )
        }

        if (expanded) {
            if (entries.isEmpty()) {
                InfoCard("No hand-offs recorded yet. Downloads and picked files you return to Morphe show up here.")
            } else {
                entries.forEach { entry ->
                    val usable = remember(entry.uri) { context.isHistoryUriUsable(entry.uri) }
                    HistoryEntryCard(
                        entry = entry,
                        usable = usable,
                        onOpen = { onOpen(entry) },
                        onShare = { onShare(entry) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RequestLogsCard(
    logs: List<RequestLogEntry>,
    onClearLogs: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
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
                modifier = Modifier.width(HelperDefaults.ActionClearWidth)
            )
            HelperOutlinedButton(
                text = if (expanded) "Hide" else "Show",
                onClick = { expanded = !expanded },
                modifier = Modifier.width(HelperDefaults.ActionShowWidth)
            )
        }

        if (expanded) {
            if (logs.isEmpty()) {
                InfoCard("No logs yet.")
            } else {
                HelperCard(cornerRadius = HelperDefaults.CompactCornerRadius) {
                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(HelperDefaults.ContentPadding),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
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
}

@Composable
private fun CandidateCard(
    request: HelperRequest,
    candidate: DownloadCandidate,
    onDownload: () -> Unit,
    onPickDownloadedFile: () -> Unit,
    onUseInstalledApp: () -> Unit,
    onSolveCaptcha: (DownloadCandidate) -> Unit,
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

    // A plain web link whose only content is the Open link action (manual-mode
    // rows, and info-less Play/Aurora listings) renders as a bare outlined
    // button to match the idle action buttons instead of a filled card around
    // a single button.
    val bareLink = candidate.note == null &&
        !hasResolvedCandidateInfo &&
        !candidate.directDownload &&
        (candidate.option == CandidateOption.MANUAL ||
            candidate.source == DownloadSource.AURORA ||
            candidate.source == DownloadSource.PLAY)

    val body: @Composable ColumnScope.() -> Unit = {
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
            // Resolved candidates (Recommended/Latest/History) from every source
            // except Aurora and Play can fall back to the in-app captcha
            // browser: it opens the candidate's page in a real WebView (passing
            // any Cloudflare challenge) and captures the download URL the page
            // produces. Manual-mode links stay plain open-link rows — their job
            // is the external "select downloaded file" flow.
            if (candidate.option != CandidateOption.MANUAL &&
                candidate.source != DownloadSource.AURORA &&
                candidate.source != DownloadSource.PLAY
            ) {
                HelperButton(
                    text = "Solve captcha in app",
                    onClick = { onSolveCaptcha(candidate) },
                    icon = Icons.Outlined.VerifiedUser,
                    modifier = Modifier.fillMaxWidth()
                )
            }
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

    if (bareLink) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing)
        ) {
            body()
        }
    } else {
        HelperCard(cornerRadius = HelperDefaults.SectionCornerRadius) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(HelperDefaults.ContentPadding),
                verticalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing)
            ) {
                body()
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
private fun FastModeCard(
    progress: FastModeProgress,
    onCancel: () -> Unit,
    onUseMismatch: () -> Unit,
    onSkipMismatch: () -> Unit
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
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text("Fast Mode", fontWeight = FontWeight.Bold)
                    progress.sourceLabel?.let { source ->
                        Text(
                            text = source,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                if (!progress.done && progress.percent == null && !progress.awaitingDecision) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp
                    )
                }
            }
            Text(
                text = progress.detail,
                color = when {
                    progress.done && !progress.succeeded -> MaterialTheme.colorScheme.error
                    progress.done -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.bodyMedium
            )
            if (progress.awaitingDecision) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(HelperDefaults.ContentPadding),
                        horizontalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = progress.mismatchDetail ?: "Version code mismatch.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(HelperDefaults.ItemSpacing)
                ) {
                    HelperButton(
                        text = "Use this version",
                        onClick = onUseMismatch,
                        modifier = Modifier.weight(1f)
                    )
                    HelperOutlinedButton(
                        text = "Skip to next source",
                        onClick = onSkipMismatch,
                        modifier = Modifier.weight(1f)
                    )
                }
                HelperOutlinedButton(
                    text = "Cancel",
                    onClick = onCancel,
                    icon = Icons.Outlined.Close,
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (progress.percent != null && !progress.done) {
                LinearProgressIndicator(
                    progress = { progress.percent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${progress.percent}%",
                        fontWeight = FontWeight.Medium
                    )
                    HelperOutlinedButton(
                        text = "Cancel",
                        onClick = onCancel,
                        icon = Icons.Outlined.Close
                    )
                }
            } else if (!progress.done) {
                HelperOutlinedButton(
                    text = "Cancel",
                    onClick = onCancel,
                    icon = Icons.Outlined.Close,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun DownloadingState(state: UiState.Downloading, onCancel: () -> Unit) {
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${state.percent}%", fontWeight = FontWeight.Medium)
                HelperOutlinedButton(
                    text = "Cancel",
                    onClick = onCancel,
                    icon = Icons.Outlined.Close
                )
            }
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

internal data class HelperRequest(
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
        // Some parsers tag a candidate with the whole requested label (e.g.
        // "APK/APKM/APKS/XAPK") instead of a single kind. Split on '/' so any
        // listed kind counts as a match instead of a false "Format mismatch".
        val kinds = fileKind.lowercase(Locale.US).split('/')
        return kinds.any { it in requestedFileKinds }
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

    /**
     * Strict matcher used by Fast Mode, where the exact version name AND the
     * exact version code are both required. Unlike [matchesRequestedVersion]
     * (which accepts a name-only match), a candidate that reports a version
     * code outside the requested set is rejected up front — so Fast Mode
     * doesn't download a file that validation would reject anyway. When the
     * source doesn't report a code, the name match is accepted (validation is
     * still the backstop for the downloaded artifact).
     */
    fun matchesRequestedVersionStrict(candidateVersionName: String?, candidateVersionCode: Long?): Boolean {
        if (versionName == null && requestedVersionCodes.isEmpty()) return false

        val nameRequired = requestedVersionName != null
        val nameMatches = candidateVersionName != null &&
            candidateVersionName.versionNameEquals(requestedVersionName)
        val codeRequired = requestedVersionCodes.isNotEmpty() && candidateVersionCode != null
        val codeMatches = requestedVersionCodes.isEmpty() ||
            candidateVersionCode == null ||
            (candidateVersionCode > 0L && candidateVersionCode in requestedVersionCodes)

        return (!nameRequired || nameMatches) && (!codeRequired || codeMatches)
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
            DownloadSource.EVOZI -> listOf("apkcube.com", "evozi.com")
            DownloadSource.MI9 -> listOf("mi9.com")
            DownloadSource.APK_DOWNLOADER -> listOf("apkdownloader.pages.dev")
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
                    // Callers send the version code as either an Int (am --ei, some
                    // app stores) or a Long. getLongExtra silently returns the
                    // default for Int extras, so try Long first and fall back to Int.
                    intent.getLongExtra(DownloadHelperContract.EXTRA_VERSION_CODE, 0L)
                        .takeIf { it > 0L }
                        ?: intent.getIntExtra(DownloadHelperContract.EXTRA_VERSION_CODE, 0)
                            .toLong()
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

    fun withHistoryState(
        source: DownloadSource,
        state: VersionHistoryState
    ): CandidateResult = copy(
        sourceGroups = sourceGroups.map { group ->
            if (group.source == source) group.copy(history = state) else group
        }
    )

    fun markHistoryCandidateNoDirectDownload(
        source: DownloadSource,
        candidateKey: String
    ): CandidateResult = copy(
        sourceGroups = sourceGroups.map { group ->
            if (group.source == source && group.history is VersionHistoryState.Done) {
                val done = group.history as VersionHistoryState.Done
                group.copy(
                    history = done.copy(
                        noDirectDownloadKeys = done.noDirectDownloadKeys + candidateKey
                    )
                )
            } else {
                group
            }
        }
    )
}

private data class SourceCandidateGroup(
    val source: DownloadSource,
    val manual: List<DownloadCandidate>,
    val recommended: ResolveState,
    val latest: ResolveState,
    val history: VersionHistoryState = VersionHistoryState.Idle
)

private data class ResolveOutcome(
    val candidates: List<DownloadCandidate>,
    val errorMessage: String? = null,
    val fallbackCandidate: DownloadCandidate? = null,
    val notFoundMessage: String? = null
)

private sealed interface ResolveState {
    data object Idle : ResolveState
    data object Loading : ResolveState
    data class Done(val candidates: List<DownloadCandidate>) : ResolveState
    data class Error(
        val message: String,
        val fallbackCandidate: DownloadCandidate? = null
    ) : ResolveState
}

private sealed interface VersionHistoryState {
    data object Idle : VersionHistoryState
    data object Loading : VersionHistoryState
    data class Done(
        val candidates: List<DownloadCandidate>,
        // Identity keys of candidates whose direct download could not be
        // resolved; those rows render an "Open link" action instead of
        // "Download" so the user is not stuck in a resolve-then-error loop.
        val noDirectDownloadKeys: Set<String> = emptySet()
    ) : VersionHistoryState
    data class Error(val message: String) : VersionHistoryState
}

private enum class SourceHealthStatus {
    NotChecked,
    Checking,
    Ok,
    NoResult,
    Failed
}

private data class SourceHealthEntry(
    val source: DownloadSource,
    val status: SourceHealthStatus,
    val message: String? = null
)

private fun CandidateResult.sourceHealth(): List<SourceHealthEntry> =
    sourceGroups.map { group ->
        val states = listOf(group.recommended, group.latest)
        val failures = states.filterIsInstance<ResolveState.Error>()
        val loadedCandidates = states.filterIsInstance<ResolveState.Done>().map { it.candidates }
        val anyLoading = states.any { it is ResolveState.Loading }
        when {
            failures.isNotEmpty() -> {
                SourceHealthEntry(
                    source = group.source,
                    status = SourceHealthStatus.Failed,
                    message = failures.first().message
                )
            }
            loadedCandidates.any { it.isNotEmpty() } -> SourceHealthEntry(group.source, SourceHealthStatus.Ok)
            loadedCandidates.isNotEmpty() -> SourceHealthEntry(group.source, SourceHealthStatus.NoResult)
            anyLoading -> SourceHealthEntry(group.source, SourceHealthStatus.Checking)
            else -> SourceHealthEntry(group.source, SourceHealthStatus.NotChecked)
        }
    }

internal data class ApkMirrorLatestInfo(
    val versionName: String?,
    val openUrl: String
)

internal data class ApkMirrorVariant(
    val url: String,
    val type: String,
    val fileKind: String,
    val arch: String?,
    val dpi: String?,
    val isBundle: Boolean
)

internal data class UptodownVersionResponse(
    val data: List<UptodownVersionEntry> = emptyList()
)

internal data class UptodownVersionEntry(
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

internal data class UptodownVersionUrl(
    val url: String? = null,
    @SerializedName("extraURL")
    val extraUrl: String? = null,
    @SerializedName("versionID")
    val versionId: Long? = null
)

internal data class UptodownVariantResponse(
    val content: String? = null
)

internal data class UptodownVariantFile(
    val fileId: String,
    val fileKind: String,
    val archLabel: String?
)

internal data class DownloadCandidate(
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
    val files: List<CandidateDownloadFile> = emptyList(),
    // When set, the source gates the file behind a captcha/JS challenge that
    // only a real browser can pass. The card then offers a "Solve captcha in
    // app" action that opens this URL in an embedded WebView and captures the
    // download the page produces.
    val captchaUrl: String? = null
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

internal fun DownloadCandidate.identityKey(): String =
    "${source.name}:$versionName:$versionCode:$fileKind:$variantLabel:$url"

internal data class CandidateDownloadFile(
    val url: String,
    val fileName: String,
    val size: Long? = null,
    val referer: String? = null
)

internal data class DownloadedApkMetadata(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long?
)

internal enum class DownloadSource(
    val label: String,
    val sortIndex: Int,
    val supportsManualArtifactPicker: Boolean = true
) {
    APK_MIRROR("APKMirror", 0),
    UPTODOWN("Uptodown", 1),
    APK_PURE("APKPure", 2),
    APK_COMBO("APKCombo", 3),
    APTOIDE("Aptoide", 4),
    EVOZI("Evozi", 5),
    MI9("Mi9", 6),
    APK_DOWNLOADER("APK Downloader", 7),
    AURORA("Aurora", 8, supportsManualArtifactPicker = false),
    PLAY("Play", 9, supportsManualArtifactPicker = false)
}

private fun DownloadSource.searchDomain(): String? = when (this) {
    DownloadSource.APK_MIRROR -> "apkmirror.com"
    DownloadSource.UPTODOWN -> "uptodown.com"
    DownloadSource.APK_PURE -> "apkpure.com"
    DownloadSource.APK_COMBO -> "apkcombo.com"
    DownloadSource.APTOIDE -> "aptoide.com"
    DownloadSource.EVOZI -> "apkcube.com"
    DownloadSource.MI9 -> "mi9.com"
    DownloadSource.APK_DOWNLOADER -> "apkdownloader.pages.dev"
    DownloadSource.PLAY -> "play.google.com"
    DownloadSource.AURORA -> null
}

internal enum class CandidateOption {
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

internal enum class VersionStatus(val label: String) {
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
    data class FastMode(val progress: FastModeProgress) : UiState
}

private enum class FastModeChoice { USE, NEXT }

// Sources Fast Mode skips: they never offer direct downloads (Aurora/Play
// return installed/Play versions, the downloader services are captcha-gated).
private val NON_FAST_MODE_SOURCES = setOf(
    DownloadSource.AURORA,
    DownloadSource.PLAY,
    DownloadSource.EVOZI,
    DownloadSource.MI9,
    DownloadSource.APK_DOWNLOADER
)

private sealed interface FastModeFindResult {
    data class Exact(val candidate: DownloadCandidate) : FastModeFindResult
    data class VersionMismatch(val candidate: DownloadCandidate) : FastModeFindResult
    object None : FastModeFindResult
}

private data class FastModeProgress(
    val sourceLabel: String? = null,
    val detail: String = "Starting…",
    val percent: Int? = null,
    val done: Boolean = false,
    val succeeded: Boolean = false,
    // Present once Fast Mode finishes so the regular source list stays reachable.
    val result: CandidateResult? = null,
    // Set while Fast Mode waits for the user to accept a version-code mismatch.
    val awaitingDecision: Boolean = false,
    val mismatchDetail: String? = null
)

internal object DownloadHelperContract {
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

internal interface ApkPureApi {
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

internal data class ApkPureUpdateRequest(
    val app_info_for_update: List<ApkPureAppInfo>,
    val android_id: String = Random.nextLong().toString(16),
    val application_id: String = "com.apkpure.aegon",
    val cached_size: Long = -1
)

internal data class ApkPureAppInfo(
    val package_name: String,
    val version_code: Long,
    val is_system: Boolean = false,
    val version_id: String = "",
    val cached_size: Int = -1
)

internal data class ApkPureUpdateResponse(
    val retcode: Int = 0,
    val app_update_response: List<ApkPureAppUpdate> = emptyList()
)

internal data class ApkPureAppUpdate(
    val package_name: String = "",
    val version_code: Long = 0L,
    val version_name: String = "",
    val label: String = "",
    val asset: ApkPureAsset = ApkPureAsset()
)

internal data class ApkPureAsset(
    val type: String = "",
    val url: String = ""
)

internal data class ApkPureVersionEntry(
    val versionName: String?,
    val versionCode: Long?,
    val downloadPageUrl: String,
    val fileKind: String
)

internal data class ApkPureDeviceHeader(
    val device_info: ApkPureDeviceInfo = ApkPureDeviceInfo()
)

internal data class ApkPureDeviceInfo(
    val abis: List<String> = runCatching { Build.SUPPORTED_ABIS.toList() }.getOrDefault(emptyList()),
    val android_id: String = Random.nextLong().toString(16),
    val os_ver: String = runCatching { Build.VERSION.SDK_INT.toString() }.getOrDefault(""),
    val os_ver_name: String = runCatching { Build.VERSION.RELEASE }.getOrNull() ?: "",
    val platform: Int = 1
)

internal interface AptoideApi {
    @POST("listSearchApps")
    suspend fun searchApps(@Body request: AptoideSearchRequest): AptoideSearchResponse

    @GET("getApp")
    suspend fun getAppByPackage(@Query("package_name") packageName: String): AptoideGetAppResponse

    @GET("getApp")
    suspend fun getAppById(@Query("app_id") appId: Long): AptoideGetAppResponse

    @GET("listAppVersions")
    suspend fun listAppVersionsByPackage(
        @Query("package_name") packageName: String,
        @Query("limit") limit: Long = 100L
    ): AptoideVersionListResponse

    @GET("listAppVersions")
    suspend fun listAppVersionsById(
        @Query("app_id") appId: Long,
        @Query("limit") limit: Long = 100L
    ): AptoideVersionListResponse
}

internal data class AptoideSearchRequest(
    val query: String = "",
    val limit: String = "10",
    val q: String? = null,
    val not_apk_tags: String = "alpha,beta",
    val store_ids: List<Long>? = listOf(15L, 711454L)
)

internal data class AptoideSearchResponse(
    val datalist: AptoideDataList = AptoideDataList()
)

internal data class AptoideDataList(
    val list: List<AptoideApp> = emptyList()
)

internal data class AptoideGetAppResponse(
    val nodes: AptoideNodes = AptoideNodes()
)

internal data class AptoideVersionListResponse(
    val list: List<AptoideApp> = emptyList()
)

internal data class AptoideNodes(
    val meta: AptoideMetaNode = AptoideMetaNode()
)

internal data class AptoideMetaNode(
    val data: AptoideApp = AptoideApp()
)

internal data class AptoideNextData(
    val props: AptoideNextProps = AptoideNextProps()
)

internal data class AptoideNextProps(
    val pageProps: AptoidePageProps = AptoidePageProps()
)

internal data class AptoidePageProps(
    val app: AptoideApp = AptoideApp(),
    val packageName: String = "",
    val versions: List<AptoideVersionItem> = emptyList()
)

internal data class AptoideVersionItem(
    val id: Long = 0L,
    val name: String = "",
    val vername: String = "",
    val vercode: Long = 0L
)

internal data class AptoideApp(
    val id: Long = 0L,
    val name: String = "",
    @SerializedName("package")
    val packageName: String = "",
    val file: AptoideFile = AptoideFile(),
    val urls: AptoideUrls = AptoideUrls()
)

internal data class AptoideFile(
    val vername: String = "",
    val vercode: String = "0",
    val path: String = "",
    @SerializedName(value = "path_alt", alternate = ["pathAlt"])
    val pathAlt: String = ""
)

internal data class AptoideUrls(
    val w: String = "",
    val m: String = ""
)

internal fun playStoreUrl(packageName: String): String =
    "https://play.google.com/store/apps/details?id=${URLEncoder.encode(packageName, "UTF-8")}"

internal fun fileKindFromTags(tags: List<String>, request: HelperRequest): String {
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

internal fun String.normalizedHttpUrlOrNull(): String? {
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

internal fun Context.readDownloadedApkMetadata(file: File): DownloadedApkMetadata? {
    if (file.extension.equals("apk", ignoreCase = true)) {
        return readApkMetadata(file)
    }

    return runCatching {
        val validationDir = File(cacheDir, "validation").apply { mkdirs() }
        ZipFile(file).use { zip ->
            // The base APK inside split containers is not always named "base.apk":
            // some sources (e.g. APKPure XAPKs) name it after the package. Trying
            // the first alphabetical .apk entry is wrong — config splits like
            // config.ar.apk carry no manifest and fail to parse. Prefer the real
            // base: exact base.apk, then a name matching the package (derived from
            // the output file name), then the largest entry, and take the first
            // entry that actually reads as an APK.
            val packageHint = file.nameWithoutExtension
                .substringBefore('-')
                .lowercase(Locale.US)
            val apkEntries = zip.entries()
                .asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                .sortedWith(
                    compareBy<java.util.zip.ZipEntry> { entry ->
                        val name = entry.name.substringAfterLast('/').lowercase(Locale.US)
                        when {
                            name == "base.apk" -> 0
                            packageHint.isNotEmpty() && name.contains(packageHint) -> 1
                            name.contains("base") -> 2
                            else -> 3
                        }
                    }
                        .thenByDescending { it.size }
                        .thenBy { it.name }
                )
                .toList()
            var metadata: DownloadedApkMetadata? = null
            for (entry in apkEntries) {
                val extracted = File(
                    validationDir,
                    "${file.nameWithoutExtension}-${entry.name.hashCode()}.apk".sanitizeFileName()
                )
                zip.getInputStream(entry).use { input ->
                    extracted.outputStream().use { output -> input.copyTo(output) }
                }
                val read = readApkMetadata(extracted)
                extracted.delete()
                if (read != null) {
                    metadata = read
                    break
                }
            }
            metadata
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

internal fun parseInfoTableValue(doc: Document, label: String): String? =
    doc.select("tr")
        .firstOrNull { it.select("th").text().equals(label, ignoreCase = true) }
        ?.select("td")
        ?.lastOrNull()
        ?.text()
        ?.trim()
        ?.takeIf(String::isNotBlank)

internal fun fileKindFromUrl(url: String): String {
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

internal fun String.slugForUrl(): String =
    lowercase(Locale.US)
        .replace("&", " and ")
        .replace("'", "")
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "app" }

internal fun String.apkMirrorVersionSlug(): String =
    lowercase(Locale.US)
        .replace(".", "-")
        .replace("_", "-")
        .replace(Regex("[^a-z0-9-]+"), "-")
        .replace(Regex("-+"), "-")
        .trim('-')

internal fun String?.versionNameEquals(other: String?): Boolean {
    if (this == null || other == null) return false
    val left = normalizedVersionName()
    val right = other.normalizedVersionName()
    if (left.isBlank() || right.isBlank()) return false
    if (left == right) return true

    val leftParts = left.versionNumberParts()
    val rightParts = right.versionNumberParts()
    return leftParts.isNotEmpty() && leftParts == rightParts
}

/**
 * A downloaded result belongs to a request only when the package matches and
 * the file's version satisfies the request (or the request didn't pin one).
 * Used to stop a stale result — from a previous session's pending file or a
 * replayed [DownloadJobManager.Event.Completed] — from being handed to a
 * different request.
 */
internal fun PendingDownloadResult.belongsTo(request: HelperRequest?): Boolean {
    if (request == null) return false
    if (requestPackage != request.packageName) return false
    val requestedName = request.requestedVersionName ?: return true
    val candidateName = versionName ?: return true
    return candidateName.versionNameEquals(requestedName)
}

internal fun String.withoutTrailingVersionCode(): String =
    replace(Regex("""\s*\(\s*\d+\s*\)\s*$"""), "")
        .trim()

internal fun String.trailingVersionCode(): Long? =
    Regex("""\(\s*(\d+)\s*\)\s*$""")
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
        ?.toLongOrNull()

internal fun String.normalizedVersionName(): String =
    withoutTrailingVersionCode()
        .lowercase(Locale.US)
        .replace(Regex("""\b(version|ver|v|release|stable|apk|xapk|apkm|apks|bundle)\b"""), " ")
        .replace(Regex("""[^\p{Alnum}]+"""), ".")
        .trim('.')

internal fun String.withManualModeHint(): String {
    if (contains("Manual mode", ignoreCase = true)) return this
    val message = trimEnd()
    val hint = "Use Manual mode for this source instead."
    return if (message.contains('\n')) "$message\n$hint" else "$message $hint"
}

internal fun sourceFailureMessage(source: DownloadSource, error: Throwable): String =
    sourceFailureMessage(source.label, error, action = "check")

internal fun downloadFailureMessage(candidate: DownloadCandidate, error: Throwable): String =
    sourceFailureMessage(candidate.source.label, error, action = "download")

internal fun sourceFailureMessage(sourceLabel: String, error: Throwable, action: String): String {
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

internal fun Throwable.failureDetails(): String =
    generateSequence(this) { it.cause }
        .mapNotNull { it.message?.trim()?.takeIf(String::isNotBlank) }
        .firstOrNull()
        ?: javaClass.simpleName

internal fun sourceVersionFromText(text: String): String? =
    Regex("""\b(v?\d+(?:[._-]\d+)+(?:[-.][A-Za-z0-9]+)?)\b""", RegexOption.IGNORE_CASE)
        .find(text)
        ?.value
        ?.trim()

internal fun compareVersionNames(left: String?, right: String?): Int {
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

internal fun String.versionNumberParts(): List<Int> =
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

internal fun String?.variantFileSuffix(): String =
    this
        ?.lowercase(Locale.US)
        ?.replace(Regex("""[^a-z0-9._-]+"""), "-")
        ?.trim('-')
        ?.takeIf(String::isNotBlank)
        ?.let { "-$it" }
        .orEmpty()

internal fun String.sanitizeFileName(): String =
    replace(Regex("[^A-Za-z0-9._-]"), "_")
