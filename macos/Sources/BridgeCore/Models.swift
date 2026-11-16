import Foundation
import Security
import CryptoKit

private extension Data {
    func base64URLEncodedString() -> String {
        base64EncodedString().replacingOccurrences(of:"+",with:"-").replacingOccurrences(of:"/",with:"_").replacingOccurrences(of:"=",with:"")
    }
}

public enum BridgeError: Error, LocalizedError {
    case invalid(String), unauthorized
    public var errorDescription: String? { switch self { case .invalid(let s): return s; case .unauthorized: return "Нет доступа" } }
}
public enum BridgeValidation {
    public static let maxFile: Int64 = 1_073_741_824
    public static let maxText = 1_048_576
    public static func validName(_ name: String) -> Bool {
        let bad = CharacterSet.controlCharacters.union(CharacterSet(charactersIn: "/\\"))
        return !name.isEmpty && name != "." && name != ".." && name.utf8.count <= 180 && name.rangeOfCharacter(from: bad) == nil
    }
    public static func validSHA256(_ value: String) -> Bool { value.utf8.count == 64 && value.utf8.allSatisfy { (48...57).contains($0) || (97...102).contains($0) } }
    public static func constantTimeEquals(_ lhs: String, _ rhs: String) -> Bool {
        let a = Array(lhs.utf8), b = Array(rhs.utf8); var diff = UInt(a.count ^ b.count)
        for i in 0..<max(a.count, b.count) { diff |= UInt((i < a.count ? a[i] : 0) ^ (i < b.count ? b[i] : 0)) }
        return diff == 0
    }
    public static func randomToken() throws -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { throw BridgeError.invalid("Ошибка генерации ключа") }
        return Data(bytes).base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
    public static func hash(file: URL) throws -> (Int64, String) {
        let h = try FileHandle(forReadingFrom: file); defer { try? h.close() }
        var digest = SHA256(), total: Int64 = 0
        while true { let d = try h.read(upToCount: 65536) ?? Data(); if d.isEmpty { break }; total += Int64(d.count); guard total <= maxFile else { throw BridgeError.invalid("Файл больше 1 ГиБ") }; digest.update(data: d) }
        return (total, digest.finalize().map { String(format: "%02x", $0) }.joined())
    }
    public static func verifySHA256(_ actual: String, equals expected: String, deleting file: URL) throws {
        guard constantTimeEquals(actual, expected) else {
            try? FileManager.default.removeItem(at: file)
            throw BridgeError.invalid("Контрольная сумма не совпадает")
        }
    }
}
public struct ClipboardEvent: Codable, Equatable {
    public let eventId: String
    public let source: String
    public let type: String
    public let text: String

    public init(eventId: String = UUID().uuidString.lowercased(), source: String, type: String? = nil, text: String) throws {
        guard let uuid = UUID(uuidString:eventId), uuid.uuidString.lowercased() == eventId.lowercased() else { throw BridgeError.invalid("Некорректный ID clipboard-события") }
        guard ["mac", "android"].contains(source) else { throw BridgeError.invalid("Неизвестный источник clipboard-события") }
        guard !text.isEmpty, text.utf8.count <= BridgeValidation.maxText else { throw BridgeError.invalid("Текст пустой или больше 1 МиБ") }
        let inferred = ClipboardEvent.inferType(text)
        let validatedType = type ?? inferred
        guard ["text", "link"].contains(validatedType), validatedType == inferred else { throw BridgeError.invalid("Тип clipboard-события не соответствует содержимому") }
        self.eventId = eventId.lowercased(); self.source = source; self.type = validatedType; self.text = text
    }

    public var contentHash: String {
        SHA256.hash(data:Data(text.utf8)).map { String(format:"%02x", $0) }.joined()
    }

