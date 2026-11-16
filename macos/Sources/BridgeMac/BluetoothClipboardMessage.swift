import Foundation
import BridgeCore

enum BluetoothClipboardMessage {
    case text(ClipboardEvent)
    case image(ClipboardImageEvent)
    case acknowledgement(String, String)

    static func text(_ event: ClipboardEvent) throws -> Data {
        let encoded = try JSONEncoder().encode(event)
        let object = try JSONSerialization.jsonObject(with:encoded)
        return try JSONSerialization.data(withJSONObject:["kind":"clipboard.text","event":object])
    }

    static func image(_ event: ClipboardImageEvent) throws -> Data {
        let body: [String:Any] = [
            "eventId":event.eventId,
            "source":event.source,
            "mimeType":event.image.mimeType,
            "sha256":event.image.sha256,
            "data":event.image.data.base64EncodedString()
        ]
        return try JSONSerialization.data(withJSONObject:["kind":"clipboard.image","event":body])
    }

    static func acknowledgement(eventId: String, state: String) throws -> Data {
        try JSONSerialization.data(withJSONObject:["kind":"clipboard.ack","eventId":eventId,"state":state])
    }

    static func decode(_ data: Data) throws -> BluetoothClipboardMessage {
        guard let root = try JSONSerialization.jsonObject(with:data) as? [String:Any], let kind = root["kind"] as? String else {
            throw BridgeError.invalid("Некорректное Bluetooth-сообщение")
        }
        switch kind {
        case "clipboard.text":
            guard let value = root["event"], let encoded = try? JSONSerialization.data(withJSONObject:value) else { throw BridgeError.invalid("Некорректное событие текста") }
            let event = try JSONDecoder().decode(ClipboardEvent.self,from:encoded)
            guard event.source == "android" else { throw BridgeError.unauthorized }
            return .text(event)
        case "clipboard.image":
            guard let event = root["event"] as? [String:Any],
                  let eventID = event["eventId"] as? String,
                  let source = event["source"] as? String,
                  let mimeType = event["mimeType"] as? String,
                  let hash = event["sha256"] as? String,
                  let encoded = event["data"] as? String,
                  encoded.utf8.count <= ClipboardImage.maxBytes * 4 / 3 + 8,
                  let bytes = Data(base64Encoded:encoded) else { throw BridgeError.invalid("Некорректное событие изображения") }
            let image = try ClipboardImage(data:bytes,mimeType:mimeType,expectedSHA256:hash)
            let value = try ClipboardImageEvent(eventId:eventID,source:source,image:image)
            guard value.source == "android" else { throw BridgeError.unauthorized }
            return .image(value)
        case "clipboard.ack":
            guard let eventID = root["eventId"] as? String, let state = root["state"] as? String else { throw BridgeError.invalid("Некорректное подтверждение Bluetooth") }
            return .acknowledgement(eventID,state)
        default:
            throw BridgeError.invalid("Неизвестный тип Bluetooth-сообщения")
        }
    }
}
