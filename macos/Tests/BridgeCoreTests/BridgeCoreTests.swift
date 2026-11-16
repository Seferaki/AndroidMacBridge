import XCTest
@testable import BridgeCore

private final class MemoryCredentialStore: BridgeCredentialStore {
    private var values: [String:String] = [:]
    func loadString(_ name: String) throws -> String? { values[name] }
    func saveString(_ value: String, for name: String) throws { values[name] = value }
    func delete(_ name: String) throws { values.removeValue(forKey:name) }
}

final class BridgeCoreTests: XCTestCase {
    func testPhoneModelRequiresCurrentPairAndIsClearedOnUnpair() throws {
        let suite = "bridge-phone-model-\(UUID().uuidString)", defaults = UserDefaults(suiteName:suite)!
        defer { defaults.removePersistentDomain(forName:suite) }
        let store = MemoryCredentialStore(), state = BridgeState(keychain:store,name:"Mac",defaults:defaults)
        let invitation = try state.startInvitation(), id = try state.requestPair(secret:invitation,name:"Телефон")
        try state.approvePair(true)
        let token = try XCTUnwrap(state.pairStatus(secret:invitation,id:id)["token"] as? String)
        XCTAssertThrowsError(try state.recordPhoneModel("SM-S931B",token:"invalid"))
        XCTAssertThrowsError(try state.recordPhoneModel("bad\nmodel",token:token))
        try state.recordPhoneModel("SM-S931B",token:token)
        XCTAssertEqual(BridgeState(keychain:store,name:"Mac",defaults:defaults).phoneModel,"SM-S931B")
        state.unpair()
        XCTAssertNil(state.phoneModel)
        XCTAssertThrowsError(try state.recordPhoneModel("SM-S931B",token:token))
    }
    func testFilenameRejectsTraversalAndControlCharacters() {
        for name in ["", ".", "..", "../a", "a/b", "a\\b", "a\0b", String(repeating: "x", count: 181)] {
            XCTAssertFalse(BridgeValidation.validName(name), name)
        }
        XCTAssertTrue(BridgeValidation.validName("фото 1.jpg"))
    }
    func testTokensAndDigestValidation() {
        XCTAssertTrue(BridgeValidation.constantTimeEquals("same", "same"))
        XCTAssertFalse(BridgeValidation.constantTimeEquals("same", "other"))
        XCTAssertFalse(BridgeValidation.constantTimeEquals("same", "same-longer"))
        XCTAssertTrue(BridgeValidation.validSHA256(String(repeating: "a", count: 64)))
        XCTAssertFalse(BridgeValidation.validSHA256(String(repeating: "z", count: 64)))
    }
    func testClipboardEventValidatesTypeSourceSizeAndHTTPLinks() throws {
        XCTAssertEqual(try ClipboardEvent(source:"android",text:"Привет").type, "text")
        XCTAssertEqual(try ClipboardEvent(source:"mac",text:"https://example.com/путь").type, "link")
        XCTAssertThrowsError(try ClipboardEvent(source:"unknown",text:"Привет"))
        XCTAssertThrowsError(try ClipboardEvent(source:"android",type:"link",text:"обычный текст"))
        XCTAssertThrowsError(try ClipboardEvent(source:"android",text:"https://"))
        XCTAssertNoThrow(try ClipboardEvent(source:"android",text:String(repeating:"a",count:BridgeValidation.maxText)))
        XCTAssertThrowsError(try ClipboardEvent(source:"android",text:String(repeating:"a",count:BridgeValidation.maxText + 1)))
    }
    func testClipboardMailboxKeepsOnlyLatestInMemoryEvent() throws {
        let suite = "bridge-clipboard-tests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName:suite)!
        defer { defaults.removePersistentDomain(forName:suite) }
        let state = try pairedClipboardState(defaults:defaults)
        let historyBefore = defaults.data(forKey:"bridge.history")
        let first = try ClipboardEvent(source:"mac",text:"первый")
        let repeatedText = try ClipboardEvent(source:"mac",text:"первый")
        let last = try ClipboardEvent(source:"mac",text:"последний")
        XCTAssertTrue(try state.publishMacClipboard(first))
        XCTAssertFalse(try state.publishMacClipboard(repeatedText))
        XCTAssertTrue(try state.publishMacClipboard(last))
        XCTAssertEqual(state.nextMacClipboard(after:nil)?.text, "последний")
        XCTAssertNil(state.nextMacClipboard(after:last.eventId))
        XCTAssertEqual(defaults.data(forKey:"bridge.history"),historyBefore)
    }
    func testIncomingClipboardIsAppliedOnceAndNeverStoredInTransferHistory() throws {
        let suite = "bridge-clipboard-incoming-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName:suite)!
        defer { defaults.removePersistentDomain(forName:suite) }
        let state = try pairedClipboardState(defaults:defaults)
        let historyBefore = defaults.data(forKey:"bridge.history")
        let event = try ClipboardEvent(source:"android",text:"телефон → Mac")
        let received = expectation(description:"Mac applies clipboard event")
        state.onClipboardReceived = { applied in
            XCTAssertEqual(applied.eventId,event.eventId)
            received.fulfill()
        }
        XCTAssertTrue(try state.acceptAndroidClipboard(event))
        XCTAssertFalse(try state.acceptAndroidClipboard(ClipboardEvent(source:"android",text:"телефон → Mac")))
        wait(for:[received],timeout:1)
        XCTAssertEqual(defaults.data(forKey:"bridge.history"),historyBefore)
    }

