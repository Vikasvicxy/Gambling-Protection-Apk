package dev.gamblock.data.update

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gamblock.core.common.logging.Logs
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.core.release.ReleaseCrypto
import dev.gamblock.core.release.TrustedKeyRing
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads the embedded public signing key into a [TrustedKeyRing]. Only public
 * material is ever read from assets; there is no private key on the device.
 */
@Singleton
class SigningKeyProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: ShieldLogger,
) : SigningKeySource {
    @Volatile
    private var cached: TrustedKeyRing? = null

    /** Returns the verifier ring, or null when the key asset is missing/unparseable. */
    override fun keyRingOrNull(): TrustedKeyRing? {
        cached?.let { return it }
        val publicKey = try {
            context.assets.open(SigningPem.ASSET).bufferedReader().use { ReleaseCrypto.parsePublicKeyPem(it.readText()) }
        } catch (e: Exception) {
            logger.e(Logs.SECURITY, "signing key asset missing or invalid: ${e.message}")
            return null
        }
        return TrustedKeyRing(listOf(publicKey)).also {
            cached = it
            logger.i(Logs.SECURITY, "signing key loaded, keyId ${it.keyIds.first()}")
        }
    }
}