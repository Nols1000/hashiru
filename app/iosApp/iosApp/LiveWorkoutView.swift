import CoreLocation
import SwiftUI
import SharedLogic

extension Color {
    /// The workout accent: a volt yellow-green that stays legible on black from the treadmill, and a deep green
    /// on light backgrounds, where volt would all but vanish.
    static let workoutVolt = adaptive(light: 0x4A7300, dark: 0xD4FF3B)
    /// Volt as a fill behind black glyphs, such as the play button, in either appearance.
    static let workoutVoltFill = Color(red: 0.83, green: 1.0, blue: 0.23)
    static let workoutEnergy = adaptive(light: 0xC8003C, dark: 0xFA124F)
    static let workoutDistance = adaptive(light: 0x00739E, dark: 0x59D9FF)
    static let workoutSteps = adaptive(light: 0x2E7A1C, dark: 0x9EF273)
    static let workoutHeart = adaptive(light: 0xD70015, dark: 0xFF3B30)
    /// The running clock, yellow as in Workout; it is only shown on black.
    static let workoutClock = Color(red: 1.0, green: 0.9, blue: 0.0)

    /// Each value at least 4.5:1 against the backgrounds of its appearance.
    static func adaptive(light: UInt32, dark: UInt32) -> Color {
        Color(UIColor { traits in UIColor(rgb: traits.userInterfaceStyle == .dark ? dark : light) })
    }
}

private extension UIColor {
    convenience init(rgb: UInt32) {
        self.init(
            red: CGFloat((rgb >> 16) & 0xFF) / 255, green: CGFloat((rgb >> 8) & 0xFF) / 255, blue: CGFloat(rgb & 0xFF) / 255, alpha: 1
        )
    }
}

extension HeartRateZone {
    /// Cool to hot, matching the watch app.
    var color: Color {
        switch self {
        case .warmup: .adaptive(light: 0x2F63C8, dark: 0x8AB5F7)
        case .easy: .adaptive(light: 0x1A7F45, dark: 0x7AE0A1)
        case .aerobic: .workoutVolt
        case .threshold: .adaptive(light: 0xA55F00, dark: 0xFFB547)
        default: .adaptive(light: 0xC42B21, dark: 0xFF6B5E)
        }
    }

    var title: String {
        switch self {
        case .warmup: "WARM-UP"
        case .easy: "EASY"
        case .aerobic: "AEROBIC"
        case .threshold: "THRESHOLD"
        default: "MAXIMUM"
        }
    }
}

/// The workout, styled like a workout in the Fitness app on iPhone: the time and metrics on black, the state of
/// the WalkingPad or the walk top left, and a glass sheet at the bottom. It opens ready, before anything is
/// recorded, with play in the sheet; once the belt runs, the compact sheet holds the pad's speed instead. Pulled
/// up, it lists Pause (on the pad), End Workout, Connected Devices and Lock Controls. Outdoors, a swipe brings up
/// the map with the route so far.
struct LiveWorkoutView: View {
    let state: TreadmillUiState
    let actions: TreadmillActions
    /// The workout to start from the ready screen.
    let kind: WorkoutKind
    /// The route it follows from the ready screen; once recording, the workout's own.
    var course: Course?
    /// Leaves the ready screen without recording anything.
    var onCancel: () -> Void = {}

    /// Just tall enough for the round buttons and their margin, like the timer bar in Fitness.
    @ScaledMetric(relativeTo: .largeTitle) private var compactHeight: CGFloat = 88
    /// Three buttons of 64 with their spacing, and the bottom margin.
    @ScaledMetric(relativeTo: .headline) private var listHeight: CGFloat = 238
    /// Pause, listed under the speed on the pad, with its spacing.
    @ScaledMetric(relativeTo: .headline) private var pauseHeight: CGFloat = 74
    @State private var detent = PresentationDetent.height(88)
    @State private var locked = false
    /// Asks for location on the ready screen, so the prompt does not hold up the start.
    @State private var location = CLLocationManager()

