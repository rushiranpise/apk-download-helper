package dev.rushi.apkdownloadhelper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

internal const val ACTION_START_DOWNLOAD = "dev.rushi.apkdownloadhelper.action.START_DOWNLOAD"
internal const val ACTION_CANCEL_DOWNLOAD = "dev.rushi.apkdownloadhelper.action.CANCEL_DOWNLOAD"
internal const val ACTION_RETRY_DOWNLOAD = "dev.rushi.apkdownloadhelper.action.RETRY_DOWNLOAD"

private const val CHANNEL_PROGRESS = "download_progress"
private const val CHANNEL_DONE = "download_done"
private const val NOTIFICATION_ID_PROGRESS = 1001
internal const val NOTIFICATION_ID_DONE = 1002
private const val TEMP_CLEANUP_DELAY_MS = 5 * 60 * 1000L
private const val PREFS_PENDING = "pending_download_result"


internal data class PendingDownload(
    val request: HelperRequest,
    val candidate: DownloadCandidate,
    val settings: HelperSettings
)

internal data class PendingDownloadResult(
    val uri: String,
    val fileName: String,
    val packageName: String,
    val versionName: String?,
    val sourceName: String,
    val requestPackage: String,
    val callerPackage: String
)

/**
 * In-memory state shared between [DownloadService] and [MainActivity] so the
 * activity can keep its UI in sync with a download that survives the activity
 * being stopped or destroyed. Completed results are also persisted to
 * SharedPreferences so a re-created activity can hand the file to the caller.
 */
internal object DownloadJobManager {
    data class DownloadJob(
        val request: HelperRequest,
        val candidate: DownloadCandidate,
        val settings: HelperSettings,
        val requestIntentExtras: Bundle? = null
    )

    sealed interface Event {
        data class Progress(val candidate: DownloadCandidate, val percent: Int) : Event
        data class Completed(val result: PendingDownloadResult) : Event
        data class Failed(val candidate: DownloadCandidate, val message: String) : Event
        data class Cancelled(val candidate: DownloadCandidate) : Event
        // The downloaded file is valid except for its version code (which the
        // parser could not know up front). The file is kept so the UI can ask
        // whether to use it anyway.
        data class ValidationMismatch(
            val candidate: DownloadCandidate,
            val file: File,
            val foundVersionCode: Long?
        ) : Event
    }

    @Volatile
    var activeJob: DownloadJob? = null
        private set

    private val _events = MutableStateFlow<Event?>(null)
    val events: StateFlow<Event?> = _events.asStateFlow()

    fun start(job: DownloadJob) {
        activeJob = job
        _events.value = null
    }

    fun clearEvent() {
        _events.value = null
    }

    fun emit(event: Event) {
        _events.value = event
        if (event is Event.Completed || event is Event.Cancelled) {
            activeJob = null
        }
    }

    fun readPendingResult(context: Context): PendingDownloadResult? {
        val prefs = context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
        val uri = prefs.getString("uri", null) ?: return null
        return PendingDownloadResult(
            uri = uri,
            fileName = prefs.getString("file_name", "download") ?: "download",
            packageName = prefs.getString("package_name", "") ?: "",
            versionName = prefs.getString("version_name", null),
            sourceName = prefs.getString("source_name", "") ?: "",
            requestPackage = prefs.getString("request_package", "") ?: "",
            callerPackage = prefs.getString("caller_package", "") ?: ""
        )
    }

    fun persistPendingResult(result: PendingDownloadResult, context: Context) {
        context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
            .edit()
            .putString("uri", result.uri)
            .putString("file_name", result.fileName)
            .putString("package_name", result.packageName)
            .putString("version_name", result.versionName)
            .putString("source_name", result.sourceName)
            .putString("request_package", result.requestPackage)
            .putString("caller_package", result.callerPackage)
            .apply()
    }

