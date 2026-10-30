import SwiftUI
import SharedLogic

@MainActor
final class TreadmillModel: ObservableObject {
    @Published private(set) var state = TreadmillViewModelKt.initialTreadmillUiState()

    private let viewModel: TreadmillViewModel
    private var subscription: (any KotlinAutoCloseable)?

    /// The session and recorder outlive this model, so the connection and the walk go on when the view goes away.
    init(app: IosApp) {
        viewModel = TreadmillViewModel(session: app.session, workouts: app.workouts, relay: app.relay)
        subscription = viewModel.watch { [weak self] state in
            self?.state = state
        }
    }

    deinit {
        subscription?.close()
    }

    func connect() { viewModel.connect() }
    func pair() { viewModel.pair() }
    func forget() { viewModel.forget() }
    func disconnect() { viewModel.disconnect() }
    func toggleBelt() { viewModel.toggleBelt() }
    func stopBelt() { viewModel.stopBelt() }
    func increaseSpeed() { viewModel.increaseSpeed() }
    func decreaseSpeed() { viewModel.decreaseSpeed() }
    func dismissError() { viewModel.dismissError() }
    func startWorkout(_ kind: WorkoutKind) { viewModel.startWorkout(kind: kind) }
    func dismissStartRejected() { viewModel.dismissStartRejected() }
    func pauseWorkout() { viewModel.pauseWorkout() }
    func resumeWorkout() { viewModel.resumeWorkout() }
    func endWorkout() { viewModel.endWorkout() }
    func dismissSummary() { viewModel.dismissSummary() }
    func deleteSummary() { viewModel.deleteSummary() }
    func connectHealth() { viewModel.connectHealth() }
    func dismissHealthCard() { viewModel.dismissHealthCard() }
    func dismissWatchTip() { viewModel.dismissWatchTip() }
    func dismissTooShort() { viewModel.dismissTooShort() }
    func retrySync() { viewModel.retrySync() }
    func setWeight(_ kg: Double?) { viewModel.setWeight(kg: kg.map { KotlinDouble(value: $0) }) }
    func setProfile(_ birthYear: Int?, _ sex: Sex?) {
        viewModel.setProfile(birthYear: birthYear.map { KotlinInt(value: Int32($0)) }, sex: sex)
    }
    func onForeground() { viewModel.onForeground() }
}

@MainActor
final class HistoryModel: ObservableObject {
    @Published private(set) var state = HistoryViewModelKt.initialHistoryUiState()

    private let viewModel: HistoryViewModel
    private var subscription: (any KotlinAutoCloseable)?

    init(app: IosApp) {
        viewModel = app.historyViewModel()
        subscription = viewModel.watch { [weak self] state in
            self?.state = state
        }
    }

    deinit {
        subscription?.close()
    }

    var actions: HistoryActions {
        HistoryActions(
            refresh: viewModel.refresh,
            loadMore: viewModel.loadMore,
            delete: { [viewModel] walk in viewModel.delete(walk: walk) },
            heartRate: { [viewModel] walk, onResult in viewModel.heartRate(walk: walk, onResult: onResult) },
            setWeeklyGoal: { [viewModel] meters in viewModel.setWeeklyGoal(meters: Int32(meters)) }
        )
    }

    func onForeground() { viewModel.onForeground() }
}

/// What the history pages can ask for. The defaults do nothing, which suits previews.
struct HistoryActions {
    var refresh: () -> Void = {}
    var loadMore: () -> Void = {}
    var delete: (PastWalk) -> Void = { _ in }
    var heartRate: (PastWalk, @escaping (HeartRateUi) -> Void) -> Void = { _, _ in }
    var setWeeklyGoal: (Int) -> Void = { _ in }
}

