package dev.gamblock.core.release

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Covers the release payload wire format, which is the trust boundary between the
 * signing pipeline and the device. These codecs parse attacker-influenceable bytes
 * (a mirror could serve anything), so the failure modes matter more than the happy path.
 */
class PayloadCodecTest {

    private fun record(
        domain: String = "casino.example",
        normalized: String = "casino.example",
        category: String = "GAMBLING",
        confidence: String = "HIGH",
        status: String = "ACTIVE",
        risk: String = "CRITICAL",
    ) = ReleaseDomainRecord(
        domain = domain,
        normalizedDomain = normalized,
        category = category,
        confidence = confidence,
        status = status,
        riskLevel = risk,
    )

    private fun gzip(text: String): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        return bos.toByteArray()
    }

    // ---- round trips ----

    @Test
    fun `round trips a single record`() {
        val decoded = PayloadCodec.decodeRecords(PayloadCodec.encodeRecords(listOf(record())))

        assertThat(decoded).hasSize(1)
        assertThat(decoded.single().normalizedDomain).isEqualTo("casino.example")
    }

    @Test
    fun `round trips an empty payload`() {
        assertThat(PayloadCodec.decodeRecords(PayloadCodec.encodeRecords(emptyList()))).isEmpty()
    }

    @Test
    fun `round trips many records preserving order`() {
        val records = (1..200).map { record(normalized = "d$it.example") }

        val decoded = PayloadCodec.decodeRecords(PayloadCodec.encodeRecords(records))

        assertThat(decoded.map { it.normalizedDomain })
            .isEqualTo(records.map { it.normalizedDomain })
    }

    @Test
    fun `preserves optional provenance fields`() {
        val source = record().copy(
            sourceIds = listOf("src-a", "src-b"),
            operatorId = "op-1",
            brandId = "brand-1",
            mirrorOf = "mirror.example",
            country = "GB",
            firstSeenEpochMs = 1_700_000_000_000L,
            lastVerifiedEpochMs = 1_700_000_500_000L,
            databaseVersion = 42,
            appliesToSubdomains = false,
        )

        val decoded = PayloadCodec.decodeRecords(PayloadCodec.encodeRecords(listOf(source))).single()

        assertThat(decoded.sourceIds).containsExactly("src-a", "src-b").inOrder()
        assertThat(decoded.operatorId).isEqualTo("op-1")
        assertThat(decoded.mirrorOf).isEqualTo("mirror.example")
        assertThat(decoded.country).isEqualTo("GB")
        assertThat(decoded.databaseVersion).isEqualTo(42)
        assertThat(decoded.appliesToSubdomains).isFalse()
    }

    @Test
    fun `skips blank lines instead of failing`() {
        val payload = gzip("\n\n" + "{\"domain\":\"a.example\",\"normalizedDomain\":\"a.example\"," +
            "\"category\":\"GAMBLING\",\"confidence\":\"HIGH\",\"status\":\"ACTIVE\",\"riskLevel\":\"CRITICAL\"}" + "\n\n")

        assertThat(PayloadCodec.decodeRecords(payload)).hasSize(1)
    }

    @Test
    fun `tolerates CRLF line endings`() {
        val line = "{\"domain\":\"a.example\",\"normalizedDomain\":\"a.example\"," +
            "\"category\":\"GAMBLING\",\"confidence\":\"HIGH\",\"status\":\"ACTIVE\",\"riskLevel\":\"CRITICAL\"}"
        val payload = gzip("$line\r\n")

        assertThat(PayloadCodec.decodeRecords(payload)).hasSize(1)
    }

    // ---- hostile input ----

    @Test
    fun `rejects a payload that is not gzip`() {
        val error = assertThrows(PayloadException::class.java) {
            PayloadCodec.decodeRecords("not gzip at all".toByteArray())
        }

        assertThat(error).hasMessageThat().contains("corrupted")
    }

    @Test
    fun `rejects a truncated gzip stream`() {
        val full = PayloadCodec.encodeRecords(listOf(record()))

        val error = assertThrows(PayloadException::class.java) {
            PayloadCodec.decodeRecords(full.copyOfRange(0, full.size / 2))
        }

        assertThat(error).hasMessageThat().contains("corrupted")
    }

    @Test
    fun `rejects a gzip bomb that exceeds the size ceiling`() {
        // 4 MiB of zeros compresses to a few KiB, which is exactly the zip-bomb shape.
        val bomb = gzip("0".repeat(4 * 1024 * 1024))

        val error = assertThrows(PayloadException::class.java) {
            PayloadCodec.decodeRecords(bomb, maxUncompressedBytes = 64 * 1024)
        }

        assertThat(error).hasMessageThat().contains("exceeds limit")
    }

    @Test
    fun `accepts a payload that sits just under the size ceiling`() {
        val text = "x".repeat(1000)

        assertThat(PayloadCodec.decompress(gzip(text), maxUncompressedBytes = 2000)).hasLength(1000)
    }

    @Test
    fun `accepts output exactly at the ceiling and rejects one byte over`() {
        val text = "y".repeat(1200)

        assertThat(PayloadCodec.decompress(gzip(text), maxUncompressedBytes = 1200)).hasLength(1200)
        assertThrows(PayloadException::class.java) {
            PayloadCodec.decompress(gzip(text), maxUncompressedBytes = 1199)
        }
    }

    @Test
    fun `enforces the ceiling on multi-chunk output`() {
        // The reader uses a 64 KiB buffer, so this spans several reads. The limit is
        // checked after each one, which is what stops a highly compressible stream from
        // ballooning before the check runs.
        val text = "z".repeat(300_000)
        val compressed = gzip(text)

        assertThat(PayloadCodec.decompress(compressed, maxUncompressedBytes = 300_000)).hasLength(300_000)
        assertThrows(PayloadException::class.java) {
            PayloadCodec.decompress(compressed, maxUncompressedBytes = 299_999)
        }
    }

    @Test
    fun `rejects a malformed json line`() {
        val payload = gzip("{\"domain\":\"a.example\"}\n")

        val error = assertThrows(PayloadException::class.java) { PayloadCodec.decodeRecords(payload) }

        assertThat(error).hasMessageThat().contains("malformed payload line")
    }

    @Test
    fun `rejects a line with an unknown field`() {
        val line = "{\"domain\":\"a.example\",\"normalizedDomain\":\"a.example\",\"category\":\"GAMBLING\"," +
            "\"confidence\":\"HIGH\",\"status\":\"ACTIVE\",\"riskLevel\":\"CRITICAL\",\"surprise\":1}"
        val payload = gzip("$line\n")

        assertThrows(PayloadException::class.java) { PayloadCodec.decodeRecords(payload) }
    }

    @Test
    fun `rejects a wrong discriminator type`() {
        val payload = gzip("[\"not\",\"an\",\"object\"]\n")

        assertThrows(PayloadException::class.java) { PayloadCodec.decodeRecords(payload) }
    }

    @Test
    fun `rejects a multi-line json blob instead of silently accepting the first line`() {
        val payload = gzip("{\"domain\":\"a.example\"}\n{\"domain\":\"b.example\"}\n")

        assertThrows(PayloadException::class.java) { PayloadCodec.decodeRecords(payload) }
    }
}

