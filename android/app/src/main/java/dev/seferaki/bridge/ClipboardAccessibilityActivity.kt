package dev.seferaki.bridge

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager

/** A short-lived focused window is required by Android 10+ for clipboard reads. */
class ClipboardAccessibilityActivity : Activity() {
    private val isDebugBuild get() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val handler = Handler(Looper.getMainLooper())
    private var read = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isDebugBuild) Log.d("BridgeClipboard", "clipboard_activity_created")
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED)
        overridePendingTransition(0, 0)
        handler.postDelayed({ finish() }, 2000)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (isDebugBuild) Log.d("BridgeClipboard", "clipboard_activity_focus=$hasFocus read_started=$read")
        if (!hasFocus || read) return
        read = true
        handler.postDelayed({
            val prefs = ClipboardSyncPreferences(this)
            val locked = (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked
            if (hasWindowFocus() && !locked && prefs.enabled && prefs.sendMethod == ClipboardSendMethod.ACCESSIBILITY) {
                val clipboardNotBefore = intent.getLongExtra(ClipboardAccessibilityService.EXTRA_CLIPBOARD_NOT_BEFORE, 0L)
                runCatching { ClipboardSendCoordinator.readClipboard(this, clipboardNotBefore) }
                    .onFailure { if (isDebugBuild) Log.w("BridgeClipboard", "clipboard_read_failed=${it.javaClass.simpleName}") }
                    .getOrNull()?.also { snapshot ->
                        if (isDebugBuild) {
                            val kind = when {
                                snapshot.status != null -> snapshot.status.name
                                snapshot.image != null -> "IMAGE"
                                !snapshot.text.isNullOrEmpty() -> "TEXT"
                                else -> "EMPTY"
                            }
                            Log.d("BridgeClipboard", "clipboard_read_result=$kind")
                        }
                    }?.let { ClipboardAccessibilityService.deliver(it, intent.getLongExtra("generation", -1)) }
            } else if (isDebugBuild) {
                Log.d("BridgeClipboard", "clipboard_read_skipped focus=${hasWindowFocus()} locked=$locked enabled=${prefs.enabled} method=${prefs.sendMethod}")
            }
            finish()
            overridePendingTransition(0, 0)
        }, 100)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
