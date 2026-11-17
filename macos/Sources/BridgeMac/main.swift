import AppKit
import SwiftUI
import ServiceManagement
import CoreImage.CIFilterBuiltins
import BridgeCore
import Darwin
import QuickLookThumbnailing
import UserNotifications
import ImageIO

func localAddresses() -> [String] {
    var head: UnsafeMutablePointer<ifaddrs>?; guard getifaddrs(&head) == 0 else { return [] }; defer { freeifaddrs(head) }
    var result: [String] = []; var current = head
    while let item = current {
        let interface = item.pointee; defer { current = interface.ifa_next }
        guard let address = interface.ifa_addr, address.pointee.sa_family == UInt8(AF_INET), (interface.ifa_flags & UInt32(IFF_UP)) != 0, (interface.ifa_flags & UInt32(IFF_LOOPBACK)) == 0 else { continue }
        var host = [CChar](repeating:0,count:Int(NI_MAXHOST)); if getnameinfo(address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 { let value = String(cString:host); if !result.contains(value) { result.append(value) } }
    }
    return result
}
private func transferDecimal(_ value: Double, fractionDigits: Int = 1) -> String {
    let formatter = NumberFormatter()
    formatter.locale = Locale(identifier:UserDefaults.standard.string(forKey:"appLanguage") ?? "en")
    formatter.numberStyle = .decimal
    formatter.minimumFractionDigits = fractionDigits
    formatter.maximumFractionDigits = fractionDigits
    return formatter.string(from:NSNumber(value:value)) ?? String(value)
}
private func transferSize(_ bytes: Int64) -> String {
    let value = max(0, bytes)
    if value >= 1_048_576 { return "\(transferDecimal(Double(value) / 1_048_576)) MB" }
    if value >= 1_024 { return "\(transferDecimal(Double(value) / 1_024)) KB" }
    return "\(value) bytes"
}
private func transferProgressValue(_ bytes: Int64, total: Int64) -> String {
    let value = max(0, bytes)
    if total >= 1_048_576 { return "\(transferDecimal(Double(value) / 1_048_576)) MB" }
    return transferSize(value)
}
private func transferSpeed(_ bytesPerSecond: Double) -> String {
    let megabytesPerSecond = max(0, bytesPerSecond) / 1_048_576
    return "\(transferDecimal(megabytesPerSecond, fractionDigits:megabytesPerSecond < 0.1 ? 2 : 1)) MB/s"
}
private func phoneModelLabel(_ rawModel: String?) -> String {
    guard let model = rawModel?.trimmingCharacters(in:.whitespacesAndNewlines), !model.isEmpty else { return "Телефон Android" }
    if model.uppercased().contains("SM-S931") { return "Samsung Galaxy S25" }
    return model
}
struct PendingFile: Identifiable {
    let id: String
    let url: URL
    var state: String
    var error: String?
    var finderShareRequestID: String? = nil
    var finderShareFileName: String? = nil
    var name: String { url.lastPathComponent }
}
private final class BluetoothIncomingFileSession {
    let id: String
    let temporaryURL: URL
    let handle: FileHandle
    var received: Int64 = 0
    init(id: String, temporaryURL: URL, handle: FileHandle) { self.id = id; self.temporaryURL = temporaryURL; self.handle = handle }
}
final class ViewModel: ObservableObject {
    @Published var transfers: [Transfer] = []
    @Published var prompt: String?
    @Published var paired = false
    @Published var message = "Запуск…"
    @Published var invitation = ""
    @Published var qr: NSImage?
    @Published var connected = false
    @Published var lastPhoneContact: Date?
    @Published var phoneModel: String?
    @Published var previews: [String:NSImage] = [:]
    @Published private(set) var filePaths: [String:String] = [:]
    @Published var outgoingText = ""
    @Published private(set) var queuedFiles: [PendingFile] = []
    var queuedFileCount: Int { queuedFiles.count }
    @Published var progressDetails: [String:String] = [:]
    @Published var clipboardSyncEnabled = false
    @Published var bluetoothModeEnabled = UserDefaults.standard.bool(forKey:"bridge.bluetoothModeEnabled")
    @Published var bluetoothStatus = "Bluetooth выключен"
    @Published var bluetoothConnected = false
    @Published var autoSendMacScreenshots = UserDefaults.standard.bool(forKey:"bridge.autoSendMacScreenshots")
    @Published var autoAcceptIncomingFiles = UserDefaults.standard.bool(forKey:"bridge.autoAcceptIncomingFiles")
    @Published var clipboardHistory: [String] = []
    @Published var launchAtLoginEnabled = false
    @Published var loginItemNeedsApproval = false
    private var progressStarts: [String:(time:Date, bytes:Int64)] = [:]
    private var preparingFile = false
    private var preparingFileID: String?
    private var cancelledQueuedFileIDs = Set<String>()
    private var autoAcceptingFileIDs = Set<String>()
    private var autoAcceptedFileIDs = Set<String>()
    private var notificationRequestsInFlight = Set<String>()
    private var clipboardTimer: Timer?
    private var macScreenshotTimer: Timer?
    private var macScreenshotBaselinePending = false
    private var macScreenshotDirectoryPath: String?
    private var knownMacScreenshotPaths = Set<String>()
    private var pendingMacScreenshotObservations: [String:(size:Int64, stableScans:Int)] = [:]
    private var finderShareInboxTimer: Timer?
    private let finderShareInbox = FinderShareInbox()
    private var importedFinderShareRequestIDs = Set<String>()
    private var finderShareSourcePaths = UserDefaults.standard.dictionary(forKey:"bridge.finderShareSourcePaths") as? [String:String] ?? [:]
    private var previewIDs: Set<String>?
    private let previewDirectory = FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask)[0].appendingPathComponent("SamsungMacBridge/Previews",isDirectory:true)
    private let clipboardHistoryStore = PairClipboardHistoryStore(directory:FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask)[0].appendingPathComponent("SamsungMacBridge",isDirectory:true))
    private var loadedClipboardPairID: String?
    private var lastPasteboardChangeCount = NSPasteboard.general.changeCount
    private var bluetoothCentral: BluetoothBridgeCentral?
    private var pendingBluetoothClipboard: [String:Data] = [:]
    private var pendingBluetoothClipboardOrder: [String] = []
    private var sentBluetoothClipboardIDs = Set<String>()
    private var receivedBluetoothTextIDs = Set<String>()
    private var bluetoothIncomingFiles: [String:BluetoothIncomingFileSession] = [:]
    var onClipboardEnabledChange: ((Bool) -> Void)?
    let state = BridgeState(loadCredentials:false)
    var server: BridgeServer?, port: UInt16 = 0
    init() {
        UserDefaults.standard.removeObject(forKey:"bridge.acceleratedFileTransferEnabled")
        filePaths = UserDefaults.standard.dictionary(forKey:"bridge.localFilePaths") as? [String:String] ?? [:]
        clipboardSyncEnabled = state.isClipboardSyncEnabled
        refreshLoginItemStatus()
        state.onChange = { [weak self] in self?.refresh() }
        state.onClipboardReceived = { [weak self] event in self?.applyClipboard(event) }
        state.onClipboardImageReceived = { [weak self] event in self?.applyClipboardImage(event) ?? false }
        state.onLocalFileAvailable = { [weak self] id, url in self?.rememberLocalFile(id:id, url:url) }
        refresh(); startClipboardMonitor(); startMacScreenshotMonitor()
        finderShareInboxTimer = Timer.scheduledTimer(withTimeInterval:1, repeats:true) { [weak self] _ in self?.importFinderShareFiles() }
        DispatchQueue.global(qos:.userInitiated).async { [weak self] in
            guard let self = self else { return }
            do {
                self.state.loadSavedCredentials()
                DispatchQueue.main.async {
                    self.paired = self.state.paired
                    self.configureBluetoothCentral()
                }
                let identity = try BridgeIdentity.loadOrCreate(keychain:BridgeKeychain(), deviceID:self.state.deviceID)
                let server = try BridgeServer(state:self.state, identity:identity)
                server.onReady = { [weak self] port in self?.port = port; self?.connected = true; self?.message = "Ожидание телефона в локальной сети" }
                server.onError = { [weak self] error in self?.message = "Ошибка сети: " + error }
                server.onAuthenticatedRequest = { [weak self] in self?.lastPhoneContact = Date() }
                self.server = server; server.start()
            } catch { DispatchQueue.main.async { self.message = error.localizedDescription } }
        }
    }
    func refresh() {
        let pairID = state.pairHistoryID
        if loadedClipboardPairID != pairID {
            if let migration = state.clipboardHistoryMigration, migration.targetID == pairID {
                do {
                    try clipboardHistoryStore.copyHistoryIfNeeded(from:migration.sourceID,to:migration.targetID)
                    state.completeClipboardHistoryMigration(sourceID:migration.sourceID,targetID:migration.targetID)
                } catch { message = "Не удалось перенести историю буфера для этой пары." }
            }
            loadedClipboardPairID = pairID
            clipboardHistory = clipboardHistoryStore.load(pairID:pairID)
        }
        let current = state.transfers, now = Date()
        acceptIncomingFilesAutomatically(current)
        var details: [String:String] = [:]
        let active = Set(current.filter { $0.type == "file" && $0.state == "transferring" }.map(\.id))
        progressStarts = progressStarts.filter { active.contains($0.key) }
        for transfer in current where active.contains(transfer.id) && transfer.progress > 0 {
            if progressStarts[transfer.id] == nil { progressStarts[transfer.id] = (now, transfer.progress) }
            guard let start = progressStarts[transfer.id] else { continue }
            let elapsed = now.timeIntervalSince(start.time), sent = transfer.progress - start.bytes
            guard elapsed >= 1, sent > 0 else { continue }
            let rate = Double(sent) / elapsed
            let seconds = Int(min(31_536_000, ceil(Double(max(0, transfer.size - transfer.progress)) / rate)))
            let remaining: String
            if seconds < 60 { remaining = "\(max(1, seconds)) с" }
            else if seconds < 3_600 { remaining = "\(max(1, seconds / 60)) мин" }
            else { remaining = "\(seconds / 3_600) ч \((seconds % 3_600) / 60) мин" }
            details[transfer.id] = "\(transferSpeed(rate)) · ≈\(remaining)"
        }
        progressDetails = details; transfers = current; prompt = state.pairPrompt; paired = state.paired; phoneModel = state.phoneModel
        notifyCompletedAutoReceives(current)
        let ids = Set(current.filter { $0.type == "file" }.map(\.id))
        if ids != previewIDs {
            previewIDs = ids
            previews = previews.filter { ids.contains($0.key) }
            try? FileManager.default.createDirectory(at:previewDirectory,withIntermediateDirectories:true)
            for id in ids where previews[id] == nil { if let url = previewURL(id), let image = NSImage(contentsOf:url) { previews[id] = image } }
        }
        cleanupFinderShareSources(current)
        importFinderShareFiles()
        processFileQueue()
    }
    private func previewURL(_ id: String) -> URL? {
        guard UUID(uuidString:id) != nil else { return nil }
        return previewDirectory.appendingPathComponent(id.lowercased()).appendingPathExtension("png")
    }
    private func rememberLocalFile(id: String, url: URL) {
        guard UUID(uuidString:id) != nil, url.isFileURL, state.transfers.contains(where: { $0.id == id }) else { return }
        filePaths[id] = url.standardizedFileURL.path
        UserDefaults.standard.set(filePaths,forKey:"bridge.localFilePaths")
        makePreview(id:id,url:url)
        notifyCompletedAutoReceives(state.transfers)
    }
    func revealFile(_ transfer: Transfer) {
        guard let storedPath = filePaths[transfer.id] else {
            message = "Путь к файлу недоступен. Файл мог быть перемещён или передан до этого обновления."
            return
        }
        let path = reconciledReceivedFilePath(storedPath)
        if path != storedPath {
            filePaths[transfer.id] = path
            UserDefaults.standard.set(filePaths,forKey:"bridge.localFilePaths")
        }
        guard FileManager.default.fileExists(atPath:path) else {
            message = "Путь к файлу недоступен. Файл мог быть перемещён или передан до этого обновления."
            return
        }
        NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath:path)])
    }
    func revealFile(id: String) {
        guard let transfer = state.transfers.first(where: { $0.id == id }) else { message = "Передача больше не найдена в истории."; return }
        revealFile(transfer)
    }
    func openReceivedFolder() {
        do {
            let folder = try receivedFilesDirectory()
            guard NSWorkspace.shared.open(folder) else { throw BridgeError.invalid("Finder не смог открыть папку") }
        } catch { fail(error) }
    }
    private func receivedFilesDirectory() throws -> URL {
        let fileManager = FileManager.default
        let documents = fileManager.urls(for:.documentDirectory,in:.userDomainMask)[0]
        let folder = documents.appendingPathComponent("AndroidMacBridge",isDirectory:true)
        let legacy = fileManager.urls(for:.downloadsDirectory,in:.userDomainMask)[0]
            .appendingPathComponent("SamsungMacBridge",isDirectory:true)

        if fileManager.fileExists(atPath:legacy.path) {
            if !fileManager.fileExists(atPath:folder.path) {
                do { try fileManager.moveItem(at:legacy,to:folder) }
                catch {
                    try? fileManager.createDirectory(at:folder,withIntermediateDirectories:true)
                    moveLegacyReceivedItems(from:legacy,to:folder)
                }
            } else {
                moveLegacyReceivedItems(from:legacy,to:folder)
            }
        }
        try fileManager.createDirectory(at:folder,withIntermediateDirectories:true)
        return folder
    }
    private func moveLegacyReceivedItems(from legacy: URL, to folder: URL) {
        let fileManager = FileManager.default
        guard let items = try? fileManager.contentsOfDirectory(at:legacy,includingPropertiesForKeys:nil) else { return }
        for item in items {
            let destination = folder.appendingPathComponent(item.lastPathComponent)
            guard !fileManager.fileExists(atPath:destination.path) else { continue }
            try? fileManager.moveItem(at:item,to:destination)
        }
        if let remaining = try? fileManager.contentsOfDirectory(at:legacy,includingPropertiesForKeys:nil), remaining.isEmpty {
            try? fileManager.removeItem(at:legacy)
        }
    }
    private func reconciledReceivedFilePath(_ path: String) -> String {
        let fileManager = FileManager.default
        let legacyRoot = fileManager.urls(for:.downloadsDirectory,in:.userDomainMask)[0]
            .appendingPathComponent("SamsungMacBridge",isDirectory:true).standardizedFileURL.path
        let prefix = legacyRoot + "/"
        guard path.hasPrefix(prefix) else { return path }
        let relativePath = String(path.dropFirst(prefix.count))
        let destination = fileManager.urls(for:.documentDirectory,in:.userDomainMask)[0]
            .appendingPathComponent("AndroidMacBridge",isDirectory:true)
            .appendingPathComponent(relativePath).standardizedFileURL.path
        return !fileManager.fileExists(atPath:path) && fileManager.fileExists(atPath:destination) ? destination : path
    }
    private func notifyCompletedAutoReceives(_ candidates: [Transfer]) {
        let center = UNUserNotificationCenter.current()
        center.getNotificationSettings { [weak self] settings in
            guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else { return }
            DispatchQueue.main.async {
                guard let self = self else { return }
                let sent = Set(UserDefaults.standard.stringArray(forKey:"bridge.autoReceiveNotifiedIDs") ?? [])
                for transfer in candidates where transfer.direction == "toMac" && transfer.type == "file" && transfer.state == "complete" {
                    guard !sent.contains(transfer.id), self.autoAcceptedFileIDs.contains(transfer.id),
                          let path = self.filePaths[transfer.id], FileManager.default.fileExists(atPath:path),
                          self.notificationRequestsInFlight.insert(transfer.id).inserted else { continue }
                    let content = UNMutableNotificationContent()
                    content.title = "Файл получен"
                    content.body = transfer.name
                    content.sound = .default
                    content.userInfo = ["transferID":transfer.id]
                    let request = UNNotificationRequest(identifier:"smb-auto-received-\(transfer.id)",content:content,trigger:nil)
                    center.add(request) { [weak self] error in
                        DispatchQueue.main.async {
                            guard let self = self else { return }
                            self.notificationRequestsInFlight.remove(transfer.id)
                            if error == nil {
                                var sent = UserDefaults.standard.stringArray(forKey:"bridge.autoReceiveNotifiedIDs") ?? []
                                if !sent.contains(transfer.id) { sent.append(transfer.id); UserDefaults.standard.set(Array(sent.suffix(100)),forKey:"bridge.autoReceiveNotifiedIDs") }
                                self.autoAcceptedFileIDs.remove(transfer.id)
                            }
                        }
                    }
                }
            }
        }
    }
    private func makePreview(id: String, url: URL) {
        guard previewURL(id) != nil else { return }
        let request = QLThumbnailGenerator.Request(fileAt:url,size:CGSize(width:96,height:96),scale:2,representationTypes:.thumbnail)
        QLThumbnailGenerator.shared.generateBestRepresentation(for:request) { [weak self] thumbnail, _ in
            guard let self = self, let cgImage = thumbnail?.cgImage,
                  let bytes = NSBitmapImageRep(cgImage:cgImage).representation(using:.png,properties:[:]) else { return }
            DispatchQueue.main.async {
                guard self.state.transfers.contains(where: { $0.id == id }), let destination = self.previewURL(id) else { return }
                try? FileManager.default.createDirectory(at:self.previewDirectory,withIntermediateDirectories:true)
                guard (try? bytes.write(to:destination,options:.atomic)) != nil else { return }
                self.previews[id] = NSImage(cgImage:cgImage,size:NSSize(width:96,height:96))
            }
        }
    }
    func fail(_ error: Error) { message = error.localizedDescription }
    func setClipboardSyncEnabled(_ enabled: Bool) {
        guard !enabled || state.paired else {
            clipboardSyncEnabled = false; state.setClipboardSyncEnabled(false); onClipboardEnabledChange?(false); message = "Сначала подключите телефон, затем включите общий буфер."; return
        }
        state.setClipboardSyncEnabled(enabled); clipboardSyncEnabled = enabled
        onClipboardEnabledChange?(enabled)
        lastPasteboardChangeCount = NSPasteboard.general.changeCount
        baselineMacScreenshots()
        message = enabled ? "Общий буфер включён. Mac передаёт изменения сопряжённому телефону." : "Общий буфер приостановлен."
    }
    func setBluetoothModeEnabled(_ enabled: Bool) {
        guard !enabled || state.paired else {
            bluetoothModeEnabled = false
            UserDefaults.standard.set(false,forKey:"bridge.bluetoothModeEnabled")
            message = "Сначала сопрягите телефон по QR-коду."
            return
        }
        bluetoothModeEnabled = enabled
        UserDefaults.standard.set(enabled,forKey:"bridge.bluetoothModeEnabled")
        if enabled {
            configureBluetoothCentral()
            bluetoothCentral?.start()
            message = "Ищу телефон по Bluetooth. Общая сеть Wi‑Fi не нужна."
        } else {
            bluetoothCentral?.stop()
            pendingBluetoothClipboard.removeAll()
            pendingBluetoothClipboardOrder.removeAll()
            sentBluetoothClipboardIDs.removeAll()
            bluetoothConnected = false
            bluetoothStatus = "Bluetooth выключен"
            message = "Bluetooth-транспорт выключен."
        }
    }
    private func configureBluetoothCentral() {
        guard bluetoothCentral == nil, state.paired else { return }
        let central = BluetoothBridgeCentral(state:state)
        central.onStatus = { [weak self, weak central] status in
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.bluetoothStatus = status
                self.bluetoothConnected = central?.isConnected ?? false
                if self.bluetoothConnected { self.flushBluetoothClipboardQueue() }
            }
        }
        central.onMessage = { [weak self] data in self?.handleBluetoothMessage(data) }
        bluetoothCentral = central
        if bluetoothModeEnabled { central.start() }
    }
    private func handleBluetoothMessage(_ data: Data) {
        guard bluetoothModeEnabled, let central = bluetoothCentral else { return }
        do {
            let envelope = try BluetoothFileMessage.decode(data)
            if let kind = envelope["kind"] as? String, kind.hasPrefix("file.") {
                try handleBluetoothFileMessage(envelope, kind:kind, central:central)
                return
            }
            if envelope["kind"] as? String == "text.offer" {
                guard let id = envelope["id"] as? String, UUID(uuidString:id) != nil,
                      let value = envelope["text"] as? String, let type = envelope["type"] as? String else {
                    throw BridgeError.invalid("Некорректное Bluetooth-сообщение")
                }
                do {
                    if !receivedBluetoothTextIDs.contains(id) {
                        _ = try state.addText(direction:"toMac",type:type,text:value)
                        receivedBluetoothTextIDs.insert(id)
                    }
                    try central.send(BluetoothFileMessage.reply(id:id,phase:"text",state:"accepted"))
                    message = "Получен текст с телефона по Bluetooth."
                } catch {
                    try? central.send(BluetoothFileMessage.reply(id:id,phase:"text",state:"rejected"))
                    throw error
                }
                return
            }
            switch try BluetoothClipboardMessage.decode(data) {
            case .text(let event):
                let receipt = (try? state.acceptAndroidClipboard(event)) == true ? "accepted" : (state.isClipboardSyncEnabled ? "duplicate" : "paused")
                try central.send(BluetoothClipboardMessage.acknowledgement(eventId:event.eventId,state:receipt))
            case .image(let event):
                let receipt = (try? state.acceptAndroidClipboardImage(event)) == true ? "accepted" : (state.isClipboardSyncEnabled ? "duplicate" : "paused")
                try central.send(BluetoothClipboardMessage.acknowledgement(eventId:event.eventId,state:receipt))
            case .acknowledgement(let eventID,let state):
                central.resolveClipboardAck(eventID:eventID,state:state)
            }
        } catch {
            message = "Bluetooth: \(error.localizedDescription)"
        }
    }

    private func handleBluetoothFileMessage(_ message: [String:Any], kind: String, central: BluetoothBridgeCentral) throws {
        guard let id = message["id"] as? String, UUID(uuidString:id) != nil else { throw BridgeError.invalid("Некорректный ID Bluetooth-файла") }
        switch kind {
        case "file.reply":
            central.resolveFileReply(message)
        case "file.offer":
            do {
                guard let name = message["name"] as? String,
                      let size = message["size"] as? Int64,
                      let hash = message["sha256"] as? String else { throw BridgeError.invalid("Некорректное предложение Bluetooth-файла") }
                if state.transfers.contains(where: { $0.id == id }) { return }
                _ = try state.offerFile(direction:"toMac",name:name,size:size,sha256:hash,transferID:id)
                if autoAcceptIncomingFiles {
                    let folder = try receivedFilesDirectory()
                    try state.accept(id,destination:folder)
                    try prepareBluetoothReceive(id)
                    autoAcceptedFileIDs.insert(id)
                    try central.send(BluetoothFileMessage.reply(id:id,phase:"decision",state:"accepted"))
                    self.message = "Принимаю «\(name)» по Bluetooth в Документы/AndroidMacBridge."
                } else {
                    self.message = "Файл «\(name)» ждёт подтверждения на Mac."
                }
            } catch {
                try? central.send(BluetoothFileMessage.reply(id:id,phase:"decision",state:"rejected"))
                throw error
            }
        case "file.chunk":
            guard let session = bluetoothIncomingFiles[id],
                  let offset = message["offset"] as? Int64 else {
                try central.send(BluetoothFileMessage.reply(id:id,phase:"chunk",state:"rejected",nextOffset:0))
                return
            }
            do {
                let bytes = try BluetoothFileMessage.chunkData(message)
                guard offset == session.received,
                      let transfer = state.transfers.first(where: { $0.id == id }),
                      session.received + Int64(bytes.count) <= transfer.size else { throw BridgeError.invalid("Нарушена последовательность Bluetooth-файла") }
                try session.handle.write(contentsOf:bytes)
                session.received += Int64(bytes.count)
                try state.updateProgress(id,session.received)
                try central.send(BluetoothFileMessage.reply(id:id,phase:"chunk",state:"accepted",nextOffset:session.received))
            } catch {
                try? central.send(BluetoothFileMessage.reply(id:id,phase:"chunk",state:"rejected",nextOffset:session.received))
                throw error
            }
        case "file.finish":
            guard let session = bluetoothIncomingFiles.removeValue(forKey:id),
                  let size = message["size"] as? Int64,
                  let expectedHash = message["sha256"] as? String,
                  let transfer = state.transfers.first(where: { $0.id == id }) else {
                try central.send(BluetoothFileMessage.reply(id:id,phase:"result",state:"rejected"))
                return
            }
            do {
                try session.handle.synchronize(); try session.handle.close()
                let (actualSize,actualHash) = try BridgeValidation.hash(file:session.temporaryURL)
                guard size == transfer.size, session.received == transfer.size, actualSize == transfer.size,
                      expectedHash == transfer.sha256, actualHash == transfer.sha256 else { throw BridgeError.invalid("Размер или SHA-256 Bluetooth-файла не совпадает") }
                _ = try state.finishTicketedUpload(id,temp:session.temporaryURL)
                try central.send(BluetoothFileMessage.reply(id:id,phase:"result",state:"complete",sha256:actualHash))
                self.message = "Файл «\(transfer.name)» получен по Bluetooth."
            } catch {
                try? session.handle.close(); try? FileManager.default.removeItem(at:session.temporaryURL)
                state.markFailed(id)
                try? central.send(BluetoothFileMessage.reply(id:id,phase:"result",state:"failed"))
                throw error
            }
        case "file.cancel":
            if let session = bluetoothIncomingFiles.removeValue(forKey:id) {
                try? session.handle.close(); try? FileManager.default.removeItem(at:session.temporaryURL)
            }
            if let transfer = state.transfers.first(where: { $0.id == id }), !["complete","cancelled","failed"].contains(transfer.state) { try? state.cancel(id) }
            try central.send(BluetoothFileMessage.reply(id:id,phase:"decision",state:"rejected"))
            try central.send(BluetoothFileMessage.reply(id:id,phase:"chunk",state:"cancelled"))
            try central.send(BluetoothFileMessage.reply(id:id,phase:"result",state:"cancelled"))
            self.message = "Bluetooth-передача отменена."
        default:
            throw BridgeError.invalid("Неизвестное Bluetooth-сообщение файла")
        }
    }

    private func prepareBluetoothReceive(_ id: String) throws {
        let (transfer,destination) = try state.beginFile(id,direction:"toMac")
        let temporary = destination.appendingPathComponent(".smb-\(id).part")
        guard FileManager.default.createFile(atPath:temporary.path,contents:nil) else { throw BridgeError.invalid("Не удалось создать временный Bluetooth-файл") }
        do {
            let handle = try FileHandle(forWritingTo:temporary)
            bluetoothIncomingFiles[id] = BluetoothIncomingFileSession(id:id,temporaryURL:temporary,handle:handle)
            message = "Принимаю «\(transfer.name)» по Bluetooth…"
        } catch {
            try? FileManager.default.removeItem(at:temporary)
            state.markFailed(id)
            throw error
        }
    }
    private func queueBluetoothClipboard(_ data: Data, eventID: String) {
        guard bluetoothModeEnabled else { return }
        if pendingBluetoothClipboard[eventID] == nil { pendingBluetoothClipboardOrder.append(eventID) }
        pendingBluetoothClipboard[eventID] = data
        while pendingBluetoothClipboardOrder.count > 4 {
            let removed = pendingBluetoothClipboardOrder.removeFirst()
            pendingBluetoothClipboard.removeValue(forKey:removed)
            sentBluetoothClipboardIDs.remove(removed)
        }
        flushBluetoothClipboardQueue()
    }
    private func flushBluetoothClipboardQueue() {
        guard bluetoothModeEnabled, let central = bluetoothCentral, central.isConnected else { return }
        for eventID in pendingBluetoothClipboardOrder where !sentBluetoothClipboardIDs.contains(eventID) {
            guard let data = pendingBluetoothClipboard[eventID] else { continue }
            do {
                sentBluetoothClipboardIDs.insert(eventID)
                try central.sendClipboard(data,eventID:eventID) { [weak self] result in
                    guard let self = self else { return }
                    self.sentBluetoothClipboardIDs.remove(eventID)
                    if result == "accepted" || result == "duplicate" {
                        self.pendingBluetoothClipboard.removeValue(forKey:eventID)
                        self.pendingBluetoothClipboardOrder.removeAll { $0 == eventID }
                        self.message = "Буфер синхронизирован по Bluetooth."
                    } else if result != "disconnected" {
                        self.pendingBluetoothClipboard.removeValue(forKey:eventID)
                        self.pendingBluetoothClipboardOrder.removeAll { $0 == eventID }
                        self.message = "Mac не получил подтверждение Bluetooth-буфера (\(result))."
                    }
                }
            } catch {
                sentBluetoothClipboardIDs.remove(eventID)
                message = "Буфер ждёт Bluetooth-соединения с телефоном."
                break
            }
        }
    }
    func setAutoSendMacScreenshots(_ enabled: Bool) {
        autoSendMacScreenshots = enabled
        UserDefaults.standard.set(enabled,forKey:"bridge.autoSendMacScreenshots")
        baselineMacScreenshots()
        message = enabled ? "Новые снимки Mac будут передаваться при включённом общем буфере." : "Автоотправка снимков Mac выключена."
    }
    func setLaunchAtLogin(_ enabled: Bool) {
        do {
            if enabled { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
            refreshLoginItemStatus()
            if loginItemNeedsApproval { message = "Разрешите запуск AndroidMacBridge в Системных настройках → Основные → Объекты входа." }
            else { message = enabled ? "Приложение будет запускаться при входе." : "Автозапуск при входе выключен." }
        } catch { refreshLoginItemStatus(); message = "Не удалось изменить автозапуск: \(error.localizedDescription)" }
    }
    private func refreshLoginItemStatus() {
        let status = SMAppService.mainApp.status
        launchAtLoginEnabled = status == .enabled || status == .requiresApproval
        loginItemNeedsApproval = status == .requiresApproval
    }
    private func startClipboardMonitor() {
        clipboardTimer?.invalidate()
        clipboardTimer = Timer.scheduledTimer(withTimeInterval:1.0,repeats:true) { [weak self] _ in self?.checkPasteboard() }
    }
    private func startMacScreenshotMonitor() {
        macScreenshotTimer?.invalidate()
        baselineMacScreenshots()
        macScreenshotTimer = Timer.scheduledTimer(withTimeInterval:0.5,repeats:true) { [weak self] _ in self?.scanMacScreenshots() }
    }
    private func macScreenshotDirectory() -> URL {
        if let path = CFPreferencesCopyAppValue("location" as CFString,"com.apple.screencapture" as CFString) as? String,
           !path.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty {
            let expanded = NSString(string:path).expandingTildeInPath
            return URL(fileURLWithPath:expanded,isDirectory:true).standardizedFileURL
        }
        return FileManager.default.urls(for:.desktopDirectory,in:.userDomainMask)[0]
    }
    private func isMacScreenshot(_ url: URL) -> Bool {
        let name = url.deletingPathExtension().lastPathComponent.lowercased()
        let ext = url.pathExtension.lowercased()
        guard ["png","jpg","jpeg","tif","tiff","heic","heif"].contains(ext) else { return false }
        return ["screenshot","screen shot","снимок экрана"].contains { name.hasPrefix($0) }
    }
    private func screenshotFiles(in directory: URL) -> [URL] {
        guard let entries = try? FileManager.default.contentsOfDirectory(at:directory,includingPropertiesForKeys:[.isRegularFileKey,.fileSizeKey]) else { return [] }
        return entries.filter(isMacScreenshot)
    }
    private func baselineMacScreenshots() {
        guard !macScreenshotBaselinePending else { return }
        macScreenshotBaselinePending = true
        DispatchQueue.global(qos:.utility).async { [weak self] in
            guard let self = self else { return }
            let directory = self.macScreenshotDirectory()
            let paths = Set(self.screenshotFiles(in:directory).map(\.path))
            DispatchQueue.main.async {
                self.macScreenshotDirectoryPath = directory.path
                self.knownMacScreenshotPaths = paths
                self.pendingMacScreenshotObservations.removeAll()
                self.macScreenshotBaselinePending = false
            }
        }
    }
    private func scanMacScreenshots() {
        guard !macScreenshotBaselinePending else { return }
        let directory = macScreenshotDirectory()
        var isDirectory: ObjCBool = false
        guard FileManager.default.fileExists(atPath:directory.path,isDirectory:&isDirectory), isDirectory.boolValue else {
            macScreenshotDirectoryPath = nil
            knownMacScreenshotPaths.removeAll()
            pendingMacScreenshotObservations.removeAll()
            return
        }
        if macScreenshotDirectoryPath != directory.path {
            baselineMacScreenshots()
            return
        }
        let files = screenshotFiles(in:directory)
        let currentPaths = Set(files.map(\.path))
        knownMacScreenshotPaths.formIntersection(currentPaths)
        pendingMacScreenshotObservations = pendingMacScreenshotObservations.filter { currentPaths.contains($0.key) }
        guard autoSendMacScreenshots, clipboardSyncEnabled, state.isClipboardSyncEnabled, state.paired else {
            knownMacScreenshotPaths.formUnion(currentPaths)
            pendingMacScreenshotObservations.removeAll()
            return
        }
        for url in files where !knownMacScreenshotPaths.contains(url.path) {
            guard let values = try? url.resourceValues(forKeys:[.isRegularFileKey,.fileSizeKey]), values.isRegularFile == true,
                  let number = values.fileSize, number > 0 else { continue }
            let size = Int64(number)
            var observation = pendingMacScreenshotObservations[url.path] ?? (size:size,stableScans:0)
            if observation.size == size { observation.stableScans += 1 }
            else { observation = (size:size,stableScans:0) }
            pendingMacScreenshotObservations[url.path] = observation
            guard observation.stableScans >= 2 else { continue }
            knownMacScreenshotPaths.insert(url.path)
            pendingMacScreenshotObservations.removeValue(forKey:url.path)
            sendMacScreenshot(at:url)
        }
    }
    private func sendMacScreenshot(at url: URL) {
        guard let image = clipboardImage(fromScreenshot:url) else {
            message = "Не удалось прочитать снимок Mac или его формат и размер не поддерживаются."
            return
        }
        do {
            let event = try ClipboardImageEvent(source:"mac",image:image)
            if try state.publishMacClipboardImage(event) {
                if bluetoothModeEnabled { queueBluetoothClipboard(try BluetoothClipboardMessage.image(event),eventID:event.eventId); message = "Снимок Mac ждёт передачи по Bluetooth." }
                else { message = "Снимок Mac передан в буфер телефона." }
            }
        } catch { message = error.localizedDescription }
    }
    private func checkPasteboard() {
        guard clipboardSyncEnabled, state.isClipboardSyncEnabled, state.paired else { return }
        let pasteboard = NSPasteboard.general, current = pasteboard.changeCount
        guard current != lastPasteboardChangeCount else { return }
        lastPasteboardChangeCount = current
        let hiddenTypes = ["org.nspasteboard.ConcealedType", "org.nspasteboard.TransientType", "org.nspasteboard.AutoGeneratedType"]
        if pasteboard.types?.contains(where:{ hiddenTypes.contains($0.rawValue) }) == true { return }
        let imageTypes = ["public.png", "public.jpeg", "public.tiff", "public.heic", "public.heif"]
        let containsImage = pasteboard.types?.contains { imageTypes.contains($0.rawValue) } == true
        if containsImage {
            guard let image = clipboardImage(from:pasteboard) else {
                message = "Не удалось прочитать изображение из буфера Mac."
                return
            }
            do {
                let event = try ClipboardImageEvent(source:"mac",image:image)
                if try state.publishMacClipboardImage(event) {
                    if bluetoothModeEnabled { queueBluetoothClipboard(try BluetoothClipboardMessage.image(event),eventID:event.eventId); message = "Изображение ждёт передачи по Bluetooth." }
                    else { message = "Изображение передано в буфер телефона." }
                }
            } catch { message = error.localizedDescription }
            return
        }
        if let text = pasteboard.string(forType:.string), !text.isEmpty {
            rememberClipboard(text)
            do {
                let event = try ClipboardEvent(source:"mac",text:text)
                if try state.publishMacClipboard(event) {
                    if bluetoothModeEnabled { queueBluetoothClipboard(try BluetoothClipboardMessage.text(event),eventID:event.eventId); message = "Текст ждёт передачи по Bluetooth." }
                    else { message = event.type == "link" ? "Ссылка передана в буфер телефона." : "Текст передан в буфер телефона." }
                }
            } catch { message = error.localizedDescription }
        }
    }
    private func clipboardImage(from pasteboard: NSPasteboard) -> ClipboardImage? {
        let pngType = NSPasteboard.PasteboardType("public.png")
        let jpegType = NSPasteboard.PasteboardType("public.jpeg")
        for (type, mime) in [(pngType,"image/png"),(jpegType,"image/jpeg")] {
            guard let data = pasteboard.data(forType:type), data.count <= ClipboardImage.maxBytes else { continue }
            if let image = try? ClipboardImage(data:data,mimeType:mime) { return image }
        }
        let encodedTypes = ["public.tiff", "public.heic", "public.heif"]
        for encodedType in encodedTypes {
            let pasteboardType = NSPasteboard.PasteboardType(encodedType)
            // TIFF produced by a screenshot shortcut can be much larger than its normalized PNG/JPEG.
            guard let encodedData = pasteboard.data(forType:pasteboardType), encodedData.count <= maxEncodedClipboardImageBytes,
                  let source = CGImageSourceCreateWithData(encodedData as CFData,nil), CGImageSourceGetCount(source) == 1,
                  CGImageSourceGetStatusAtIndex(source,0) == .statusComplete,
                  let properties = CGImageSourceCopyPropertiesAtIndex(source,0,nil) as? [CFString:Any],
                  let width = properties[kCGImagePropertyPixelWidth] as? Int,
                  let height = properties[kCGImagePropertyPixelHeight] as? Int,
                  width > 0, height > 0, width <= ClipboardImage.maxPixels / height,
                  let cgImage = CGImageSourceCreateImageAtIndex(source,0,nil) else { continue }

            if let image = normalizedClipboardImage(cgImage, uti:"public.png", mimeType:"image/png") { return image }
            if let image = normalizedClipboardImage(cgImage, uti:"public.jpeg", mimeType:"image/jpeg", quality:0.9) { return image }
        }
        return nil
    }
    private func clipboardImage(fromScreenshot url: URL) -> ClipboardImage? {
        guard let values = try? url.resourceValues(forKeys:[.isRegularFileKey,.fileSizeKey]), values.isRegularFile == true,
              let fileSize = values.fileSize, fileSize > 0, fileSize <= maxEncodedClipboardImageBytes,
              let data = try? Data(contentsOf:url,options:.mappedIfSafe),
              let source = CGImageSourceCreateWithData(data as CFData,nil), CGImageSourceGetCount(source) == 1,
              CGImageSourceGetStatusAtIndex(source,0) == .statusComplete,
              let properties = CGImageSourceCopyPropertiesAtIndex(source,0,nil) as? [CFString:Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int,
              width > 0, height > 0, width <= ClipboardImage.maxPixels / height,
              let image = CGImageSourceCreateImageAtIndex(source,0,nil) else { return nil }

        let ext = url.pathExtension.lowercased()
        if ["png","jpg","jpeg"].contains(ext), data.count <= ClipboardImage.maxBytes {
            let mimeType = ext == "png" ? "image/png" : "image/jpeg"
            if let direct = try? ClipboardImage(data:data,mimeType:mimeType) { return direct }
        }
        return normalizedClipboardImage(image,uti:"public.png",mimeType:"image/png")
            ?? normalizedClipboardImage(image,uti:"public.jpeg",mimeType:"image/jpeg",quality:0.9)
    }
    private var maxEncodedClipboardImageBytes: Int { ClipboardImage.maxBytes * 8 }
    private func normalizedClipboardImage(_ image: CGImage, uti: String, mimeType: String, quality: Double? = nil) -> ClipboardImage? {
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(output,uti as CFString,1,nil) else { return nil }
        let options: CFDictionary? = quality.map { [kCGImageDestinationLossyCompressionQuality:$0] as CFDictionary }
        CGImageDestinationAddImage(destination,image,options)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return try? ClipboardImage(data:output as Data,mimeType:mimeType)
    }
    private func applyClipboard(_ event: ClipboardEvent) {
        guard clipboardSyncEnabled, state.isClipboardSyncEnabled, state.paired else { return }
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        guard pasteboard.setString(event.text,forType:.string) else { message = "Не удалось записать текст в буфер Mac."; return }
        lastPasteboardChangeCount = pasteboard.changeCount
        rememberClipboard(event.text)
        message = event.type == "link" ? "Ссылка получена с телефона." : "Текст получен с телефона."
    }
    private func applyClipboardImage(_ event: ClipboardImageEvent) -> Bool {
        guard clipboardSyncEnabled, state.isClipboardSyncEnabled, state.paired else { return false }
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        let type = NSPasteboard.PasteboardType(event.image.mimeType == "image/png" ? "public.png" : "public.jpeg")
        guard pasteboard.setData(event.image.data,forType:type) else { message = "Не удалось записать изображение в буфер Mac."; return false }
        lastPasteboardChangeCount = pasteboard.changeCount
        message = "Изображение получено с телефона."
        return true
    }
    private func saveClipboardHistory() {
        guard let id = state.pairHistoryID, id == loadedClipboardPairID else { return }
        do { try clipboardHistoryStore.save(clipboardHistory,pairID:id) }
        catch { message = "Не удалось сохранить историю буфера." }
    }
    private func rememberClipboard(_ text: String) {
        guard state.pairHistoryID != nil, state.pairHistoryID == loadedClipboardPairID, !text.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty, text.utf8.count <= 16384 else { return }
        clipboardHistory = Array(([text] + clipboardHistory.filter { $0 != text }).prefix(30))
        saveClipboardHistory()
    }
    func copyClipboardHistory(_ text: String) {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        guard pasteboard.setString(text,forType:.string) else { message = "Не удалось скопировать текст."; return }
        lastPasteboardChangeCount = pasteboard.changeCount
        rememberClipboard(text)
        message = "Скопировано из истории буфера."
    }
    func clearClipboardHistory() { clipboardHistory = []; saveClipboardHistory() }
    func connect() {
        do {
            guard let server = server, port != 0 else { throw BridgeError.invalid("Сеть ещё запускается") }
            let addresses = localAddresses(); guard !addresses.isEmpty else { throw BridgeError.invalid("Подключите Mac к Wi‑Fi") }
            let secret = try state.startInvitation()
            let data = try JSONSerialization.data(withJSONObject:["version":1,"name":state.name,"deviceId":state.deviceID,"hosts":addresses,"port":port,"fingerprint":server.identity.fingerprint,"invitation":secret], options:[.sortedKeys])
            guard let str = String(data:data,encoding:.utf8) else { throw BridgeError.invalid("Ошибка приглашения") }; invitation = str
            let filter = CIFilter.qrCodeGenerator(); filter.message = data; filter.correctionLevel = "M"
            if let output = filter.outputImage?.transformed(by:CGAffineTransform(scaleX:8,y:8)), let image = CIContext().createCGImage(output,from:output.extent) { qr = NSImage(cgImage:image,size:NSSize(width:250,height:250)) }
            message = "Отсканируйте код на Samsung. Он действует 2 минуты."
        } catch { fail(error) }
    }
    func respond(_ yes: Bool) { do { try state.approvePair(yes); invitation = ""; qr = nil; message = yes ? "Телефон подключён" : "Подключение отклонено" } catch { fail(error) } }
    func sendText() {
        do {
            let text = outgoingText; let url = URL(string:text)
            let type = ["http","https"].contains(url?.scheme?.lowercased() ?? "") ? "link" : "text"
            if bluetoothModeEnabled {
                guard let central = bluetoothCentral, central.isConnected else { throw BridgeError.invalid("Нет Bluetooth-соединения с телефоном") }
                let id = try state.addText(direction:"toPhone",type:type,text:text)
                let payload = try JSONSerialization.data(withJSONObject:["kind":"text.offer","id":id,"type":type,"text":text])
                outgoingText = ""; message = "Отправляю текст по Bluetooth…"
                DispatchQueue.global(qos:.userInitiated).async {
                    do {
                        let reply = try central.sendFileAndAwait(payload,id:id,phase:"text",timeout:120)
                        guard reply["state"] as? String == "accepted" else { throw BridgeError.invalid("Телефон не принял текст") }
                        try self.state.acknowledge(id,sha256:nil)
                        DispatchQueue.main.async { self.message = "Текст отправлен на телефон." }
                    } catch {
                        self.state.markFailed(id)
                        DispatchQueue.main.async { self.message = "Не удалось отправить текст: \(error.localizedDescription)" }
                    }
                }
            } else {
                try state.addText(direction:"toPhone",type:type,text:text); outgoingText = ""; message = "Отправлено на телефон"
            }
        } catch { fail(error) }
    }
    func chooseFile() {
        let panel = NSOpenPanel(); panel.canChooseFiles = true; panel.canChooseDirectories = false; panel.allowsMultipleSelection = true
        if panel.runModal() == .OK { sendFiles(panel.urls) }
    }
    func sendFiles(_ urls: [URL]) {
        guard state.paired else { message = "Сначала подключите телефон, затем добавляйте файлы."; return }
        let unique = urls.filter { $0.isFileURL }.reduce(into:[URL]()) { result, url in
            if !result.contains(url.standardizedFileURL) { result.append(url.standardizedFileURL) }
        }
        queuedFiles.append(contentsOf:unique.map { PendingFile(id:UUID().uuidString,url:$0,state:"waiting") })
        processFileQueue()
    }
    private func importFinderShareFiles() {
        guard state.paired else { return }
        do {
            for request in try finderShareInbox.pendingRequests() where !importedFinderShareRequestIDs.contains(request.manifest.requestID) {
                let files = request.files
                guard !files.isEmpty else { continue }
                importedFinderShareRequestIDs.insert(request.manifest.requestID)
                queuedFiles.append(contentsOf:files.map { url in
                    PendingFile(id:UUID().uuidString,url:url,state:"waiting",finderShareRequestID:request.manifest.requestID,finderShareFileName:url.lastPathComponent)
                })
                message = files.count == 1 ? "Файл из Finder добавлен в очередь передачи." : "Файлы из Finder добавлены в очередь передачи: \(files.count)."
            }
            processFileQueue()
        } catch {
            message = "Не удалось прочитать очередь Finder: \(error.localizedDescription)"
        }
    }
    private func cleanupFinderShareSources(_ transfers: [Transfer]) {
        let states = Dictionary(uniqueKeysWithValues:transfers.map { ($0.id,$0.state) })
        var changed = false
        for (transferID,path) in Array(finderShareSourcePaths) {
            guard let transferState = states[transferID], ["complete","cancelled","rejected"].contains(transferState) else { continue }
            finderShareInbox.removeStagedFile(at:path)
            finderShareSourcePaths.removeValue(forKey:transferID)
            changed = true
        }
        if changed { UserDefaults.standard.set(finderShareSourcePaths,forKey:"bridge.finderShareSourcePaths") }
    }
    func enqueueFileProviders(_ providers: [NSItemProvider]) {
        guard state.paired, !providers.isEmpty else { message = "Сначала подключите телефон, чтобы отправить файлы."; return }
        let group = DispatchGroup()
        let lock = NSLock()
        var ordered = Array<URL?>(repeating:nil,count:providers.count)
        for (index, provider) in providers.enumerated() {
            group.enter()
            _ = provider.loadObject(ofClass:URL.self) { object, _ in
                lock.lock(); ordered[index] = object; lock.unlock()
                group.leave()
            }
        }
        group.notify(queue:.main) { self.sendFiles(ordered.compactMap { $0 }) }
    }
    func removeQueuedFile(_ id: String) {
        if preparingFileID == id { cancelledQueuedFileIDs.insert(id) }
        if let file = queuedFiles.first(where: { $0.id == id }), let requestID = file.finderShareRequestID {
            let done = finderShareInbox.acknowledgeFile(requestID:requestID,name:file.finderShareFileName ?? file.name)
            finderShareInbox.removeStagedFile(at:file.url.path)
            if done { importedFinderShareRequestIDs.remove(requestID) }
        }
        queuedFiles.removeAll { $0.id == id }
    }
    func retryQueuedFile(_ id: String) {
        guard let index = queuedFiles.firstIndex(where: { $0.id == id }) else { return }
        queuedFiles[index].state = "waiting"; queuedFiles[index].error = nil
        processFileQueue()
    }
    private func processFileQueue() {
        guard !preparingFile, state.paired, let index = queuedFiles.firstIndex(where: { $0.state == "waiting" }) else { return }
        let active = state.transfers.contains { $0.type == "file" && ["offered", "accepted", "transferring"].contains($0.state) }
        guard !active else { return }
        queuedFiles[index].state = "preparing"
        let entry = queuedFiles[index]; preparingFile = true; preparingFileID = entry.id
        message = "Подготовка файла «\(entry.name)»…"
        DispatchQueue.global(qos:.userInitiated).async {
            do {
                let (size,hash) = try BridgeValidation.hash(file:entry.url)
                DispatchQueue.main.async {
                    self.preparingFile = false; self.preparingFileID = nil
                    if self.cancelledQueuedFileIDs.remove(entry.id) != nil { self.processFileQueue(); return }
                    guard self.state.paired else { self.queuedFiles.removeAll(); return }
                    if self.state.transfers.contains(where: { $0.type == "file" && ["offered", "accepted", "transferring"].contains($0.state) }) {
                        if let index = self.queuedFiles.firstIndex(where: { $0.id == entry.id }) { self.queuedFiles[index].state = "waiting" }
                        return
                    }
                    do {
                        let transferID = try self.state.offerFile(direction:"toPhone",name:entry.name,size:size,sha256:hash,file:entry.url)
                        self.queuedFiles.removeAll { $0.id == entry.id }
                        if let requestID = entry.finderShareRequestID {
                            self.finderShareSourcePaths[transferID] = entry.url.path
                            UserDefaults.standard.set(self.finderShareSourcePaths,forKey:"bridge.finderShareSourcePaths")
                            let requestFinished = self.finderShareInbox.acknowledgeFile(requestID:requestID,name:entry.finderShareFileName ?? entry.name)
                            if requestFinished { self.importedFinderShareRequestIDs.remove(requestID) }
                        }
                        if self.cancelledQueuedFileIDs.remove(entry.id) != nil { try? self.state.cancel(transferID) }
                        if self.bluetoothModeEnabled {
                            self.message = "Отправляю «\(entry.name)» по Bluetooth на телефон."
                            DispatchQueue.global(qos:.userInitiated).async { self.sendBluetoothFileToPhone(id:transferID,url:entry.url,name:entry.name,size:size,sha256:hash) }
                            return
                        }
                        self.message = "Ожидание подтверждения на телефоне"
                        self.processFileQueue()
                    } catch {
                        if let index = self.queuedFiles.firstIndex(where: { $0.id == entry.id }) { self.queuedFiles[index].state = "failed"; self.queuedFiles[index].error = error.localizedDescription }
                        self.fail(error); self.processFileQueue()
                    }
                }
            } catch {
                DispatchQueue.main.async {
                    self.preparingFile = false; self.preparingFileID = nil
                    if self.cancelledQueuedFileIDs.remove(entry.id) == nil, let index = self.queuedFiles.firstIndex(where: { $0.id == entry.id }) {
                        self.queuedFiles[index].state = "failed"; self.queuedFiles[index].error = error.localizedDescription
                    }
                    self.fail(error); self.processFileQueue()
                }
            }
        }
    }

    private func sendBluetoothFileToPhone(id: String, url: URL, name: String, size: Int64, sha256: String) {
        guard let central = bluetoothCentral else { return }
        do {
            let decision = try central.sendFileAndAwait(BluetoothFileMessage.offer(id:id,name:name,size:size,sha256:sha256),id:id,phase:"decision")
            guard (decision["state"] as? String) == "accepted" else { throw BridgeError.invalid("Файл отклонён на телефоне") }
            try state.accept(id)
            let (_,source) = try state.beginFile(id,direction:"toPhone",length:size)
            let handle = try FileHandle(forReadingFrom:source)
            defer { try? handle.close() }
            var sent: Int64 = 0
            while sent < size {
                let bytes = try handle.read(upToCount:BluetoothFileMessage.chunkBytes) ?? Data()
                guard !bytes.isEmpty else { throw BridgeError.invalid("Исходный файл закончился раньше ожидаемого") }
                let next = sent + Int64(bytes.count)
                let reply = try central.sendFileAndAwait(BluetoothFileMessage.chunk(id:id,offset:sent,data:bytes),id:id,phase:"chunk")
                guard (reply["state"] as? String) == "accepted", (reply["nextOffset"] as? Int64) == next else { throw BridgeError.invalid("Телефон не подтвердил часть файла") }
                sent = next
                try state.updateProgress(id,sent)
            }
            let (actualSize,actualHash) = try BridgeValidation.hash(file:source)
            guard actualSize == size, actualHash == sha256 else { throw BridgeError.invalid("Исходный файл изменился во время передачи") }
            let result = try central.sendFileAndAwait(BluetoothFileMessage.finish(id:id,size:size,sha256:sha256),id:id,phase:"result")
            guard (result["state"] as? String) == "complete", (result["sha256"] as? String) == sha256 else { throw BridgeError.invalid("Телефон не подтвердил сохранение файла") }
            try state.acknowledge(id,sha256:sha256)
            DispatchQueue.main.async { self.message = "Файл «\(name)» передан по Bluetooth." }
        } catch {
            state.markFailed(id)
            DispatchQueue.main.async {
                self.message = "Передача «\(name)» по Bluetooth не завершена: \(error.localizedDescription)"
                self.refresh()
            }
            DispatchQueue.main.async { try? central.send(BluetoothFileMessage.cancel(id:id)) }
        }
    }

    func accept(_ t: Transfer) {
        let p = NSOpenPanel(); p.canChooseDirectories = true; p.canChooseFiles = false; p.allowsMultipleSelection = false; p.prompt = "Сохранить сюда"
        if p.runModal() == .OK, let url = p.url {
            do {
                try state.accept(t.id,destination:url)
                if bluetoothModeEnabled && t.direction == "toMac" {
                    try prepareBluetoothReceive(t.id)
                    try bluetoothCentral?.send(BluetoothFileMessage.reply(id:t.id,phase:"decision",state:"accepted"))
                    message = "Принимаю «\(t.name)» по Bluetooth…"
                } else { message = "Ожидание файла" }
            } catch {
                if bluetoothModeEnabled && t.direction == "toMac" {
                    try? bluetoothCentral?.send(BluetoothFileMessage.reply(id:t.id,phase:"decision",state:"rejected"))
                    state.markFailed(t.id)
                }
                fail(error)
            }
        }
    }
    func setAutoAcceptIncomingFiles(_ enabled: Bool) {
        autoAcceptIncomingFiles = enabled
        UserDefaults.standard.set(enabled,forKey:"bridge.autoAcceptIncomingFiles")
        if enabled {
            UNUserNotificationCenter.current().requestAuthorization(options:[.alert,.sound]) { [weak self] granted, error in
                DispatchQueue.main.async {
                    guard let self = self else { return }
                    if !granted { self.message = "Автоприём включён. Уведомления macOS отключены — файлы останутся в истории передач." }
                    else if let error = error { self.message = "Не удалось запросить уведомления: \(error.localizedDescription)" }
                }
            }
            acceptIncomingFilesAutomatically(state.transfers)
        }
    }
    private func acceptIncomingFilesAutomatically(_ candidates: [Transfer]) {
        guard autoAcceptIncomingFiles else { return }
        for transfer in candidates where transfer.type == "file" && transfer.direction == "toMac" && transfer.state == "offered" {
            guard autoAcceptingFileIDs.insert(transfer.id).inserted else { continue }
            defer { autoAcceptingFileIDs.remove(transfer.id) }
            do {
                let folder = try receivedFilesDirectory()
                try state.accept(transfer.id,destination:folder)
                autoAcceptedFileIDs.insert(transfer.id)
                if bluetoothModeEnabled {
                    try prepareBluetoothReceive(transfer.id)
                    try bluetoothCentral?.send(BluetoothFileMessage.reply(id:transfer.id,phase:"decision",state:"accepted"))
                    message = "Файл «\(transfer.name)» принимается автоматически по Bluetooth."
                } else { message = "Файл «\(transfer.name)» принимается автоматически в Документы/AndroidMacBridge." }
            } catch {
                if bluetoothModeEnabled {
                    try? bluetoothCentral?.send(BluetoothFileMessage.reply(id:transfer.id,phase:"decision",state:"rejected"))
                    try? state.cancel(transfer.id)
                }
                message = "Не удалось автоматически принять файл «\(transfer.name)»: \(error.localizedDescription)"
            }
        }
    }
    func cancel(_ t: Transfer) {
        do {
            try state.cancel(t.id)
            if bluetoothModeEnabled, let central = bluetoothCentral {
                if t.direction == "toMac" && t.state == "offered" {
                    try central.send(BluetoothFileMessage.reply(id:t.id,phase:"decision",state:"rejected"))
                } else {
                    try central.send(BluetoothFileMessage.cancel(id:t.id))
                }
                if let session = bluetoothIncomingFiles.removeValue(forKey:t.id) {
                    try? session.handle.close(); try? FileManager.default.removeItem(at:session.temporaryURL)
                }
            }
        } catch { fail(error) }
    }
    func copy(_ value: String) { NSPasteboard.general.clearContents(); NSPasteboard.general.setString(value,forType:.string); message = "Скопировано" }
    func open(_ value: String) { if let url = URL(string:value), ["http","https"].contains(url.scheme?.lowercased() ?? "") { NSWorkspace.shared.open(url) } }
    func unpair() { queuedFiles.removeAll(); setClipboardSyncEnabled(false); state.unpair(); qr = nil; invitation = ""; message = "Пара удалена" }
}
private struct AppLanguageOption: Identifiable {
    let tag: String
    let name: String
    var id: String { tag }
}

struct ContentView: View {
    @ObservedObject var model: ViewModel
    @AppStorage("appLanguage") private var appLanguage = "en"
    @State private var section = 0
    @State private var showingText = false
    @State private var confirmingUnpair = false
    @State private var confirmingClipboardClear = false
    @State private var isDropTargeted = false
    private let blue = Color(red:0.04,green:0.43,blue:0.98)
    private let ink = Color(red:0.08,green:0.12,blue:0.20)
    private let muted = Color(red:0.50,green:0.57,blue:0.69)
    private let appLanguages: [AppLanguageOption] = [
        .init(tag:"en",name:"English"), .init(tag:"ru",name:"Русский"), .init(tag:"es",name:"Español"), .init(tag:"fr",name:"Français"),
        .init(tag:"de",name:"Deutsch"), .init(tag:"it",name:"Italiano"), .init(tag:"pt-BR",name:"Português (Brasil)"),
        .init(tag:"tr",name:"Türkçe"), .init(tag:"pl",name:"Polski"), .init(tag:"uk",name:"Українська"),
        .init(tag:"zh-Hans",name:"简体中文"), .init(tag:"ja",name:"日本語"), .init(tag:"ko",name:"한국어"),
        .init(tag:"ar",name:"العربية"), .init(tag:"hi",name:"हिन्दी"), .init(tag:"id",name:"Bahasa Indonesia"), .init(tag:"nl",name:"Nederlands"),
        .init(tag:"da",name:"Dansk"), .init(tag:"sv",name:"Svenska"), .init(tag:"nb",name:"Norsk bokmål"), .init(tag:"fi",name:"Suomi"),
        .init(tag:"el",name:"Ελληνικά"), .init(tag:"cs",name:"Čeština"), .init(tag:"sk",name:"Slovenčina"), .init(tag:"hu",name:"Magyar"),
        .init(tag:"ro",name:"Română"), .init(tag:"bg",name:"Български"), .init(tag:"hr",name:"Hrvatski"), .init(tag:"sr",name:"Српски"),
        .init(tag:"sl",name:"Slovenščina"), .init(tag:"et",name:"Eesti"), .init(tag:"lv",name:"Latviešu"), .init(tag:"lt",name:"Lietuvių"),
        .init(tag:"he",name:"עברית"), .init(tag:"th",name:"ไทย"), .init(tag:"vi",name:"Tiếng Việt"), .init(tag:"ms",name:"Bahasa Melayu"),
        .init(tag:"bn",name:"বাংলা"), .init(tag:"ta",name:"தமிழ்"), .init(tag:"te",name:"తెలుగు"), .init(tag:"ur",name:"اردو"),
        .init(tag:"fa",name:"فارسی"), .init(tag:"sw",name:"Kiswahili"), .init(tag:"fil",name:"Filipino"), .init(tag:"ca",name:"Català"),
        .init(tag:"eu",name:"Euskara"), .init(tag:"gl",name:"Galego"), .init(tag:"is",name:"Íslenska"), .init(tag:"bs",name:"Bosanski"),
        .init(tag:"mk",name:"Македонски")
    ]

    private func displayState(_ state: String) -> String {
        switch state {
        case "offered": return "Ожидает решения"
        case "accepted": return "Принято"
        case "transferring": return "Передаётся"
        case "complete": return "Завершено"
        case "rejected": return "Отклонено"
        case "cancelled": return "Отменено"
        case "failed": return "Ошибка"
        case "unknown": return "Результат неизвестен — проверьте файл на принимающем устройстве"
        default: return state
        }
    }
    private var displayMessage: String {
        guard model.message == "Ожидание подтверждения на телефоне",
              let latest = model.transfers.last(where: { $0.type == "file" && $0.direction == "toPhone" }) else { return model.message }
        switch latest.state {
        case "offered": return "Ожидание решения на телефоне"
        case "accepted", "transferring": return "Передача файла на телефон…"
        case "complete": return "Файл отправлен на телефон"
        case "cancelled": return "Передача отменена"
        case "failed", "rejected": return "Не удалось отправить файл на телефон"
        default: return model.message
        }
    }
    private var connectionDescription: String {
        guard model.paired else { return "Телефон не сопряжён" }
        if model.bluetoothModeEnabled { return model.bluetoothConnected ? "Подключено по Bluetooth" : model.bluetoothStatus }
        guard model.connected else { return "Сервер Mac недоступен" }
        guard let last = model.lastPhoneContact else { return "Пара сохранена · связь ещё не подтверждена" }
        return "Последняя связь: " + last.formatted(date:.omitted,time:.shortened)
    }
    private var clipboardBinding: Binding<Bool> {
        Binding(get:{model.clipboardSyncEnabled},set:{model.setClipboardSyncEnabled($0)})
    }
    private var bluetoothModeBinding: Binding<Bool> {
        Binding(get:{model.bluetoothModeEnabled},set:{model.setBluetoothModeEnabled($0)})
    }
    private var autoSendMacScreenshotsBinding: Binding<Bool> {
        Binding(get:{model.autoSendMacScreenshots},set:{model.setAutoSendMacScreenshots($0)})
    }
    private var autoAcceptIncomingFilesBinding: Binding<Bool> {
        Binding(get:{model.autoAcceptIncomingFiles},set:{model.setAutoAcceptIncomingFiles($0)})
    }
    private var loginBinding: Binding<Bool> {
        Binding(get:{model.launchAtLoginEnabled},set:{model.setLaunchAtLogin($0)})
    }
    private func sidebarButton(_ label: String, icon: String, index: Int) -> some View {
        Button { section = index } label: {
            HStack(spacing:10) {
                Image(systemName:index == 0 && section == index ? "house.fill" : icon).font(.system(size:17,weight:.medium)).foregroundColor(section == index ? blue : muted).frame(width:20)
                Text(LocalizedStringKey(label)).font(.system(size:12,weight:section == index ? .medium : .regular)).foregroundColor(section == index ? blue : ink)
            }.frame(maxWidth:.infinity,alignment:.leading).padding(.vertical,8).padding(.horizontal,8)
                .background(section == index ? Color(red:0.84,green:0.90,blue:0.99) : Color.clear).cornerRadius(7)
                .contentShape(Rectangle())
        }.buttonStyle(.plain)
    }
    private var sidebar: some View {
        VStack(alignment:.leading,spacing:4) {
            sidebarButton("Главная",icon:"house",index:0)
            sidebarButton("Передачи",icon:"clock",index:1)
            sidebarButton("Буфер",icon:"doc.on.doc",index:2)
            sidebarButton("Настройки",icon:"gearshape",index:3)
            Spacer()
        }.padding(.horizontal,10).padding(.top,44).frame(width:140).frame(maxHeight:.infinity,alignment:.top).background(Color(red:0.92,green:0.95,blue:0.98))
    }
    private var deviceCard: some View {
        HStack(spacing:18) {
            ZStack {
                RoundedRectangle(cornerRadius:5).fill(Color(red:0.05,green:0.08,blue:0.12)).frame(width:30,height:58).shadow(color:.black.opacity(0.18),radius:2,y:1)
                ZStack {
                    LinearGradient(colors:[Color(red:0.34,green:0.53,blue:0.92),Color(red:0.30,green:0.29,blue:0.65),Color(red:0.72,green:0.62,blue:0.82)],startPoint:.topLeading,endPoint:.bottomTrailing)
                    Ellipse().fill(LinearGradient(colors:[Color(red:0.76,green:0.85,blue:1),Color(red:0.62,green:0.66,blue:0.94),Color(red:0.98,green:0.79,blue:0.77)],startPoint:.topLeading,endPoint:.bottomTrailing)).frame(width:24,height:66).rotationEffect(.degrees(29)).offset(x:-12,y:17)
                    RoundedRectangle(cornerRadius:3).strokeBorder(Color.white.opacity(0.18),lineWidth:0.6)
                }.frame(width:25,height:52).clipShape(RoundedRectangle(cornerRadius:3))
                Capsule().fill(Color.black).frame(width:8,height:3).offset(y:-24)
            }.frame(width:48).accessibilityLabel("Телефон Android")
            VStack(alignment:.leading,spacing:4) {
                Text(phoneModelLabel(model.phoneModel)).font(.system(size:13,weight:.semibold))
                TimelineView(.periodic(from: Date(), by: 5)) { context in
                    let live = model.bluetoothModeEnabled ? model.bluetoothConnected : (model.connected && model.lastPhoneContact.map { context.date.timeIntervalSince($0) < 15 } == true)
                    HStack(spacing:5) {
                        if live { Circle().fill(Color(red:0.11,green:0.68,blue:0.29)).frame(width:8,height:8) }
                        Text(LocalizedStringKey(model.bluetoothModeEnabled ? connectionDescription : (live ? "Подключено" : connectionDescription))).foregroundColor(live ? Color(red:0.11,green:0.65,blue:0.28) : muted)
                    }.font(.system(size:11))
                }
            }
            Spacer()
            if !model.paired { Button("Подключить") { model.connect() }.disabled(!model.connected) }
            else { Button { section = 3 } label: { Image(systemName:"chevron.right").foregroundColor(.secondary) }.buttonStyle(.plain).accessibilityLabel("Настройки подключения") }
        }.padding(.horizontal,16).frame(maxWidth:.infinity,minHeight:92,alignment:.leading).background(Color.white).cornerRadius(10)
    }
    private var pairing: some View {
        VStack(alignment:.leading,spacing:10) {
            if let prompt = model.prompt {
                HStack { Text("Разрешить подключение «\(prompt)»?"); Spacer(); Button("Отклонить") { model.respond(false) }; Button("Разрешить") { model.respond(true) }.buttonStyle(.borderedProminent) }.padding(10).background(Color.accentColor.opacity(0.1)).cornerRadius(8)
            }
            if let qr = model.qr {
                HStack { Image(nsImage:qr).interpolation(.none).resizable().frame(width:160,height:160); VStack(alignment:.leading,spacing:8) { Text("Отсканируйте код приложением на Samsung."); Text("Устройства должны быть в одной сети Wi‑Fi.").foregroundColor(.secondary); Button("Скопировать код") { model.copy(model.invitation) } } }.padding(8)
            }
        }
    }
    private var actions: some View {
        VStack(alignment:.leading,spacing:10) {
            HStack(spacing:8) {
                actionButton("Файл",icon:"doc",enabled:model.paired) { model.chooseFile() }
                actionButton("Текст",icon:"text.alignleft",enabled:model.paired) { showingText.toggle() }
                actionButton("Буфер",icon:"doc.on.doc",enabled:true) { section = 2 }.help("История и настройки общего буфера")
            }
            if showingText && model.paired {
                HStack { TextField("Текст или ссылка для Samsung",text:$model.outgoingText).textFieldStyle(.roundedBorder); Button("Отправить") { model.sendText() }.disabled(model.outgoingText.isEmpty) }
            }
            if model.paired {
                Button { model.chooseFile() } label: {
                    HStack(spacing:12) { Image(systemName:"doc").font(.system(size:25)).foregroundColor(.secondary); VStack(alignment:.leading,spacing:4) { Text(isDropTargeted ? "Отпустите файлы для отправки" : "Перетащите файлы сюда").foregroundColor(.primary); Text(model.queuedFileCount > 0 ? "В очереди: \(model.queuedFileCount)" : "Для быстрой передачи на телефон").foregroundColor(.secondary) } }.font(.system(size:11)).frame(maxWidth:.infinity,minHeight:88).background(isDropTargeted ? Color(red:0.88,green:0.93,blue:1.0) : Color.white.opacity(0.65)).overlay(RoundedRectangle(cornerRadius:8).strokeBorder(style:StrokeStyle(lineWidth:1,dash:[4,3])).foregroundColor(isDropTargeted ? blue : .secondary.opacity(0.45)))
                }.buttonStyle(.plain).onDrop(of:["public.file-url"],isTargeted:$isDropTargeted) { providers in
                    guard model.paired, !providers.isEmpty else { return false }
                    model.enqueueFileProviders(providers)
                    return true
                }
            }
        }
    }
    private func actionButton(_ title: String, icon: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action:action) {
            HStack(spacing:10) { Image(systemName:icon).font(.system(size:18,weight:.regular)).foregroundColor(blue); Text(LocalizedStringKey(title)).foregroundColor(ink) }
                .font(.system(size:12,weight:.medium)).frame(maxWidth:.infinity,minHeight:48)
                .background(Color.white).cornerRadius(9)
        }.buttonStyle(.plain).disabled(!enabled).opacity(enabled ? 1 : 0.5)
    }
    private func transferRow(_ t: Transfer) -> some View {
        VStack(alignment:.leading,spacing:6) {
        HStack(alignment:.center,spacing:10) {
            Group {
                if let preview = model.previews[t.id], t.type == "file" { Image(nsImage:preview).resizable().scaledToFill() }
                else { Image(systemName:t.type == "file" ? (t.name.lowercased().hasSuffix(".pdf") ? "doc.richtext" : "doc") : t.type == "link" ? "link" : "text.alignleft").foregroundColor(t.type == "file" && t.name.lowercased().hasSuffix(".pdf") ? .white : blue).font(.system(size:16)).frame(maxWidth:.infinity,maxHeight:.infinity) }
            }.frame(width:34,height:34).background(t.type == "file" && t.name.lowercased().hasSuffix(".pdf") && model.previews[t.id] == nil ? Color(red:0.94,green:0.17,blue:0.18) : Color(red:0.90,green:0.94,blue:0.99)).clipShape(RoundedRectangle(cornerRadius:6))
            VStack(alignment:.leading,spacing:4) {
                if t.type == "file" {
                    Text(t.name).font(.system(size:12,weight:.medium)).lineLimit(1).truncationMode(.middle).textSelection(.enabled)
                } else {
                    Text(LocalizedStringKey(t.type == "link" ? "Ссылка" : "Текст")).font(.system(size:12,weight:.medium)).lineLimit(1).truncationMode(.middle).textSelection(.enabled)
                }
                if t.type != "file" && !t.text.isEmpty { Text(t.text).lineLimit(2).font(.system(size:11)).textSelection(.enabled) }
                if t.type != "file" || t.state == "complete" {
                    Text((t.type == "file" ? transferSize(t.size) + " · " : "") + (Calendar.current.isDateInToday(Date(timeIntervalSince1970:t.createdAt)) ? "Сегодня, " + Date(timeIntervalSince1970:t.createdAt).formatted(date:.omitted,time:.shortened) : Date(timeIntervalSince1970:t.createdAt).formatted(date:.abbreviated,time:.shortened))).font(.system(size:10)).foregroundColor(muted)
                }
                if t.type == "file" && ["accepted","transferring"].contains(t.state) {
                    Text("\(transferProgressValue(t.progress,total:t.size)) из \(transferProgressValue(t.size,total:t.size))").font(.system(size:10)).foregroundColor(muted)
                    if let detail = model.progressDetails[t.id] { Text(detail).font(.system(size:10)).foregroundColor(muted).lineLimit(1) }
                    ProgressView(value:Double(max(0,t.progress)),total:Double(max(1,t.size))).progressViewStyle(.linear).tint(blue).frame(height:5)
                }
            }.frame(maxWidth:.infinity,alignment:.leading)
            HStack(spacing:10) {
                if t.state == "complete" {
                    Image(systemName:"checkmark").font(.system(size:11,weight:.bold)).foregroundColor(Color(red:0.10,green:0.62,blue:0.26)).frame(width:24,height:24).background(Color(red:0.88,green:0.96,blue:0.90)).clipShape(Circle())
                    VStack(alignment:.leading,spacing:3) {
                        Text(LocalizedStringKey(t.direction == "toMac" ? "Получено" : "Отправлено")).foregroundColor(.primary)
                        Text(LocalizedStringKey(t.direction == "toMac" ? "С телефона" : "На телефон")).foregroundColor(muted)
                        if t.direction == "toMac" && t.type != "file" && !t.text.isEmpty {
                            Button("Копировать") { model.copy(t.text) }.controlSize(.small)
                        }
                    }.font(.system(size:10))
                } else if t.type == "file" && ["accepted","transferring"].contains(t.state) {
                    Text(LocalizedStringKey(t.direction == "toPhone" ? "↑ Отправка" : "↓ Получение"))
                        .font(.system(size:10,weight:.medium)).foregroundColor(blue)
                        .padding(.horizontal,8).padding(.vertical,5)
                        .background(Color(red:0.91,green:0.95,blue:1.0)).clipShape(Capsule())
                } else { Text(LocalizedStringKey(displayState(t.state))).font(.system(size:10)).foregroundColor(.secondary).lineLimit(2) }
            }.frame(width:120,alignment:.leading)
            ZStack { Color.clear; if t.type == "file" || !t.text.isEmpty {
                Menu {
                    if t.type == "file" { Button("Показать в Finder") { model.revealFile(t) }; Button("Копировать название") { model.copy(t.name) } }
                    else { Button("Копировать") { model.copy(t.text) }; if t.type == "link" { Button("Открыть") { model.open(t.text) } } }
                } label: { Image(systemName:"ellipsis").foregroundColor(muted) }.menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize().help("Действия с передачей")
            } }.frame(width:36,height:28,alignment:.center)
        }
        if (t.direction == "toMac" && t.type == "file" && t.state == "offered") || (t.direction == "toMac" && t.type == "link" && !t.text.isEmpty) || (t.type == "file" && ["accepted","transferring","offered"].contains(t.state) && !(t.direction == "toMac" && t.state == "offered")) {
        HStack {
            if t.direction == "toMac" && t.type == "file" && t.state == "offered" { Button("Принять") { model.accept(t) }; Button("Отклонить") { model.cancel(t) } }
            if t.direction == "toMac" && t.type == "link" && !t.text.isEmpty { Button("Открыть") { model.open(t.text) } }
            if t.type == "file" && ["accepted","transferring","offered"].contains(t.state) && !(t.direction == "toMac" && t.state == "offered") { Button("Отмена") { model.cancel(t) } }
        }
        }
        }.padding(.horizontal,10).padding(.vertical,7).frame(maxWidth:.infinity,minHeight:52,alignment:.leading)
    }
    private var transfers: some View {
        VStack(alignment:.leading,spacing:8) {
            if section != 1 {
                HStack { Text(section == 0 ? "Недавние передачи" : "Передачи").font(.system(size:13,weight:.semibold)); Spacer(); if section == 0 { Button { section = 1 } label: { Image(systemName:"chevron.right").foregroundColor(.secondary) }.buttonStyle(.plain).accessibilityLabel("Все передачи") } }
            }
            if !model.queuedFiles.isEmpty {
                VStack(spacing:0) {
                    ForEach(model.queuedFiles) { item in
                        HStack(spacing:8) {
                            Image(systemName:"doc").foregroundColor(blue).frame(width:22)
                            VStack(alignment:.leading,spacing:3) {
                                Text(item.name).lineLimit(1).truncationMode(.middle).textSelection(.enabled)
                                Text(LocalizedStringKey(item.state == "preparing" ? "Подготовка…" : item.state == "failed" ? "Ошибка · \(item.error ?? "Не удалось подготовить файл")" : "В очереди"))
                                    .font(.system(size:10)).foregroundColor(item.state == "failed" ? Color(red:0.68,green:0.30,blue:0.36) : muted).lineLimit(2)
                            }
                            Spacer(minLength:4)
                            if item.state == "failed" { Button("Повторить") { model.retryQueuedFile(item.id) }.controlSize(.small) }
                            Button { model.removeQueuedFile(item.id) } label: { Image(systemName:"xmark.circle.fill").foregroundColor(muted) }
                                .buttonStyle(.plain).help(item.state == "preparing" ? "Отменить подготовку" : "Убрать из очереди")
                        }.font(.system(size:11)).padding(.horizontal,10).padding(.vertical,8)
                        Divider().padding(.leading,38)
                    }
                }.background(Color.white).cornerRadius(9)
            }
            if model.transfers.isEmpty && model.queuedFiles.isEmpty { Text("Пока нет передач").foregroundColor(.secondary).padding(.vertical,8) }
            VStack(spacing:0) {
                ForEach(section == 0 ? Array(model.transfers.reversed().filter { ["offered","accepted","transferring","unknown"].contains($0.state) }) + Array(model.transfers.reversed().filter { !["offered","accepted","transferring","unknown"].contains($0.state) }.prefix(3)) : Array(model.transfers.reversed())) { t in
                    transferRow(t)
                    Divider().padding(.leading,50)
                }
            }.background(Color.white).cornerRadius(9)
        }
    }
    private var clipboardPage: some View {
        VStack(alignment:.leading,spacing:12) {
            Toggle("Общий буфер обмена",isOn:clipboardBinding).disabled(!model.paired)
            Text("История хранится на этом Mac. Нажмите на запись, чтобы скопировать её.").font(.caption).foregroundColor(.secondary)
            Text("История буфера").font(.system(size:13,weight:.semibold))
            if model.clipboardHistory.isEmpty { Text("История пока пуста").foregroundColor(.secondary).padding(14).frame(maxWidth:.infinity,alignment:.leading).background(Color.white).cornerRadius(9) }
            else {
                VStack(spacing:0) {
                    ForEach(model.clipboardHistory,id:\.self) { text in
                        Button { model.copyClipboardHistory(text) } label: {
                            Text(text).lineLimit(3).frame(maxWidth:.infinity,alignment:.leading).padding(12).contentShape(Rectangle())
                        }.buttonStyle(.plain).help("Скопировать")
                        Divider()
                    }
                }.background(Color.white).cornerRadius(9)
            }
        }.frame(maxWidth:.infinity,alignment:.leading)
            .alert("Очистить историю буфера на Mac?",isPresented:$confirmingClipboardClear) {
                Button("Очистить",role:.destructive) { model.clearClipboardHistory() }; Button("Отмена",role:.cancel) { }
            }
    }
    private func settingsCard<Content: View>(title: String, icon: String? = nil, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment:.leading,spacing:11) {
            HStack(spacing: icon == nil ? 0 : 9) {
                if let icon {
                    Image(systemName:icon)
                        .font(.system(size:13,weight:.semibold))
                        .foregroundColor(blue)
                        .frame(width:30,height:30)
                        .background(Color(red:0.91,green:0.95,blue:1),in:RoundedRectangle(cornerRadius:9))
                }
                Text(LocalizedStringKey(title)).font(.system(size:13,weight:.semibold)).foregroundColor(ink)
            }
            content().padding(.leading, icon == nil ? 0 : 4)
        }
        .padding(15)
        .frame(maxWidth:.infinity,alignment:.leading)
        .background(Color.white,in:RoundedRectangle(cornerRadius:14))
        .overlay(RoundedRectangle(cornerRadius:14).stroke(Color(red:0.89,green:0.91,blue:0.94),lineWidth:1))
    }
    private var settings: some View {
        VStack(alignment:.leading,spacing:12) {
            settingsCard(title:"Язык",icon:"globe") {
                Picker("App language",selection:$appLanguage) {
                    ForEach(appLanguages) { language in
                        Text(language.name).tag(language.tag)
                    }
                }.pickerStyle(.menu)
                Text(LocalizedStringKey("Выбранный язык применяется только к AndroidMacBridge и сохраняется на этом Mac."))
                    .font(.caption).foregroundColor(.secondary)
                Text(LocalizedStringKey("Системные запросы разрешений отображаются на языке устройства."))
                    .font(.caption).foregroundColor(.secondary)
            }
            settingsCard(title:"Общий буфер") {
                Toggle("Общий буфер обмена",isOn:clipboardBinding).disabled(!model.paired)
                Text(LocalizedStringKey("Синхронизирует текст, ссылки и изображения с сопряжённым телефоном."))
                    .font(.caption).foregroundColor(.secondary)
            }
            settingsCard(title:"Подключение") {
                Toggle("Bluetooth без общей сети Wi‑Fi",isOn:bluetoothModeBinding).disabled(!model.paired)
                Text("Прямой защищённый канал между Mac и телефоном. Bluetooth должен быть включён на обоих устройствах; передача работает на расстоянии Bluetooth.")
                    .font(.caption).foregroundColor(.secondary)
                Text(LocalizedStringKey(model.bluetoothStatus))
                    .font(.caption).foregroundColor(model.bluetoothConnected ? .green : .secondary)
            }
            settingsCard(title:"Файлы и снимки") {
                Toggle("Автоотправка снимков Mac",isOn:autoSendMacScreenshotsBinding)
                    .disabled(!model.paired || !model.clipboardSyncEnabled)
                Text("Новые снимки из папки снимков macOS автоматически появятся в буфере телефона. Старые файлы не отправляются.")
                    .font(.caption).foregroundColor(.secondary)
                Divider()
                Toggle("Принимать файлы автоматически",isOn:autoAcceptIncomingFilesBinding).disabled(!model.paired)
                Text("Входящие файлы от сопряжённого телефона будут сохраняться в Документы/AndroidMacBridge без запроса подтверждения.")
                    .font(.caption).foregroundColor(.secondary)
            }
            settingsCard(title:"Запуск приложения") {
                Toggle("Запускать приложение при входе",isOn:loginBinding)
                if model.loginItemNeedsApproval {
                    Text("Разрешите запуск в Системных настройках → Основные → Объекты входа.")
                        .font(.caption).foregroundColor(.secondary)
                }
                if model.paired { Button("Удалить пару",role:.destructive) { confirmingUnpair = true } }
                else { Button("Подключить телефон") { model.connect() }.disabled(!model.connected) }
            }
        }.frame(maxWidth:.infinity,alignment:.leading)
    }
    var body: some View {
        HStack(spacing:0) {
            sidebar
            ScrollView {
                    VStack(alignment:.leading,spacing:10) {
                    HStack {
                        Text(LocalizedStringKey(section == 0 ? "AndroidMacBridge" : section == 1 ? "Передачи" : section == 2 ? "Буфер" : "Настройки")).font(.system(size:16,weight:.semibold))
                        Spacer()
                        if section == 1 {
                            Button("Открыть папку") { model.openReceivedFolder() }
                            Button("Очистить историю") { model.state.clearHistory() }
                        }
                        if section == 2 { Button("Очистить историю",role:.destructive) { confirmingClipboardClear = true }.disabled(model.clipboardHistory.isEmpty) }
                    }.frame(minHeight:24)
                    if section == 0 { deviceCard }
                    if displayMessage != "Ожидание телефона в локальной сети" { Text(LocalizedStringKey(displayMessage)).font(.system(size:10)).foregroundColor(.secondary).textSelection(.enabled) }
                    pairing
                    if section == 0 { actions; transfers }
                    else if section == 1 { transfers }
                    else if section == 2 { clipboardPage; if model.transfers.contains(where: { $0.direction == "toMac" && $0.type == "file" && $0.state == "offered" }) { transfers } }
                    else { settings; if model.transfers.contains(where: { $0.direction == "toMac" && $0.type == "file" && $0.state == "offered" }) { transfers } }
                }.padding(.horizontal,14).padding(.top,9).padding(.bottom,14).frame(maxWidth:.infinity,alignment:.leading)
            }.background(Color(red:0.95,green:0.96,blue:0.98))
        }.frame(minWidth:620,minHeight:470).ignoresSafeArea(.container,edges:.top).preferredColorScheme(.light)
        .environment(\.locale,Locale(identifier:appLanguage))
        .alert("Удалить пару с телефоном?",isPresented:$confirmingUnpair) {
            Button("Удалить",role:.destructive) { model.unpair() }; Button("Отмена",role:.cancel) { }
        } message: { Text("Для повторного подключения потребуется новое сопряжение.") }
    }
}
private struct MenuBarDropView: View {
    @ObservedObject var model: ViewModel
    @AppStorage("appLanguage") private var appLanguage = "en"
    let openMain: () -> Void
    let chooseFiles: () -> Void
    let toggleClipboard: () -> Void
    let quit: () -> Void
    @State private var isDropTargeted = false
    private let blue = Color(red:0.04,green:0.43,blue:0.98)
    private let ink = Color(red:0.10,green:0.14,blue:0.22)
    private let muted = Color(red:0.39,green:0.45,blue:0.55)
    private let panel = Color(red:0.96,green:0.97,blue:0.99)
    var body: some View {
        VStack(alignment:.leading,spacing:13) {
            HStack(spacing:10) {
                Image(systemName:"arrow.left.arrow.right")
                    .font(.system(size:14,weight:.semibold)).foregroundStyle(.white)
                    .frame(width:32,height:32).background(blue, in:RoundedRectangle(cornerRadius:9))
                VStack(alignment:.leading,spacing:3) {
                    Text("AndroidMacBridge").font(.system(size:13,weight:.semibold)).foregroundStyle(ink)
                    HStack(spacing:5) {
                        let live = model.bluetoothModeEnabled ? model.bluetoothConnected : model.connected
                        Circle().fill(model.paired && live ? Color(red:0.12,green:0.63,blue:0.34) : Color(red:0.87,green:0.57,blue:0.12)).frame(width:7,height:7)
                        Text(model.paired ? (live ? "Телефон подключён" : "Ожидание связи") : "Телефон не сопряжён")
                            .font(.system(size:11,weight:.medium)).foregroundStyle(ink)
                    }
                }
                Spacer()
            }
            if let active = model.transfers.first(where: { $0.type == "file" && ["offered","accepted","transferring"].contains($0.state) }) {
                VStack(alignment:.leading,spacing:5) {
                    HStack(spacing:6) {
                        Image(systemName:"arrow.up.doc.fill").foregroundStyle(blue)
                        Text(active.name).font(.system(size:11,weight:.medium)).foregroundStyle(ink).lineLimit(1).truncationMode(.middle)
                    }
                    Text(model.progressDetails[active.id] ?? (active.state == "offered" ? "Ожидает решения на телефоне" : "Передача файла"))
                        .font(.system(size:10)).foregroundStyle(muted)
                    ProgressView(value:Double(max(0,active.progress)),total:Double(max(1,active.size))).tint(blue).controlSize(.small)
                }.padding(10).frame(maxWidth:.infinity,alignment:.leading).background(.white, in:RoundedRectangle(cornerRadius:10))
            }
            VStack(spacing:9) {
                Image(systemName:isDropTargeted ? "arrow.down.doc.fill" : "tray.and.arrow.down.fill")
                    .font(.system(size:24,weight:.regular)).foregroundStyle(isDropTargeted ? blue : Color(red:0.31,green:0.49,blue:0.76))
                Text(isDropTargeted ? "Отпустите, чтобы отправить" : "Перетащите файлы сюда")
                    .font(.system(size:12,weight:.semibold)).foregroundStyle(ink)
                Text(model.paired ? "Файлы добавятся в очередь передачи" : "Сначала подключите телефон")
                    .font(.system(size:10)).foregroundStyle(muted)
                if model.queuedFileCount > 0 { Text("В очереди: \(model.queuedFileCount)").font(.system(size:10,weight:.medium)).foregroundStyle(blue) }
                if model.paired {
                    Button(action:chooseFiles) {
                        Label("Выбрать файлы",systemImage:"plus")
                            .font(.system(size:11,weight:.semibold)).foregroundStyle(blue)
                            .padding(.horizontal,12).padding(.vertical,6)
                            .background(Color(red:0.91,green:0.95,blue:1),in:Capsule())
                    }.buttonStyle(.plain)
                }
            }.frame(maxWidth:.infinity,minHeight:132).padding(.vertical,8).background(isDropTargeted ? Color(red:0.89,green:0.94,blue:1) : .white)
                .overlay(RoundedRectangle(cornerRadius:11).strokeBorder(style:StrokeStyle(lineWidth:isDropTargeted ? 1.5 : 1,dash:[5,4])).foregroundColor(isDropTargeted ? blue : Color(red:0.75,green:0.80,blue:0.88)))
                .clipShape(RoundedRectangle(cornerRadius:11))
                .onDrop(of:["public.file-url"],isTargeted:$isDropTargeted) { providers in
                    guard model.paired, !providers.isEmpty else { return false }
                    model.enqueueFileProviders(providers)
                    return true
                }
            HStack(spacing:8) {
                Button(action:openMain) {
                    Label("Открыть приложение",systemImage:"macwindow")
                        .font(.system(size:11,weight:.semibold)).foregroundStyle(.white)
                        .frame(maxWidth:.infinity,minHeight:34).background(blue,in:RoundedRectangle(cornerRadius:8))
                }.buttonStyle(.plain)
                Button(action:toggleClipboard) {
                    Label(model.clipboardSyncEnabled ? "Буфер включён" : "Включить буфер",systemImage:"doc.on.clipboard")
                        .font(.system(size:10,weight:.medium)).foregroundStyle(ink)
                        .frame(maxWidth:.infinity,minHeight:34).background(.white,in:RoundedRectangle(cornerRadius:8))
                        .overlay(RoundedRectangle(cornerRadius:8).stroke(Color(red:0.84,green:0.87,blue:0.91),lineWidth:1))
                }.buttonStyle(.plain)
                Spacer()
                Button(action:quit) { Image(systemName:"power").font(.system(size:12,weight:.medium)).foregroundStyle(muted).frame(width:30,height:32) }
                    .buttonStyle(.plain).help("Выйти из приложения")
            }
        }.padding(14).frame(width:320).background(panel.ignoresSafeArea()).environment(\.colorScheme,.light)
            .environment(\.locale,Locale(identifier:appLanguage))
    }
}

