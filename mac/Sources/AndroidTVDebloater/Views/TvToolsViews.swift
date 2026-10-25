import SwiftUI

struct TvInfoView: View {
    var info: TvInfoModel

    var body: some View {
        PageLayout(title: L("Info_Title"), subtitle: L("Info_Lead")) {
            if info.isLoading { ProgressView().controlSize(.small) }
            if !info.status.isEmpty { Note(text: info.status, kind: info.statusIsError ? .error : .success) }

            if info.info != nil {
                ForEach(info.tips) { tip in
                    Note(text: tip.text, kind: tip.kind == .ok ? .success : tip.kind == .warn ? .warning : .info)
                }

                Card(title: L("Info_SoftwareTitle"), systemImage: "tv") {
                    facts(info.software)
                    Button(L("Info_OpenUpdate")) { Task { await info.openUpdate() } }
                }

                Card(title: L("Info_BootTitle"), systemImage: "lock.shield") {
                    facts(info.boot)
                    Text(L("Info_BootText")).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                }

                Card(title: L("Info_RestartTitle"), systemImage: "arrow.clockwise.circle") {
                    if info.isConfirmingRestart {
                        HStack {
                            Label(L("Info_ConfirmRestart"), systemImage: "exclamationmark.triangle.fill")
                                .fixedSize(horizontal: false, vertical: true)
                            Spacer()
                            Button(L("Common_Cancel")) { info.isConfirmingRestart = false }
                            Button(L("Info_ConfirmYes")) { Task { await info.restart() } }
                                .buttonStyle(.borderedProminent)
                        }
                        .padding(10)
                        .background(Color.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 8))
                    }
                    if info.isRestarting { ProgressView().controlSize(.small) }
                    HStack(alignment: .center, spacing: 16) {
                        Text(L("Info_RestartText")).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                        Spacer()
                        Button(L("Info_Restart")) { info.isConfirmingRestart = true }.disabled(info.isRestarting)
                    }
                }

                Card(title: L("Info_ResetTitle"), systemImage: "arrow.counterclockwise.circle", tint: .orange) {
                    Text(L("Info_ResetText")).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    Button(L("Info_ResetOpen")) { Task { await info.openAbout() } }
                }
            }
        }
        .toolbar {
            Button { Task { await info.refresh() } } label: { Label(L("Health_Refresh"), systemImage: "arrow.clockwise") }
                .help(L("Health_Refresh"))
                .disabled(info.isLoading)
        }
    }

    private func facts(_ rows: [InfoRow]) -> some View {
        Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 6) {
            ForEach(rows) { r in
                GridRow {
                    Text(r.label).foregroundStyle(.secondary)
                    Text(r.value).fontWeight(.semibold).textSelection(.enabled)
                }
            }
        }
    }
}

struct PrivacyView: View {
    var privacy: PrivacyModel

    var body: some View {
        PageLayout(title: L("Privacy_Title"), subtitle: L("Privacy_Lead")) {
            Note(text: L("Privacy_Note"))
            if privacy.isLoading { ProgressView().controlSize(.small) }
            if !privacy.status.isEmpty { Note(text: privacy.status, kind: .error) }
            if !privacy.isLoading && privacy.groups.isEmpty { Note(text: L("Privacy_None"), kind: .success) }

            ForEach(privacy.groups) { g in
                Card(title: L("Privacy_Kind_" + g.kind.key), systemImage: Self.icon(g.kind.key)) {
                    HStack {
                        Text(L("Privacy_KindText_" + g.kind.key)).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                        Spacer()
                        Text(L("Privacy_Count", g.rows.count)).font(.caption).foregroundStyle(.tertiary)
                    }
                    ForEach(g.rows) { row in
                        Divider()
                        AccessRowView(row: row) { Task { await privacy.remove(row) } }
                    }
                }
            }
        }
        .toolbar {
            Button { Task { await privacy.refresh() } } label: { Label(L("Health_Refresh"), systemImage: "arrow.clockwise") }
                .help(L("Health_Refresh"))
                .disabled(privacy.isLoading)
        }
    }

    private static func icon(_ key: String) -> String {
        switch key {
        case "BgLocation": "location.fill"
        case "Location": "location"
        case "Microphone": "mic"
        case "Camera": "camera"
        case "Contacts": "person.crop.circle"
        default: "calendar"
        }
    }
}

private struct AccessRowView: View {
    var row: AccessRow
    var remove: () -> Void

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 1) {
                Text(row.name).lineLimit(1).truncationMode(.middle)
                if row.name != row.package {
                    Text(row.package).font(.caption.monospaced()).foregroundStyle(.tertiary)
                }
            }
            Spacer()
            Text(L(row.access.isThirdParty ? "Privacy_Yours" : "Privacy_Preinstalled")).font(.caption).foregroundStyle(.tertiary)
            if row.isRemoved {
                Label(L("Privacy_Removed"), systemImage: "checkmark.circle.fill").foregroundStyle(.green)
            } else {
                Button(L("Privacy_Remove"), action: remove).disabled(row.isWorking)
            }
        }
    }
}