    func testClipboardCanReturnToEarlierTextButRejectsRepeatedEventID() throws {
        let suite = "bridge-clipboard-repeat-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName:suite)!
        defer { defaults.removePersistentDomain(forName:suite) }
        let state = try pairedClipboardState(defaults:defaults)
        state.onClipboardReceived = { _ in }
        let first = try ClipboardEvent(source:"android",text:"первый")
        let second = try ClipboardEvent(source:"android",text:"второй")
        let again = try ClipboardEvent(source:"android",text:"первый")
        XCTAssertTrue(try state.acceptAndroidClipboard(first))
        XCTAssertTrue(try state.acceptAndroidClipboard(second))
        XCTAssertFalse(try state.acceptAndroidClipboard(first))
        XCTAssertTrue(try state.acceptAndroidClipboard(again))
    }

    private func pairedClipboardState(defaults: UserDefaults) throws -> BridgeState {
        let state = BridgeState(keychain:MemoryCredentialStore(),name:"Mac для теста",defaults:defaults)
        state.setClipboardSyncEnabled(true)
        let invitation = try state.startInvitation()
        let requestID = try state.requestPair(secret:invitation,name:"Galaxy S25")
        try state.approvePair(true)
        _ = try state.pairStatus(secret:invitation,id:requestID)
        return state
    }
    func testPairingRequiresApprovalAndRevocationInvalidatesToken() throws {
        let store = MemoryCredentialStore()
        let state = BridgeState(keychain:store, name:"Mac для теста")
        let invitation = try state.startInvitation()
        let requestID = try state.requestPair(secret:invitation, name:"Galaxy S25")
        XCTAssertEqual(state.pairPrompt, "Galaxy S25")
        try state.approvePair(true)
        let answer = try state.pairStatus(secret:invitation, id:requestID)
        let token = try XCTUnwrap(answer["token"] as? String)
        XCTAssertTrue(state.authorize(token))
        XCTAssertThrowsError(try state.requestPair(secret:invitation, name:"Другой телефон"))
        state.unpair()
        XCTAssertFalse(state.authorize(token))
    }

