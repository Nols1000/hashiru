import HealthKit
import SwiftUI
import WatchKit

final class WatchAppDelegate: NSObject, WKApplicationDelegate {
    /// The phone started a walk and launched this app with `startWatchApp(with:)`.
    func handle(_ workoutConfiguration: HKWorkoutConfiguration) {
        Task { @MainActor in WalkModel.shared.startWorkout(workoutConfiguration) }
    }
}

@main
struct HashiruWatchApp: App {
    @WKApplicationDelegateAdaptor(WatchAppDelegate.self) private var delegate

    var body: some Scene {
        WindowGroup {
            WalkView(model: .shared)
        }
    }
}
