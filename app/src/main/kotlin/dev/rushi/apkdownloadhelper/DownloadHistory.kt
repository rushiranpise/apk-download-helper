package dev.rushi.apkdownloadhelper

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.lang.reflect.Type

internal data class DownloadHistoryEntry(
    val timestamp: Long,
    val appName: String,
    val packageName: String,
    val versionName: String?,
    val sourceName: String,
    val fileName: String,
    val fileKind: String,
    val uri: String
)

internal object DownloadHistoryStore {
    private const val PREFS_HISTORY = "download_history"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 50
    private val gson = Gson()
    private val entriesType: Type = object : TypeToken<List<DownloadHistoryEntry>>() {}.type

    fun entries(context: Context): List<DownloadHistoryEntry> {
        val raw = context.getSharedPreferences(PREFS_HISTORY, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null)
            ?: return emptyList()
        return runCatching {
            gson.fromJson<List<DownloadHistoryEntry>>(raw, entriesType).orEmpty()
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, entry: DownloadHistoryEntry) {
        val updated = (listOf(entry) + entries(context)).take(MAX_ENTRIES)
        context.getSharedPreferences(PREFS_HISTORY, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENTRIES, gson.toJson(updated))
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_HISTORY, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

internal fun Context.recordHandOff(
    request: HelperRequest,
    candidate: DownloadCandidate,
    file: File,
    uri: Uri
) {
    DownloadHistoryStore.add(
        this,
        DownloadHistoryEntry(
            timestamp = System.currentTimeMillis(),
            appName = candidate.name.ifBlank { request.appName },
            packageName = candidate.packageName,
            versionName = candidate.versionName,
            sourceName = candidate.source.label,
            fileName = file.name,
            fileKind = candidate.fileKind,
            uri = uri.toString()
        )
    )
}
