import AVFoundation
import SwiftUI
import UIKit
import UserNotifications
import SharedLogic

/// Tells the runner they left the route or are back on it: a haptic and a spoken line while the app is in front,
/// a time-sensitive notification while it is in the background or the iPhone is locked. A watch on the wrist buzzes
/// on its own.
@MainActor
final class NavigationAlerts {
    static let shared = NavigationAlerts()

    private let speech = AVSpeechSynthesizer()
    private let haptics = UINotificationFeedbackGenerator()

    /// Asks once for notifications, so alerts reach a locked iPhone; called when a workout with a route opens.
    func prepare() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, error in
            if let error { print("NavigationAlerts: \(error.localizedDescription)") }
        }
    }

    func announce(_ navigation: NavigationStatus) {
        let text = navigation.onCourse
            ? String(localized: "Back on route.")
            : String(localized: "Off route, \(navigation.offCourseMeters) metres away.")
        if UIApplication.shared.applicationState == .active {
            haptics.notificationOccurred(navigation.onCourse ? .success : .warning)
            speak(text)
        } else {
            notify(text, onCourse: navigation.onCourse)
        }
    }

    private func speak(_ text: String) {
        // Ducks music and podcasts rather than stopping them, and hands the audio back afterwards.
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .voicePrompt, options: [.duckOthers, .interruptSpokenAudioAndMixWithOthers])
        try? session.setActive(true)
        let utterance = AVSpeechUtterance(string: text)
        speech.speak(utterance)
        Task {
            try? await Task.sleep(for: .seconds(3))
            if !speech.isSpeaking { try? session.setActive(false, options: .notifyOthersOnDeactivation) }
        }
    }

    private func notify(_ text: String, onCourse: Bool) {
        let content = UNMutableNotificationContent()
        content.title = String(localized: onCourse ? "Back on Route" : "Off Route")
        content.body = text
        content.sound = .default
        content.interruptionLevel = .timeSensitive
        // One at a time: a newer alert replaces the last.
        let request = UNNotificationRequest(identifier: "navigation", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }
}

/// Where the workout stands on its route, over the map: how far to go, or how far off it is.
struct NavigationBanner: View {
    let navigation: NavigationStatus

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: symbol)
                .font(.title3.weight(.semibold))
                .foregroundStyle(tint)
            VStack(alignment: .leading, spacing: 0) {
                Text(title)
                    .font(.system(.headline, design: .rounded))
                Text(detail)
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
        .padding(12)
        .background(.ultraThinMaterial, in: .rect(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(tint.opacity(navigation.onCourse ? 0 : 0.8), lineWidth: 2))
        .accessibilityElement(children: .combine)
    }

    private var symbol: String {
        if navigation.finished { return "flag.checkered" }
        if !navigation.joined { return "location.north.line" }
        return navigation.onCourse ? "point.topleft.down.to.point.bottomright.curvepath" : "exclamationmark.triangle.fill"
    }

    private var tint: Color {
        if navigation.finished || navigation.onCourse { return .workoutVolt }
        return .orange
    }

    private var title: String {
        if navigation.finished { return String(localized: "Route Complete") }
        if !navigation.joined { return String(localized: "Head to the Route") }
        if !navigation.onCourse { return String(localized: "Off Route") }
        return String(localized: "\(kilometres(navigation.remainingMeters)) km to Go")
    }

    private var detail: String {
        if navigation.finished { return String(localized: "You reached the finish.") }
        if !navigation.joined || !navigation.onCourse { return String(localized: "\(navigation.offCourseMeters) m away") }
        return String(localized: "\(kilometres(navigation.progressMeters)) km done")
    }
}
