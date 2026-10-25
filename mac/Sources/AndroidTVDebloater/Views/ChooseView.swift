import SwiftUI
import UniformTypeIdentifiers

struct ChooseView: View {
    @Environment(AppModel.self) private var app
    @Bindable var choose: ChooseModel
    @State private var exporting = false
    @State private var importing = false

    var body: some View {
        Form {
            // The TV
            Section {
                HStack(alignment: .center, spacing: 14) {
                    Image(systemName: "tv").font(.system(size: 28)).foregroundStyle(.tint).frame(width: 40)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(choose.device.displayName).font(.title3.weight(.semibold))
                        Text(choose.infoLine).font(.callout).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button(L("Choose_ChangeTv")) { app.goConnect() }
                }
                .padding(.vertical, 4)
            }

            // Fire TV: Amazon's apps can't be turned off; its tracking is in the TV's menu
            if choose.device.isFireTv {
                Section {
                    HStack(alignment: .top, spacing: 14) {
                        Image(systemName: "exclamationmark.shield").font(.title2).foregroundStyle(.tint).frame(width: 40)
                        VStack(alignment: .leading, spacing: 6) {
                            Text(L("Fire_Title")).font(.headline)
                            Text(L("Fire_Text")).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                            if let on = choose.firePrivacyOn {
                                Text(choose.firePrivacyText)
                                    .fontWeight(on.isEmpty ? .regular : .semibold)
                                    .foregroundStyle(on.isEmpty ? .green : .orange)
                                    .fixedSize(horizontal: false, vertical: true)
                                HStack {
                                    if !on.isEmpty {
                                        Button(L("Fire_Open")) { Task { await FirePrivacy.openOnTv(Adb.shared, serial: choose.device.serial) } }
                                    }
                                    Button(L("Home_CheckAgain")) { Task { await choose.checkFirePrivacy() } }
                                }
                            }
                        }
                    }
                    .padding(.vertical, 4)
                }
            }

            // Things an update turned back on
            if choose.returned != nil {
                Section {
                    HStack(spacing: 14) {
                        Image(systemName: "clock.arrow.circlepath").font(.title2).foregroundStyle(.orange).frame(width: 40)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(L("Choose_ReturnedTitle")).font(.headline)
                            Text(choose.returnedText).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                            ForEach(choose.returnedNames, id: \.self) { name in
                                Text("• " + name).fixedSize(horizontal: false, vertical: true)
                            }
                        }
                        Spacer()
                        Button(L("Choose_LastNo")) { choose.returned = nil }
                        Button(L("Choose_ReturnedTick")) { choose.tickReturned() }.buttonStyle(.borderedProminent)
                    }
                    .padding(.vertical, 4)
                }
            }

            // Same choices as last time, e.g. on the other TV
            if let last = choose.lastChoices {
                Section {
                    HStack(spacing: 14) {
                        Image(systemName: "square.on.square").font(.title2).foregroundStyle(.tint).frame(width: 40)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(L("Choose_LastTitle")).font(.headline)
                            Text(L("Choose_LastText", last.choices.count, last.name, last.createdText))
                                .foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                        }
                        Spacer()
                        Button(L("Choose_LastNo")) { choose.lastChoices = nil }
                        Button(L("Choose_LastUse")) { choose.useLastChoices() }.buttonStyle(.borderedProminent)
                    }
                    .padding(.vertical, 4)
                }
            }

            Section {
                VStack(alignment: .leading, spacing: 10) {
                    Text(L("Choose_Title")).font(.title2.weight(.semibold))
                    Text(L("Choose_Lead")).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    HStack {
                        Button(L("Choose_Recommended")) { choose.selectRecommended() }
                        Button(L("Choose_None")) { choose.selectNone() }
                        Spacer()
                        Button(L("Choose_SaveChoices")) { exporting = true }
                        Button(L("Choose_LoadChoices")) { importing = true }
                    }
                    if !choose.choicesStatus.isEmpty { Note(text: choose.choicesStatus) }
                }
                .padding(.vertical, 4)
            }

