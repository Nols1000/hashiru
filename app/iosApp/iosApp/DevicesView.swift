import SwiftUI
import SharedLogic

/// The toolbar button for what is connected, like the one on Fitness's Workout tab: the WalkingPad and the
/// watch light up while they are there.
struct ConnectedDevicesButton: View {
    let state: TreadmillUiState
    let action: () -> Void

    private var watchReachable: Bool { state.workout.companion == .reachable }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if state.isConnecting {
                    ProgressView()
                } else {
                    Image(systemName: state.isConnected ? "figure.walk.treadmill" : "antenna.radiowaves.left.and.right")
                        .foregroundStyle(state.isConnected ? Color.workoutVolt : .primary)
                }
                if watchReachable {
                    Image(systemName: "applewatch")
                        .foregroundStyle(Color.workoutVolt)
                }
            }
        }
        .accessibilityLabel("Connected Devices")
        .accessibilityValue(accessibilityValue)
    }

    private var accessibilityValue: LocalizedStringKey {
        switch (state.isConnected, watchReachable) {
        case (true, true): "WalkingPad and Apple Watch"
        case (true, false): "WalkingPad"
        case (false, true): "Apple Watch"
        default: state.isConnecting ? "Connecting…" : "None"
        }
    }
}

/// The WalkingPad and the watch, with what can be done about each.
struct ConnectedDevicesSheet: View {
    let state: TreadmillUiState
    let actions: TreadmillActions

    @State private var confirmingForget = false
    @Environment(\.dismiss) private var dismiss

    private var needsPairing: Bool { state.canPair && !state.isPaired }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    DeviceRow(title: "WalkingPad", systemImage: "figure.walk.treadmill", status: padStatus, ok: state.isConnected)
                    if state.isConnected {
                        Button("Disconnect", systemImage: "xmark.circle", action: actions.disconnect)
                    } else if state.isConnecting {
                        // A connection to the paired pad waits until it is in range, so it has to be possible to stop.
                        Button("Stop Connecting", systemImage: "xmark.circle", action: actions.disconnect)
                    } else {
                        Button(action: needsPairing ? actions.pair : actions.connect) {
                            Label(needsPairing ? "Pair WalkingPad" : "Connect", systemImage: "link")
                        }
                    }
                    if state.isPaired {
                        Button("Forget WalkingPad", systemImage: "trash", role: .destructive) { confirmingForget = true }
                    }
                    #if DEBUG
                    // Ends the process the way iOS does under memory pressure, which (unlike quitting from
                    // the app switcher or the debugger) keeps Bluetooth state restoration armed.
                    Button("Simulate Termination", systemImage: "bolt.slash") { kill(getpid(), SIGKILL) }
                    #endif
                } footer: {
                    Text("With a WalkingPad, hashiru controls its speed and counts from the belt. Without one, your iPhone counts your steps and distance.")
                }

                Section {
                    DeviceRow(title: "Apple Watch", systemImage: "applewatch", status: watchStatus, ok: state.workout.companion == .reachable)
                } footer: {
                    Text("hashiru on Apple Watch measures your heart rate during workouts.")
                }
            }
            .navigationTitle("Connected Devices")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .confirmationDialog("Forget WalkingPad?", isPresented: $confirmingForget, titleVisibility: .visible) {
                Button("Forget WalkingPad", role: .destructive, action: actions.forget)
            } message: {
                Text("You’ll need to pair it again to use it with hashiru.")
            }
        }
        .presentationDetents([.medium, .large])
    }

    private var padStatus: LocalizedStringKey {
        if state.isConnected { return state.isRunning ? "Belt Running" : "Connected" }
        if state.isConnecting { return "Connecting…" }
        return state.isPaired ? "Not Connected" : "Not Paired"
    }

    private var watchStatus: LocalizedStringKey {
        switch state.workout.companion {
        case .reachable: state.workout.isInProgress && state.workout.heartRateBpm == nil ? "Waiting for Watch" : "Connected"
        case .unreachable: "Watch Away"
        case .appnotinstalled: "App Not Installed"
        default: "No Watch"
        }
    }
}

private struct DeviceRow: View {
    let title: LocalizedStringKey
    let systemImage: String
    let status: LocalizedStringKey
    let ok: Bool

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.title3)
                .foregroundStyle(ok ? Color.workoutVolt : .secondary)
                .frame(width: 32)
            Text(title)
            Spacer()
            Text(status)
                .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
    }
}

#Preview("Connected") {
    ConnectedDevicesSheet(state: previewState(workout: previewWorkout(phase: .active)), actions: TreadmillActions())
}
#Preview("Nothing") {
    ConnectedDevicesSheet(state: previewState(connected: false, paired: false), actions: TreadmillActions())
}
