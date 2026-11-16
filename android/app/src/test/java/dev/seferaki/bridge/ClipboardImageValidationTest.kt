package dev.seferaki.bridge

import org.junit.Test

class ClipboardImageValidationTest {
    @Test fun rejectsEmptyImagesBeforeDecoding() {
        assertFails { ClipboardImage.validate(byteArrayOf(), "image/png") }
    }

    @Test fun rejectsImagesAboveTheTransferLimitBeforeDecoding() {
        assertFails { ClipboardImage.validate(ByteArray(ClipboardImage.MAX_BYTES + 1), "image/png") }
    }

    @Test fun rejectsUnsupportedMimeTypesBeforeDecoding() {
        assertFails { ClipboardImage.validate(byteArrayOf(1), "image/webp") }
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Ожидалось отклонение изображения")
        } catch (_: IllegalArgumentException) {
        }
    }
}
