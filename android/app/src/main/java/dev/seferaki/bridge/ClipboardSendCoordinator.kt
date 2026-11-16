package dev.seferaki.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException

internal enum class ClipboardSendMethod(val storageValue: String) {
    APP_FOCUS("app_focus"),
    QUICK_SETTINGS_TILE("quick_settings_tile"),
    IME_KEYBOARD("ime_keyboard"),
    ACCESSIBILITY("accessibility");

    companion object {
        fun fromStoredValue(value: String?): ClipboardSendMethod =
            entries.firstOrNull { it.storageValue == value } ?: APP_FOCUS
    }
}

internal enum class ClipboardSendStatus {
    SENT,
    DUPLICATE,
    PAUSED,
    WRONG_METHOD,
    NO_PAIR,
    NO_NETWORK_PERMISSION,
    EMPTY,
    SENSITIVE,
    UNSUPPORTED,
    IMAGE_SENT,
    IMAGE_DUPLICATE,
    UNSUPPORTED_IMAGE,
    IMAGE_FAILED,
    FAILED
}

internal data class ClipboardSendOutcome(
    val status: ClipboardSendStatus,
    val message: String
) {
    val isSuccessful: Boolean
        get() = status == ClipboardSendStatus.SENT || status == ClipboardSendStatus.DUPLICATE ||
            status == ClipboardSendStatus.IMAGE_SENT || status == ClipboardSendStatus.IMAGE_DUPLICATE
}

internal data class ClipboardSnapshot(
    val text: String? = null,
    val image: ClipboardImage? = null,
    val sensitive: Boolean = false,
    val status: ClipboardSendStatus? = null
)

/** Shared validation and publication path for all explicitly selected send methods. */
internal object ClipboardSendCoordinator {
    fun preflight(
        context: Context,
        client: BridgeClient,
        expectedMethod: ClipboardSendMethod
    ): ClipboardSendOutcome? {
        val preferences = ClipboardSyncPreferences(context)
        if (preferences.sendMethod != expectedMethod) {
            return ClipboardSendOutcome(
                ClipboardSendStatus.WRONG_METHOD,
                if (expectedMethod == ClipboardSendMethod.QUICK_SETTINGS_TILE) {
                    "В настройках выберите способ «Плитка быстрых настроек»."
                } else if (expectedMethod == ClipboardSendMethod.IME_KEYBOARD) {
                    "В настройках приложения выберите способ «Клавиатура AndroidMacBridge»."
                } else {
                    "В настройках выбран другой способ отправки."
                }
            )
        }
        if (!preferences.enabled) {
            return ClipboardSendOutcome(ClipboardSendStatus.PAUSED, "Сначала включите общий буфер.")
        }
        if (!BluetoothModePreferences(context).enabled && Build.VERSION.SDK_INT >= 37 &&
            context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED
        ) {
            return ClipboardSendOutcome(
                ClipboardSendStatus.NO_NETWORK_PERMISSION,
                "Разрешите приложению доступ к локальной сети."
            )
        }
        client.refreshPair()
        if (client.pair == null) {
            return ClipboardSendOutcome(ClipboardSendStatus.NO_PAIR, "Сначала подключите Mac в приложении.")
        }
        return null
    }

