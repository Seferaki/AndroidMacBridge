package dev.seferaki.bridge

import android.content.Context

/** Records an attempt before upload so a lost response is never treated as a definite failure. */
internal class PendingUploadStore(context: Context) {
    private val prefs = context.getSharedPreferences("bridge_pending_uploads", Context.MODE_PRIVATE)
    fun record(id: String, deviceId: String) {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        check(prefs.edit().putString(id, deviceId).commit()) { "Не удалось сохранить состояние передачи" }
    }
    fun contains(id: String, deviceId: String?): Boolean = deviceId != null && prefs.getString(id, null) == deviceId
    fun remove(id: String) { prefs.edit().remove(id).commit() }
    fun clear() { prefs.edit().clear().commit() }
}
