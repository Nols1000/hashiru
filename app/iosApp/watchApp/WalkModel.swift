import Foundation
import HealthKit
import WatchKit

/// What the wrist shows and can do. The phone owns the treadmill and the walk; this shows it, runs the walk's
/// workout session (which the phone mirrors), and sends commands.
@MainActor
final class WalkModel: ObservableObject {
    static let shared = WalkModel()

    static let minTenths = 5
    static let maxTenths = 60

    /// Speed changes go out once the crown has been still this long, so the treadmill gets one command per turn.
    private static let settle: Duration = .milliseconds(300)
    /// A failed command is shown this long; the next tap tells the walker whether it works now.
    private static let commandErrorDuration: Duration = .seconds(4)

    @Published private(set) var telemetry = WristTelemetry()
    @Published private(set) var localBpm: Int?
    @Published private(set) var phoneReachable = false
    @Published private(set) var error: LocalizedStringResource?
    /// True while `error` is a stop that did not reach the pad, which stays until the belt stops.
    @Published private(set) var stopFailed = false
    /// The speed the crown asked for, until the pad reports it.
    @Published private(set) var targetTenths: Int?
    /// The route the workout follows, from the phone; nil without one.
    @Published private(set) var course: WristCourse?

    private let link = PhoneLink()
    private let workout = WorkoutSessionManager()
    private var speedTask: Task<Void, Never>?
    private var errorTask: Task<Void, Never>?
    private var orphanTask: Task<Void, Never>?

    /// How long a session may run while the phone has no walk before it is dropped.
    private static let orphanTimeout: Duration = .seconds(60)

    private init() {
        link.onTelemetry = { [weak self] in self?.apply($0) }
        link.onCourse = { [weak self] in self?.course = $0 }
        link.onReachability = { [weak self] in self?.phoneReachable = $0 }
        workout.onHeartRate = { [weak self] bpm, date in self?.heartRate(bpm, at: date) }
        link.activate()
        Task { await workout.requestAuthorization() }
    }

    var controlsEnabled: Bool { phoneReachable && telemetry.isConnected }

    /// The phone started a walk; its session runs here and is mirrored back.
    func startWorkout(_ configuration: HKWorkoutConfiguration) {
        workout.start(configuration)
    }

    /// Takes back the session that ran when the app was stopped.
    func recoverWorkout() {
        workout.recover()
    }

    /// Moves the pending speed target from the crown; the command follows once the crown settles.
    func setTarget(_ tenths: Int) {
        guard telemetry.isRunning, controlsEnabled else { return }
        let target = min(max(tenths, Self.minTenths), Self.maxTenths)
        guard target != (targetTenths ?? telemetry.speedTenths) else { return }
        targetTenths = target
        speedTask?.cancel()
        speedTask = Task {
            try? await Task.sleep(for: Self.settle)
            guard !Task.isCancelled else { return }
            if await !link.send(.setSpeed(tenths: target)) {
                fail("Phone didn’t respond. Try again.")
                targetTenths = nil
            }
        }
    }

    func togglePause() {
        send(.setPaused(telemetry.phase == .active), failure: "Phone didn’t respond. Try again.")
    }

    func stopBelt() {
        send(.stopBelt, failure: "Couldn’t stop. Press the button on the pad.", critical: true)
    }

    private func send(_ command: WristCommand, failure: LocalizedStringResource, critical: Bool = false) {
        clearError()
        Task {
            if await link.send(command) {
                WKInterfaceDevice.current().play(.success)
            } else {
                fail(failure, critical: critical)
            }
        }
    }

    private func fail(_ message: LocalizedStringResource, critical: Bool = false) {
        error = message
        stopFailed = critical
        WKInterfaceDevice.current().play(.failure)
        errorTask?.cancel()
        guard !critical else { return }
        errorTask = Task {
            try? await Task.sleep(for: Self.commandErrorDuration)
            guard !Task.isCancelled else { return }
            clearError()
        }
    }

    private func clearError() {
        errorTask?.cancel()
        error = nil
        stopFailed = false
    }

    private func apply(_ telemetry: WristTelemetry) {
        // Off the route or back on it: the wrist feels it, as the phone may be in a pocket.
        if let before = self.telemetry.navigation, let now = telemetry.navigation, before.onCourse != now.onCourse {
            WKInterfaceDevice.current().play(now.onCourse ? .success : .failure)
        }
        if let now = telemetry.navigation, now.finished, self.telemetry.navigation?.finished == false {
            WKInterfaceDevice.current().play(.success)
        }
        self.telemetry = telemetry
        if !telemetry.isRunning || telemetry.speedTenths == targetTenths { targetTenths = nil }
        // Stopped by the pad's own button or remote, the failed stop no longer matters.
        if !telemetry.isRunning && stopFailed { clearError() }
        // The phone starts and ends the session; a session left running without a walk is dropped after a while.
        if telemetry.phase == .idle {
            localBpm = nil
            guard workout.isRunning, orphanTask == nil else { return }
            orphanTask = Task {
                try? await Task.sleep(for: Self.orphanTimeout)
                guard !Task.isCancelled else { return }
                orphanTask = nil
                if self.telemetry.phase == .idle { workout.discard() }
            }
        } else {
            orphanTask?.cancel()
            orphanTask = nil
        }
    }

    /// Shown on the wrist; the phone gets heart rate through the mirrored session.
    private func heartRate(_ bpm: Int, at date: Date) {
        localBpm = bpm
    }
}
