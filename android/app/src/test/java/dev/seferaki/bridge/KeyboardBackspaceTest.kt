package dev.seferaki.bridge

import android.view.inputmethod.InputConnection
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardBackspaceTest {
    private fun connection(selected: CharSequence?, codePointsSupported: Boolean, calls: MutableList<String>): InputConnection =
        Proxy.newProxyInstance(InputConnection::class.java.classLoader, arrayOf(InputConnection::class.java)) { _, method, args ->
            when (method.name) {
                "getSelectedText" -> selected
                "commitText" -> { calls.add("selection:${args?.get(0)}"); true }
                "deleteSurroundingTextInCodePoints" -> { calls.add("codepoint:${args?.get(0)}:${args?.get(1)}"); codePointsSupported }
                "deleteSurroundingText" -> { calls.add("characters:${args?.get(0)}:${args?.get(1)}"); true }
                else -> throw AssertionError("Unexpected call: ${method.name}")
            }
        } as InputConnection

    @Test fun deletesWholeSelectionInsteadOfAdjacentCharacters() {
        val calls = mutableListOf<String>()
        assertTrue(deleteKeyboardSelectionOrPrevious(connection("выделенный текст", true, calls), false))
        assertEquals(listOf("selection:"), calls)
    }

    @Test fun usesEditorSelectionWhenSelectedTextIsUnavailable() {
        val calls = mutableListOf<String>()
        assertTrue(deleteKeyboardSelectionOrPrevious(connection(null, true, calls), true))
        assertEquals(listOf("selection:"), calls)
    }

    @Test fun deletesOneCodePointAtCaretAndFallsBackWhenNeeded() {
        val calls = mutableListOf<String>()
        assertTrue(deleteKeyboardSelectionOrPrevious(connection(null, false, calls), false))
        assertEquals(listOf("codepoint:1:0", "characters:1:0"), calls)
    }
}
