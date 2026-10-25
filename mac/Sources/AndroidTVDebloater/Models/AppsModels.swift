import AppKit
import Foundation
import Observation

/// One installed app. Uninstall waits for a yes on the row.
@MainActor @Observable
final class AppRow: Identifiable {
    var app: InstalledApp
    var name: String
    /// The banner or icon as the TV draws it; nil until it arrives, or when the TV can't give one.
    var icon: NSImage?
    let isProtected: Bool
    /// "Apps_Uninstalled" once done; the row stays until the next refresh.
    var goneKey = ""
    var isWorking = false
    /// Waiting for a yes before uninstalling.
    var isConfirming = false
    nonisolated let id: String
    var package: String { app.package }

    init(app: InstalledApp, isProtected: Bool) {
        self.app = app
        self.isProtected = isProtected
        name = Catalog.name(of: app.package) ?? app.package
        id = app.package
    }

    var isGone: Bool { !goneKey.isEmpty }
    var hasFriendlyName: Bool { name != package }

    /// The name on the TV wins over the catalog's.
    func setFromTv(_ e: TvIcons.Entry) {
        if !e.label.isEmpty && e.label != package { name = e.label }
        if icon == nil, let png = e.png { icon = NSImage(data: png) }
    }

    var isOff: Bool { !app.isEnabled && !isGone }
    /// From a file or another source, not from Google Play, the Amazon Appstore or the TV's maker.
    var isOutside: Bool { !isGone && !app.fromStore && !app.byMaker }
    private var ready: Bool { !isGone && !isWorking }
    var canOpen: Bool { ready && app.isEnabled && app.hasIcon }
    var canStop: Bool { ready && app.isEnabled }
    var canTurnOff: Bool { ready && app.isEnabled && !isProtected }
    var canTurnOn: Bool { ready && !app.isEnabled }
    var canRemove: Bool { ready && !isProtected }

    var confirmText: String { isConfirming ? L("Apps_ConfirmUninstall", name) : "" }

    /// "4.16.8 · 245 MB · Google Play · installed 11 Sep 2025"
    var details: String {
        var parts: [String] = []
        if !app.version.isEmpty { parts.append(L("Apps_Version", app.version)) }
        if app.totalBytes > 0 { parts.append(Self.size(app.totalBytes)) }
        parts.append(Self.origin(app))
        if let d = app.installed {
            let f = DateFormatter()
            f.locale = Lang.locale
            f.setLocalizedDateFormatFromTemplate("dMMMyyyy")
            parts.append(L("Apps_InstalledOn", f.string(from: d)))
        }
        return parts.joined(separator: " · ")
    }

    static func size(_ bytes: Int64) -> String {
        let mb = Double(bytes) / 1024 / 1024
        if mb < 1 { return L("Apps_LessThanMb") }
        if mb < 1000 { return L("Apps_Mb", String(format: "%.0f", mb)) }
        return L("Apps_Gb", ChooseModel.oneDecimal(mb / 1024))
    }

    static func origin(_ app: InstalledApp) -> String {
        if app.byMaker { return L("Apps_FromMaker") }
        return switch app.installer {
        case "com.android.vending": "Google Play"
        case "com.amazon.venezia": "Amazon Appstore"
        case "", "com.android.shell", "com.google.android.packageinstaller", "com.android.packageinstaller": L("Apps_FromFile")
        case "com.esaba.downloader": "Downloader"
        case "com.aurora.store": "Aurora Store"
        case "org.fdroid.fdroid", "org.fdroid.basic": "F-Droid"
        default: app.installer
        }
    }
}

/// The apps the owner installed: size, origin, and open, stop, turn off or uninstall.
@MainActor @Observable
final class AppsModel {
    enum Sort: Int, CaseIterable { case name, size, newest }

    weak var app: AppModel?
    let device: TvDevice
    var isLoading = false
    var rows: [AppRow] = []
    var status = ""
    var statusIsError = false
    var search = ""
    var sort = Sort.name
    /// The list shows only the apps that didn't come from a store.
    var onlyOutside = false
    /// One backup per visit, started on the first app turned off or on.
    private var backup: Backup?

    init(app: AppModel, device: TvDevice) {
        self.app = app
        self.device = device
        Task { await refresh() }
    }

    var present: [AppRow] { rows.filter { !$0.isGone } }

