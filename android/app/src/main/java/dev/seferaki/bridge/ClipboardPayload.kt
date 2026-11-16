package dev.seferaki.bridge

import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

internal const val MAX_CLIPBOARD_BYTES = 1_048_576
internal const val MAX_CLIPBOARD_JSON_BYTES = MAX_CLIPBOARD_BYTES * 6 + 4096

internal data class ClipboardPayload(
    val eventId: String,
    val source: String,
    val type: String,
    val text: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("eventId", eventId)
        .put("source", source)
        .put("type", type)
        .put("text", text)

    fun contentHash(): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        fun fromForegroundText(text: String?, syncEnabled: Boolean, lastContentHash: String? = null, eventId: String = UUID.randomUUID().toString()): ClipboardPayload? {
            if (!syncEnabled || text.isNullOrEmpty()) return null
            val payload = fromText(text, "android", eventId)
            if (payload.contentHash() == lastContentHash) return null
            return payload
        }

        fun fromText(text: String, source: String, eventId: String = UUID.randomUUID().toString()): ClipboardPayload {
            require(source == "mac" || source == "android") { "Неизвестный источник clipboard-события" }
            require(isUuid(eventId)) { "Некорректный ID clipboard-события" }
            require(text.isNotEmpty()) { "Пустой текст не передаётся" }
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_CLIPBOARD_BYTES) { "Текст больше 1 МиБ" }

            val type = if (text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)) "link" else "text"
            if (type == "link") require(isHttpUrl(text)) { "Поддерживаются только корректные ссылки HTTP и HTTPS" }
            return ClipboardPayload(eventId, source, type, text)
        }

        fun fromJson(value: JSONObject): ClipboardPayload {
            val eventId = value.getString("eventId")
            val source = value.getString("source")
            val type = value.getString("type")
            val text = value.getString("text")
            val validated = fromText(text, source, eventId)
            require(type == "text" || type == "link") { "Тип clipboard-события не поддерживается" }
            require(type == validated.type) { "Тип clipboard-события не соответствует содержимому" }
            return validated
        }

        private fun isUuid(value: String): Boolean = try {
            UUID.fromString(value).toString().equals(value, ignoreCase = true)
        } catch (_: IllegalArgumentException) {
            false
        }

        private fun isHttpUrl(value: String): Boolean = try {
            val uri = URI(value)
            uri.scheme?.lowercase() in listOf("http", "https") && !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }
}
