import Foundation

// The watch's copy of the phone's wire format, `WatchMessageCodec` in the shared Kotlin code. Keep both in
// step; its KDoc and `WatchMessageCodecTest` list the reference strings.

enum WorkoutPhase: String { case idle = "Idle", active = "Active", paused = "Paused" }

enum HeartRateZone: String { case warmup = "Warmup", easy = "Easy", aerobic = "Aerobic", threshold = "Threshold", maximum = "Maximum" }

struct WristTelemetry: Equatable {
    var phase = WorkoutPhase.idle
    var isConnected = false
    var isRunning = false
    var speedTenths = 0
    var durationSeconds = 0
    var distanceMeters = 0
    var heartRateBpm: Int?
    var zone: HeartRateZone?
    var canResume = false
}

enum WristCommand {
    case setSpeed(tenths: Int)
    case setPaused(Bool)
    case stopBelt
}

enum WatchCodec {
    private static let version = "1"

    static func encode(_ command: WristCommand) -> Data {
        let fields: [String] = switch command {
        case .setSpeed(let tenths): ["C", version, "SetSpeed", String(tenths)]
        case .setPaused(let paused): ["C", version, "SetPaused", flag(paused)]
        case .stopBelt: ["C", version, "StopBelt", ""]
        }
        return Data(fields.joined(separator: "|").utf8)
    }

    static func encodeHeartRate(epochMs: Int64, bpm: Int) -> Data {
        Data(["H", version, String(epochMs), String(bpm)].joined(separator: "|").utf8)
    }

    static func decodeTelemetry(_ data: Data) -> WristTelemetry? {
        guard let f = fields(data, kind: "T"), f.count == 11,
              let phase = WorkoutPhase(rawValue: f[2]),
              let connected = flag(f[3]), let running = flag(f[4]), let canResume = flag(f[10]),
              let speed = Int(f[5]), let duration = Int(f[6]), let distance = Int(f[7]) else { return nil }
        return WristTelemetry(
            phase: phase, isConnected: connected, isRunning: running, speedTenths: speed, durationSeconds: duration,
            distanceMeters: distance, heartRateBpm: Int(f[8]), zone: HeartRateZone(rawValue: f[9]), canResume: canResume
        )
    }

    static func decodeAck(_ data: Data) -> Bool? {
        guard let f = fields(data, kind: "A"), f.count == 3 else { return nil }
        return flag(f[2])
    }

    private static func fields(_ data: Data, kind: String) -> [String]? {
        guard let text = String(data: data, encoding: .utf8) else { return nil }
        let f = text.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
        return f.count >= 3 && f[0] == kind && f[1] == version ? f : nil
    }

    private static func flag(_ value: Bool) -> String { value ? "1" : "0" }

    private static func flag(_ text: String) -> Bool? {
        switch text {
        case "1": true
        case "0": false
        default: nil
        }
    }
}
