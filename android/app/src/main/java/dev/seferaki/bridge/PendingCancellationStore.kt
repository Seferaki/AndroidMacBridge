package dev.seferaki.bridge

import android.content.Context

/** Keeps cancellation requests until the paired Mac confirms a terminal result. */
internal class PendingCancellationStore(context: Context) {
    private val prefs = context.getSharedPreferences("bridge_pending_cancellations", Context.MODE_PRIVATE)

    fun add(id: String, deviceId: String) {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        check(prefs.edit().putString(id, deviceId).commit()) { "Не удалось сохранить состояние отмены" }
    }
    fun contains(id: String): Boolean = prefs.contains(id)
    fun clear() { prefs.edit().clear().commit() }

    fun reconcile(client: BridgeClient) {
        val deviceId = client.pair?.deviceId ?: return
        for ((id, savedDevice) in prefs.all) {
            if (savedDevice != deviceId) continue
            val result = runCatching { client.reconcileTransfer(id) }.getOrDefault(TransferOutcome.UNKNOWN)
            when (cancellationDecision(result)) {
                CancellationDecision.CLEAR -> prefs.edit().remove(id).commit()
                CancellationDecision.SEND -> if (runCatching { client.requestCancellation(id) }.isSuccess) prefs.edit().remove(id).commit()
                CancellationDecision.WAIT -> Unit
            }
        }
    }
}
