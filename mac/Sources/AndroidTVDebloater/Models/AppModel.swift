import AppKit
import Foundation
import Observation

enum Page { case welcome, connect, choose, apply, restore, info, health, privacy, apps, install, remote }

/// The whole app's state: which page is showing, the connected TV, and each page's model.
@MainActor @Observable
final class AppModel {
    let adb = Adb.shared
    var page: Page = .welcome
    var device: TvDevice?

    var welcome = WelcomeModel()
    var connect: ConnectModel!
    var choose: ChooseModel?
    var apply: ApplyModel?
    var restore: RestoreModel?
    var info: TvInfoModel?
    var health: HealthModel?
    var privacy: PrivacyModel?
    var apps: AppsModel?
    var install: InstallModel?
    var remote: RemoteModel?

    init() {
        connect = ConnectModel(app: self)
    }

    static let steps = ["Step_Prepare", "Step_Connect", "Step_Choose", "Step_Apply"]

    var stepIndex: Int {
        switch page {
        case .welcome: 0
        case .connect: 1
        case .choose, .restore, .info, .health, .privacy, .apps, .install, .remote: 2
        case .apply: apply?.isFinished == true ? 4 : 3
        }
    }

    /// Changes are being made to the TV: don't let the user leave the page.
    var isBusy: Bool {
        (page == .apply && apply?.isFinished == false) || (page == .restore && restore?.isBusy == true)
            || (page == .info && info?.isRestarting == true)
    }

    func canNavigate(toStep n: Int) -> Bool {
        // From a tool page (step 3 too), "Choose" leads back to the list.
        let onTool = [.restore, .info, .health, .privacy, .apps, .install, .remote].contains(page)
        return (n < stepIndex || (n == 2 && onTool)) && !isBusy && n < 3 && (n != 2 || device != nil)
    }

    func go(toStep n: Int) {
        guard canNavigate(toStep: n) else { return }
        switch n {
        case 0: page = .welcome
        case 1: goConnect()
        case 2: Task { await goChoose(reload: true) }
        default: break
        }
    }

    func goConnect() {
        connect = ConnectModel(app: self)
        page = .connect
    }

    func goChoose(reload: Bool = false) async {
        guard var dev = device else { goConnect(); return }
        if reload {
            dev = await TvDevice.load(adb, serial: dev.serial)
            device = dev
        }
        await Catalog.refreshFromGitHub() // the app may have stayed open since the list changed
        choose = ChooseModel(app: self, device: dev)
        page = .choose
    }

    func goApply(_ selected: [Tweak], reboot: Bool) {
        guard let dev = device else { return }
        ChoiceSet.from(tvName: dev.displayName, selected: selected).saveAsLast()
        let model = ApplyModel(app: self, device: dev, selected: selected, reboot: reboot)
        apply = model
        page = .apply
        Task { await model.run() }
    }

    func goRestore() {
        guard let dev = device, !isBusy else { return }
        restore = RestoreModel(app: self, device: dev)
        page = .restore
    }

    func goInfo() {
        guard let dev = device, !isBusy else { return }
        info = TvInfoModel(app: self, device: dev)
        page = .info
    }

    func goPrivacy() {
        guard let dev = device, !isBusy else { return }
        privacy = PrivacyModel(device: dev)
        page = .privacy
    }

    func goApps() {
        guard let dev = device, !isBusy else { return }
        apps = AppsModel(app: self, device: dev)
        page = .apps
    }

    func goHealth() {
        guard let dev = device, !isBusy else { return }
        health = HealthModel(device: dev)
        page = .health
    }

    func goInstall() {
        guard let dev = device, !isBusy else { return }
        install = InstallModel(device: dev)
        page = .install
    }

    func goRemote() {
        guard let dev = device, !isBusy else { return }
        remote = RemoteModel(device: dev)
        page = .remote
    }

    /// Back to the first page with no TV connected, e.g. to clean another one.
    func startOver() async {
        if let d = device, d.serial.contains(":") { _ = await adb.run(["disconnect", d.serial], timeout: 5) }
        device = nil
        welcome = WelcomeModel()
        page = .welcome
    }
}

// MARK: - Prepare

@MainActor @Observable
final class WelcomeModel {
    var isDownloading = false
    var progress = 0.0
    var error = ""
    var toolsReady = Adb.shared.isInstalled

