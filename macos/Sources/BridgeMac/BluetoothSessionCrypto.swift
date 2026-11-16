import Foundation
import CryptoKit
import Security
import BridgeCore

/// App-pair authentication and directional AEAD framing for the non-IP BLE link.
final class BluetoothSessionCrypto {
    private let state: BridgeState
    private var androidNonce: Data?
    private var macNonce: Data?
    private var macDeviceID = ""
    private var sendKey: SymmetricKey?
    private var receiveKey: SymmetricKey?
    private var sendCounter: UInt64 = 0
    private var receiveCounter: UInt64 = 0
    private(set) var authenticated = false

    init(state: BridgeState) { self.state = state }

    func acceptHello(_ data: Data) throws -> Data {
        let object = try JSONSerialization.jsonObject(with:data) as? [String:Any]
        guard let object = object, object["type"] as? String == "hello", object["version"] as? Int == 1,
              let macID = object["macDeviceId"] as? String, macID == state.deviceID,
              let aText = object["nonceA"] as? String, let a = Data(base64URL:aText), a.count == 32 else {
            throw BridgeError.invalid("Некорректное Bluetooth-рукопожатие")
        }
        var bytes = [UInt8](repeating:0,count:32)
        guard SecRandomCopyBytes(kSecRandomDefault,bytes.count,&bytes) == errSecSuccess else { throw BridgeError.invalid("Не удалось создать Bluetooth nonce") }
        let m = Data(bytes)
        guard let proof = state.bluetoothChallenge(macDeviceID:macID,androidNonce:a,macNonce:m), proof.count == 32 else { throw BridgeError.unauthorized }
        androidNonce = a; macNonce = m; macDeviceID = macID
        return try JSONSerialization.data(withJSONObject:["type":"challenge","version":1,"nonceM":m.base64URLEncodedString(),"proof":proof.base64URLEncodedString()])
    }

    func acceptProof(_ data: Data) throws {
        let object = try JSONSerialization.jsonObject(with:data) as? [String:Any]
        guard let object = object, object["type"] as? String == "proof", object["version"] as? Int == 1,
              let proofText = object["proof"] as? String, let proof = Data(base64URL:proofText),
              let a = androidNonce, let m = macNonce,
              let rawKey = state.bluetoothSessionKey(macDeviceID:macDeviceID,androidNonce:a,macNonce:m,proof:proof) else {
            throw BridgeError.unauthorized
        }
        let root = SymmetricKey(data:rawKey)
        sendKey = HKDF<SHA256>.deriveKey(inputKeyMaterial:root,salt:Data(),info:Data("SamsungMacBridge BLE v1 key M2A".utf8),outputByteCount:32)
        receiveKey = HKDF<SHA256>.deriveKey(inputKeyMaterial:root,salt:Data(),info:Data("SamsungMacBridge BLE v1 key A2M".utf8),outputByteCount:32)
        sendCounter = 0; receiveCounter = 0; authenticated = true
    }

    func seal(_ payload: Data) throws -> Data {
        guard authenticated, let key = sendKey else { throw BridgeError.unauthorized }
        guard sendCounter < UInt64.max else { throw BridgeError.invalid("Переполнен счётчик Bluetooth") }
        let counter = sendCounter; sendCounter += 1
        let counterData = counter.bigEndianData
        let nonce = try AES.GCM.Nonce(data:nonceFor(key:key,direction:"M2A",counter:counterData))
        let box = try AES.GCM.seal(payload,using:key,nonce:nonce,authenticating:aad(direction:"M2A",counter:counterData))
        return Data([0x53,0x01]) + counterData + box.ciphertext + box.tag
    }

    func open(_ frame: Data) throws -> Data {
        guard authenticated, let key = receiveKey, frame.count >= 26, frame[frame.startIndex] == 0x53, frame[frame.startIndex + 1] == 0x01 else {
            throw BridgeError.invalid("Повреждённый Bluetooth-пакет")
        }
        let counterData = frame.subdata(in:2..<10)
        let counter = UInt64(bigEndianBytes:counterData)
        guard counter == receiveCounter, counter < UInt64.max else { throw BridgeError.invalid("Повтор или пропуск Bluetooth-пакета") }
        let nonce = try AES.GCM.Nonce(data:nonceFor(key:key,direction:"A2M",counter:counterData))
        let box = try AES.GCM.SealedBox(nonce:nonce,ciphertext:frame.subdata(in:10..<(frame.count - 16)),tag:frame.suffix(16))
        let plaintext = try AES.GCM.open(box,using:key,authenticating:aad(direction:"A2M",counter:counterData))
        receiveCounter += 1
        return plaintext
    }

    func clear() {
        sendKey = nil; receiveKey = nil; androidNonce = nil; macNonce = nil; macDeviceID = ""; authenticated = false
        sendCounter = 0; receiveCounter = 0
    }

    private func nonceFor(key: SymmetricKey, direction: String, counter: Data) -> Data {
        let digest = HMAC<SHA256>.authenticationCode(for:Data("nonce:\(direction)".utf8),using:key)
        return Data(digest.prefix(4)) + counter
    }
    private func aad(direction: String, counter: Data) -> Data { Data("SMBBLE1|\(direction)|".utf8) + counter }
}

private extension Data {
    init?(base64URL: String) {
        var value = base64URL.replacingOccurrences(of:"-",with:"+").replacingOccurrences(of:"_",with:"/")
        while value.count % 4 != 0 { value.append("=") }
        self.init(base64Encoded:value)
    }
    func base64URLEncodedString() -> String {
        base64EncodedString().replacingOccurrences(of:"+",with:"-").replacingOccurrences(of:"/",with:"_").replacingOccurrences(of:"=",with:"")
    }
}

private extension UInt64 {
    var bigEndianData: Data { var value = bigEndian; return withUnsafeBytes(of:&value) { Data($0) } }
    init(bigEndianBytes data: Data) { self = data.reduce(UInt64(0)) { ($0 << 8) | UInt64($1) } }
}
