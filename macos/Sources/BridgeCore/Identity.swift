import Foundation
import Security
import CryptoKit

public protocol BridgeCredentialStore: AnyObject {
    func loadString(_ name: String) throws -> String?
    func saveString(_ value: String, for name: String) throws
    func delete(_ name: String) throws
}

public final class BridgeKeychain {
    private let service: String
    public init(service: String = "dev.seferaki.SamsungMacBridge") { self.service = service }
    public func load(_ name: String) throws -> Data? {
        let query: [String:Any] = [kSecClass as String:kSecClassGenericPassword, kSecAttrService as String:service, kSecAttrAccount as String:name, kSecReturnData as String:true, kSecMatchLimit as String:kSecMatchLimitOne]
        var found: CFTypeRef?; let status = SecItemCopyMatching(query as CFDictionary, &found)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let value = found as? Data else { throw BridgeError.invalid("Ошибка связки ключей: \(status)") }; return value
    }
    public func save(_ value: Data, for name: String) throws {
        let query: [String:Any] = [kSecClass as String:kSecClassGenericPassword, kSecAttrService as String:service, kSecAttrAccount as String:name]
        let updated = SecItemUpdate(query as CFDictionary, [kSecValueData as String:value] as CFDictionary)
        if updated == errSecItemNotFound {
            var entry = query; entry[kSecValueData as String] = value; entry[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            let status = SecItemAdd(entry as CFDictionary, nil); guard status == errSecSuccess else { throw BridgeError.invalid("Не удалось сохранить ключ: \(status)") }
        } else if updated != errSecSuccess { throw BridgeError.invalid("Не удалось изменить ключ: \(updated)") }
    }
    public func delete(_ name: String) throws {
        let query: [String:Any] = [kSecClass as String:kSecClassGenericPassword, kSecAttrService as String:service, kSecAttrAccount as String:name]
        let status = SecItemDelete(query as CFDictionary); guard status == errSecSuccess || status == errSecItemNotFound else { throw BridgeError.invalid("Ошибка удаления ключа") }
    }
    public func saveString(_ value: String, for name: String) throws { try save(Data(value.utf8), for:name) }
    public func loadString(_ name: String) throws -> String? { guard let d = try load(name) else { return nil }; return String(data:d, encoding:.utf8) }
}
extension BridgeKeychain: BridgeCredentialStore {}

public struct BridgeIdentity {
    public let identity: SecIdentity
    public let fingerprint: String
    public static func loadOrCreate(keychain: BridgeKeychain, deviceID: String) throws -> BridgeIdentity {
        let cn = "SamsungMacBridge-" + deviceID
        var p12 = try keychain.load("identity.p12"), password = try keychain.loadString("identity.password")
        if p12 == nil || password == nil {
            let generated = try generate(commonName:cn)
            try keychain.save(generated.0, for:"identity.p12"); try keychain.saveString(generated.1, for:"identity.password")
            p12 = generated.0; password = generated.1
        }
        guard let blob = p12, let pass = password else { throw BridgeError.invalid("Нет сертификата") }
        var identity: SecIdentity?
        if #unavailable(macOS 15) {
            let query: [String:Any] = [kSecClass as String:kSecClassIdentity, kSecReturnRef as String:true, kSecMatchLimit as String:kSecMatchLimitAll]
            var result: CFTypeRef?
            if SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let items = result as? [SecIdentity] {
                for item in items { var cert: SecCertificate?; if SecIdentityCopyCertificate(item, &cert) == errSecSuccess, let cert = cert, SecCertificateCopySubjectSummary(cert) as String? == cn { identity = item; break } }
            }
        }
        if identity == nil {
            var options: [String:Any] = [kSecImportExportPassphrase as String:pass]
            if #available(macOS 15, *) { options[kSecImportToMemoryOnly as String] = true }
            var items: CFArray?; let status = SecPKCS12Import(blob as CFData, options as CFDictionary, &items)
            guard status == errSecSuccess, let values = items as? [[String:Any]], let first = values.first, let item = first[kSecImportItemIdentity as String] else { throw BridgeError.invalid("Ошибка импорта сертификата: \(status)") }
            identity = (item as! SecIdentity)
        }
        guard let key = identity else { throw BridgeError.invalid("Нет ключа сертификата") }
        var cert: SecCertificate?; guard SecIdentityCopyCertificate(key, &cert) == errSecSuccess, let cert = cert else { throw BridgeError.invalid("Нет сертификата") }
        let der = SecCertificateCopyData(cert) as Data
        let fp = SHA256.hash(data:der).map { String(format:"%02x", $0) }.joined()
        return BridgeIdentity(identity:key, fingerprint:fp)
    }
    private static func generate(commonName: String) throws -> (Data, String) {
        let path = FileManager.default.temporaryDirectory.appendingPathComponent("bridge-" + UUID().uuidString, isDirectory:true)
        try FileManager.default.createDirectory(at:path, withIntermediateDirectories:false, attributes:[.posixPermissions:0o700])
        defer { try? FileManager.default.removeItem(at:path) }
        let password = try BridgeValidation.randomToken()
        func run(_ args: [String]) throws {
            let p = Process(); p.executableURL = URL(fileURLWithPath:"/usr/bin/openssl"); p.arguments = args
            var env = ProcessInfo.processInfo.environment; env["BRIDGE_P12_PASSWORD"] = password; p.environment = env
            let err = Pipe(); p.standardError = err; p.standardOutput = Pipe(); try p.run(); p.waitUntilExit()
            guard p.terminationStatus == 0 else { throw BridgeError.invalid("Не удалось создать сертификат") }
        }
        let key = path.appendingPathComponent("key.pem"), cert = path.appendingPathComponent("cert.pem"), p12 = path.appendingPathComponent("id.p12")
        try run(["req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout",key.path, "-out",cert.path, "-days","3650", "-subj","/CN=\(commonName)"])
        try run(["pkcs12", "-export", "-inkey",key.path, "-in",cert.path, "-out",p12.path, "-passout","env:BRIDGE_P12_PASSWORD", "-name",commonName])
        return (try Data(contentsOf:p12),password)
    }
}