    func continueTapped(_ app: AppModel) async {
        error = ""
        if !Adb.shared.isInstalled {
            isDownloading = true
            defer { isDownloading = false; toolsReady = Adb.shared.isInstalled }
            do {
                try await Adb.shared.ensureInstalled { p in Task { @MainActor in self.progress = p * 100 } }
            } catch {
                self.error = L("Welcome_DownloadFailed", error.localizedDescription)
                return
            }
        }
        app.goConnect()
    }
}

// MARK: - Connect

struct FoundTv: Identifiable, Hashable {
    var address: String
    var name = ""
    var id: String { address }
}

enum ConnectState { case idle, connecting, waitingForApproval, reading, failed }

@MainActor @Observable
final class ConnectModel {
    weak var app: AppModel?
    var recent: [FoundTv]
    var found: [FoundTv] = []
    var address = ""
    var isScanning = false
    var scanDone = false
    var scanProgress = 0.0
    var state: ConnectState = .idle
    var errorDetail = ""
    var pairAddress = ""
    var pairCode = ""
    var pairResult = ""
    private var recentNames: [String: String] = [:]
    private var task: Task<Void, Never>?

    init(app: AppModel) {
        self.app = app
        let list = RecentTvs.load()
        recent = list.map { FoundTv(address: Self.displayAddress($0.address), name: $0.name) }
        for r in list where recentNames[r.address] == nil { recentNames[r.address] = r.name }
        // Development aid: ATVD_CONNECT=192.168.1.20 connects straight away.
        if let auto = ProcessInfo.processInfo.environment["ATVD_CONNECT"], !auto.isEmpty { connect(to: auto) }
    }

    var isBusy: Bool { [.connecting, .waitingForApproval, .reading].contains(state) }
    var nothingFound: Bool { scanDone && found.isEmpty }

    var statusText: String {
        switch state {
        case .connecting: L("Connect_Connecting")
        case .reading: L("Connect_Reading")
        case .failed: errorDetail
        default: ""
        }
    }

    /// "192.168.1.20:5555" → "192.168.1.20"; other ports are kept.
    static func displayAddress(_ serial: String) -> String {
        serial.hasSuffix(":\(NetworkScanner.adbPort)") ? String(serial.dropLast(":\(NetworkScanner.adbPort)".count)) : serial
    }

    func scan() async {
        isScanning = true
        scanDone = false
        found = []
        let ips = await NetworkScanner.scan { p in self.scanProgress = p * 100 }
        found = ips.map { ip in FoundTv(address: ip, name: recentNames["\(ip):\(NetworkScanner.adbPort)"] ?? "") }
        isScanning = false
        scanDone = true
        for tv in found where tv.name.isEmpty {
            Task {
                if let name = await NetworkScanner.hostName(tv.address), let i = found.firstIndex(of: tv) {
                    found[i].name = name
                }
            }
        }
    }

    func connect(to input: String) {
        task?.cancel()
        task = Task { await connectNow(input) }
    }

    func cancel() {
        task?.cancel()
        state = .idle
    }

    private func connectNow(_ input: String) async {
        guard let app else { return }
        guard let target = Self.normalize(input) else { fail(L("Connect_BadAddress")); return }
        state = .connecting
        let adb = app.adb
        let r = await adb.run(["connect", target], timeout: 20)
        // "failed to authenticate" means the TV is showing "Allow USB debugging?": wait for it below.
        guard r.all.contains("connected to") || r.all.contains("failed to authenticate") else {
            fail(L("Connect_Refused", target) + "\n" + r.all)
            return
        }

        // Wait for "Allow USB debugging?" to be accepted on the TV (up to 3 minutes).
        let deadline = Date().addingTimeInterval(180)
        while Date() < deadline {
            if Task.isCancelled { state = .idle; return }
            let s = await adb.state(target)
            if s == "device" { break }
            if s == "unauthorized" { state = .waitingForApproval }
            if s == nil || s == "offline" { _ = await adb.run(["connect", target], timeout: 15) }
            try? await Task.sleep(for: .milliseconds(1500))
        }
        if Task.isCancelled { state = .idle; return }
        guard await adb.state(target) == "device" else { fail(L("Connect_ApprovalTimeout")); return }

        state = .reading
        let dev = await TvDevice.load(adb, serial: target)
        RecentTvs.remember(address: target, name: dev.displayName)
        app.device = dev
        await app.goChoose()
        // Development aid, with ATVD_CONNECT: ATVD_PAGE=info|health|privacy|apps|remote|install|restore opens a tool page (read-only).
        switch ProcessInfo.processInfo.environment["ATVD_PAGE"] {
        case "info": app.goInfo()
        case "health": app.goHealth()
        case "privacy": app.goPrivacy()
        case "apps": app.goApps()
        case "remote": app.goRemote()
        case "install": app.goInstall()
        case "restore": app.goRestore()
        default: break
        }
    }

