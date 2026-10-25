import Foundation

enum NetworkKind: Sendable { case unknown, ethernet, wifi }

/// What we know about the connected TV. Read-only queries only.
struct TvDevice: Sendable {
    var serial: String
    var manufacturer = ""
    var brand = ""
    var model = ""
    var serialNo = ""
    var androidVersion = ""
    var sdk = 0
    /// "Fire OS 7.6.8.8 (PS7688/4591)" on Fire TV, empty elsewhere.
    var fireOsVersion = ""
    var ramMb: Int64 = 0
    var network: NetworkKind = .unknown
    var homePackage = ""
    /// Every app that can act as the home screen, not just the current one.
    var homeCapable: Set<String> = []
    var inputMethodPackage = ""
    var screensaverComponent = ""
    var setupComplete = false
    var storageTotalMb: Int64 = 0
    var storageFreeMb: Int64 = 0
    var enabled: Set<String> = []
    var disabled: Set<String> = []
    var thirdParty: Set<String> = []

    /// Amazon Fire TV: Fire OS refuses to switch off Amazon's own apps.
    var isFireTv: Bool { manufacturer.caseInsensitiveCompare("Amazon") == .orderedSame }

    func isInstalled(_ pkg: String) -> Bool { enabled.contains(pkg) || disabled.contains(pkg) }

    var displayName: String {
        if isFireTv { return Self.fireModels[model] ?? "Fire TV \(model)" }
        let maker = brand.trimmingCharacters(in: .whitespaces).isEmpty ? manufacturer : brand
        return maker.isEmpty || model.lowercased().hasPrefix(maker.lowercased()) ? model : "\(maker) \(model)"
    }

    /// Fire TV model codes are not shown anywhere on the device.
    static let fireModels = [
        "AFTKA": "Fire TV Stick 4K Max", "AFTKRT": "Fire TV Stick 4K Max (2nd gen)",
        "AFTMM": "Fire TV Stick 4K", "AFTKM": "Fire TV Stick 4K (2nd gen)",
        "AFTSSS": "Fire TV Stick (3rd gen)", "AFTSS": "Fire TV Stick Lite",
        "AFTR": "Fire TV Cube (2nd gen)", "AFTGAZL": "Fire TV Cube (3rd gen)",
    ]

    var ipAddress: String {
        guard let i = serial.lastIndex(of: ":") else { return serial }
        return String(serial[..<i])
    }

    /// Packages that must never be disabled, whatever the catalog says.
    @MainActor func isProtected(_ pkg: String) -> Bool {
        pkg == homePackage || homeCapable.contains(pkg) || pkg == inputMethodPackage || Self.protectedPackages.contains(pkg)
            || (isFireTv && Self.fireProtectedPrefixes.contains { pkg.hasPrefix($0) } && !Catalog.isListed(pkg))
    }

    /// Fire OS answers "Cannot disable a protected package" for many of Amazon's apps, and others hold the
    /// TV together: only the Amazon apps the catalog lists, tested on Fire TV, are offered.
    static let fireProtectedPrefixes = ["com.amazon.", "amazon.", "android.amazon.", "com.fireos.", "com.ivona."]

    static let protectedPackages: Set<String> = [
        "android", "com.android.systemui", "com.android.settings", "com.android.tv.settings",
        "com.android.providers.settings", "com.android.providers.tv", "com.android.bluetooth",
        "com.android.shell", "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
        "com.google.android.webview", "com.android.webview", "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx", "com.google.android.leanbacklauncher",
        "com.android.tv", "com.google.android.permissioncontroller", "com.android.permissioncontroller",
        "com.google.android.inputmethod.latin", "com.android.inputdevices",
    ]

    static func load(_ adb: Adb, serial: String) async -> TvDevice {
        var d = TvDevice(serial: serial)
        let props = await adb.shell(serial,
            "getprop ro.product.manufacturer; getprop ro.product.brand; getprop ro.product.model; " +
            "getprop ro.serialno; getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.build.version.name")
        let p = props.output.components(separatedBy: "\n").map { $0.trimmingCharacters(in: .whitespaces) }
        func prop(_ i: Int) -> String { i < p.count ? p[i] : "" }
        d.manufacturer = prop(0); d.brand = prop(1); d.model = prop(2)
        d.serialNo = prop(3); d.androidVersion = prop(4); d.sdk = Int(prop(5)) ?? 0
        d.fireOsVersion = prop(6).hasPrefix("Fire OS") ? prop(6) : ""

        let mem = await adb.shell(serial, "grep MemTotal /proc/meminfo")
        d.ramMb = (Int64(mem.output.filter(\.isNumber)) ?? 0) / 1024

        d.homePackage = await currentHome(adb, serial: serial)
        let homes = await adb.shell(serial,
            "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
        d.homeCapable = Set(homes.output.components(separatedBy: "\n")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { $0.contains("/") && !$0.contains("=") && !$0.contains(" ") }
            .map { String($0.split(separator: "/")[0]) })

        let ime = await adb.shell(serial, "settings get secure default_input_method").output
            .trimmingCharacters(in: .whitespacesAndNewlines)
        d.inputMethodPackage = ime.contains("/") ? String(ime.split(separator: "/")[0]) : ""

        let dream = await adb.shell(serial, "settings get secure screensaver_components").output
            .trimmingCharacters(in: .whitespacesAndNewlines)
        d.screensaverComponent = dream == "null" ? "" : dream
        d.setupComplete = await adb.shell(serial, "settings get secure user_setup_complete").output
            .trimmingCharacters(in: .whitespacesAndNewlines) == "1"

        let storage = await readStorage(adb, serial: serial)
        d.storageTotalMb = storage.totalMb
        d.storageFreeMb = storage.freeMb

        let net = await adb.shell(serial, "dumpsys connectivity | grep -E 'NetworkAgentInfo.*CONNECTED/CONNECTED'").output.lowercased()
        d.network = net.contains("type: ethernet") ? .ethernet : net.contains("type: wifi") ? .wifi : .unknown

        d.enabled = await listPackages(adb, serial: serial, flag: "-e")
        d.disabled = await listPackages(adb, serial: serial, flag: "-d")
        d.thirdParty = await listPackages(adb, serial: serial, flag: "-3")
        return d
    }

    static func currentHome(_ adb: Adb, serial: String) async -> String {
        let r = await adb.shell(serial,
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
        let line = r.output.split(separator: "\n").last.map(String.init) ?? ""
        return line.contains("/") ? String(line.split(separator: "/")[0]).trimmingCharacters(in: .whitespaces) : ""
    }

    /// Size and free space of the app storage (/data), from "df -k". Zeros if unreadable.
    static func readStorage(_ adb: Adb, serial: String) async -> (totalMb: Int64, freeMb: Int64) {
        let r = await adb.shell(serial, "df -k /data")
        let last = r.output.split(separator: "\n").last.map(String.init) ?? ""
        let cols = last.split(separator: " ")
        // Filesystem 1K-blocks Used Available Use% Mounted-on
        guard cols.count >= 4, let total = Int64(cols[1]), let free = Int64(cols[3]) else { return (0, 0) }
        return (total / 1024, free / 1024)
    }

    private static func listPackages(_ adb: Adb, serial: String, flag: String) async -> Set<String> {
        let r = await adb.shell(serial, "pm list packages \(flag)", timeout: 30)
        return Set(r.output.components(separatedBy: "\n")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { $0.hasPrefix("package:") }
            .map { String($0.dropFirst("package:".count)) })
    }
}
