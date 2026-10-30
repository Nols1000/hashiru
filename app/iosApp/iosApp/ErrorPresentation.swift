import SwiftUI
import SharedLogic

/// What the user sees for a treadmill error. Blocking errors (the pad can't be reached) take over the walk card and
/// turn its button into the fix; a failed command is a short message, except a failed stop, which gets a banner.
struct ErrorPresentation: Equatable {
    enum Fix { case tryAgain, pairAgain, openSettings, none }

    let title: LocalizedStringResource
    let body: LocalizedStringResource?
    let systemImage: String
    let fix: Fix
    /// The platform's own code, for support; never the headline.
    let code: Int?
    let blocking: Bool
    let failedStop: Bool

    init(_ error: any TreadmillError) {
        var body: LocalizedStringResource?
        var systemImage = "exclamationmark.triangle.fill"
        var fix = Fix.tryAgain
        var code: Int?
        var blocking = true
        var failedStop = false
        switch error {
        case is TreadmillErrorBluetoothOff:
            title = "Bluetooth Is Off"
            body = "Turn it on in Control Center or Settings to find your WalkingPad."
            systemImage = "antenna.radiowaves.left.and.right.slash"
        case is TreadmillErrorBluetoothUnauthorized:
            title = "Allow Bluetooth Access"
            body = "hashiru uses Bluetooth only to talk to your WalkingPad."
            systemImage = "antenna.radiowaves.left.and.right.slash"
            fix = .openSettings
        case is TreadmillErrorBluetoothUnsupported:
            title = "This Device Has No Bluetooth"
            body = "hashiru needs Bluetooth to connect to a WalkingPad."
            systemImage = "antenna.radiowaves.left.and.right.slash"
            fix = .none
        case let error as TreadmillErrorPadNotFound:
            title = "WalkingPad Not Found"
            body = "Switch it on and stay within a few metres, then try again."
            systemImage = "magnifyingglass"
            code = error.code?.intValue
        case let error as TreadmillErrorConnectionFailed:
            title = "Couldn’t Connect"
            body = "Your WalkingPad didn’t answer. Switch it off and on again, then try again."
            systemImage = "link.badge.plus"
            code = error.code?.intValue
        case is TreadmillErrorNotAWalkingPad:
            title = "This Isn’t a WalkingPad"
            body = "The device you chose doesn’t respond like a WalkingPad. Pair yours again."
            systemImage = "link"
            fix = .pairAgain
        case is TreadmillErrorPairAgain:
            title = "Pair Your WalkingPad Again"
            body = "This iPhone no longer recognizes it, for example after restoring from a backup."
            systemImage = "link"
            fix = .pairAgain
        case let error as TreadmillErrorCommandFailed:
            blocking = false
            fix = .none
            switch error.command {
            case is TreadmillCommandStopBelt:
                title = "Couldn’t Stop the Belt"
                body = "Press the button on the WalkingPad or use its remote."
                systemImage = "stop.circle.fill"
                fix = .tryAgain
                failedStop = true
            case is TreadmillCommandStartBelt:
                title = "Couldn’t start the belt"
            default:
                title = "Speed change didn’t reach the WalkingPad"
            }
        default:
            title = "Couldn’t Connect"
            body = "Your WalkingPad didn’t answer. Switch it off and on again, then try again."
        }
        self.body = body
        self.systemImage = systemImage
        self.fix = fix
        self.code = code
        self.blocking = blocking
        self.failedStop = failedStop
    }
}

extension TreadmillUiState {
    /// The error as shown, or nil; Equatable where the Kotlin error is not, for animations and change handlers.
    var errorPresentation: ErrorPresentation? { error.map(ErrorPresentation.init) }
}

/// A feature that is off or failed, on the screen that owns it: icon, title, a line of body, and what to do about it.
struct NoticeRow: View {
    let title: LocalizedStringResource
    var message: LocalizedStringResource?
    let systemImage: String
    var tint: Color = .accentColor
    var action: LocalizedStringResource?
    var onAction: (() -> Void)?
    var onDismiss: (() -> Void)?

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Image(systemName: systemImage)
                .font(.title3)
                .foregroundStyle(tint)
                .frame(width: 28)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline.weight(.semibold))
                if let message {
                    Text(message)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if let action, let onAction {
                Button(action: onAction) { Text(action) }
                    .font(.subheadline.weight(.semibold))
            }
            if let onDismiss {
                Button("Dismiss", systemImage: "xmark", action: onDismiss)
                    .labelStyle(.iconOnly)
                    .foregroundStyle(.secondary)
            }
        }
        .padding()
        .background(Color.card, in: .rect(cornerRadius: 20))
    }
}
