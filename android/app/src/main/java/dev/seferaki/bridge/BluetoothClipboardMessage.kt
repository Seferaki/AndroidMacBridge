package dev.seferaki.bridge

import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets

internal sealed interface BluetoothClipboardMessage {
    data class Text(val event: ClipboardPayload) : BluetoothClipboardMessage
    data class Image(val event: ClipboardImageEvent) : BluetoothClipboardMessage
    data class Acknowledgement(val eventId: String, val state: String) : BluetoothClipboardMessage

    companion object {
        fun encode(event: ClipboardPayload): ByteArray = JSONObject()
            .put("kind", "clipboard.text")
            .put("event", event.toJson())
            .toString().toByteArray(StandardCharsets.UTF_8)

        fun encode(event: ClipboardImageEvent): ByteArray = JSONObject()
            .put("kind", "clipboard.image")
            .put("event", JSONObject()
                .put("eventId", event.eventId)
                .put("source", event.source)
                .put("mimeType", event.image.mimeType)
                .put("sha256", event.image.sha256)
                .put("data", Base64.encodeToString(event.image.data, Base64.NO_WRAP)))
            .toString().toByteArray(StandardCharsets.UTF_8)

        fun acknowledgement(eventId: String, state: String): ByteArray = JSONObject()
            .put("kind", "clipboard.ack")
            .put("eventId", eventId)
            .put("state", state)
            .toString().toByteArray(StandardCharsets.UTF_8)

        fun decode(bytes: ByteArray): BluetoothClipboardMessage {
            val root = JSONObject(String(bytes, StandardCharsets.UTF_8))
            return when (root.getString("kind")) {
                "clipboard.text" -> Text(ClipboardPayload.fromJson(root.getJSONObject("event")).also {
                    require(it.source == "mac") { "Ожидалось событие текста с Mac" }
                })
                "clipboard.image" -> {
                    val event = root.getJSONObject("event")
                    val encoded = event.getString("data")
                    require(encoded.length <= ClipboardImage.MAX_BYTES * 4 / 3 + 8) { "Изображение Bluetooth слишком велико" }
                    val data = Base64.decode(encoded, Base64.DEFAULT)
                    val image = ClipboardImage.validate(data, event.getString("mimeType"), event.getString("sha256"))
                    Image(ClipboardImageEvent.create(event.getString("source"), image, event.getString("eventId")).also {
                        require(it.source == "mac") { "Ожидалось изображение с Mac" }
                    })
                }
                "clipboard.ack" -> Acknowledgement(root.getString("eventId"), root.getString("state"))
                else -> throw IllegalArgumentException("Неизвестное Bluetooth-сообщение")
            }
        }
    }
}