/// Everything the screens can ask for. The defaults do nothing, which suits previews.
struct TreadmillActions {
    var connect: () -> Void = {}
    var pair: () -> Void = {}
    var forget: () -> Void = {}
    var disconnect: () -> Void = {}
    var toggleBelt: () -> Void = {}
    var stopBelt: () -> Void = {}
    var increaseSpeed: () -> Void = {}
    var decreaseSpeed: () -> Void = {}
    var dismissError: () -> Void = {}
    var startWorkout: (WorkoutKind) -> Void = { _ in }
    var dismissStartRejected: () -> Void = {}
    var pauseWorkout: () -> Void = {}
    var resumeWorkout: () -> Void = {}
    var endWorkout: () -> Void = {}
    var dismissSummary: () -> Void = {}
    var deleteSummary: () -> Void = {}
    var connectHealth: () -> Void = {}
    var dismissHealthCard: () -> Void = {}
    var dismissWatchTip: () -> Void = {}
    var dismissTooShort: () -> Void = {}
    var retrySync: () -> Void = {}
    var setWeight: (Double?) -> Void = { _ in }
    var setProfile: (Int?, Sex?) -> Void = { _, _ in }
}

struct ContentView: View {
    @StateObject private var model: TreadmillModel
    @StateObject private var history: HistoryModel
    @Environment(\.scenePhase) private var scenePhase

    init(app: IosApp) {
        _model = StateObject(wrappedValue: TreadmillModel(app: app))
        _history = StateObject(wrappedValue: HistoryModel(app: app))
    }

    var body: some View {
        RootView(state: model.state, history: history.state, actions: actions, historyActions: history.actions)
            .onChange(of: scenePhase) { _, phase in
                // Walks that finished while the phone was locked could not be saved yet, and other apps may
                // have added walks meanwhile.
                if phase == .active {
                    model.onForeground()
                    history.onForeground()
                }
            }
    }

    private var actions: TreadmillActions {
        TreadmillActions(
            connect: model.connect, pair: model.pair, forget: model.forget, disconnect: model.disconnect,
            toggleBelt: model.toggleBelt, stopBelt: model.stopBelt, increaseSpeed: model.increaseSpeed,
            decreaseSpeed: model.decreaseSpeed, dismissError: model.dismissError, startWorkout: model.startWorkout,
            dismissStartRejected: model.dismissStartRejected, pauseWorkout: model.pauseWorkout,
            resumeWorkout: model.resumeWorkout, endWorkout: model.endWorkout, dismissSummary: model.dismissSummary,
            deleteSummary: model.deleteSummary, connectHealth: model.connectHealth, dismissHealthCard: model.dismissHealthCard,
            dismissWatchTip: model.dismissWatchTip, dismissTooShort: model.dismissTooShort, retrySync: model.retrySync,
            setWeight: model.setWeight, setProfile: model.setProfile
        )
    }
}

/// Summary and Workout tabs, like the Fitness app. A workout in progress covers both, whichever started it, and a
/// finished workout's summary comes up as a sheet.
struct RootView: View {
    let state: TreadmillUiState
    let history: HistoryUiState
    let actions: TreadmillActions
    let historyActions: HistoryActions

    private enum Page { case summary, workout }
    @State private var page = Page.summary
    @State private var editingWeight = false
    /// A workout opened from its tile, ready to start; it records once its play button is pressed.
    @State private var preparing: WorkoutKind?

    var body: some View {
        TabView(selection: $page) {
            Tab("Summary", systemImage: "figure.walk.circle", value: Page.summary) {
                SummaryView(state: state, history: history, actions: actions, historyActions: historyActions)
            }
            Tab("Workout", systemImage: "figure.walk", value: Page.workout) {
                WorkoutTabView(state: state, history: history, actions: actions, onOpen: { preparing = $0 })
            }
        }
        // Driven by the workout: it is dismissed by ending the workout or leaving the ready screen, not by a gesture.
        .fullScreenCover(isPresented: Binding(get: { state.workout.isInProgress || preparing != nil }, set: { _ in })) {
            LiveWorkoutView(state: state, actions: actions, kind: preparing ?? state.workout.kind, onCancel: { preparing = nil })
        }
        .onChange(of: state.workout.isInProgress) { _, inProgress in
            if !inProgress { preparing = nil }
        }
        .onChange(of: state.workout.startRejected) { _, rejected in
            // The Workout tab explains why.
            if rejected != nil { preparing = nil }
        }
        .sheet(isPresented: summaryPresented) {
            if let summary = state.workout.summary {
                WorkoutSummaryView(
                    summary: summary, health: state.workout.health, actions: actions,
                    onEditWeight: { editingWeight = true }
                )
            }
        }
        .weightAlert(isPresented: $editingWeight, initialKg: state.workout.manualWeightKg?.doubleValue, onSave: actions.setWeight)
        .onChange(of: state.workout.phase == WorkoutPhase.active, initial: true) { _, walking in
            // Keep the display awake while walking, like a workout app.
            UIApplication.shared.isIdleTimerDisabled = walking
        }
        .onChange(of: state.errorPresentation) { _, error in
            if let error { AccessibilityNotification.Announcement(String(localized: error.title)).post() }
        }
    }

