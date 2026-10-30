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
    /// Where the workout stands on its route; nil without one.
    var navigation: WristNavigation?
}

struct WristNavigation: Equatable {
    var onCourse: Bool
    var offCourseMeters: Int
    var remainingMeters: Int
    /// False while heading to the route, before first reaching it.
    var joined: Bool
    var finished: Bool
}

/// The route the workout follows, simplified for the watch: `CourseCodec.encodeForWatch` in the shared code.
struct WristCourse: Equatable {
    var id: String
    var name: String
    var distanceMeters: Int
    var points: [(lat: Double, lon: Double)]

    static func == (a: WristCourse, b: WristCourse) -> Bool { a.id == b.id }
}

enum WristCommand {
    case setSpeed(tenths: Int)
    case setPaused(Bool)
    case stopBelt
}

/// What the phone tells this watch's primary workout session, over HealthKit's mirrored session.
enum SessionMessage {
    /// The walk began at `startMs`; the session starts dated back to then.
    case start(id: String, startMs: Int64, indoor: Bool, padCounts: Bool)
    /// The pad's distance and steps for the walk so far.
    case counted(atMs: Int64, meters: Int, steps: Int)
    /// End the walk at `endMs` and save it with the phone's ID and sync version.
    case end(endMs: Int64, id: String, syncVersion: Int64)
    case discard
}

/// The session's totals, sent back to the phone.
struct SessionTotals {
    var seconds = 0
    var meters: Int?
    var steps: Int?
    var activeKcal: Double?
    var totalKcal: Double?
    var latestBpm: Int?
    var avgBpm: Int?
    var maxBpm: Int?
}

/// The phone's copy is `SessionMessageCodec` in the shared Kotlin code; its KDoc and `SessionMessageCodecTest`
/// list the reference strings.
enum SessionCodec {
    private static let version = "1"

    static func decode(_ data: Data) -> SessionMessage? {
        guard let text = String(data: data, encoding: .utf8) else { return nil }
        let f = text.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
        guard f.count >= 3, f[1] == version else { return nil }
        switch f[0] {
        case "S":
            guard f.count == 7, let start = Int64(f[3]) else { return nil }
            return .start(id: f[2], startMs: start, indoor: f[5] == "Indoor", padCounts: f[6] == "Pad")
        case "N":
            guard f.count == 5, let at = Int64(f[2]), let meters = Int(f[3]), let steps = Int(f[4]) else { return nil }
            return .counted(atMs: at, meters: meters, steps: steps)
        case "E":
            guard f.count == 5, let end = Int64(f[2]), let syncVersion = Int64(f[4]) else { return nil }
            return .end(endMs: end, id: f[3], syncVersion: syncVersion)
        case "D":
            return .discard
        default:
            return nil
        }
    }

    static func encode(_ totals: SessionTotals) -> Data {
        func text(_ value: Int?) -> String { value.map(String.init) ?? "" }
        func tenths(_ kcal: Double?) -> String { kcal.map { String(Int(($0 * 10).rounded())) } ?? "" }
        let fields = [
            "T", version, String(totals.seconds), text(totals.meters), text(totals.steps), tenths(totals.activeKcal),
            tenths(totals.totalKcal), text(totals.latestBpm), text(totals.avgBpm), text(totals.maxBpm),
        ]
        return Data(fields.joined(separator: "|").utf8)
    }

    static func encodeResult(ok: Bool, message: String) -> Data {
        Data(["R", version, ok ? "1" : "0", message.replacingOccurrences(of: "|", with: "")].joined(separator: "|").utf8)
    }
}

enum WatchCodec {
    /// Version 2 added the route to telemetry.
    private static let version = "2"

    static func encode(_ command: WristCommand) -> Data {
        let fields: [String] = switch command {
        case .setSpeed(let tenths): ["C", version, "SetSpeed", String(tenths)]
        case .setPaused(let paused): ["C", version, "SetPaused", flag(paused)]
        case .stopBelt: ["C", version, "StopBelt", ""]
        }
        return Data(fields.joined(separator: "|").utf8)
    }

    static func decodeTelemetry(_ data: Data) -> WristTelemetry? {
        guard let f = fields(data, kind: "T"), f.count == 16,
              let phase = WorkoutPhase(rawValue: f[2]),
              let connected = flag(f[3]), let running = flag(f[4]), let canResume = flag(f[10]),
              let speed = Int(f[5]), let duration = Int(f[6]), let distance = Int(f[7]) else { return nil }
        var navigation: WristNavigation?
        if let onCourse = flag(f[11]), let off = Int(f[12]), let remaining = Int(f[13]), let joined = flag(f[14]), let finished = flag(f[15]) {
            navigation = WristNavigation(onCourse: onCourse, offCourseMeters: off, remainingMeters: remaining, joined: joined, finished: finished)
        }
        return WristTelemetry(
            phase: phase, isConnected: connected, isRunning: running, speedTenths: speed, durationSeconds: duration,
            distanceMeters: distance, heartRateBpm: Int(f[8]), zone: HeartRateZone(rawValue: f[9]), canResume: canResume,
            navigation: navigation
        )
    }

    /// `R|1|id|name|distance|lat,lon;lat,lon…`, with its own version.
    static func decodeCourse(_ data: Data) -> WristCourse? {
        guard let text = String(data: data, encoding: .utf8) else { return nil }
        let f = text.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
        guard f.count == 6, f[0] == "R", f[1] == "1", let distance = Int(f[4]) else { return nil }
        let points = f[5].split(separator: ";").compactMap { pair -> (lat: Double, lon: Double)? in
            let v = pair.split(separator: ",")
            guard v.count == 2, let lat = Double(v[0]), let lon = Double(v[1]) else { return nil }
            return (lat, lon)
        }
        return WristCourse(id: f[2], name: f[3], distanceMeters: distance, points: points)
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