    func testTransferAndClipboardHistoriesStayWithTheirPairWhenSwitching() throws {
        let suite = "bridge-pair-history-\(UUID().uuidString)", defaults = UserDefaults(suiteName:suite)!
        defer { defaults.removePersistentDomain(forName:suite) }
        let legacyTransferData = Data("legacy shared transfer history".utf8)
        defaults.set(legacyTransferData, forKey:"bridge.history")

        let credentialStore = MemoryCredentialStore()
        try credentialStore.saveString("pair-token-A", for:"pair.token")
        let state = BridgeState(keychain:credentialStore,name:"Mac",defaults:defaults)
        let pairA = try XCTUnwrap(state.pairHistoryID)
        _ = try state.offerFile(direction:"toPhone",name:"only pair A",size:0,sha256:String(repeating:"a",count:64))

        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString,isDirectory:true)
        try FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true)
        defer { try? FileManager.default.removeItem(at:directory) }
        let legacyClipboardURL = directory.appendingPathComponent("clipboard-history.json")
        let legacyClipboardData = Data("legacy shared clipboard history".utf8)
        try legacyClipboardData.write(to:legacyClipboardURL)
        let clipboardStore = PairClipboardHistoryStore(directory:directory)
        try clipboardStore.save(["clipboard only pair A"],pairID:pairA)

        try credentialStore.saveString("pair-token-B", for:"pair.token")
        state.loadSavedCredentials()
        let pairB = try XCTUnwrap(state.pairHistoryID)
        XCTAssertNotEqual(pairA,pairB)
        XCTAssertTrue(state.transfers.isEmpty)
        XCTAssertTrue(clipboardStore.load(pairID:pairB).isEmpty)
        _ = try state.offerFile(direction:"toPhone",name:"only pair B",size:0,sha256:String(repeating:"b",count:64))
        try clipboardStore.save(["clipboard only pair B"],pairID:pairB)

        try credentialStore.saveString("pair-token-A", for:"pair.token")
        state.loadSavedCredentials()
        XCTAssertEqual(state.transfers.map(\.name),["only pair A"])
        XCTAssertEqual(clipboardStore.load(pairID:pairA),["clipboard only pair A"])

        try credentialStore.saveString("pair-token-B", for:"pair.token")
        state.loadSavedCredentials()
        XCTAssertEqual(state.transfers.map(\.name),["only pair B"])
        XCTAssertEqual(clipboardStore.load(pairID:pairB),["clipboard only pair B"])

        XCTAssertEqual(defaults.data(forKey:"bridge.history"),legacyTransferData)
        XCTAssertEqual(try Data(contentsOf:legacyClipboardURL),legacyClipboardData)
    }

    func testClipboardHistoryRejectsInvalidPairIDAndCapsEntries() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString,isDirectory:true)
        defer { try? FileManager.default.removeItem(at:directory) }
        let store = PairClipboardHistoryStore(directory:directory)
        XCTAssertNoThrow(try store.save(["must not be stored"],pairID:"../invalid"))
        XCTAssertTrue(store.load(pairID:"../invalid").isEmpty)
        let pairID = String(repeating:"b",count:64)
        try store.save((0..<35).map(String.init),pairID:pairID)
        XCTAssertEqual(store.load(pairID:pairID).count,30)
    }

    func testRePairingRestoresEachPhonesTransferAndClipboardHistory() throws {
        let suite = "bridge-stable-pair-history-\(UUID().uuidString)", defaults = UserDefaults(suiteName:suite)!
        defer { defaults.removePersistentDomain(forName:suite) }
        let legacy = Data("unassigned legacy transfer history".utf8)
        defaults.set(legacy,forKey:"bridge.history")
        let credentials = MemoryCredentialStore()
        let state = BridgeState(keychain:credentials,name:"Mac",defaults:defaults)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString,isDirectory:true)
        try FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true)
        defer { try? FileManager.default.removeItem(at:directory) }
        let clipboardStore = PairClipboardHistoryStore(directory:directory)

        func pair(clientID: String, name: String) throws {
            let invitation = try state.startInvitation()
            let requestID = try state.requestPair(secret:invitation,name:name,clientID:clientID)
            try state.approvePair(true)
            let answer = try state.pairStatus(secret:invitation,id:requestID)
            XCTAssertEqual(answer["clientId"] as? String,clientID)
        }
        let phoneA = UUID().uuidString.lowercased(), phoneB = UUID().uuidString.lowercased()
        try pair(clientID:phoneA,name:"Телефон A")
        let pairAID = try XCTUnwrap(state.pairHistoryID)
        _ = try state.offerFile(direction:"toPhone",name:"A.txt",size:0,sha256:String(repeating:"a",count:64))
        try clipboardStore.save(["clipboard A"],pairID:pairAID)

        state.unpair()
        try pair(clientID:phoneB,name:"Телефон B")
        let pairBID = try XCTUnwrap(state.pairHistoryID)
        XCTAssertNotEqual(pairAID,pairBID)
        XCTAssertTrue(state.transfers.isEmpty)
        XCTAssertTrue(clipboardStore.load(pairID:pairBID).isEmpty)
        _ = try state.offerFile(direction:"toPhone",name:"B.txt",size:0,sha256:String(repeating:"b",count:64))
        try clipboardStore.save(["clipboard B"],pairID:pairBID)

        state.unpair()
        try pair(clientID:phoneA,name:"Телефон A")
        XCTAssertEqual(state.pairHistoryID,pairAID)
        XCTAssertEqual(state.transfers.map(\.name),["A.txt"])
        XCTAssertEqual(clipboardStore.load(pairID:pairAID),["clipboard A"])
        let restored = BridgeState(keychain:credentials,name:"Mac",defaults:defaults)
        XCTAssertEqual(restored.pairHistoryID,pairAID)
        XCTAssertEqual(restored.transfers.map(\.name),["A.txt"])
        XCTAssertEqual(defaults.data(forKey:"bridge.history"),legacy)
    }

    func testLegacyCurrentPairHistoryIsCopiedWhenStablePhoneIDArrives() throws {
        let suite = "bridge-pair-history-migration-\(UUID().uuidString)", defaults = UserDefaults(suiteName:suite)!
        defer { defaults.removePersistentDomain(forName:suite) }
        let credentials = MemoryCredentialStore()
        try credentials.saveString("old-pair-token",for:"pair.token")
        let state = BridgeState(keychain:credentials,name:"Mac",defaults:defaults)
        let oldID = try XCTUnwrap(state.pairHistoryID)
        _ = try state.offerFile(direction:"toPhone",name:"kept.txt",size:0,sha256:String(repeating:"c",count:64))

        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString,isDirectory:true)
        try FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true)
        defer { try? FileManager.default.removeItem(at:directory) }
        let clipboardStore = PairClipboardHistoryStore(directory:directory)
        try clipboardStore.save(["kept clipboard"],pairID:oldID)

        let phoneID = UUID().uuidString.lowercased()
        try state.recordPhoneClientID(phoneID,token:"old-pair-token")
        let newID = try XCTUnwrap(state.pairHistoryID)
        XCTAssertNotEqual(newID,oldID)
        XCTAssertEqual(state.transfers.map(\.name),["kept.txt"])
        XCTAssertEqual(state.clipboardHistoryMigration?.sourceID,oldID)
        XCTAssertEqual(state.clipboardHistoryMigration?.targetID,newID)
        try clipboardStore.copyHistoryIfNeeded(from:oldID,to:newID)
        state.completeClipboardHistoryMigration(sourceID:oldID,targetID:newID)
        XCTAssertEqual(clipboardStore.load(pairID:newID),["kept clipboard"])
        XCTAssertEqual(clipboardStore.load(pairID:oldID),["kept clipboard"])
        XCTAssertNil(state.clipboardHistoryMigration)
    }

    func testCancelledFileOfferCannotBeAcceptedLater() throws {
        let state = BridgeState(keychain:MemoryCredentialStore(), name:"Mac для теста")
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try Data("данные".utf8).write(to:file)
        defer { try? FileManager.default.removeItem(at:file) }
        let (size, hash) = try BridgeValidation.hash(file:file)
        let id = try state.offerFile(direction:"toPhone", name:"тест.txt", size:size, sha256:hash, file:file)
        try state.cancel(id)
        XCTAssertEqual(state.transfers.first(where:{$0.id == id})?.state, "cancelled")
        XCTAssertThrowsError(try state.accept(id))
    }

    func testCancellationNotifiesActiveConnectionAndRejectsCompletion() throws {
        let suite = "bridge-cancel-callback-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName:suite)!
        defer { defaults.removePersistentDomain(forName:suite) }
        let state = BridgeState(keychain:MemoryCredentialStore(), name:"Mac", defaults:defaults)
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try Data("test".utf8).write(to:file)
        defer { try? FileManager.default.removeItem(at:file) }
        let (size, hash) = try BridgeValidation.hash(file:file)
        let id = try state.offerFile(direction:"toPhone",name:"test.txt",size:size,sha256:hash,file:file)
        try state.accept(id)
        _ = try state.beginFile(id,direction:"toPhone")
        var notified: String?
        state.onTransferCancelled = { notified = $0 }
        try state.cancel(id)
        XCTAssertEqual(notified,id)
        XCTAssertThrowsError(try state.acknowledge(id,sha256:hash))
        XCTAssertEqual(state.reconciliationState(id),"cancelled")
    }

    func testRepeatedFileAcknowledgementAndCancellationAreSafe() throws {
        let suite = "bridge-idempotence-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName:suite)!
        defer { defaults.removePersistentDomain(forName:suite) }
        let state = BridgeState(keychain:MemoryCredentialStore(), name:"Mac", defaults:defaults)
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try Data("тест".utf8).write(to:file)
        defer { try? FileManager.default.removeItem(at:file) }
        let (size, hash) = try BridgeValidation.hash(file:file)
        let id = try state.offerFile(direction:"toPhone",name:"test.txt",size:size,sha256:hash,file:file)
        try state.accept(id)
        _ = try state.beginFile(id,direction:"toPhone")
        try state.acknowledge(id,sha256:hash)
        XCTAssertNoThrow(try state.acknowledge(id,sha256:hash))
        let restored = BridgeState(keychain:MemoryCredentialStore(), name:"Mac", defaults:defaults)
        XCTAssertEqual(restored.transfers.first(where:{$0.id == id})?.state, "complete")
        XCTAssertNoThrow(try restored.acknowledge(id,sha256:hash))
        XCTAssertThrowsError(try state.acknowledge(id,sha256:String(repeating:"0",count:64)))
        XCTAssertThrowsError(try state.cancel(id))
        let second = try state.offerFile(direction:"toPhone",name:"test.txt",size:size,sha256:hash,file:file)
        try state.cancel(second)
        XCTAssertNoThrow(try state.cancel(second))
    }

    func testInterruptedFileCanBeAcknowledgedAfterRestart() throws {
        let suite = "bridge-interrupted-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName:suite)!
        defer { defaults.removePersistentDomain(forName:suite) }
        let state = BridgeState(keychain:MemoryCredentialStore(),name:"Mac",defaults:defaults)
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try Data("файл".utf8).write(to:file)
        defer { try? FileManager.default.removeItem(at:file) }
        let (size, hash) = try BridgeValidation.hash(file:file)
        let id = try state.offerFile(direction:"toPhone",name:"a.txt",size:size,sha256:hash,file:file)
        try state.accept(id)
        _ = try state.beginFile(id,direction:"toPhone")
        let restored = BridgeState(keychain:MemoryCredentialStore(),name:"Mac",defaults:defaults)
        XCTAssertEqual(restored.transfers.first(where:{$0.id == id})?.state,"unknown")
        XCTAssertEqual(restored.reconciliationState(id),"unknown")
        XCTAssertEqual(restored.reconciliationState(UUID().uuidString),"unknown")
        XCTAssertThrowsError(try restored.acknowledge(id,sha256:String(repeating:"0",count:64)))
        try restored.acknowledge(id,sha256:hash)
        XCTAssertEqual(restored.transfers.first(where:{$0.id == id})?.state,"complete")
        XCTAssertEqual(restored.reconciliationState(id),"complete")
    }

    func testSavedUploadCanBeReconciledAfterLostResponseAndRestart() throws {
        let suite = "bridge-upload-recovery-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName:suite)!
        defer { defaults.removePersistentDomain(forName:suite) }
        let store = MemoryCredentialStore()
        let state = BridgeState(keychain:store,name:"Mac",defaults:defaults)
        let invitation = try state.startInvitation()
        let requestID = try state.requestPair(secret:invitation,name:"Galaxy")
        try state.approvePair(true)
        let token = try XCTUnwrap(state.pairStatus(secret:invitation,id:requestID)["token"] as? String)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true)
        defer { try? FileManager.default.removeItem(at:directory) }
        let temp = directory.appendingPathComponent(".test.part")
        try Data("файл".utf8).write(to:temp)
        let (size,hash) = try BridgeValidation.hash(file:temp)
        let id = try state.offerFile(direction:"toMac",name:"test.txt",size:size,sha256:hash)
        try state.accept(id,destination:directory)
        _ = try state.beginFile(id,direction:"toMac",length:size)
        _ = try state.finishUpload(id,token:token,temp:temp)
        let restored = BridgeState(keychain:store,name:"Mac",defaults:defaults)
        XCTAssertEqual(restored.reconciliationState(id),"complete")
        XCTAssertThrowsError(try restored.beginFile(id,direction:"toMac",length:size))
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath:directory.path),["test.txt"])
    }

    func testRestartAfterFileMoveBeforeResultIsUnknownAndDoesNotDuplicate() throws {
        let suite = "bridge-publication-gap-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName:suite)!
        defer { defaults.removePersistentDomain(forName:suite) }
        let state = BridgeState(keychain:MemoryCredentialStore(),name:"Mac",defaults:defaults)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true)
        defer { try? FileManager.default.removeItem(at:directory) }
        let temp = directory.appendingPathComponent(".test.part")
        let destination = directory.appendingPathComponent("test.txt")
        try Data("контрольный файл".utf8).write(to:temp)
        let (size,hash) = try BridgeValidation.hash(file:temp)
        let id = try state.offerFile(direction:"toMac",name:"test.txt",size:size,sha256:hash)
        try state.accept(id,destination:directory)
        _ = try state.beginFile(id,direction:"toMac",length:size)
        // Reproduce the file move inside finishUpload, then reconstruct state before complete is recorded.
        try FileManager.default.moveItem(at:temp,to:destination)
        let restored = BridgeState(keychain:MemoryCredentialStore(),name:"Mac",defaults:defaults)
        XCTAssertEqual(restored.reconciliationState(id),"unknown")
        XCTAssertThrowsError(try restored.beginFile(id,direction:"toMac",length:size))
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath:directory.path),["test.txt"])
        XCTAssertEqual(try BridgeValidation.hash(file:destination).1,hash)
    }

    func testRejectsAmbiguousHTTPRequestHeaders() throws {
        XCTAssertThrowsError(try HTTPRequest.parse("POST /v1/text HTTP/1.1\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\n"))
        XCTAssertThrowsError(try HTTPRequest.parse("POST /v1/text HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"))
        XCTAssertThrowsError(try HTTPRequest.parse("GET http://evil/ HTTP/1.1\r\n\r\n"))
        let valid = try HTTPRequest.parse("POST /v1/text HTTP/1.1\r\nContent-Length: 4\r\nAuthorization: Bearer test\r\n\r\n")
        XCTAssertEqual(valid.length, 4)
        XCTAssertEqual(valid.headers["authorization"], "Bearer test")
    }
}
