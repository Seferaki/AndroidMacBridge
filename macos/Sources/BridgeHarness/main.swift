import Foundation
import BridgeCore

private final class MemoryCredentialStore: BridgeCredentialStore {
    private var values: [String:String] = [:]
    func loadString(_ name: String) throws -> String? { values[name] }
    func saveString(_ value: String, for name: String) throws { values[name] = value }
    func delete(_ name: String) throws { values.removeValue(forKey:name) }
}

private func check(_ condition: @autoclosure () -> Bool, _ message: String) throws {
    guard condition() else { throw BridgeError.invalid("Проверка не пройдена: " + message) }
}

let valid = try HTTPRequest.parse("POST /v1/text HTTP/1.1\r\nContent-Length: 4\r\nAuthorization: Bearer test\r\n\r\n")
try check(valid.length == 4, "разбор размера HTTP-запроса")
try check(valid.headers["authorization"] == "Bearer test", "разбор авторизации")
let duplicateHeaderWasRejected = (try? HTTPRequest.parse("POST /v1/text HTTP/1.1\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\n")) == nil
try check(duplicateHeaderWasRejected, "отклонение повторяющегося Content-Length")
try check(BridgeValidation.validName("фото 1.jpg"), "поддержка имени файла с кириллицей")
try check(!BridgeValidation.validName("../secret"), "запрет обхода папки")

let limits = BridgeState(keychain:MemoryCredentialStore(), name:"Тест ограничений")
let largestText = String(repeating:"x", count:BridgeValidation.maxText)
try limits.addText(direction:"toPhone", type:"text", text:largestText)
var oversizedTextWasAccepted = false
do { try limits.addText(direction:"toPhone", type:"text", text:largestText + "x"); oversizedTextWasAccepted = true } catch { }
try check(!oversizedTextWasAccepted, "отклонение текста больше 1 МиБ")
var unsupportedLinkWasAccepted = false
do { try limits.addText(direction:"toPhone", type:"link", text:"ftp://example.com/file"); unsupportedLinkWasAccepted = true } catch { }
try check(!unsupportedLinkWasAccepted, "отклонение ссылки с неподдерживаемой схемой")
let maximumFile = try limits.offerFile(direction:"toPhone", name:"large.bin", size:BridgeValidation.maxFile, sha256:String(repeating:"a", count:64))
try limits.cancel(maximumFile)
var oversizedFileWasAccepted = false
do { try limits.offerFile(direction:"toPhone", name:"too-large.bin", size:BridgeValidation.maxFile + 1, sha256:String(repeating:"a", count:64)); oversizedFileWasAccepted = true } catch { }
try check(!oversizedFileWasAccepted, "отклонение файла больше 1 ГиБ")

let failureDirectory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory:true)
try FileManager.default.createDirectory(at:failureDirectory, withIntermediateDirectories:true)
defer { try? FileManager.default.removeItem(at:failureDirectory) }
let partialFile = failureDirectory.appendingPathComponent(".bridge-checksum.part")
try Data("полученные тестовые данные".utf8).write(to:partialFile)
let (_, correctDigest) = try BridgeValidation.hash(file:partialFile)
let wrongDigest = correctDigest == String(repeating:"0", count:64) ? String(repeating:"1", count:64) : String(repeating:"0", count:64)
let failedUpload = BridgeState(keychain:MemoryCredentialStore(), name:"Тест очистки")
let failedID = try failedUpload.offerFile(direction:"toMac", name:"checksum.txt", size:Int64("полученные тестовые данные".utf8.count), sha256:wrongDigest)
try failedUpload.accept(failedID, destination:failureDirectory)
let (failedTransfer, _) = try failedUpload.beginFile(failedID, direction:"toMac", length:Int64("полученные тестовые данные".utf8.count))
var wrongChecksumWasRejected = false
do { try BridgeValidation.verifySHA256(correctDigest, equals:failedTransfer.sha256, deleting:partialFile) } catch { wrongChecksumWasRejected = true; failedUpload.markFailed(failedID) }
try check(wrongChecksumWasRejected, "ошибочная SHA-256 отклонена")
try check(!FileManager.default.fileExists(atPath:partialFile.path), "временный файл удалён после ошибки SHA-256")
try check(failedUpload.transfers.first(where:{$0.id == failedID})?.state == "failed", "передача с ошибочной SHA-256 отмечена неуспешной")

let state = BridgeState(keychain:MemoryCredentialStore(), name:"Тестовый Mac")
let invitation = try state.startInvitation()
let requestID = try state.requestPair(secret:invitation, name:"Galaxy S25")
try check(state.pairPrompt == "Galaxy S25", "ожидание подтверждения сопряжения")
try state.approvePair(true)
let answer = try state.pairStatus(secret:invitation, id:requestID)
let token = answer["token"] as? String ?? ""
try check(!token.isEmpty && state.authorize(token), "подтверждённое сопряжение выдаёт рабочий токен")
state.unpair()
try check(!state.authorize(token), "отзыв пары блокирует прежний токен")

let cancelled = BridgeState(keychain:MemoryCredentialStore(), name:"Тестовый Mac")
let id = try cancelled.offerFile(direction:"toPhone", name:"тест.txt", size:0, sha256:"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
try cancelled.cancel(id)
try check(cancelled.transfers.first(where:{$0.id == id})?.state == "cancelled", "отмена предложения файла")
var cancelledFileWasAccepted = false
do { try cancelled.accept(id); cancelledFileWasAccepted = true } catch { }
try check(!cancelledFileWasAccepted, "отменённую передачу нельзя принять повторно")

print("Все локальные проверки пройдены: HTTP, имена файлов, пределы данных, SHA-256 и очистка, сопряжение, отзыв пары и отмена.")