    private static func inferType(_ text: String) -> String {
        guard text.lowercased().hasPrefix("http://") || text.lowercased().hasPrefix("https://") else { return "text" }
        guard let components = URLComponents(string:text),
              let scheme = components.scheme?.lowercased(), ["http", "https"].contains(scheme),
              let host = components.host, !host.isEmpty,
              text.rangeOfCharacter(from:.whitespacesAndNewlines) == nil else { return "invalid-link" }
        return "link"
    }
}
public struct Transfer: Codable, Identifiable {
    public var id: String, direction: String, type: String, name: String, sha256: String, text: String, state: String
    public var size: Int64, progress: Int64, createdAt: Double
    public init(direction: String, type: String, name: String = "", size: Int64 = 0, sha256: String = "", text: String = "", state: String = "offered", id: String? = nil) {
        self.id = id ?? UUID().uuidString.lowercased(); self.direction = direction; self.type = type; self.name = name; self.size = size; self.sha256 = sha256; self.text = text; self.state = state; self.progress = 0; self.createdAt = Date().timeIntervalSince1970
    }
}

/// Stores each paired phone's clipboard history in a separate private file.
/// The pre-partition `clipboard-history.json` file is intentionally left untouched.
public final class PairClipboardHistoryStore {
    private let directory: URL
    public init(directory: URL) { self.directory = directory }
    private func url(for pairID: String) -> URL {
        directory.appendingPathComponent("clipboard-history-pair-\(pairID).json")
    }
    public func load(pairID: String?) -> [String] {
        guard let pairID, pairID.range(of:"^[a-f0-9]{64}$", options:.regularExpression) != nil,
              let data = try? Data(contentsOf:url(for:pairID)),
              let saved = try? JSONDecoder().decode([String].self, from:data) else { return [] }
        return Array(saved.prefix(30))
    }
    public func save(_ history: [String], pairID: String?) throws {
        guard let pairID, pairID.range(of:"^[a-f0-9]{64}$", options:.regularExpression) != nil else { return }
        try FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true)
        let data = try JSONEncoder().encode(Array(history.prefix(30)))
        let file = url(for:pairID)
        try data.write(to:file,options:.atomic)
        try FileManager.default.setAttributes([.posixPermissions:0o600],ofItemAtPath:file.path)
    }
    public func copyHistoryIfNeeded(from sourceID: String, to targetID: String) throws {
        guard sourceID != targetID,
              sourceID.range(of:"^[a-f0-9]{64}$", options:.regularExpression) != nil,
              targetID.range(of:"^[a-f0-9]{64}$", options:.regularExpression) != nil else { return }
        let destination = url(for:targetID)
        guard !FileManager.default.fileExists(atPath:destination.path) else { return }
        let oldHistory = load(pairID:sourceID)
        guard !oldHistory.isEmpty else { return }
        try save(oldHistory,pairID:targetID)
    }
}

