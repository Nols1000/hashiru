import CoreLocation
import Foundation
import HealthKit

/// Runs the walk's primary `HKWorkoutSession` and mirrors it to the phone. The phone owns the walk and the pad: it
/// launches this session with `startWatchApp(with:)`, then sends the walk's start, the pad's counts and the end,
/// and pauses and resumes the mirror. The session times the walk, estimates its energy, measures heart rate, holds
/// the pad's counts as samples and saves the workout; its totals go back to the phone once a second. Outdoors, it
/// also records the route with the watch's GPS and saves it with the workout.
final class WorkoutSessionManager: NSObject, HKWorkoutSessionDelegate, HKLiveWorkoutBuilderDelegate, CLLocationManagerDelegate {
    /// Called on the main queue with each new reading.
    var onHeartRate: ((Int, Date) -> Void)?

    /// How long to wait for the phone's start message before starting at once.
    private static let startTimeout: Duration = .seconds(5)
    /// How long the saved session stays up, so a repeated end can be answered again.
    private static let endDelay: Duration = .seconds(10)
    private static let walkIdKey = "workout.walkId"
    private static let padCountsKey = "workout.padCounts"

    private let store = HKHealthStore()
    private let heartRateType = HKQuantityType(.heartRate)
    private let distanceType = HKQuantityType(.distanceWalkingRunning)
    private let stepsType = HKQuantityType(.stepCount)
    private let activeType = HKQuantityType(.activeEnergyBurned)
    private let basalType = HKQuantityType(.basalEnergyBurned)
    private let routeType = HKSeriesType.workoutRoute()
    /// Positions less accurate than this are left out of the route.
    private static let maxAccuracy: CLLocationAccuracy = 20
    private let bpm = HKUnit.count().unitDivided(by: .minute())
    private let padDevice = HKDevice(
        name: "WalkingPad", manufacturer: "KingSmith", model: nil, hardwareVersion: nil, firmwareVersion: nil,
        softwareVersion: nil, localIdentifier: nil, udiDeviceIdentifier: nil
    )

    private var session: HKWorkoutSession?
    private var builder: HKLiveWorkoutBuilder?
    private var location: CLLocationManager?
    private var routeBuilder: HKWorkoutRouteBuilder?
    private var walkId: String?
    private var counts: CountedIncrements?
    private var began = false
    private var startTask: Task<Void, Never>?
    private var totalsTask: Task<Void, Never>?
    private var ending: (id: String, syncVersion: Int64, date: Date)?
    /// The answer to the end, kept to repeat it.
    private var result: Data?

    var isRunning: Bool { session != nil }

    func requestAuthorization() async {
        let shared: Set = [HKObjectType.workoutType(), distanceType, stepsType, activeType, basalType, routeType]
        let read: Set<HKObjectType> = [heartRateType, distanceType, stepsType, activeType, basalType, routeType]
        do {
            try await store.requestAuthorization(toShare: shared, read: read)
        } catch {
            print("WorkoutSession: authorization failed: \(error.localizedDescription)")
        }
    }

    /// Starts the session the phone asked for and mirrors it back; the activity begins with the phone's start message.
    func start(_ configuration: HKWorkoutConfiguration) {
        guard session == nil else { return }
        do {
            let session = try HKWorkoutSession(healthStore: store, configuration: configuration)
            let builder = session.associatedWorkoutBuilder()
            builder.dataSource = HKLiveWorkoutDataSource(healthStore: store, workoutConfiguration: configuration)
            attach(session, builder)
            mirror(session)
            startTask = Task { @MainActor in
                try? await Task.sleep(for: Self.startTimeout)
                guard !Task.isCancelled, !self.began else { return }
                // The start message was lost; indoors, most walks are on the pad, whose counts follow.
                self.begin(at: Date(), padCounts: configuration.locationType == .indoor)
            }
        } catch {
            print("WorkoutSession: could not start: \(error.localizedDescription)")
        }
    }

