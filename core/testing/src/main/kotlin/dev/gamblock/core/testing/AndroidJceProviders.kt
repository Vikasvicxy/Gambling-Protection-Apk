package dev.gamblock.core.testing

import java.security.Provider
import java.security.Security

/**
 * Removes the JCE providers Robolectric injects into the host JVM.
 *
 * Robolectric loads `android-all`, which registers Android's own providers —
 * Conscrypt, AndroidOpenSSL, AndroidKeyStore and BC — at the front of the host JVM's
 * provider list. The JDK then verifies each of them the first time anything asks for
 * a cipher, and on a current JDK that verification of Conscrypt's bundled certificate
 * fails with
 *
 * ```
 * java.lang.ExceptionInInitializerError: javax.crypto.JarVerifier
 *   caused by java.security.NoSuchAlgorithmException:
 *     error:0c00006f:ASN.1 encoding routines:OPENSSL_internal:DIGEST_AND_KEY_TYPE_NOT_SUPPORTED
 * ```
 *
 * because OpenSSL 3 refuses Conscrypt's signature digest. The failure is cached in
 * `JarVerifier`, so **every** later `Cipher.getInstance(...)` in that JVM throws
 * `NoClassDefFoundError: Could not initialize class javax.crypto.JarVerifier`. The
 * symptom then looks nothing like its cause: a backup test reports
 * "file is too small to be a Shield backup (0 bytes)" because encryption failed
 * before a single byte was ever written.
 *
 * Removing the Android providers leaves the JDK's own SunJCE, which is a complete,
 * standards-compliant AES-GCM implementation. Tests therefore exercise the backup
 * format against a different JCE provider than production does, which is a stronger
 * check of the on-disk format than testing against the same provider twice.
 *
 * Call [dropAndroidProviders] from test setup before touching crypto.
 */
object AndroidJceProviders {

    private val androidProviders = listOf(
        "Conscrypt",
        "AndroidOpenSSL",
        "AndroidKeyStore",
        "BouncyCastle",
    )

    /**
     * Drops the Android JCE providers. Safe and cheap to call from every test.
     *
     * Deliberately not cached across calls: Robolectric bootstraps a fresh sandbox
     * per test configuration and re-registers the Android providers each time, so a
     * one-shot guard lets Conscrypt back in and the next test dies on the already
     * poisoned `JarVerifier` class.
     */
    fun dropAndroidProviders() {
        Security.getProviders()
            .map(Provider::getName)
            .filter { name -> androidProviders.any { it.equals(name, ignoreCase = true) } }
            .forEach(Security::removeProvider)
    }
}