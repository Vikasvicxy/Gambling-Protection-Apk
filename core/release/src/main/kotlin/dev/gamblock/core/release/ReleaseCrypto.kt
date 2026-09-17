package dev.gamblock.core.release

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Signing/verification primitives for blocklist releases.
 *
 * Scheme: **ECDSA P-256 with SHA-256** (`SHA256withECDSA`).
 *
 * Why not Ed25519? Ed25519 is available on Android only from API 33 (the platform
 * implementation shipped in Android 13). This app targets minSdk 26 (Android 8.0), so
 * using Ed25519 would force a bundled/third-party implementation on API 26-32, adding a
 * large dependency and a new supply-chain + license surface. ECDSA P-256 is available on
 * every supported API level through the platform provider and on every JVM (CI), needs
 * no extra dependency, and its security is well understood. The manifest carries an
 * explicit [SignatureMetadata.algorithm] so the scheme can be extended to Ed25519 in the
 * future (e.g. once the supported minimum rises, or alongside a second key) without
 * breaking older clients: clients simply evaluate the algorithm field against their
 * allowed set.
 *
 * KEY HYGIENE (mandatory):
 *  - the private key NEVER enters the repository, the APK, or any log;
 *  - only the public key is embedded in the app (asset `update_signing_public_key.pem`);
 *  - keyId = first 16 hex chars of SHA-256(DER public key) lets clients confirm they are
 *    verifying with the exact key the signer intended, and enables multi-key rotation.
 */
object ReleaseCrypto {
    /** Algorithm identifier stored in the manifest signature. */
    const val SIGNING_ALGORITHM = "ECDSA-P256-SHA256"
    const val JCA_SIGNATURE_ALGORITHM = "SHA256withECDSA"
    const val CURVE = "secp256r1"

    fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(CURVE), SecureRandom())
        return generator.generateKeyPair()
    }

    fun fingerprint(publicKey: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    fun sign(manifest: SignedReleaseManifest, keyPair: KeyPair): SignatureMetadata {
        val sig = Signature.getInstance(JCA_SIGNATURE_ALGORITHM)
        sig.initSign(keyPair.private)
        sig.update(CanonicalCodec.canonicalBytes(manifest))
        val der = sig.sign()
        return SignatureMetadata(
            algorithm = SIGNING_ALGORITHM,
            keyId = fingerprint(keyPair.public),
            signatureBase64 = Base64.getEncoder().encodeToString(der),
        )
    }

    fun envelope(manifest: SignedReleaseManifest, keyPair: KeyPair): SignedReleaseEnvelope =
        SignedReleaseEnvelope(manifest = manifest, signature = sign(manifest, keyPair))

    fun verify(envelope: SignedReleaseEnvelope, publicKey: PublicKey): VerificationResult {
        val expected = fingerprint(publicKey)
        if (envelope.signature.keyId != expected) {
            return VerificationResult.fail("keyId mismatch: manifest key ${envelope.signature.keyId} != verifier key $expected")
        }
        if (envelope.signature.algorithm != SIGNING_ALGORITHM) {
            return VerificationResult.fail("unsupported signature algorithm '${envelope.signature.algorithm}'")
        }
        val der = try {
            Base64.getDecoder().decode(envelope.signature.signatureBase64.trim())
        } catch (e: IllegalArgumentException) {
            return VerificationResult.fail("signature is not valid base64")
        }
        return try {
            val sig = Signature.getInstance(JCA_SIGNATURE_ALGORITHM)
            sig.initVerify(publicKey)
            sig.update(CanonicalCodec.canonicalBytes(envelope.manifest))
            if (sig.verify(der)) {
                VerificationResult.ok("valid $SIGNING_ALGORITHM signature (keyId $expected)")
            } else {
                VerificationResult.fail("signature verification failed")
            }
        } catch (e: Exception) {
            VerificationResult.fail("signature check threw: ${e::class.java.simpleName}")
        }
    }

    // ---------------------------------------------------------------- Key encoding

    private const val PEM_PUBLIC_BEGIN = "-----BEGIN PUBLIC KEY-----"
    private const val PEM_PUBLIC_END = "-----END PUBLIC KEY-----"
    private const val PEM_PRIVATE_BEGIN = "-----BEGIN PRIVATE KEY-----"
    private const val PEM_PRIVATE_END = "-----END PRIVATE KEY-----"

    fun publicKeyPem(publicKey: PublicKey): String = pemEncode(PEM_PUBLIC_BEGIN, PEM_PUBLIC_END, publicKey.encoded)

    fun privateKeyPem(privateKey: PrivateKey): String = pemEncode(PEM_PRIVATE_BEGIN, PEM_PRIVATE_END, privateKey.encoded)

    fun parsePublicKeyPem(pem: String): PublicKey {
        val body = pemBody(pem, PEM_PUBLIC_BEGIN, PEM_PUBLIC_END)
        val bytes = Base64.getDecoder().decode(body)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    }

    fun parsePrivateKeyPem(pem: String): PrivateKey {
        val body = pemBody(pem, PEM_PRIVATE_BEGIN, PEM_PRIVATE_END)
        val bytes = Base64.getDecoder().decode(body)
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))
    }

    private fun pemBody(pem: String, begin: String, end: String): String =
        pem.replace(begin, "").replace(end, "").replace(Regex("\\s"), "")

    private fun pemEncode(begin: String, end: String, der: ByteArray): String {
        val body = Base64.getEncoder().encodeToString(der)
        return buildString {
            append(begin)
            var i = 0
            while (i < body.length) {
                append('\n').append(body, i, minOf(i + 64, body.length))
                i += 64
            }
            append('\n').append(end).append('\n')
        }
    }
}