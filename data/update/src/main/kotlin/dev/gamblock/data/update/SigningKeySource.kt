package dev.gamblock.data.update

import dev.gamblock.core.release.TrustedKeyRing

/** Provides the device-side signing key ring (public key for signature verification). */
interface SigningKeySource {
    fun keyRingOrNull(): TrustedKeyRing?
}