    private var session: WorkoutSession { WorkoutSession(state: state, kind: kind, course: course) }
    private var expandedHeight: CGFloat { compactHeight + listHeight + (session.onPad && !session.held ? pauseHeight : 0) }

    var body: some View {
        pages
            .background(Color.black.ignoresSafeArea())
            .preferredColorScheme(.dark)
            .statusBarHidden()
            .safeAreaInset(edge: .top) {
                WorkoutStatusBar(state: state, actions: actions, session: session)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal)
            }
            .sensoryFeedback(.error, trigger: state.errorPresentation) { _, error in error != nil }
            .sensoryFeedback(.warning, trigger: state.workout.connectionLost) { _, lost in lost }
            // There as long as the cover it sits on, which goes when the workout ends, never by a swipe.
            .sheet(isPresented: .constant(true)) {
                WorkoutControlsSheet(state: state, actions: actions, session: session, onCancel: onCancel, locked: $locked)
                    .presentationDetents([.height(compactHeight), .height(expandedHeight)], selection: $detent)
                    .presentationDragIndicator(.visible)
                    .presentationBackgroundInteraction(.enabled(upThrough: .height(compactHeight)))
                    .presentationContentInteraction(.resizes)
                    .interactiveDismissDisabled()
                    .preferredColorScheme(.dark)
            }
            .onChange(of: compactHeight, initial: true) { _, height in
                if detent != .height(expandedHeight) { detent = .height(height) }
            }
            .task {
                if session.kind.outdoor && location.authorizationStatus == .notDetermined { location.requestWhenInUseAuthorization() }
                if session.course != nil { NavigationAlerts.shared.prepare() }
            }
            .onChange(of: state.workout.navigationAlerts) { _, _ in
                if let navigation = state.workout.navigation { NavigationAlerts.shared.announce(navigation) }
            }
    }

    @ViewBuilder private var pages: some View {
        // The metrics run on under the glass sheet and scroll clear of it.
        let metrics = MetricsPage(workout: state.workout, session: session)
            .contentMargins(.bottom, compactHeight + 40, for: .scrollContent)
            .ignoresSafeArea(edges: .bottom)
        if session.kind.outdoor {
            TabView {
                metrics
                LiveRouteMap(route: state.workout.route, course: session.course?.coordinates ?? [])
                    .overlay(alignment: .top) {
                        if let navigation = state.workout.navigation {
                            NavigationBanner(navigation: navigation)
                                .padding(.horizontal)
                                .padding(.top, 8)
                        }
                    }
                    .padding(.bottom, compactHeight)
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .ignoresSafeArea(edges: .bottom)
        } else {
            metrics
        }
    }
}

/// Where the screen stands: [ready] before the workout records, and whether it runs [onPad] or on the iPhone.
private struct WorkoutSession {
    let kind: WorkoutKind
    let ready: Bool
    let onPad: Bool
    /// Not recording time right now: ready, or paused.
    let held: Bool
    /// The route followed, if any.
    let course: Course?

    init(state: TreadmillUiState, kind: WorkoutKind, course: Course?) {
        let workout = state.workout
        ready = !workout.isInProgress
        self.kind = ready ? kind : workout.kind
        self.course = ready ? course : workout.course
        onPad = ready ? state.isConnected && kind.takesPad : workout.usesPad
        held = ready || workout.phase != .active
    }
}

/// Top left: the WalkingPad, or the walk without one, coloured by how it is doing, with the workout and a word on
/// its state beside it. The button's menu says what is wrong and offers what to do, in place of a banner.
private struct WorkoutStatusBar: View {
    let state: TreadmillUiState
    let actions: TreadmillActions
    let session: WorkoutSession

    private var workout: WorkoutUiState { state.workout }
    private var error: ErrorPresentation? { state.errorPresentation }
    private var failedStop: Bool { error?.failedStop == true }
    private var disconnected: Bool { workout.pauseReason == .disconnected }

