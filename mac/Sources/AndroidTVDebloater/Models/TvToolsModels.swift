import AppKit
import Foundation
import Observation

// MARK: - TV info

struct InfoRow: Identifiable {
    var label: String
    var value: String
    var id: String { label }
}

/// Software, updates and boot security, with shortcuts to the TV's own screens and a restart button.
/// Reading changes nothing; buttons only open screens on the TV, except Restart, which asks first.
@MainActor @Observable
final class TvInfoModel {
    weak var app: AppModel?
    private(set) var device: TvDevice
    var isLoading = false
    var info: TvInfo?
    var status = ""
    var statusIsError = false
    /// Restart pressed, waiting for a yes.
    var isConfirmingRestart = false
    /// The TV is restarting: the page waits for it to come back.
    var isRestarting = false

    init(app: AppModel, device: TvDevice) {
        self.app = app
        self.device = device
        Task { await refresh() }
    }

    func refresh() async {
        isLoading = true
        info = await TvInfo.read(Adb.shared, serial: device.serial)
        isLoading = false
    }

    private static func date(_ d: Date, _ format: String) -> String {
        let f = DateFormatter()
        f.locale = Lang.locale
        f.setLocalizedDateFormatFromTemplate(format)
        return f.string(from: d)
    }

    var software: [InfoRow] {
        guard let i = info else { return [] }
        let age = i.patchAgeMonths()
        let ago = age == 0 ? L("Info_ThisMonth") : age == 1 ? L("Info_OneMonth") : L("Info_MonthsAgo", age)
        var rows = [InfoRow(label: L("Info_Android"), value: L("Info_AndroidValue", i.androidVersion))]
        if !device.fireOsVersion.isEmpty {
            rows.append(InfoRow(label: "Fire OS", value: device.fireOsVersion.replacingOccurrences(of: "Fire OS ", with: "")))
        }
        rows.append(InfoRow(label: L("Info_Patch"),
                            value: i.securityPatch.map { L("Info_PatchValue", Self.date($0, "dMMMMyyyy"), ago) } ?? L("Info_Unknown")))
        rows.append(InfoRow(label: L("Info_Build"), value: i.buildId.isEmpty ? L("Info_Unknown") : i.buildId))
        return rows
    }

    var boot: [InfoRow] {
        guard let i = info else { return [] }
        let locked = i.bootloaderLocked.map { $0 ? L("Info_Locked") : L("Info_Unlocked") } ?? L("Info_Unknown")
        return [
            InfoRow(label: L("Info_Bootloader"), value: locked),
            InfoRow(label: L("Info_Verified"), value: Self.verifiedText(i.verifiedBoot)),
        ]
    }

    var tips: [HealthTip] {
        guard let i = info else { return [] }
        var tips: [HealthTip] = []
        let age = i.patchAgeMonths()
        // TVs get fewer updates than phones: a year without one is when to worry.
        if age >= 12 { tips.append(HealthTip(text: L("Info_TipPatchVeryOld", age), kind: .warn)) }
        else if age >= 6 { tips.append(HealthTip(text: L("Info_TipPatchOld", age), kind: .warn)) }
        else if age >= 0 { tips.append(HealthTip(text: L("Info_TipPatchOk"), kind: .ok)) }
        if i.bootloaderLocked == false { tips.append(HealthTip(text: L("Info_TipUnlocked"), kind: .warn)) }
        else if !["", "green"].contains(i.verifiedBoot) { tips.append(HealthTip(text: L("Info_TipNotVerified", Self.verifiedText(i.verifiedBoot)), kind: .warn)) }
        else if i.bootloaderLocked == true && i.verifiedBoot == "green" { tips.append(HealthTip(text: L("Info_TipLocked"), kind: .ok)) }
        return tips
    }

    private static func verifiedText(_ state: String) -> String {
        switch state {
        case "green": L("Info_VbGreen")
        case "yellow": L("Info_VbYellow")
        case "orange": L("Info_VbOrange")
        case "red": L("Info_VbRed")
        default: L("Info_Unknown")
        }
    }

    /// Android TV keeps System update under About, so that is the fallback.
    func openUpdate() async {
        if await !openOnTv("am start -a android.settings.SYSTEM_UPDATE_SETTINGS", ok: "Info_UpdateOpened") {
            _ = await openOnTv("am start -a android.settings.DEVICE_INFO_SETTINGS", ok: "Info_AboutOpened")
        }
    }

