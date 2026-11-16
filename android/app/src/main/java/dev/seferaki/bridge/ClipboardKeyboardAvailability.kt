package dev.seferaki.bridge

import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

internal enum class ClipboardKeyboardAvailability {
    NOT_ENABLED,
    ENABLED_NOT_SELECTED,
    SELECTED,
    UNAVAILABLE
}

internal object ClipboardKeyboardStatus {
    fun read(context: Context): ClipboardKeyboardAvailability = runCatching {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return ClipboardKeyboardAvailability.UNAVAILABLE
        val enabled = manager.enabledInputMethodList.any { it.packageName == context.packageName }
        if (!enabled) return ClipboardKeyboardAvailability.NOT_ENABLED
        val currentId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        )
        val currentPackage = currentId?.substringBefore('/')
            ?: return ClipboardKeyboardAvailability.UNAVAILABLE
        if (currentPackage == context.packageName) ClipboardKeyboardAvailability.SELECTED
        else ClipboardKeyboardAvailability.ENABLED_NOT_SELECTED
    }.getOrDefault(ClipboardKeyboardAvailability.UNAVAILABLE)
}