    var summary: String { L("Apps_Summary", present.count, AppRow.size(present.reduce(0) { $0 + $1.app.totalBytes })) }

    var outside: [AppRow] {
        rows.filter(\.isOutside).sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }

    /// "• Name · installed from a file", one line per app, by name.
    var outsideList: String {
        outside.map { "• \($0.name) · \(AppRow.origin($0.app))" }.joined(separator: "\n")
    }

    var visible: [AppRow] {
        let q = search.trimmingCharacters(in: .whitespaces)
        let onlyOutside = onlyOutside && rows.contains(where: \.isOutside)
        let found = rows.filter {
            (!onlyOutside || $0.isOutside)
                && (q.isEmpty || $0.name.localizedCaseInsensitiveContains(q) || $0.package.localizedCaseInsensitiveContains(q))
        }
        switch sort {
        case .name: return found.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        case .size: return found.sorted { $0.app.totalBytes > $1.app.totalBytes }
        case .newest: return found.sorted { ($0.app.installed ?? .distantPast) > ($1.app.installed ?? .distantPast) }
        }
    }

    func refresh() async {
        isLoading = true
        status = ""
        let apps = await UserApps.read(Adb.shared, device: device)
        rows = apps.map { AppRow(app: $0, isProtected: isNeeded($0)) }
        isLoading = false
        await loadIcons()
    }

    /// Pictures and names from the TV, after the list shows: they take a few seconds the first time.
    private func loadIcons() async {
        let current = rows
        guard !current.isEmpty else { return }
        let found = await TvIcons.read(Adb.shared, serial: device.serial, apps: current.map(\.app))
        guard rows.map(\.id) == current.map(\.id) else { return }  // refreshed meanwhile
        for r in current { if let e = found[r.package] { r.setFromTv(e) } }
    }

    /// The home screen, keyboard and so on, Amazon's apps on Fire TV, and the parts of Android that Google
    /// installs through Play: those have no icon and aren't in the catalog.
    private func isNeeded(_ a: InstalledApp) -> Bool {
        device.isProtected(a.package) || Catalog.isKeep(a.package)
            || (!a.hasIcon && a.package.hasPrefix("com.google.android.") && Catalog.name(of: a.package) == nil)
    }

    private func report(_ error: String?, _ okKey: String, _ row: AppRow) {
        statusIsError = error != nil
        status = error.map { L("Apps_Failed", row.name, $0) } ?? L(okKey, row.name)
    }

    func run(_ row: AppRow, _ okKey: String, _ action: (Adb, String, String) async -> String?) async {
        guard !row.isWorking else { return }
        row.isWorking = true
        let error = await action(Adb.shared, device.serial, row.package)
        row.isWorking = false
        report(error, okKey, row)
    }

    /// Saved in a backup before the change, like every other change, so Restore can undo it.
    func setEnabled(_ row: AppRow, on: Bool) async {
        guard !row.isWorking else { return }
        row.isWorking = true
        let b = backup ?? Backup.start(device)
        backup = b
        if on { b.enabledPackages.append(row.package) } else { b.disabledPackages.append(row.package) }
        try? b.save()
        let error = await UserApps.setEnabled(Adb.shared, device.serial, row.package, on: on)
        row.isWorking = false
        if error != nil {
            if on { b.enabledPackages.removeLast() } else { b.disabledPackages.removeLast() }
            try? b.save()
        } else {
            row.app.isEnabled = on
            // Keep the connected TV's lists in step, for the other pages.
            if var d = app?.device {
                if on { d.disabled.remove(row.package); d.enabled.insert(row.package) }
                else { d.enabled.remove(row.package); d.disabled.insert(row.package) }
                app?.device = d
            }
        }
        report(error, on ? "Apps_TurnedOn" : "Apps_TurnedOff", row)
    }

    /// After the yes on the row. Can't be undone from Restore.
    func uninstall(_ row: AppRow) async {
        row.isConfirming = false
        guard row.canRemove else { return }
        row.isWorking = true
        let error = await UserApps.uninstall(Adb.shared, device.serial, row.package)
        row.isWorking = false
        if error == nil {
            row.goneKey = "Apps_Uninstalled"
            if var d = app?.device {
                d.enabled.remove(row.package)
                d.disabled.remove(row.package)
                d.thirdParty.remove(row.package)
                app?.device = d
            }
        }
        report(error, "Apps_UninstalledDone", row)
    }
}
