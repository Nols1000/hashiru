import SwiftUI
import SharedLogic

/// Like the Workout tab in Fitness: a tile per kind of workout, each started with its play button. An indoor
/// walk runs on the WalkingPad when one is attached, and on the iPhone's own sensors otherwise; outdoor workouts
/// always run on the iPhone (or the watch), with GPS for the route.
struct WorkoutTabView: View {
    let state: TreadmillScreenState
    let history: HistoryUiState
    let actions: TreadmillActions
    var courses: [Course] = []
    var routeActions = RouteActions()
    /// Opens the workout ready to start, following the route where one was picked.
    var onOpen: (WorkoutKind, Course?) -> Void = { _, _ in }

    @State private var showingDevices = false
    @State private var showingRoutes = false
    /// The route picked on each outdoor tile, by tile.
    @State private var routes: [String: String] = [:]
    /// A workout picked in the route library, opened once the library is gone.
    @State private var startAfterRoutes: PreparedWorkout?
    @Environment(\.openURL) private var openURL

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    ForEach(WorkoutTile.all) { tile in
                        let course = routes[tile.id].flatMap { id in courses.first { $0.id == id } }
                        WorkoutTileView(
                            tile: tile, state: state, actions: actions, onPlay: { onOpen(tile.kind, course) },
                            onShowDevices: { showingDevices = true },
                            courses: courses, course: course, onPickCourse: { routes[tile.id] = $0?.id },
                            onShowRoutes: { showingRoutes = true }
                        )
                    }
                    if state.workout.tooShort {
                        NoticeRow(
                            title: "Workout Not Saved",
                            message: "Workouts shorter than \(state.workout.minSeconds) seconds or \(state.workout.minMeters) m aren’t saved.",
                            systemImage: "clock",
                            tint: .secondary,
                            onDismiss: actions.dismissTooShort
                        )
                    }
                    if state.workout.showWatchTip {
                        NoticeRow(
                            title: "Heart Rate from Your Watch",
                            message: "Install hashiru on Apple Watch from the Watch app on your iPhone.",
                            systemImage: "applewatch",
                            onDismiss: actions.dismissWatchTip
                        )
                    }
                    if let last = history.walks.first {
                        LastWalkLine(walk: last)
                            .padding(.horizontal, 4)
                    }
                }
                .padding()
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Workout")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Routes", systemImage: "point.topleft.down.to.point.bottomright.curvepath") { showingRoutes = true }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    ConnectedDevicesButton(state: state) { showingDevices = true }
                }
            }
            .sheet(isPresented: $showingDevices) {
                ConnectedDevicesSheet(state: state, actions: actions)
            }
            .sheet(isPresented: $showingRoutes, onDismiss: {
                if let start = startAfterRoutes { onOpen(start.kind, start.course) }
                startAfterRoutes = nil
            }) {
                RoutesView(courses: courses, actions: routeActions) { kind, course in
                    startAfterRoutes = PreparedWorkout(kind: kind, course: course)
                    showingRoutes = false
                }
            }
            .alert(rejectionTitle, isPresented: rejectionPresented) {
                if state.workout.startRejected == .motionaccessdenied || state.workout.startRejected == .locationaccessdenied {
                    Button("Open Settings") {
                        if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                    }
                }
                Button("OK", role: .cancel) {}
            } message: {
                Text(rejectionMessage)
            }
            .safeAreaInset(edge: .top) {
                // Blocking errors show in the Indoor Walk tile while disconnected; everything else is a banner here.
                if let error = state.treadmill.errorPresentation, !state.workout.isInProgress, !error.blocking || state.treadmill.isConnected {
                    ErrorBanner(message: error.title, onDismiss: actions.dismissError)
                        .padding(.horizontal)
                        .transition(.move(edge: .top).combined(with: .opacity))
                }
            }
            .animation(.snappy, value: state.treadmill.errorPresentation)
            .animation(.snappy, value: state.treadmill.isConnected)
        }
    }

    private var rejectionPresented: Binding<Bool> {
        Binding(get: { state.workout.startRejected != nil }, set: { presented in if !presented { actions.dismissStartRejected() } })
    }

    private var rejectionTitle: LocalizedStringKey {
        switch state.workout.startRejected {
        case .motionaccessdenied: "Motion & Fitness Is Off"
        case .locationaccessdenied: "Location Is Off"
        default: "Workout Not Available"
        }
    }

    private var rejectionMessage: LocalizedStringKey {
        switch state.workout.startRejected {
        case .motionaccessdenied:
            "Without a WalkingPad, hashiru counts your steps and distance with your iPhone. Turn on Motion & Fitness for hashiru in Settings, or attach a WalkingPad."
        case .locationaccessdenied:
            "Outdoor workouts use your location to map your route and measure your distance. Allow location access for hashiru in Settings."
        default:
            "This iPhone can’t record this workout. Attach a WalkingPad to walk indoors."
        }
    }
}

