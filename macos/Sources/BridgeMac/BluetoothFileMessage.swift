import Foundation
import BridgeCore

enum BluetoothFileMessage {
    static let chunkBytes = 32 * 1024

    static func offer(id: String, name: String, size: Int64, sha256: String) throws -> Data {
        try encode(["kind":"file.offer","id":id,"name":name,"size":size,"sha256":sha256])
    }
    static func decision(id: String, state: String) throws -> Data {
        try encode(["kind":"file.decision","id":id,"state":state])
    }
    static func chunk(id: String, offset: Int64, data: Data) throws -> Data {
        try encode(["kind":"file.chunk","id":id,"offset":offset,"data":data.base64EncodedString()])
    }
    static func finish(id: String, size: Int64, sha256: String) throws -> Data {
        try encode(["kind":"file.finish","id":id,"size":size,"sha256":sha256])
    }
    static func cancel(id: String) throws -> Data { try encode(["kind":"file.cancel","id":id]) }
    static func reply(id: String, phase: String, state: String, nextOffset: Int64? = nil, sha256: String? = nil) throws -> Data {
        var value: [String:Any] = ["kind":"file.reply","id":id,"phase":phase,"state":state]
        if let nextOffset { value["nextOffset"] = nextOffset }
        if let sha256 { value["sha256"] = sha256 }
        return try encode(value)
    }
    static func decode(_ data: Data) throws -> [String:Any] {
        guard let value = try JSONSerialization.jsonObject(with:data) as? [String:Any] else { throw BridgeError.invalid("Повреждено Bluetooth-сообщение о файле") }
        return value
    }
    static func chunkData(_ message: [String:Any]) throws -> Data {
        guard let string = message["data"] as? String, let value = Data(base64Encoded:string), value.count <= chunkBytes else { throw BridgeError.invalid("Повреждена часть Bluetooth-файла") }
        return value
    }
    private static func encode(_ value: [String:Any]) throws -> Data { try JSONSerialization.data(withJSONObject:value,options:[.sortedKeys]) }
}
