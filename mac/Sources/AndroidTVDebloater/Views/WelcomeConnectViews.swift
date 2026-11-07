import SwiftUI

struct WelcomeView: View {
    @Environment(AppModel.self) private var app
    var welcome: WelcomeModel

    var body: some View {
        PageLayout(title: L("Welcome_Title"), subtitle: L("Welcome_Lead")) {
            HStack(alignment: .top, spacing: 12) {
                feature("checkmark.shield", L("Welcome_F1Title"), L("Welcome_F1Text"))
                feature("arrow.uturn.backward.circle", L("Welcome_F2Title"), L("Welcome_F2Text"))
                feature("checklist", L("Welcome_F3Title"), L("Welcome_F3Text"))
            }

            Card(title: L("Welcome_PrepTitle"), systemImage: "tv") {
                ForEach(1...4, id: \.self) { i in
                    HStack(alignment: .firstTextBaseline, spacing: 10) {
                        Image(systemName: "\(i).circle.fill").foregroundStyle(.tint)
                        Text(L("Welcome_Step\(i)")).fixedSize(horizontal: false, vertical: true)
                    }
                }
                Text(L("Welcome_StepFire")).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                Note(text: L("Welcome_Supported"))
            }

            Card(title: L("Welcome_ToolsTitle"), systemImage: "wrench.and.screwdriver") {
                HStack {
                    Text(L(welcome.toolsReady ? "Welcome_ToolsReady" : "Welcome_ToolsMissing"))
                        .foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    Spacer()
                    if welcome.toolsReady {
                        Label(L("Welcome_ToolsPill"), systemImage: "checkmark.circle.fill").foregroundStyle(.green).font(.callout)
                    }
                }
                if welcome.isDownloading {
                    ProgressView(value: welcome.progress, total: 100) { Text(L("Welcome_Downloading")).font(.caption) }
                }
                if !welcome.error.isEmpty { Note(text: welcome.error, kind: .error) }
            }

            Button {
                Task { await welcome.continueTapped(app) }
            } label: {
                Text(L("Welcome_Continue")).padding(.horizontal, 10)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .keyboardShortcut(.defaultAction)
            .disabled(welcome.isDownloading)
        }
    }

    private func feature(_ icon: String, _ title: String, _ text: String) -> some View {
        GroupBox {
            VStack(alignment: .leading, spacing: 6) {
                Image(systemName: icon).font(.title2).foregroundStyle(.tint)
                Text(title).font(.headline)
                Text(text).font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
            .padding(6)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

struct ConnectView: View {
    @Bindable var connect: ConnectModel

    var body: some View {
        PageLayout(title: L("Connect_Title"), subtitle: L("Connect_Lead")) {
            if connect.state == .waitingForApproval {
                Card(title: L("Connect_ApproveTitle"), systemImage: "tv.badge.wifi") {
                    Text(L("Connect_ApproveText")).fixedSize(horizontal: false, vertical: true)
                    ProgressView().progressViewStyle(.linear)
                    Button(L("Common_Cancel")) { connect.cancel() }
                }
            } else if connect.isBusy {
                Card {
                    HStack(spacing: 12) {
                        ProgressView().controlSize(.small)
                        Text(connect.statusText).font(.headline)
                        Spacer()
                        Button(L("Common_Cancel")) { connect.cancel() }
                    }
                }
            } else if connect.state == .failed {
                Card(title: L("Connect_FailedTitle"), systemImage: "exclamationmark.triangle.fill", tint: .orange) {
                    Note(text: connect.errorDetail, kind: .error)
                    Text(L("Connect_Tips")).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                }
            }

            Group {
                if !connect.recent.isEmpty {
                    Card(title: L("Connect_RecentTitle"), systemImage: "clock.arrow.circlepath") {
                        Text(L("Connect_RecentText")).foregroundStyle(.secondary)
                        ForEach(connect.recent) { tv in tvRow(tv) }
                    }
                }

                Card(title: L("Connect_FindTitle"), systemImage: "magnifyingglass") {
                    Text(L("Connect_FindText")).foregroundStyle(.secondary)
                    HStack(spacing: 12) {
                        Button(L("Connect_Scan")) { Task { await connect.scan() } }
                            .buttonStyle(.borderedProminent)
                            .disabled(connect.isScanning)
                        if connect.isScanning {
                            ProgressView(value: connect.scanProgress, total: 100).frame(width: 200)
                        }
                    }
                    ForEach(connect.found) { tv in tvRow(tv) }
                    if connect.nothingFound { Note(text: L("Connect_NothingFound"), kind: .warning) }
                }

                Card(title: L("Connect_ManualTitle"), systemImage: "keyboard") {
                    Text(L("Connect_ManualText")).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    HStack {
                        TextField("192.168.1.20", text: $connect.address)
                            .textFieldStyle(.roundedBorder)
                            .frame(width: 240)
                            .onSubmit { connect.connect(to: connect.address) }
                        Button(L("Connect_Connect")) { connect.connect(to: connect.address) }
                            .buttonStyle(.borderedProminent)
                    }
                }

                GroupBox {
                    DisclosureGroup(L("Connect_PairTitle")) {
                        VStack(alignment: .leading, spacing: 10) {
                            Text(L("Connect_PairText")).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                            HStack {
                                TextField("192.168.1.20:37099", text: $connect.pairAddress).textFieldStyle(.roundedBorder).frame(width: 200)
                                TextField("123456", text: $connect.pairCode).textFieldStyle(.roundedBorder).frame(width: 110)
                                Button(L("Connect_Pair")) { Task { await connect.pair() } }
                            }
                            if !connect.pairResult.isEmpty { Text(connect.pairResult).foregroundStyle(.secondary) }
                        }
                        .padding(.top, 8)
                    }
                    .padding(6)
                }
            }
            .disabled(connect.isBusy)
        }
    }

    private func tvRow(_ tv: FoundTv) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "tv").font(.title3).foregroundStyle(.tint).frame(width: 30)
            VStack(alignment: .leading, spacing: 1) {
                Text(tv.name.isEmpty ? tv.address : tv.name).font(.body.weight(.semibold))
                if !tv.name.isEmpty { Text(tv.address).font(.caption).foregroundStyle(.secondary) }
            }
            Spacer()
            Button(L("Connect_ConnectThis")) { connect.connect(to: tv.address) }
        }
        .padding(8)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: Radius.control))
    }
}