/// A workout offered on the tab. Unavailable ones show as coming soon.
struct WorkoutTile: Identifiable {
    let kind: WorkoutKind
    let available: Bool

    var id: String { "\(kind.activity.name)-\(kind.location.name)" }

    static let all = [
        WorkoutTile(kind: .indoorWalk, available: true),
        WorkoutTile(kind: .outdoorWalk, available: true),
        WorkoutTile(kind: .outdoorRun, available: true),
    ]
}

extension WorkoutKind {
    static let indoorWalk = WorkoutKind(activity: .walking, location: .indoor)
    static let outdoorWalk = WorkoutKind(activity: .walking, location: .outdoor)
    static let outdoorRun = WorkoutKind(activity: .running, location: .outdoor)

    var title: LocalizedStringKey {
        switch (activity, location) {
        case (.running, .indoor): "Indoor Run"
        case (.running, _): "Outdoor Run"
        case (_, .indoor): "Indoor Walk"
        default: "Outdoor Walk"
        }
    }

    var symbol: String { activity == .running ? "figure.run" : "figure.walk" }

    /// Only indoor workouts can use the WalkingPad.
    var takesPad: Bool { location == .indoor }
}

/// A tile like those in Fitness: the glyph and play button, the title, and chips for what goes with it.
private struct WorkoutTileView: View {
    let tile: WorkoutTile
    let state: TreadmillScreenState
    let actions: TreadmillActions
    let onPlay: () -> Void
    let onShowDevices: () -> Void
    var courses: [Course] = []
    /// The route to follow outdoors; nil to go anywhere.
    var course: Course?
    var onPickCourse: (Course?) -> Void = { _ in }
    var onShowRoutes: () -> Void = {}

    @Environment(\.openURL) private var openURL

    private var kind: WorkoutKind { tile.kind }
    private var usesPad: Bool { kind.takesPad && state.treadmill.isConnected }

    /// A blocking error while disconnected shows in the tile, with its fix.
    private var blockingError: ErrorPresentation? {
        guard kind.takesPad, let error = state.treadmill.errorPresentation, error.blocking, !state.treadmill.isConnected, !state.treadmill.isConnecting else { return nil }
        return error
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top) {
                Image(systemName: kind.symbol)
                    .font(.system(size: 40, weight: .semibold))
                    .foregroundStyle(tile.available ? Color.workoutVolt : .secondary)
                    .frame(height: 48)
                    .accessibilityHidden(true)
                Spacer()
                PlayButton(enabled: tile.available && !state.workout.isInProgress, action: onPlay)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(kind.title)
                    .font(.title2.bold())
                if !tile.available {
                    Text("Coming Soon")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                } else if usesPad {
                    Text("On your WalkingPad")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                } else if let course {
                    Text(course.summary)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            if let blockingError {
                BlockingErrorRow(error: blockingError, state: state, actions: actions)
            }
            if tile.available {
                HStack(spacing: 10) {
                    if kind.takesPad {
                        TileChip(title: padChipTitle, systemImage: "figure.walk.treadmill", highlighted: state.treadmill.isConnected, action: onShowDevices)
                    }
                    if kind.outdoor {
                        routeChip
                    }
                    TileChip(
                        title: "Apple Watch", systemImage: "applewatch",
                        highlighted: state.workout.companion == .reachable, action: onShowDevices
                    )
                }
            }
        }
        .padding(18)
        .background {
            RoundedRectangle(cornerRadius: 28)
                .fill(Color.card)
                .overlay(RoundedRectangle(cornerRadius: 28).fill(Color.workoutVolt.opacity(tile.available ? 0.08 : 0.03)))
        }
        .accessibilityElement(children: .contain)
    }

    /// Picks the route to follow, like the route button on a Fitness workout; without routes, it opens the library.
    @ViewBuilder private var routeChip: some View {
        if courses.isEmpty {
            TileChip(title: "Route", systemImage: "point.topleft.down.to.point.bottomright.curvepath", highlighted: false, action: onShowRoutes)
        } else {
            Menu {
                Picker("Route", selection: Binding(get: { course?.id }, set: { id in onPickCourse(courses.first { $0.id == id }) })) {
                    Text("No Route").tag(String?.none)
                    ForEach(courses) { course in
                        Text(course.name).tag(Optional(course.id))
                    }
                }
                Divider()
                Button("All Routes…", systemImage: "list.bullet", action: onShowRoutes)
            } label: {
                TileChipLabel(
                    title: course.map { Text(verbatim: $0.name) } ?? Text("Route"),
                    systemImage: "point.topleft.down.to.point.bottomright.curvepath", highlighted: course != nil
                )
            }
            .buttonStyle(.plain)
        }
    }

    private var padChipTitle: LocalizedStringKey {
        if state.treadmill.isConnected { return "WalkingPad" }
        if state.treadmill.isConnecting { return "Connecting…" }
        return "Attach WalkingPad"
    }

}

