import Foundation

struct FinderShareManifest: Codable {
    let requestID: String
    var files: [String]
}

struct FinderShareRequest {
    let manifest: FinderShareManifest
    let directory: URL
    let files: [URL]
}

final class FinderShareInbox {
    static let appGroup = "group.dev.seferaki.SamsungMacBridge"
    private let root: URL?
    private let fileManager = FileManager.default

    init() {
        root = fileManager.containerURL(forSecurityApplicationGroupIdentifier: Self.appGroup)?
            .appendingPathComponent("FinderShareInbox", isDirectory: true)
        if let root { try? fileManager.createDirectory(at: root, withIntermediateDirectories: true) }
    }

    func pendingRequests() throws -> [FinderShareRequest] {
        guard let root else { return [] }
        let directories = try fileManager.contentsOfDirectory(
            at: root,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        )
        return directories.sorted { $0.lastPathComponent < $1.lastPathComponent }.compactMap { directory in
            let manifestURL = directory.appendingPathComponent("manifest.json")
            guard let data = try? Data(contentsOf: manifestURL),
                  let manifest = try? JSONDecoder().decode(FinderShareManifest.self, from: data) else { return nil }
            let urls = manifest.files.compactMap { name -> URL? in
                guard !name.isEmpty, name != ".", name != "..",
                      name == URL(fileURLWithPath: name).lastPathComponent,
                      !name.unicodeScalars.contains(where: { $0.value == 0 }) else { return nil }
                let candidate = directory.appendingPathComponent(name).standardizedFileURL
                guard isInsideInbox(candidate),
                      let values = try? candidate.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey]),
                      values.isRegularFile == true, values.isSymbolicLink != true else { return nil }
                return candidate
            }
            guard !urls.isEmpty else { return nil }
            return FinderShareRequest(manifest: manifest, directory: directory, files: urls)
        }
    }

    @discardableResult func acknowledgeFile(requestID: String, name: String) -> Bool {
        guard let directory = requestDirectory(requestID),
              let manifestURL = manifestURL(in: directory),
              let data = try? Data(contentsOf: manifestURL),
              var manifest = try? JSONDecoder().decode(FinderShareManifest.self, from: data) else { return true }
        manifest.files.removeAll { $0 == name }
        if manifest.files.isEmpty {
            try? fileManager.removeItem(at: manifestURL)
            return true
        } else if let updated = try? JSONEncoder().encode(manifest) {
            try? updated.write(to: manifestURL, options: .atomic)
        }
        return false
    }

    func removeStagedFile(at path: String) {
        let url = URL(fileURLWithPath: path).standardizedFileURL
        guard isInsideInbox(url) else { return }
        try? fileManager.removeItem(at: url)
        let directory = url.deletingLastPathComponent()
        if let contents = try? fileManager.contentsOfDirectory(atPath: directory.path), contents.isEmpty {
            try? fileManager.removeItem(at: directory)
        }
    }

    private func requestDirectory(_ id: String) -> URL? {
        guard UUID(uuidString: id) != nil, let root else { return nil }
        let directory = root.appendingPathComponent(id, isDirectory: true).standardizedFileURL
        return isInsideInbox(directory) ? directory : nil
    }

    private func manifestURL(in directory: URL) -> URL? {
        let url = directory.appendingPathComponent("manifest.json").standardizedFileURL
        return isInsideInbox(url) ? url : nil
    }

    private func isInsideInbox(_ url: URL) -> Bool {
        guard let root else { return false }
        let prefix = root.standardizedFileURL.path + "/"
        return url.standardizedFileURL.path.hasPrefix(prefix)
    }
}