    func pair() async {
        pairResult = ""
        guard let app, let target = Self.normalize(pairAddress, requirePort: true),
              pairCode.trimmingCharacters(in: .whitespaces).count >= 6 else {
            pairResult = L("Connect_PairBadInput")
            return
        }
        let r = await app.adb.run(["pair", target, pairCode.trimmingCharacters(in: .whitespaces)], timeout: 30)
        pairResult = r.all.contains("Successfully paired") ? L("Connect_PairOk") : L("Connect_PairFailed") + "\n" + r.all
    }

    private func fail(_ message: String) {
        errorDetail = message
        state = .failed
    }

    /// "192.168.1.20" → "192.168.1.20:5555". Accepts a comma typed instead of a dot.
    static func normalize(_ input: String, requirePort: Bool = false) -> String? {
        let s = input.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".").replacingOccurrences(of: " ", with: "")
        guard !s.isEmpty else { return nil }
        var host = s
        var port = Int(NetworkScanner.adbPort)
        if let colon = s.lastIndex(of: ":") {
            host = String(s[..<colon])
            guard let p = Int(s[s.index(after: colon)...]), (1...65535).contains(p) else { return nil }
            port = p
        } else if requirePort { return nil }
        let parts = host.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4, parts.allSatisfy({ Int($0).map { (0...255).contains($0) } ?? false }) else { return nil }
        return "\(parts.map { String(Int($0)!) }.joined(separator: ".")):\(port)"
    }
}

// MARK: - Choose

@MainActor @Observable
final class ChooseModel {
    weak var app: AppModel?
    let device: TvDevice
    let groups: [TweakGroup]
    let unknownApps: [String]
    var rebootAfter = true
    var lastChoices: ChoiceSet?
    var choicesStatus = ""
    /// Things earlier runs changed that are back (usually after a system update).
    var returned: ReturnedItems?
    /// Fire TV only: Amazon's tracking switches that are on (string keys of their names).
    var firePrivacyOn: [String]?

    init(app: AppModel, device: TvDevice) {
        self.app = app
        self.device = device
        groups = Catalog.build(device)
        unknownApps = Catalog.unknownSystemApps(device)
        lastChoices = ChoiceSet.loadLast()
        Task { await checkReturned() }
        if device.isFireTv { Task { await checkFirePrivacy() } }
    }

    func checkFirePrivacy() async {
        firePrivacyOn = await FirePrivacy.readOn(Adb.shared, serial: device.serial)
    }

    var firePrivacyText: String {
        switch firePrivacyOn {
        case nil: ""
        case let on? where on.isEmpty: L("Fire_PrivacyAllOff")
        case let on?: L("Fire_PrivacyOn", on.map { L($0) }.joined(separator: ", "))
        }
    }

    private func checkReturned() async {
        guard let r = await ReturnedItems.find(Adb.shared, device: device), !matchingTweaks(r).isEmpty else { return }
        returned = r
        lastChoices = nil // one suggestion at a time
    }

    private func matchingTweaks(_ r: ReturnedItems) -> [(Tweak, SettingChange?)] {
        allTweaks.compactMap { t in
            if let p = t.packageName, r.packages.contains(p) { return (t, nil) }
            if let s = r.settings.first(where: { t.settingKeys.contains($0.key) }) { return (t, s) }
            return nil
        }
    }

    /// Counts only what can be ticked again, so they match the list below.
    var returnedText: String {
        guard let r = returned else { return "" }
        let matches = matchingTweaks(r)
        return L("Choose_ReturnedText", DateText.display(r.since),
                 matches.filter { $0.1 == nil }.count, matches.filter { $0.1 != nil }.count)
    }

