package dev.gamblock.core.release

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Test

class PayloadCodecTest {

    private fun record(domain: String) = ReleaseDomainRecord(
        domain = domain,
        normalizedDomain = domain,
        category = "CASINO",
        confidence = "HIGH",
        status = "ACTIVE",
        riskLevel = "MEDIUM",
        sourceIds = listOf("s"),
        firstSeenEpochMs = 1L,
        lastVerifiedEpochMs = 1L,
        databaseVersion = 1,
    )

    @Test
    fun `records round trip through gzip ndjson`() {
        val records = listOf(record("casino-a.test"), record("poker-b.test"), record("lotto-c.test"))
        val compressed = PayloadCodec.encodeRecords(records)
        val decoded = PayloadCodec.decodeRecords(compressed)
        assertThat(decoded).isEqualTo(records)
        // Compressed should be meaningfully smaller than raw JSON for repetitive lines.
        assertThat(compressed.size).isLessThan(512)
    }

    @Test(expected = PayloadException::class)
    fun `truncated gzip rejects`() {
        val compressed = PayloadCodec.encodeRecords(listOf(record("a.test")))
        PayloadCodec.decodeRecords(compressed.copyOf(compressed.size - 4)) // drop gzip trailer
    }

    @Test(expected = PayloadException::class)
    fun `random garbage rejects`() {
        PayloadCodec.decodeRecords(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
    }

    @Test(expected = PayloadException::class)
    fun `invalid json line rejects`() {
        val gz = gzip("{\"domain\":\"a\"}\n{not-json}\n".toByteArray())
        PayloadCodec.decodeRecords(gz)
    }

    @Test(expected = PayloadException::class)
    fun `decompression bomb is capped`() {
        // Compress ~1 MiB of mostly-repeating padding; then try to decode with a tiny cap.
        val big = StringBuilder()
        repeat(64 * 1024) { big.append("{\"x\":\"0000000000000000000000\"}\n") }
        val gz = gzip(big.toString().toByteArray())
        PayloadCodec.decodeRecords(gz, maxUncompressedBytes = 4096)
    }

    @Test
    fun `delta ops round trip`() {
        val ops = listOf<DeltaOp>(
            DeltaOp.Header(1, 2, added = 1, removed = 1, modified = 0),
            DeltaOp.Add(record("new.test")),
            DeltaOp.Remove("gone.test"),
        )
        val compressed = DeltaCodec.encode(ops)
        val decoded = DeltaCodec.decode(compressed)
        assertThat(decoded).isEqualTo(ops)
    }

    @Test(expected = PayloadException::class)
    fun `truncated delta rejects`() {
        val compressed = DeltaCodec.encode(listOf<DeltaOp>(DeltaOp.Header(1, 2, 0, 0, 0)))
        DeltaCodec.decode(compressed.copyOf(compressed.size - 5))
    }

    @Test
    fun `large payloads decode without truncation`() {
        val records = (1..5000).map { record("domain-$it.test") }
        val compressed = PayloadCodec.encodeRecords(records)
        assertThat(PayloadCodec.decodeRecords(compressed)).hasSize(5000)
    }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().use { bos ->
            GZIPOutputStream(bos).use { it.write(bytes) }
            bos.toByteArray()
        }
}