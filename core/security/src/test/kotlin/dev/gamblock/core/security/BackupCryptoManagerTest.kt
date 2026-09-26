package dev.gamblock.core.security

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.security.GeneralSecurityException
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The backup file is the one place recovery data leaves the device, so these
 * tests are as much about what is *refused* as about the happy path.
 */
class BackupCryptoManagerTest {

    private val crypto = BackupCryptoManager()
    private val passphrase = "correct horse battery".toCharArray()
    private val plaintext = """{"schemaVersion":1,"note":"private journal text"}""".toByteArray()

    @Test
    fun `round trip returns the original bytes exactly`() {
        val envelope = crypto.encrypt(plaintext, passphrase)

        val decrypted = crypto.decrypt(envelope, passphrase)

        assertThat(decrypted).isEqualTo(plaintext)
    }

    @Test
    fun `round trip works through an input stream`() {
        val envelope = crypto.encrypt(plaintext, passphrase)

        val decrypted = crypto.decrypt(ByteArrayInputStream(envelope), passphrase)

        assertThat(decrypted).isEqualTo(plaintext)
    }

    @Test
    fun `empty payload round trips`() {
        val envelope = crypto.encrypt(ByteArray(0), passphrase)

        assertThat(crypto.decrypt(envelope, passphrase)).isEqualTo(ByteArray(0))
    }

    @Test
    fun `large payload round trips`() {
        val large = ByteArray(512 * 1024) { (it % 251).toByte() }

        val decrypted = crypto.decrypt(crypto.encrypt(large, passphrase), passphrase)

        assertThat(decrypted).isEqualTo(large)
    }

    @Test
    fun `header matches the documented layout`() {
        val envelope = crypto.encrypt(plaintext, passphrase)

        assertThat(envelope.copyOfRange(0, 8).toString(Charsets.US_ASCII)).isEqualTo("SHLDBAK1")
        assertThat(envelope[8].toInt()).isEqualTo(BackupCryptoManager.FORMAT_VERSION)
        // header + ciphertext + 16-byte tag
        assertThat(envelope.size)
            .isEqualTo(BackupCryptoManager.HEADER_LENGTH + plaintext.size + BackupCryptoManager.TAG_LENGTH_BYTES)
    }

