package dev.gamblock.protection.tamper

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Keyed HMAC signing seam. The implementation owns key lifecycle; callers only
 * see sign/verify byte strings.
 */
interface EvidenceHmac {
    /** Returns the hex-encoded HMAC-SHA256 of [data]. */
    fun sign(data: ByteArray): String

    /** True when [expectedHex] equals the freshly computed HMAC of [data]. */
    fun verify(data: ByteArray, expectedHex: String): Boolean
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/**
 * HMAC key bound to the Android KeyStore. The key material cannot be extracted
 * or read from app storage, so an attacker who can rewrite files cannot forge
 * evidence records. A fresh key is created on first use; on KeyStore
 * invalid-after-restore the key is re-bootstrapped (evidence integrity is
 * assessed from the chain, not from key continuity alone).
 */
class KeystoreEvidenceHmac(
    private val alias: String = DEFAULT_ALIAS,
) : EvidenceHmac {
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    init {
        if (!keyStore.containsAlias(alias)) {
            val generator = javax.crypto.KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
                ANDROID_KEYSTORE,
            )
            generator.init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(256)
                    .build(),
            )
            generator.generateKey()
        }
    }

    private fun mac(): Mac = Mac.getInstance(KEY_ALGORITHM).apply {
        init(keyStore.getKey(alias, null) as SecretKey)
    }

    override fun sign(data: ByteArray): String = mac().doFinal(data).toHex()

    override fun verify(data: ByteArray, expectedHex: String): Boolean =
        expectedHex.equals(sign(data), ignoreCase = true)

    companion object {
        const val DEFAULT_ALIAS = "shield_evidence_hmac_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALGORITHM = "HmacSHA256"
    }
}

/**
 * Deterministic HMAC for tests and pure-JVM contexts where no KeyStore exists.
 * NOT for production: the key is stored in the clear.
 */
class StaticEvidenceHmac(
    keyBytes: ByteArray,
) : EvidenceHmac {
    private val mac: Mac = Mac.getInstance(KEY_ALGORITHM).apply {
        init(SecretKeySpec(keyBytes.copyOf(), KEY_ALGORITHM))
    }

    override fun sign(data: ByteArray): String = mac.doFinal(data).toHex()

    override fun verify(data: ByteArray, expectedHex: String): Boolean =
        expectedHex.equals(sign(data), ignoreCase = true)

    companion object {
        private const val KEY_ALGORITHM = "HmacSHA256"
    }
}