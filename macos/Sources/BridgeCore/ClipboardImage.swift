import Foundation
import CryptoKit
import ImageIO

/// Validates untrusted image data before it can reach a system pasteboard.
public struct ClipboardImage: Equatable {
    public static let maxBytes = 8 * 1024 * 1024
    public static let maxPixels = 24_000_000
    public let data: Data
    public let mimeType: String
    public let sha256: String

    public init(data: Data, mimeType: String, expectedSHA256: String? = nil) throws {
        guard !data.isEmpty, data.count <= Self.maxBytes else { throw BridgeError.invalid("Изображение пустое или больше 8 МиБ") }
        let expectedType: String
        switch mimeType {
        case "image/png": expectedType = "public.png"
        case "image/jpeg": expectedType = "public.jpeg"
        default: throw BridgeError.invalid("Формат изображения не поддерживается")
        }
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              CGImageSourceGetCount(source) == 1, CGImageSourceGetStatusAtIndex(source, 0) == .statusComplete,
              let type = CGImageSourceGetType(source) as String?, type == expectedType,
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int,
              width > 0, height > 0, width <= Self.maxPixels / height,
              CGImageSourceCreateImageAtIndex(source, 0, nil) != nil else {
            throw BridgeError.invalid("Изображение повреждено или слишком велико")
        }
        let digest = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        if let expectedSHA256, digest != expectedSHA256.lowercased() { throw BridgeError.invalid("Контрольная сумма изображения не совпадает") }
        self.data = data; self.mimeType = mimeType; self.sha256 = digest
    }
}

/// Clipboard image event kept only in memory and excluded from transfer history.
public struct ClipboardImageEvent: Equatable {
    public let eventId: String
    public let source: String
    public let image: ClipboardImage

    public init(eventId: String = UUID().uuidString.lowercased(), source: String, image: ClipboardImage) throws {
        guard let uuid = UUID(uuidString: eventId), uuid.uuidString.lowercased() == eventId.lowercased() else {
            throw BridgeError.invalid("Некорректный ID события изображения")
        }
        guard ["mac", "android"].contains(source) else { throw BridgeError.invalid("Неизвестный источник изображения") }
        self.eventId = eventId.lowercased()
        self.source = source
        self.image = image
    }
}