    var body: some View {
        HStack(spacing: 12) {
            button
            VStack(alignment: .leading, spacing: 0) {
                Text(session.kind.title)
                    .font(.system(.headline, design: .rounded))
                Text(summary)
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(tint == .workoutVolt ? Color.secondary : tint)
                    .contentTransition(.opacity)
            }
            .lineLimit(1)
            .accessibilityHidden(true)
        }
        .animation(.snappy, value: summary)
    }

    private var button: some View {
        Menu {
            Section {
                if failedStop {
                    Button("Try Again", systemImage: "stop.fill", action: actions.stopBelt)
                    Button("Dismiss", systemImage: "xmark", action: actions.dismissError)
                } else if workout.connectionLost {
                    Button("End Walk", systemImage: "xmark", role: .destructive, action: actions.endWorkout)
                } else if error != nil {
                    Button("Dismiss", systemImage: "xmark", action: actions.dismissError)
                }
            } header: {
                Text(message)
            }
        } label: {
            Image(systemName: symbol)
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(tint)
                .symbolEffect(.pulse, isActive: needsAttention)
                .frame(width: 44, height: 44)
                .background(tint.opacity(needsAttention ? 0.25 : 0), in: .circle)
                .background(.fill.secondary, in: .circle)
        }
        .accessibilityLabel(Text(session.kind.title))
        .accessibilityValue(Text(message))
    }

    private var needsAttention: Bool { failedStop || workout.connectionLost || error != nil }

    private var symbol: String {
        if failedStop { return "exclamationmark.triangle.fill" }
        if session.onPad && disconnected { return "antenna.radiowaves.left.and.right.slash" }
        if let error { return error.systemImage }
        return session.onPad ? "figure.walk.treadmill" : session.kind.symbol
    }

    private var tint: Color {
        if failedStop { return .red }
        if workout.connectionLost { return .orange }
        if disconnected || error != nil { return .yellow }
        if session.held { return .secondary }
        return .workoutVolt
    }

    /// A few words on the state, beside the title.
    private var summary: String {
        if failedStop { return String(localized: "Belt Didn’t Stop") }
        if workout.connectionLost { return String(localized: "WalkingPad Lost") }
        if session.onPad && disconnected { return String(localized: "Reconnecting…") }
        if disconnected { return String(localized: "Not Counting") }
        if let error { return String(localized: error.title) }
        if session.ready { return String(localized: session.onPad ? "Ready on WalkingPad" : "Ready") }
        if workout.phase == .paused { return String(localized: "Paused") }
        if session.onPad { return String(localized: state.isRunning ? "WalkingPad Running" : "WalkingPad Stopped") }
        return String(localized: "Counting with iPhone")
    }

    private var message: LocalizedStringResource {
        if let error, failedStop { return error.body ?? error.title }
        if workout.connectionLost { return "Lost the WalkingPad. Your walk is paused; check that the pad is on and nearby." }
        if session.onPad && disconnected { return "Reconnecting to the WalkingPad…" }
        if disconnected { return "Your iPhone stopped counting. The workout is paused." }
        if let error { return error.title }
        if session.ready { return session.onPad ? "Press play to start the belt and the walk." : "Press play to start the workout." }
        if workout.phase == .paused { return "Paused" }
        return session.onPad ? (state.isRunning ? "WalkingPad running" : "WalkingPad stopped") : "Counting with your iPhone"
    }
}

/// Every metric on one page, the time first, as Fitness stacks them.
private struct MetricsPage: View {
    let workout: WorkoutUiState
    let session: WorkoutSession

