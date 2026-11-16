package dev.seferaki.bridge

import android.view.inputmethod.InputConnection

/** Deletes the current selection first; with a caret, deletes one Unicode code point. */
internal fun deleteKeyboardSelectionOrPrevious(connection: InputConnection, selectionKnown: Boolean): Boolean {
    val selected = runCatching { connection.getSelectedText(0) }.getOrNull()
    if (!selected.isNullOrEmpty() || selectionKnown) return connection.commitText("", 1)
    if (connection.deleteSurroundingTextInCodePoints(1, 0)) return true
    return connection.deleteSurroundingText(1, 0)
}
