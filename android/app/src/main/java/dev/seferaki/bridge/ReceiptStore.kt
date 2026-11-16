package dev.seferaki.bridge

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.os.SystemClock
import org.json.JSONObject
import java.security.MessageDigest

/** Minimal journal written before publishing a received file, never its contents. */
internal class ReceiptStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("bridge_receipts", Context.MODE_PRIVATE)
    private var lastAttempt: Long = -30_000
    fun record(id: String, hash: String, uri: Uri, deviceId: String) {
        val item = JSONObject().put("hash", hash).put("uri", uri.toString()).put("deviceId", deviceId)
        check(prefs.edit().putString(id, item.toString()).commit()) { "Не удалось сохранить состояние передачи" }
    }
    fun remove(id: String) { prefs.edit().remove(id).commit() }
    fun contains(id: String): Boolean = prefs.contains(id)
    fun clear() { prefs.edit().clear().commit() }

    fun retryConfirmed(client: BridgeClient, isReceiving: (String) -> Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAttempt < 30_000) return
        lastAttempt = now
        val pair = client.pair ?: return
        for ((id, value) in prefs.all) {
            if (isReceiving(id)) continue
            val item = runCatching { JSONObject(value as String) }.getOrNull() ?: continue
            if (item.optString("deviceId") != pair.deviceId) continue
            val uri = runCatching { Uri.parse(item.getString("uri")) }.getOrNull() ?: continue
            val expected = item.optString("hash")
            if (!expected.matches(Regex("[a-f0-9]{64}"))) continue
            val pending = runCatching {
                context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)?.use {
                    if (it.moveToFirst()) it.getInt(0) else null
                }
            }.getOrNull() ?: continue
            if (pending != 0) {
                // Only a journalled, inactive pending item is ours to clean up.
                if (runCatching { context.contentResolver.delete(uri, null, null) == 1 }.getOrDefault(false)) remove(id)
                continue
            }
            val actual = runCatching {
                val md = MessageDigest.getInstance("SHA-256")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(65536)
                    while (true) { val n = input.read(buffer); if (n < 0) break; if (n > 0) md.update(buffer, 0, n) }
                    md.digest().joinToString("") { "%02x".format(it) }
                }
            }.getOrNull() ?: continue
            if (actual != expected) continue
            if (runCatching { client.acknowledge(id, expected) }.isSuccess) remove(id)
        }
    }
}
