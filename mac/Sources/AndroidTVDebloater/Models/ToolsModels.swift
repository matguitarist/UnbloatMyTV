import AppKit
import Foundation
import Observation

// MARK: - Health check

struct HealthTip: Identifiable {
    enum Kind { case ok, info, warn }
    var text: String
    var kind: Kind
    var id: String { text }
}

/// Read-only check-up: memory, storage, processor, biggest apps, crash loops, with advice.
@MainActor @Observable
final class HealthModel {
    let device: TvDevice
    var isLoading = false
    var report: HealthReport?

    init(device: TvDevice) {
        self.device = device
        Task { await refresh() }
    }

    func refresh() async {
        isLoading = true
        report = await HealthReport.read(Adb.shared, serial: device.serial)
        isLoading = false
    }

    static func gb(_ mb: Int64) -> String { ChooseModel.oneDecimal(Double(mb) / 1024) }

    var memoryUsed: Double { report.map { $0.memTotalMb > 0 ? Double($0.memTotalMb - $0.memAvailableMb) / Double($0.memTotalMb) : 0 } ?? 0 }
    var storageUsed: Double { report.map { $0.storageTotalMb > 0 ? Double($0.storageTotalMb - $0.storageFreeMb) / Double($0.storageTotalMb) : 0 } ?? 0 }

    var cpuText: String {
        guard let r = report else { return "" }
        let h = r.uptimeHours
        let up = h < 0.5 ? L("Health_UnderHour") : h < 1.5 ? L("Health_OneHour")
            : h < 48 ? L("Health_Hours", Int(h.rounded())) : L("Health_Days", Int((h / 24).rounded()))
        // The load average is how many tasks want the processor at once, not a percentage.
        let perCore = r.cores > 0 ? r.load / Double(r.cores) : 0
        let level = perCore <= 0.7 ? "Health_CpuQuiet" : perCore <= 1.0 ? "Health_CpuBusy" : "Health_CpuOverloaded"
        return L("Health_CpuText", L(level), ChooseModel.oneDecimal(r.load), r.cores, up)
    }

    var tips: [HealthTip] {
        guard let r = report else { return [] }
        var tips: [HealthTip] = []
        if r.memTotalMb > 0 && r.memAvailableMb * 100 < r.memTotalMb * 15 { tips.append(HealthTip(text: L("Health_TipMemory"), kind: .warn)) }
        if r.storageTotalMb > 0 && r.storageFreeMb * 100 < r.storageTotalMb * 15 { tips.append(HealthTip(text: L("Health_TipStorage"), kind: .warn)) }
        if r.cores > 0 && r.load / Double(r.cores) > 1.0 { tips.append(HealthTip(text: L("Health_TipCpu"), kind: .warn)) }
        for c in r.crashing {
            let name = Catalog.name(of: c.package) ?? c.package
            let key = Catalog.isKeep(c.package) ? "Health_TipCrashKeep"
                : Catalog.isOffered(device, c.package) ? "Health_TipCrashOffered" : "Health_TipCrash"
            tips.append(HealthTip(text: L(key, name, c.restarts), kind: .warn))
        }
        if tips.isEmpty { tips.append(HealthTip(text: L("Health_TipAllGood"), kind: .ok)) }
        return tips
    }
}

// MARK: - Install an app

@MainActor @Observable
final class InstallModel {
    let device: TvDevice
    var isInstalling = false
    var status = ""
    var statusIsError = false

    init(device: TvDevice) { self.device = device }

    func install(_ url: URL) async {
        guard !isInstalling else { return }
        guard url.pathExtension.lowercased() == "apk" else {
            statusIsError = true
            status = L("Install_NotApk")
            return
        }
        isInstalling = true
        statusIsError = false
        status = L("Install_Installing", url.lastPathComponent)
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let r = await Adb.shared.run(["-s", device.serial, "install", "-r", url.path], timeout: 600)
        let ok = r.all.contains("Success")
        statusIsError = !ok
        status = ok ? L("Install_Done", url.lastPathComponent)
            : L("Install_Failed", r.all.components(separatedBy: "\n").last { $0.contains("Failure") || $0.contains("INSTALL_") } ?? r.all)
        isInstalling = false
    }
}

// MARK: - Screen and remote

/// Live view of the TV screen and a remote control.
@MainActor @Observable
final class RemoteModel {
    let device: TvDevice
    var screen: NSImage?
    var isLive = true { didSet { isLive ? start() : stop() } }
    var textToSend = ""
    @ObservationIgnored private var loop: Task<Void, Never>?

    init(device: TvDevice) { self.device = device }

    func start() {
        loop?.cancel()
        let serial = device.serial
        loop = Task { [weak self] in
            while !Task.isCancelled {
                if let png = await Adb.shared.runBinary(["-s", serial, "exec-out", "screencap", "-p"]), !Task.isCancelled {
                    self?.screen = NSImage(data: png)
                }
                try? await Task.sleep(for: .milliseconds(400))
            }
        }
    }

    func stop() {
        loop?.cancel()
        loop = nil
    }

    func refreshOnce() async {
        if let png = await Adb.shared.runBinary(["-s", device.serial, "exec-out", "screencap", "-p"]) { screen = NSImage(data: png) }
    }

    /// Android key codes: 19-22 arrows, 23 OK, 4 Back, 3 Home, 82 Menu, 85 Play/pause, 24/25 volume.
    func key(_ code: Int) async {
        _ = await Adb.shared.shell(device.serial, "input keyevent \(code)", timeout: 8)
        if !isLive { await refreshOnce() }
    }

    /// Type into whatever text field is open on the TV (search boxes, passwords).
    func sendText() async {
        guard !textToSend.isEmpty else { return }
        // "input text" takes %s for spaces; the rest is passed quoted.
        let text = textToSend.replacingOccurrences(of: "%", with: "\\%").replacingOccurrences(of: " ", with: "%s")
            .replacingOccurrences(of: "'", with: "'\\''")
        _ = await Adb.shared.shell(device.serial, "input text '\(text)'", timeout: 15)
        textToSend = ""
    }
}