    /// Shown while a summary exists and no walk covers the screen; swiping it away dismisses the summary.
    private var summaryPresented: Binding<Bool> {
        Binding(
            get: { state.workout.summary != nil && !state.workout.isInProgress },
            set: { presented in if !presented { actions.dismissSummary() } }
        )
    }
}

/// Offers Apple Health where the walks are kept, until the user answers.
struct HealthConnectCard: View {
    let onConnect: () -> Void
    let onNotNow: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Save Walks to Apple Health", systemImage: "heart.fill")
                .font(.headline)
                .symbolRenderingMode(.multicolor)
            Text("Add your treadmill walks with distance, steps and calories to your Activity rings, and see your sessions and trends here.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            HStack {
                Spacer()
                Button("Not Now", action: onNotNow)
                    .secondaryButtonStyle()
                Button("Connect", action: onConnect)
                    .prominentButtonStyle()
            }
        }
        .padding()
        .background(Color.card, in: .rect(cornerRadius: 20))
    }
}

extension View {
    /// Liquid Glass on iOS 26+, the pre-26 bordered equivalents otherwise.
    @ViewBuilder func prominentButtonStyle() -> some View {
        if #available(iOS 26, *) {
            buttonStyle(.glassProminent)
        } else {
            buttonStyle(.borderedProminent)
        }
    }

    @ViewBuilder func secondaryButtonStyle() -> some View {
        if #available(iOS 26, *) {
            buttonStyle(.glass)
        } else {
            buttonStyle(.bordered)
        }
    }

    @ViewBuilder func bannerBackground() -> some View {
        if #available(iOS 26, *) {
            glassEffect(.regular, in: .rect(cornerRadius: 20))
        } else {
            background(.regularMaterial, in: .rect(cornerRadius: 20))
        }
    }
}

// MARK: - Previews

func previewHealth(accessDenied: Bool = false, showPriorityTip: Bool = false) -> HealthUi {
    HealthUi(available: true, showConnectCard: false, accessDenied: accessDenied, showPriorityTip: showPriorityTip, showInstallCard: false)
}

func previewWorkout(
    phase: WorkoutPhase = .idle, usesPad: Bool = true, pauseReason: PauseReason? = nil, pausedSeconds: Int32 = 12,
    summary: WorkoutSummaryUi? = nil, health: HealthUi = previewHealth(), showWatchTip: Bool = false, tooShort: Bool = false,
    starting: Bool = false, startRejected: RejectReason? = nil
) -> WorkoutUiState {
    let walking = phase != .idle
    return WorkoutUiState(
        phase: phase, kind: .indoorWalk, usesPad: usesPad, starting: starting, startRejected: startRejected, pauseReason: pauseReason, pausedSeconds: pauseReason == nil ? 0 : pausedSeconds,
        durationSeconds: walking ? 1254 : 0, distanceMeters: walking ? 1220 : 0, steps: walking ? 1742 : 0,
        activeKcal: walking ? 43 : 0, paceSecondsPerKm: walking ? KotlinInt(value: 1028) : nil, weightEstimated: true,
        manualWeightKg: nil, healthWeightKg: nil, heartRateBpm: walking ? KotlinInt(value: 118) : nil, heartRateZone: walking ? .aerobic : nil,
        companion: walking ? .reachable : .notpaired, showWatchTip: showWatchTip, manualBirthYear: nil, manualSex: nil, ageFromHealth: false,
        sexFromHealth: false, summary: summary, tooShort: tooShort, health: health
    )
}