    var returnedNames: [String] {
        guard let r = returned else { return [] }
        return matchingTweaks(r).map { $0.0.title.text }
    }

    /// Tick exactly what came back, with the values chosen last time.
    func tickReturned() {
        guard let r = returned else { return }
        let matches = matchingTweaks(r)
        for t in allTweaks { t.isSelected = false }
        for (t, setting) in matches {
            t.isSelected = true
            // Pick the option that gives the value set last time ("16" or "max_cached_processes=16").
            if let s = setting, let o = t.options.first(where: { s.newValue == $0.value || s.newValue.hasSuffix("=" + $0.value) }) {
                t.selectedOption = o.value
            }
        }
        choicesStatus = L("Choose_ReturnedTicked", matches.count)
        returned = nil
    }

    var allTweaks: [Tweak] { groups.flatMap(\.tweaks) }
    var selected: [Tweak] { allTweaks.filter(\.isSelected) }
    var selectedCount: Int { selected.count }
    var selectedText: String { selectedCount == 1 ? L("Choose_SelectedOne") : L("Choose_Selected", selectedCount) }

    var infoLine: String {
        var parts = [device.fireOsVersion.isEmpty ? L("Choose_Android", device.androidVersion)
                        : device.fireOsVersion.components(separatedBy: " (")[0],
                     L("Choose_Ram", Self.oneDecimal(Double(device.ramMb) / 1024))]
        if device.storageTotalMb > 0 { parts.append(L("Choose_Storage", Self.oneDecimal(Double(device.storageFreeMb) / 1024))) }
        parts.append(L(device.network == .ethernet ? "Choose_NetEthernet" : device.network == .wifi ? "Choose_NetWifi" : "Choose_NetUnknown"))
        if !device.disabled.isEmpty {
            parts.append(device.disabled.count == 1 ? L("Choose_AlreadyOffOne") : L("Choose_AlreadyOff", device.disabled.count))
        }
        return parts.joined(separator: "  ·  ")
    }

    static func oneDecimal(_ v: Double) -> String {
        let f = NumberFormatter()
        f.locale = Lang.locale
        f.maximumFractionDigits = 1
        return f.string(from: NSNumber(value: v)) ?? "\(v)"
    }

    /// A chosen option that needs an app (e.g. Google Ambient) unticks turning that app off.
    func enforceKeeps(_ tweak: Tweak) {
        let kept = tweak.keptPackages
        guard !kept.isEmpty else { return }
        for t in allTweaks where t.packageName.map(kept.contains) == true { t.isSelected = false }
    }

    func selectRecommended() {
        for t in allTweaks { t.isSelected = t.recommended }
        choicesStatus = ""
    }

    func selectNone() {
        for t in allTweaks { t.isSelected = false }
        choicesStatus = ""
    }

    func use(_ set: ChoiceSet) {
        let found = set.apply(to: allTweaks)
        let missing = set.choices.count - found
        choicesStatus = missing == 0 ? L("Choose_UsedAll", found) : L("Choose_UsedSome", found, missing)
    }

    func useLastChoices() {
        if let set = lastChoices { use(set) }
        lastChoices = nil
    }

    var currentChoices: ChoiceSet { ChoiceSet.from(tvName: device.displayName, selected: selected) }

    func reportUnknown() {
        let (url, complete) = IssueReport.build(device, packages: unknownApps, catalogVersion: Catalog.version)
        if !complete { copyUnknown() }
        NSWorkspace.shared.open(url)
    }

    func copyUnknown() {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(unknownApps.joined(separator: "\n"), forType: .string)
    }
}

// MARK: - Apply

enum StepState { case pending, running, done, failed, skipped }

@Observable
final class StepItem: Identifiable {
    let id = UUID()
    var text: String
    var detail = ""
    var state: StepState = .pending
    init(_ text: String) { self.text = text }
}

@MainActor @Observable
final class ApplyModel {
    weak var app: AppModel?
    private(set) var device: TvDevice
    let selected: [Tweak]
    let reboot: Bool

    var items: [StepItem] = []
    var isFinished = false
    var problemCount = 0
    var progress = 0.0
    var backupPath = ""
    var appsOff = 0
    var settingsChanged = 0
    var homeScreen: HomeScreenModel?
    var debugOff = false
    var isTurningOffDebug = false
    var debugStatus = ""

