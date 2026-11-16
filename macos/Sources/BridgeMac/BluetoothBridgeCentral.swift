import Foundation
import CoreBluetooth
import BridgeCore

/// CoreBluetooth central side. It scans only while the user has explicitly enabled BLE.
final class BluetoothBridgeCentral: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    private let state: BridgeState
    private let serviceID = CBUUID(string:"6D2F0001-8A14-4E72-9B2A-5A4D42524944")
    private let rxID = CBUUID(string:"6D2F0002-8A14-4E72-9B2A-5A4D42524944")
    private let txID = CBUUID(string:"6D2F0003-8A14-4E72-9B2A-5A4D42524944")
    private var manager: CBCentralManager?
    private var peripheral: CBPeripheral?
    private var rx: CBCharacteristic?
    private var tx: CBCharacteristic?
    private var enabled = false
    private var assembler = FrameAssembler()
    private var crypto: BluetoothSessionCrypto?
    private var writeQueue: [Data] = []
    private var writing = false
    private var outgoingFrameID: UInt32 = 0
    private var pendingClipboardAcks: [String:(String) -> Void] = [:]
    private var pendingFileReplies: [String:([String:Any]?) -> Void] = [:]
    var onStatus: ((String) -> Void)?
    var onMessage: ((Data) -> Void)?
    private(set) var isConnected = false

    init(state: BridgeState) { self.state = state; super.init() }

    func start() {
        guard state.paired else { onStatus?("Сначала подключите телефон по QR-коду"); return }
        enabled = true
        if manager == nil { manager = CBCentralManager(delegate:self,queue:.main) }
        else if manager?.state == .poweredOn { scan() }
    }

    func stop() {
        enabled = false; isConnected = false; crypto?.clear(); crypto = nil; writeQueue.removeAll(); writing = false
        if let peripheral = peripheral { manager?.cancelPeripheralConnection(peripheral) }
        manager?.stopScan(); peripheral = nil; rx = nil; tx = nil
        onStatus?("Bluetooth выключен")
    }

    func send(_ plaintext: Data) throws {
        guard enabled, isConnected, let crypto = crypto, crypto.authenticated else { throw BridgeError.invalid("Нет защищённого Bluetooth-соединения") }
        enqueue(try crypto.seal(plaintext))
    }

    func sendClipboard(_ plaintext: Data, eventID: String, completion: @escaping (String) -> Void) throws {
        guard pendingClipboardAcks[eventID] == nil else { throw BridgeError.invalid("Ожидается подтверждение этого clipboard-события") }
        pendingClipboardAcks[eventID] = completion
        do {
            try send(plaintext)
            DispatchQueue.main.asyncAfter(deadline:.now() + 120) { [weak self] in
                guard let callback = self?.pendingClipboardAcks.removeValue(forKey:eventID) else { return }
                callback("timeout")
            }
        } catch {
            pendingClipboardAcks.removeValue(forKey:eventID)
            throw error
        }
    }

    func resolveClipboardAck(eventID: String, state: String) {
        pendingClipboardAcks.removeValue(forKey:eventID)?(state)
    }

    /// Waits for a file decision/chunk receipt/result without blocking CoreBluetooth's main queue.
    func sendFileAndAwait(_ plaintext: Data, id: String, phase: String, timeout: TimeInterval = 900) throws -> [String:Any] {
        guard !Thread.isMainThread else { throw BridgeError.invalid("Bluetooth-файл нельзя ожидать в главном потоке") }
        let key = "\(id)|\(phase)"
        let semaphore = DispatchSemaphore(value:0)
        let box = BluetoothReplyBox()
        try DispatchQueue.main.sync {
            guard pendingFileReplies[key] == nil else { throw BridgeError.invalid("Уже ожидается ответ Bluetooth-передачи") }
            pendingFileReplies[key] = { reply in box.set(reply); semaphore.signal() }
            do { try send(plaintext) }
            catch { pendingFileReplies.removeValue(forKey:key); throw error }
            DispatchQueue.main.asyncAfter(deadline:.now() + timeout) { [weak self] in
                guard let callback = self?.pendingFileReplies.removeValue(forKey:key) else { return }
                callback(nil)
            }
        }
        guard semaphore.wait(timeout:.now() + timeout + 1) == .success, let reply = box.value else { throw BridgeError.invalid("Bluetooth-ответ не получен вовремя") }
        return reply
    }

    func resolveFileReply(_ reply: [String:Any]) {
        guard let id = reply["id"] as? String, let phase = reply["phase"] as? String else { return }
        pendingFileReplies.removeValue(forKey:"\(id)|\(phase)")?(reply)
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard enabled else { return }
        switch central.state {
        case .poweredOn: scan()
        case .poweredOff: isConnected = false; onStatus?("Включите Bluetooth на Mac")
        case .unauthorized: isConnected = false; onStatus?("Разрешите Bluetooth для AndroidMacBridge в настройках macOS")
        case .unsupported: isConnected = false; onStatus?("На Mac недоступен Bluetooth LE")
        default: isConnected = false; onStatus?("Проверяю состояние Bluetooth…")
        }
    }

    private func scan() {
        guard enabled, manager?.state == .poweredOn, state.paired, peripheral == nil else { return }
        onStatus?("Ищу сопряжённый телефон по Bluetooth…")
        manager?.scanForPeripherals(withServices:[serviceID],options:[CBCentralManagerScanOptionAllowDuplicatesKey:false])
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String:Any], rssi RSSI: NSNumber) {
        guard enabled, self.peripheral == nil else { return }
        self.peripheral = peripheral; peripheral.delegate = self; central.stopScan()
        onStatus?("Телефон найден · подключаюсь по Bluetooth…")
        central.connect(peripheral,options:nil)
        DispatchQueue.main.asyncAfter(deadline:.now() + 15) { [weak self, weak peripheral] in
            guard let self = self, let peripheral = peripheral, self.enabled,
                  self.peripheral === peripheral, !self.isConnected else { return }
            self.onStatus?("Bluetooth не ответил · повторный поиск")
            central.cancelPeripheralConnection(peripheral)
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard enabled else { central.cancelPeripheralConnection(peripheral); return }
        onStatus?("Bluetooth-соединение открыто · проверяю пару")
        peripheral.discoverServices([serviceID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) { disconnected(error) }
    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) { disconnected(error) }

    private func disconnected(_ error: Error?) {
        isConnected = false; crypto?.clear(); crypto = nil; rx = nil; tx = nil; self.peripheral = nil; writeQueue.removeAll(); writing = false
        let callbacks = pendingClipboardAcks.values
        pendingClipboardAcks.removeAll()
        callbacks.forEach { $0("disconnected") }
        let fileCallbacks = pendingFileReplies.values
        pendingFileReplies.removeAll()
        fileCallbacks.forEach { $0(nil) }
        guard enabled else { return }
        onStatus?(error.map { "Связь Bluetooth прервана: \($0.localizedDescription) · повторный поиск" } ?? "Связь Bluetooth прервана · повторный поиск")
        DispatchQueue.main.asyncAfter(deadline:.now() + 1) { [weak self] in self?.scan() }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil, let service = peripheral.services?.first(where:{$0.uuid == serviceID}) else { return fail("На телефоне не найдена служба AndroidMacBridge") }
        peripheral.discoverCharacteristics([rxID,txID],for:service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard error == nil, let chars = service.characteristics,
              let write = chars.first(where:{$0.uuid == rxID}), let notify = chars.first(where:{$0.uuid == txID}) else { return fail("Не найдены характеристики Bluetooth-службы") }
        rx = write; tx = notify; peripheral.setNotifyValue(true,for:notify)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        if let error = error { return fail("Не удалось включить Bluetooth-уведомления: \(error.localizedDescription)") }
        if characteristic.uuid == txID, characteristic.isNotifying { onStatus?("Подтверждаю пару AndroidMacBridge…") }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, characteristic.uuid == txID, let value = characteristic.value else {
            if let error = error { fail(error.localizedDescription) }; return
        }
        do {
            guard let complete = try assembler.append(value) else { return }
            if crypto == nil || crypto?.authenticated == false {
                let session = crypto ?? BluetoothSessionCrypto(state:state)
                if crypto == nil {
                    let challenge = try session.acceptHello(complete)
                    crypto = session; enqueue(challenge); onStatus?("Mac и телефон проверяют существующую пару…")
                } else {
                    try session.acceptProof(complete); isConnected = true; onStatus?("Подключено по Bluetooth"); crypto = session
                }
            } else if let crypto = crypto {
                let plaintext = try crypto.open(complete)
                onMessage?(plaintext)
            }
        } catch { fail("Bluetooth-пара не подтверждена: \(error.localizedDescription)") }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        writing = false
        if let error = error { return fail("Ошибка записи Bluetooth-кадра: \(error.localizedDescription)") }
        writeNext()
    }

    private func enqueue(_ data: Data) {
        guard let peripheral = peripheral, let rx = rx else { return fail("Bluetooth-устройство ещё не готово") }
        let limit = max(20,peripheral.maximumWriteValueLength(for:.withResponse))
        outgoingFrameID &+= 1
        do { writeQueue.append(contentsOf:try FrameAssembler.fragment(data,id:outgoingFrameID,limit:limit)); writeNext() }
        catch { fail(error.localizedDescription) }
        _ = rx
    }

    private func writeNext() {
        guard !writing, !writeQueue.isEmpty, let peripheral = peripheral, let rx = rx else { return }
        let bytes = writeQueue.removeFirst(); writing = true; peripheral.writeValue(bytes,for:rx,type:.withResponse)
    }

    private func fail(_ text: String) {
        isConnected = false; crypto?.clear(); crypto = nil; writeQueue.removeAll(); writing = false
        let callbacks = pendingClipboardAcks.values
        pendingClipboardAcks.removeAll()
        callbacks.forEach { $0("disconnected") }
        let fileCallbacks = pendingFileReplies.values
        pendingFileReplies.removeAll()
        fileCallbacks.forEach { $0(nil) }
        onStatus?(text)
        if let peripheral = peripheral { manager?.cancelPeripheralConnection(peripheral) }
    }
}

private final class BluetoothReplyBox {
    private let lock = NSLock()
    private var stored: [String:Any]?
    func set(_ value: [String:Any]?) { lock.lock(); stored = value; lock.unlock() }
    var value: [String:Any]? { lock.lock(); defer { lock.unlock() }; return stored }
}

private struct FrameAssembler {
    private var activeID: UInt32?
    private var count = 0
    private var next = 0
    private var output = Data()
    private let maximumMessage = 12 * 1024 * 1024

    mutating func append(_ data: Data) throws -> Data? {
        guard data.count >= 8 else { throw BridgeError.invalid("Короткий Bluetooth-фрагмент") }
        let id = UInt32(bigEndianBytes:data.subdata(in:0..<4)), index = Int(UInt16(bigEndianBytes:data.subdata(in:4..<6))), total = Int(UInt16(bigEndianBytes:data.subdata(in:6..<8)))
        guard total > 0, index < total else { throw BridgeError.invalid("Некорректный Bluetooth-фрагмент") }
        if index == 0 { activeID = id; count = total; next = 0; output = Data() }
        guard activeID == id, count == total, next == index, output.count + data.count - 8 <= maximumMessage else { throw BridgeError.invalid("Нарушен порядок Bluetooth-фрагментов") }
        output.append(data.suffix(from:8)); next += 1
        guard next == count else { return nil }
        let complete = output; activeID = nil; count = 0; next = 0; output = Data(); return complete
    }

    static func fragment(_ data: Data, id: UInt32, limit: Int) throws -> [Data] {
        let chunkSize = limit - 8
        guard !data.isEmpty, data.count <= 12 * 1024 * 1024, chunkSize > 0 else { throw BridgeError.invalid("Недопустимый размер Bluetooth-сообщения") }
        let total = (data.count + chunkSize - 1) / chunkSize
        guard total <= Int(UInt16.max) else { throw BridgeError.invalid("Слишком много Bluetooth-фрагментов") }
        return (0..<total).map { index in
            var fragment = Data(); fragment.append(id.bigEndianData); fragment.append(UInt16(index).bigEndianData); fragment.append(UInt16(total).bigEndianData)
            let lower = index * chunkSize, upper = min(data.count,lower + chunkSize); fragment.append(data.subdata(in:lower..<upper)); return fragment
        }
    }
}

private extension UInt32 {
    var bigEndianData: Data { var value = bigEndian; return withUnsafeBytes(of:&value) { Data($0) } }
    init(bigEndianBytes data: Data) { self = data.reduce(UInt32(0)) { ($0 << 8) | UInt32($1) } }
}
private extension UInt16 {
    var bigEndianData: Data { var value = bigEndian; return withUnsafeBytes(of:&value) { Data($0) } }
    init(bigEndianBytes data: Data) { self = data.reduce(UInt16(0)) { ($0 << 8) | UInt16($1) } }
}