func previewState(
    connected: Bool = true, connecting: Bool = false, running: Bool = false, paired: Bool = true, error: (any TreadmillError)? = nil,
    workout: WorkoutUiState = previewWorkout()
) -> TreadmillUiState {
    TreadmillUiState(
        isConnected: connected, speedKmh: running ? 3.5 : 0, isRunning: running, isConnecting: connecting,
        error: error, elapsedSeconds: running ? 1254 : 0, distanceMeters: running ? 1220 : 0, steps: running ? 1742 : 0,
        isPaired: paired, canPair: true, workout: workout
    )
}

let previewSummary = WorkoutSummaryUi(
    startEpochMs: 1_761_732_000_000, endEpochMs: 1_761_734_100_000, durationSeconds: 1980, distanceMeters: 1930,
    steps: 2710, activeKcal: 68, totalKcal: 108, paceSecondsPerKm: KotlinInt(value: 1026), weightEstimated: true,
    sync: .saved, syncMessage: nil, avgHeartRate: KotlinInt(value: 112), maxHeartRate: KotlinInt(value: 131),
    energyFromHeartRate: true
)

private let previewNow = Date.now.timeIntervalSince1970 * 1000
private let previewDay: Double = 86_400_000

let previewWalks: [PastWalk] = (0..<40).map { index in
    let start = Int64(previewNow - Double(index) * previewDay * 1.6 - 3_600_000)
    let seconds = Int32(1_500 + (index % 5) * 300)
    let meters = Int32(Double(seconds) * (index < 4 ? 1.12 : 1.0))
    let own = index % 4 != 2
    return PastWalk(
        id: "walk-\(index)", startMs: start, endMs: start + Int64(seconds) * 1000, durationSeconds: seconds,
        distanceMeters: meters, steps: KotlinInt(value: meters * 14 / 10), activeKcal: KotlinInt(value: meters / 28),
        indoor: own, sourceName: own ? "hashiru" : "Apple Watch", recordId: own ? "record-\(index)" : nil,
        activity: index % 7 == 3 ? .running : .walking
    )
}

let previewHeartRate: HeartRateUi = {
    let start = previewWalks[0].startMs
    let points = (0..<300).map { i in
        let bpm = Int32(95 + min(i, 60) / 2 + Int(10 * sin(Double(i) / 25)))
        return HeartRatePointUi(epochMs: start + Int64(i) * 5_000, bpm: bpm, heartRateZone: HeartRateZone.companion.of(bpm: bpm, ageYears: 40))
    }
    return HeartRateUi(points: points, avgBpm: KotlinInt(value: 118), maxBpm: KotlinInt(value: 131))
}()

func previewHistory(walks: [PastWalk] = previewWalks, connected: Bool = true, loadFailed: Bool = false) -> HistoryUiState {
    let calendar = Calendar.current
    let weekStart = calendar.dateInterval(of: .weekOfYear, for: .now)!.start
    let dayStarts = (0...7).map { day in
        KotlinLong(value: Int64(calendar.date(byAdding: .day, value: day, to: weekStart)!.timeIntervalSince1970 * 1000))
    }
    let shown = connected ? walks : []
    return HistoryUiState(
        healthConnected: connected, isLoading: false, loadFailed: loadFailed, walks: shown, hasMore: false, isLoadingMore: false,
        week: WalkTrends.shared.week(walks: shown, dayStartsMs: dayStarts), weeklyGoalMeters: 10_000,
        trends: WalkTrends.shared.trends(walks: shown, nowMs: Int64(previewNow))
    )
}

#Preview("Root") {
    RootView(state: previewState(), history: previewHistory(), actions: TreadmillActions(), historyActions: HistoryActions())
}
#Preview("Summary Sheet") {
    RootView(
        state: previewState(workout: previewWorkout(summary: previewSummary, health: previewHealth(showPriorityTip: true))),
        history: previewHistory(),
        actions: TreadmillActions(),
        historyActions: HistoryActions()
    )
}
#Preview("Live (mock)") { ContentView(app: IosTreadmillSessionKt.createIosApp(simulated: true)) }
