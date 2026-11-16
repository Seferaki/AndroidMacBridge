import AppKit
import UniformTypeIdentifiers

private struct FinderShareManifest: Codable {
    let requestID: String
    let files: [String]
}

final class ShareViewController: NSViewController {
    private let groupID = "group.dev.seferaki.SamsungMacBridge"
    private var providers: [NSItemProvider] = []
    private var requestStarted = false
    private let titleLabel = NSTextField(labelWithString: "Отправка на Samsung")
    private let detailLabel = NSTextField(wrappingLabelWithString: "")
    private let statusLabel = NSTextField(wrappingLabelWithString: "")

    override func loadView() {
        let root = NSView(frame: NSRect(x: 0, y: 0, width: 350, height: 118))
        root.wantsLayer = true

        titleLabel.font = .systemFont(ofSize: 15, weight: .semibold)
        detailLabel.font = .systemFont(ofSize: 12)
        detailLabel.textColor = .secondaryLabelColor
        statusLabel.font = .systemFont(ofSize: 11)
        statusLabel.textColor = .secondaryLabelColor

        let stack = NSStackView(views: [titleLabel, detailLabel, statusLabel])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.distribution = .fill
        stack.spacing = 10
        stack.translatesAutoresizingMaskIntoConstraints = false
        root.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: root.leadingAnchor, constant: 18),
            stack.trailingAnchor.constraint(equalTo: root.trailingAnchor, constant: -18),
            stack.centerYAnchor.constraint(equalTo: root.centerYAnchor),
        ])
        preferredContentSize = root.frame.size
        view = root
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? [])
            .flatMap { $0.attachments ?? [] }
            .filter { $0.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) }
        let count = providers.count
        if count > 0 {
            detailLabel.stringValue = count == 1 ? "Выбран 1 файл" : "Выбрано файлов: \(count)"
            statusLabel.stringValue = "Файл автоматически добавится в очередь передачи…"
            DispatchQueue.main.async { [weak self] in self?.addToQueue() }
        } else {
            detailLabel.stringValue = "Не удалось получить выбранные файлы из Finder."
            statusLabel.stringValue = "Закройте меню и попробуйте ещё раз."
        }
    }

    private func addToQueue() {
        guard !requestStarted, !providers.isEmpty else { return }
        requestStarted = true
        guard let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: groupID) else {
            statusLabel.stringValue = "Не удалось открыть общую очередь приложения."
            return
        }
        statusLabel.stringValue = "Подготовка файлов…"

        let requestID = UUID().uuidString.lowercased()
        let inbox = container.appendingPathComponent("FinderShareInbox", isDirectory: true)
        let requestDirectory = inbox.appendingPathComponent(requestID, isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: requestDirectory, withIntermediateDirectories: true)
        } catch {
            statusLabel.stringValue = "Не удалось подготовить очередь: \(error.localizedDescription)"
            return
        }

        let group = DispatchGroup()
        let lock = NSLock()
        var copied: [(Int, String)] = []
        var errors: [String] = []
        for (index, provider) in providers.enumerated() {
            group.enter()
            provider.loadFileRepresentation(forTypeIdentifier: UTType.fileURL.identifier) { source, error in
                defer { group.leave() }
                guard let source else {
                    lock.lock(); errors.append(error?.localizedDescription ?? "Файл недоступен"); lock.unlock()
                    return
                }
                let hasScope = source.startAccessingSecurityScopedResource()
                defer { if hasScope { source.stopAccessingSecurityScopedResource() } }
                do {
                    let destination = self.uniqueDestination(in: requestDirectory, preferredName: source.lastPathComponent)
                    try FileManager.default.copyItem(at: source, to: destination)
                    lock.lock(); copied.append((index, destination.lastPathComponent)); lock.unlock()
                } catch {
                    lock.lock(); errors.append(error.localizedDescription); lock.unlock()
                }
            }
        }

        group.notify(queue: .main) { [weak self] in
            guard let self else { return }
            guard errors.isEmpty, copied.count == self.providers.count else {
                try? FileManager.default.removeItem(at: requestDirectory)
                self.statusLabel.stringValue = errors.first.map { "Не удалось подготовить файл: \($0)" } ?? "Не удалось подготовить все выбранные файлы."
                return
            }
            let manifest = FinderShareManifest(requestID: requestID, files: copied.sorted { $0.0 < $1.0 }.map(\.1))
            do {
                let data = try JSONEncoder().encode(manifest)
                try data.write(to: requestDirectory.appendingPathComponent("manifest.json"), options: .atomic)
                self.statusLabel.stringValue = "Добавлено в очередь. Передача начнётся автоматически при подключении телефона."
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.55) {
                    self.extensionContext?.completeRequest(returningItems: nil)
                }
            } catch {
                try? FileManager.default.removeItem(at: requestDirectory)
                self.statusLabel.stringValue = "Не удалось добавить файлы в очередь: \(error.localizedDescription)"
            }
        }
    }

    private func uniqueDestination(in directory: URL, preferredName: String) -> URL {
        let original = URL(fileURLWithPath: preferredName)
        let stem = original.deletingPathExtension().lastPathComponent
        let ext = original.pathExtension
        var candidate = directory.appendingPathComponent(preferredName)
        var suffix = 2
        while FileManager.default.fileExists(atPath: candidate.path) {
            let name = ext.isEmpty ? "\(stem) (\(suffix))" : "\(stem) (\(suffix)).\(ext)"
            candidate = directory.appendingPathComponent(name)
            suffix += 1
        }
        return candidate
    }
}