private struct DragPeekView: View {
    @ObservedObject var model: ViewModel
    @ObservedObject var appDelegate: AppDelegate
    @AppStorage("appLanguage") private var appLanguage = "en"
    @State private var isTargeted = false
    private let blue = Color(red:0.04,green:0.43,blue:0.98)
    var body: some View {
        Group {
            if appDelegate.dragPanelExpanded {
                VStack(spacing:9) {
                    Image(systemName:"tray.and.arrow.down.fill").font(.system(size:25)).foregroundStyle(blue)
                    Text("Отправить на телефон").font(.system(size:13,weight:.semibold)).foregroundStyle(Color(red:0.10,green:0.14,blue:0.22))
                    Text(model.paired ? "Отпустите файл — он добавится в очередь" : "Сначала подключите телефон")
                        .font(.system(size:10)).foregroundStyle(Color.secondary).multilineTextAlignment(.center)
                    if model.queuedFileCount > 0 { Text("В очереди: \(model.queuedFileCount)").font(.system(size:10,weight:.medium)).foregroundStyle(blue) }
                }.frame(width:280,height:150)
            } else {
                HStack(spacing:9) {
                    Image(systemName:"arrow.down.doc.fill").foregroundStyle(blue)
                    Text("Бросить файл сюда").font(.system(size:11,weight:.semibold)).foregroundStyle(Color(red:0.10,green:0.14,blue:0.22))
                }.frame(width:176,height:48)
            }
        }
        .background(Color.white.opacity(0.98),in:RoundedRectangle(cornerRadius:14))
        .overlay(RoundedRectangle(cornerRadius:14).stroke(Color(red:0.78,green:0.83,blue:0.91),lineWidth:1))
        .shadow(color:.black.opacity(0.18),radius:16,y:7)
        .clipShape(RoundedRectangle(cornerRadius:14))
        .onDrop(of:["public.file-url"],isTargeted:$isTargeted) { providers in
            guard model.paired, !providers.isEmpty else { return false }
            model.enqueueFileProviders(providers)
            appDelegate.hideDragPanel()
            return true
        }
        .environment(\.colorScheme,.light)
        .environment(\.locale,Locale(identifier:appLanguage))
    }
}