    @ScaledMetric(relativeTo: .largeTitle) private var timeSize: CGFloat = 64

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                // While the belt runs the sheet holds the speed, so the time is up here; otherwise it is in the sheet.
                if session.onPad && !session.held {
                    ElapsedTime(seconds: Int(workout.durationSeconds), paused: false, size: min(timeSize, 104))
                }
                MetricBlock(
                    value: Int(workout.activeKcal).formatted(), unit: "CAL",
                    label: workout.weightEstimated ? "Active Calories*" : "Active Calories"
                )
                if workout.showsHeartRate {
                    HeartRateMetric(workout: workout)
                }
                MetricBlock(value: kilometres(workout.distanceMeters), unit: "KM", label: "Distance")
                if let navigation = workout.navigation {
                    MetricBlock(
                        value: kilometres(navigation.remainingMeters), unit: "KM",
                        label: navigation.onCourse ? "Remaining" : "Remaining · Off Route",
                        glyph: navigation.onCourse ? nil : Image(systemName: "exclamationmark.triangle.fill"), tint: .orange
                    )
                }
                MetricBlock(value: pace(workout.paceSecondsPerKm?.int32Value), unit: "/KM", label: "Average Pace")
                MetricBlock(value: Int(workout.steps).formatted(), unit: "", label: "Steps")
                if workout.weightEstimated {
                    Text("* Estimated for 70 kg")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 24)
            .padding(.top, 8)
        }
        .scrollBounceBehavior(.basedOnSize)
    }
}

private extension WorkoutUiState {
    /// With a watch in play, even an unreachable one: "--" then says the reading is missing.
    var showsHeartRate: Bool {
        heartRateBpm != nil || companion == .reachable || companion == .unreachable
    }
}

private struct HeartRateMetric: View {
    let workout: WorkoutUiState

    var body: some View {
        MetricBlock(
            value: workout.heartRateBpm.map { "\($0.int32Value)" } ?? "--", unit: "", label: label,
            glyph: Image(systemName: "heart.fill"), tint: workout.heartRateZone?.color ?? .workoutHeart
        )
    }

    private var label: LocalizedStringKey {
        if let zone = workout.heartRateZone { return "Heart Rate · \(zone.title.capitalized)" }
        if workout.heartRateBpm == nil && workout.companion == .unreachable { return "Heart Rate · Watch Away" }
        return "Heart Rate"
    }
}

/// A metric as Fitness shows it: a large value with its unit, and what it is underneath.
private struct MetricBlock: View {
    let value: String
    let unit: String
    let label: LocalizedStringKey
    var glyph: Image?
    var tint: Color = .white

    @ScaledMetric(relativeTo: .largeTitle) private var size: CGFloat = 56

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline, spacing: 2) {
                Text(value)
                    .font(.system(size: min(size, 96), weight: .medium, design: .rounded))
                    .monospacedDigit()
                    .contentTransition(.numericText())
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                if !unit.isEmpty {
                    Text(unit)
                        .font(.system(size: min(size, 96) * 0.55, weight: .semibold, design: .rounded))
                }
                if let glyph {
                    glyph
                        .font(.system(size: min(size, 96) * 0.4))
                        .foregroundStyle(tint)
                }
            }
            Text(label)
                .font(.system(.footnote, design: .rounded, weight: .medium))
                .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
    }
}

/// The elapsed time as Workout shows it: minutes, seconds and hundredths, ticking live in yellow while active,
/// grey and still while paused. The recorder counts whole seconds; in between, the clock runs on from the last one
/// it reported, never past the next.
private struct ElapsedTime: View {
    let seconds: Int
    let paused: Bool
    let size: CGFloat

    @State private var anchor = (seconds: 0, at: Date.now)
    @State private var heldAt: Double?

