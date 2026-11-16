package dev.seferaki.bridge

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Fragments logical messages for ATT characteristics without assuming a fixed MTU. */
internal object BluetoothGattProtocol {
    const val SERVICE_UUID = "6d2f0001-8a14-4e72-9b2a-5a4d42524944"
    const val RX_UUID = "6d2f0002-8a14-4e72-9b2a-5a4d42524944"
    const val TX_UUID = "6d2f0003-8a14-4e72-9b2a-5a4d42524944"
    // Includes the JSON/base64 envelope around an 8 MiB clipboard image plus AEAD overhead.
    const val MAX_MESSAGE_BYTES = 12 * 1024 * 1024
    private const val HEADER_BYTES = 8
    private const val MAX_FRAGMENTS = 65_535

    data class Fragment(val messageId: Int, val index: Int, val count: Int, val payload: ByteArray) {
        fun encode(): ByteArray = ByteBuffer.allocate(HEADER_BYTES + payload.size).order(ByteOrder.BIG_ENDIAN)
            .putInt(messageId).putShort(index.toShort()).putShort(count.toShort()).put(payload).array()
    }

    class Assembler {
        private var currentId: Int? = null
        private var expectedCount = 0
        private var nextIndex = 0
        private var output = ByteArrayOutputStream()

        @Synchronized fun append(bytes: ByteArray): ByteArray? {
            require(bytes.size >= HEADER_BYTES) { "Повреждённый Bluetooth-кадр" }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val id = buffer.int
            val index = buffer.short.toInt() and 0xffff
            val count = buffer.short.toInt() and 0xffff
            require(count in 1..MAX_FRAGMENTS && index < count) { "Недопустимый фрагмент Bluetooth-кадра" }
            if (index == 0) {
                currentId = id; expectedCount = count; nextIndex = 0; output = ByteArrayOutputStream()
            }
            require(currentId == id && count == expectedCount && index == nextIndex) { "Нарушен порядок Bluetooth-фрагментов" }
            val size = bytes.size - HEADER_BYTES
            require(output.size() + size <= MAX_MESSAGE_BYTES) { "Bluetooth-сообщение слишком велико" }
            output.write(bytes, HEADER_BYTES, size); nextIndex++
            if (nextIndex != expectedCount) return null
            val result = output.toByteArray()
            currentId = null; expectedCount = 0; nextIndex = 0; output = ByteArrayOutputStream()
            return result
        }
    }

    fun fragment(messageId: Int, message: ByteArray, characteristicLimit: Int): List<ByteArray> {
        require(message.isNotEmpty() && message.size <= MAX_MESSAGE_BYTES) { "Недопустимый размер Bluetooth-сообщения" }
        val pieceSize = characteristicLimit - HEADER_BYTES
        require(pieceSize > 0) { "Слишком малый размер GATT-кадра" }
        val count = (message.size + pieceSize - 1) / pieceSize
        require(count <= MAX_FRAGMENTS) { "Слишком много Bluetooth-фрагментов" }
        return (0 until count).map { index ->
            val start = index * pieceSize
            Fragment(messageId, index, count, message.copyOfRange(start, minOf(message.size, start + pieceSize))).encode()
        }
    }
}
