package dev.gamblock.data.update

/**
 * Verifier-only key source. The device holds the PUBLIC half of the signing key(s).
 * The private key lives in CI secrets / the publisher's offline machine and is never
 * shipped in the APK, the repo or logs.
 */
object SigningPem {
    /** Filename of the embedded public key (X.509 SubjectPublicKeyInfo PEM). */
    const val ASSET = "update_signing_public_key.pem"
}