    init(app: AppModel, device: TvDevice, selected: [Tweak], reboot: Bool) {
        self.app = app; self.device = device; self.selected = selected; self.reboot = reboot
    }

    var hasProblems: Bool { problemCount > 0 }
    var title: String { !isFinished ? L("Apply_Working") : hasProblems ? L("Apply_DoneWithProblems") : L("Apply_DoneTitle") }
    var subtitle: String { !isFinished ? L("Apply_WorkingSub") : hasProblems ? L("Apply_ProblemsSub", problemCount) : L("Apply_DoneSub") }
    var checkValue: String { hasProblems ? "\(problemCount)" : "OK" }
    var checkLabel: String { L(hasProblems ? "Apply_StatProblems" : reboot ? "Apply_StatChecks" : "Apply_StatChecksNoReboot") }

    private func add(_ text: String) -> StepItem {
        let s = StepItem(text)
        items.append(s)
        return s
    }

    func run() async {
        guard let app else { return }
        let adb = app.adb
        let backupStep = add(L("Apply_Backup"))
        let tweakSteps = selected.map { ($0, add($0.title.text)) }
        let rebootStep = reboot ? add(L("Apply_Reboot")) : nil
        let checkStep = add(L("Apply_Check"))
        let total = Double(items.count)
        var done = 0.0
        func tick() { done += 1; progress = 100 * done / total }

        // 1. Backup. No backup, no changes.
        backupStep.state = .running
        let backup = Backup.start(device)
        do {
            try backup.save()
            await saveSnapshot(backup)
            backupPath = backup.fileURL.deletingLastPathComponent().path
            backupStep.state = .done
        } catch {
            backupStep.detail = error.localizedDescription
            backupStep.state = .failed
            for (_, s) in tweakSteps { s.state = .skipped }
            problemCount = 1
            isFinished = true
            return
        }
        tick()

        // 2. Changes, packages first so the screensaver fix below can see what went.
        let ctx = ApplyContext(adb: adb, device: device, backup: backup)
        let kept = Set(selected.flatMap(\.keptPackages))
        for (tweak, step) in tweakSteps.sorted(by: { $0.0.isPackage && !$1.0.isPackage }) {
            if let pkg = tweak.packageName, kept.contains(pkg) {
                // A choice in the same run needs this app (e.g. the screensaver): leave it on.
                step.detail = L("Apply_Kept")
                step.state = .skipped
                tick()
                continue
            }
            step.state = .running
            let r = await tweak.apply(ctx)
            step.detail = r.detail
            step.state = r.success ? .done : .failed
            if !r.success { problemCount += 1 }
            else if tweak.isPackage { appsOff += 1 }
            else if !tweak.isMaintenance { settingsChanged += 1 }
            try? backup.save()
            tick()
        }

        // If the active screensaver's app was just turned off, switch to the simple one.
        let dreamPkg = device.screensaverComponent.split(separator: "/").first.map(String.init) ?? ""
        if !dreamPkg.isEmpty, backup.disabledPackages.contains(dreamPkg), device.isInstalled("com.android.dreams.basic") {
            _ = await SettingsIO.change(ctx, "secure", "screensaver_components", Catalog.basicScreensaver)
            try? backup.save()
        }

        // 3. Restart and wait for the TV to come back.
        if let rebootStep {
            rebootStep.state = .running
            let back = await rebootAndWait(rebootStep)
            rebootStep.state = back ? .done : .failed
            if !back { problemCount += 1 }
            tick()
        }

        // 4. Check the essentials: home screen, internet, name lookups.
        checkStep.state = .running
        let problems = await check()
        checkStep.detail = problems.isEmpty ? L("Apply_CheckOk") : problems.joined(separator: "\n")
        checkStep.state = problems.isEmpty ? .done : .failed
        if !problems.isEmpty { problemCount += 1 }
        tick()

        isFinished = true
        device = await TvDevice.load(adb, serial: device.serial)
        app.device = device
        homeScreen = HomeScreenModel(device: device)
    }