    @Test
    fun `each export draws a fresh salt and nonce`() {
        val first = crypto.encrypt(plaintext, passphrase)
        val second = crypto.encrypt(plaintext, passphrase)

        val saltStart = 9
        val nonceStart = saltStart + BackupCryptoManager.SALT_LENGTH_BYTES
        assertThat(first.copyOfRange(saltStart, nonceStart)).isNotEqualTo(second.copyOfRange(saltStart, nonceStart))
        assertThat(first.copyOfRange(nonceStart, BackupCryptoManager.HEADER_LENGTH))
            .isNotEqualTo(second.copyOfRange(nonceStart, BackupCryptoManager.HEADER_LENGTH))
        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `wrong passphrase is rejected`() {
        val envelope = crypto.encrypt(plaintext, passphrase)

        val error = assertThrows(InvalidPassphraseException::class.java) {
            crypto.decrypt(envelope, "definitely not it".toCharArray())
        }

        assertThat(error).hasMessageThat().contains("passphrase is wrong")
    }

    @Test
    fun `modified ciphertext is rejected by the gcm tag`() {
        val envelope = crypto.encrypt(plaintext, passphrase)
        // Flip one bit deep inside the ciphertext, leaving the header intact.
        val target = BackupCryptoManager.HEADER_LENGTH + 2
        envelope[target] = (envelope[target].toInt() xor 0x01).toByte()

        assertThrows(InvalidPassphraseException::class.java) { crypto.decrypt(envelope, passphrase) }
    }

    @Test
    fun `appended garbage is rejected by the gcm tag`() {
        val envelope = crypto.encrypt(plaintext, passphrase)

        val tampered = envelope + byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

        assertThrows(InvalidPassphraseException::class.java) { crypto.decrypt(tampered, passphrase) }
    }

    @Test
    fun `a modified nonce is rejected`() {
        val envelope = crypto.encrypt(plaintext, passphrase)
        envelope[BackupCryptoManager.HEADER_LENGTH - 1] =
            (envelope[BackupCryptoManager.HEADER_LENGTH - 1].toInt() xor 0xFF).toByte()

        assertThrows(InvalidPassphraseException::class.java) { crypto.decrypt(envelope, passphrase) }
    }

    @Test
    fun `a modified salt is rejected`() {
        val envelope = crypto.encrypt(plaintext, passphrase)
        envelope[9] = (envelope[9].toInt() xor 0xFF).toByte()

        assertThrows(InvalidPassphraseException::class.java) { crypto.decrypt(envelope, passphrase) }
    }

    @Test
    fun `random bytes are not mistaken for a backup`() {
        val notABackup = ByteArray(4096) { (it * 7).toByte() }

        val error = assertThrows(InvalidBackupFormatException::class.java) {
            crypto.decrypt(notABackup, passphrase)
        }

        assertThat(error).hasMessageThat().contains("not a Shield recovery backup")
    }

    @Test
    fun `a file too small to hold a header is rejected`() {
        val error = assertThrows(InvalidBackupFormatException::class.java) {
            crypto.decrypt("SHLDBAK1".toByteArray(), passphrase)
        }

        assertThat(error).hasMessageThat().contains("too small")
    }

    @Test
    fun `a truncated ciphertext is rejected`() {
        val envelope = crypto.encrypt(plaintext, passphrase)
        val truncated = envelope.copyOf(BackupCryptoManager.HEADER_LENGTH + 4)

        assertThrows(GeneralSecurityException::class.java) { crypto.decrypt(truncated, passphrase) }
    }

    @Test
    fun `a file from a newer build is refused rather than guessed at`() {
        val envelope = crypto.encrypt(plaintext, passphrase)
        envelope[8] = (BackupCryptoManager.FORMAT_VERSION + 1).toByte()

        val error = assertThrows(UnsupportedBackupVersionException::class.java) {
            crypto.decrypt(envelope, passphrase)
        }

        assertThat(error.version).isEqualTo(BackupCryptoManager.FORMAT_VERSION + 1)
    }

    @Test
    fun `the header can be inspected without the passphrase`() {
        val envelope = crypto.encrypt(plaintext, passphrase)

        val header = crypto.readHeader(envelope)

        assertThat(header.formatVersion).isEqualTo(BackupCryptoManager.FORMAT_VERSION)
        assertThat(header.salt).hasLength(BackupCryptoManager.SALT_LENGTH_BYTES)
        assertThat(header.nonce).hasLength(BackupCryptoManager.NONCE_LENGTH_BYTES)
    }

    @Test
    fun `an implausibly large stream is refused instead of exhausting memory`() {
        val oversized = object : java.io.InputStream() {
            private var written = 0
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val allowed = minOf(len, BackupCryptoManager.MAX_ENVELOPE_BYTES + 1 - written)
                if (allowed <= 0) return -1
                java.util.Arrays.fill(b, off, off + allowed, 0)
                written += allowed
                return allowed
            }
        }

        val error = assertThrows(InvalidBackupFormatException::class.java) {
            crypto.decrypt(oversized, passphrase)
        }

        assertThat(error).hasMessageThat().contains("implausibly large")
    }

    @Test
    fun `key derivation meets the required strength`() {
        assertThat(BackupCryptoManager.KDF_ITERATIONS).isAtLeast(100_000)
        assertThat(BackupCryptoManager.KEY_LENGTH_BITS).isEqualTo(256)
        assertThat(BackupCryptoManager.SALT_LENGTH_BYTES).isEqualTo(16)
        assertThat(BackupCryptoManager.NONCE_LENGTH_BYTES).isEqualTo(12)
        assertThat(BackupCryptoManager.TRANSFORMATION).isEqualTo("AES/GCM/NoPadding")
        assertThat(BackupCryptoManager.KDF_ALGORITHM).isEqualTo("PBKDF2WithHmacSHA256")
    }

    @Test
    fun `the plaintext never appears in the envelope`() {
        val secret = "my secret gambling trigger note".toByteArray()

        val envelope = crypto.encrypt(secret, passphrase)

        val asText = envelope.toString(Charsets.ISO_8859_1)
        assertThat(asText).doesNotContain("gambling trigger")
    }

    @Test
    fun `a short passphrase is refused as an encryption key`() {
        assertThat(BackupPassphrase.rejectionReason("1234".toCharArray())).isNotNull()
        assertThat(BackupPassphrase.rejectionReason("        ".toCharArray())).isNotNull()
        assertThat(BackupPassphrase.rejectionReason("longenoughpassphrase".toCharArray())).isNull()
        assertThat(BackupPassphrase.isAcceptable("1234".toCharArray())).isFalse()
        assertThat(BackupPassphrase.isAcceptable("longenoughpassphrase".toCharArray())).isTrue()
    }

    @Test
    fun `passphrase comparison does not short circuit on a shared prefix`() {
        assertThat(BackupPassphrase.constantTimeEquals("abcdefgh".toCharArray(), "abcdefgh".toCharArray())).isTrue()
        assertThat(BackupPassphrase.constantTimeEquals("abcdefgh".toCharArray(), "abcdefgi".toCharArray())).isFalse()
    }
}
