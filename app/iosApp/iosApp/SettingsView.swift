import SwiftUI
import SharedLogic

/// Behind the profile button on Summary, like the account sheet in Fitness: what calories and zones need,
/// the weekly goal, Apple Health and the WalkingPad.
struct SettingsView: View {
    let state: TreadmillScreenState
    let history: HistoryUiState
    let actions: TreadmillActions
    let historyActions: HistoryActions

    @State private var editingWeight = false
    @State private var editingProfile = false
    @State private var confirmingForget = false
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    // Apple Health's weight wins over the one entered here, so it is shown instead of asked for.
                    if let kg = state.workout.healthWeightKg?.doubleValue {
                        LabeledContent {
                            Text(formatWeight(kg))
                        } label: {
                            Text("Body Weight")
                            Text("From Apple Health")
                        }
                    } else {
                        Button(action: { editingWeight = true }) {
                            LabeledContent("Body Weight", value: weightSummary)
                        }
                    }
                    // Only asked for what Apple Health does not provide.
                    if !state.workout.ageFromHealth || !state.workout.sexFromHealth {
                        Button(action: { editingProfile = true }) {
                            LabeledContent("Age and Sex", value: profileSummary)
                        }
                    }
                } header: {
                    Text("Profile")
                } footer: {
                    Text("Used for calories and heart-rate zones when Apple Health has none.")
                }
                .tint(.primary)

                Section("Goal") {
                    Stepper(value: goalKm, in: 1...200) {
                        LabeledContent("Weekly Distance", value: "\(goalKm.wrappedValue) km")
                    }
                }

                Section("Apple Health") {
                    healthStatus
                }

                Section("WalkingPad") {
                    LabeledContent("Status") {
                        Text(state.treadmill.isConnected ? "Connected" : state.treadmill.isConnecting ? "Connecting…" : "Not Connected")
                    }
                    if state.treadmill.isPaired {
                        Button("Forget WalkingPad", role: .destructive) { confirmingForget = true }
                    }
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .weightAlert(isPresented: $editingWeight, initialKg: state.workout.manualWeightKg?.doubleValue, onSave: actions.setWeight)
            .sheet(isPresented: $editingProfile) {
                ProfileSheet(
                    initialBirthYear: state.workout.manualBirthYear.map { Int($0.int32Value) },
                    initialSex: state.workout.manualSex,
                    onSave: { year, sex in
                        actions.setProfile(year, sex)
                        editingProfile = false
                    },
                    onCancel: { editingProfile = false }
                )
            }
            .confirmationDialog("Forget your WalkingPad?", isPresented: $confirmingForget, titleVisibility: .visible) {
                Button("Forget WalkingPad", role: .destructive, action: actions.forget)
            } message: {
                Text("You need to pair it again to walk with it.")
            }
        }
    }

    private var goalKm: Binding<Int> {
        Binding(
            get: { Int(history.weeklyGoalMeters) / 1000 },
            set: { historyActions.setWeeklyGoal($0 * 1000) }
        )
    }

    @ViewBuilder private var healthStatus: some View {
        let health = state.workout.health
        if !health.available {
            Label("Not available on this device.", systemImage: "iphone")
                .foregroundStyle(.secondary)
        } else if health.accessDenied {
            Label("Access is off. Walks stay on this iPhone.", systemImage: "heart.slash")
            Button("Open Settings") {
                if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
            }
        } else if history.healthConnected {
            Label("Connected", systemImage: "checkmark.circle.fill")
                .foregroundStyle(.green)
        } else {
            Button("Connect Apple Health", systemImage: "heart.fill", action: actions.connectHealth)
        }
    }

    private var weightSummary: String {
        guard let kg = state.workout.manualWeightKg?.doubleValue else { return String(localized: "Not Set") }
        return formatWeight(kg)
    }

    private func formatWeight(_ kg: Double) -> String {
        Measurement(value: kg, unit: UnitMass.kilograms).formatted(.measurement(width: .abbreviated, usage: .asProvided))
    }

    private var profileSummary: String {
        let year = state.workout.manualBirthYear.map { String(localized: "Born \(String($0.int32Value))") }
        let sex = state.workout.manualSex.map { $0 == .female ? String(localized: "Female") : String(localized: "Male") }
        let parts = [year, sex].compactMap { $0 }
        return parts.isEmpty ? String(localized: "Not Set") : parts.joined(separator: " · ")
    }
}

/// Birth year and sex for heart-rate calories and zones, where Apple Health has none; either may stay empty.
private struct ProfileSheet: View {
    let initialBirthYear: Int?
    let initialSex: Sex?
    let onSave: (Int?, Sex?) -> Void
    let onCancel: () -> Void

    @State private var yearText = ""
    @State private var female: Bool?

    private var year: Int? { Int(yearText).flatMap { (1900...2100).contains($0) ? $0 : nil } }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Birth Year", text: $yearText)
                        .keyboardType(.numberPad)
                    Picker("Sex", selection: $female) {
                        Text("Not Set").tag(Bool?.none)
                        Text("Female").tag(Bool?.some(true))
                        Text("Male").tag(Bool?.some(false))
                    }
                } footer: {
                    if !yearText.isEmpty && year == nil {
                        Text("Enter a four-digit year, like 1985.")
                            .foregroundStyle(.red)
                    } else {
                        Text("Used with heart rate from your Apple Watch for calories and heart-rate zones.")
                    }
                }
            }
            .navigationTitle("Age and Sex")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onCancel) }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { onSave(year, female.map { $0 ? Sex.female : Sex.male }) }
                        .disabled(!yearText.isEmpty && year == nil)
                }
            }
        }
        .presentationDetents([.medium])
        .onAppear {
            yearText = initialBirthYear.map(String.init) ?? ""
            female = initialSex.map { $0 == .female }
        }
    }
}

extension View {
    /// Asks for the weight used for calories when Apple Health has none; saving an empty field clears it.
    func weightAlert(isPresented: Binding<Bool>, initialKg: Double?, onSave: @escaping (Double?) -> Void) -> some View {
        modifier(WeightAlert(isPresented: isPresented, initialKg: initialKg, onSave: onSave))
    }
}

private struct WeightAlert: ViewModifier {
    @Binding var isPresented: Bool
    let initialKg: Double?
    let onSave: (Double?) -> Void

    @State private var text = ""

    func body(content: Content) -> some View {
        content
            .alert("Body Weight", isPresented: $isPresented) {
                TextField("Weight in kg", text: $text)
                    .keyboardType(.decimalPad)
                Button("Save") { onSave(parsed) }
                    .disabled(parsed == nil && !text.isEmpty)
                Button("Cancel", role: .cancel) {}
            } message: {
                if parsed == nil && !text.isEmpty {
                    Text("Enter a weight from 20 to 300 kg.")
                } else {
                    Text("Used to estimate calories when Apple Health has no weight for you.")
                }
            }
            .onChange(of: isPresented) { _, presented in
                if presented { text = initialKg.map { $0.formatted(.number.precision(.fractionLength(0...1))) } ?? "" }
            }
    }

    private var parsed: Double? {
        guard let kg = Double(text.replacingOccurrences(of: ",", with: ".")), (20...300).contains(kg) else { return nil }
        return kg
    }
}

#Preview {
    SettingsView(state: previewState(), history: previewHistory(), actions: TreadmillActions(), historyActions: HistoryActions())
}
