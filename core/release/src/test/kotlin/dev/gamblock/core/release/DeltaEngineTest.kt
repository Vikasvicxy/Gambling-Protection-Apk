package dev.gamblock.core.release

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeltaEngineTest {

    private fun record(domain: String, v: Int) = ReleaseDomainRecord(
        domain = domain,
        normalizedDomain = domain,
        category = "CASINO",
        confidence = "HIGH",
        status = "ACTIVE",
        riskLevel = "MEDIUM",
        sourceIds = listOf("s"),
        firstSeenEpochMs = v.toLong(),
        lastVerifiedEpochMs = v.toLong(),
        databaseVersion = v,
    )

    private fun artifact(base: Int, added: Int, removed: Int, modified: Int) = DeltaArtifact(
        baseVersion = base,
        fileName = "delta.json.gz",
        sha256 = "f".repeat(64),
        sizeBytes = 10,
        addedCount = added,
        removedCount = removed,
        modifiedCount = modified,
    )

    @Test
    fun `plan detects add remove and modify`() {
        val prev = mapOf(
            "a" to record("a", 1),
            "b" to record("b", 1),
            "gone" to record("gone", 1),
        )
        val next = mapOf(
            "a" to record("a", 2), // modified
            "b" to record("b", 1), // unchanged
            "new" to record("new", 2), // added
        )
        val plan = DeltaEngine.plan(prev, next, 1, 2)
        assertThat(plan.addedCount).isEqualTo(1)
        assertThat(plan.removedCount).isEqualTo(1)
        assertThat(plan.modifiedCount).isEqualTo(1)
        assertThat(plan.added.single().normalizedDomain).isEqualTo("new")
        assertThat(plan.removed.single()).isEqualTo("gone")
        assertThat(plan.modified.single().normalizedDomain).isEqualTo("a")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `plan rejects non single hop`() {
        DeltaEngine.plan(mapOf(), mapOf(), 1, 3)
    }

    @Test
    fun `ops round trip through wire format`() {
        val prev = mapOf("a" to record("a", 1), "b" to record("b", 1))
        val next = mapOf(
            "a" to record("a", 2),
            "b" to record("b", 1),
            "c" to record("c", 2),
        )
        val plan = DeltaEngine.plan(prev, next, 1, 2)
        val ops = DeltaEngine.ops(plan, 1, 2)
        val compressed = DeltaCodec.encode(ops)
        val decoded = DeltaCodec.decode(compressed)

        assertThat(decoded.first()).isInstanceOf(DeltaOp.Header::class.java)
        val header = decoded.first() as DeltaOp.Header
        assertThat(header.base).isEqualTo(1)
        assertThat(header.target).isEqualTo(2)
        assertThat(header.added).isEqualTo(1)
        assertThat(header.modified).isEqualTo(1)
        assertThat(header.removed).isEqualTo(0)

        val revalidated = DeltaEngine.validateAndGroup(decoded, artifact(1, added = 1, removed = 0, modified = 1))
        assertThat(revalidated.added.single().normalizedDomain).isEqualTo("c")
    }

    @Test
    fun `applyTo reaches exactly the target set`() {
        val prev = mapOf("a" to record("a", 1), "b" to record("b", 1), "gone" to record("gone", 1))
        val next = mapOf("a" to record("a", 2), "b" to record("b", 1), "new" to record("new", 2))
        val plan = DeltaEngine.plan(prev, next, 1, 2)
        val applied = DeltaEngine.applyTo(prev, plan)
        assertThat(applied).isEqualTo(next)
    }

    @Test(expected = ReleaseValidationException::class)
    fun `count mismatch rejected`() {
        val ops = listOf<DeltaOp>(DeltaOp.Header(1, 2, added = 1, removed = 0, modified = 0))
        DeltaEngine.validateAndGroup(ops, artifact(1, added = 2, removed = 0, modified = 0))
    }

    @Test(expected = ReleaseValidationException::class)
    fun `duplicate remove rejected`() {
        val ops = listOf<DeltaOp>(
            DeltaOp.Header(1, 2, added = 0, removed = 2, modified = 0),
            DeltaOp.Remove("a"),
            DeltaOp.Remove("a"),
        )
        DeltaEngine.validateAndGroup(ops, artifact(1, added = 0, removed = 2, modified = 0))
    }

    @Test(expected = ReleaseValidationException::class)
    fun `missing header rejected`() {
        DeltaEngine.validateAndGroup(listOf(DeltaOp.Remove("a")), artifact(1, added = 0, removed = 1, modified = 0))
    }

    @Test(expected = ReleaseValidationException::class)
    fun `double header rejected`() {
        val ops = listOf<DeltaOp>(
            DeltaOp.Header(1, 2, added = 0, removed = 0, modified = 0),
            DeltaOp.Header(1, 2, added = 0, removed = 0, modified = 0),
        )
        DeltaEngine.validateAndGroup(ops, artifact(1, added = 0, removed = 0, modified = 0))
    }

    @Test(expected = ReleaseValidationException::class)
    fun `header base must match artifact`() {
        val ops = listOf<DeltaOp>(DeltaOp.Header(5, 6, added = 0, removed = 0, modified = 0))
        DeltaEngine.validateAndGroup(ops, artifact(1, added = 0, removed = 0, modified = 0))
    }

    @Test(expected = ReleaseValidationException::class)
    fun `invalid normalized domain rejected`() {
        val badRecord = record("a", 2).copy(normalizedDomain = "-not-a-domain!")
        val ops = listOf<DeltaOp>(DeltaOp.Add(badRecord))
        DeltaEngine.validateAndGroup(ops, artifact(1, added = 1, removed = 0, modified = 0))
    }
}