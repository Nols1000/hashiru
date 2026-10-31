import CryptoKit
import Foundation
import Security
import SharedLogic

final class ShareCipher: NSObject, ShareCipherPort {
    private let service = "hashiru.share"

    func randomBytes(count: Int32) -> KotlinByteArray {
        precondition(count >= 0)
        var bytes = [UInt8](repeating: 0, count: Int(count))
        guard !bytes.isEmpty else { return kotlinBytes(Data()) }
        let status = bytes.withUnsafeMutableBytes { buffer in
            SecRandomCopyBytes(kSecRandomDefault, buffer.count, buffer.baseAddress!)
        }
        precondition(status == errSecSuccess, "Secure random generation failed")
        return kotlinBytes(Data(bytes))
    }

    func hmacSha512(key: KotlinByteArray, data: KotlinByteArray) -> KotlinByteArray {
        let code = HMAC<SHA512>.authenticationCode(for: data.data, using: SymmetricKey(data: key.data))
        return kotlinBytes(Data(code))
    }

    func aesGcmSeal(
        key: KotlinByteArray,
        nonce: KotlinByteArray,
        aad: KotlinByteArray,
        plaintext: KotlinByteArray
    ) -> KotlinByteArray {
        guard let gcmNonce = try? AES.GCM.Nonce(data: nonce.data) else {
            preconditionFailure("Invalid AES-GCM nonce")
        }
        let sealed = try! AES.GCM.seal(
            plaintext.data,
            using: SymmetricKey(data: key.data),
            nonce: gcmNonce,
            authenticating: aad.data
        )
        var ciphertextAndTag = sealed.ciphertext
        ciphertextAndTag.append(sealed.tag)
        return kotlinBytes(ciphertextAndTag)
    }

    func createSigningKey(alias: String) -> KotlinByteArray {
        let tag = Data(alias.utf8)
        SecItemDelete(keyQuery(tag: tag) as CFDictionary)

        let privateAttributes: [String: Any] = [
            kSecAttrIsPermanent as String: true,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecAttrApplicationTag as String: tag,
        ]
        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecPrivateKeyAttrs as String: privateAttributes,
        ]
        var creationError: Unmanaged<CFError>?
        guard let privateKey = SecKeyCreateRandomKey(attributes as CFDictionary, &creationError) else {
            let error = creationError?.takeRetainedValue()
            preconditionFailure("Could not create share signing key: \(String(describing: error))")
        }
        var exportError: Unmanaged<CFError>?
        guard let publicKey = SecKeyCopyPublicKey(privateKey),
              let publicBytes = SecKeyCopyExternalRepresentation(publicKey, &exportError) else {
            let error = exportError?.takeRetainedValue()
            preconditionFailure("Could not export share public key: \(String(describing: error))")
        }
        let data = publicBytes as Data
        precondition(data.count == 65 && data.first == 0x04, "Unexpected P-256 public key representation")
        return kotlinBytes(data)
    }

    func sign(alias: String, data: KotlinByteArray) -> KotlinByteArray? {
        let tag = Data(alias.utf8)
        var query = keyQuery(tag: tag)
        query[kSecAttrKeyClass as String] = kSecAttrKeyClassPrivate
        query[kSecReturnRef as String] = true
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let item else { return nil }
        let privateKey = item as! SecKey

        var signingError: Unmanaged<CFError>?
        guard let der = SecKeyCreateSignature(
            privateKey,
            .ecdsaSignatureMessageX962SHA256,
            data.data as CFData,
            &signingError
        ) else { return nil }
        guard let raw = Self.rawSignature(der as Data) else { return nil }
        return kotlinBytes(raw)
    }

    func putSecret(alias: String, value: KotlinByteArray?) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: alias,
        ]
        SecItemDelete(query as CFDictionary)
        if let value {
            var item = query
            item[kSecValueData as String] = value.data
            // iOS relaunches the app in the background (Bluetooth restoration,
            // HealthKit) mostly while the phone is locked; the share must survive that.
            item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            let status = SecItemAdd(item as CFDictionary, nil)
            precondition(status == errSecSuccess, "Could not store share secret (status \(status))")
        } else {
            SecItemDelete(keyQuery(tag: Data(alias.utf8)) as CFDictionary)
        }
    }

    func secret(alias: String) -> KotlinByteArray? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: alias,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else { return nil }
        // Older builds stored the secret as WhenUnlocked; move it to the class putSecret uses.
        let match: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: alias,
        ]
        let accessible = [kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        SecItemUpdate(match as CFDictionary, accessible as CFDictionary)
        return kotlinBytes(data)
    }

    private func keyQuery(tag: Data) -> [String: Any] {
        [
            kSecClass as String: kSecClassKey,
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrApplicationTag as String: tag,
        ]
    }

    private static func rawSignature(_ der: Data) -> Data? {
        let bytes = [UInt8](der)
        guard bytes.count >= 8, bytes[0] == 0x30, Int(bytes[1]) == bytes.count - 2 else { return nil }
        let offset = 2
        guard bytes[offset] == 0x02 else { return nil }
        let rLength = Int(bytes[offset + 1])
        let rStart = offset + 2
        let sTag = rStart + rLength
        guard rLength > 0, sTag + 2 <= bytes.count, bytes[sTag] == 0x02 else { return nil }
        let sLength = Int(bytes[sTag + 1])
        let sStart = sTag + 2
        guard sLength > 0, sStart + sLength == bytes.count else { return nil }

        guard let r = rawInteger(Array(bytes[rStart..<sTag])),
              let s = rawInteger(Array(bytes[sStart..<bytes.count])) else { return nil }
        return Data(r + s)
    }

    private static func rawInteger(_ integer: [UInt8]) -> [UInt8]? {
        var magnitude = integer
        while magnitude.count > 32 && magnitude.first == 0 { magnitude.removeFirst() }
        guard !magnitude.isEmpty, magnitude.count <= 32 else { return nil }
        return [UInt8](repeating: 0, count: 32 - magnitude.count) + magnitude
    }
}

private extension KotlinByteArray {
    var data: Data {
        var bytes = [UInt8](repeating: 0, count: Int(size))
        for index in bytes.indices {
            bytes[index] = UInt8(bitPattern: get(index: Int32(index)))
        }
        return Data(bytes)
    }
}

private func kotlinBytes(_ data: Data) -> KotlinByteArray {
    precondition(data.count <= Int(Int32.max))
    let result = KotlinByteArray(size: Int32(data.count))
    for (index, byte) in data.enumerated() {
        result.set(index: Int32(index), value: Int8(bitPattern: byte))
    }
    return result
}
