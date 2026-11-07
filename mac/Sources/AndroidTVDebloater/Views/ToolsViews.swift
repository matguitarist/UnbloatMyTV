import SwiftUI
import UniformTypeIdentifiers

struct HealthView: View {
    @Environment(AppModel.self) private var app
    var health: HealthModel

    var body: some View {
        PageLayout(title: L("Health_Title"), subtitle: L("Health_Lead")) {
            if health.isLoading { ProgressView().controlSize(.small) }

            if let r = health.report {
                ForEach(health.tips) { tip in
                    Note(text: tip.text, kind: tip.kind == .ok ? .success : tip.kind == .warn ? .warning : .info)
                }

                HStack(alignment: .top, spacing: 12) {
                    gauge(L("Health_Memory"), health.memoryUsed, L("Health_MemoryText", HealthModel.gb(r.memAvailableMb), HealthModel.gb(r.memTotalMb)))
                    gauge(L("Health_Storage"), health.storageUsed, L("Health_StorageText", HealthModel.gb(r.storageFreeMb), HealthModel.gb(r.storageTotalMb)))
                    GroupBox {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(L("Health_Cpu")).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                            Text(health.cpuText).fixedSize(horizontal: false, vertical: true)
                        }
                        .padding(6)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    }
                }
                .fixedSize(horizontal: false, vertical: true) // the three tiles share one height

                Card(title: L("Health_TopTitle"), systemImage: "memorychip") {
                    Text(L("Health_TopText")).foregroundStyle(.secondary)
                    ForEach(r.topMemory) { p in
                        HStack {
                            VStack(alignment: .leading, spacing: 1) {
                                if let friendly = Catalog.name(of: p.name) {
                                    Text(friendly)
                                    Text(p.name).font(.caption.monospaced()).foregroundStyle(.tertiary)
                                } else {
                                    Text(p.name).lineLimit(1).truncationMode(.middle)
                                }
                            }
                            Spacer()
                            Text(L("Health_Mb", p.mb)).font(.body.weight(.semibold).monospacedDigit())
                        }
                        Divider()
                    }
                }

                Button(L("Health_GoChoose")) { Task { await app.goChoose(reload: true) } }
            }
        }
        .toolbar {
            Button { Task { await health.refresh() } } label: { Label(L("Health_Refresh"), systemImage: "arrow.clockwise") }
                .help(L("Health_Refresh"))
                .disabled(health.isLoading)
        }
    }

    private func gauge(_ title: String, _ used: Double, _ text: String) -> some View {
        GroupBox {
            VStack(alignment: .leading, spacing: 8) {
                Text(title).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                ProgressView(value: used).tint(used > 0.85 ? .orange : .accentColor)
                Text(text).foregroundStyle(.secondary)
            }
            .padding(6)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
    }
}

struct InstallView: View {
    var install: InstallModel
    @State private var picking = false
    @State private var targeted = false

    private static let apk = UTType(filenameExtension: "apk") ?? .data

    var body: some View {
        PageLayout(title: L("Install_Title"), subtitle: L("Install_Lead")) {
            VStack(spacing: 14) {
                Image(systemName: "square.and.arrow.down").font(.system(size: 34)).foregroundStyle(.tint)
                Text(L("Install_Drop")).font(.title3.weight(.semibold))
                Button(L("Install_Pick")) { picking = true }
                    .buttonStyle(.borderedProminent)
                    .disabled(install.isInstalling)
            }
            .frame(maxWidth: .infinity)
            .padding(36)
            .background(targeted ? Color.accentColor.opacity(0.12) : Color.clear, in: RoundedRectangle(cornerRadius: Radius.card))
            .overlay(RoundedRectangle(cornerRadius: Radius.card).strokeBorder(style: StrokeStyle(lineWidth: 2, dash: [7, 5]))
                .foregroundStyle(targeted ? Color.accentColor : Color.secondary.opacity(0.5)))
            .dropDestination(for: URL.self) { urls, _ in
                guard let url = urls.first else { return false }
                Task { await install.install(url) }
                return true
            } isTargeted: { targeted = $0 }

            if install.isInstalling { ProgressView().progressViewStyle(.linear) }
            if !install.status.isEmpty { Note(text: install.status, kind: install.statusIsError ? .error : .success) }
            Note(text: L("Install_Warning"), kind: .warning)
        }
        .fileImporter(isPresented: $picking, allowedContentTypes: [Self.apk]) { result in
            if case .success(let url) = result { Task { await install.install(url) } }
        }
    }
}

