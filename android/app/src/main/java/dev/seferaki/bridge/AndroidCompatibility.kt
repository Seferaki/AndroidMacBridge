package dev.seferaki.bridge

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import java.util.Locale

internal enum class CompatibilityState {
    AVAILABLE,
    NEEDS_PERMISSION,
    UNAVAILABLE,
    LIMITED,
    UNKNOWN
}

internal data class CompatibilityCapability(
    val state: CompatibilityState,
    val description: String
)

/** Device identity stays local; only a short, safe label is sent to the paired Mac. */
internal object AndroidDeviceIdentity {
    fun displayName(manufacturer: String?, model: String?): String {
        val cleanManufacturer = cleanPart(manufacturer)
        val cleanModel = cleanPart(model)
        if (cleanModel.isEmpty()) return "Телефон Android"
        if (cleanManufacturer.isEmpty() || cleanManufacturer.equals("unknown", ignoreCase = true)) return cleanModel
        if (cleanModel.startsWith(cleanManufacturer, ignoreCase = true)) return cleanModel
        val titleManufacturer = cleanManufacturer.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
        }
        return "$titleManufacturer $cleanModel".take(64)
    }

    fun networkLabel(manufacturer: String?, model: String?): String {
        val label = displayName(manufacturer, model)
        val ascii = label.filter { it.code in 0x20..0x7E && (it.isLetterOrDigit() || it in " ._-") }
            .trim()
            .take(64)
        if (ascii.isNotEmpty()) return ascii
        val asciiModel = cleanPart(model).filter { it.code in 0x20..0x7E && (it.isLetterOrDigit() || it in " ._-") }
            .trim()
            .take(64)
        return asciiModel.ifEmpty { "Android" }
    }

    private fun cleanPart(value: String?): String = value.orEmpty()
        .map { if (Character.isISOControl(it)) ' ' else it }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(64)
}

