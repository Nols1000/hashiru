import Foundation
import HealthKit

/// Runs an `HKWorkoutSession` while the phone records a walk. The session is what keeps the optical heart-rate
/// sensor sampling with the wrist down; the phone writes the walk to Apple Health, so the workout is discarded
/// at the end instead of saved. The heart-rate samples the watch records stay in Apple Health on their own.
final class WorkoutSessionManager: NSObject, HKWorkoutSessionDelegate, HKLiveWorkoutBuilderDelegate {
    /// Called on the main queue with each new reading.
    var onHeartRate: ((Int, Date) -> Void)?

    private let store = HKHealthStore()
    private let heartRateType = HKQuantityType(.heartRate)
    private let bpm = HKUnit.count().unitDivided(by: .minute())
    private var session: HKWorkoutSession?
    private var builder: HKLiveWorkoutBuilder?

    var isRunning: Bool { session != nil }

    func requestAuthorization() async {
        do {
            try await store.requestAuthorization(toShare: [HKObjectType.workoutType()], read: [heartRateType])
        } catch {
            print("WorkoutSession: authorization failed: \(error.localizedDescription)")
        }
    }

    func start(_ configuration: HKWorkoutConfiguration? = nil) {
        guard session == nil else { return }
        let configuration = configuration ?? {
            let c = HKWorkoutConfiguration()
            c.activityType = .walking
            c.locationType = .indoor
            return c
        }()
        do {
            let session = try HKWorkoutSession(healthStore: store, configuration: configuration)
            let builder = session.associatedWorkoutBuilder()
            builder.dataSource = HKLiveWorkoutDataSource(healthStore: store, workoutConfiguration: configuration)
            session.delegate = self
            builder.delegate = self
            self.session = session
            self.builder = builder
            let start = Date()
            session.startActivity(with: start)
            builder.beginCollection(withStart: start) { _, error in
                if let error { print("WorkoutSession: could not begin: \(error.localizedDescription)") }
            }
        } catch {
            print("WorkoutSession: could not start: \(error.localizedDescription)")
        }
    }

    func end() {
        guard let session, let builder else { return }
        self.session = nil
        self.builder = nil
        session.end()
        builder.endCollection(withEnd: Date()) { _, _ in builder.discardWorkout() }
    }

    // MARK: - HKWorkoutSessionDelegate

    func workoutSession(
        _ workoutSession: HKWorkoutSession, didChangeTo toState: HKWorkoutSessionState, from fromState: HKWorkoutSessionState, date: Date
    ) {
        // Ended by the system or from Control Center: let go, so the next walk can start a fresh one.
        guard toState == .ended else { return }
        DispatchQueue.main.async {
            if self.session === workoutSession { self.end() }
        }
    }

    func workoutSession(_ workoutSession: HKWorkoutSession, didFailWithError error: Error) {
        print("WorkoutSession: failed: \(error.localizedDescription)")
    }

    // MARK: - HKLiveWorkoutBuilderDelegate

    func workoutBuilder(_ workoutBuilder: HKLiveWorkoutBuilder, didCollectDataOf collectedTypes: Set<HKSampleType>) {
        guard collectedTypes.contains(heartRateType),
              let statistics = workoutBuilder.statistics(for: heartRateType),
              let quantity = statistics.mostRecentQuantity() else { return }
        let value = Int(quantity.doubleValue(for: bpm).rounded())
        let date = statistics.mostRecentQuantityDateInterval()?.end ?? Date()
        DispatchQueue.main.async { self.onHeartRate?(value, date) }
    }

    func workoutBuilderDidCollectEvent(_ workoutBuilder: HKLiveWorkoutBuilder) {}
}