    /// Takes back the session that ran when the app was stopped, and mirrors it again.
    func recover() {
        store.recoverActiveWorkoutSession { session, error in
            DispatchQueue.main.async {
                guard let session, self.session == nil else {
                    if let error { print("WorkoutSession: nothing to recover: \(error.localizedDescription)") }
                    return
                }
                let builder = session.associatedWorkoutBuilder()
                let padCounts = UserDefaults.standard.bool(forKey: Self.padCountsKey)
                builder.dataSource = self.dataSource(for: session.workoutConfiguration, padCounts: padCounts)
                self.attach(session, builder)
                self.walkId = UserDefaults.standard.string(forKey: Self.walkIdKey)
                self.began = true
                var counts = CountedIncrements(lastMs: Date().epochMs)
                counts.seed(meters: self.whole(self.distanceType, .meter()), steps: self.whole(self.stepsType, .count()), atMs: Date().epochMs)
                self.counts = counts
                self.startTotals()
                // The route goes on from now; what came before the app was stopped is lost.
                self.startRoute(for: session.workoutConfiguration)
                self.mirror(session)
            }
        }
    }

    /// Drops the session without saving, as when the phone has no walk for it.
    func discard() {
        guard let session, let builder else { return }
        routeBuilder?.discard()
        reset()
        session.end()
        builder.discardWorkout()
    }

    private func attach(_ session: HKWorkoutSession, _ builder: HKLiveWorkoutBuilder) {
        session.delegate = self
        builder.delegate = self
        self.session = session
        self.builder = builder
    }

    private func mirror(_ session: HKWorkoutSession) {
        session.startMirroringToCompanionDevice { success, error in
            guard !success else { return }
            print("WorkoutSession: could not mirror: \(error?.localizedDescription ?? "")")
            // Without the mirror the phone records the walk itself.
            DispatchQueue.main.async { if self.session === session { self.discard() } }
        }
    }

    private func dataSource(for configuration: HKWorkoutConfiguration, padCounts: Bool) -> HKLiveWorkoutDataSource {
        let source = HKLiveWorkoutDataSource(healthStore: store, workoutConfiguration: configuration)
        if padCounts {
            // The pad's counts come in as samples instead of the wrist's estimate.
            source.disableCollection(for: distanceType)
            source.disableCollection(for: stepsType)
        }
        return source
    }

    private func begin(at start: Date, padCounts: Bool) {
        guard let session, let builder, !began else { return }
        began = true
        startTask?.cancel()
        builder.dataSource = dataSource(for: session.workoutConfiguration, padCounts: padCounts)
        UserDefaults.standard.set(walkId, forKey: Self.walkIdKey)
        UserDefaults.standard.set(padCounts, forKey: Self.padCountsKey)
        counts = CountedIncrements(lastMs: start.epochMs)
        session.startActivity(with: start)
        builder.beginCollection(withStart: start) { _, error in
            if let error { print("WorkoutSession: could not begin: \(error.localizedDescription)") }
        }
        startTotals()
        startRoute(for: session.workoutConfiguration)
    }

    /// Records the route of an outdoor workout; the first time, the watch asks for location access.
    private func startRoute(for configuration: HKWorkoutConfiguration) {
        guard configuration.locationType == .outdoor, location == nil else { return }
        let manager = CLLocationManager()
        manager.delegate = self
        manager.activityType = .fitness
        manager.desiredAccuracy = kCLLocationAccuracyBest
        if manager.authorizationStatus == .notDetermined { manager.requestWhenInUseAuthorization() }
        manager.startUpdatingLocation()
        location = manager
        routeBuilder = HKWorkoutRouteBuilder(healthStore: store, device: nil)
    }

    private func stopRoute() {
        location?.stopUpdatingLocation()
        location?.delegate = nil
        location = nil
    }

    private func receive(_ message: SessionMessage) {
        switch message {
        case .start(let id, let startMs, _, let padCounts):
            walkId = id
            begin(at: Date(epochMs: startMs), padCounts: padCounts)
        case .counted(let atMs, let meters, let steps):
            add(atMs: atMs, meters: meters, steps: steps)
        case .end(let endMs, let id, let syncVersion):
            end(at: Date(epochMs: endMs), id: id, syncVersion: syncVersion)
        case .discard:
            discard()
        }
    }

