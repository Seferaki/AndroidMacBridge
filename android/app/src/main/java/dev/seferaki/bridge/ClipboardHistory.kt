package dev.seferaki.bridge

import android.content.Context
import org.json.JSONArray
import java.security.MessageDigest

internal fun clipboardHistoryStorageKey(macDeviceId: String, phoneClientID: String): String =
    "pair_" + MessageDigest.getInstance("SHA-256").digest("mac:$macDeviceId|phone:$phoneClientID".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun legacyClipboardHistoryStorageKey(pairToken: String): String =
    "pair_" + MessageDigest.getInstance("SHA-256").digest(pairToken.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Local history, shared by the activity and clipboard service. */
internal class ClipboardHistory(context: Context) {
    private val prefs = context.getSharedPreferences("clipboard_history", Context.MODE_PRIVATE)
    private val pairStorage = PairStorage(context)
    private val clientIdentity = LocalClientIdentity(context)
    private fun key(): String? = pairStorage.load()?.let { pair -> clipboardHistoryStorageKey(pair.deviceId,clientIdentity.get()) }

    fun migrateTokenHistoryToPair(pair: PairInfo, phoneClientID: String) = synchronized(lock) {
        val destination = clipboardHistoryStorageKey(pair.deviceId,phoneClientID)
        if (prefs.contains(destination)) return@synchronized
        val old = prefs.getString(legacyClipboardHistoryStorageKey(pair.token),null) ?: return@synchronized
        prefs.edit().putString(destination,old).apply()
    }

    fun items(): List<String> = synchronized(lock) { read() }
    fun add(text: String) = synchronized(lock) {
        val key = key() ?: return@synchronized
        if (text.isBlank() || text.toByteArray(Charsets.UTF_8).size > 16384) return@synchronized
        val items = (listOf(text) + read(key).filter { it != text }).take(30)
        prefs.edit().putString(key, JSONArray(items).toString()).apply()
    }
    fun clear() = synchronized(lock) { key()?.let { prefs.edit().remove(it).apply() } }
    private fun read(): List<String> = key()?.let { read(it) } ?: emptyList()
    private fun read(key: String): List<String> = runCatching {
        val arr = JSONArray(prefs.getString(key, "[]"))
        (0 until minOf(arr.length(), 30)).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
    }.getOrDefault(emptyList())
    companion object { private val lock = Any() }
}