    /// Raw lists of packages and settings next to the change file, for reference.
    private func saveSnapshot(_ backup: Backup) async {
        guard let adb = app?.adb else { return }
        var parts: [String] = []
        for (title, cmd) in [("packages (all)", "pm list packages -f"), ("packages (disabled)", "pm list packages -d"),
                             ("settings global", "settings list global"), ("settings secure", "settings list secure"),
                             ("settings system", "settings list system")] {
            let r = await adb.shell(device.serial, cmd, timeout: 30)
            parts.append("===== \(title) =====\n\(r.output)")
        }
        let name = backup.fileURL.lastPathComponent.replacingOccurrences(of: "changes-", with: "snapshot-")
            .replacingOccurrences(of: ".json", with: ".txt")
        try? parts.joined(separator: "\n").write(to: backup.fileURL.deletingLastPathComponent().appendingPathComponent(name),
                                                 atomically: true, encoding: .utf8)
    }

    private func rebootAndWait(_ step: StepItem) async -> Bool {
        guard let adb = app?.adb else { return false }
        let serial = device.serial
        _ = await adb.run(["-s", serial, "reboot"], timeout: 15)
        try? await Task.sleep(for: .seconds(8))
        let deadline = Date().addingTimeInterval(240)
        while Date() < deadline {
            if serial.contains(":") { _ = await adb.run(["connect", serial], timeout: 10) }
            if await adb.state(serial) == "device",
               await adb.shell(serial, "getprop sys.boot_completed", timeout: 8).output.trimmingCharacters(in: .whitespacesAndNewlines) == "1" {
                step.detail = L("Apply_RebootSettle")
                try? await Task.sleep(for: .seconds(20)) // let apps and the network settle
                step.detail = ""
                return true
            }
            try? await Task.sleep(for: .seconds(3))
        }
        step.detail = L("Apply_RebootTimeout")
        return false
    }

    private func check() async -> [String] {
        guard let adb = app?.adb else { return [] }
        let serial = device.serial
        var problems: [String] = []
        if await TvDevice.currentHome(adb, serial: serial).isEmpty { problems.append(L("Apply_CheckNoHome")) }
        let ip = await adb.shell(serial, "ping -c 1 -W 3 8.8.8.8", timeout: 10)
        if !ip.output.contains(" 0% packet loss") { problems.append(L("Apply_CheckNoInternet")) }
        else if !(await adb.shell(serial, "ping -c 1 -W 3 connectivitycheck.gstatic.com", timeout: 10)).output.contains("PING ") {
            problems.append(L("Apply_CheckNoDns"))
        }
        return problems
    }

    func openBackupFolder() {
        guard !backupPath.isEmpty else { return }
        NSWorkspace.shared.open(URL(fileURLWithPath: backupPath))
    }

    /// Turns USB and network debugging off. adbd stops as soon as the setting is written, so success
    /// is "the TV no longer answers", not the command's output.
    func turnOffDebugging() async {
        guard let app else { return }
        isTurningOffDebug = true
        debugStatus = ""
        defer { isTurningOffDebug = false }
        let serial = device.serial
        _ = await app.adb.shell(serial, "settings put global adb_wifi_enabled 0; settings put global adb_enabled 0", timeout: 8)
        try? await Task.sleep(for: .seconds(3))
        if serial.contains(":") {
            _ = await app.adb.run(["disconnect", serial], timeout: 5)
            _ = await app.adb.run(["connect", serial], timeout: 8)
        }
        debugOff = await app.adb.state(serial) != "device"
        if debugOff {
            if serial.contains(":") { _ = await app.adb.run(["disconnect", serial], timeout: 5) }
            app.device = nil
        }
        debugStatus = L(debugOff ? "Apply_DebugOffDone" : "Apply_DebugOffFailed")
    }
}

// MARK: - Restore

@MainActor @Observable
final class RestoreModel {
    weak var app: AppModel?
    private(set) var device: TvDevice
    var backups: [Backup] = []
    var isBusy = false
    var status = ""
    var statusIsError = false
    var homeScreen: HomeScreenModel

    init(app: AppModel, device: TvDevice) {
        self.app = app
        self.device = device
        homeScreen = HomeScreenModel(device: device)
        backups = Backup.list(for: device)
    }