class DeltaCodecTest {

    private fun record(domain: String) = ReleaseDomainRecord(
        domain = domain,
        normalizedDomain = domain,
        category = "GAMBLING",
        confidence = "HIGH",
        status = "ACTIVE",
        riskLevel = "CRITICAL",
    )

    private fun header(base: Int = 10, target: Int = 11) =
        DeltaOp.Header(base = base, target = target, added = 1, removed = 0, modified = 0)

    @Test
    fun `round trips a header with operations`() {
        val ops = listOf(
            header(),
            DeltaOp.Add(record("new.example")),
            DeltaOp.Modify(record("changed.example")),
            DeltaOp.Remove("gone.example"),
        )

        val decoded = DeltaCodec.decode(DeltaCodec.encode(ops))

        assertThat(decoded).hasSize(4)
        assertThat(decoded[0]).isInstanceOf(DeltaOp.Header::class.java)
        assertThat(decoded[1]).isInstanceOf(DeltaOp.Add::class.java)
        assertThat(decoded[2]).isInstanceOf(DeltaOp.Modify::class.java)
        assertThat(decoded[3]).isInstanceOf(DeltaOp.Remove::class.java)
    }

    @Test
    fun `preserves the header counts`() {
        val decoded = DeltaCodec.decode(
            DeltaCodec.encode(listOf(DeltaOp.Header(3, 4, added = 5, removed = 6, modified = 7))),
        ).single() as DeltaOp.Header

        assertThat(decoded.base).isEqualTo(3)
        assertThat(decoded.target).isEqualTo(4)
        assertThat(decoded.added).isEqualTo(5)
        assertThat(decoded.removed).isEqualTo(6)
        assertThat(decoded.modified).isEqualTo(7)
    }

