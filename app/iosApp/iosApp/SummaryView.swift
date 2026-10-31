import Charts
import SwiftUI
import SharedLogic

/// The home page, like Summary in Fitness: this week against the goal, its totals, recent sessions and trends,
/// all read from Apple Health.
struct SummaryView: View {
    let state: TreadmillScreenState
    let history: HistoryUiState
    let actions: TreadmillActions
    let historyActions: HistoryActions

    @State private var showingSettings = false

    private enum Route: Hashable { case sessions }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    Text(Date.now, format: .dateTime.weekday(.wide).day().month(.wide))
                        .textCase(.uppercase)
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .padding(.bottom, -16)
                    healthNotice
                    if history.loadFailed {
                        LoadFailedRow(onRetry: historyActions.refresh)
                    }
                    if history.healthConnected {
                        if history.walks.isEmpty {
                            if !history.loadFailed { emptyState }
                        } else {
                            WeekCard(week: history.week, goalMeters: Int(history.weeklyGoalMeters))
                            TotalsGrid(week: history.week)
                            sessions
                            if !history.trends.isEmpty {
                                TrendsCard(trends: history.trends)
                            }
                        }
                    }
                }
                .padding()
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
            }
            .background(Color(.systemGroupedBackground))
            .refreshable { historyActions.refresh() }
            .navigationTitle("Summary")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Settings", systemImage: "person.crop.circle") { showingSettings = true }
                }
            }
            .navigationDestination(for: Route.self) { _ in
                SessionsView(history: history, actions: historyActions)
            }
            .navigationDestination(for: PastWalk.self) { walk in
                WalkDetailView(walk: walk, actions: historyActions)
            }
            .sheet(isPresented: $showingSettings) {
                SettingsView(state: state, history: history, actions: actions, historyActions: historyActions)
            }
        }
    }

    private var health: HealthUi { state.workout.health }

    @ViewBuilder private var healthNotice: some View {
        if health.available && !history.healthConnected && !health.accessDenied {
            HealthConnectCard(onConnect: actions.connectHealth, onNotNow: actions.dismissHealthCard)
        }
    }

    @ViewBuilder private var emptyState: some View {
        if history.isLoading {
            ProgressView()
                .frame(maxWidth: .infinity, minHeight: 240)
        } else {
            ContentUnavailableView {
                Label("No Walks Yet", systemImage: "figure.walk")
            } description: {
                Text("Your walks appear here once they are in Apple Health. If walks are missing, check that hashiru may read workouts in Settings › Health › Data Access & Devices.")
            }
        }
    }

    private var sessions: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text("Sessions")
                    .font(.title2.bold())
                Spacer()
                NavigationLink("Show More", value: Route.sessions)
                    .font(.subheadline)
            }
            VStack(spacing: 0) {
                ForEach(Array(history.walks.prefix(3).enumerated()), id: \.element.id) { index, walk in
                    if index > 0 { Divider().padding(.leading, 76) }
                    NavigationLink(value: walk) {
                        SessionRow(walk: walk)
                            .padding(.horizontal)
                            .padding(.vertical, 12)
                    }
                    .buttonStyle(.plain)
                }
            }
            .background(Color.card, in: .rect(cornerRadius: 20))
        }
    }
}

/// Distance this week as a ring against the goal, with a bar for each day.
private struct WeekCard: View {
    let week: WeekSummary
    let goalMeters: Int

    private var progress: Double { goalMeters > 0 ? Double(week.distanceMeters) / Double(goalMeters) : 0 }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("This Week")
                .font(.headline)
            HStack(spacing: 20) {
                DistanceRing(progress: progress)
                    .frame(width: 96, height: 96)
                VStack(alignment: .leading, spacing: 8) {
                    Text("Distance")
                        .font(.subheadline)
                    Text("\(kilometres(week.distanceMeters, digits: 1))/\(kilometres(Int32(goalMeters), digits: 0)) KM")
                        .font(.system(.title, design: .rounded, weight: .semibold))
                        .monospacedDigit()
                        .foregroundStyle(Color.workoutVolt)
                    DayBars(days: week.days)
                        .frame(height: 56)
                }
            }
        }
        .padding()
        .background(Color.card, in: .rect(cornerRadius: 20))
        .accessibilityElement(children: .combine)
    }
}

