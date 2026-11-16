package dev.seferaki.bridge

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.ClipboardManager
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Observes only copy-related UI events, never typed text or the contents of a screen. */
class ClipboardAccessibilityService : AccessibilityService() {
    private val isDebugBuild get() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val generation = AtomicLong()
    private val lastBridgeClipboardWriteAt = AtomicLong()
    private val client by lazy { BridgeClient(this) }
    private var lastLaunch = 0L
    private var notesSelection: NotesSelection? = null
    private val clipboardManager by lazy { getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        val now = SystemClock.elapsedRealtime()
        val bridgeWriteAge = now - lastBridgeClipboardWriteAt.get()
        val suppressed = bridgeWriteAge in 0L..BRIDGE_WRITE_SUPPRESSION_MS
        if (isDebugBuild) {
            Log.d("BridgeClipboard", "primary_clip_changed eligible=${eligible()} bridge_write_suppressed=$suppressed")
        }
        if (!suppressed) scheduleClipboardRead("primary_clip")
    }

    companion object {
        @Volatile private var active: ClipboardAccessibilityService? = null

        internal fun deliver(snapshot: ClipboardSnapshot, token: Long) {
            active?.send(snapshot, token, UUID.randomUUID().toString(), 0)
        }

        internal fun noteBridgeClipboardWrite() {
            active?.lastBridgeClipboardWriteAt?.set(SystemClock.elapsedRealtime())
        }

        private const val BRIDGE_WRITE_SUPPRESSION_MS = 750L
        private const val XIAOMI_NOTES_PACKAGE = "com.miui.notes"
        private const val COPY_EVENT_WINDOW_MS = 750L
        private val whatsAppCopiedMessage = Regex(
            """(?:message copied|\d+ messages? copied|сообщение скопировано|\d+ сообщени(?:е|я|й)\s+скопировано)"""
        )
        internal const val EXTRA_CLIPBOARD_NOT_BEFORE = "clipboard_not_before"
    }

    private data class NotesSelection(val windowId: Int, val startedAt: Long)

