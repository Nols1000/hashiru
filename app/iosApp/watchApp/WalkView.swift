import SwiftUI
import WatchKit

extension HeartRateZone {
    /// Cool to hot, matching the phone app.
    var color: Color {
        switch self {
        case .warmup: Color(red: 0.54, green: 0.71, blue: 0.97)
        case .easy: Color(red: 0.48, green: 0.88, blue: 0.63)
        case .aerobic: Color(red: 0.83, green: 1.0, blue: 0.23)
        case .threshold: Color(red: 1.0, green: 0.71, blue: 0.28)
        case .maximum: Color(red: 1.0, green: 0.42, blue: 0.37)
        }
    }

    var title: LocalizedStringResource {
        switch self {
        case .warmup: "Warm-Up"
        case .easy: "Easy"
        case .aerobic: "Aerobic"
        case .threshold: "Threshold"
        case .maximum: "Maximum"
        }
    }
}

/// Black canvas with the heart rate first: the zone colour reads at a glance mid-stride. The Digital Crown
/// sets the belt speed in 0.1 km/h detents, with a stronger click at every whole km/h; Stop is a full-width
/// red button that stops the belt at once.
struct WalkView: View {
    @ObservedObject var model: WalkModel

    @State private var crown = 0.0
    @FocusState private var crownFocused: Bool

    private var telemetry: WristTelemetry { model.telemetry }
    private var bpm: Int? { model.localBpm ?? telemetry.heartRateBpm }
    private var shownTenths: Int { model.targetTenths ?? telemetry.speedTenths }

    var body: some View {
        ScrollView {
            VStack(spacing: 6) {
                heartRate
                Text("\(speed(shownTenths)) km/h")
                    .font(.system(.title3, design: .rounded, weight: .semibold))
                    .monospacedDigit()
                    .foregroundStyle(model.targetTenths == nil ? .primary : Color.yellow)
                Text("\(duration(telemetry.durationSeconds)) · \(kilometres(telemetry.distanceMeters)) km")
                    .font(.system(.footnote, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
                if let status {
                    Label { Text(status.text) } icon: { Image(systemName: status.symbol) }
                        .font(.footnote)
                        .foregroundStyle(status.critical ? Color.red : Color.orange)
                        .multilineTextAlignment(.center)
                }
                Button(action: model.togglePause) {
                    Text(pauseTitle)
                        .frame(maxWidth: .infinity)
                }
                .disabled(!canTogglePause)
                Button(role: .destructive, action: model.stopBelt) {
                    Label("Stop", systemImage: "stop.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(.red)
                .disabled(!model.phoneReachable || !telemetry.isRunning)
            }
            .padding(.horizontal, 4)
        }
        .background(Color.black.ignoresSafeArea())
        .focusable(telemetry.isRunning && model.controlsEnabled)
        .focused($crownFocused)
        .digitalCrownRotation(
            $crown, from: Double(WalkModel.minTenths) / 10, through: Double(WalkModel.maxTenths) / 10, by: 0.1,
            sensitivity: .low, isContinuous: false, isHapticFeedbackEnabled: true
        )
        .onChange(of: crown) { _, value in
            let tenths = Int((value * 10).rounded())
            guard tenths != shownTenths else { return }
            if tenths == WalkModel.minTenths || tenths == WalkModel.maxTenths {
                WKInterfaceDevice.current().play(.failure)
            } else if tenths % 10 == 0 {
                WKInterfaceDevice.current().play(.click)
            }
            model.setTarget(tenths)
        }
        .onChange(of: telemetry.speedTenths, initial: true) { _, tenths in
            // Follow the pad unless the crown is waiting for its own target.
            if model.targetTenths == nil, tenths > 0 { crown = Double(tenths) / 10 }
        }
        .onChange(of: telemetry.isRunning) { _, running in crownFocused = running }
    }

    private var heartRate: some View {
        VStack(spacing: 0) {
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(bpm.map(String.init) ?? "--")
                    .font(.system(size: 48, weight: .semibold, design: .rounded))
                    .monospacedDigit()
                    .contentTransition(.numericText())
                Image(systemName: "heart.fill")
                    .font(.title3)
                    .symbolEffect(.pulse, isActive: bpm != nil)
            }
            .foregroundStyle(telemetry.zone?.color ?? .red)
            Text(telemetry.zone?.title ?? "BPM")
                .font(.system(.caption, design: .rounded, weight: .semibold))
                .foregroundStyle(telemetry.zone?.color ?? .secondary)
        }
        .accessibilityElement(children: .combine)
    }

    /// Most pressing first: without the phone nothing else can be fixed from the wrist. Red only for a failed stop.
    private var status: (text: LocalizedStringResource, symbol: String, critical: Bool)? {
        if !model.phoneReachable { return ("Open hashiru on your iPhone", "iphone.slash", false) }
        if !telemetry.isConnected {
            return telemetry.phase == .idle
                ? ("WalkingPad not connected", "antenna.radiowaves.left.and.right.slash", false)
                : ("Reconnecting to the WalkingPad", "antenna.radiowaves.left.and.right.slash", false)
        }
        guard let error = model.error else { return nil }
        return (error, model.stopFailed ? "stop.circle" : "exclamationmark.arrow.circlepath", model.stopFailed)
    }

    private var pauseTitle: LocalizedStringResource {
        switch telemetry.phase {
        case .active: "Pause"
        case .idle: "Start"
        case .paused: "Resume"
        }
    }

    private var canTogglePause: Bool {
        guard model.controlsEnabled else { return false }
        return telemetry.phase == .active || telemetry.phase == .idle || telemetry.canResume
    }

    private func speed(_ tenths: Int) -> String { "\(tenths / 10).\(tenths % 10)" }

    private func kilometres(_ meters: Int) -> String { String(format: "%d.%02d", meters / 1000, meters % 1000 / 10) }

    private func duration(_ seconds: Int) -> String {
        let h = seconds / 3600, m = seconds % 3600 / 60, s = seconds % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }
}

#Preview {
    WalkView(model: .shared)
}
