package dev.seferaki.bridge

import android.content.Context

internal class ClipboardSyncPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("clipboard_sync", Context.MODE_PRIVATE)

    var sendMethod: ClipboardSendMethod
        get() = ClipboardSendMethod.fromStoredValue(prefs.getString(KEY_SEND_METHOD, null))
        set(value) { prefs.edit().putString(KEY_SEND_METHOD, value.storageValue).apply() }

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    var autoShareScreenshots: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SHARE_SCREENSHOTS, false)
        set(value) { prefs.edit().putBoolean(KEY_AUTO_SHARE_SCREENSHOTS, value).apply() }

    var status: String
        get() = prefs.getString(KEY_STATUS, "Остановлено") ?: "Остановлено"
        set(value) { prefs.edit().putString(KEY_STATUS, value).apply() }

    val lastReceivedEventId: String?
        get() = prefs.getString(KEY_RECEIVED_EVENT_ID, null)

    val lastReceivedImageEventId: String?
        get() = prefs.getString(KEY_RECEIVED_IMAGE_EVENT_ID, null)

    fun recentContentHash(nowMillis: Long = System.currentTimeMillis()): String? {
        val hash = prefs.getString(KEY_HASH, null) ?: return null
        val age = nowMillis - prefs.getLong(KEY_HASH_TIME, 0L)
        return hash.takeIf { age in 0..HASH_TTL_MILLIS }
    }

    fun shouldIgnore(payload: ClipboardPayload, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (payload.eventId == lastReceivedEventId || payload.eventId == prefs.getString(KEY_SENT_EVENT_ID, null)) return true
        return payload.contentHash() == recentContentHash(nowMillis)
    }

    fun markSeen(payload: ClipboardPayload, nowMillis: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString(if (payload.source == "mac") KEY_RECEIVED_EVENT_ID else KEY_SENT_EVENT_ID, payload.eventId)
            .putString(KEY_HASH, payload.contentHash())
            .putLong(KEY_HASH_TIME, nowMillis)
            .remove(KEY_IMAGE_HASH)
            .remove(KEY_IMAGE_HASH_TIME)
            .apply()
    }

    fun shouldIgnore(event: ClipboardImageEvent, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (event.eventId == lastReceivedImageEventId || event.eventId == prefs.getString(KEY_SENT_IMAGE_EVENT_ID, null)) return true
        val hash = recentContentHash(nowMillis) ?: return false
        val age = nowMillis - prefs.getLong(KEY_HASH_TIME, 0L)
        return age in 0..HASH_TTL_MILLIS && event.image.sha256 == hash
    }

    fun markImageSeen(event: ClipboardImageEvent, nowMillis: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString(if (event.source == "mac") KEY_RECEIVED_IMAGE_EVENT_ID else KEY_SENT_IMAGE_EVENT_ID, event.eventId)
            .putString(KEY_HASH, event.image.sha256)
            .putLong(KEY_HASH_TIME, nowMillis)
            .putString(KEY_IMAGE_HASH, event.image.sha256)
            .putLong(KEY_IMAGE_HASH_TIME, nowMillis)
            .apply()
    }

    fun clearClipboardCursor() {
        prefs.edit().remove(KEY_RECEIVED_EVENT_ID).remove(KEY_SENT_EVENT_ID).remove(KEY_HASH).remove(KEY_HASH_TIME)
            .remove(KEY_RECEIVED_IMAGE_EVENT_ID).remove(KEY_SENT_IMAGE_EVENT_ID).remove(KEY_IMAGE_HASH).remove(KEY_IMAGE_HASH_TIME).apply()
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_AUTO_SHARE_SCREENSHOTS = "auto_share_screenshots"
        private const val KEY_SEND_METHOD = "send_method"
        private const val KEY_STATUS = "status"
        private const val KEY_RECEIVED_EVENT_ID = "last_received_event_id"
        private const val KEY_SENT_EVENT_ID = "last_sent_event_id"
        private const val KEY_HASH = "last_text_hash"
        private const val KEY_HASH_TIME = "last_text_hash_time"
        private const val KEY_RECEIVED_IMAGE_EVENT_ID = "last_received_image_event_id"
        private const val KEY_SENT_IMAGE_EVENT_ID = "last_sent_image_event_id"
        private const val KEY_IMAGE_HASH = "last_image_hash"
        private const val KEY_IMAGE_HASH_TIME = "last_image_hash_time"
        private const val HASH_TTL_MILLIS = 24L * 60 * 60 * 1000
    }
}