    func summary(_ b: Backup) -> String {
        L(b.revokedPermissions.isEmpty ? "Restore_Summary" : "Restore_SummaryAccess",
          b.disabledPackages.count, b.settings.count + (b.wifiWasOn == true ? 1 : 0), b.revokedPermissions.count)
    }

    /// Undo one run: re-enable its apps, put every setting back as it was, and give back removed accesses.
    func restore(_ b: Backup) async {
        await runBusy { adb, serial in
            for pkg in b.disabledPackages { _ = await adb.shell(serial, "pm enable \(pkg)") }
            for s in b.settings.reversed() { await SettingsIO.restore(adb, serial, s.namespace, s.key, s.oldValue) }
            if b.wifiWasOn == true { _ = await adb.shell(serial, "svc wifi enable") }
            for pkg in b.enabledPackages { _ = await adb.shell(serial, "pm disable-user --user 0 \(pkg)") }
            for p in b.revokedPermissions { _ = await adb.shell(serial, "pm grant --user 0 \(p.package) \(p.permission)") }
            // Keep the file for reference, but stop offering it.
            let renamed = b.fileURL.deletingLastPathComponent()
                .appendingPathComponent(b.fileURL.lastPathComponent.replacingOccurrences(of: "changes-", with: "restored-"))
            try? FileManager.default.removeItem(at: renamed)
            try? FileManager.default.moveItem(at: b.fileURL, to: renamed)
            return L("Restore_Done")
        }
    }

    /// Safety net: re-enable every app disabled by a user (not the ones the maker turned off).
    func enableAll() async {
        let disabled = device.disabled
        await runBusy { adb, serial in
            var count = 0
            for pkg in disabled.sorted() {
                // enabled=3 is COMPONENT_ENABLED_STATE_DISABLED_USER, i.e. disabled by someone, not by the firmware.
                let state = await adb.shell(serial, "dumpsys package \(pkg) | grep -m1 -oE 'enabled=[0-9]'")
                guard state.output.contains("enabled=3") else { continue }
                _ = await adb.shell(serial, "pm enable \(pkg)")
                count += 1
            }
            return L("Restore_AllDone", count)
        }
    }

    private func runBusy(_ work: (Adb, String) async -> String) async {
        guard let app else { return }
        isBusy = true
        status = ""
        statusIsError = false
        status = await work(app.adb, device.serial)
        device = await TvDevice.load(app.adb, serial: device.serial)
        app.device = device
        homeScreen = HomeScreenModel(device: device)
        backups = Backup.list(for: device)
        isBusy = false
    }
}

// MARK: - Home screen

@Observable
final class LauncherOption: Identifiable {
    let package: String
    let name: String
    let description: LocText
    /// "owner/name" when the .apk is also published on GitHub, so it can be installed without the Play Store.
    let gitHubRepo: String?
    var isInstalled = false
    var isCurrent = false
    var id: String { package }
    init(package: String, name: String, description: LocText, gitHubRepo: String? = nil) {
        self.package = package; self.name = name; self.description = description; self.gitHubRepo = gitHubRepo
    }
    var canInstall: Bool { !isInstalled }
    var canInstallFromGitHub: Bool { !isInstalled && gitHubRepo != nil }
    var canMakeHome: Bool { isInstalled && !isCurrent }
}

/// Suggests a home screen without ads, opens it in the Play Store on the TV (or installs it from
/// GitHub), and makes it the default. The original home screen is remembered on this Mac so it can be put back.
@MainActor @Observable
final class HomeScreenModel {
    let device: TvDevice
    let launchers: [LauncherOption] = [
        LauncherOption(package: "com.spocky.projengmenu", name: "Projectivy Launcher", description: LocText(
            en: "Clean and very customisable, no ads. It can take over the Home button, even on Google TV.",
            fr: "Épuré et très personnalisable, sans publicité. Il peut prendre le contrôle du bouton Accueil, même sur Google TV.")),
        LauncherOption(package: "me.efesser.flauncher", name: "FLauncher", description: LocText(
            en: "Simple and open source: just your apps, in rows you arrange. No ads, no tracking.",
            fr: "Simple et open source : juste vos applications, en rangées que vous organisez. Sans publicité ni pistage.")),
        LauncherOption(package: "com.overdevs.at4k", name: "AT4K Launcher", description: LocText(
            en: "Simple and clean, inspired by Apple TV: your favourite apps with what to watch next. No ads, no tracking.",
            fr: "Simple et épuré, inspiré de l'Apple TV : vos applications favorites et ce qu'il y a à regarder ensuite. Sans publicité ni pistage."),
            gitHubRepo: "avadhesh18/at4k"),
    ]
    var isBusy = false
    var status = ""
    var currentHome: String
    private var originalHome: String