/// An Activity-style ring that laps itself past the goal.
private struct DistanceRing: View {
    let progress: Double

    var body: some View {
        ZStack {
            Circle()
                .stroke(Color.workoutVolt.opacity(0.2), lineWidth: 16)
            Circle()
                .trim(from: 0, to: min(progress, 1))
                .stroke(Color.workoutVolt, style: StrokeStyle(lineWidth: 16, lineCap: .round))
                .rotationEffect(.degrees(-90))
            if progress > 1 {
                Circle()
                    .trim(from: 0, to: min(progress - 1, 1))
                    .stroke(Color.workoutVolt, style: StrokeStyle(lineWidth: 16, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .shadow(color: .black.opacity(0.4), radius: 2)
            }
            Image(systemName: "figure.walk")
                .font(.title2.weight(.bold))
                .foregroundStyle(Color.workoutVolt)
        }
        .padding(8)
        .animation(.snappy, value: progress)
        .accessibilityLabel("Weekly goal")
        .accessibilityValue(Text(progress, format: .percent.precision(.fractionLength(0))))
    }
}

private struct DayBars: View {
    let days: [DayTotal]

    var body: some View {
        Chart(days, id: \.startMs) { day in
            BarMark(
                x: .value("Day", Date(epochMs: day.startMs), unit: .day),
                y: .value("Distance", Double(day.distanceMeters) / 1000)
            )
            .foregroundStyle(Color.workoutVolt)
            .clipShape(.capsule)
        }
        .chartXAxis {
            AxisMarks(values: .stride(by: .day)) { _ in
                AxisValueLabel(format: .dateTime.weekday(.narrow), centered: true)
            }
        }
        .chartYAxis(.hidden)
    }
}

/// This week's other totals, two to a row like the cards in Fitness.
private struct TotalsGrid: View {
    let week: WeekSummary

    var body: some View {
        Grid(horizontalSpacing: 12, verticalSpacing: 12) {
            GridRow {
                TotalTile(title: "Walking Time", value: walkDuration(Int(week.durationSeconds)), color: .workoutVolt)
                TotalTile(title: "Steps", value: Int(week.steps).formatted(), color: .workoutSteps)
            }
            GridRow {
                TotalTile(title: "Walks", value: Int(week.walks).formatted(), color: .workoutDistance)
                TotalTile(title: "Avg. Pace", value: "\(pace(averagePace))/KM", color: .workoutDistance)
            }
        }
    }

    private var averagePace: Int32? {
        week.distanceMeters < 50 ? nil : Int32(Int64(week.durationSeconds) * 1000 / Int64(week.distanceMeters))
    }
}

private struct TotalTile: View {
    let title: LocalizedStringKey
    let value: String
    let color: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.subheadline)
            Text(value)
                .font(.system(.title2, design: .rounded, weight: .semibold))
                .monospacedDigit()
                .foregroundStyle(color)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(Color.card, in: .rect(cornerRadius: 20))
        .accessibilityElement(children: .combine)
    }
}

/// A walk as a row in Sessions: its kind, distance and day, like the session list in Fitness.
struct SessionRow: View {
    let walk: PastWalk

    var body: some View {
        HStack(spacing: 14) {
            WalkIcon(walk: walk, size: 48)
            VStack(alignment: .leading, spacing: 2) {
                Text(walk.title)
                    .font(.subheadline)
                Text("\(kilometres(walk.distanceMeters)) KM")
                    .font(.system(.title2, design: .rounded, weight: .semibold))
                    .monospacedDigit()
                    .foregroundStyle(Color.workoutVolt)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(sessionDay(walk.start))
                Text(walkDuration(Int(walk.durationSeconds)))
            }
            .font(.subheadline)
            .foregroundStyle(.secondary)
            Image(systemName: "chevron.right")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.tertiary)
        }
        .contentShape(.rect)
        .accessibilityElement(children: .combine)
    }
}

struct WalkIcon: View {
    let walk: PastWalk
    let size: CGFloat