    /// About, where Reset is. Android doesn't let anything else open the reset screen itself.
    func openAbout() async {
        if await !openOnTv("am start -a android.settings.DEVICE_INFO_SETTINGS", ok: "Info_AboutOpened") {
            _ = await openOnTv("am start -a android.settings.SETTINGS", ok: "Info_SettingsOpened")
        }
    }

    private func openOnTv(_ command: String, ok: String) async -> Bool {
        let r = await Adb.shared.shell(device.serial, command)
        let failed = r.all.localizedCaseInsensitiveContains("error") || r.all.contains("Exception")
        statusIsError = failed
        status = failed ? L("Info_OpenFailed", r.all.split(separator: "\n").last.map(String.init) ?? "") : L(ok)
        return !failed
    }

    /// Restarts the TV and waits for it, reconnecting over the network, like the Apply page does.
    func restart() async {
        guard isConfirmingRestart, !isRestarting, let app else { return }
        isConfirmingRestart = false
        isRestarting = true
        statusIsError = false
        status = L("Info_Restarting")
        let serial = device.serial
        _ = await app.adb.run(["-s", serial, "reboot"], timeout: 15)
        try? await Task.sleep(for: .seconds(8))
        var back = false
        let deadline = Date().addingTimeInterval(4 * 60)
        while Date() < deadline && !back {
            if serial.contains(":") { _ = await app.adb.run(["connect", serial], timeout: 10) }
            if await app.adb.state(serial) == "device" {
                back = await app.adb.shell(serial, "getprop sys.boot_completed", timeout: 8).output
                    .trimmingCharacters(in: .whitespacesAndNewlines) == "1"
            }
            if !back { try? await Task.sleep(for: .seconds(3)) }
        }
        if back {
            device = await TvDevice.load(app.adb, serial: serial)
            app.device = device
            status = L("Info_RestartDone")
        } else {
            statusIsError = true
            status = L("Info_RestartTimeout")
        }
        isRestarting = false
        if back { await refresh() }
    }
}

// MARK: - Privacy check-up

@MainActor @Observable
final class AccessRow: Identifiable {
    let access: AppAccess
    let name: String
    var isRemoved = false
    var isWorking = false
    nonisolated let id: String
    var package: String { access.package }

    init(access: AppAccess, name: String) {
        self.access = access
        self.name = name
        id = access.package + "/" + access.kind.key
    }
}

struct AccessGroup: Identifiable {
    var kind: AccessKind
    var rows: [AccessRow]
    var id: String { kind.key }
}

/// Which apps can use the microphone, camera, location and personal data, with one click to remove an access.
@MainActor @Observable
final class PrivacyModel {
    let device: TvDevice
    var isLoading = false
    var groups: [AccessGroup] = []
    var status = ""
    /// One backup per visit, started on the first removal.
    private var backup: Backup?

    init(device: TvDevice) {
        self.device = device
        Task { await refresh() }
    }

    func refresh() async {
        isLoading = true
        let all = await Privacy.read(Adb.shared, device: device)
        func name(_ a: AppAccess) -> String { Catalog.name(of: a.package) ?? a.package }
        groups = Privacy.kinds.compactMap { kind in
            let rows = all.filter { $0.kind == kind }
                .sorted { ($0.isThirdParty ? 0 : 1, name($0).lowercased()) < ($1.isThirdParty ? 0 : 1, name($1).lowercased()) }
                .map { AccessRow(access: $0, name: name($0)) }
            return rows.isEmpty ? nil : AccessGroup(kind: kind, rows: rows)
        }
        isLoading = false
    }

    func remove(_ row: AccessRow) async {
        guard !row.isRemoved, !row.isWorking else { return }
        row.isWorking = true
        status = ""
        let b = backup ?? Backup.start(device)
        backup = b
        var error: String?
        for perm in row.access.granted {
            // Saved before the change, like every other change, so even an interrupted run can be undone.
            b.revokedPermissions.append(PermissionChange(package: row.package, permission: perm))
            try? b.save()
            error = await Privacy.revoke(Adb.shared, serial: device.serial, package: row.package, permission: perm)
            if error == nil { continue }
            b.revokedPermissions.removeLast()
            try? b.save()
            break
        }
        row.isWorking = false
        if let error {
            status = L("Privacy_Failed", row.name, error)
            return
        }
        row.isRemoved = true
        // Removing location also removed "all the time".
        if row.access.kind.key == "Location" {
            for g in groups where g.kind.key == "BgLocation" {
                for r in g.rows where r.package == row.package { r.isRemoved = true }
            }
        }
    }
}
