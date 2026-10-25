import SwiftUI

struct AppsView: View {
    @Bindable var apps: AppsModel

    var body: some View {
        PageLayout(title: L("Apps_Title"), subtitle: L("Apps_Lead")) {
            Note(text: L("Apps_Note"))
            let outside = apps.outside
            if !outside.isEmpty {
                // Apps that didn't come from a store, by name
                VStack(alignment: .leading, spacing: 8) {
                    Label { Text(L("Apps_Outside", outside.count)).fixedSize(horizontal: false, vertical: true) }
                        icon: { Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange) }
                    Text(apps.outsideList).textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
                        .padding(.leading, 26)
                    Button(L(apps.onlyOutside ? "Apps_ShowAll" : "Apps_ShowOutside")) { apps.onlyOutside.toggle() }
                        .buttonStyle(.link)
                        .padding(.leading, 26)
                }
                .padding(10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 8))
            }
            if apps.isLoading { ProgressView().controlSize(.small) }
            if !apps.status.isEmpty { Note(text: apps.status, kind: apps.statusIsError ? .error : .success) }

            if !apps.isLoading && apps.rows.isEmpty {
                Note(text: L("Apps_None"), kind: .success)
            } else if !apps.rows.isEmpty {
                HStack {
                    TextField(L("Apps_Search"), text: $apps.search).textFieldStyle(.roundedBorder)
                    Picker("", selection: $apps.sort) {
                        Text(L("Apps_SortName")).tag(AppsModel.Sort.name)
                        Text(L("Apps_SortSize")).tag(AppsModel.Sort.size)
                        Text(L("Apps_SortNewest")).tag(AppsModel.Sort.newest)
                    }
                    .labelsHidden()
                    .fixedSize()
                }
                Text(apps.summary).font(.caption).foregroundStyle(.secondary)

                let visible = apps.visible
                if visible.isEmpty {
                    Text(L("Apps_NoMatch")).foregroundStyle(.secondary)
                } else {
                    Card {
                        ForEach(Array(visible.enumerated()), id: \.element.id) { i, row in
                            if i > 0 { Divider() }
                            AppRowView(row: row, apps: apps)
                        }
                    }
                }
            }
        }
        .toolbar {
            Button { Task { await apps.refresh() } } label: { Label(L("Health_Refresh"), systemImage: "arrow.clockwise") }
                .help(L("Health_Refresh"))
                .disabled(apps.isLoading)
        }
    }
}

private struct AppRowView: View {
    var row: AppRow
    var apps: AppsModel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 12) {
                // 16:9, like the tiles on the TV's home screen
                Group {
                    if let icon = row.icon {
                        Image(nsImage: icon).resizable().interpolation(.high).aspectRatio(contentMode: .fit)
                    } else {
                        RoundedRectangle(cornerRadius: 6).fill(.quaternary)
                    }
                }
                .frame(width: 64, height: 36)
                .clipShape(RoundedRectangle(cornerRadius: 6))
                VStack(alignment: .leading, spacing: 1) {
                    Text(row.name).lineLimit(1).truncationMode(.middle)
                    if row.hasFriendlyName {
                        Text(row.package).font(.caption.monospaced()).foregroundStyle(.tertiary)
                    }
                    Text(row.details).font(.caption).foregroundStyle(.secondary).lineLimit(1).truncationMode(.tail)
                }
                Spacer()
                if row.isProtected { pill(L("Apps_Needed"), .accentColor) }
                if row.isOutside { pill(L("Apps_NotFromStore"), .orange) }
                if row.isOff { pill(L("Apps_Off"), .secondary) }
                if row.isGone {
                    Label(L(row.goneKey), systemImage: "checkmark.circle.fill").foregroundStyle(.green)
                } else {
                    menu
                }
            }
            if row.isConfirming {
                HStack {
                    Label(row.confirmText, systemImage: "exclamationmark.triangle.fill")
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer()
                    Button(L("Common_Cancel")) { row.isConfirming = false }
                    Button(L("Apps_Uninstall")) { Task { await apps.uninstall(row) } }
                        .buttonStyle(.borderedProminent)
                }
                .padding(10)
                .background(Color.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 8))
            }
        }
    }

    private var menu: some View {
        Menu(L("Apps_Actions")) {
            Button(L("Apps_Open")) { Task { await apps.run(row, "Apps_Opened", UserApps.open) } }.disabled(!row.canOpen)
            Button(L("Apps_ShowInfo")) { Task { await apps.run(row, "Apps_InfoOpened", UserApps.showInfo) } }
            Button(L("Apps_Stop")) { Task { await apps.run(row, "Apps_Stopped", UserApps.stop) } }.disabled(!row.canStop)
            Divider()
            if row.app.isEnabled {
                Button(L("Apps_TurnOff")) { Task { await apps.setEnabled(row, on: false) } }.disabled(!row.canTurnOff)
            } else {
                Button(L("Apps_TurnOn")) { Task { await apps.setEnabled(row, on: true) } }.disabled(!row.canTurnOn)
            }
            Button(L("Apps_UninstallMenu")) { row.isConfirming = true }.disabled(!row.canRemove)
        }
        .fixedSize()
        .disabled(row.isWorking)
    }

    private func pill(_ text: String, _ color: Color) -> some View {
        Text(text).font(.caption.weight(.semibold)).foregroundStyle(color)
            .padding(.horizontal, 8).padding(.vertical, 1)
            .background(color.opacity(0.14), in: Capsule())
    }
}