    private func add(atMs: Int64, meters: Int, steps: Int) {
        guard began, let builder, let more = counts?.next(atMs: atMs, meters: meters, steps: steps) else { return }
        let start = Date(epochMs: more.fromMs)
        let end = Date(epochMs: more.toMs)
        let metadata: [String: Any] = walkId.map { [HKMetadataKeyExternalUUID: $0] } ?? [:]
        var samples: [HKQuantitySample] = []
        if more.meters > 0 {
            let quantity = HKQuantity(unit: .meter(), doubleValue: Double(more.meters))
            samples.append(HKQuantitySample(type: distanceType, quantity: quantity, start: start, end: end, device: padDevice, metadata: metadata))
        }
        if more.steps > 0 {
            let quantity = HKQuantity(unit: .count(), doubleValue: Double(more.steps))
            samples.append(HKQuantitySample(type: stepsType, quantity: quantity, start: start, end: end, device: padDevice, metadata: metadata))
        }
        builder.add(samples) { _, error in
            if let error { print("WorkoutSession: could not add the pad's counts: \(error.localizedDescription)") }
        }
    }

    /// Stops the activity at `date`; the workout is saved once the session reports it stopped.
    private func end(at date: Date, id: String, syncVersion: Int64) {
        if let result {
            send(result)
            return
        }
        guard let session, ending == nil else { return }
        ending = (id, syncVersion, date)
        session.stopActivity(with: date)
    }

    private func save() {
        guard let session, let builder, let ending else { return }
        totalsTask?.cancel()
        stopRoute()
        let routeBuilder = routeBuilder
        let metadata: [String: Any] = [
            HKMetadataKeyExternalUUID: ending.id,
            HKMetadataKeySyncIdentifier: "\(ending.id)-workout",
            HKMetadataKeySyncVersion: ending.syncVersion,
            HKMetadataKeyIndoorWorkout: session.workoutConfiguration.locationType == .indoor,
        ]
        Task { @MainActor in
            do {
                try await builder.endCollection(at: ending.date)
                try await builder.addMetadata(metadata)
                let workout = try await builder.finishWorkout()
                if let workout, let routeBuilder {
                    do {
                        try await routeBuilder.finishRoute(with: workout, metadata: [HKMetadataKeyExternalUUID: ending.id])
                    } catch {
                        // The workout is saved either way.
                        print("WorkoutSession: could not save the route: \(error.localizedDescription)")
                    }
                }
                answer(ok: true, message: "")
            } catch {
                print("WorkoutSession: could not save: \(error.localizedDescription)")
                answer(ok: false, message: error.localizedDescription)
            }
            // Stays up a moment, so an end repeated because the answer was lost gets it again.
            try? await Task.sleep(for: Self.endDelay)
            if self.session === session { session.end() }
        }
    }

    private func answer(ok: Bool, message: String) {
        let data = SessionCodec.encodeResult(ok: ok, message: message)
        result = data
        send(data)
    }

