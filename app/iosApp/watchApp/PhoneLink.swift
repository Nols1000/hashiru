import Foundation
import WatchConnectivity

/// The watch's side of WatchConnectivity: live metrics as message data, the walk's phase and the route it follows
/// as the application context, and commands that the phone answers with an ack.
final class PhoneLink: NSObject, WCSessionDelegate {
    /// How long a command may take; past it the user is told to act on the pad.
    static let ackTimeout: TimeInterval = 1.5

    /// Called on the main queue.
    var onTelemetry: ((WristTelemetry) -> Void)?
    /// The route of the workout in progress, or nil without one.
    var onCourse: ((WristCourse?) -> Void)?
    var onReachability: ((Bool) -> Void)?

    private let session = WCSession.default

    func activate() {
        session.delegate = self
        session.activate()
    }

    var isReachable: Bool { session.activationState == .activated && session.isReachable }

    /// Sends [command] and reports whether the phone carried it out within [ackTimeout].
    func send(_ command: WristCommand) async -> Bool {
        guard isReachable else { return false }
        return await withCheckedContinuation { continuation in
            let once = Once(continuation)
            session.sendMessageData(WatchCodec.encode(command), replyHandler: { reply in
                once.resume(WatchCodec.decodeAck(reply) ?? false)
            }, errorHandler: { _ in
                once.resume(false)
            })
            DispatchQueue.main.asyncAfter(deadline: .now() + Self.ackTimeout) { once.resume(false) }
        }
    }

    // MARK: - WCSessionDelegate

    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        let context = session.receivedApplicationContext
        DispatchQueue.main.async {
            self.onReachability?(self.isReachable)
            self.applyContext(context)
        }
    }

    func sessionReachabilityDidChange(_ session: WCSession) {
        DispatchQueue.main.async { self.onReachability?(self.isReachable) }
    }

    func session(_ session: WCSession, didReceiveMessageData messageData: Data) {
        guard let telemetry = WatchCodec.decodeTelemetry(messageData) else { return }
        DispatchQueue.main.async { self.onTelemetry?(telemetry) }
    }

    func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        DispatchQueue.main.async { self.applyContext(applicationContext) }
    }

    private func applyContext(_ context: [String: Any]) {
        onCourse?((context["r"] as? Data).flatMap(WatchCodec.decodeCourse))
        guard let data = context["s"] as? Data, let telemetry = WatchCodec.decodeTelemetry(data) else { return }
        onTelemetry?(telemetry)
    }
}

/// Resumes a continuation once, whichever of reply, error or timeout comes first.
private final class Once: @unchecked Sendable {
    private var continuation: CheckedContinuation<Bool, Never>?
    private let lock = NSLock()

    init(_ continuation: CheckedContinuation<Bool, Never>) { self.continuation = continuation }

    func resume(_ value: Bool) {
        lock.lock()
        let pending = continuation
        continuation = nil
        lock.unlock()
        pending?.resume(returning: value)
    }
}
