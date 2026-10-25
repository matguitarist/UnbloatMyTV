import Foundation
import Observation

struct TweakOption: Hashable, Identifiable {
    var label: LocText
    var value: String
    /// Apps this option needs: they are not turned off while it is chosen.
    var keeps: [String] = []
    var id: String { value }
}

struct SettingWrite: Codable, Hashable {
    var namespace: String
    var key: String
    var value: String
}

final class ApplyContext {
    let adb: Adb
    let device: TvDevice
    let backup: Backup
    var serial: String { device.serial }
    init(adb: Adb, device: TvDevice, backup: Backup) { self.adb = adb; self.device = device; self.backup = backup }
}

struct TweakResult {
    var success: Bool
    var detail = ""
}

/// One thing the user can tick.
@Observable
final class Tweak: Identifiable {
    enum Kind {
        case package(String)
        case settings([SettingWrite])
        case backgroundLimit
        case privateDns
        case wifiOff
        case trimCaches
        case optimizeApps
    }

    let id: String
    let groupId: String
    let recommended: Bool
    let title: LocText
    let description: LocText
    let warning: LocText?
    let options: [TweakOption]
    let kind: Kind

    var isSelected = false
    var selectedOption: String?
    /// The TV is already set this way: shown with a check mark and not ticked.
    var isDone = false
    /// False for a plain choice between options, such as the screensaver.
    var canBeDone = true

    init(id: String, groupId: String, recommended: Bool, title: LocText, description: LocText,
         warning: LocText? = nil, options: [TweakOption] = [], kind: Kind) {
        self.id = id; self.groupId = groupId; self.recommended = recommended
        self.title = title; self.description = description; self.warning = warning
        self.options = options; self.kind = kind
        self.selectedOption = options.first?.value
        self.isSelected = recommended
    }

    var isPackage: Bool { if case .package = kind { return true }; return false }
    var isTrimCaches: Bool { if case .trimCaches = kind { return true }; return false }
    var isMaintenance: Bool {
        switch kind { case .trimCaches, .optimizeApps: true; default: false }
    }
    var packageName: String? { if case .package(let p) = kind { return p }; return nil }

    /// Settings this choice writes, to recognise it when an update changed them back.
    var settingKeys: [String] {
        switch kind {
        case .settings(let writes): writes.map(\.key)
        case .backgroundLimit: ["max_cached_processes", "activity_manager_constants"]
        case .privateDns: ["private_dns_mode", "private_dns_specifier"]
        case .wifiOff: ["wifi_on"]
        default: []
        }
    }

    /// Apps the chosen option needs, which must not be turned off in the same run.
    var keptPackages: [String] {
        guard isSelected else { return [] }
        return options.first { $0.value == selectedOption }?.keeps ?? []
    }

    /// Small technical line under the description (package name, setting key).
    var technical: String {
        switch kind {
        case .package(let pkg): return pkg
        case .settings(let writes):
            var seen = Set<String>()
            return writes.map(\.key).filter { seen.insert($0).inserted }.joined(separator: " · ")
        case .backgroundLimit: return "max_cached_processes"
        case .privateDns: return "private_dns_mode · private_dns_specifier"
        case .wifiOff: return "svc wifi disable"
        case .trimCaches: return "pm trim-caches"
        case .optimizeApps: return "cmd package bg-dexopt-job"
        }
    }

    /// Whether the TV is already set the way this choice would set it, and if so with which option.
    /// Reading changes nothing. Choices that run something (clear caches…) are never done.
    func readDone(_ adb: Adb, _ dev: TvDevice) async -> (done: Bool, option: String?) {
        let values = options.map(\.value)
        switch kind {
        case .settings(let writes) where canBeDone:
            // Every setting already has the value of one same option (any option counts).
            var current: [String: String?] = [:]
            for w in writes { current[w.namespace + "/" + w.key] = await SettingsIO.get(adb, dev.serial, w.namespace, w.key) }
            func matches(_ option: String) -> Bool {
                writes.allSatisfy { current[$0.namespace + "/" + $0.key] == .some($0.value.replacingOccurrences(of: "{option}", with: option)) }
            }
            if values.isEmpty { return (matches(""), nil) }
            let match = values.first(where: matches)
            return (match != nil, match)
        case .backgroundLimit:
            let v = dev.sdk >= 29
                ? await SettingsIO.get(adb, dev.serial, "device_config:activity_manager", "max_cached_processes")
                : (await SettingsIO.get(adb, dev.serial, "global", "activity_manager_constants"))?
                    .split(separator: ",").first { $0.hasPrefix("max_cached_processes=") }
                    .map { String($0.dropFirst("max_cached_processes=".count)) }
            let match = values.first { $0 == v }
            return (match != nil, match)
        case .wifiOff:
            return (await SettingsIO.get(adb, dev.serial, "global", "wifi_on") == "0", nil)
        case .privateDns:
            // Done when Private DNS already points at one of the offered filtering services.
            guard await SettingsIO.get(adb, dev.serial, "global", "private_dns_mode") == "hostname" else { return (false, nil) }
            let host = await SettingsIO.get(adb, dev.serial, "global", "private_dns_specifier")
            let match = values.first { $0 == host }
            return (match != nil, match)
        default:
            return (false, nil)
        }
    }

