package dev.gamblock.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.gamblock.core.common.logging.Logs
import dev.gamblock.core.common.logging.ShieldLogger
import dev.gamblock.core.model.util.randomId128
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first

/** Provider of an anonymous, non-tracking installation identifier. */
interface InstallIdProvider {
    /** Returns the stored ID (generating and persisting it the first time). */
    suspend fun get(): String
}

private val Context.installIdentityDataStore by preferencesDataStore(name = "install_identity")

/**
 * Anonymous installation identity protected with AES/GCM via the Android KeyStore.
 *
 * The ID is random, contains no personal data, and is never sent anywhere in Phase 1.
 * If the KeyStore key becomes unusable (e.g. after a backup restore mismatch), a new
 * ID is generated - this is acceptable because Phase 1 performs no server analytics.
 */
class KeystoreInstallId(
    private val context: Context,
    private val logger: ShieldLogger,
) : InstallIdProvider {

    private companion object {
        const val KEYSTORE_ALIAS = "shield_install_id_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val GCM_TAG_BITS = 128
        val PREF_INSTALL_ID = stringPreferencesKey("install_id_ciphertext")
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
        }.also {
            if (!it.containsAlias(KEYSTORE_ALIAS)) {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                generator.init(
                    KeyGenParameterSpec.Builder(
                        KEYSTORE_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
                generator.generateKey()
            }
        }
    }

    override suspend fun get(): String {
        try {
            val stored = context.installIdentityDataStore.data.first()[PREF_INSTALL_ID]
            if (stored != null) {
                return decrypt(stored)
            }
        } catch (t: Exception) {
            logger.w(Logs.SECURITY, "Install ID could not be decrypted; regenerating", t)
        }

        val id = randomId128()
        val ciphertext = encrypt(id)
        context.installIdentityDataStore.edit { it[PREF_INSTALL_ID] = ciphertext }
        return id
    }

    private fun key(): SecretKey =
        (keyStore.getKey(KEYSTORE_ALIAS, null) as SecretKey)

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val output = cipher.iv + bytes
        return Base64.encodeToString(output, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val blob = Base64.decode(encoded, Base64.NO_WRAP)
        require(blob.size > 12) { "ciphertext too short" }
        val iv = blob.copyOfRange(0, 12)
        val payload = blob.copyOfRange(12, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(payload), Charsets.UTF_8)
    }
}