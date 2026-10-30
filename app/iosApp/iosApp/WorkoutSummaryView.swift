import SwiftUI
import SharedLogic

/// The finished walk, whether it reached Apple Health, and what to do if it did not.
struct WorkoutSummaryView: View {
    let summary: WorkoutSummaryUi
    let health: HealthUi
    let actions: TreadmillActions
    let onEditWeight: () -> Void

    @State private var confirmingDelete = false

    private var start: Date { Date(timeIntervalSince1970: Double(summary.startEpochMs) / 1000) }
    private var end: Date { Date(timeIntervalSince1970: Double(summary.endEpochMs) / 1000) }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    WorkoutMetricsGrid(metrics: metrics)
                        .listRowInsets(EdgeInsets())
                } header: {
                    Text((start..<end).formatted(.interval.weekday(.abbreviated).day().month(.abbreviated).hour().minute()))
                } footer: {
                    VStack(alignment: .leading, spacing: 4) {
                        if summary.energyFromHeartRate {
                            Text("Calories use your heart rate.")
                        }
                        if summary.weightEstimated {
                            HStack {
                                Text("Calories use an estimated weight of 70 kg.")
                                Button("Set Weight", action: onEditWeight)
                                    .font(.footnote.weight(.semibold))
                            }
                        }
                    }
                }

                Section("Apple Health") {
                    syncStatus
                    if health.showPriorityTip {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Avoid Counting Steps Twice")
                                .font(.subheadline.weight(.semibold))
                            Text("Your iPhone counts steps too. In the Health app, open Steps › Data Sources & Access, tap Edit and move hashiru to the top.")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                Section {
                    Button("Delete Walk", role: .destructive) { confirmingDelete = true }
                }
            }
            .navigationTitle("Indoor Walk")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done", action: actions.dismissSummary)
                }
            }
            .confirmationDialog("Delete this walk?", isPresented: $confirmingDelete, titleVisibility: .visible) {
                Button("Delete Walk", role: .destructive, action: actions.deleteSummary)
            } message: {
                Text("It is removed from hashiru and Apple Health.")
            }
        }
        .presentationDetents([.large])
    }

    /// The same grid as a past walk's details, so the two read alike.
    private var metrics: [WorkoutMetric] {
        var metrics = [
            WorkoutMetric(title: "Workout Time", value: formatDuration(Int(summary.durationSeconds)), color: .workoutVolt),
            WorkoutMetric(title: "Distance", value: "\(kilometres(summary.distanceMeters)) KM", color: .workoutDistance),
            WorkoutMetric(title: "Active Kilocalories", value: "\(summary.activeKcal) KCAL", color: .workoutEnergy),
            WorkoutMetric(title: "Total Kilocalories", value: "\(summary.totalKcal) KCAL", color: .workoutEnergy),
            WorkoutMetric(title: "Steps", value: Int(summary.steps).formatted(), color: .workoutSteps),
            WorkoutMetric(title: "Avg. Pace", value: "\(pace(summary.paceSecondsPerKm?.int32Value))/KM", color: .workoutDistance),
        ]
        if let avg = summary.avgHeartRate, let max = summary.maxHeartRate {
            metrics.append(WorkoutMetric(title: "Avg. Heart Rate", value: "\(avg.int32Value) BPM", color: .workoutHeart))
            metrics.append(WorkoutMetric(title: "Max. Heart Rate", value: "\(max.int32Value) BPM", color: .workoutHeart))
        }
        return metrics
    }

    @ViewBuilder private var syncStatus: some View {
        switch summary.sync {
        case .saved:
            Label("Saved to Apple Health", systemImage: "checkmark.circle.fill")
                .foregroundStyle(.green)
        case .saving:
            HStack(spacing: 12) {
                ProgressView()
                Text("Saving to Apple Health…")
            }
        case .busy:
            Label("Saves to Apple Health when you unlock your iPhone.", systemImage: "clock")
                .foregroundStyle(.secondary)
        case .waitingforaccess:
            VStack(alignment: .leading, spacing: 12) {
                Label("Not saved yet. Connect Apple Health to add this walk.", systemImage: "heart.fill")
                    .symbolRenderingMode(.multicolor)
                HStack {
                    Button("Not Now", action: actions.dismissHealthCard)
                        .secondaryButtonStyle()
                    Button("Connect", action: actions.connectHealth)
                        .prominentButtonStyle()
                }
            }
        case .failed:
            // The store's own message is for support, not the headline.
            NoticeRow(
                title: "Couldn’t Save to Apple Health",
                message: summary.syncMessage.flatMap { $0.isEmpty ? nil : "Details: \($0)" },
                systemImage: "exclamationmark.triangle.fill",
                tint: .red,
                action: "Try Again",
                onAction: actions.retrySync
            )
        case .notsaved:
            Label("Kept on this iPhone only.", systemImage: "iphone")
                .foregroundStyle(.secondary)
        default:
            Label("Apple Health is not available on this device.", systemImage: "iphone")
                .foregroundStyle(.secondary)
        }
    }
}

#Preview("Saved") {
    Text("").sheet(isPresented: .constant(true)) {
        WorkoutSummaryView(
            summary: previewSummary,
            health: previewHealth(showPriorityTip: true),
            actions: TreadmillActions(), onEditWeight: {}
        )
    }
}
