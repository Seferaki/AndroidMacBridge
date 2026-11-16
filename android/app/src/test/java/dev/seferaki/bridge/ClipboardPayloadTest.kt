package dev.seferaki.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ClipboardPayloadTest {
    @Test fun foregroundRetryRetainsEventIdentity() {
        val id = java.util.UUID.randomUUID().toString()
        val first = ClipboardPayload.fromForegroundText("test", true, eventId = id)
        val retry = ClipboardPayload.fromForegroundText("test", true, eventId = id)
        assertEquals(first?.eventId, retry?.eventId)
        assertEquals(id, retry?.eventId)
    }
    @Test fun classifiesPlainTextAndWebLinks() {
        assertEquals("text", ClipboardPayload.fromText("Привет", "android").type)
        assertEquals("link", ClipboardPayload.fromText("https://example.com/path", "android").type)
        assertEquals("link", ClipboardPayload.fromText("HTTP://example.com", "android").type)
    }

    @Test fun rejectsEmptyOversizedAndMalformedWebValues() {
        listOf("", "https://", "http://no host").forEach { value ->
            assertFails { ClipboardPayload.fromText(value, "android") }
        }
        assertFails { ClipboardPayload.fromText("é".repeat(MAX_CLIPBOARD_BYTES / 2 + 1), "android") }
    }

    @Test fun rejectsUnknownSource() {
        assertFails { ClipboardPayload.fromText("hello", "unknown") }
    }

    @Test fun foregroundReadRequiresEnabledModeAndChangedNonEmptyText() {
        assertEquals(null, ClipboardPayload.fromForegroundText("secret", false))
        assertEquals(null, ClipboardPayload.fromForegroundText("", true))
        assertEquals(null, ClipboardPayload.fromForegroundText("same", true, ClipboardPayload.fromText("same", "android").contentHash()))
        assertEquals("new", ClipboardPayload.fromForegroundText("new", true)?.text)
        assertFails { ClipboardPayload.fromForegroundText("x".repeat(MAX_CLIPBOARD_BYTES + 1), true) }
    }

    @Test fun hashesTextWithoutRetainingAHistory() {
        val first = ClipboardPayload.fromText("same", "android")
        val duplicate = ClipboardPayload.fromText("same", "mac")
        val different = ClipboardPayload.fromText("other", "mac")
        assertEquals(first.contentHash(), duplicate.contentHash())
        assertFalse(first.contentHash() == different.contentHash())
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Ожидалось отклонение clipboard-значения")
        } catch (_: IllegalArgumentException) {
        }
    }
}
