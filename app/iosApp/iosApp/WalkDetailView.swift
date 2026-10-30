import Charts
import SwiftUI
import SharedLogic

/// One past walk, like a workout in Fitness: its details in a grid, then heart rate through the walk.
struct WalkDetailView: View {
    let walk: PastWalk
    let actions: HistoryActions

    @State private var heartRate: HeartRateUi?
    @State private var confirmingDelete = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                header
                VStack(alignment: .leading, spacing: 8) {
                    Text("Workout Details")
                        .font(.title2.bold())
                    WorkoutMetricsGrid(metrics: metrics)
                }
                if let heartRate, !heartRate.points.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Heart Rate")
                            .font(.title2.bold())
                        HeartRateChart(heartRate: heartRate)
                    }
                }
                if walk.recordedByApp {
                    Button("Delete Walk", role: .destructive) { confirmingDelete = true }
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(Color.card, in: .rect(cornerRadius: 20))
                }
            }
            .padding()
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle(walk.title)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: walk.id) {
            actions.heartRate(walk) { heartRate = $0 }
        }
        .confirmationDialog("Delete this walk?", isPresented: $confirmingDelete, titleVisibility: .visible) {
            Button("Delete Walk", role: .destructive) {
                actions.delete(walk)
                dismiss()
            }
        } message: {
            Text("It is removed from Apple Health.")
        }
    }

    private var header: some View {
        HStack(spacing: 14) {
            WalkIcon(walk: walk, size: 64)
            VStack(alignment: .leading, spacing: 2) {
                Text(walk.title)
                    .font(.title3.bold())
                Text((walk.start..<walk.end).formatted(.interval.weekday(.abbreviated).day().month(.abbreviated).hour().minute()))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Label(walk.sourceName, systemImage: walk.recordedByApp ? "figure.walk.treadmill" : "applewatch")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var metrics: [WorkoutMetric] {
        var metrics = [
            WorkoutMetric(title: "Workout Time", value: formatDuration(Int(walk.durationSeconds)), color: .workoutVolt),
            WorkoutMetric(title: "Distance", value: "\(kilometres(walk.distanceMeters)) KM", color: .workoutDistance),
        ]
        if let kcal = walk.activeKcal {
            metrics.append(WorkoutMetric(title: "Active Kilocalories", value: "\(kcal.int32Value) KCAL", color: .workoutEnergy))
        }
        if let steps = walk.steps {
            metrics.append(WorkoutMetric(title: "Steps", value: Int(steps.int32Value).formatted(), color: .workoutSteps))
        }
        metrics.append(WorkoutMetric(title: "Avg. Pace", value: "\(pace(walk.paceSecondsPerKm?.int32Value))/KM", color: .workoutDistance))
        if let heartRate, let avg = heartRate.avgBpm, let max = heartRate.maxBpm {
            metrics.append(WorkoutMetric(title: "Avg. Heart Rate", value: "\(avg.int32Value) BPM", color: .workoutHeart))
            metrics.append(WorkoutMetric(title: "Max. Heart Rate", value: "\(max.int32Value) BPM", color: .workoutHeart))
        }
        return metrics
    }
}

struct WorkoutMetric {
    let title: LocalizedStringKey
    let value: String
    let color: Color
}

/// Metrics two to a row, divided by hairlines like Workout Details in Fitness.
struct WorkoutMetricsGrid: View {
    let metrics: [WorkoutMetric]

    private var rows: [[WorkoutMetric]] {
        stride(from: 0, to: metrics.count, by: 2).map { Array(metrics[$0..<min($0 + 2, metrics.count)]) }
    }

    var body: some View {
        Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 12) {
            ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                if index > 0 { Divider() }
                GridRow {
                    ForEach(Array(row.enumerated()), id: \.offset) { _, metric in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(metric.title)
                                .font(.subheadline)
                            Text(metric.value)
                                .font(.system(.title2, design: .rounded, weight: .semibold))
                                .monospacedDigit()
                                .foregroundStyle(metric.color)
                                .lineLimit(1)
                                .minimumScaleFactor(0.6)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityElement(children: .combine)
                    }
                    if row.count == 1 { Color.clear.gridCellUnsizedAxes([.horizontal, .vertical]) }
                }
            }
        }
        .padding()
        .background(Color.card, in: .rect(cornerRadius: 20))
    }
}

/// Heart rate through the walk, each reading in the colour of its zone where the age is known.
private struct HeartRateChart: View {
    let heartRate: HeartRateUi

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Chart(heartRate.points, id: \.epochMs) { point in
                PointMark(
                    x: .value("Time", Date(epochMs: point.epochMs)),
                    y: .value("BPM", Int(point.bpm))
                )
                .symbolSize(12)
                .foregroundStyle(zoneColor(point))
            }
            .chartYScale(domain: .automatic(includesZero: false))
            .chartXAxis {
                AxisMarks(values: .automatic(desiredCount: 4)) { _ in
                    AxisGridLine()
                    AxisValueLabel(format: .dateTime.hour().minute())
                }
            }
            .frame(height: 180)
            .accessibilityLabel("Heart rate chart")
            if let avg = heartRate.avgBpm {
                Text("\(avg.int32Value) BPM AVG")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.secondary)
            }
        }
        .padding()
        .background(Color.card, in: .rect(cornerRadius: 20))
    }

    private func zoneColor(_ point: HeartRatePointUi) -> Color {
        point.heartRateZone?.color ?? .workoutHeart
    }
}

#Preview("Own walk") {
    NavigationStack {
        WalkDetailView(walk: previewWalks[0], actions: HistoryActions(heartRate: { _, done in done(previewHeartRate) }))
    }
}
#Preview("Other source") {
    NavigationStack {
        WalkDetailView(walk: previewWalks[2], actions: HistoryActions())
    }
}
