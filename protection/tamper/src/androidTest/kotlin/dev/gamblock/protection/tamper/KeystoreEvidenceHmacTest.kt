package dev.gamblock.protection.tamper

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Instrumentation tests for the Android-KeyStore-backed HMAC.
 *
 * Requires a real Android runtime: the AndroidKeyStore JCE provider exists only
 * on a device. Run via `connectedDebugAndroidTest`, never in the JVM unit test
 * suite, and do NOT move back to `src/test` under Robolectric - Robolectric's
 * sandbox breaks `java.util.jar.JarVerifier` static initialization, which then
 * poisons every subsequent JCE call (`Mac.getInstance`, HMAC) for the other
 * pure-JVM tests sharing the Gradle test fork.
 */
class KeystoreEvidenceHmacTest {

    @Test
    fun `round trip signs and verifies`() {
        val hmac = KeystoreEvidenceHmac()
        val data = "canonical evidence bytes".toByteArray()
        val signature = hmac.sign(data)
        assertThat(hmac.verify(data, signature)).isTrue()
        assertThat(hmac.verify("tampered bytes".toByteArray(), signature)).isFalse()
    }

    @Test
    fun `signature is deterministic for same key`() {
        val hmac = KeystoreEvidenceHmac()
        val data = "evidence".toByteArray()
        assertThat(hmac.sign(data)).isEqualTo(hmac.sign(data))
    }

    @Test
    fun `wrong expected signature fails`() {
        val hmac = KeystoreEvidenceHmac()
        assertThat(hmac.verify("x".toByteArray(), "0000000000000000000000000000000000000000000000000000000000000000")).isFalse()
    }
}