struct RemoteView: View {
    @Bindable var remote: RemoteModel
    @FocusState private var typing: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 24) {
            VStack(alignment: .leading, spacing: 12) {
                Text(L("Remote_Title")).font(.largeTitle.weight(.semibold))
                Text(L("Remote_Lead")).font(.title3).foregroundStyle(.secondary)
                ZStack {
                    RoundedRectangle(cornerRadius: Radius.screen).fill(.black)
                    if let img = remote.screen {
                        Image(nsImage: img).resizable().aspectRatio(contentMode: .fit)
                    } else {
                        Text(L("Remote_Loading")).foregroundStyle(.white.opacity(0.6))
                    }
                }
                .aspectRatio(16 / 9, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: Radius.screen))
                HStack(spacing: 14) {
                    Toggle(L("Remote_Live"), isOn: $remote.isLive).toggleStyle(.checkbox)
                    if !remote.isLive { Button(L("Health_Refresh")) { Task { await remote.refreshOnce() } } }
                    Text(L("Remote_Protected")).font(.caption).foregroundStyle(.secondary)
                }
            }

            GroupBox {
                VStack(spacing: 16) {
                    Grid(horizontalSpacing: 8, verticalSpacing: 8) {
                        GridRow { Color.clear.frame(width: 1, height: 1); key("chevron.up", 19); Color.clear.frame(width: 1, height: 1) }
                        GridRow {
                            key("chevron.left", 21)
                            Button { Task { await remote.key(23) } } label: { Text("OK").frame(width: 46, height: 34) }
                                .buttonStyle(.borderedProminent).buttonBorderShape(.capsule)
                            key("chevron.right", 22)
                        }
                        GridRow { Color.clear.frame(width: 1, height: 1); key("chevron.down", 20); Color.clear.frame(width: 1, height: 1) }
                    }
                    Grid(horizontalSpacing: 6, verticalSpacing: 6) {
                        GridRow { wide("Remote_Back", 4); wide("Remote_Home", 3); wide("Remote_Menu", 82) }
                        GridRow { wide("Remote_Play", 85); wide("Remote_VolDown", 25); wide("Remote_VolUp", 24) }
                    }
                    VStack(alignment: .leading, spacing: 6) {
                        Text(L("Remote_TypeLabel")).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                        HStack {
                            TextField(L("Remote_TypeHint"), text: $remote.textToSend)
                                .textFieldStyle(.roundedBorder)
                                .focused($typing)
                                .onSubmit { Task { await remote.sendText() } }
                            Button(L("Remote_Send")) { Task { await remote.sendText() } }
                        }
                    }
                    Text(L("Remote_Keys")).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                }
                .padding(10)
                .frame(width: 290)
            }
            .padding(.top, 70)
        }
        .padding(28)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .focusable()
        .focusEffectDisabled()
        // Keyboard as a remote, except while typing in the text field.
        .onKeyPress { press in
            guard !typing else { return .ignored }
            let code: Int? = switch press.key {
            case .upArrow: 19
            case .downArrow: 20
            case .leftArrow: 21
            case .rightArrow: 22
            case .return, .space: 23
            case .escape, .delete: 4
            default: press.characters == "h" ? 3 : press.characters == "m" ? 82 : nil
            }
            guard let code else { return .ignored }
            Task { await remote.key(code) }
            return .handled
        }
        .onAppear { if remote.isLive { remote.start() } }
        .onDisappear { remote.stop() }
    }

    private func key(_ symbol: String, _ code: Int) -> some View {
        Button { Task { await remote.key(code) } } label: {
            Image(systemName: symbol).frame(width: 46, height: 34)
        }
    }

    private func wide(_ label: String, _ code: Int) -> some View {
        Button { Task { await remote.key(code) } } label: { Text(L(label)).frame(minWidth: 60) }
    }
}