    override fun onServiceConnected() {
        super.onServiceConnected()
        active = this
        runCatching { clipboardManager.addPrimaryClipChangedListener(clipboardListener) }
            .onSuccess { if (isDebugBuild) Log.d("BridgeClipboard", "service_connected clipboard_listener_registered=true eligible=${eligible()}") }
            .onFailure { Log.w("BridgeClipboard", "clipboard_listener_register_failed=${it.javaClass.simpleName}") }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName?.toString() == packageName || event.isPassword) return
        if (!eligible()) {
            notesSelection = null
            return
        }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            if (event.packageName?.toString() == XIAOMI_NOTES_PACKAGE) handleNotesSelection(event)
            return
        }
        val labels = buildList {
            addAll(event.text.map { it.toString() })
            event.contentDescription?.let { add(it.toString()) }
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                event.source?.let { node ->
                    if (node.isPassword) return
                    node.text?.let { add(it.toString()) }
                    node.contentDescription?.let { add(it.toString()) }
                }
            }
        }
        if (isWhatsAppCopyToast(event, labels)) {
            scheduleClipboardRead("whatsapp_copy_toast", eventWallTime(event) - COPY_EVENT_WINDOW_MS)
            return
        }
        val matched = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> labels.any { isCopyLabel(it) }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> labels.any { isCopiedNotice(it) }
            else -> false
        }
        if (isDebugBuild) {
            val sourceClass = event.className?.toString()?.substringAfterLast('.') ?: "unknown"
            Log.d("BridgeClipboard", "accessibility_event type=${event.eventType} class=$sourceClass copy_match=$matched")
        }
        if (matched) scheduleClipboardRead("accessibility_event")
    }

    private fun handleNotesSelection(event: AccessibilityEvent) {
        if (event.fromIndex < 0 || event.toIndex < 0) return
        val changedAt = eventWallTime(event)
        val previous = notesSelection?.takeIf { it.windowId == event.windowId }
        val hasSelection = event.fromIndex != event.toIndex
        if (isDebugBuild) Log.d("BridgeClipboard", "notes_selection active=$hasSelection had_selection=${previous != null}")
        if (hasSelection) {
            notesSelection = previous ?: NotesSelection(event.windowId, changedAt)
            return
        }
        notesSelection = null
        if (previous == null) return
        // Xiaomi Notes can collapse the selection without exposing a click on its Copy button.
        // Only proceed if the focused window finds a clipboard write from this interaction.
        scheduleClipboardRead(
            "xiaomi_notes_selection_closed",
            maxOf(previous.startedAt, changedAt - COPY_EVENT_WINDOW_MS)
        )
    }

    private fun isWhatsAppCopyToast(event: AccessibilityEvent, labels: List<String>): Boolean {
        if (event.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED ||
            event.className?.toString()?.substringAfterLast('.') != "Toast"
        ) return false
        val source = event.packageName?.toString()
        if (source != "com.whatsapp" && source != "com.whatsapp.w4b") return false
        return labels.any { whatsAppCopiedMessage.matches(it.trim().lowercase().trimEnd('.', '!')) }
    }

    private fun eventWallTime(event: AccessibilityEvent): Long {
        val eventAge = (SystemClock.uptimeMillis() - event.eventTime).coerceAtLeast(0L)
        return System.currentTimeMillis() - eventAge
    }

    private fun scheduleClipboardRead(source: String, clipboardNotBefore: Long = 0L) {
        if (!eligible()) {
            if (isDebugBuild) Log.d("BridgeClipboard", "clipboard_read_skipped source=$source reason=ineligible")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunch < 500) {
            if (isDebugBuild) Log.d("BridgeClipboard", "clipboard_read_skipped source=$source reason=throttled")
            return
        }
        lastLaunch = now
        val token = generation.incrementAndGet()
        if (isDebugBuild) Log.d("BridgeClipboard", "clipboard_read_scheduled source=$source")
        handler.postDelayed({
            if (generation.get() != token || !eligible()) {
                if (isDebugBuild) Log.d("BridgeClipboard", "clipboard_read_skipped source=$source reason=stale_or_ineligible")
                return@postDelayed
            }
            runCatching {
                startActivity(Intent(this, ClipboardAccessibilityActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    putExtra("generation", token)
                    if (clipboardNotBefore > 0L) putExtra(EXTRA_CLIPBOARD_NOT_BEFORE, clipboardNotBefore)
                })
            }.onSuccess { if (isDebugBuild) Log.d("BridgeClipboard", "clipboard_activity_launched source=$source") }
                .onFailure { Log.w("BridgeClipboard", "accessibility_launch_failed=${it.javaClass.simpleName}") }
        }, 150)
    }

    private fun eligible(): Boolean {
        val preferences = ClipboardSyncPreferences(this)
        val locked = (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked
        return active === this && !locked && preferences.enabled && preferences.sendMethod == ClipboardSendMethod.ACCESSIBILITY
    }

    private fun send(snapshot: ClipboardSnapshot, token: Long, id: String, attempt: Int) {
        if (!eligible() || generation.get() != token || (snapshot.text.isNullOrEmpty() && snapshot.image == null) || snapshot.sensitive || snapshot.status != null) return
        worker.execute {
            if (!eligible() || generation.get() != token) return@execute
            val result = ClipboardSendCoordinator.send(this, client, snapshot, ClipboardSendMethod.ACCESSIBILITY, id)
            Log.i("BridgeClipboard", "accessibility_send=${result.status} attempt=$attempt")
            val delays = longArrayOf(2000, 5000, 10000)
            if (result.status in setOf(ClipboardSendStatus.FAILED, ClipboardSendStatus.IMAGE_FAILED) && attempt < delays.size) {
                handler.postDelayed({ send(snapshot, token, id, attempt + 1) }, delays[attempt])
            }
        }
    }

    override fun onInterrupt() {
        generation.incrementAndGet()
        notesSelection = null
    }

    override fun onDestroy() {
        if (active === this) active = null
        generation.incrementAndGet()
        handler.removeCallbacksAndMessages(null)
        runCatching { clipboardManager.removePrimaryClipChangedListener(clipboardListener) }
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun isCopyLabel(label: String): Boolean {
        val value = label.trim().lowercase().replace(Regex("[.!…:]+$"), "")
        return value in setOf("copy", "copy text", "copy to clipboard", "копировать", "скопировать", "копировать текст", "скопировать текст", "копировать в буфер обмена") ||
            value.contains("copy") || value.contains("копир")
    }

    private fun isCopiedNotice(label: String): Boolean {
        val value = label.trim().lowercase()
        return value == "copied" || value.startsWith("copied to clipboard") ||
            value.startsWith("скопирован") || value == "текст скопирован"
    }
}
