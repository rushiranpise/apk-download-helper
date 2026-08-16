package dev.rushi.apkdownloadhelper

import android.os.Build
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class RequestLogEntry(
    val time: String,
    val level: LogLevel,
    val message: String
)

internal enum class LogLevel(val badge: String) {
    Info("I"),
    Warning("W"),
    Error("E")
}

/**
 * Global in-app log ring buffer shared by the activity (UI events, resolution,
 * downloads) and the OkHttp HTTP interceptor (every request/redirect/status).
 * The Logs tab reads [entries] directly, so a user can see the full request
 * story — including which URLs failed and with what status — without adb.
 *
 * [entries] is a Compose snapshot list so the UI recomposes as lines arrive.
 * Logcat mirroring follows the user's "Log to Logcat" setting.
 */
internal object AppLog {
    private const val MAX_ENTRIES = 400

    val entries = mutableStateListOf<RequestLogEntry>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** The Morphe request this session is working on, shown in [exportText]. */
    @Volatile
    private var requestSummary: String? = null

    fun setRequestSummary(summary: String?) {
        requestSummary = summary
    }

    @Synchronized
    fun record(level: LogLevel, message: String) {
        val timestamp = timeFormat.format(Date())
        entries += RequestLogEntry(timestamp, level, message)
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
        if (logcatLoggingEnabled) {
            when (level) {
                LogLevel.Info -> Log.i(TAG, message)
                LogLevel.Warning -> Log.w(TAG, message)
                LogLevel.Error -> Log.e(TAG, message)
            }
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    /** Plain-text export with request/device context, for the Share action. */
    @Synchronized
    fun exportText(): String = buildString {
        append("APK Download Helper v${BuildConfig.VERSION_NAME}\n")
        append("Device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        requestSummary?.let { summary ->
            append("----\n")
            append(summary)
            append("\n")
        }
        append("----\n")
        entries.forEach { entry ->
            append("${entry.time} ${entry.level.badge} ${entry.message}\n")
        }
    }
}
