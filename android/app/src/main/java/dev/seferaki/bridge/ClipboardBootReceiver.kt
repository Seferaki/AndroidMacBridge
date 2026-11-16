package dev.seferaki.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
class ClipboardBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (BackgroundTransferService.shouldStart(context) && PairStorage(context).load() != null) {
            runCatching { BackgroundTransferService.start(context) }
        }
        val preferences = ClipboardSyncPreferences(context)
        if (!preferences.enabled) return
        try {
            ClipboardSyncService.start(context)
        } catch (error: Exception) {
            preferences.enabled = false
            preferences.status = "Не удалось восстановить службу после перезагрузки: ${error.localizedMessage ?: "запустите приложение"}"
        }
    }
}