    var body: some View {
        Image(systemName: walk.symbol)
            .font(.system(size: size * 0.5, weight: .semibold))
            .foregroundStyle(Color.workoutVolt)
            .frame(width: size, height: size)
            .background(Color.workoutVolt.opacity(0.18), in: .circle)
            .accessibilityHidden(true)
    }
}

/// The last week against the longer run, with an arrow coloured when it is the better way.
private struct TrendsCard: View {
    let trends: [WalkTrend]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Trends")
                .font(.title2.bold())
            VStack(alignment: .leading, spacing: 16) {
                ForEach(trends, id: \.metric) { trend in
                    TrendRow(trend: trend)
                }
                Text("Your last 7 days compared with the last 90.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
            .background(Color.card, in: .rect(cornerRadius: 20))
        }
    }
}

private struct TrendRow: View {
    let trend: WalkTrend

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: symbol)
                .font(.headline.weight(.bold))
                .foregroundStyle(improving ? Color.black : Color.secondary)
                .frame(width: 36, height: 36)
                .background(improving ? AnyShapeStyle(Color.workoutVoltFill) : AnyShapeStyle(.fill.tertiary), in: .circle)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline)
                Text(value(trend.recent))
                    .font(.system(.title3, design: .rounded, weight: .semibold))
                    .monospacedDigit()
                    .foregroundStyle(improving ? Color.workoutVolt : .primary)
            }
            Spacer()
            Text("avg. \(value(trend.baseline))")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
    }

    /// For pace, lower is better.
    private var improving: Bool {
        trend.metric == .pace ? trend.direction == .down : trend.direction == .up
    }

    private var symbol: String {
        switch trend.direction {
        case .up: "arrow.up"
        case .down: "arrow.down"
        default: "equal"
        }
    }

    private var title: LocalizedStringKey {
        switch trend.metric {
        case .dailydistance: "Distance per Day"
        case .dailyduration: "Walking Time per Day"
        default: "Pace"
        }
    }

    private func value(_ value: Int32) -> String {
        switch trend.metric {
        case .dailydistance: "\(kilometres(value)) KM"
        case .dailyduration: walkDuration(Int(value))
        default: "\(pace(value))/KM"
        }
    }
}

// MARK: - Formatting

extension Color {
    /// Cards on the grouped background: white in light mode, raised grey in dark.
    static let card = Color(.secondarySystemGroupedBackground)
}

extension Date {
    init(epochMs: Int64) {
        self.init(timeIntervalSince1970: Double(epochMs) / 1000)
    }
}

extension PastWalk {
    var start: Date { Date(epochMs: startMs) }
    var end: Date { Date(epochMs: endMs) }
    var kind: WorkoutKind { WorkoutKind(activity: activity, location: indoor ? .indoor : .outdoor) }
    var title: LocalizedStringKey { kind.title }
    var symbol: String { kind.symbol }
}

func kilometres(_ meters: Int32, digits: Int) -> String {
    (Double(meters) / 1000).formatted(.number.precision(.fractionLength(digits)))
}

/// A duration in words, such as 33 min or 1 hr 42 min.
func walkDuration(_ seconds: Int) -> String {
    Duration.seconds(seconds).formatted(.units(allowed: [.hours, .minutes], width: .abbreviated))
}

/// Today, Yesterday, the weekday within the last week, or the date, as Fitness labels its sessions.
func sessionDay(_ date: Date) -> String {
    let calendar = Calendar.current
    if calendar.isDateInToday(date) { return String(localized: "Today") }
    if calendar.isDateInYesterday(date) { return String(localized: "Yesterday") }
    if let days = calendar.dateComponents([.day], from: date, to: .now).day, days < 7 {
        return date.formatted(.dateTime.weekday(.wide))
    }
    return date.formatted(.dateTime.day().month(.abbreviated).year(.twoDigits))
}

#Preview("Summary") {
    SummaryView(state: previewState(), history: previewHistory(), actions: TreadmillActions(), historyActions: HistoryActions())
}
#Preview("Empty") {
    SummaryView(state: previewState(), history: previewHistory(walks: []), actions: TreadmillActions(), historyActions: HistoryActions())
}
#Preview("Not Connected to Health") {
    SummaryView(
        state: previewState(connected: false),
        history: previewHistory(connected: false),
        actions: TreadmillActions(),
        historyActions: HistoryActions()
    )
}
