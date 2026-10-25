import Foundation

/// App names and pictures as the TV shows them, from Android itself. A tiny helper (helper/umtv-helper.dex)
/// is copied to the TV's temp folder, run with app_process, and deleted: nothing is installed.
/// Same helper and rules as the Windows app.
@MainActor
enum TvIcons {
    struct Entry {
        /// The app's name in the TV's language; "" when the TV didn't give one.
        let label: String
        /// The app's banner (its tile on the TV's home screen), or else its icon centred, `height` pixels high
        /// and 16:9 wide; nil when the TV didn't draw one.
        let png: Data?
    }

    static let height = 90
    private static let remote = "/data/local/tmp/umtv-helper.dex"
    /// Keyed by TV, package and last update, so a refresh only asks for what changed.
    private static var cache: [String: Entry] = [:]

    private static func key(_ serial: String, _ a: InstalledApp) -> String {
        "\(serial)|\(a.package)|\(a.updated?.timeIntervalSince1970 ?? 0)"
    }

    /// Reading changes nothing on the TV, apart from the helper that comes and goes in its temp folder.
    static func read(_ adb: Adb, serial: String, apps: [InstalledApp]) async -> [String: Entry] {
        let missing = apps.filter { cache[key(serial, $0)] == nil }
        if !missing.isEmpty { await fetch(adb, serial, missing) }
        var found: [String: Entry] = [:]
        for a in apps { if let e = cache[key(serial, a)] { found[a.package] = e } }
        return found
    }

    private static func fetch(_ adb: Adb, _ serial: String, _ apps: [InstalledApp]) async {
        guard let local = Bundle.main.url(forResource: "umtv-helper", withExtension: "dex") else { return }
        defer { Task { _ = await adb.shell(serial, "rm -f \(remote)") } }
        guard await adb.run(["-s", serial, "push", local.path, remote], timeout: 30).ok else { return }
        // A few dozen at a time keeps the command line short on older TVs.
        for start in stride(from: 0, to: apps.count, by: 40) {
            let chunk = apps[start..<min(start + 40, apps.count)]
            let byPkg = Dictionary(chunk.map { ($0.package, $0) }, uniquingKeysWith: { a, _ in a })
            let r = await adb.run(["-s", serial, "exec-out",
                "CLASSPATH=\(remote) app_process / app.unbloatmytv.AppIcons \(height) \(chunk.map(\.package).joined(separator: " "))"],
                timeout: 60)
            for line in r.output.split(separator: "\n") {
                let f = line.split(separator: "\t", omittingEmptySubsequences: false)
                guard f.count >= 3, let app = byPkg[String(f[0])] else { continue }
                cache[key(serial, app)] = Entry(label: f[1].trimmingCharacters(in: .whitespaces),
                                                png: f[2].isEmpty ? nil : Data(base64Encoded: String(f[2])))
            }
        }
    }
}
