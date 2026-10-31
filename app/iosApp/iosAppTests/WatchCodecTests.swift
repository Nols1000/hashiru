import Foundation
import XCTest

/// `WatchCodec.swift` is the watch app's; this target compiles it in. The vectors are
/// fixtures/watch-codec-vectors.json, which `WatchMessageCodecTest` checks the phone's codec against.
/// The watch only encodes commands and only decodes telemetry and acks.
final class WatchCodecTests: XCTestCase {
    private struct Vectors: Decodable {
        var messages: [Vector]
        var rejected: [String]
    }

    private struct Vector: Decodable {
        var name: String
        var hex: String
        var telemetry: Telemetry?
        var command: Command?
        var ack: Ack?
    }

    private struct Telemetry: Decodable {
        var phase: String
        var isConnected: Bool
        var isRunning: Bool
        var speedTenths: Int
        var durationSeconds: Int
        var distanceMeters: Int
        var heartRateBpm: Int?
        var zone: String?
        var canResume: Bool
        var navigation: Navigation?
    }

    private struct Navigation: Decodable {
        var onCourse: Bool
        var offCourseMeters: Int
        var remainingMeters: Int
        var joined: Bool
        var finished: Bool
    }

    private struct Command: Decodable {
        var kind: String
        var tenthsKmh: Int?
        var paused: Bool?
    }

    private struct Ack: Decodable {
        var ok: Bool
    }

    private func vectors() throws -> Vectors {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "watch-codec-vectors", withExtension: "json"))
        return try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: url))
    }

    func testTelemetryDecodes() throws {
        let vectors = try vectors().messages.filter { $0.telemetry != nil }
        XCTAssertFalse(vectors.isEmpty)
        for vector in vectors {
            let t = try XCTUnwrap(vector.telemetry)
            let expected = WristTelemetry(
                phase: try XCTUnwrap(WorkoutPhase(rawValue: t.phase), vector.name),
                isConnected: t.isConnected,
                isRunning: t.isRunning,
                speedTenths: t.speedTenths,
                durationSeconds: t.durationSeconds,
                distanceMeters: t.distanceMeters,
                heartRateBpm: t.heartRateBpm,
                zone: try t.zone.map { try XCTUnwrap(HeartRateZone(rawValue: $0), vector.name) },
                canResume: t.canResume,
                navigation: t.navigation.map {
                    WristNavigation(
                        onCourse: $0.onCourse, offCourseMeters: $0.offCourseMeters, remainingMeters: $0.remainingMeters,
                        joined: $0.joined, finished: $0.finished
                    )
                }
            )
            XCTAssertEqual(WatchCodec.decodeTelemetry(try bytes(vector.hex)), expected, vector.name)
        }
    }

    func testCommandsEncode() throws {
        let vectors = try vectors().messages.filter { $0.command != nil }
        XCTAssertFalse(vectors.isEmpty)
        for vector in vectors {
            let c = try XCTUnwrap(vector.command)
            let command: WristCommand
            switch c.kind {
            case "SetSpeed": command = .setSpeed(tenths: try XCTUnwrap(c.tenthsKmh, vector.name))
            case "SetPaused": command = .setPaused(try XCTUnwrap(c.paused, vector.name))
            case "StopBelt": command = .stopBelt
            default:
                XCTFail("Unknown command \(c.kind) in \(vector.name)")
                continue
            }
            XCTAssertEqual(WatchCodec.encode(command), try bytes(vector.hex), vector.name)
        }
    }

    func testAcksDecode() throws {
        let vectors = try vectors().messages.filter { $0.ack != nil }
        XCTAssertFalse(vectors.isEmpty)
        for vector in vectors {
            XCTAssertEqual(WatchCodec.decodeAck(try bytes(vector.hex)), try XCTUnwrap(vector.ack).ok, vector.name)
        }
    }

    func testRejectedInputDecodesToNothing() throws {
        for hex in try vectors().rejected {
            let data = try bytes(hex)
            XCTAssertNil(WatchCodec.decodeTelemetry(data), hex)
            XCTAssertNil(WatchCodec.decodeAck(data), hex)
        }
    }

    private func bytes(_ hex: String) throws -> Data {
        let digits = Array(hex.utf8)
        guard digits.count.isMultiple(of: 2) else { throw CocoaError(.coderInvalidValue) }
        return try Data(stride(from: 0, to: digits.count, by: 2).map { index in
            guard let byte = UInt8(String(decoding: digits[index...index + 1], as: UTF8.self), radix: 16) else {
                throw CocoaError(.coderInvalidValue)
            }
            return byte
        })
    }
}