    fun clearPendingResult(context: Context) {
        context.getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

internal class DownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null
    private var lastPostedPercent = -1

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
        .addInterceptor(httpLoggingInterceptor("Download"))
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

    private val downloader = ApkDownloader(client, apkPureClient, onRetry = ::retryNotification)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_DOWNLOAD -> {
                cancelDownload()
                return START_NOT_STICKY
            }
            ACTION_RETRY_DOWNLOAD -> {
                val job = DownloadJobManager.activeJob ?: run {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startDownload(job)
                return START_NOT_STICKY
            }
            else -> {
                val job = DownloadJobManager.activeJob ?: run {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startDownload(job)
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun startDownload(job: DownloadJobManager.DownloadJob) {
        val candidate = job.candidate
        lastPostedPercent = -1
        val notification = buildProgressNotification(candidate, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID_PROGRESS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID_PROGRESS, notification)
        }
        downloadJob?.cancel()
        downloadJob = serviceScope.launch {
            try {
                val file = runDownload(job)
                handleSuccess(job, file)
            } catch (error: Throwable) {
                if (error is CancellationException || error.message == "Canceled") {
                    handleCancelled(job)
                } else if (error is VersionCodeMismatchException) {
                    // Keep the file; the UI decides whether to use it anyway.
                    DownloadJobManager.emit(
                        DownloadJobManager.Event.ValidationMismatch(
                            candidate = job.candidate,
                            file = error.file,
                            foundVersionCode = error.foundVersionCode
                        )
                    )
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    handleFailure(job, error)
                }
            }
        }
    }

    private suspend fun runDownload(job: DownloadJobManager.DownloadJob): File {
        val candidate = job.candidate
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
        validateDownloadedArtifact(this, job.request, candidate, file)
        return file
    }

    private fun handleSuccess(job: DownloadJobManager.DownloadJob, file: File) {
        val request = job.request
        val candidate = job.candidate
        val settings = job.settings

        val uri = when (settings.downloadLocation) {
            DownloadLocation.TEMPORARY -> FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.files", file)
            DownloadLocation.DOWNLOADS -> copyToDownloads(file)
        }
        grantUriPermission(request.callerPackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val result = PendingDownloadResult(
            uri = uri.toString(),
            fileName = file.name,
            packageName = candidate.packageName,
            versionName = candidate.versionName,
            sourceName = candidate.source.label,
            requestPackage = request.packageName,
            callerPackage = request.callerPackage
        )
        DownloadJobManager.persistPendingResult(result, applicationContext)
        recordHandOff(request, candidate, file, uri)

        if (
            settings.downloadLocation == DownloadLocation.DOWNLOADS ||
            settings.deleteTemporaryAfterHandoff
        ) {
            scheduleTemporaryDelete(file)
        }

        DownloadJobManager.emit(DownloadJobManager.Event.Completed(result))
        notifyCompletion(job, result)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun handleFailure(job: DownloadJobManager.DownloadJob, error: Throwable) {
        val message = downloadFailureMessage(job.candidate, error)
        Log.w(TAG, "Download failed for ${job.candidate.packageName}", error)
        DownloadJobManager.emit(DownloadJobManager.Event.Failed(job.candidate, message))
        notifyFailure(job, message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun handleCancelled(job: DownloadJobManager.DownloadJob) {
        DownloadJobManager.emit(DownloadJobManager.Event.Cancelled(job.candidate))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cancelDownload() {
        downloader.cancelCurrent()
        downloadJob?.cancel()
    }

    private suspend fun downloadSingleFile(
        candidate: DownloadCandidate,
        downloadFile: CandidateDownloadFile,
        downloadsDir: File
    ): File {
        val outputName = if (candidate.source == DownloadSource.AURORA) {
            "${candidate.packageName}-${candidate.versionName ?: "latest"}-aurora.apk"
        } else {
            downloadFile.fileName
        }
        val safeName = outputName.sanitizeFileName()
        val outputFile = File(downloadsDir, safeName)
        val stagedFile = File(downloadsDir, "$safeName.part")
        downloader.downloadToFile(downloadFile, stagedFile) { copied, total ->
            updateDownloadProgress(candidate, copied, total)
        }
        if (outputFile.exists()) outputFile.delete()
        if (!stagedFile.renameTo(outputFile)) {
            stagedFile.copyTo(outputFile, overwrite = true)
            stagedFile.delete()
        }
        return outputFile
    }

    private suspend fun downloadSplitArchive(
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
        var completed = 0L
        val staged = files.mapIndexed { index, file ->
            val stagedFile = File(
                downloadsDir,
                "${candidate.packageName}-${candidate.source.name}-split_$index.part".sanitizeFileName()
            )
            downloader.downloadToFile(file, stagedFile) { fileCopied, total ->
                knownTotal?.let { updateDownloadProgress(candidate, completed + fileCopied, it) }
            }
            completed += stagedFile.length()
            stagedFile to file.fileName.ifBlank { "split_$index.apk" }.sanitizeFileName()
        }

        ZipOutputStream(outputFile.outputStream()).use { zip ->
            staged.forEach { (stagedFile, entryName) ->
                zip.putNextEntry(ZipEntry(entryName))
                stagedFile.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
                stagedFile.delete()
            }
        }
        return outputFile
    }

    private fun retryNotification(attempt: Int, delayMs: Long) {
        val candidate = DownloadJobManager.activeJob?.candidate ?: return
        notifySafe(
            NOTIFICATION_ID_PROGRESS,
            buildProgressNotification(
                candidate,
                lastPostedPercent.coerceAtLeast(0),
                "Connection issue — retrying in ${delayMs / 1000}s (attempt $attempt)"
            )
        )
    }

    private fun downloadClientFor(url: String): OkHttpClient =
        if (url.contains("apkpure", ignoreCase = true)) apkPureClient else client

    private fun updateDownloadProgress(candidate: DownloadCandidate, copied: Long, total: Long) {
        if (total <= 0L) return
        val percent = ((copied * 100f) / total).roundToInt().coerceIn(0, 100)
        DownloadJobManager.emit(DownloadJobManager.Event.Progress(candidate, percent))
        if (percent != lastPostedPercent) {
            lastPostedPercent = percent
            notifySafe(NOTIFICATION_ID_PROGRESS, buildProgressNotification(candidate, percent))
        }
    }

    private fun temporaryDownloadsDir(): File = File(cacheDir, "downloads")

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows the progress of APK downloads"
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Download results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Alerts when a download finishes or fails"
            }
        )
    }

    private fun buildProgressNotification(
        candidate: DownloadCandidate,
        percent: Int,
        contentText: String? = null
    ): Notification {
        val contentIntent = requestContentIntent(DownloadJobManager.activeJob?.requestIntentExtras)
        val cancelIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL_DOWNLOAD),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading ${candidate.name}")
            .setContentText(contentText ?: if (percent <= 0) "Preparing…" else "$percent%")
            .setProgress(100, percent.coerceIn(0, 100), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(0, "Cancel", cancelIntent)
            .build()
    }

    private fun notifyCompletion(job: DownloadJobManager.DownloadJob, result: PendingDownloadResult) {
        val notification = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download complete: ${job.candidate.name}")
            .setContentText(
                "${job.candidate.versionDisplay} from ${job.candidate.source.label} · " +
                    "tap to return it to ${result.callerPackage}"
            )
            .setContentIntent(requestContentIntent(job.requestIntentExtras))
            .setAutoCancel(true)
            .build()
        notifySafe(NOTIFICATION_ID_DONE, notification)
    }

    private fun notifyFailure(job: DownloadJobManager.DownloadJob, message: String) {
        val retryIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DownloadService::class.java).setAction(ACTION_RETRY_DOWNLOAD),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Download failed: ${job.candidate.name}")
            .setContentText(message.lineSequence().firstOrNull().orEmpty().take(180))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(requestContentIntent(job.requestIntentExtras))
            .addAction(0, "Retry", retryIntent)
            .setAutoCancel(true)
            .build()
        notifySafe(NOTIFICATION_ID_DONE, notification)
    }

    private fun requestContentIntent(extras: Bundle?): PendingIntent {
        val launch = Intent(this, MainActivity::class.java).apply {
            action = DownloadHelperContract.ACTION_DOWNLOAD_ORIGINAL_APK
            extras?.let(::putExtras)
        }
        return PendingIntent.getActivity(
            this,
            2,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notifySafe(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            NotificationManagerCompat.from(this).notify(id, notification)
        }
    }
}

internal fun scheduleTemporaryDelete(file: File) {
    Thread {
        Thread.sleep(TEMP_CLEANUP_DELAY_MS)
        runCatching { file.delete() }
    }.apply {
        name = "apk-helper-temp-cleanup"
        isDaemon = true
        start()
    }
}

internal fun Context.copyToDownloads(file: File): Uri {
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

internal class VersionCodeMismatchException(
    val file: File,
    val foundVersionCode: Long?
) : Exception("Version code: requested a different build, found $foundVersionCode")

internal fun validateDownloadedArtifact(
    context: Context,
    request: HelperRequest,
    candidate: DownloadCandidate,
    file: File
) {
    val shouldValidateMetadata = candidate.fileKind.lowercase(Locale.US) in setOf("apk", "apks", "apkm", "xapk") ||
        file.extension.lowercase(Locale.US) in setOf("apk", "apks", "apkm", "xapk")
    val metadata = context.readDownloadedApkMetadata(file) ?: run {
        check(!shouldValidateMetadata) {
            file.delete()
            "Downloaded file could not be read as an APK.".withManualModeHint()
        }
        return
    }
    val hardMismatches = buildList {
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
        }
    }

    // Wrong package or version name: the file is unusable — delete and fail.
    check(hardMismatches.isEmpty()) {
        file.delete()
        "Downloaded file does not match Morphe request.\n${hardMismatches.joinToString("\n")}"
            .withManualModeHint()
    }

    // Version-code-only mismatch: the file is otherwise valid, but its build
    // differs from the request. Keep the file so the caller (Fast Mode) can
    // ask the user whether to use it anyway.
    if (candidate.option == CandidateOption.REQUESTED) {
        val requestedCodes = request.requestedVersionCodes +
            request.compatibleVersionCodes.filter { it > 0L }
        if (
            requestedCodes.isNotEmpty() &&
            metadata.versionCode !in requestedCodes
        ) {
            throw VersionCodeMismatchException(file, metadata.versionCode)
        }
    }
}


