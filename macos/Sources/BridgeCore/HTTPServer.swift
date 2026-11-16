import Foundation
import Network
import Security
import CryptoKit

public struct HTTPRequest {
    public let method: String, path: String, headers: [String:String], length: Int64
    public static func parse(_ raw: String) throws -> HTTPRequest {
        guard raw.utf8.count <= 16384, raw.hasSuffix("\r\n\r\n") else { throw BridgeError.invalid("Некорректные заголовки") }
        let lines = raw.components(separatedBy:"\r\n"); let parts = lines[0].split(separator:" ", omittingEmptySubsequences:false)
        guard parts.count == 3, ["GET","POST"].contains(String(parts[0])), parts[2] == "HTTP/1.1", parts[1].first == "/", !parts[1].contains("?"), !parts[1].contains("%"), !parts[1].contains("..") else { throw BridgeError.invalid("Некорректный запрос") }
        var headers: [String:String] = [:]
        for line in lines.dropFirst() where !line.isEmpty {
            guard let idx = line.firstIndex(of:":") else { throw BridgeError.invalid("Некорректный заголовок") }
            let name = line[..<idx].lowercased(), value = line[line.index(after:idx)...].trimmingCharacters(in:.whitespaces)
            guard !name.isEmpty, headers[name] == nil, name != "transfer-encoding", !value.contains("\n") else { throw BridgeError.invalid("Повторяющийся или недопустимый заголовок") }
            headers[name] = value
        }
        let value = headers["content-length"] ?? (parts[0] == "GET" ? "0" : "")
        guard !value.isEmpty, value.utf8.allSatisfy({ (48...57).contains($0) }), let length = Int64(value), length >= 0, length <= BridgeValidation.maxFile, !(parts[0] == "GET" && length != 0) else { throw BridgeError.invalid("Недопустимый размер") }
        return HTTPRequest(method:String(parts[0]), path:String(parts[1]), headers:headers, length:length)
    }
}
private final class Peer {
    let connection: NWConnection
    var buffered = Data()
    init(_ c: NWConnection) { connection = c }
    func receive(max: Int = 65536) async throws -> Data {
        try await withCheckedThrowingContinuation { continuation in
            let timeout = DispatchWorkItem { [connection] in connection.cancel() }
            DispatchQueue.global(qos:.utility).asyncAfter(deadline:.now() + 30, execute:timeout)
            connection.receive(minimumIncompleteLength:1, maximumLength:max) { data, _, done, error in
                timeout.cancel()
                if let d = data, !d.isEmpty { continuation.resume(returning:d) }
                else if let e = error { continuation.resume(throwing:e) }
                else if done { continuation.resume(throwing:BridgeError.invalid("Соединение прервано")) }
                else { continuation.resume(throwing:BridgeError.invalid("Пустой сетевой ответ")) }
            }
        }
    }
    func header() async throws -> HTTPRequest {
        let delimiter = Data([13,10,13,10])
        while buffered.range(of:delimiter) == nil {
            guard buffered.count < 16384 else { throw BridgeError.invalid("Заголовок слишком велик") }
            buffered.append(try await receive(max:16384-buffered.count))
        }
        guard let range = buffered.range(of:delimiter), let text = String(data:buffered[..<range.upperBound], encoding:.utf8) else { throw BridgeError.invalid("Некорректный заголовок") }
        buffered.removeSubrange(..<range.upperBound); return try HTTPRequest.parse(text)
    }
    func chunk(_ max: Int) async throws -> Data {
        if !buffered.isEmpty { let n = min(max,buffered.count); let d = Data(buffered.prefix(n)); buffered.removeFirst(n); return d }
        return try await receive(max:max)
    }
    func body(_ count: Int64, maxBytes: Int64 = 2_097_152) async throws -> Data {
        guard count <= maxBytes else { throw BridgeError.invalid("Запрос слишком большой") }
        var result = Data(); while result.count < count { result.append(try await chunk(Int(count)-result.count)) }; return result
    }
    func send(_ data: Data) async throws {
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void,Error>) in connection.send(content:data, completion:.contentProcessed { error in if let e = error { c.resume(throwing:e) } else { c.resume() } }) }
    }
    func respond(status: Int, body: Data, contentType: String = "application/json", headers: [String:String] = [:]) async throws {
        let message = status == 200 ? "OK" : status == 201 ? "Created" : status == 202 ? "Accepted" : status == 204 ? "No Content" : status == 401 ? "Unauthorized" : "Bad Request"
        let extra = headers.map { "\($0.key): \($0.value)\r\n" }.joined()
        let content = status == 204 ? "" : "Content-Type: \(contentType)\r\n"
        let head = "HTTP/1.1 \(status) \(message)\r\n\(content)Content-Length: \(status == 204 ? 0 : body.count)\r\n\(extra)Connection: close\r\n\r\n"
        try await send(Data(head.utf8)); if !body.isEmpty { try await send(body) }
    }
    func respondJSON(status: Int = 200, _ object: [String:Any]) async throws { try await respond(status:status, body:JSONSerialization.data(withJSONObject:object)) }
}
public final class BridgeServer {
    public let state: BridgeState, identity: BridgeIdentity
    private let listener: NWListener
    private let queue = DispatchQueue(label:"bridge.network", qos:.userInitiated)
    private let transfersLock = NSLock()
    private var activeTransfers: [String:NWConnection] = [:]
    public var onReady: ((UInt16) -> Void)?
    public var onError: ((String) -> Void)?
    public var onAuthenticatedRequest: (() -> Void)?
    public init(state: BridgeState, identity: BridgeIdentity, port: UInt16 = 0) throws {
        self.state = state; self.identity = identity
        let tls = NWProtocolTLS.Options(); sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv12)
        guard let local = sec_identity_create(identity.identity) else { throw BridgeError.invalid("Нет TLS-ключа") }
        sec_protocol_options_set_local_identity(tls.securityProtocolOptions, local)
        listener = try NWListener(using:NWParameters(tls:tls, tcp:NWProtocolTCP.Options()), on:port == 0 ? .any : NWEndpoint.Port(rawValue:port)!)
        listener.service = NWListener.Service(name:state.name, type:"_smbbridge._tcp")
        state.onTransferCancelled = { [weak self] id in
            self?.cancelActiveTransfer(id)
        }
    }
    private func registerTransfer(_ id: String, connection: NWConnection) {
        transfersLock.lock(); activeTransfers[id] = connection; transfersLock.unlock()
    }
    private func unregisterTransfer(_ id: String, connection: NWConnection) {
        transfersLock.lock(); if activeTransfers[id] === connection { activeTransfers.removeValue(forKey:id) }; transfersLock.unlock()
    }
    private func cancelActiveTransfer(_ id: String) {
        transfersLock.lock(); let connection = activeTransfers[id]; transfersLock.unlock()
        connection?.cancel()
    }
    public func start() {
        listener.stateUpdateHandler = { [weak self] s in
            guard let self = self else { return }
            switch s { case .ready: if let p = self.listener.port?.rawValue { DispatchQueue.main.async { self.onReady?(p) } }; case .failed(let e): DispatchQueue.main.async { self.onError?(e.localizedDescription) }; default: break }
        }
        listener.newConnectionHandler = { [weak self] connection in
            guard let self = self else { connection.cancel(); return }; connection.start(queue:self.queue); Task { await self.serve(connection) }
        }
        listener.start(queue:queue)
    }
    public func stop() { listener.cancel() }
    private func serve(_ connection: NWConnection) async {
        let peer = Peer(connection); defer { connection.cancel() }
        let deadline = DispatchWorkItem { connection.cancel() }; queue.asyncAfter(deadline:.now()+900, execute:deadline); defer { deadline.cancel() }
        do { let req = try await peer.header(); try await handle(req, peer) }
        catch { let code = (error as? BridgeError).map { if case .unauthorized = $0 { return 401 }; return 400 } ?? 400; try? await peer.respondJSON(status:code,["error":error.localizedDescription]) }
    }
    private func json(_ peer: Peer, _ req: HTTPRequest, maxBytes: Int64 = 2_097_152) async throws -> [String:Any] {
        let body = try await peer.body(req.length, maxBytes:maxBytes); guard let object = try JSONSerialization.jsonObject(with:body) as? [String:Any] else { throw BridgeError.invalid("Некорректный JSON") }; return object
    }
    private func text(_ obj: [String:Any], _ name: String) throws -> String { guard let s = obj[name] as? String else { throw BridgeError.invalid("Не заполнено поле \(name)") }; return s }
    private func handle(_ req: HTTPRequest, _ peer: Peer) async throws {
        if req.method == "POST" && req.path == "/v1/pair" {
            let o = try await json(peer,req); let id = try state.requestPair(secret:try text(o,"invitation"), name:try text(o,"name"), clientID:o["clientId"] as? String); try await peer.respondJSON(status:202,["requestId":id]); return
        }
        if req.method == "POST" && req.path == "/v1/pair/status" {
            let o = try await json(peer,req); let result = try state.pairStatus(secret:try text(o,"invitation"), id:try text(o,"requestId")); try await peer.respondJSON(result); return
        }
        let authorization = req.headers["authorization"] ?? ""; guard authorization.hasPrefix("Bearer ") else { throw BridgeError.unauthorized }
        let token = String(authorization.dropFirst(7)); guard state.authorize(token) else { throw BridgeError.unauthorized }
        DispatchQueue.main.async { [weak self] in self?.onAuthenticatedRequest?() }
        if req.method == "GET" && req.path == "/v1/capabilities" {
            try await peer.respondJSON(["clipboardImagesVersion":1,"maxClipboardImageBytes":ClipboardImage.maxBytes,"maxClipboardImagePixels":ClipboardImage.maxPixels]); return
        }
        if req.method == "GET" && req.path == "/v1/status" {
            if let phoneModel = req.headers["x-bridge-phone-model"] { try? state.recordPhoneModel(phoneModel, token:token) }
            if let clientID = req.headers["x-bridge-client-id"] { try? state.recordPhoneClientID(clientID, token:token) }
            let serialized = try JSONEncoder().encode(state.transfers), transfers = try JSONSerialization.jsonObject(with:serialized)
            var response: [String:Any] = ["name":state.name,"deviceId":state.deviceID,"transfers":transfers]
            if let clientID = state.pairedPhoneClientID { response["clientId"] = clientID }
            try await peer.respondJSON(response); return
        }
        if req.method == "POST" && req.path == "/v1/text" {
            let o = try await json(peer,req); guard state.authorize(token) else { throw BridgeError.unauthorized }
            let id = try state.addText(direction:"toMac", type:try text(o,"type"), text:try text(o,"text")); try await peer.respondJSON(status:201,["id":id]); return
        }
        if req.method == "POST" && req.path == "/v1/clipboard" {
            let o = try await json(peer,req,maxBytes:Int64(BridgeValidation.maxText * 6 + 4096)); guard state.authorize(token) else { throw BridgeError.unauthorized }
            let event = try ClipboardEvent(eventId:try text(o,"eventId"), source:try text(o,"source"), type:try text(o,"type"), text:try text(o,"text"))
            let accepted = try state.acceptAndroidClipboard(event)
            try await peer.respondJSON(status:201,["eventId":event.eventId,"state":accepted ? "accepted" : "duplicate"]); return
        }
        if req.method == "POST" && req.path == "/v1/clipboard/image" {
            guard req.length > 0, req.length <= ClipboardImage.maxBytes else { throw BridgeError.invalid("Изображение пустое или больше 8 МиБ") }
            let mimeType = req.headers["content-type"] ?? ""
            let eventId = req.headers["x-bridge-event-id"] ?? ""
            let expectedHash = req.headers["x-bridge-sha256"]
            let source = req.headers["x-bridge-source"] ?? ""
            let bytes = try await peer.body(req.length, maxBytes:Int64(ClipboardImage.maxBytes))
            let image = try ClipboardImage(data:bytes,mimeType:mimeType,expectedSHA256:expectedHash)
            let event = try ClipboardImageEvent(eventId:eventId,source:source,image:image)
            let accepted = try state.acceptAndroidClipboardImage(event)
            try await peer.respondJSON(status:201,["eventId":event.eventId,"state":accepted ? "accepted" : "duplicate"]); return
        }
        if req.method == "POST" && req.path == "/v1/clipboard/image/next" {
            let o = try await json(peer,req,maxBytes:4096)
            let waitNumber = o["waitSeconds"] as? NSNumber
            guard let waitNumber, CFGetTypeID(waitNumber) != CFBooleanGetTypeID(), (0...25).contains(waitNumber.intValue) else { throw BridgeError.invalid("Некорректное время ожидания изображения") }
            let eventID = o["afterEventId"] as? String
            let deadline = Date().addingTimeInterval(TimeInterval(waitNumber.intValue))
            var event = state.nextMacClipboardImage(after:eventID)
            while event == nil, state.paired, state.isClipboardSyncEnabled, Date() < deadline {
                try await Task.sleep(nanoseconds:250_000_000)
                event = state.nextMacClipboardImage(after:eventID)
            }
            guard let event else { try await peer.respond(status:204,body:Data()); return }
            let headers = ["X-Bridge-Event-Id":event.eventId,"X-Bridge-Source":event.source,"X-Bridge-SHA256":event.image.sha256]
            try await peer.respond(status:200,body:event.image.data,contentType:event.image.mimeType,headers:headers); return
        }
        if req.method == "POST" && req.path == "/v1/clipboard/next" {
            let o = try await json(peer,req)
            let waitNumber = o["waitSeconds"] as? NSNumber
            guard let waitNumber, CFGetTypeID(waitNumber) != CFBooleanGetTypeID(), (0...25).contains(waitNumber.intValue) else { throw BridgeError.invalid("Некорректное время ожидания clipboard-события") }
            let eventID = o["afterEventId"] as? String
            let deadline = Date().addingTimeInterval(TimeInterval(waitNumber.intValue))
            var event = state.nextMacClipboard(after:eventID)
            while event == nil, state.paired, state.isClipboardSyncEnabled, Date() < deadline {
                try await Task.sleep(nanoseconds:250_000_000)
                event = state.nextMacClipboard(after:eventID)
            }
            if let event {
                let encoded = try JSONEncoder().encode(event)
                let value = try JSONSerialization.jsonObject(with:encoded)
                try await peer.respondJSON(["event":value]); return
            }
            try await peer.respondJSON(["event":NSNull()]); return
        }
        if req.method == "POST" && req.path == "/v1/offers" {
            let o = try await json(peer,req); guard state.authorize(token), let number = o["size"] as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() else { throw BridgeError.invalid("Размер не указан") }
            let id = try state.offerFile(direction:"toMac", name:try text(o,"name"), size:number.int64Value, sha256:try text(o,"sha256")); try await peer.respondJSON(status:201,["id":id]); return
        }
        if req.method == "POST" && req.path == "/v1/unpair" { _ = try await peer.body(req.length); state.unpair(); try await peer.respondJSON([:]); return }
        let parts = req.path.split(separator:"/").map(String.init)
        if parts.count == 4 && parts[0] == "v1" && parts[1] == "transfers" && parts[3] == "result" && req.method == "GET" {
            try await peer.respondJSON(["id":parts[2], "state":state.reconciliationState(parts[2])]); return
        }
        if parts.count == 4 && parts[0] == "v1" && parts[1] == "transfers" && req.method == "POST" {
            let id = parts[2], action = parts[3], o = try await json(peer,req); guard state.authorize(token) else { throw BridgeError.unauthorized }
            switch action { case "accept": try state.accept(id); case "cancel": try state.cancel(id); case "ack": try state.acknowledge(id, sha256:o["sha256"] as? String); default: throw BridgeError.invalid("Неизвестное действие") }
            try await peer.respondJSON([:]); return
        }
        if parts.count == 3 && parts[0] == "v1" && parts[1] == "files" {
            if req.method == "POST" { try await upload(parts[2], req, peer, token); return }
            if req.method == "GET" { try await download(parts[2], peer, token); return }
        }
        throw BridgeError.invalid("Неизвестный запрос")
    }
    private func upload(_ id: String, _ req: HTTPRequest, _ peer: Peer, _ token: String) async throws {
        let (transfer, directory) = try state.beginFile(id, direction:"toMac", length:req.length)
        registerTransfer(id, connection:peer.connection)
        defer { unregisterTransfer(id, connection:peer.connection) }
        guard state.reconciliationState(id) == "pending" else { throw BridgeError.invalid("Передача отменена") }
        let tmp = directory.appendingPathComponent(".bridge-" + UUID().uuidString + ".part")
        guard FileManager.default.createFile(atPath:tmp.path, contents:nil, attributes:[.posixPermissions:0o600]) else { state.markFailed(id); throw BridgeError.invalid("Нельзя записать файл") }
        defer { try? FileManager.default.removeItem(at:tmp) }
        do {
            let handle = try FileHandle(forWritingTo:tmp); defer { try? handle.close() }; var hash = SHA256(), received: Int64 = 0
            while received < transfer.size { let part = try await peer.chunk(Int(min(65536,transfer.size-received))); try handle.write(contentsOf:part); hash.update(data:part); received += Int64(part.count); try state.updateProgress(id,received) }
            try handle.synchronize(); let digest = hash.finalize().map { String(format:"%02x",$0) }.joined()
            try BridgeValidation.verifySHA256(digest,equals:transfer.sha256,deleting:tmp)
            _ = try state.finishUpload(id,token:token,temp:tmp); try await peer.respondJSON(["id":id,"state":"complete"])
        } catch { state.markFailed(id); throw error }
    }
    private func download(_ id: String, _ peer: Peer, _ token: String) async throws {
        let (transfer, file) = try state.beginFile(id,direction:"toPhone")
        registerTransfer(id, connection:peer.connection)
        defer { unregisterTransfer(id, connection:peer.connection) }
        guard state.reconciliationState(id) == "pending" else { throw BridgeError.invalid("Передача отменена") }
        do {
            let handle = try FileHandle(forReadingFrom:file); defer { try? handle.close() }
            let head = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: \(transfer.size)\r\nX-Content-SHA256: \(transfer.sha256)\r\nConnection: close\r\n\r\n"
            try await peer.send(Data(head.utf8)); var sent: Int64 = 0
            while sent < transfer.size {
                guard state.authorize(token) else { throw BridgeError.unauthorized }
                let data = try handle.read(upToCount:Int(min(65536,transfer.size-sent))) ?? Data(); guard !data.isEmpty else { throw BridgeError.invalid("Исходный файл изменился") }
                try await peer.send(data); sent += Int64(data.count); try state.updateProgress(id,sent)
            }
        } catch { state.markFailed(id); throw error }
    }
}
