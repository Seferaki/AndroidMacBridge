package dev.seferaki.bridge

import android.graphics.BitmapFactory
import java.security.MessageDigest

/** Validate untrusted image bytes before publishing them to another app. */
internal class ClipboardImage private constructor(val data: ByteArray, val mimeType: String, val sha256: String) {
    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        const val MAX_PIXELS = 24_000_000

        fun validate(data: ByteArray, mimeType: String, expectedSHA256: String? = null): ClipboardImage {
            require(data.isNotEmpty() && data.size <= MAX_BYTES) { "Изображение пустое или больше 8 МиБ" }
            require(mimeType == "image/png" || mimeType == "image/jpeg") { "Формат изображения не поддерживается" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            require(bounds.outMimeType == mimeType && bounds.outWidth > 0 && bounds.outHeight > 0 &&
                bounds.outWidth.toLong() * bounds.outHeight.toLong() <= MAX_PIXELS) { "Изображение повреждено или слишком велико" }
            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                ?: throw IllegalArgumentException("Изображение повреждено")
            bitmap.recycle()
            val digest = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
            require(expectedSHA256 == null || digest.equals(expectedSHA256, ignoreCase = true)) { "Контрольная сумма изображения не совпадает" }
            return ClipboardImage(data, mimeType, digest)
        }
    }
}

internal data class ClipboardImageEvent(
    val eventId: String,
    val source: String,
    val image: ClipboardImage
) {
    companion object {
        fun create(source: String, image: ClipboardImage, eventId: String = java.util.UUID.randomUUID().toString()) =
            ClipboardImageEvent(eventId, source, image).also {
                require(source == "mac" || source == "android") { "Неизвестный источник изображения" }
                require(runCatching { java.util.UUID.fromString(eventId).toString().equals(eventId, true) }.getOrDefault(false)) {
                    "Некорректный ID события изображения"
                }
            }
    }
}