    var body: some View {
        TimelineView(.animation(paused: paused)) { context in
            Text(Self.format(paused ? heldAt ?? Double(seconds) : running(at: context.date)))
                .font(.system(size: size, weight: .semibold, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(paused ? Color.secondary : Color.workoutClock)
                .lineLimit(1)
                .minimumScaleFactor(0.5)
        }
        .onChange(of: seconds, initial: true) { _, seconds in anchor = (seconds, .now) }
        .onChange(of: paused, initial: true) { _, paused in heldAt = paused ? running(at: .now) : nil }
        .accessibilityElement()
        .accessibilityLabel("Duration")
        .accessibilityValue(Duration.seconds(seconds).formatted(.units(allowed: [.hours, .minutes, .seconds], width: .wide)))
    }

    private func running(at date: Date) -> Double {
        Double(anchor.seconds) + min(max(date.timeIntervalSince(anchor.at), 0), 0.99)
    }

    /// 00:05.26, or 1:02:05.26 past an hour.
    static func format(_ time: Double) -> String {
        let hundredths = Int(time * 100)
        let h = hundredths / 360_000, m = hundredths / 6_000 % 60, s = hundredths / 100 % 60, cs = hundredths % 100
        return h > 0
            ? String(format: "%d:%02d:%02d.%02d", h, m, s, cs)
            : String(format: "%02d:%02d.%02d", m, s, cs)
    }
}

/// The sheet: a list whose first row shows when compact, the pad's speed or else pause. Pulled up, it goes on
/// with pause (on the pad), End Workout, Connected Devices and Lock Controls.
private struct WorkoutControlsSheet: View {
    let state: TreadmillUiState
    let actions: TreadmillActions
    let session: WorkoutSession
    let onCancel: () -> Void
    @Binding var locked: Bool

    @State private var showingDevices = false

    private var workout: WorkoutUiState { state.workout }

    var body: some View {
        // No scroll view: a drag anywhere moves the sheet itself, as in Workout. Laid out from the top at full
        // height, so the compact sheet shows the first row and cuts off the rest.
        GeometryReader { geometry in
            controls
                .frame(width: geometry.size.width, alignment: .top)
        }
        .clipped()
        .ignoresSafeArea(.container, edges: .top)
        .sheet(isPresented: $showingDevices) {
            ConnectedDevicesSheet(state: state, actions: actions)
        }
    }

    private var controls: some View {
        VStack(spacing: 10) {
                Group {
                    if session.onPad && !session.held {
                        SheetSpeedControl(state: state, actions: actions)
                            .disabled(!state.isConnected || locked)
                    } else {
                        // As in Fitness: play or pause, and the time in the middle.
                        HStack {
                            roundPauseButton
                            ElapsedTime(seconds: Int(workout.durationSeconds), paused: session.held, size: 48)
                                .frame(maxWidth: .infinity)
                            Color.clear.frame(width: RoundControl.compactSize, height: RoundControl.compactSize)
                        }
                    }
                }
                .frame(height: RoundControl.compactSize)
                // Close to the sheet's edges, so the buttons fill it; the rest stays below the compact height.
                .padding(.horizontal, -8)
                // While paused, the round button in the first row resumes.
                if session.onPad && !session.held {
                    pauseButton
                }
                SheetButton(title: "End Workout", systemImage: "xmark", tint: .red, action: session.ready ? onCancel : actions.endWorkout)
                    .disabled(locked)
                SheetButton(title: "Connected Devices", systemImage: "antenna.radiowaves.left.and.right") { showingDevices = true }
                LockButton(locked: $locked)
        }
        .padding(.horizontal, 18)
        // From the sheet's top edge, the same margin as at the sides; the grabber floats over the first row.
        .padding(.top, 9)
        .padding(.bottom, 24)
        .fixedSize(horizontal: false, vertical: true)
    }

    /// Play, pause or resume in the compact sheet, in the yellow of the clock it runs.
    private var roundPauseButton: some View {
        let title: LocalizedStringKey = session.ready ? "Start" : session.held ? "Resume" : "Pause"
        let symbol = session.ready ? "play.fill" : session.held ? "arrow.clockwise" : "pause.fill"
        return RoundControl(
            title: title, systemImage: symbol, size: RoundControl.compactSize, tint: .workoutClock,
            busy: session.ready && workout.starting, action: playPause
        )
        // A pad that is not there cannot resume.
        .disabled(locked || (!session.ready && session.held && workout.pauseReason == .disconnected))
    }

    private func playPause() {
        if session.ready {
            // On the pad, the walk starts once the belt runs.
            if session.onPad { actions.toggleBelt() } else { actions.startWorkout(session.kind, session.course) }
        } else if session.held {
            actions.resumeWorkout()
        } else {
            actions.pauseWorkout()
        }
    }

    /// Pause in the list while the belt runs, where the first row holds the speed.
    private var pauseButton: some View {
        SheetButton(title: "Pause", systemImage: "pause.fill", tint: .workoutClock, action: actions.pauseWorkout)
            .disabled(locked)
    }
}

private extension View {
    /// Interactive Liquid Glass on iOS 26+, a plain fill before.
    @ViewBuilder func roundGlass() -> some View {
        if #available(iOS 26, *) {
            glassEffect(.regular.interactive(), in: .circle)
        } else {
            background(.fill.secondary, in: .circle)
        }
    }
}

/// The pad's speed between slower and faster: the main control while walking on it.
private struct SheetSpeedControl: View {
    let state: TreadmillUiState
    let actions: TreadmillActions

