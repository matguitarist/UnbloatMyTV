import SwiftUI

struct ApplyView: View {
    @Environment(AppModel.self) private var app
    var apply: ApplyModel
    @State private var detailsOpen = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack(spacing: 16) {
                    if apply.isFinished {
                        Image(systemName: apply.hasProblems ? "exclamationmark.triangle.fill" : "checkmark.circle.fill")
                            .font(.system(size: 44))
                            .foregroundStyle(apply.hasProblems ? .orange : .green)
                            .symbolRenderingMode(.hierarchical)
                    }
                    VStack(alignment: .leading, spacing: 6) {
                        Text(apply.title).font(.largeTitle.weight(.semibold))
                        Text(apply.subtitle).font(.title3).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    }
                }

                if !apply.isFinished {
                    ProgressView(value: apply.progress, total: 100)
                    GroupBox { StepList(items: apply.items).padding(4) }
                } else {
                    finished
                }
            }
            .padding(28)
            .frame(maxWidth: 820, alignment: .leading)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .onChange(of: apply.isFinished) { _, done in if done { detailsOpen = apply.hasProblems } }
    }

    @ViewBuilder private var finished: some View {
        HStack(spacing: 12) {
            stat("\(apply.appsOff)", L("Apply_StatApps"), .primary)
            stat("\(apply.settingsChanged)", L("Apply_StatSettings"), .primary)
            stat(apply.checkValue, apply.checkLabel, apply.hasProblems ? .orange : .green)
        }

        GroupBox {
            DisclosureGroup(L("Apply_Details"), isExpanded: $detailsOpen) {
                StepList(items: apply.items).padding(.top, 6)
            }
            .padding(6)
        }

        if let home = apply.homeScreen, home.hasPlayStore, !apply.debugOff {
            HomeScreenCard(home: home)
        }

        Card(title: L("Apply_BackupTitle"), systemImage: "externaldrive") {
            Text(L("Apply_BackupText")).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            Text(apply.backupPath).font(.caption.monospaced()).foregroundStyle(.secondary).textSelection(.enabled)
            HStack {
                Button(L("Mac_ShowInFinder")) { apply.openBackupFolder() }
                if !apply.debugOff { Button(L("Side_Restore")) { app.goRestore() } }
            }
        }

        if apply.debugOff {
            Note(text: apply.debugStatus, kind: .success)
        } else {
            Card(title: L("Apply_LastStepTitle"), systemImage: "lock.fill", tint: .orange) {
                Text(L("Apply_LastStep")).fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 12) {
                    Button(L("Apply_DebugOffButton")) { Task { await apply.turnOffDebugging() } }
                        .disabled(apply.isTurningOffDebug)
                    if apply.isTurningOffDebug { ProgressView().controlSize(.small) }
                }
                if !apply.debugStatus.isEmpty { Note(text: apply.debugStatus, kind: .warning) }
            }
        }

        HStack(spacing: 12) {
            Button { Task { await app.startOver() } } label: {
                Label(L("Apply_StartOver"), systemImage: "arrow.counterclockwise").padding(.horizontal, 6)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            if !apply.debugOff {
                Button(L("Apply_BackToChoices")) { Task { await app.goChoose(reload: true) } }
                    .controlSize(.large)
            }
        }
    }

    private func stat(_ value: String, _ label: String, _ color: Color) -> some View {
        GroupBox {
            VStack(alignment: .leading, spacing: 2) {
                Text(value).font(.system(size: 30, weight: .semibold).monospacedDigit()).foregroundStyle(color)
                Text(label).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
            .padding(6)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

struct StepList: View {
    var items: [StepItem]

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(items) { item in
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    icon(item.state).frame(width: 18)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.text).foregroundStyle(item.state == .pending ? .secondary : .primary)
                        if !item.detail.isEmpty {
                            Text(item.detail).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder private func icon(_ s: StepState) -> some View {
        switch s {
        case .pending: Image(systemName: "circle").foregroundStyle(.tertiary)
        case .running: ProgressView().controlSize(.mini)
        case .done: Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
        case .failed: Image(systemName: "xmark.circle.fill").foregroundStyle(.red)
        case .skipped: Image(systemName: "minus.circle.fill").foregroundStyle(.secondary)
        }
    }
}

struct HomeScreenCard: View {
    var home: HomeScreenModel

    var body: some View {
        Card(title: L("Home_Title"), systemImage: "house") {
            Text(L("Home_Text")).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            ForEach(home.launchers) { l in
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 3) {
                        HStack(spacing: 8) {
                            Text(l.name).font(.body.weight(.semibold))
                            if l.isCurrent { pill(L("Home_CurrentPill"), .green) }
                            else if l.isInstalled { pill(L("Home_InstalledPill"), .accentColor) }
                        }
                        Text(l.description.text).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer()
                    if l.canInstall { Button(L("Home_OpenStore")) { Task { await home.openStore(l) } } }
                    if l.canInstallFromGitHub { Button(L("Home_InstallGitHub")) { Task { await home.installFromGitHub(l) } } }
                    if l.canMakeHome {
                        Button(L("Home_MakeHome")) { Task { await home.makeHome(l) } }.buttonStyle(.borderedProminent)
                    }
                }
                .padding(8)
                .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: Radius.control))
            }
            if home.isBusy { ProgressView().progressViewStyle(.linear) }
            if !home.status.isEmpty { Note(text: home.status) }
            if home.isGoogleTv { Text(L("Home_GoogleTvNote")).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true) }
            HStack {
                Button(L("Home_CheckAgain")) { Task { await home.checkAgain() } }
                if home.canRestoreOriginal { Button(L("Home_RestoreOriginal")) { Task { await home.restoreOriginal() } } }
            }
        }
        .disabled(home.isBusy)
    }

    private func pill(_ text: String, _ color: Color) -> some View {
        Text(text).font(.caption2.weight(.semibold)).foregroundStyle(color)
            .padding(.horizontal, 6).padding(.vertical, 1)
            .background(color.opacity(0.15), in: Capsule())
    }
}

struct RestoreView: View {
    @Environment(AppModel.self) private var app
    var restore: RestoreModel

    var body: some View {
        PageLayout(title: L("Restore_Title"), subtitle: L("Restore_Lead")) {
            if restore.isBusy { ProgressView().progressViewStyle(.linear) }
            if !restore.status.isEmpty { Note(text: restore.status, kind: restore.statusIsError ? .error : .success) }

            Card(title: L("Restore_RunsTitle"), systemImage: "clock.arrow.circlepath") {
                if restore.backups.isEmpty {
                    Text(L("Restore_None")).foregroundStyle(.secondary)
                } else {
                    ForEach(restore.backups, id: \.fileURL) { b in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(b.createdText).font(.body.weight(.semibold))
                                Text(restore.summary(b)).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button(L("Restore_Undo")) { Task { await restore.restore(b) } }
                        }
                        .padding(8)
                        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: Radius.control))
                    }
                }
            }
            .disabled(restore.isBusy)

            Card(title: L("Restore_AllTitle"), systemImage: "arrow.uturn.backward.circle") {
                Text(L("Restore_AllText")).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                Button(L("Restore_AllButton")) { Task { await restore.enableAll() } }
            }
            .disabled(restore.isBusy)

            if restore.homeScreen.hasPlayStore {
                HomeScreenCard(home: restore.homeScreen).disabled(restore.isBusy)
            }

            Button(L("Common_Back")) { Task { await app.goChoose(reload: true) } }.disabled(restore.isBusy)
        }
    }
}