    @Test
    fun `preserves the removed domain`() {
        val decoded = DeltaCodec.decode(DeltaCodec.encode(listOf(header(), DeltaOp.Remove("bad.example"))))

        assertThat((decoded[1] as DeltaOp.Remove).domain).isEqualTo("bad.example")
    }

    @Test
    fun `rejects a delta with no header`() {
        val payload = DeltaCodec.encode(listOf(DeltaOp.Add(record("new.example"))))

        val error = assertThrows(PayloadException::class.java) { DeltaCodec.decode(payload) }

        assertThat(error).hasMessageThat().contains("before header")
    }

    @Test
    fun `rejects an empty delta`() {
        val error = assertThrows(PayloadException::class.java) {
            DeltaCodec.decode(DeltaCodec.encode(emptyList()))
        }

        assertThat(error).hasMessageThat().contains("missing header")
    }

    @Test
    fun `rejects a delta with two headers`() {
        val payload = DeltaCodec.encode(listOf(header(), header(base = 11, target = 12)))

        val error = assertThrows(PayloadException::class.java) { DeltaCodec.decode(payload) }

        assertThat(error).hasMessageThat().contains("more than one header")
    }

    @Test
    fun `rejects an unknown op discriminator`() {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).bufferedWriter(Charsets.UTF_8).use {
            it.write("{\"op\":\"header\",\"base\":1,\"target\":2,\"added\":0,\"removed\":0,\"modified\":0}\n")
            it.write("{\"op\":\"destroy\",\"domain\":\"a.example\"}\n")
        }

        val error = assertThrows(PayloadException::class.java) { DeltaCodec.decode(bos.toByteArray()) }

        assertThat(error).hasMessageThat().contains("malformed delta line")
    }

    @Test
    fun `rejects a delta op with a missing field`() {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).bufferedWriter(Charsets.UTF_8).use {
            it.write("{\"op\":\"header\",\"base\":1,\"target\":2,\"added\":0,\"removed\":0,\"modified\":0}\n")
            it.write("{\"op\":\"remove\"}\n")
        }

        assertThrows(PayloadException::class.java) { DeltaCodec.decode(bos.toByteArray()) }
    }

    @Test
    fun `applies the decompression ceiling to deltas too`() {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).bufferedWriter(Charsets.UTF_8).use {
            it.write("z".repeat(50_000))
        }

        assertThrows(PayloadException::class.java) {
            DeltaCodec.decode(bos.toByteArray(), maxUncompressedBytes = 1024)
        }
    }
}

class ReleaseValidatorTest {

