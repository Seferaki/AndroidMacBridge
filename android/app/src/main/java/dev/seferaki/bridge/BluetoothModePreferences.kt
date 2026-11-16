package dev.seferaki.bridge

import android.content.Context

internal class BluetoothModePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("bridge_settings", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }
    var status: String
        get() {
            val saved = prefs.getString(KEY_STATUS, "Bluetooth выключен") ?: "Bluetooth выключен"
            // Translate Bluetooth failure statuses saved by older builds into the clearer wording.
            return saved.replaceFirst(
                "Не удалось включить Bluetooth-рекламу (",
                "Не удалось сделать телефон видимым для поиска по Bluetooth ("
            )
        }
        set(value) { prefs.edit().putString(KEY_STATUS, value).apply() }

    companion object { private const val KEY_ENABLED = "bluetooth_mode_enabled"; private const val KEY_STATUS = "bluetooth_mode_status" }
}