    /** Read only while called by a focused app or the system-selected IME. */
    fun readClipboard(context: Context, notBeforeMillis: Long = 0L): ClipboardSnapshot {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (notBeforeMillis > 0L &&
            (manager.primaryClipDescription?.timestamp ?: 0L) < notBeforeMillis
        ) return ClipboardSnapshot(status = ClipboardSendStatus.EMPTY)
        val clip = manager.primaryClip ?: return ClipboardSnapshot(status = ClipboardSendStatus.EMPTY)
        if (notBeforeMillis > 0L && clip.description.timestamp < notBeforeMillis) {
            return ClipboardSnapshot(status = ClipboardSendStatus.EMPTY)
        }
        val isSensitive = Build.VERSION.SDK_INT >= 33 &&
            clip.description.extras?.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE) == true
        if (isSensitive) return ClipboardSnapshot(sensitive = true, status = ClipboardSendStatus.SENSITIVE)
        if (clip.itemCount == 0) return ClipboardSnapshot(status = ClipboardSendStatus.EMPTY)
        val item = clip.getItemAt(0)
        if (item.uri != null) {
            val uri = item.uri
            val resolver = context.contentResolver
            val mimeType = resolver.getType(uri) ?: (0 until clip.description.mimeTypeCount)
                .mapNotNull { runCatching { clip.description.getMimeType(it) }.getOrNull() }
                .firstOrNull { it == "image/png" || it == "image/jpeg" }
            if (mimeType != "image/png" && mimeType != "image/jpeg") {
                return ClipboardSnapshot(status = ClipboardSendStatus.UNSUPPORTED_IMAGE)
            }
            return try {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                (resolver.openInputStream(uri) ?: throw IOException("Изображение недоступно")).use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        if (output.size() + count > ClipboardImage.MAX_BYTES) {
                            return ClipboardSnapshot(status = ClipboardSendStatus.UNSUPPORTED_IMAGE)
                        }
                        output.write(buffer, 0, count)
                    }
                }
                ClipboardSnapshot(image = ClipboardImage.validate(output.toByteArray(), mimeType))
            } catch (_: Exception) {
                ClipboardSnapshot(status = ClipboardSendStatus.UNSUPPORTED_IMAGE)
            }
        }
        if (item.intent != null) {
            return ClipboardSnapshot(status = ClipboardSendStatus.UNSUPPORTED)
        }
        val text = item.text?.toString()?.takeIf(String::isNotEmpty)
            ?: return ClipboardSnapshot(status = ClipboardSendStatus.EMPTY)
        return ClipboardSnapshot(text = text)
    }

    fun send(
        context: Context,
        client: BridgeClient,
        snapshot: ClipboardSnapshot,
        expectedMethod: ClipboardSendMethod,
        eventId: String = java.util.UUID.randomUUID().toString()
    ): ClipboardSendOutcome {
        preflight(context, client, expectedMethod)?.let { return it }
        snapshot.status?.let { return outcomeFor(it) }
        if (snapshot.sensitive) return outcomeFor(ClipboardSendStatus.SENSITIVE)
        val preferences = ClipboardSyncPreferences(context)
        snapshot.image?.let { image ->
            val event = ClipboardImageEvent.create("android", image, eventId)
            if (preferences.shouldIgnore(event)) return outcomeFor(ClipboardSendStatus.IMAGE_DUPLICATE)
            return try {
                if (!client.supportsClipboardImages()) return outcomeFor(ClipboardSendStatus.UNSUPPORTED_IMAGE)
                val result = client.publishClipboardImage(event)
                preferences.markImageSeen(event)
                outcomeFor(if (result == "duplicate") ClipboardSendStatus.IMAGE_DUPLICATE else ClipboardSendStatus.IMAGE_SENT)
            } catch (error: Exception) {
                android.util.Log.w("BridgeClipboard", "image_send_failure=${error.javaClass.simpleName}")
                ClipboardSendOutcome(ClipboardSendStatus.IMAGE_FAILED, "Не удалось передать изображение на Mac.")
            }
        }
        val text = snapshot.text?.takeIf(String::isNotEmpty)
            ?: return outcomeFor(ClipboardSendStatus.EMPTY)
        val payload = try {
            ClipboardPayload.fromForegroundText(text, preferences.enabled, preferences.recentContentHash(), eventId)
        } catch (error: IllegalArgumentException) {
            val message = error.localizedMessage ?: "Текст не поддерживается."
            return ClipboardSendOutcome(
                if (message.contains("1 МиБ")) ClipboardSendStatus.UNSUPPORTED else ClipboardSendStatus.FAILED,
                message
            )
        } ?: return outcomeFor(ClipboardSendStatus.DUPLICATE)
        if (preferences.shouldIgnore(payload)) return outcomeFor(ClipboardSendStatus.DUPLICATE)

        ClipboardHistory(context).add(text)
        return try {
            val result = client.publishClipboard(payload)
            preferences.markSeen(payload)
            if (result == "duplicate") outcomeFor(ClipboardSendStatus.DUPLICATE)
            else outcomeFor(ClipboardSendStatus.SENT)
        } catch (error: Exception) {
            android.util.Log.w("BridgeClipboard", "send_failure=${error.javaClass.simpleName}")
            ClipboardSendOutcome(
                ClipboardSendStatus.FAILED,
                "Не удалось связаться с Mac. Проверьте Wi‑Fi и доступность Mac."
            )
        }
    }

    fun outcomeFor(status: ClipboardSendStatus): ClipboardSendOutcome = when (status) {
        ClipboardSendStatus.SENT -> ClipboardSendOutcome(status, "Текст отправлен на Mac.")
        ClipboardSendStatus.DUPLICATE -> ClipboardSendOutcome(status, "Этот текст уже синхронизирован.")
        ClipboardSendStatus.PAUSED -> ClipboardSendOutcome(status, "Сначала включите общий буфер.")
        ClipboardSendStatus.WRONG_METHOD -> ClipboardSendOutcome(status, "Выберите нужный способ отправки в настройках приложения.")
        ClipboardSendStatus.NO_PAIR -> ClipboardSendOutcome(status, "Сначала подключите Mac в приложении.")
        ClipboardSendStatus.NO_NETWORK_PERMISSION -> ClipboardSendOutcome(status, "Разрешите доступ к локальной сети в настройках приложения.")
        ClipboardSendStatus.EMPTY -> ClipboardSendOutcome(status, "В буфере нет текста.")
        ClipboardSendStatus.SENSITIVE -> ClipboardSendOutcome(status, "Чувствительный текст не отправлен.")
        ClipboardSendStatus.UNSUPPORTED -> ClipboardSendOutcome(status, "Можно отправить только обычный текст и ссылки до 1 МиБ.")
        ClipboardSendStatus.IMAGE_SENT -> ClipboardSendOutcome(status, "Изображение передано на Mac.")
        ClipboardSendStatus.IMAGE_DUPLICATE -> ClipboardSendOutcome(status, "Это изображение уже синхронизировано.")
        ClipboardSendStatus.UNSUPPORTED_IMAGE -> ClipboardSendOutcome(status, "Не удалось прочитать изображение. Поддерживаются PNG и JPEG до 8 МиБ.")
        ClipboardSendStatus.IMAGE_FAILED -> ClipboardSendOutcome(status, "Не удалось передать изображение на Mac.")
        ClipboardSendStatus.FAILED -> ClipboardSendOutcome(status, "Не удалось отправить текст на Mac.")
    }
}
