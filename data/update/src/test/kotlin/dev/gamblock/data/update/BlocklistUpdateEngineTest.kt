package dev.gamblock.data.update

import android.content.Context
import com.google.common.truth.Truth.assertThat
import dev.gamblock.core.database.ShieldDatabase
import dev.gamblock.core.database.dao.DomainDao
import dev.gamblock.core.database.dao.MetaDao
import dev.gamblock.core.database.entity.MetaEntity
import dev.gamblock.core.model.UpdateMeta
import dev.gamblock.core.model.UpdateState
import dev.gamblock.core.release.BuiltRelease
import dev.gamblock.core.release.ReleaseBuilder
import dev.gamblock.core.release.ReleaseCrypto
import dev.gamblock.core.release.ReleaseDomainRecord
import dev.gamblock.core.release.ReleaseVerifier
import dev.gamblock.core.release.TrustedKeyRing
import dev.gamblock.core.release.UpdateChannel
import dev.gamblock.core.testing.FakeWallClock
import dev.gamblock.core.testing.NoOpLogger
import dev.gamblock.core.testing.TestDispatchersProvider
import dev.gamblock.data.blocklist.BlocklistRepository
import dev.gamblock.data.blocklist.SeedBlocklistLoader
import dev.gamblock.data.repository.UpdateRepository
import java.security.KeyPair
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BlocklistUpdateEngineTest {

    private val dispatchers = TestDispatchersProvider(UnconfinedTestDispatcher())
    private val keyPair = ReleaseCrypto.generateKeyPair()
    private val ring = TrustedKeyRing(listOf(keyPair.public))
    private val wrongKeyPair = ReleaseCrypto.generateKeyPair()

    private lateinit var context: Context
    private lateinit var db: ShieldDatabase
    private lateinit var metaDao: MetaDao
    private lateinit var domainDao: DomainDao
    private lateinit var state: UpdateStateRepository
    private lateinit var updateRepository: UpdateRepository
    private lateinit var fetcher: FakeFetcher
    private lateinit var applier: BlocklistApplier

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        db = ShieldDatabase.inMemory(context)
        metaDao = db.metaDao()
        domainDao = db.domainDao()
        state = UpdateStateRepository(metaDao, dispatchers)
        updateRepository = UpdateRepository(metaDao, dispatchers, NoOpLogger)
        applier = buildApplier()
        fetcher = FakeFetcher()
    }

    private fun buildApplier(): BlocklistApplier {
        val loader = SeedBlocklistLoader(context, dispatchers, NoOpLogger)
        val blocklistRepository = BlocklistRepository(
            context,
            db,
            loader,
            dispatchers,
            FakeWallClock(),
            NoOpLogger,
        )
        return BlocklistApplier(db, blocklistRepository, NoOpLogger)
    }

    private fun engine(signingKeySource: SigningKeySource) = BlocklistUpdateEngine(
        context = context,
        config = UpdateConfig(),
        signingKeyProvider = signingKeySource,
        fetcher = fetcher,
        state = state,
        applier = applier,
        updateRepository = updateRepository,
        dispatchers = dispatchers,
        logger = NoOpLogger,
    )

    private fun keyRingSource(): SigningKeySource = object : SigningKeySource {
        override fun keyRingOrNull(): TrustedKeyRing = ring
    }

    private fun noKeySource(): SigningKeySource = object : SigningKeySource {
        override fun keyRingOrNull(): TrustedKeyRing? = null
    }

    private fun records(version: Int, vararg domains: String): List<ReleaseDomainRecord> =
        domains.map { d ->
            ReleaseDomainRecord(
                domain = d,
                normalizedDomain = d,
                category = "GAMBLING",
                confidence = "HIGH",
                status = "ACTIVE",
                riskLevel = "HIGH",
                sourceIds = listOf("test"),
                operatorId = "GAMBLOCK_SEED",
                firstSeenEpochMs = 0L,
                lastVerifiedEpochMs = 0L,
                databaseVersion = version,
                appliesToSubdomains = false,
            )
        }

    private fun buildRelease(
        version: Int,
        previous: List<ReleaseDomainRecord>?,
        next: List<ReleaseDomainRecord>,
        signing: KeyPair = keyPair,
    ): BuiltRelease = ReleaseBuilder.build(
        previousRecords = previous,
        nextRecords = next,
        releaseId = "test-release-v$version",
        version = version,
        channel = UpdateChannel.STABLE,
        generatedAtEpochMs = 1_700_000_000_000L,
        minimumAppVersion = 0,
        signingKey = signing,
    )

    private fun serve(built: BuiltRelease) {
        fetcher.manifest = ReleaseVerifier.encodeEnvelope(built.envelope)
        fetcher.artifacts[built.envelope.manifest.full.fileName] = built.fullPayload
        built.deltaPayload?.let { fetcher.artifacts[built.envelope.manifest.delta!!.fileName] = it }
    }

    // --------------------------------------------------------------- full path

    @Test
    fun `valid full release is applied and persisted with durable facts`() = runTest {
        val built = buildRelease(1, null, records(1, "a.example", "b.example"))
        serve(built)

        val result = engine(keyRingSource()).checkForUpdate()

        assertThat(result).contains("applied full v1")
        assertThat(state.state.value).isEqualTo(UpdateState.UP_TO_DATE)
        assertThat(metaDao.get(BlocklistRepository.KEY_VERSION)).isEqualTo("1")
        assertThat(metaDao.get(UpdateMeta.KEY_ACTIVE_RELEASE_ID)).isEqualTo("test-release-v1")
        assertThat(metaDao.get(UpdateMeta.KEY_DELTA_LAST_APPLIED)).isEqualTo("false")
        assertThat(domainDao.findAll()).hasSize(2)
        assertThat(metaDao.get(BlocklistRepository.KEY_DIGEST)).isNotNull()
        assertThat(metaDao.get(BlocklistRepository.KEY_DIGEST)).isNotEmpty()
    }

    @Test
    fun `wrong signing key rejects signature and leaves database untouched`() = runTest {
        val built = buildRelease(1, null, records(1, "a.example"), signing = wrongKeyPair)
        serve(built)

        val result = engine(keyRingSource()).checkForUpdate()

        assertThat(result).contains("signature rejected")
        assertThat(state.state.value).isEqualTo(UpdateState.FAILED)
        assertThat(metaDao.get(BlocklistRepository.KEY_VERSION)).isNull()
        assertThat(domainDao.findAll()).isEmpty()
    }

    @Test
    fun `manifest parse failure is a reported failure not an exception`() = runTest {
        fetcher.manifest = "{ not valid json ]"

        val result = engine(keyRingSource()).checkForUpdate()

        assertThat(result).contains("manifest parse failed")
        assertThat(state.state.value).isEqualTo(UpdateState.FAILED)
        assertThat(domainDao.findAll()).isEmpty()
    }

    @Test
    fun `corrupt artifact fails hash verification and keeps last known good`() = runTest {
        val built = buildRelease(1, null, records(1, "a.example"))
        fetcher.manifest = ReleaseVerifier.encodeEnvelope(built.envelope)
        fetcher.artifacts[built.envelope.manifest.full.fileName] = built.fullPayload + byteArrayOf(0x01)

        val result = engine(keyRingSource()).checkForUpdate()

        assertThat(result).contains("full verify failed")
        assertThat(state.state.value).isEqualTo(UpdateState.FAILED)
        assertThat(domainDao.findAll()).isEmpty()
    }

    @Test
    fun `downgrade without signed rollback is refused and keeps installed version`() = runTest {
        // Simulate an already-applied v2 without ever touching the network pipeline.
        metaDao.put(MetaEntity(BlocklistRepository.KEY_VERSION, "2"))
        val version1 = buildRelease(1, null, records(1, "a.example"))
        serve(version1)

        val result = engine(keyRingSource()).checkForUpdate()

        assertThat(result).contains("already up to date")
        assertThat(metaDao.get(BlocklistRepository.KEY_VERSION)).isEqualTo("2")
        assertThat(domainDao.findAll()).isEmpty()
    }

    @Test
    fun `missing signing key asset marks update failed`() = runTest {
        val result = engine(noKeySource()).checkForUpdate()

        assertThat(result).contains("signing key unavailable")
        assertThat(state.state.value).isEqualTo(UpdateState.FAILED)
    }

    @Test
    fun `corrupt delta with valid full falls back to full and applies`() = runTest {
        val v1 = buildRelease(1, null, records(1, "a.example", "b.example"))
        serve(v1)
        val first = engine(keyRingSource()).checkForUpdate()
        assertThat(first).contains("applied full v1")

        val v2 = buildRelease(2, records(1, "a.example", "b.example"), records(2, "a.example", "c.example"))
        assertThat(v2.envelope.manifest.delta).isNotNull()
        serve(v2)
        // Corrupt only the delta bytes; the full artifact stays intact.
        fetcher.artifacts[v2.envelope.manifest.delta!!.fileName] =
            fetcher.artifacts.getValue(v2.envelope.manifest.delta!!.fileName) + byteArrayOf(0x7f)

        val result = engine(keyRingSource()).checkForUpdate()

        assertThat(result).contains("applied full v2")
        assertThat(state.state.value).isEqualTo(UpdateState.UP_TO_DATE)
        assertThat(metaDao.get(BlocklistRepository.KEY_VERSION)).isEqualTo("2")
        assertThat(metaDao.get(UpdateMeta.KEY_DELTA_LAST_APPLIED)).isEqualTo("false")
        val rows = domainDao.findAll().map { it.normalizedDomain }.sorted()
        assertThat(rows).containsExactly("a.example", "c.example")
    }

    @Test
    fun `corrupt delta and corrupt full preserve previous good v1`() = runTest {
        val v1 = buildRelease(1, null, records(1, "a.example", "b.example"))
        serve(v1)
        val first = engine(keyRingSource()).checkForUpdate()
        assertThat(first).contains("applied full v1")

        val v2 = buildRelease(2, records(1, "a.example", "b.example"), records(2, "a.example", "c.example"))
        serve(v2)
        fetcher.artifacts[v2.envelope.manifest.delta!!.fileName] =
            fetcher.artifacts.getValue(v2.envelope.manifest.delta!!.fileName) + byteArrayOf(0x7f)
        fetcher.artifacts[v2.envelope.manifest.full.fileName] =
            fetcher.artifacts.getValue(v2.envelope.manifest.full.fileName) + byteArrayOf(0x7f)

        val result = engine(keyRingSource()).checkForUpdate()

        assertThat(result).contains("full verify failed")
        assertThat(state.state.value).isEqualTo(UpdateState.FAILED)
        assertThat(metaDao.get(BlocklistRepository.KEY_VERSION)).isEqualTo("1")
        assertThat(metaDao.get(UpdateMeta.KEY_ACTIVE_RELEASE_ID)).isEqualTo("test-release-v1")
        val rows = domainDao.findAll().map { it.normalizedDomain }.sorted()
        assertThat(rows).containsExactly("a.example", "b.example")
    }

    @Test
    fun `empty verified release is refused so protection is never wiped`() = runTest {
        val v2 = buildRelease(2, null, emptyList())
        serve(v2)

        val result = engine(keyRingSource()).checkForUpdate()

        assertThat(result).contains("apply failed")
        assertThat(result).contains("empty blocklist")
        assertThat(state.state.value).isEqualTo(UpdateState.FAILED)
        assertThat(metaDao.get(BlocklistRepository.KEY_VERSION)).isNull()
        assertThat(domainDao.findAll()).isEmpty()
    }

    // --------------------------------------------------------------- delta path

    @Test
    fun `delta release applies over matching base and persists merged rows`() = runTest {
        val v1 = buildRelease(1, null, records(1, "a.example", "b.example"))
        serve(v1)
        val first = engine(keyRingSource()).checkForUpdate()
        assertThat(first).contains("applied full v1")

        val v2 = buildRelease(2, records(1, "a.example", "b.example"), records(2, "a.example", "c.example"))
        assertThat(v2.envelope.manifest.delta).isNotNull()
        serve(v2)

        val result = engine(keyRingSource()).checkForUpdate()

        assertThat(result).contains("applied delta v2 from v1")
        assertThat(state.state.value).isEqualTo(UpdateState.UP_TO_DATE)
        assertThat(metaDao.get(BlocklistRepository.KEY_VERSION)).isEqualTo("2")
        assertThat(metaDao.get(UpdateMeta.KEY_DELTA_LAST_APPLIED)).isEqualTo("true")
        val rows = domainDao.findAll().map { it.normalizedDomain }.sorted()
        assertThat(rows).containsExactly("a.example", "c.example")
    }

    /** In-memory fake network transport used by the [BlocklistUpdateEngine] tests. */
    class FakeFetcher : UpdateFetcher {
        var manifest: String? = null
        val artifacts = mutableMapOf<String, ByteArray>()

        /** Artifacts published by exactly one origin, to model a partially deployed mirror. */
        val originArtifacts = mutableMapOf<String, MutableMap<String, ByteArray>>()

        /** Origins that behave as if unreachable (404, DNS failure, timeout...). */
        val deadOrigins = mutableSetOf<String>()

        /** Origins whose manifest body is a 200 carrying something that is not an envelope. */
        val garbageOrigins = mutableSetOf<String>()

        /** Every URL requested, in order, so tests can assert on failover behaviour. */
        val requestedUrls = mutableListOf<String>()

        fun publishOnlyAt(origin: String, name: String, bytes: ByteArray) {
            originArtifacts.getOrPut(origin) { mutableMapOf() }[name] = bytes
        }

        override suspend fun fetch(config: UpdateConfig, url: String, maxBytes: Long): ByteArray {
            requestedUrls += url
            val base = config.candidateBaseUrls().firstOrNull { url.startsWith(it) }
                ?: throw DownloadException("unexpected origin for '$url'")
            if (base in deadOrigins) throw DownloadException("http 404 from $base")

            val name = url.removePrefix(base)
            if (name == "manifest.json") {
                if (base in garbageOrigins) return "{ not a release envelope }".encodeToByteArray()
                val local = manifest ?: throw DownloadException("http 404 from $base")
                return local.encodeToByteArray()
            }
            originArtifacts[base]?.get(name)?.let { return it }
            return artifacts[name] ?: throw DownloadException("http 404 from $base for $name")
        }
    }

    // ------------------------------------------------------------ origin failover

    private val primary = UpdateConfig().candidateBaseUrls().first()
    private val second = UpdateConfig().candidateBaseUrls()[1]

    @Test
    fun `dead primary origin fails over to a mirror and still applies`() = runTest {
        val built = buildRelease(1, null, records(1, "a.example"))
        serve(built)
        fetcher.deadOrigins += primary

        val result = engine(keyRingSource()).checkForUpdate()

        assertThat(result).contains("applied full v1")
        assertThat(domainDao.findAll()).hasSize(1)
        assertThat(fetcher.requestedUrls.first()).startsWith(primary)
        assertThat(fetcher.requestedUrls.filter { it.startsWith(second) }).isNotEmpty()
    }

    @Test
    fun `manifest that is not an envelope on the primary falls through to a mirror`() = runTest {
        val built = buildRelease(1, null, records(1, "a.example"))
        serve(built)
        fetcher.garbageOrigins += primary

        val result = engine(keyRingSource()).checkForUpdate()

        assertThat(result).contains("applied full v1")
        assertThat(domainDao.findAll()).hasSize(1)
    }

    @Test
    fun `every origin returning garbage reports a parse failure rather than applying`() = runTest {
        fetcher.garbageOrigins += UpdateConfig().candidateBaseUrls()

        val result = engine(keyRingSource()).checkForUpdate()

        assertThat(result).contains("manifest parse failed")
        assertThat(state.state.value).isEqualTo(UpdateState.FAILED)
        assertThat(domainDao.findAll()).isEmpty()
    }

    @Test
    fun `every origin dead keeps the last known good database and applies nothing`() = runTest {
        // v1 already live from a previous successful check.
        val first = buildRelease(1, null, records(1, "keep.example"))
        serve(first)
        engine(keyRingSource()).checkForUpdate()
        assertThat(domainDao.findAll()).hasSize(1)

        // Next release exists but no origin can serve it.
        val next = buildRelease(2, records(1, "keep.example"), records(2, "keep.example", "new.example"))
        serve(next)
        fetcher.deadOrigins += UpdateConfig().candidateBaseUrls()

        val result = engine(keyRingSource()).checkForUpdate()

        assertThat(result).contains("manifest fetch failed")
        assertThat(state.state.value).isEqualTo(UpdateState.FAILED)
        // Protection continues on the previously verified release.
        assertThat(domainDao.findAll()).hasSize(1)
        assertThat(domainDao.findAll().map { it.domain }).containsExactly("keep.example")
        assertThat(metaDao.get(BlocklistRepository.KEY_VERSION)).isEqualTo("1")
    }

    @Test
    fun `artifact only present on a mirror is fetched from there`() = runTest {
        val built = buildRelease(1, null, records(1, "a.example"))
        // The manifest origin advertises the release but its copy of the payload is missing,
        // which is what a half-finished deployment actually looks like.
        fetcher.manifest = ReleaseVerifier.encodeEnvelope(built.envelope)
        fetcher.publishOnlyAt(second, built.envelope.manifest.full.fileName, built.fullPayload)

        val result = engine(keyRingSource()).checkForUpdate()

        assertThat(result).contains("applied full v1")
        assertThat(domainDao.findAll()).hasSize(1)
        // The mirror origin was consulted for the payload.
        assertThat(fetcher.requestedUrls.filter { it == "${second}${built.envelope.manifest.full.fileName}" })
            .isNotEmpty()
    }

    @Test
    fun `a mirror serving a forged manifest is still rejected by signature`() = runTest {
        // The primary origin is dead, so the only content on offer comes from mirrors - and that
        // content is a well-formed envelope with correct hashes, signed by a key we do not trust.
        // Availability is widened without widening trust: it must still be refused.
        val forged = buildRelease(9, null, records(9, "evil.example"), signing = wrongKeyPair)
        serve(forged)
        fetcher.deadOrigins += primary

        val result = engine(keyRingSource()).checkForUpdate()

        assertThat(result).contains("signature rejected")
        assertThat(state.state.value).isEqualTo(UpdateState.FAILED)
        assertThat(domainDao.findAll()).isEmpty()
        // The forgery really did come off the wire, so this is a rejection and not a skip.
        assertThat(fetcher.requestedUrls.filter { it.endsWith("manifest.json") }.size).isAtLeast(2)
    }

    @Test
    fun `candidate origins are https only and de-duplicated`() {
        val config = UpdateConfig(
            baseUrl = "https://primary.example/cdn/",
            fallbackBaseUrls = listOf(
                "https://primary.example/cdn/",
                "https://mirror.example/cdn",
                "http://insecure.example/cdn/",
                "   ",
            ),
        )

        assertThat(config.candidateBaseUrls()).containsExactly(
            "https://primary.example/cdn/",
            "https://mirror.example/cdn/",
        ).inOrder()
        assertThat(config.manifestUrls()).containsExactly(
            "https://primary.example/cdn/manifest.json",
            "https://mirror.example/cdn/manifest.json",
        ).inOrder()
        assertThat(config.artifactUrls("full.json")).containsExactly(
            "https://primary.example/cdn/full.json",
            "https://mirror.example/cdn/full.json",
        ).inOrder()
    }

    @Test
    fun `a base without a trailing slash still yields well formed urls`() {
        val config = UpdateConfig(baseUrl = "https://primary.example/cdn", fallbackBaseUrls = emptyList())

        assertThat(config.candidateBaseUrls()).containsExactly("https://primary.example/cdn/")
        assertThat(config.manifestUrls()).containsExactly("https://primary.example/cdn/manifest.json")
        assertThat(config.manifestUrl).isEqualTo("https://primary.example/cdn/manifest.json")
    }
}