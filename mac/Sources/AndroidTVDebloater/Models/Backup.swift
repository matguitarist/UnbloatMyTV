import Foundation

/// One setting changed by a run, with its value before. Same JSON as the Windows app.
struct SettingChange: Codable {
    /// "global", "secure", "system", or "device_config:<namespace>".
    var namespace: String
    var key: String
    /// Value before we touched it; nil means it was not set.
    var oldValue: String?
    var newValue: String

    enum CodingKeys: String, CodingKey {
        case namespace = "Namespace", key = "Key", oldValue = "OldValue", newValue = "NewValue"
    }
}

/// An access removed in the privacy check-up; Restore gives it back.
struct PermissionChange: Codable {
    var package: String
    var permission: String

    enum CodingKeys: String, CodingKey { case package = "Package", permission = "Permission" }
}

/// Everything one run changed, with the original values, so it can be undone exactly.
/// Files are interchangeable with the Windows/Linux app.
final class Backup: Codable {
    var created: String
    var deviceName: String
    var serialNo: String
    var androidVersion: String
    var disabledPackages: [String] = []
    /// Apps a choice turned back on (e.g. for the screensaver); Restore turns them off again.
    var enabledPackages: [String] = []
    var settings: [SettingChange] = []
    var wifiWasOn: Bool?
    var revokedPermissions: [PermissionChange] = []

    var fileURL: URL = URL(fileURLWithPath: "/")

    enum CodingKeys: String, CodingKey {
        case created = "Created", deviceName = "DeviceName", serialNo = "SerialNo", androidVersion = "AndroidVersion"
        case disabledPackages = "DisabledPackages", enabledPackages = "EnabledPackages", settings = "Settings", wifiWasOn = "WifiWasOn"
        case revokedPermissions = "RevokedPermissions"
    }

    // Older backups have no EnabledPackages or RevokedPermissions.
    required init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        created = try c.decode(String.self, forKey: .created)
        deviceName = try c.decodeIfPresent(String.self, forKey: .deviceName) ?? ""
        serialNo = try c.decodeIfPresent(String.self, forKey: .serialNo) ?? ""
        androidVersion = try c.decodeIfPresent(String.self, forKey: .androidVersion) ?? ""
        disabledPackages = try c.decodeIfPresent([String].self, forKey: .disabledPackages) ?? []
        enabledPackages = try c.decodeIfPresent([String].self, forKey: .enabledPackages) ?? []
        settings = try c.decodeIfPresent([SettingChange].self, forKey: .settings) ?? []
        wifiWasOn = try c.decodeIfPresent(Bool.self, forKey: .wifiWasOn)
        revokedPermissions = try c.decodeIfPresent([PermissionChange].self, forKey: .revokedPermissions) ?? []
    }

    init(device: TvDevice) {
        created = DateText.now()
        deviceName = device.displayName
        serialNo = device.serialNo
        androidVersion = device.androidVersion
    }

    var changeCount: Int { disabledPackages.count + enabledPackages.count + settings.count + (wifiWasOn == true ? 1 : 0) + revokedPermissions.count }
    var createdDate: Date { DateText.parse(created) ?? .distantPast }
    var createdText: String { DateText.display(created) }

    static func folder(for dev: TvDevice) -> URL {
        let id = dev.serialNo.trimmingCharacters(in: .whitespaces).isEmpty ? dev.serial : dev.serialNo
        let safe = String("\(dev.displayName)-\(id)".map { $0.isLetter || $0.isNumber || $0 == "-" ? $0 : "_" })
        return AppPaths.backupDir.appendingPathComponent(safe, isDirectory: true)
    }

    static func start(_ dev: TvDevice) -> Backup {
        let folder = folder(for: dev)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let b = Backup(device: dev)
        let f = DateFormatter()
        f.dateFormat = "yyyyMMdd-HHmmss"
        f.locale = Locale(identifier: "en_US_POSIX")
        b.fileURL = folder.appendingPathComponent("changes-\(f.string(from: Date())).json")
        return b
    }

    func save() throws {
        try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        let e = JSONEncoder()
        e.outputFormatting = [.prettyPrinted]
        try e.encode(self).write(to: fileURL, options: .atomic)
    }

    static func list(for dev: TvDevice) -> [Backup] {
        let folder = folder(for: dev)
        guard let files = try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil) else { return [] }
        return files
            .filter { $0.lastPathComponent.hasPrefix("changes-") && $0.pathExtension == "json" }
            .compactMap { url -> Backup? in
                guard let data = try? Data(contentsOf: url), let b = try? JSONDecoder().decode(Backup.self, from: data) else { return nil }
                b.fileURL = url
                return b.changeCount > 0 ? b : nil
            }
            .sorted { $0.createdDate > $1.createdDate }
    }
}

/// Dates as text, readable by both apps (.NET writes up to 7 fractional digits).
enum DateText {
    static func now() -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        f.timeZone = .current
        return f.string(from: Date())
    }

    static func parse(_ s: String) -> Date? {
        // Drop fractional seconds, which ISO8601DateFormatter only accepts with exactly 3 digits.
        let trimmed = s.replacingOccurrences(of: #"\.\d+"#, with: "", options: .regularExpression)
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        if let d = f.date(from: trimmed) { return d }
        // No time zone: local time.
        let local = DateFormatter()
        local.locale = Locale(identifier: "en_US_POSIX")
        local.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        return local.date(from: trimmed)
    }

    static func display(_ s: String) -> String {
        guard let d = parse(s) else { return s }
        let f = DateFormatter()
        f.locale = Lang.locale
        f.dateStyle = .short
        f.timeStyle = .short
        return f.string(from: d)
    }
}

/// Reading and writing settings, recording old values in the backup first.
enum SettingsIO {
    private static func split(_ ns: String) -> (cmd: String, ns: String) {
        ns.hasPrefix("device_config:") ? ("device_config", String(ns.dropFirst("device_config:".count))) : ("settings", ns)
    }

    static func get(_ adb: Adb, _ serial: String, _ ns: String, _ key: String) async -> String? {
        let (cmd, n) = split(ns)
        let v = await adb.shell(serial, "\(cmd) get \(n) \(key)").output.trimmingCharacters(in: .whitespacesAndNewlines)
        return v.isEmpty || v == "null" ? nil : v
    }

    static func change(_ ctx: ApplyContext, _ ns: String, _ key: String, _ value: String) async -> Bool {
        let old = await get(ctx.adb, ctx.serial, ns, key)
        // Keep the first original value if the same key is changed twice in one run.
        if !ctx.backup.settings.contains(where: { $0.namespace == ns && $0.key == key }) {
            ctx.backup.settings.append(SettingChange(namespace: ns, key: key, oldValue: old, newValue: value))
            try? ctx.backup.save()
        }
        let (cmd, n) = split(ns)
        _ = await ctx.adb.shell(ctx.serial, "\(cmd) put \(n) \(key) \(quote(value))")
        return await get(ctx.adb, ctx.serial, ns, key) == value
    }

    static func restore(_ adb: Adb, _ serial: String, _ ns: String, _ key: String, _ oldValue: String?) async {
        let (cmd, n) = split(ns)
        if let oldValue {
            _ = await adb.shell(serial, "\(cmd) put \(n) \(key) \(quote(oldValue))")
        } else {
            _ = await adb.shell(serial, "\(cmd) delete \(n) \(key)")
        }
    }

    static func quote(_ v: String) -> String {
        let safe = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "._-/=:,"))
        return v.unicodeScalars.allSatisfy(safe.contains) ? v : "'" + v.replacingOccurrences(of: "'", with: "'\\''") + "'"
    }
}
