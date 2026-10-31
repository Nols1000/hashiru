import Foundation

enum ShareConfig {
    /// Where the phone sends sealed frames; DEBUG builds can point at a local relay.
    #if DEBUG
    static let relayUrl = ProcessInfo.processInfo.environment["HASHIRU_RELAY"] ?? "https://hashiru.tw-esport.de/api"
    static let viewerUrl = ProcessInfo.processInfo.environment["HASHIRU_VIEWER"] ?? "https://hashiru.tw-esport.de"
    #else
    static let relayUrl = "https://hashiru.tw-esport.de/api"
    static let viewerUrl = "https://hashiru.tw-esport.de"
    #endif
}