    let hasPlayStore: Bool
    let isGoogleTv: Bool
    var canRestoreOriginal: Bool { !originalHome.isEmpty && currentHome != originalHome }

    private var originalFile: URL { Backup.folder(for: device).appendingPathComponent("original-home.txt") }

    init(device: TvDevice) {
        self.device = device
        currentHome = device.homePackage
        hasPlayStore = device.enabled.contains("com.android.vending")
        isGoogleTv = device.isInstalled("com.google.android.apps.tv.launcherx")
        originalHome = ""
        originalHome = (try? String(contentsOf: originalFile, encoding: .utf8))?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        updateStates(device.enabled)
    }

    private func updateStates(_ installed: Set<String>) {
        for l in launchers {
            l.isInstalled = installed.contains(l.package)
            l.isCurrent = l.package == currentHome
        }
    }

    func openStore(_ l: LauncherOption) async {
        await busy {
            let r = await Adb.shared.shell(self.device.serial, "am start -a android.intent.action.VIEW -d market://details?id=\(l.package)")
            return r.all.localizedCaseInsensitiveContains("error") ? L("Home_StoreFailed") : L("Home_StoreOpened", l.name)
        }
    }

    func installFromGitHub(_ l: LauncherOption) async {
        guard let repo = l.gitHubRepo else { return }
        await busy {
            self.status = L("Home_Downloading", l.name)
            let apk: URL
            do { apk = try await GitHubApk.downloadLatest(repo) }
            catch { return L("Home_DownloadFailed", l.name, error.localizedDescription) }
            defer { try? FileManager.default.removeItem(at: apk.deletingLastPathComponent()) }
            self.status = L("Install_Installing", l.name)
            let r = await Adb.shared.run(["-s", self.device.serial, "install", "-r", apk.path], timeout: 600)
            await self.refresh()
            return r.all.contains("Success")
                ? L("Home_GitHubDone", l.name)
                : L("Install_Failed", r.all.components(separatedBy: "\n").last { $0.contains("Failure") || $0.contains("INSTALL_") } ?? r.all)
        }
    }

    func checkAgain() async { await busy { await self.refresh(); return "" } }

    func makeHome(_ l: LauncherOption) async {
        await busy {
            // "android" is the "choose an app" dialog, not a home screen worth going back to.
            if self.originalHome.isEmpty, !self.currentHome.isEmpty, self.currentHome != "android" {
                try? FileManager.default.createDirectory(at: self.originalFile.deletingLastPathComponent(), withIntermediateDirectories: true)
                try? self.currentHome.write(to: self.originalFile, atomically: true, encoding: .utf8)
                self.originalHome = self.currentHome
            }
            _ = await Adb.shared.shell(self.device.serial, "cmd package set-home-activity \(l.package)")
            await self.refresh()
            return self.currentHome == l.package
                ? L(self.isGoogleTv ? "Home_SetOkGoogleTv" : "Home_SetOk", l.name)
                : L("Home_SetFailed", l.name)
        }
    }

    func restoreOriginal() async {
        await busy {
            _ = await Adb.shared.shell(self.device.serial, "cmd package set-home-activity \(self.originalHome)")
            await self.refresh()
            return self.currentHome == self.originalHome ? L("Home_RestoredOk") : L("Home_RestoreFailed")
        }
    }

    private func refresh() async {
        currentHome = await TvDevice.currentHome(Adb.shared, serial: device.serial)
        var installed = Set<String>()
        for l in launchers {
            let r = await Adb.shared.shell(device.serial, "pm list packages -e \(l.package)")
            if r.output.split(separator: "\n").contains(where: { $0.trimmingCharacters(in: .whitespaces) == "package:" + l.package }) {
                installed.insert(l.package)
            }
        }
        updateStates(installed)
    }

    private func busy(_ work: () async -> String) async {
        isBusy = true
        status = ""
        status = await work()
        isBusy = false
    }
}