    var body: some View {
        HStack {
            step("Slower", systemImage: "minus", enabled: state.canDecreaseSpeed, action: actions.decreaseSpeed)
            Spacer(minLength: 4)
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(state.speedKmh, format: .number.precision(.fractionLength(1)))
                    .font(.system(size: 40, weight: .semibold, design: .rounded))
                    .monospacedDigit()
                    .contentTransition(.numericText(value: state.speedKmh))
                    .animation(.snappy, value: state.speedKmh)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                Text("KM/H")
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                    .foregroundStyle(.secondary)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Speed")
            .accessibilityValue(
                Measurement(value: state.speedKmh, unit: UnitSpeed.kilometersPerHour)
                    .formatted(.measurement(width: .wide, usage: .asProvided))
            )
            .accessibilityAdjustableAction { direction in
                switch direction {
                case .increment: if state.canIncreaseSpeed { actions.increaseSpeed() }
                case .decrement: if state.canDecreaseSpeed { actions.decreaseSpeed() }
                @unknown default: break
                }
            }
            Spacer(minLength: 4)
            step("Faster", systemImage: "plus", enabled: state.canIncreaseSpeed, action: actions.increaseSpeed)
        }
    }

    private func step(_ title: LocalizedStringKey, systemImage: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        // As tall as the compact sheet allows, like the buttons beside the timer in Fitness.
        RoundControl(title: title, systemImage: systemImage, size: RoundControl.compactSize, action: action)
            .disabled(!enabled)
    }
}

/// A full-width button in the sheet, like those under the timer in Fitness.
private struct SheetButton: View {
    let title: LocalizedStringKey
    let systemImage: String
    var tint: Color = .white
    let action: () -> Void

    @Environment(\.isEnabled) private var isEnabled

    var body: some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .font(.headline)
                .foregroundStyle(tint == .white ? Color.white : tint)
                .frame(maxWidth: .infinity, minHeight: 64)
                .background(tint == .white ? AnyShapeStyle(.fill.tertiary) : AnyShapeStyle(tint.opacity(0.3)), in: .capsule)
                .opacity(isEnabled ? 1 : 0.4)
        }
        .buttonStyle(.plain)
    }
}

/// Locks pause, end and speed against stray touches; holding it unlocks them again.
private struct LockButton: View {
    @Binding var locked: Bool

