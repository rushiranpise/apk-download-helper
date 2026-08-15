package dev.rushi.apkdownloadhelper

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File

internal const val PREFS_NAME = "helper_settings"
internal const val TEMP_CLEANUP_MAX_AGE_MS = 6 * 60 * 60 * 1000L

/**
 * Mirrors the user's Logcat preference so long-lived clients (built before
 * settings load) can check it per request without holding a Context.
 */
@Volatile
internal var logcatLoggingEnabled = true

internal data class HelperSettings(
    val downloadLocation: DownloadLocation = DownloadLocation.TEMPORARY,
    val networkPolicy: NetworkPolicy = NetworkPolicy.WIFI_AND_MOBILE,
    val deleteTemporaryAfterHandoff: Boolean = true,
    val logcatLogging: Boolean = true,
    val fastMode: Boolean = false
)

internal enum class DownloadLocation(
    val title: String,
    val description: String
) {
    TEMPORARY(
        title = "Store in cache",
        description = "Keep the file in Helper's cache and hand it off to Morphe — no visible copy is left behind."
    ),
    DOWNLOADS(
        title = "Store in downloads",
        description = "Save a visible copy in Downloads/APK Download Helper after the file checks out."
    )
}

internal enum class NetworkPolicy(
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

internal fun Context.loadHelperSettings(): HelperSettings {
    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val settings = HelperSettings(
        downloadLocation = enumValueOrDefault(
            prefs.getString("download_location", null),
            DownloadLocation.TEMPORARY
        ),
        networkPolicy = enumValueOrDefault(
            prefs.getString("network_policy", null),
            NetworkPolicy.WIFI_AND_MOBILE
        ),
        deleteTemporaryAfterHandoff = prefs.getBoolean("delete_temporary_after_handoff", true),
        logcatLogging = prefs.getBoolean("logcat_logging", true),
        fastMode = prefs.getBoolean("fast_mode", false)
    )
    logcatLoggingEnabled = settings.logcatLogging
    return settings
}

internal fun Context.saveHelperSettings(settings: HelperSettings) {
    logcatLoggingEnabled = settings.logcatLogging
    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString("download_location", settings.downloadLocation.name)
        .putString("network_policy", settings.networkPolicy.name)
        .putBoolean("delete_temporary_after_handoff", settings.deleteTemporaryAfterHandoff)
        .putBoolean("logcat_logging", settings.logcatLogging)
        .putBoolean("fast_mode", settings.fastMode)
        .apply()
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String?, fallback: T): T =
    name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

internal fun Context.temporaryDownloadsDir(): File = File(cacheDir, "downloads")

internal fun Context.temporaryDownloadsSize(): Long =
    temporaryDownloadsDir()
        .listFiles()
        ?.filter { it.isFile }
        ?.sumOf { it.length() }
        ?: 0L

/** Deletes every temporary hand-off file and returns the freed bytes. */
internal fun Context.clearTemporaryDownloads(): Long {
    val dir = temporaryDownloadsDir()
    val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
    val freed = files.sumOf { it.length() }
    files.forEach { file -> runCatching { file.delete() } }
    return freed
}

internal fun Context.cleanupTemporaryDownloads(settings: HelperSettings) {
    if (!settings.deleteTemporaryAfterHandoff) return
    val cutoff = System.currentTimeMillis() - TEMP_CLEANUP_MAX_AGE_MS
    temporaryDownloadsDir()
        .listFiles()
        ?.filter { it.isFile && it.lastModified() < cutoff }
        ?.forEach { file -> runCatching { file.delete() } }
}
