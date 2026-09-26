package dev.gamblock.core.security

import java.io.IOException
import java.io.InputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Envelope for a Shield recovery backup file.
 *
 * ```
 * offset  size  field
 * 0       8     magic "SHLDBAK1"
 * 8       1     format version
 * 9       16    PBKDF2 salt
 * 25      12    GCM nonce/IV
 * 37      n     ciphertext, with the 16-byte GCM tag appended
 * ```
 *
 * The header is deliberately outside the ciphertext so a file can be identified
 * (and refused, if it is not ours) before any expensive key derivation runs, and
 * so the user gets "this is not a Shield backup" instead of "wrong passphrase".
 *
 * The header carries no key-derivation parameters on purpose: keeping the layout
 * fixed means [FORMAT_VERSION] selects the KDF, so a future increase in the
 * iteration count stays readable for files that are already in the wild.
 */
class BackupCryptoManager(
    private val secureRandom: SecureRandom = SecureRandom(),
) {

    /**
     * Wraps [data] in an encrypted envelope bound to [passphrase].
     *
     * A fresh salt and nonce are drawn for every call, so encrypting the same
     * plaintext twice produces unrelated ciphertexts.
     */
    fun encrypt(data: ByteArray, passphrase: CharArray): ByteArray {
        require(passphrase.isNotEmpty()) { "passphrase must not be empty" }
        val salt = ByteArray(SALT_LENGTH_BYTES).also(secureRandom::nextBytes)
        val nonce = ByteArray(NONCE_LENGTH_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_LENGTH_BITS, nonce))
        val ciphertext = cipher.doFinal(data)

        val out = ByteArray(HEADER_LENGTH + ciphertext.size)
        MAGIC.copyInto(out, 0)
        out[MAGIC.size] = FORMAT_VERSION.toByte()
        salt.copyInto(out, MAGIC.size + 1)
        nonce.copyInto(out, MAGIC.size + 1 + SALT_LENGTH_BYTES)
        ciphertext.copyInto(out, HEADER_LENGTH)
        return out
    }

    /**
     * Validates and decrypts an envelope read from [encryptedStream].
     *
     * Throws [InvalidBackupFormatException] if the bytes are not a Shield backup,
     * [UnsupportedBackupVersionException] if the file was written by a newer
     * build, and [InvalidPassphraseException] when the GCM tag does not verify.
     */
    @Throws(IOException::class)
    fun decrypt(encryptedStream: InputStream, passphrase: CharArray): ByteArray =
        decrypt(readEnvelope(encryptedStream), passphrase)

    fun decrypt(envelope: ByteArray, passphrase: CharArray): ByteArray {
        val header = parseHeader(envelope)
        require(passphrase.isNotEmpty()) { "passphrase must not be empty" }

        val body = envelope.copyOfRange(HEADER_LENGTH, envelope.size)
            if (body.size < TAG_LENGTH_BYTES) {
                throw InvalidBackupFormatException("backup payload is truncated")
            }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                deriveKey(passphrase, header.salt),
                GCMParameterSpec(TAG_LENGTH_BITS, header.nonce),
            )
            cipher.doFinal(body)
        } catch (e: AEADBadTagException) {
            // GCM cannot tell "wrong key" apart from "someone flipped a bit in the
            // file": both surface as a tag mismatch. Reporting it as a passphrase
            // problem is the honest description, and the rejection is what matters.
            throw InvalidPassphraseException(
                "could not decrypt: the passphrase is wrong, or the file was modified",
                e,
            )
        } catch (e: GeneralSecurityException) {
            throw InvalidBackupFormatException("backup payload could not be decrypted", e)
        }
    }

    /**
     * Reads the plaintext backup format and total size without deriving a key.
     * Used to reject a non-Shield file before asking the user for a passphrase.
     */
    @Throws(IOException::class)
    fun readHeader(encryptedStream: InputStream): BackupHeader = parseHeader(readEnvelope(encryptedStream))

    fun readHeader(envelope: ByteArray): BackupHeader = parseHeader(envelope)

    private fun parseHeader(envelope: ByteArray): BackupHeader {
        if (envelope.size < HEADER_LENGTH) {
            throw InvalidBackupFormatException(
                "file is too small to be a Shield backup (${envelope.size} bytes)",
            )
        }
        val magic = envelope.copyOfRange(0, MAGIC.size)
        if (!MessageDigest.isEqual(MAGIC, magic)) {
            throw InvalidBackupFormatException("not a Shield recovery backup file")
        }
        val version = envelope[MAGIC.size].toInt() and 0xFF
        if (version > FORMAT_VERSION) {
            throw UnsupportedBackupVersionException(version)
        }
        if (version < MINIMUM_SUPPORTED_FORMAT_VERSION) {
            throw UnsupportedBackupVersionException(version)
        }
        val saltStart = MAGIC.size + 1
        val nonceStart = saltStart + SALT_LENGTH_BYTES
        return BackupHeader(
            formatVersion = version,
            salt = envelope.copyOfRange(saltStart, nonceStart),
            nonce = envelope.copyOfRange(nonceStart, HEADER_LENGTH),
            bodyLength = envelope.size - HEADER_LENGTH,
        )
    }

    @Throws(IOException::class)
    private fun readEnvelope(stream: InputStream): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(chunk)
            if (read == -1) break
            total += read
            // A recovery backup is a few hundred KB at most. Refusing to buffer an
            // unbounded "backup" keeps a hostile or corrupt file from exhausting
            // the app's heap.
            if (total > MAX_ENVELOPE_BYTES) {
                throw InvalidBackupFormatException("backup file is implausibly large")
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, KDF_ITERATIONS, KEY_LENGTH_BITS)
        try {
            val factory = SecretKeyFactory.getInstance(KDF_ALGORITHM)
            return SecretKeySpec(factory.generateSecret(spec).encoded, CIPHER_ALGORITHM)
        } finally {
            // Do not leave the passphrase sitting in the spec's internal char array.
            spec.clearPassword()
        }
    }

    data class BackupHeader(
        val formatVersion: Int,
        val salt: ByteArray,
        val nonce: ByteArray,
        val bodyLength: Int,
    ) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is BackupHeader &&
                formatVersion == other.formatVersion &&
                bodyLength == other.bodyLength &&
                MessageDigest.isEqual(salt, other.salt) &&
                MessageDigest.isEqual(nonce, other.nonce))

        override fun hashCode(): Int =
            (formatVersion * 31 + bodyLength) xor salt.contentHashCode() xor nonce.contentHashCode()
    }

    companion object {
        const val TRANSFORMATION: String = "AES/GCM/NoPadding"
        const val CIPHER_ALGORITHM: String = "AES"
        const val KDF_ALGORITHM: String = "PBKDF2WithHmacSHA256"
        const val KDF_ITERATIONS: Int = 120_000
        const val KEY_LENGTH_BITS: Int = 256
        const val SALT_LENGTH_BYTES: Int = 16
        const val NONCE_LENGTH_BYTES: Int = 12
        const val TAG_LENGTH_BITS: Int = 128
        const val TAG_LENGTH_BYTES: Int = TAG_LENGTH_BITS / 8
        val MAGIC: ByteArray = "SHLDBAK1".toByteArray(Charsets.US_ASCII)
        const val FORMAT_VERSION: Int = 1
        const val MINIMUM_SUPPORTED_FORMAT_VERSION: Int = 1
        val HEADER_LENGTH: Int = MAGIC.size + 1 + SALT_LENGTH_BYTES + NONCE_LENGTH_BYTES

        /** ~500 journal rows plus exceptions stays far below this. */
        const val MAX_ENVELOPE_BYTES: Int = 32 * 1024 * 1024
    }
}

