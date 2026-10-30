import SwiftUI
import SharedLogic

final class AppDelegate: NSObject, UIApplicationDelegate {
    private(set) var app: IosApp!

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Created before launch finishes so CoreBluetooth state restoration can hand back the connection
        // when iOS relaunches the app in the background for the WalkingPad, and the walk with it.
        #if targetEnvironment(simulator)
        app = IosTreadmillSessionKt.createIosApp(simulated: true)
        #else
        app = IosTreadmillSessionKt.createIosApp(simulated: false)
        #endif
        return true
    }
}

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView(app: appDelegate.app)
        }
    }
}