final class AppDelegate: NSObject, ObservableObject, NSApplicationDelegate, NSWindowDelegate, UNUserNotificationCenterDelegate {
    var window: NSWindow?, model: ViewModel?
    private var statusItem: NSStatusItem?
    private var dropPopover: NSPopover?
    private var dragPanel: NSPanel?
    private var dragMonitor: Any?
    private var clickMonitor: Any?
    private var releaseMonitor: Any?
    private var localClickMonitor: Any?
    @Published var dragPanelExpanded = false
    private var dragPanelVisible = false
    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)
        let model = ViewModel(); self.model = model
        UNUserNotificationCenter.current().delegate = self
        let window = NSWindow(contentRect:NSRect(x:0,y:0,width:660,height:550),styleMask:[.titled,.closable,.miniaturizable,.resizable],backing:.buffered,defer:false)
        window.appearance = NSAppearance(named:.aqua)
        window.styleMask.insert(.fullSizeContentView)
        window.titlebarAppearsTransparent = true
        window.titleVisibility = .hidden
        window.title = "AndroidMacBridge"; window.contentViewController = NSHostingController(rootView:ContentView(model:model)); window.center(); window.delegate = self; window.makeKeyAndOrderFront(nil); self.window = window
        let item = NSStatusBar.system.statusItem(withLength:NSStatusItem.squareLength)
        item.button?.image = NSImage(systemSymbolName:"doc.on.clipboard",accessibilityDescription:"AndroidMacBridge")
        if let button = item.button { button.target = self; button.action = #selector(toggleDropPopover) }
        let popover = NSPopover(); popover.behavior = .transient
        popover.contentViewController = NSHostingController(rootView:MenuBarDropView(model:model,openMain:{ [weak self] in self?.openMainWindow(); self?.dropPopover?.close() },chooseFiles:{ [weak self] in self?.model?.chooseFile(); self?.dropPopover?.close() },toggleClipboard:{ [weak self] in self?.toggleClipboardSync() },quit:{ [weak self] in self?.quitApplication() }))
        dropPopover = popover; statusItem = item
        installDragPanel(model:model)
    }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }
    func windowShouldClose(_ sender:NSWindow) -> Bool { sender.orderOut(nil); return false }
    @objc private func openMainWindow() { window?.makeKeyAndOrderFront(nil); NSApp.activate(ignoringOtherApps:true) }
    @objc private func toggleDropPopover() {
        guard let popover = dropPopover, let button = statusItem?.button else { return }
        if popover.isShown { popover.performClose(nil) }
        else { popover.show(relativeTo:button.bounds,of:button,preferredEdge:.minY) }
    }
    @objc private func toggleClipboardSync() { model?.setClipboardSyncEnabled(!(model?.clipboardSyncEnabled ?? false)) }
    @objc private func quitApplication() { NSApp.terminate(nil) }
    private func installDragPanel(model: ViewModel) {
        let panel = NSPanel(contentRect:NSRect(x:0,y:0,width:176,height:48),styleMask:[.borderless,.nonactivatingPanel],backing:.buffered,defer:false)
        panel.isFloatingPanel = true
        panel.level = .floating
        panel.backgroundColor = .clear
        panel.isOpaque = false
        panel.hasShadow = false
        panel.hidesOnDeactivate = false
        panel.collectionBehavior = [.canJoinAllSpaces,.fullScreenAuxiliary]
        panel.contentViewController = NSHostingController(rootView:DragPeekView(model:model,appDelegate:self))
        dragPanel = panel
        dragMonitor = NSEvent.addGlobalMonitorForEvents(matching:.leftMouseDragged) { [weak self] _ in
            guard let self = self, NSWorkspace.shared.frontmostApplication?.bundleIdentifier == "com.apple.finder" || self.dragPanelVisible else { return }
            DispatchQueue.main.async { self.updateDragPanel(at:NSEvent.mouseLocation) }
        }
        clickMonitor = NSEvent.addGlobalMonitorForEvents(matching:.leftMouseDown) { [weak self] _ in
            DispatchQueue.main.async { self?.dismissOverlays(at:NSEvent.mouseLocation) }
        }
        releaseMonitor = NSEvent.addGlobalMonitorForEvents(matching:.leftMouseUp) { [weak self] _ in
            DispatchQueue.main.asyncAfter(deadline:.now()+0.45) { self?.hideDragPanel() }
        }
        localClickMonitor = NSEvent.addLocalMonitorForEvents(matching:.leftMouseDown) { [weak self] event in
            self?.dismissOverlays(at:NSEvent.mouseLocation)
            return event
        }
    }
    private func dismissOverlays(at point: NSPoint) {
        hideDragPanel()
        guard let popover = dropPopover, popover.isShown else { return }
        if let frame = popover.contentViewController?.view.window?.frame, frame.contains(point) { return }
        if let button = statusItem?.button, let statusWindow = button.window {
            let buttonFrame = statusWindow.convertToScreen(button.convert(button.bounds,to:nil))
            if buttonFrame.contains(point) { return }
        }
        popover.performClose(nil)
    }
    private func updateDragPanel(at point: NSPoint) {
        guard let panel = dragPanel, let screen = NSScreen.main else { return }
        let expanded = panel.frame.insetBy(dx:-155,dy:-115).contains(point)
        let size = expanded ? NSSize(width:280,height:150) : NSSize(width:176,height:48)
        dragPanelExpanded = expanded
        let visible = screen.visibleFrame
        let origin = NSPoint(x:visible.maxX-size.width-18,y:visible.maxY-size.height-22)
        panel.setContentSize(size)
        panel.setFrame(NSRect(origin:origin,size:size),display:true)
        if !panel.isVisible { panel.orderFrontRegardless() }
        dragPanelVisible = true
    }
    func hideDragPanel() {
        dragPanel?.orderOut(nil)
        dragPanelVisible = false
        dragPanelExpanded = false
    }
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        if let id = response.notification.request.content.userInfo["transferID"] as? String {
            DispatchQueue.main.async { self.model?.revealFile(id:id) }
        }
        completionHandler()
    }
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner,.sound])
    }
}
let app = NSApplication.shared
let delegate = AppDelegate(); app.delegate = delegate; app.setActivationPolicy(.regular); app.run()