/// Opens the workout, ready to start.
private struct PlayButton: View {
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "play.fill")
                .font(.system(size: 22, weight: .bold))
                .foregroundStyle(.black)
                .frame(width: 48, height: 48)
                .background(enabled ? Color.workoutVoltFill : Color.secondary.opacity(0.3), in: .circle)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityLabel("Open")
    }
}

/// A chip under a tile's title, like the goal and alert buttons in Fitness.
private struct TileChip: View {
    let title: LocalizedStringKey
    let systemImage: String
    let highlighted: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            TileChipLabel(title: Text(title), systemImage: systemImage, highlighted: highlighted)
        }
        .buttonStyle(.plain)
    }
}

/// What a chip shows, for buttons and menus alike.
private struct TileChipLabel: View {
    let title: Text
    let systemImage: String
    let highlighted: Bool

    var body: some View {
        Label {
            title
        } icon: {
            Image(systemName: systemImage)
                .foregroundStyle(highlighted ? Color.workoutVolt : .secondary)
        }
            .font(.subheadline.weight(.semibold))
            .lineLimit(1)
            .minimumScaleFactor(0.8)
            .foregroundStyle(.primary)
            .frame(maxWidth: .infinity, minHeight: 44)
            .background(.fill.tertiary, in: .rect(cornerRadius: 14))
    }
}

/// A blocking WalkingPad error: its icon, title, text and the button that fixes it.
private struct BlockingErrorRow: View {
    let error: ErrorPresentation
    let state: TreadmillScreenState
    let actions: TreadmillActions

    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label { Text(error.title).font(.headline) } icon: { Image(systemName: error.systemImage).foregroundStyle(.red) }
            if let body = error.body {
                Text(body)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let code = error.code {
                Text("Code \(String(code))")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }
            if error.fix != ErrorPresentation.Fix.none {
                Button(action: fix) {
                    Text(fixTitle)
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 36)
                }
                .secondaryButtonStyle()
            }
        }
        .padding(14)
        .background(.red.opacity(0.12), in: .rect(cornerRadius: 16))
    }

    private var fixTitle: LocalizedStringKey {
        switch error.fix {
        case .pairAgain: "Pair Again"
        case .openSettings: "Open Settings"
        default: "Try Again"
        }
    }

    private func fix() {
        switch error.fix {
        case .pairAgain: actions.pair()
        case .openSettings:
            actions.dismissError()
            if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
        default: state.treadmill.canPair && !state.treadmill.isPaired ? actions.pair() : actions.connect()
        }
    }
}

/// The most recent workout from any source, for a sense of where the user left off.
private struct LastWalkLine: View {
    let walk: PastWalk

    var body: some View {
        Text("Last workout: \(kilometres(walk.distanceMeters)) km · \(walkDuration(Int(walk.durationSeconds))) · \(sessionDay(walk.start))")
            .font(.subheadline)
            .foregroundStyle(.secondary)
    }
}

private struct ErrorBanner: View {
    let message: LocalizedStringResource
    let onDismiss: () -> Void

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Label { Text(message) } icon: { Image(systemName: "exclamationmark.triangle.fill") }
                .symbolRenderingMode(.multicolor)
                .font(.subheadline)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button("Dismiss", systemImage: "xmark", action: onDismiss)
                .labelStyle(.iconOnly)
                .foregroundStyle(.secondary)
        }
        .padding()
        .bannerBackground()
    }
}

#Preview("Pad Attached") {
    WorkoutTabView(state: previewState(), history: previewHistory(), actions: TreadmillActions())
}
#Preview("No Pad") {
    WorkoutTabView(state: previewState(connected: false, paired: false), history: previewHistory(walks: []), actions: TreadmillActions())
}
#Preview("Connecting") {
    WorkoutTabView(state: previewState(connected: false, connecting: true), history: previewHistory(), actions: TreadmillActions())
}
#Preview("Bluetooth Off") {
    WorkoutTabView(
        state: previewState(connected: false, error: TreadmillErrorBluetoothOff.shared), history: previewHistory(), actions: TreadmillActions()
    )
}
#Preview("Pad Not Found") {
    WorkoutTabView(
        state: previewState(connected: false, error: TreadmillErrorPadNotFound(code: KotlinInt(value: 2))),
        history: previewHistory(), actions: TreadmillActions()
    )
}
#Preview("Command Failed") {
    WorkoutTabView(
        state: previewState(error: TreadmillErrorCommandFailed(command: TreadmillCommandStartBelt.shared)),
        history: previewHistory(), actions: TreadmillActions()
    )
}
#Preview("Motion Denied") {
    WorkoutTabView(
        state: previewState(connected: false, paired: false, workout: previewWorkout(startRejected: .motionaccessdenied)),
        history: previewHistory(), actions: TreadmillActions()
    )
}
#Preview("Too Short") {
    WorkoutTabView(state: previewState(workout: previewWorkout(tooShort: true)), history: previewHistory(), actions: TreadmillActions())
}
