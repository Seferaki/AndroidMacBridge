package dev.seferaki.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ClipboardHistoryPairKeyTest {
    @Test fun eachPhoneMacPairGetsAStableIsolatedHistoryKey() {
        val pairA = clipboardHistoryStorageKey("mac-device", "phone-A")
        val pairB = clipboardHistoryStorageKey("mac-device", "phone-B")

        assertEquals(pairA, clipboardHistoryStorageKey("mac-device", "phone-A"))
        assertNotEquals(pairA, pairB)
        assertEquals(69, pairA.length) // "pair_" plus the 64-character SHA-256 digest.
        assertNotEquals(pairA, clipboardHistoryStorageKey("another-mac", "phone-A"))
        assertNotEquals(pairA, legacyClipboardHistoryStorageKey("phone-A"))
    }
}