    private fun record(
        normalized: String = "casino.example",
        category: String = "GAMBLING",
        status: String = "ACTIVE",
        confidence: String = "HIGH",
        risk: String = "CRITICAL",
    ) = ReleaseDomainRecord(
        domain = normalized,
        normalizedDomain = normalized,
        category = category,
        confidence = confidence,
        status = status,
        riskLevel = risk,
    )

    @Test
    fun `accepts a valid payload and returns domains in order`() {
        val ordered = ReleaseValidator.validateRecords(
            listOf(record("a.example"), record("b.example"), record("c.example")),
        )

        assertThat(ordered).containsExactly("a.example", "b.example", "c.example").inOrder()
    }

    @Test
    fun `accepts an empty payload`() {
        assertThat(ReleaseValidator.validateRecords(emptyList())).isEmpty()
    }

    @Test
    fun `rejects a duplicate domain`() {
        val error = assertThrows(ReleaseValidationException::class.java) {
            ReleaseValidator.validateRecords(listOf(record("dup.example"), record("dup.example")))
        }

        assertThat(error).hasMessageThat().contains("duplicate domain")
    }

    @Test
    fun `rejects a non-normalized domain`() {
        val error = assertThrows(ReleaseValidationException::class.java) {
            ReleaseValidator.validateRecords(listOf(record("WWW.Example.COM")))
        }

        assertThat(error).hasMessageThat().contains("invalid normalizedDomain")
    }

    @Test
    fun `rejects an unnormalizable domain`() {
        assertThrows(ReleaseValidationException::class.java) {
            ReleaseValidator.validateRecords(listOf(record("")))
        }
    }

    @Test
    fun `reports the one-based record index`() {
        val error = assertThrows(ReleaseValidationException::class.java) {
            ReleaseValidator.validateRecords(
                listOf(record("a.example"), record("b.example"), record("c.example", category = "NOPE")),
            )
        }

        assertThat(error).hasMessageThat().contains("record #3")
    }

    @Test
    fun `rejects an unknown category`() {
        assertThrows(ReleaseValidationException::class.java) {
            ReleaseValidator.validateRecords(listOf(record(category = "PONSIBLE")))
        }
    }

    @Test
    fun `rejects an unknown status`() {
        assertThrows(ReleaseValidationException::class.java) {
            ReleaseValidator.validateRecords(listOf(record(status = "MAYBE")))
        }
    }

    @Test
    fun `rejects an unknown confidence`() {
        assertThrows(ReleaseValidationException::class.java) {
            ReleaseValidator.validateRecords(listOf(record(confidence = "VERY")))
        }
    }

    @Test
    fun `rejects an unknown risk level`() {
        assertThrows(ReleaseValidationException::class.java) {
            ReleaseValidator.validateRecords(listOf(record(risk = "EXTREME")))
        }
    }

    @Test
    fun `accepts every allowed category`() {
        val categories = listOf(
            "GAMBLING", "CASINO", "SPORTSBOOK", "POKER", "LOTTERY", "BINGO",
            "CRYPTO_GAMBLING", "SKIN_GAMBLING", "AFFILIATE", "PREDICTION_MARKETS", "UNKNOWN",
        )

        val records = categories.mapIndexed { i, c -> record("d$i.example", category = c) }

        assertThat(ReleaseValidator.validateRecords(records)).hasSize(categories.size)
    }

    @Test
    fun `accepts every allowed status`() {
        val statuses = listOf("CANDIDATE", "VERIFIED", "ACTIVE", "ALLOWLISTED", "FALSE_POSITIVE", "DISABLED")

        val records = statuses.mapIndexed { i, s -> record("d$i.example", status = s) }

        assertThat(ReleaseValidator.validateRecords(records)).hasSize(statuses.size)
    }

    @Test
    fun `accepts every allowed confidence and risk level`() {
        val confidences = listOf("HIGH", "MEDIUM", "LOW", "UNKNOWN")
        val risks = listOf("CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN")

        val records = confidences.mapIndexed { i, c ->
            record("d$i.example", confidence = c, risk = risks[i])
        }

        assertThat(ReleaseValidator.validateRecords(records)).hasSize(confidences.size)
    }

