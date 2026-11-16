package dev.seferaki.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

internal data class LocatedFile(val id: String, val uri: Uri, val name: String, val time: Long)

/** Local file locations only; they are not sent to the paired Mac. */
internal class FileLocationStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("bridge_file_locations", Context.MODE_PRIVATE)

    @Synchronized fun record(id: String, uri: Uri, name: String = "Файл", received: Boolean = false) {
        if (!id.matches(Regex("[0-9a-fA-F-]{36}")) || uri.scheme != "content") return
        val record = JSONObject().put("uri", uri.toString()).put("name", name).put("received", received).put("time", System.currentTimeMillis())
        prefs.edit().putString(id, record.toString()).apply()
        val entries = prefs.all.mapNotNull { (key, value) ->
            runCatching { key to JSONObject(value as String) }.getOrNull()
        }.sortedByDescending { it.second.optLong("time") }
        val retainedUris = entries.take(60).map { it.second.optString("uri") }.toSet()
        val removed = entries.drop(60)
        if (removed.isNotEmpty()) {
            val editor = prefs.edit()
            removed.forEach { (key, _) -> editor.remove(key) }
            editor.apply()
            removed.map { it.second.optString("uri") }.distinct().filter { it !in retainedUris }.forEach { value ->
                runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(value), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
        }
    }

    fun find(id: String): Uri? = runCatching {
        val value = prefs.getString(id, null) ?: return null
        Uri.parse(JSONObject(value).getString("uri")).takeIf { it.scheme == "content" }
    }.getOrNull()

    @Synchronized fun recentReceived(): List<LocatedFile> = prefs.all.mapNotNull { (id, value) ->
        runCatching {
            val record = JSONObject(value as String)
            if (!record.optBoolean("received")) return@runCatching null
            val uri = Uri.parse(record.getString("uri"))
            if (uri.scheme != "content") return@runCatching null
            LocatedFile(id, uri, record.optString("name", "Файл"), record.optLong("time"))
        }.getOrNull()
    }.sortedByDescending { it.time }
}
