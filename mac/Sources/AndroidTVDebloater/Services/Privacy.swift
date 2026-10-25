import Foundation

/// One kind of access an app can be given ("Location", "Microphone"…) and the permissions behind it.
struct AccessKind: Hashable {
    var key: String
    var permissions: [String]
}

/// An app that has one kind of access, with the granted permissions that removing it would revoke.
struct AppAccess {
    var package: String
    var kind: AccessKind
    var granted: [String]
    var isThirdParty: Bool
}

/// Which apps can use location, camera, microphone and personal data. Reading changes nothing. Same rules as the Windows app.
enum Privacy {
    private static let prefix = "android.permission."

    /// In the order shown. "BgLocation" removes only "all the time", so the app keeps it while open.
    static let kinds: [AccessKind] = [
        AccessKind(key: "Microphone", permissions: ["RECORD_AUDIO"]),
        AccessKind(key: "Camera", permissions: ["CAMERA"]),
        AccessKind(key: "BgLocation", permissions: ["ACCESS_BACKGROUND_LOCATION"]),
        AccessKind(key: "Location", permissions: ["ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION"]),
        AccessKind(key: "Contacts", permissions: ["READ_CONTACTS", "WRITE_CONTACTS"]),
        AccessKind(key: "Calendar", permissions: ["READ_CALENDAR", "WRITE_CALENDAR"]),
    ]

    /// Apps you installed and preinstalled apps with an icon. System parts without an icon (voice search,
    /// TV inputs…) and everything the TV needs are left out: removing their access breaks things.
    @MainActor static func read(_ adb: Adb, device dev: TvDevice) async -> [AppAccess] {
        let withIcon = await UserApps.withIcon(adb, serial: dev.serial)

        let wanted = Array(Set(kinds.flatMap(\.permissions))).sorted().joined(separator: "|")
        let dump = await adb.shell(dev.serial,
            "dumpsys package packages | grep -E '^Hidden|^  Package \\[|^    User [0-9]+:|permission\\.(\(wanted)): granted=true'",
            timeout: 60).output.replacingOccurrences(of: "\r", with: "")
        // Only the installed packages, not the original copies of updated system apps listed after them.
        let text = "\n" + (dump.components(separatedBy: "\nHidden").first ?? "")

        var result: [AppAccess] = []
        for block in text.components(separatedBy: "\n  Package [").dropFirst() {
            guard let end = block.firstIndex(of: "]") else { continue }
            let pkg = String(block[..<end])
            let thirdParty = dev.thirdParty.contains(pkg)
            if !dev.enabled.contains(pkg) || dev.isProtected(pkg) || Catalog.isKeep(pkg) { continue }
            if !thirdParty && !withIcon.contains(pkg) { continue }

            // Main user's permissions only: "    User 0: …" up to the next user.
            let users = block.components(separatedBy: "\n    User ")
            guard let user0 = users.first(where: { $0.hasPrefix("0:") }) else { continue }
            var granted = Set<String>()
            for line in user0.split(separator: "\n") where line.contains("granted=true") {
                guard let r = line.range(of: prefix), let colon = line[r.upperBound...].firstIndex(of: ":") else { continue }
                if line.contains("SYSTEM_FIXED") || line.contains("POLICY_FIXED") { continue }
                granted.insert(String(line[r.upperBound..<colon]))
            }

            for kind in kinds {
                let g = kind.permissions.filter(granted.contains)
                // Location counts only for precise or approximate; background alone is shown under "all the time".
                if kind.key == "Location" && !g.contains(where: { $0 != "ACCESS_BACKGROUND_LOCATION" }) { continue }
                if !g.isEmpty { result.append(AppAccess(package: pkg, kind: kind, granted: g.map { prefix + $0 }, isThirdParty: thirdParty)) }
            }
        }
        return result
    }

    /// Nil when it worked, else the TV's error.
    static func revoke(_ adb: Adb, serial: String, package: String, permission: String) async -> String? {
        let r = await adb.shell(serial, "pm revoke --user 0 \(package) \(permission)")
        return r.ok && !r.all.contains("Exception") ? nil : r.all
    }
}
