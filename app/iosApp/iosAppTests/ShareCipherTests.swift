import CryptoKit
import Security
import SharedLogic
import XCTest
@testable import hashiru

final class ShareCipherTests: XCTestCase {
    private let r = [UInt8](repeating: 0x11, count: 32)
    private let s = [UInt8](repeating: 0x22, count: 32)

    func testRawSignatureOfFullLengthIntegers() {
        XCTAssertEqual(ShareCipher.rawSignature(der(r, s)), Data(r + s))
    }

    func testRawSignatureDropsTheSignBytes() {
        let highR = [0x80] + [UInt8](repeating: 0x33, count: 31)
        let highS = [0xFF] + [UInt8](repeating: 0x44, count: 31)
        XCTAssertEqual(ShareCipher.rawSignature(der([0] + highR, [0] + highS)), Data(highR + highS))
    }

    func testRawSignaturePadsShortIntegers() {
        let shortR = [UInt8](repeating: 0x55, count: 31)
        let shortS: [UInt8] = [0x01]
        XCTAssertEqual(
            ShareCipher.rawSignature(der(shortR, shortS)),
            Data([0] + shortR + [UInt8](repeating: 0, count: 31) + shortS)
        )
    }

    func testRawSignatureRejectsMalformedDer() {
        let valid = [UInt8](der(r, s))
        var wrongSequenceTag = valid
        wrongSequenceTag[0] = 0x31
        var wrongLength = valid
        wrongLength[1] += 1
        var wrongIntegerTag = valid
        wrongIntegerTag[2] = 0x04
        var rPastTheEnd = valid
        rPastTheEnd[3] = 0x45
        let cases: [String: [UInt8]] = [
            "empty": [],
            "too short": [0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01],
            "sequence tag": wrongSequenceTag,
            "sequence length": wrongLength,
            "integer tag": wrongIntegerTag,
            "r past the end": rPastTheEnd,
            "trailing byte": [UInt8](sequence(integer(r) + integer(s) + [0])),
            "empty r": [UInt8](der([], s)),
            "empty s": [UInt8](der(r, [])),
            "r too long": [UInt8](der([0x01] + r, s)),
            "s too long": [UInt8](der(r, [0x01] + s)),
        ]
        for (name, bytes) in cases {
            XCTAssertNil(ShareCipher.rawSignature(Data(bytes)), name)
        }
    }

    func testSignatureVerifiesWithThePublicKey() throws {
        try XCTSkipUnless(keychainIsUsable(), "The test host can't use the keychain")
        let cipher = ShareCipher()
        let alias = "hashiru.tests.\(UUID().uuidString)"
        let publicKey = cipher.createSigningKey(alias: alias)
        defer { cipher.putSecret(alias: alias, value: nil) }
        let message = Data("snapshot".utf8)

        let signature = try XCTUnwrap(cipher.sign(alias: alias, data: kotlinBytes(message)))

        XCTAssertEqual(signature.size, 64)
        let key = try P256.Signing.PublicKey(x963Representation: data(publicKey))
        let ecdsa = try P256.Signing.ECDSASignature(rawRepresentation: data(signature))
        XCTAssertTrue(key.isValidSignature(ecdsa, for: message))
        XCTAssertFalse(key.isValidSignature(ecdsa, for: Data("other".utf8)))
    }

    /// An unsigned simulator build has no keychain access group, and the cipher's key calls stop the app then.
    private func keychainIsUsable() -> Bool {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "hashiru.tests",
            kSecAttrAccount as String: UUID().uuidString,
        ]
        var item = query
        item[kSecValueData as String] = Data([1])
        let status = SecItemAdd(item as CFDictionary, nil)
        SecItemDelete(query as CFDictionary)
        return status == errSecSuccess
    }

    private func der(_ r: [UInt8], _ s: [UInt8]) -> Data { sequence(integer(r) + integer(s)) }

    private func integer(_ bytes: [UInt8]) -> [UInt8] { [0x02, UInt8(bytes.count)] + bytes }

    private func sequence(_ body: [UInt8]) -> Data { Data([0x30, UInt8(body.count)] + body) }

    private func kotlinBytes(_ data: Data) -> KotlinByteArray {
        let result = KotlinByteArray(size: Int32(data.count))
        for (index, byte) in data.enumerated() {
            result.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return result
    }

    private func data(_ bytes: KotlinByteArray) -> Data {
        Data((0..<bytes.size).map { UInt8(bitPattern: bytes.get(index: $0)) })
    }
}