    // ---- artifact verification ----

    @Test
    fun `verifies a matching full artifact`() {
        val payload = PayloadCodec.encodeRecords(listOf(record()))

        val result = ReleaseValidator.verifyArtifact(
            FullArtifact(
                fileName = "full.ndjson.gz",
                sha256 = ReleaseValidator.sha256Hex(payload),
                sizeBytes = payload.size.toLong(),
            ),
            payload,
        )

        assertThat(result.ok).isTrue()
    }

    @Test
    fun `rejects a full artifact with a tampered hash`() {
        val payload = PayloadCodec.encodeRecords(listOf(record()))

        val result = ReleaseValidator.verifyArtifact(
            FullArtifact("full.ndjson.gz", "0".repeat(64), payload.size.toLong()),
            payload,
        )

        assertThat(result.ok).isFalse()
        assertThat(result.reason).contains("sha256 mismatch")
    }

    @Test
    fun `rejects a full artifact with a size mismatch before checking the hash`() {
        val payload = PayloadCodec.encodeRecords(listOf(record()))

        val result = ReleaseValidator.verifyArtifact(
            FullArtifact("full.ndjson.gz", "0".repeat(64), payload.size.toLong() + 1),
            payload,
        )

        assertThat(result.reason).contains("size mismatch")
    }

    @Test
    fun `verifies a matching delta artifact`() {
        val payload = DeltaCodec.encode(listOf(DeltaOp.Header(1, 2, 0, 0, 0)))

        val result = ReleaseValidator.verifyArtifact(
            DeltaArtifact(
                baseVersion = 1,
                fileName = "delta.ndjson.gz",
                sha256 = ReleaseValidator.sha256Hex(payload),
                sizeBytes = payload.size.toLong(),
                addedCount = 0,
                removedCount = 0,
                modifiedCount = 0,
            ),
            payload,
        )

        assertThat(result.ok).isTrue()
    }

    @Test
    fun `rejects a delta artifact with a tampered hash`() {
        val payload = DeltaCodec.encode(listOf(DeltaOp.Header(1, 2, 0, 0, 0)))

        val result = ReleaseValidator.verifyArtifact(
            DeltaArtifact(1, "delta.ndjson.gz", "0".repeat(64), payload.size.toLong(), 0, 0, 0),
            payload,
        )

        assertThat(result.ok).isFalse()
        assertThat(result.reason).contains("delta sha256 mismatch")
    }

    @Test
    fun `rejects a delta artifact with a size mismatch`() {
        val payload = DeltaCodec.encode(listOf(DeltaOp.Header(1, 2, 0, 0, 0)))

        val result = ReleaseValidator.verifyArtifact(
            DeltaArtifact(1, "delta.ndjson.gz", ReleaseValidator.sha256Hex(payload), 999L, 0, 0, 0),
            payload,
        )

        assertThat(result.reason).contains("delta size mismatch")
    }

    @Test
    fun `sha256 matches the known empty-string digest`() {
        assertThat(ReleaseValidator.sha256Hex(ByteArray(0)))
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }

    @Test
    fun `sha256 matches the known abc digest`() {
        assertThat(ReleaseValidator.sha256Hex("abc".toByteArray()))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    @Test
    fun `decodeAndValidate returns records for a valid payload`() {
        val payload = PayloadCodec.encodeRecords(listOf(record("ok.example")))

        assertThat(ReleaseValidator.decodeAndValidate(payload)).hasSize(1)
    }

    @Test
    fun `decodeAndValidate rejects a structurally valid but duplicate payload`() {
        val payload = PayloadCodec.encodeRecords(listOf(record("dup.example"), record("dup.example")))

        assertThrows(ReleaseValidationException::class.java) {
            ReleaseValidator.decodeAndValidate(payload)
        }
    }
}