public final class BridgeState {
    private let lock = NSRecursiveLock(), keychain: any BridgeCredentialStore
    private let defaults: UserDefaults
    private var token: String?, pairedClientID: String?, pendingClientID: String?, invitation = "", expires = Date.distantPast, pendingID = "", pendingName = "", pendingStatus = "", attempts = 0
    private var outgoing: [String:URL] = [:], destinations: [String:URL] = [:], data: [Transfer] = []
    private var clipboardSyncEnabled = false, latestMacClipboard: ClipboardEvent?, latestMacClipboardImage: ClipboardImageEvent?, latestMacClipboardImageAt: Date?
    private var recentClipboardIDs: [String:Date] = [:], recentClipboardHashes: [String:Date] = [:]
    private var recentImageIDs: [String:Date] = [:], recentImageHashes: [String:Date] = [:], pendingImageIDs = Set<String>()
    public var onChange: (() -> Void)?
    public var onClipboardReceived: ((ClipboardEvent) -> Void)?
    public var onClipboardImageReceived: ((ClipboardImageEvent) -> Bool)?
    public var onTransferCancelled: ((String) -> Void)?
    public var onLocalFileAvailable: ((String, URL) -> Void)?
    public let deviceID: String, name: String
    public init(keychain: any BridgeCredentialStore = BridgeKeychain(), name: String = Host.current().localizedName ?? "Mac", defaults: UserDefaults = .standard, loadCredentials: Bool = true) {
        self.keychain = keychain; self.name = name; self.defaults = defaults
        deviceID = defaults.string(forKey: "bridge.deviceID") ?? UUID().uuidString.lowercased(); defaults.set(deviceID, forKey: "bridge.deviceID")
        clipboardSyncEnabled = defaults.bool(forKey:"bridge.clipboardSyncEnabled")
        if loadCredentials {
            token = try? keychain.loadString("pair.token")
            pairedClientID = token == nil ? nil : defaults.string(forKey:"bridge.pair.clientID")
        }
        loadScopedHistory()
    }
    public func loadSavedCredentials() {
        let savedToken = try? keychain.loadString("pair.token")
        synced {
            token = savedToken ?? nil
            pairedClientID = token == nil ? nil : defaults.string(forKey:"bridge.pair.clientID")
            loadScopedHistory()
            changed()
        }
    }
    private func synced<T>(_ work: () throws -> T) rethrows -> T { lock.lock(); defer { lock.unlock() }; return try work() }
    private func changed() {
        let history = data.suffix(30).map { var t = $0; t.text = ""; return t }
        if let key = historyStorageKey, let encoded = try? JSONEncoder().encode(history) { defaults.set(encoded, forKey:key) }
        DispatchQueue.main.async { [weak self] in self?.onChange?() }
    }
    public var pairHistoryID: String? { synced {
        guard let token = token else { return nil }
        let identity = pairedClientID.map { "client:\($0)" } ?? "token:\(token)"
        return SHA256.hash(data: Data(identity.utf8)).map { String(format:"%02x", $0) }.joined()
    } }
    private var historyStorageKey: String? {
        if let id = pairHistoryID { return "bridge.history.pair." + id }
        return "bridge.history.unpaired." + deviceID
    }
    private func loadScopedHistory() {
        guard let key = historyStorageKey, let saved = defaults.data(forKey:key), let history = try? JSONDecoder().decode([Transfer].self, from:saved) else { data = []; return }
        data = history.map { var t = $0; t.text = ""; if t.type == "file" && t.state == "transferring" { t.state = "unknown" } else if !["complete", "failed", "cancelled", "rejected", "unknown"].contains(t.state) { t.state = "cancelled" }; return t }
    }
    public var clipboardHistoryMigration: (sourceID: String, targetID: String)? { synced {
        guard let source = defaults.string(forKey:"bridge.clipboardHistoryMigration.source"),
              let target = defaults.string(forKey:"bridge.clipboardHistoryMigration.target") else { return nil }
        return (source, target)
    } }
    public func completeClipboardHistoryMigration(sourceID: String, targetID: String) { synced {
        guard defaults.string(forKey:"bridge.clipboardHistoryMigration.source") == sourceID,
              defaults.string(forKey:"bridge.clipboardHistoryMigration.target") == targetID else { return }
        defaults.removeObject(forKey:"bridge.clipboardHistoryMigration.source")
        defaults.removeObject(forKey:"bridge.clipboardHistoryMigration.target")
    } }
    private static func normalizedClientID(_ value: String?) -> String? {
        guard let value, let uuid = UUID(uuidString:value) else { return nil }
        return uuid.uuidString.lowercased()
    }
    public var paired: Bool { synced { token != nil } }
    public var phoneModel: String? { synced { defaults.string(forKey:"bridge.phoneModel") } }
    public func recordPhoneModel(_ value: String, token candidate: String) throws { try synced {
        guard authorize(candidate), !value.isEmpty, value.utf8.count <= 64, value.unicodeScalars.allSatisfy({ CharacterSet(charactersIn:"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._-").contains($0) }) else { throw BridgeError.unauthorized }
        if defaults.string(forKey:"bridge.phoneModel") != value { defaults.set(value, forKey:"bridge.phoneModel"); changed() }
    } }
    public var isClipboardSyncEnabled: Bool { synced { clipboardSyncEnabled } }
    public var pairPrompt: String? { synced { pendingStatus == "pending" && Date() < expires ? pendingName : nil } }
    public var transfers: [Transfer] { synced { data } }
    public func reconciliationState(_ id: String) -> String { synced {
        guard let transfer = data.first(where: { $0.id == id && $0.type == "file" }) else { return "unknown" }
        switch transfer.state {
        case "complete", "failed", "cancelled", "rejected": return transfer.state
        case "offered", "accepted", "transferring": return "pending"
        default: return "unknown"
        }
    } }
    public func setClipboardSyncEnabled(_ enabled: Bool) {
        synced {
            clipboardSyncEnabled = enabled; defaults.set(enabled, forKey:"bridge.clipboardSyncEnabled")
            if !enabled {
                latestMacClipboard = nil; latestMacClipboardImage = nil; latestMacClipboardImageAt = nil
                recentClipboardIDs.removeAll(); recentClipboardHashes.removeAll()
                recentImageIDs.removeAll(); recentImageHashes.removeAll(); pendingImageIDs.removeAll()
            }
        }
    }
    @discardableResult public func publishMacClipboard(_ event: ClipboardEvent) throws -> Bool { try synced {
        guard clipboardSyncEnabled, token != nil else { throw BridgeError.invalid("Общий буфер выключен или телефон не сопряжён") }
        guard event.source == "mac" else { throw BridgeError.invalid("Ожидалось событие с Mac") }
        pruneClipboardDeduplication()
        guard recentClipboardIDs[event.eventId] == nil, recentClipboardHashes[event.contentHash] == nil else { return false }
        recentImageHashes.removeAll()
        recentClipboardIDs[event.eventId] = Date(); recentClipboardHashes = [event.contentHash: Date()]
        latestMacClipboard = event; latestMacClipboardImage = nil; latestMacClipboardImageAt = nil
        return true
    } }
    public func nextMacClipboard(after eventID: String?) -> ClipboardEvent? { synced {
        guard clipboardSyncEnabled, token != nil, let event = latestMacClipboard, event.eventId != eventID else { return nil }
        return event
    } }
    @discardableResult public func publishMacClipboardImage(_ event: ClipboardImageEvent) throws -> Bool { try synced {
        guard clipboardSyncEnabled, token != nil else { throw BridgeError.invalid("Общий буфер выключен или телефон не сопряжён") }
        guard event.source == "mac" else { throw BridgeError.invalid("Ожидалось изображение с Mac") }
        pruneImageDeduplication()
        guard recentImageIDs[event.eventId] == nil, recentImageHashes[event.image.sha256] == nil else { return false }
        recentClipboardHashes.removeAll()
        recentImageIDs[event.eventId] = Date(); recentImageHashes = [event.image.sha256: Date()]
        latestMacClipboard = nil; latestMacClipboardImage = event; latestMacClipboardImageAt = Date()
        return true
    } }
    public func nextMacClipboardImage(after eventID: String?) -> ClipboardImageEvent? { synced {
        guard clipboardSyncEnabled, token != nil, let event = latestMacClipboardImage, let createdAt = latestMacClipboardImageAt else { return nil }
        guard Date().timeIntervalSince(createdAt) <= 24 * 60 * 60 else {
            latestMacClipboardImage = nil; latestMacClipboardImageAt = nil
            return nil
        }
        guard event.eventId != eventID else { return nil }
        return event
    } }
    @discardableResult public func acceptAndroidClipboardImage(_ event: ClipboardImageEvent) throws -> Bool {
        let result = try synced { () throws -> (Bool, ((ClipboardImageEvent) -> Bool)?) in
            guard clipboardSyncEnabled, token != nil else { throw BridgeError.invalid("Общий буфер выключен или телефон не сопряжён") }
            guard event.source == "android" else { throw BridgeError.invalid("Ожидалось изображение с Android") }
            pruneImageDeduplication()
            if recentImageIDs[event.eventId] != nil || recentImageHashes[event.image.sha256] != nil || pendingImageIDs.contains(event.eventId) { return (false, nil) }
            guard let callback = onClipboardImageReceived else { throw BridgeError.invalid("Общий буфер изображений недоступен на Mac") }
            recentClipboardHashes.removeAll()
            latestMacClipboard = nil; latestMacClipboardImage = nil; latestMacClipboardImageAt = nil
            pendingImageIDs.insert(event.eventId)
            return (true, callback)
        }
        guard result.0, let callback = result.1 else { return false }
        let applied = Thread.isMainThread ? callback(event) : DispatchQueue.main.sync { callback(event) }
        return synced {
            pendingImageIDs.remove(event.eventId)
            guard applied, clipboardSyncEnabled, token != nil else { return false }
            recentImageIDs[event.eventId] = Date(); recentImageHashes = [event.image.sha256: Date()]
            return true
        }
    }
    private func pruneImageDeduplication() {
        let cutoff = Date().addingTimeInterval(-24 * 60 * 60)
        recentImageIDs = recentImageIDs.filter { $0.value >= cutoff }
        recentImageHashes = recentImageHashes.filter { $0.value >= cutoff }
        if recentImageIDs.count > 32, let oldest = recentImageIDs.min(by: { $0.value < $1.value }) { recentImageIDs.removeValue(forKey: oldest.key) }
        if recentImageHashes.count > 32, let oldest = recentImageHashes.min(by: { $0.value < $1.value }) { recentImageHashes.removeValue(forKey: oldest.key) }
    }
    @discardableResult public func acceptAndroidClipboard(_ event: ClipboardEvent) throws -> Bool {
        let result = try synced { () -> (Bool, ((ClipboardEvent) -> Void)?) in
            guard clipboardSyncEnabled, token != nil else { throw BridgeError.invalid("Общий буфер выключен или телефон не сопряжён") }
            guard event.source == "android" else { throw BridgeError.invalid("Ожидалось событие с Android") }
            pruneClipboardDeduplication()
            if recentClipboardIDs[event.eventId] != nil || recentClipboardHashes[event.contentHash] != nil { return (false, nil) }
            guard let callback = onClipboardReceived else { throw BridgeError.invalid("Общий буфер недоступен в приложении Mac") }
            recentImageHashes.removeAll()
            latestMacClipboard = nil; latestMacClipboardImage = nil; latestMacClipboardImageAt = nil
            recentClipboardIDs[event.eventId] = Date(); recentClipboardHashes = [event.contentHash: Date()]
            return (true, callback)
        }
        guard result.0, let callback = result.1 else { return false }
        if Thread.isMainThread { callback(event) } else { DispatchQueue.main.async { callback(event) } }
        return true
    }
    private func pruneClipboardDeduplication() {
        let cutoff = Date().addingTimeInterval(-24 * 60 * 60)
        recentClipboardIDs = recentClipboardIDs.filter { $0.value >= cutoff }
        recentClipboardHashes = recentClipboardHashes.filter { $0.value >= cutoff }
        if recentClipboardIDs.count > 128, let oldest = recentClipboardIDs.min(by:{$0.value < $1.value}) { recentClipboardIDs.removeValue(forKey:oldest.key) }
        if recentClipboardHashes.count > 128, let oldest = recentClipboardHashes.min(by:{$0.value < $1.value}) { recentClipboardHashes.removeValue(forKey:oldest.key) }
    }
    public func startInvitation() throws -> String { try synced {
        guard token == nil else { throw BridgeError.invalid("Сначала удалите существующую пару") }
        invitation = try BridgeValidation.randomToken(); expires = Date().addingTimeInterval(120); pendingID = ""; pendingStatus = ""; attempts = 0; changed(); return invitation
    } }
    public func requestPair(secret: String, name: String, clientID: String? = nil) throws -> String { try synced {
        attempts += 1
        guard attempts <= 6, token == nil, Date() < expires, pendingID.isEmpty, BridgeValidation.constantTimeEquals(secret, invitation), !name.isEmpty, name.utf8.count <= 80 else { throw BridgeError.unauthorized }
        guard clientID == nil || Self.normalizedClientID(clientID) != nil else { throw BridgeError.invalid("Некорректный ID телефона") }
        pendingID = UUID().uuidString.lowercased(); pendingClientID = Self.normalizedClientID(clientID); pendingName = name; pendingStatus = "pending"; changed(); return pendingID
    } }
    public func approvePair(_ approve: Bool) throws { try synced {
        guard pendingStatus == "pending", Date() < expires else { throw BridgeError.invalid("Запрос устарел") }
        if approve {
            let t = try BridgeValidation.randomToken()
            try keychain.saveString(t, for: "pair.token")
            token = t
            pairedClientID = pendingClientID
            if let pairedClientID { defaults.set(pairedClientID, forKey:"bridge.pair.clientID") }
            else { defaults.removeObject(forKey:"bridge.pair.clientID") }
            loadScopedHistory()
            pendingStatus = "approved"
        } else { pendingStatus = "rejected" }
        changed()
    } }
    public func pairStatus(secret: String, id: String) throws -> [String:Any] { try synced {
        guard Date() < expires, !pendingID.isEmpty, BridgeValidation.constantTimeEquals(secret, invitation), BridgeValidation.constantTimeEquals(id, pendingID) else { throw BridgeError.unauthorized }
        if pendingStatus == "approved", let t = token {
            var answer: [String:Any] = ["status":"approved", "token":t, "deviceId":deviceID, "name":name]
            if let pairedClientID { answer["clientId"] = pairedClientID }
            invitation = ""; pendingID = ""; pendingClientID = nil; pendingStatus = ""; changed(); return answer
        }
        return ["status":pendingStatus]
    } }
    public func authorize(_ candidate: String) -> Bool { synced { guard let t = token else { return false }; return BridgeValidation.constantTimeEquals(candidate, t) } }
    public func recordPhoneClientID(_ value: String, token candidate: String) throws { try synced {
        guard authorize(candidate), let clientID = Self.normalizedClientID(value), clientID == value.lowercased() else { throw BridgeError.unauthorized }
        if let pairedClientID {
            guard pairedClientID == clientID else { throw BridgeError.unauthorized }
            return
        }
        let oldHistoryID = pairHistoryID
        pairedClientID = clientID
        defaults.set(clientID, forKey:"bridge.pair.clientID")
        if let oldHistoryID, let newHistoryID = pairHistoryID, oldHistoryID != newHistoryID {
            defaults.set(oldHistoryID, forKey:"bridge.clipboardHistoryMigration.source")
            defaults.set(newHistoryID, forKey:"bridge.clipboardHistoryMigration.target")
        }
        changed()
    } }
    public var pairedPhoneClientID: String? { synced { pairedClientID } }
    public func bluetoothChallenge(macDeviceID: String, androidNonce: Data, macNonce: Data) -> Data? { synced {
        guard let token = token, macDeviceID == deviceID, androidNonce.count == 32, macNonce.count == 32 else { return nil }
        let key = SymmetricKey(data:Data(token.utf8))
        let message = Self.bluetoothProofMessage("SMB-BLE-CHALLENGE-1", macDeviceID:macDeviceID, androidNonce:androidNonce, macNonce:macNonce)
        return Data(HMAC<SHA256>.authenticationCode(for:message, using:key))
    } }
    public func bluetoothSessionKey(macDeviceID: String, androidNonce: Data, macNonce: Data, proof: Data) -> Data? { synced {
        guard let token = token, macDeviceID == deviceID, androidNonce.count == 32, macNonce.count == 32, proof.count == 32 else { return nil }
        let secret = SymmetricKey(data:Data(token.utf8))
        let message = Self.bluetoothProofMessage("SMB-BLE-PROOF-1", macDeviceID:macDeviceID, androidNonce:androidNonce, macNonce:macNonce)
        guard HMAC<SHA256>.isValidAuthenticationCode(proof, authenticating:message, using:secret) else { return nil }
        let salt = androidNonce + macNonce
        let info = Data("SamsungMacBridge BLE v1|\(macDeviceID)".utf8)
        let derived = HKDF<SHA256>.deriveKey(inputKeyMaterial:secret, salt:salt, info:info, outputByteCount:32)
        return derived.withUnsafeBytes { Data($0) }
    } }
    private static func bluetoothProofMessage(_ label: String, macDeviceID: String, androidNonce: Data, macNonce: Data) -> Data {
        let a = androidNonce.base64URLEncodedString(), m = macNonce.base64URLEncodedString()
        return Data("\(label)|\(macDeviceID)|\(a)|\(m)".utf8)
    }
    public func unpair() {
        synced { for i in data.indices where !["complete", "failed", "cancelled", "rejected"].contains(data[i].state) { data[i].state = "cancelled" }; changed(); token = nil; pairedClientID = nil; pendingClientID = nil; data = []; try? keychain.delete("pair.token"); defaults.removeObject(forKey:"bridge.pair.clientID"); defaults.removeObject(forKey:"bridge.phoneModel"); invitation = ""; pendingID = ""; pendingStatus = ""; latestMacClipboard = nil; latestMacClipboardImage = nil; latestMacClipboardImageAt = nil; recentClipboardIDs.removeAll(); recentClipboardHashes.removeAll(); recentImageIDs.removeAll(); recentImageHashes.removeAll(); pendingImageIDs.removeAll(); outgoing.removeAll(); destinations.removeAll(); changed() }
    }
    @discardableResult public func addText(direction: String, type: String, text: String) throws -> String { try synced {
        guard ["text", "link"].contains(type), !text.isEmpty, text.utf8.count <= BridgeValidation.maxText else { throw BridgeError.invalid("Текст пустой или слишком длинный") }
        if type == "link" { guard let u = URL(string: text), ["http", "https"].contains(u.scheme?.lowercased() ?? ""), u.host != nil else { throw BridgeError.invalid("Поддерживаются ссылки http и https") } }
        let t = Transfer(direction: direction, type: type, size: Int64(text.utf8.count), text: text, state: direction == "toMac" ? "complete" : "offered"); data.append(t); trim(); changed(); return t.id
    } }
    @discardableResult public func offerFile(direction: String, name: String, size: Int64, sha256: String, file: URL? = nil, transferID: String? = nil) throws -> String { try synced {
        guard BridgeValidation.validName(name), size >= 0, size <= BridgeValidation.maxFile, BridgeValidation.validSHA256(sha256) else { throw BridgeError.invalid("Некорректный файл") }
        guard transferID == nil || (UUID(uuidString:transferID!) != nil && !data.contains(where: { $0.id == transferID! })) else { throw BridgeError.invalid("Некорректный ID передачи") }
        guard !data.contains(where: { $0.type == "file" && ["offered", "accepted", "transferring"].contains($0.state) }) else { throw BridgeError.invalid("Другая передача уже выполняется") }
        let t = Transfer(direction: direction, type: "file", name: name, size: size, sha256: sha256, id:transferID); data.append(t); if let f = file { outgoing[t.id] = f; DispatchQueue.main.async { [weak self] in self?.onLocalFileAvailable?(t.id, f) } }; trim(); changed(); return t.id
    } }
    private func trim() { if data.count > 30 { data.removeFirst(data.count - 30) } }
    public func clearHistory() { synced { data.removeAll { ["complete", "failed", "cancelled", "rejected"].contains($0.state) }; changed() } }
    public func accept(_ id: String, destination: URL? = nil) throws { try synced {
        guard let i = data.firstIndex(where: { $0.id == id }), data[i].state == "offered", data[i].type == "file" else { throw BridgeError.invalid("Передача недоступна") }
        if data[i].direction == "toMac" { guard let d = destination else { throw BridgeError.invalid("Выберите папку") }; destinations[id] = d }
        data[i].state = "accepted"; changed()
    } }
    public func cancel(_ id: String) throws { try synced {
        if let i = data.firstIndex(where: { $0.id == id }), data[i].state == "cancelled" { return }
        guard let i = data.firstIndex(where: { $0.id == id }), !["complete", "cancelled", "failed", "rejected"].contains(data[i].state) else { throw BridgeError.invalid("Передача недоступна") }
        data[i].state = "cancelled"; outgoing.removeValue(forKey:id); destinations.removeValue(forKey:id); changed()
    }; onTransferCancelled?(id) }
    public func beginFile(_ id: String, direction: String, length: Int64? = nil) throws -> (Transfer, URL) { try synced {
        guard let i = data.firstIndex(where: { $0.id == id }), data[i].direction == direction, data[i].state == "accepted", data[i].type == "file", length == nil || data[i].size == length else { throw BridgeError.invalid("Передача не подтверждена") }
        guard let url = (direction == "toMac" ? destinations[id] : outgoing[id]) else { throw BridgeError.invalid("Файл или папка недоступны") }
        data[i].state = "transferring"; changed(); return (data[i],url)
    } }
    public func updateProgress(_ id: String, _ count: Int64) throws { try synced {
        guard let i = data.firstIndex(where: { $0.id == id }), data[i].state == "transferring", token != nil else { throw BridgeError.unauthorized }
        data[i].progress = count; DispatchQueue.main.async { [weak self] in self?.onChange?() }
    } }
    private func publishUpload(_ id: String, temp: URL) throws -> URL {
        guard let i = data.firstIndex(where: { $0.id == id }), data[i].state == "transferring", let destination = destinations[id] else { throw BridgeError.unauthorized }
        let original = data[i].name, stem = (original as NSString).deletingPathExtension, ext = (original as NSString).pathExtension; var final: URL?
        for n in 0...9999 {
            let name = n == 0 ? original : stem + " (\(n))" + (ext.isEmpty ? "" : "." + ext), target = destination.appendingPathComponent(name)
            do { try FileManager.default.moveItem(at: temp, to: target); final = target; break } catch { if FileManager.default.fileExists(atPath: target.path) { continue }; throw error }
        }
        guard let target = final else { throw BridgeError.invalid("Не удалось выбрать свободное имя") }
        data[i].state = "complete"; data[i].progress = data[i].size; destinations.removeValue(forKey:id); changed(); DispatchQueue.main.async { [weak self] in self?.onLocalFileAvailable?(id, target) }; return target
    }
    public func finishUpload(_ id: String, token candidate: String, temp: URL) throws -> URL { try synced {
        guard authorize(candidate) else { throw BridgeError.unauthorized }
        return try publishUpload(id, temp:temp)
    } }
    public func finishTicketedUpload(_ id: String, temp: URL) throws -> URL { try synced { try publishUpload(id, temp:temp) } }
    public func markFailed(_ id: String) { synced { if let i = data.firstIndex(where: { $0.id == id }), ["offered", "accepted", "transferring"].contains(data[i].state) { data[i].state = "failed"; changed() } } }
    public func acknowledge(_ id: String, sha256: String?) throws { try synced {
        if let i = data.firstIndex(where: { $0.id == id }), data[i].direction == "toPhone", data[i].state == "complete" {
            if data[i].type == "file" { guard let hash = sha256, BridgeValidation.constantTimeEquals(hash, data[i].sha256) else { throw BridgeError.invalid("Контрольная сумма не совпадает") } }
            return
        }
        guard let i = data.firstIndex(where: { $0.id == id }), data[i].direction == "toPhone", ["offered", "transferring", "unknown"].contains(data[i].state) else { throw BridgeError.invalid("Передача недоступна") }
        if data[i].type == "file" { guard ["transferring", "unknown"].contains(data[i].state), let hash = sha256, BridgeValidation.constantTimeEquals(hash, data[i].sha256) else { throw BridgeError.invalid("Контрольная сумма не совпадает") } }
        data[i].state = "complete"; data[i].progress = data[i].size; outgoing.removeValue(forKey:id); changed()
    } }
}