    func apply(_ ctx: ApplyContext) async -> TweakResult {
        switch kind {
        case .package(let pkg):
            // Disable for the main user. Nothing is uninstalled.
            let r = await ctx.adb.shell(ctx.serial, "pm disable-user --user 0 \(pkg)")
            if r.all.contains("disabled-user") {
                ctx.backup.disabledPackages.append(pkg)
                return TweakResult(success: true)
            }
            // Fire OS (and some makers) refuse for their own apps; say so instead of a Java error.
            if r.all.range(of: "protected package", options: .caseInsensitive) != nil {
                return TweakResult(success: false, detail: L("Apply_ProtectedPackage"))
            }
            return TweakResult(success: false, detail: Self.firstLine(r.all))

        case .settings(let writes):
            // The chosen option needs an app that was turned off (e.g. Google Ambient): turn it back on.
            for pkg in keptPackages where ctx.device.disabled.contains(pkg) {
                _ = await ctx.adb.shell(ctx.serial, "pm enable \(pkg)")
                ctx.backup.enabledPackages.append(pkg)
                try? ctx.backup.save()
            }
            for w in writes {
                let value = w.value.replacingOccurrences(of: "{option}", with: selectedOption ?? "")
                if !(await SettingsIO.change(ctx, w.namespace, w.key, value)) {
                    return TweakResult(success: false, detail: w.key)
                }
            }
            return TweakResult(success: true)

        case .backgroundLimit:
            let value = selectedOption ?? "16"
            // Android 10+ reads it from device_config; older versions from a global setting.
            let ok = ctx.device.sdk >= 29
                ? await SettingsIO.change(ctx, "device_config:activity_manager", "max_cached_processes", value)
                : await SettingsIO.change(ctx, "global", "activity_manager_constants", "max_cached_processes=\(value)")
            return TweakResult(success: ok)

        case .wifiOff:
            let before = await SettingsIO.get(ctx.adb, ctx.serial, "global", "wifi_on")
            ctx.backup.wifiWasOn = before != "0"
            try? ctx.backup.save()
            _ = await ctx.adb.shell(ctx.serial, "svc wifi disable")
            return TweakResult(success: true)

        case .privateDns:
            return await applyPrivateDns(ctx)

        case .trimCaches:
            let before = await TvDevice.readStorage(ctx.adb, serial: ctx.serial)
            let r = await ctx.adb.shell(ctx.serial, "pm trim-caches 999G", timeout: 120)
            guard r.ok else { return TweakResult(success: false, detail: Self.firstLine(r.all)) }
            try? await Task.sleep(for: .milliseconds(1500))
            let after = await TvDevice.readStorage(ctx.adb, serial: ctx.serial)
            return TweakResult(success: true, detail: L("Apply_Freed", max(0, after.freeMb - before.freeMb)))

        case .optimizeApps:
            // Android's own background optimisation, run now. Nothing to back up or undo.
            let started = Date()
            let r = await ctx.adb.shell(ctx.serial, "cmd package bg-dexopt-job", timeout: 20 * 60)
            guard r.ok else { return TweakResult(success: false, detail: Self.firstLine(r.all)) }
            return TweakResult(success: true, detail: L("Apply_OptimizeDone", max(1, Int((Date().timeIntervalSince(started) / 60).rounded()))))
        }
    }

    /// Filtering Private DNS, checked straight away: if names stop resolving, the previous
    /// setting is put back on its own.
    private func applyPrivateDns(_ ctx: ApplyContext) async -> TweakResult {
        let host = selectedOption ?? "dns.adguard-dns.com"
        let oldMode = await SettingsIO.get(ctx.adb, ctx.serial, "global", "private_dns_mode")
        let oldHost = await SettingsIO.get(ctx.adb, ctx.serial, "global", "private_dns_specifier")
        _ = await SettingsIO.change(ctx, "global", "private_dns_specifier", host)
        _ = await SettingsIO.change(ctx, "global", "private_dns_mode", "hostname")

        for _ in 0..<4 {
            try? await Task.sleep(for: .milliseconds(2500))
            let ping = await ctx.adb.shell(ctx.serial, "ping -c 1 -W 3 connectivitycheck.gstatic.com", timeout: 10)
            if ping.output.contains("PING ") { return TweakResult(success: true, detail: host) }
        }

        await SettingsIO.restore(ctx.adb, ctx.serial, "global", "private_dns_mode", oldMode)
        await SettingsIO.restore(ctx.adb, ctx.serial, "global", "private_dns_specifier", oldHost)
        ctx.backup.settings.removeAll { $0.key == "private_dns_mode" || $0.key == "private_dns_specifier" }
        try? ctx.backup.save()
        return TweakResult(success: false, detail: L("Apply_DnsReverted"))
    }

    static func firstLine(_ s: String) -> String {
        s.split(separator: "\n").first.map(String.init) ?? ""
    }
}

@Observable
final class TweakGroup: Identifiable {
    let id: String
    let title: LocText
    let description: LocText
    var tweaks: [Tweak] = []

    init(id: String, title: LocText, description: LocText) {
        self.id = id; self.title = title; self.description = description
    }

    var countText: String { L("Choose_GroupCount", tweaks.filter(\.isSelected).count, tweaks.count) }
}
