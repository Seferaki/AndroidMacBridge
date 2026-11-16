package dev.seferaki.bridge

import android.util.Base64
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Application-layer authentication and AEAD; token bytes are never broadcast. */
internal class BluetoothSessionCrypto(private val token: String, private val macDeviceId: String) {
    private val nonceA = ByteArray(32).also(SecureRandom()::nextBytes)
    private var nonceM: ByteArray? = null
    private var sendKey: ByteArray? = null
    private var receiveKey: ByteArray? = null
    private var sendCounter = 0L
    private var receiveCounter = 0L
    var isAuthenticated: Boolean = false
        private set

    fun hello(): ByteArray = JSONObject().put("type", "hello").put("version", 1)
        .put("macDeviceId", macDeviceId).put("nonceA", encode(nonceA)).toString().toByteArray(Charsets.UTF_8)

    @Synchronized fun acceptChallenge(data: ByteArray): ByteArray {
        val challenge = JSONObject(String(data, Charsets.UTF_8))
        require(challenge.getString("type") == "challenge" && challenge.getInt("version") == 1) { "Неподдерживаемый Bluetooth-протокол" }
        val receivedNonce = decode(challenge.getString("nonceM"), 32)
        val expected = hmac(token.toByteArray(Charsets.UTF_8), proofMessage("SMB-BLE-CHALLENGE-1", macDeviceId, nonceA, receivedNonce))
        require(MessageDigest.isEqual(expected, decode(challenge.getString("proof"), 32))) { "Не удалось подтвердить пару Bluetooth" }
        nonceM = receivedNonce
        val root = hkdf(token.toByteArray(Charsets.UTF_8), nonceA + receivedNonce, "SamsungMacBridge BLE v1|$macDeviceId".toByteArray(Charsets.UTF_8), 32)
        sendKey = hkdf(root, ByteArray(0), "SamsungMacBridge BLE v1 key A2M".toByteArray(Charsets.UTF_8), 32)
        receiveKey = hkdf(root, ByteArray(0), "SamsungMacBridge BLE v1 key M2A".toByteArray(Charsets.UTF_8), 32)
        root.fill(0)
        sendCounter = 0; receiveCounter = 0
        val proof = hmac(token.toByteArray(Charsets.UTF_8), proofMessage("SMB-BLE-PROOF-1", macDeviceId, nonceA, receivedNonce))
        return JSONObject().put("type", "proof").put("version", 1).put("proof", encode(proof)).toString().toByteArray(Charsets.UTF_8)
    }

    @Synchronized fun markAuthenticated() { check(sendKey != null && receiveKey != null); isAuthenticated = true }

    @Synchronized fun seal(payload: ByteArray, direction: String = "A2M"): ByteArray {
        val sessionKey = sendKey ?: error("Bluetooth-сеанс не подтверждён")
        require(direction == "A2M")
        check(sendCounter < Long.MAX_VALUE) { "Переполнен счётчик Bluetooth-сеанса" }
        val counter = sendCounter++
        val counterBytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(counter).array()
        val nonce = nonceFor(sessionKey, direction, counterBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad(direction, counterBytes))
        return byteArrayOf(0x53, 0x01) + counterBytes + cipher.doFinal(payload)
    }

    @Synchronized fun open(frame: ByteArray, direction: String = "M2A"): ByteArray {
        val sessionKey = receiveKey ?: error("Bluetooth-сеанс не подтверждён")
        require(direction == "M2A" && frame.size >= 26 && frame[0] == 0x53.toByte() && frame[1] == 0x01.toByte()) { "Повреждённый Bluetooth-пакет" }
        val counterBytes = frame.copyOfRange(2, 10)
        val counter = ByteBuffer.wrap(counterBytes).order(ByteOrder.BIG_ENDIAN).long
        require(counter == receiveCounter && counter < Long.MAX_VALUE) { "Повтор или пропуск Bluetooth-пакета" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(128, nonceFor(sessionKey, direction, counterBytes)))
        cipher.updateAAD(aad(direction, counterBytes))
        val result = cipher.doFinal(frame.copyOfRange(10, frame.size))
        receiveCounter++
        return result
    }

    @Synchronized fun clear() { sendKey?.fill(0); receiveKey?.fill(0); sendKey = null; receiveKey = null; nonceM?.fill(0); nonceM = null; isAuthenticated = false; sendCounter = 0; receiveCounter = 0 }

    companion object {
        internal fun proofMessage(label: String, macDeviceId: String, a: ByteArray, m: ByteArray) = "$label|$macDeviceId|${encode(a)}|${encode(m)}".toByteArray(Charsets.UTF_8)
        internal fun hmac(key: ByteArray, data: ByteArray): ByteArray { val mac = Mac.getInstance("HmacSHA256"); mac.init(SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(data) }
        internal fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        private fun aad(direction: String, counter: ByteArray) = "SMBBLE1|$direction|".toByteArray(Charsets.UTF_8) + counter
        private fun nonceFor(key: ByteArray, direction: String, counter: ByteArray): ByteArray = hmac(key, "nonce:$direction".toByteArray(Charsets.UTF_8)).copyOfRange(0, 4) + counter
        private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, size: Int): ByteArray {
            val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm); var previous = ByteArray(0); val output = ByteArrayOutputStreamCompat()
            var index = 1
            while (output.size < size) { previous = hmac(prk, previous + info + byteArrayOf(index.toByte())); output.write(previous); index++ }
            prk.fill(0); return output.toByteArray().copyOf(size)
        }
        private fun decode(text: String, length: Int): ByteArray = Base64.decode(text, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING).also { require(it.size == length) { "Некорректный Bluetooth nonce" } }
    }
}

private class ByteArrayOutputStreamCompat {
    private val out = java.io.ByteArrayOutputStream()
    val size get() = out.size()
    fun write(value: ByteArray) = out.write(value)
    fun toByteArray() = out.toByteArray()
}