/** The bytes are not a Shield recovery backup (bad magic, truncated, oversized). */
class InvalidBackupFormatException(message: String, cause: Throwable? = null) :
    GeneralSecurityException(message, cause)

/**
 * The GCM tag did not verify: the passphrase is wrong or the file was altered.
 * GCM deliberately gives no way to tell those two apart.
 */
class InvalidPassphraseException(message: String, cause: Throwable? = null) :
    GeneralSecurityException(message, cause)

/** The file was written by a newer Shield build, or one too old to trust. */
class UnsupportedBackupVersionException(val version: Int) :
    GeneralSecurityException("unsupported backup format version: $version")

/**
 * Passphrase rules for backup files.
 *
 * A backup is only as private as its key. A 4-digit Guardian PIN has 10,000
 * possible values, so deriving the backup key straight from a PIN would make the
 * file cheap to brute-force offline even at 120k PBKDF2 iterations. The PIN stays
 * an *authorisation* gate (the user must clear it to export); the encryption key
 * itself must be a real passphrase.
 */
object BackupPassphrase {

    const val MIN_LENGTH: Int = 8
    const val MAX_LENGTH: Int = 128

    fun isAcceptable(passphrase: CharArray): Boolean =
        passphrase.size in MIN_LENGTH..MAX_LENGTH && passphrase.any { !it.isWhitespace() }

    fun rejectionReason(passphrase: CharArray): String? = when {
        passphrase.size < MIN_LENGTH -> "Use at least $MIN_LENGTH characters. A short or numeric key can be guessed."
        passphrase.size > MAX_LENGTH -> "That is longer than the $MAX_LENGTH character limit."
        !passphrase.any { !it.isWhitespace() } -> "The passphrase cannot be only spaces."
        else -> null
    }

    /** Constant-time comparison so callers can verify without leaking a prefix. */
    fun constantTimeEquals(a: CharArray, b: CharArray): Boolean =
        MessageDigest.isEqual(
            a.concatToString().toByteArray(Charsets.UTF_8),
            b.concatToString().toByteArray(Charsets.UTF_8),
        )
}