internal data class AndroidCompatibilitySnapshot(
    val deviceName: String,
    val androidVersion: String,
    val apiLevel: Int,
    val localNetwork: CompatibilityCapability,
    val bluetoothLe: CompatibilityCapability,
    val notifications: CompatibilityCapability,
    val keyboard: CompatibilityCapability,
    val accessibility: CompatibilityCapability,
    val clipboardService: String,
    val fileReceiveService: String,
    val backgroundPolicy: String
) {
    fun format(): String = listOf(
        "Устройство: $deviceName",
        "Android $androidVersion · API $apiLevel",
        "Локальная сеть: ${localNetwork.description}",
        "Bluetooth LE: ${bluetoothLe.description}",
        "Уведомления: ${notifications.description}",
        "Клавиатура AndroidMacBridge: ${keyboard.description}",
        "Автокопирование: ${accessibility.description}",
        "Общий буфер в фоне: $clipboardService",
        "Приём файлов в фоне: $fileReceiveService",
        "Фоновая работа: $backgroundPolicy",
        "«Поделиться»: файлы и текст принимаются через системное меню.",
        "Если причина ограничения не показана Android, проверьте настройки батареи и фоновой работы вручную."
    ).joinToString("\n")

    companion object {
        fun capture(context: Context): AndroidCompatibilitySnapshot {
            val localNetworkPermission = Build.VERSION.SDK_INT < 37 ||
                context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") == PackageManager.PERMISSION_GRANTED
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val capabilities = runCatching {
                connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
            }.getOrNull()
            val localTransport = capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true ||
                capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true
            val networkStatus = when {
                !localNetworkPermission -> CompatibilityCapability(CompatibilityState.NEEDS_PERMISSION, "доступ не выдан; разрешите его в настройках приложения")
                localTransport -> CompatibilityCapability(CompatibilityState.AVAILABLE, "Wi‑Fi или Ethernet подключён")
                capabilities == null -> CompatibilityCapability(CompatibilityState.LIMITED, "локальная сеть не подключена")
                else -> CompatibilityCapability(CompatibilityState.LIMITED, "подключите Mac и телефон к одной Wi‑Fi сети; доступен Bluetooth LE")
            }

            val ble = bluetoothCapability(context)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            val notificationStatus = when {
                Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                    CompatibilityCapability(CompatibilityState.NEEDS_PERMISSION, "разрешение не выдано; фоновые уведомления могут быть скрыты")
                notificationManager?.areNotificationsEnabled() == false ->
                    CompatibilityCapability(CompatibilityState.LIMITED, "уведомления приложения отключены в Android")
                else -> CompatibilityCapability(CompatibilityState.AVAILABLE, "разрешены")
            }

            val keyboardStatus = when (ClipboardKeyboardStatus.read(context)) {
                ClipboardKeyboardAvailability.SELECTED -> CompatibilityCapability(CompatibilityState.AVAILABLE, "выбрана")
                ClipboardKeyboardAvailability.ENABLED_NOT_SELECTED -> CompatibilityCapability(CompatibilityState.LIMITED, "включена, но выбрана другая клавиатура")
                ClipboardKeyboardAvailability.NOT_ENABLED -> CompatibilityCapability(CompatibilityState.NEEDS_PERMISSION, "не включена в настройках клавиатур Android")
                ClipboardKeyboardAvailability.UNAVAILABLE -> CompatibilityCapability(CompatibilityState.UNKNOWN, "состояние не удалось определить")
            }

            val accessibilityEnabled = isAccessibilityServiceEnabled(context)
            val accessibilityStatus = if (accessibilityEnabled) {
                CompatibilityCapability(CompatibilityState.AVAILABLE, "служба включена; работа зависит от приложения-источника")
            } else {
                CompatibilityCapability(CompatibilityState.NEEDS_PERMISSION, "служба выключена; включайте её только если выбрали этот способ")
            }

            val preferences = ClipboardSyncPreferences(context)
            val clipboardState = when {
                !preferences.enabled -> "выключен"
                ClipboardSyncService.isRunning -> "служба запущена"
                else -> "включён в настройках, но служба сейчас не подтверждена"
            }
            val backgroundEnabled = BackgroundTransferService.shouldStart(context)
            val fileReceiveState = when {
                !backgroundEnabled -> "выключен"
                BackgroundTransferService.isRunning -> "служба запущена"
                else -> "ожидает запуска; фактическое ограничение неизвестно"
            }
            val backgroundPolicy = backgroundPolicy(context)

            return AndroidCompatibilitySnapshot(
                deviceName = AndroidDeviceIdentity.displayName(Build.MANUFACTURER, Build.MODEL),
                androidVersion = Build.VERSION.RELEASE.orEmpty().ifBlank { "неизвестна" },
                apiLevel = Build.VERSION.SDK_INT,
                localNetwork = networkStatus,
                bluetoothLe = ble,
                notifications = notificationStatus,
                keyboard = keyboardStatus,
                accessibility = accessibilityStatus,
                clipboardService = clipboardState,
                fileReceiveService = fileReceiveState,
                backgroundPolicy = backgroundPolicy
            )
        }

        private fun isAccessibilityServiceEnabled(context: Context): Boolean = runCatching {
            val expected = ComponentName(context, ClipboardAccessibilityService::class.java)
            val enabledInSecureSettings = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty().split(':').mapNotNull {
                ComponentName.unflattenFromString(it.trim())
            }.any { it == expected }

            val enabledInAccessibilityManager = (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)
                ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.any { accessibilityService ->
                    val serviceInfo = accessibilityService.resolveInfo?.serviceInfo ?: return@any false
                    serviceInfo.packageName == expected.packageName &&
                        ComponentName.createRelative(serviceInfo.packageName, serviceInfo.name) == expected
                } == true

            enabledInSecureSettings || enabledInAccessibilityManager
        }.getOrDefault(false)

        private fun backgroundPolicy(context: Context): String = runCatching {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val restricted = Build.VERSION.SDK_INT >= 28 && activityManager?.isBackgroundRestricted == true
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            when {
                restricted -> "Android отмечает приложение как ограниченное; откройте настройки приложения и проверьте батарею."
                Build.VERSION.SDK_INT >= 23 && powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true ->
                    "Android не сообщает об ограничении фоновой работы."
                else -> "активна обычная оптимизация батареи; точную причину остановки службы определить нельзя"
            }
        }.getOrDefault("состояние неизвестно; проверьте настройки батареи и фоновой работы")
    }
}

@SuppressLint("MissingPermission")
internal fun bluetoothCapability(context: Context): CompatibilityCapability {
    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
        return CompatibilityCapability(CompatibilityState.UNAVAILABLE, "устройство не поддерживает BLE; используйте Wi‑Fi")
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
    ) {
        return CompatibilityCapability(CompatibilityState.NEEDS_PERMISSION, "нужно разрешить Bluetooth для режима BLE")
    }
    return runCatching {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        when {
            manager?.adapter == null -> CompatibilityCapability(CompatibilityState.UNAVAILABLE, "Bluetooth-адаптер недоступен; используйте Wi‑Fi")
            manager.adapter.isEnabled -> CompatibilityCapability(CompatibilityState.AVAILABLE, "поддерживается и включён")
            else -> CompatibilityCapability(CompatibilityState.LIMITED, "выключен; включите вручную или используйте Wi‑Fi")
        }
    }.getOrElse {
        CompatibilityCapability(CompatibilityState.UNKNOWN, "состояние адаптера не удалось определить")
    }
}
