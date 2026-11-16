import XCTest
import CoreGraphics
import ImageIO
@testable import BridgeCore

private final class ClipboardImageMemoryStore: BridgeCredentialStore {
    private var values: [String:String] = [:]
    func loadString(_ name: String) throws -> String? { values[name] }
    func saveString(_ value: String, for name: String) throws { values[name] = value }
    func delete(_ name: String) throws { values.removeValue(forKey:name) }
}

final class ClipboardImageTests: XCTestCase {
    private func png() throws -> Data {
        let color = CGColorSpaceCreateDeviceRGB()
        let context = try XCTUnwrap(CGContext(data: nil, width: 2, height: 2, bitsPerComponent: 8, bytesPerRow: 0, space: color, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        context.setFillColor(CGColor(red: 1, green: 0, blue: 0, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: 2, height: 2))
        let image = try XCTUnwrap(context.makeImage())
        let output = NSMutableData()
        let destination = try XCTUnwrap(CGImageDestinationCreateWithData(output, "public.png" as CFString, 1, nil))
        CGImageDestinationAddImage(destination, image, nil)
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        return output as Data
    }

    func testImageValidationRejectsInvalidDataAndDigest() throws {
        let data = try png()
        let image = try ClipboardImage(data: data, mimeType: "image/png")
        XCTAssertNoThrow(try ClipboardImage(data: data, mimeType: "image/png", expectedSHA256: image.sha256))
        XCTAssertThrowsError(try ClipboardImage(data: data, mimeType: "image/jpeg"))
        XCTAssertThrowsError(try ClipboardImage(data: data, mimeType: "image/png", expectedSHA256: String(repeating: "0", count: 64)))
        XCTAssertThrowsError(try ClipboardImage(data: Data(data.prefix(16)), mimeType: "image/png"))
        XCTAssertThrowsError(try ClipboardImage(data: Data(count: ClipboardImage.maxBytes + 1), mimeType: "image/png"))
    }

    func testImageMailboxDeduplicatesAndClearsOnPause() throws {
        let suite = "bridge-image-mailbox-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName:suite)!
        defer { defaults.removePersistentDomain(forName:suite) }
        let state = try pairedImageState(defaults:defaults)
        let image = try ClipboardImage(data:png(),mimeType:"image/png")
        let first = try ClipboardImageEvent(source:"mac",image:image)
        let repeated = try ClipboardImageEvent(source:"mac",image:image)
        XCTAssertTrue(try state.publishMacClipboardImage(first))
        XCTAssertFalse(try state.publishMacClipboardImage(repeated))
        XCTAssertEqual(state.nextMacClipboardImage(after:nil)?.image.sha256,image.sha256)
        XCTAssertNil(state.nextMacClipboardImage(after:first.eventId))
        state.setClipboardSyncEnabled(false)
        XCTAssertNil(state.nextMacClipboardImage(after:nil))
    }

    func testNewestClipboardTypeSupersedesOlderPendingTypeAndAllowsRecopy() throws {
        let suite = "bridge-image-order-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName:suite)!
        defer { defaults.removePersistentDomain(forName:suite) }
        let state = try pairedImageState(defaults:defaults)
        let text = try ClipboardEvent(source:"mac",text:"между картинками")
        let image = try ClipboardImage(data:png(),mimeType:"image/png")
        let firstImage = try ClipboardImageEvent(source:"mac",image:image)

        XCTAssertTrue(try state.publishMacClipboard(text))
        XCTAssertTrue(try state.publishMacClipboardImage(firstImage))
        XCTAssertNil(state.nextMacClipboard(after:nil))
        XCTAssertNotNil(state.nextMacClipboardImage(after:nil))

        XCTAssertTrue(try state.publishMacClipboard(try ClipboardEvent(source:"mac",text:"новый текст")))
        XCTAssertNil(state.nextMacClipboardImage(after:nil))
        XCTAssertEqual(state.nextMacClipboard(after:nil)?.text,"новый текст")

        XCTAssertTrue(try state.publishMacClipboardImage(try ClipboardImageEvent(source:"mac",image:image)))
        XCTAssertNotNil(state.nextMacClipboardImage(after:firstImage.eventId))
    }

    func testIncomingImageIsAppliedOnceAndNeverStoredInHistory() throws {
        let suite = "bridge-image-incoming-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName:suite)!
        defer { defaults.removePersistentDomain(forName:suite) }
        let state = try pairedImageState(defaults:defaults)
        let historyBefore = defaults.data(forKey:"bridge.history")
        let image = try ClipboardImage(data:png(),mimeType:"image/png")
        let event = try ClipboardImageEvent(source:"android",image:image)
        var appliedCount = 0
        state.onClipboardImageReceived = { received in
            XCTAssertEqual(received.eventId,event.eventId)
            appliedCount += 1
            return true
        }
        XCTAssertTrue(try state.acceptAndroidClipboardImage(event))
        XCTAssertFalse(try state.acceptAndroidClipboardImage(try ClipboardImageEvent(source:"android",image:image)))
        XCTAssertEqual(appliedCount,1)
        XCTAssertEqual(defaults.data(forKey:"bridge.history"),historyBefore)
        state.unpair()
        XCTAssertThrowsError(try state.acceptAndroidClipboardImage(try ClipboardImageEvent(source:"android",image:image)))
    }

    private func pairedImageState(defaults: UserDefaults) throws -> BridgeState {
        let state = BridgeState(keychain:ClipboardImageMemoryStore(),name:"Mac для теста",defaults:defaults)
        state.setClipboardSyncEnabled(true)
        let invitation = try state.startInvitation()
        let requestID = try state.requestPair(secret:invitation,name:"Galaxy S25")
        try state.approvePair(true)
        _ = try state.pairStatus(secret:invitation,id:requestID)
        return state
    }
}