    var body: some View {
        if locked {
            Label("Hold to Unlock", systemImage: "lock.open.fill")
                .font(.headline)
                .foregroundStyle(Color.workoutVolt)
                .frame(maxWidth: .infinity, minHeight: 64)
                .background(Color.workoutVolt.opacity(0.2), in: .capsule)
                .onLongPressGesture(minimumDuration: 1) { locked = false }
                .sensoryFeedback(.success, trigger: locked) { _, locked in !locked }
                .accessibilityAddTraits(.isButton)
                .accessibilityAction { locked = false }
        } else {
            SheetButton(title: "Lock Controls", systemImage: "lock.fill") { locked = true }
        }
    }
}

private struct RoundControl: View {
    /// Fills the compact sheet but for its margin, like the buttons beside the timer in Fitness.
    static let compactSize: CGFloat = 71

    let title: LocalizedStringKey
    let systemImage: String
    var size: CGFloat = 56
    var tint: Color = .white
    var busy = false
    let action: () -> Void

    @Environment(\.isEnabled) private var isEnabled
    @State private var taps = 0

    var body: some View {
        Button {
            taps += 1
            action()
        } label: {
            Group {
                if busy {
                    ProgressView().tint(tint)
                } else {
                    Image(systemName: systemImage)
                        .font(.system(size: size * 0.4, weight: .bold))
                        .foregroundStyle(tint)
                }
            }
            .opacity(isEnabled ? 1 : 0.4)
            .frame(width: size, height: size)
            .roundGlass()
        }
        .disabled(busy)
        .buttonStyle(.plain)
        .accessibilityLabel(Text(title))
        .sensoryFeedback(.impact(weight: .heavy), trigger: taps)
    }
}

func formatDuration(_ totalSeconds: Int) -> String {
    Duration.seconds(totalSeconds).formatted(.time(pattern: totalSeconds >= 3600 ? .hourMinuteSecond : .minuteSecond))
}

func kilometres(_ meters: Int32) -> String {
    (Double(meters) / 1000).formatted(.number.precision(.fractionLength(2)))
}

/// Minutes and seconds per kilometre in the Workout notation, such as 17'08".
func pace(_ secondsPerKm: Int32?) -> String {
    guard let secondsPerKm else { return "--'--\"" }
    return "\(secondsPerKm / 60)'\(String(format: "%02d", secondsPerKm % 60))\""
}

#Preview("Walking") {
    LiveWorkoutView(state: previewState(running: true, workout: previewWorkout(phase: .active)), actions: TreadmillActions(), kind: .indoorWalk)
}
#Preview("Walking without Pad") {
    LiveWorkoutView(
        state: previewState(connected: false, paired: false, workout: previewWorkout(phase: .active, usesPad: false)), actions: TreadmillActions(), kind: .indoorWalk
    )
}
#Preview("Paused") {
    LiveWorkoutView(
        state: previewState(workout: previewWorkout(phase: .paused, pauseReason: PauseReason.beltstopped)), actions: TreadmillActions(), kind: .indoorWalk
    )
}
#Preview("Reconnecting") {
    LiveWorkoutView(
        state: previewState(
            connected: false, connecting: true, workout: previewWorkout(phase: .paused, pauseReason: PauseReason.disconnected, pausedSeconds: 12)
        ),
        actions: TreadmillActions(), kind: .indoorWalk
    )
}
#Preview("Pad Lost") {
    LiveWorkoutView(
        state: previewState(
            connected: false, connecting: true, workout: previewWorkout(phase: .paused, pauseReason: PauseReason.disconnected, pausedSeconds: 45)
        ),
        actions: TreadmillActions(), kind: .indoorWalk
    )
}
#Preview("Stop Failed") {
    LiveWorkoutView(
        state: previewState(
            running: true, error: TreadmillErrorCommandFailed(command: TreadmillCommandStopBelt.shared), workout: previewWorkout(phase: .active)
        ),
        actions: TreadmillActions(), kind: .indoorWalk
    )
}
#Preview("Ready") {
    LiveWorkoutView(state: previewState(connected: false, paired: false), actions: TreadmillActions(), kind: .indoorWalk)
}
