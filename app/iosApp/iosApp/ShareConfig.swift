import Foundation

enum ShareConfig {
    /// A relay to use instead of the default; DEBUG builds read it from `HASHIRU_RELAY` to point at a local relay.
    #if DEBUG
    static let relayOverride = ProcessInfo.processInfo.environment["HASHIRU_RELAY"]
    static let viewerUrl = ProcessInfo.processInfo.environment["HASHIRU_VIEWER"] ?? "https://hashiru.tw-esport.de"
    #else
    static let relayOverride: String? = nil
    static let viewerUrl = "https://hashiru.tw-esport.de"
    #endif
    /// Where the phone sends sealed frames.
    static let relayUrl = relayOverride ?? "https://hashiru.tw-esport.de/api"
}