            ForEach(choose.groups) { group in
                Section {
                    ForEach(group.tweaks) { tweak in
                        TweakRow(tweak: tweak)
                            .onChange(of: tweak.isSelected) { choose.enforceKeeps(tweak) }
                            .onChange(of: tweak.selectedOption) { choose.enforceKeeps(tweak) }
                    }
                } header: {
                    HStack(alignment: .firstTextBaseline) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(group.title.text).font(.headline).foregroundStyle(.primary)
                            Text(group.description.text).font(.callout).foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        Spacer()
                        Text(group.countText).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                    }
                    .textCase(nil)
                    .padding(.top, 8)
                }
            }

            // Help grow the catalog
            if !choose.unknownApps.isEmpty {
                Section {
                    HStack(alignment: .top, spacing: 14) {
                        Image(systemName: "questionmark.app").font(.title2).foregroundStyle(.tint).frame(width: 40)
                        VStack(alignment: .leading, spacing: 8) {
                            Text(L("Choose_UnknownTitle")).font(.headline)
                            Text(L("Choose_UnknownText", choose.unknownApps.count))
                                .foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                            HStack {
                                Button(L("Choose_UnknownReport")) { choose.reportUnknown() }
                                Button(L("Choose_UnknownCopy")) { choose.copyUnknown() }.buttonStyle(.link)
                            }
                        }
                    }
                    .padding(.vertical, 4)
                }
            }
        }
        .formStyle(.grouped)
        .safeAreaInset(edge: .bottom) {
            HStack(spacing: 16) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(choose.selectedText).font(.headline)
                    Toggle(L("Choose_Reboot"), isOn: $choose.rebootAfter).toggleStyle(.checkbox)
                }
                Spacer()
                Button {
                    app.goApply(choose.selected, reboot: choose.rebootAfter)
                } label: {
                    Text(L("Choose_Apply", choose.selectedCount)).padding(.horizontal, 8)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                // Deliberately no Return shortcut: a stray key press must never change the TV.
                .disabled(choose.selectedCount == 0)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
            .background(.bar)
            .overlay(alignment: .top) { Divider() }
        }
        .fileExporter(isPresented: $exporting, document: ChoiceSetDocument(set: choose.currentChoices),
                      contentType: .json, defaultFilename: "choices-\(choose.device.displayName).json") { result in
            if case .success(let url) = result { choose.choicesStatus = L("Choose_Saved", url.lastPathComponent) }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json]) { result in
            guard case .success(let url) = result else { return }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            if let data = try? Data(contentsOf: url), let set = ChoiceSet.decode(data) { choose.use(set) }
            else { choose.choicesStatus = L("Choose_LoadFailed") }
        }
    }
}

struct TweakRow: View {
    @Bindable var tweak: Tweak

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Toggle(isOn: $tweak.isSelected) {
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 8) {
                        Text(tweak.title.text).font(.body.weight(.semibold))
                        if tweak.recommended {
                            Text(L("Choose_RecommendedPill"))
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(.green)
                                .padding(.horizontal, 6).padding(.vertical, 1)
                                .background(.green.opacity(0.15), in: Capsule())
                        }
                        if tweak.isDone {
                            Text(L("Choose_DonePill"))
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(.green)
                                .padding(.horizontal, 6).padding(.vertical, 1)
                                .background(.green.opacity(0.15), in: Capsule())
                        }
                    }
                    Text(tweak.description.text).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    if let w = tweak.warning {
                        Label(w.text, systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(.orange).font(.callout)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Text(tweak.technical).font(.caption.monospaced()).foregroundStyle(.tertiary)
                }
            }
            .toggleStyle(.switch)
            if !tweak.options.isEmpty {
                Picker("", selection: Binding(get: { tweak.selectedOption ?? "" }, set: { tweak.selectedOption = $0 })) {
                    ForEach(tweak.options) { o in Text(o.label.text).tag(o.value) }
                }
                .labelsHidden()
                .frame(maxWidth: 360, alignment: .leading)
                .disabled(!tweak.isSelected)
            }
        }
        .padding(.vertical, 3)
    }
}
