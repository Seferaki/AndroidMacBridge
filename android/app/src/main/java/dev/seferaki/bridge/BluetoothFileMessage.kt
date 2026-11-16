package dev.seferaki.bridge

import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/** JSON control/data envelope shared by the Android peripheral and macOS central. */
internal object BluetoothFileMessage {
    const val CHUNK_BYTES = 32 * 1024
    const val MAX_FILE_BYTES = 1L shl 30

    fun offer(id: String, name: String, size: Long, sha256: String): ByteArray = JSONObject()
        .put("kind", "file.offer").put("id", id).put("name", name).put("size", size).put("sha256", sha256).bytes()

    fun decision(id: String, state: String): ByteArray = JSONObject()
        .put("kind", "file.decision").put("id", id).put("state", state).bytes()

    fun chunk(id: String, offset: Long, bytes: ByteArray): ByteArray = JSONObject()
        .put("kind", "file.chunk").put("id", id).put("offset", offset)
        .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)).bytes()

    fun finish(id: String, size: Long, sha256: String): ByteArray = JSONObject()
        .put("kind", "file.finish").put("id", id).put("size", size).put("sha256", sha256).bytes()

    fun cancel(id: String): ByteArray = JSONObject().put("kind", "file.cancel").put("id", id).bytes()

    fun reply(id: String, phase: String, state: String, nextOffset: Long? = null, sha256: String? = null): ByteArray = JSONObject()
        .put("kind", "file.reply").put("id", id).put("phase", phase).put("state", state)
        .apply { if (nextOffset != null) put("nextOffset", nextOffset); if (sha256 != null) put("sha256", sha256) }.bytes()

    fun decode(bytes: ByteArray): JSONObject = JSONObject(String(bytes, StandardCharsets.UTF_8))
    fun data(message: JSONObject): ByteArray {
        val encoded = message.getString("data")
        require(encoded.length <= CHUNK_BYTES * 4 / 3 + 16) { "Bluetooth-часть файла слишком велика" }
        return Base64.decode(encoded, Base64.DEFAULT).also { require(it.size <= CHUNK_BYTES) { "Bluetooth-часть файла слишком велика" } }
    }

    private fun JSONObject.bytes(): ByteArray = toString().toByteArray(StandardCharsets.UTF_8)
}