    /// Sends the totals once a second, so the phone's time moves on between samples.
    private func startTotals() {
        totalsTask?.cancel()
        totalsTask = Task { @MainActor in
            while !Task.isCancelled {
                sendTotals()
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }

    private func sendTotals() {
        guard let builder, began else { return }
        let active = sum(activeType, .kilocalorie())
        let basal = sum(basalType, .kilocalorie())
        let heartRate = builder.statistics(for: heartRateType)
        var totals = SessionTotals(seconds: Int(builder.elapsedTime))
        totals.meters = whole(distanceType, .meter())
        totals.steps = whole(stepsType, .count())
        totals.activeKcal = active
        totals.totalKcal = active + basal
        totals.latestBpm = heartRate?.mostRecentQuantity().map(bpm(_:))
        totals.avgBpm = heartRate?.averageQuantity().map(bpm(_:))
        totals.maxBpm = heartRate?.maximumQuantity().map(bpm(_:))
        send(SessionCodec.encode(totals))
    }

    private func sum(_ type: HKQuantityType, _ unit: HKUnit) -> Double {
        builder?.statistics(for: type)?.sumQuantity()?.doubleValue(for: unit) ?? 0
    }

    private func whole(_ type: HKQuantityType, _ unit: HKUnit) -> Int { Int(sum(type, unit).rounded()) }

    private func bpm(_ quantity: HKQuantity) -> Int { Int(quantity.doubleValue(for: bpm).rounded()) }

    private func send(_ data: Data) {
        session?.sendToRemoteWorkoutSession(data: data) { _, error in
            if let error { print("WorkoutSession: could not reach the phone: \(error.localizedDescription)") }
        }
    }

    private func reset() {
        startTask?.cancel()
        totalsTask?.cancel()
        stopRoute()
        routeBuilder = nil
        session = nil
        builder = nil
        walkId = nil
        counts = nil
        began = false
        ending = nil
        result = nil
        UserDefaults.standard.removeObject(forKey: Self.walkIdKey)
        UserDefaults.standard.removeObject(forKey: Self.padCountsKey)
    }

    // MARK: - HKWorkoutSessionDelegate

    func workoutSession(
        _ workoutSession: HKWorkoutSession, didChangeTo toState: HKWorkoutSessionState, from fromState: HKWorkoutSessionState, date: Date
    ) {
        DispatchQueue.main.async {
            guard self.session === workoutSession else { return }
            switch toState {
            case .stopped where self.ending != nil: self.save()
            // Ended by the system, from Control Center, or after saving: let go, so the next walk starts fresh.
            case .ended: self.reset()
            default: break
            }
        }
    }

    func workoutSession(_ workoutSession: HKWorkoutSession, didFailWithError error: Error) {
        print("WorkoutSession: failed: \(error.localizedDescription)")
    }

    func workoutSession(_ workoutSession: HKWorkoutSession, didReceiveDataFromRemoteWorkoutSession data: [Data]) {
        let messages = data.compactMap(SessionCodec.decode)
        DispatchQueue.main.async {
            guard self.session === workoutSession else { return }
            messages.forEach(self.receive)
        }
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

    // MARK: - CLLocationManagerDelegate

    /// Called on the main queue, where the manager was made.
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard manager === location, session?.state == .running, let routeBuilder else { return }
        let accurate = locations.filter { $0.horizontalAccuracy >= 0 && $0.horizontalAccuracy <= Self.maxAccuracy }
        guard !accurate.isEmpty else { return }
        routeBuilder.insertRouteData(accurate) { _, error in
            if let error { print("WorkoutSession: could not add to the route: \(error.localizedDescription)") }
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        print("WorkoutSession: location failed: \(error.localizedDescription)")
    }
}

/// Turns the pad's cumulative counts into what the builder has not been given yet, each covering the time since
/// the last; the phone's copy is `CountedIncrements` in the shared Kotlin code.
struct CountedIncrements {
    private(set) var lastMs: Int64
    private var meters = 0
    private var steps = 0

    init(lastMs: Int64) { self.lastMs = lastMs }

    mutating func seed(meters: Int, steps: Int, atMs: Int64) {
        self.meters = meters
        self.steps = steps
        lastMs = max(lastMs, atMs)
    }

    mutating func next(atMs: Int64, meters: Int, steps: Int) -> (fromMs: Int64, toMs: Int64, meters: Int, steps: Int)? {
        guard atMs > lastMs else { return nil }
        let more = (fromMs: lastMs, toMs: atMs, meters: max(meters - self.meters, 0), steps: max(steps - self.steps, 0))
        guard more.meters > 0 || more.steps > 0 else { return nil }
        self.meters = max(self.meters, meters)
        self.steps = max(self.steps, steps)
        lastMs = atMs
        return more
    }
}

extension Date {
    init(epochMs: Int64) { self.init(timeIntervalSince1970: TimeInterval(epochMs) / 1000) }

    var epochMs: Int64 { Int64(timeIntervalSince1970 * 1000) }
}
